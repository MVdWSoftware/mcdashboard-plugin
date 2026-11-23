/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.qr;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * A filled map that shows a QR code, so a player can scan it with the app straight from the screen. The map is
 * rendered per viewer and only draws the code for the player it was made for: anyone else who sees it (item frame,
 * dropped item, another player) just sees a blank map. The code carries the MCDashboard logo in a light frame in the
 * middle; {@link QrMapLayout} lays the picture out and keeps the hole inside the error correction.
 *
 * <p>Because the QR code is a pairing secret, the item is deliberately short-lived: it carries its owner and an
 * expiry in its persistent data, {@link QrMapGuard} destroys it when it leaves the owner's inventory (drop, chest,
 * hopper, item frame, death, logout) and a repeating task destroys it when the code expires.
 */
public final class QrMap {
    private static final NamespacedKey MARKER = new NamespacedKey("mcdashboard", "qr-map");
    private static final NamespacedKey OWNER = new NamespacedKey("mcdashboard", "qr-owner");
    private static final NamespacedKey EXPIRES = new NamespacedKey("mcdashboard", "qr-expires");

    private QrMap() {
    }

    /**
     * Creates the map item for {@code owner}, valid until {@code expiresAtMs}. Must run on the main thread.
     *
     * @param owner The player who will own and be able to read this QR map.
     * @param text The URL or code encoded in the QR code.
     * @param expiresAtMs The epoch millisecond when this map stops being valid.
     * @return A filled map ItemStack containing the QR code.
     */
    public static ItemStack create(Player owner, String text, long expiresAtMs) {
        QrCode qr = QrCode.encode(text);
        MapView view = Bukkit.createMap(owner.getWorld());
        view.getRenderers().forEach(view::removeRenderer);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.addRenderer(new Renderer(qr, owner.getUniqueId()));
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof MapMeta map) {
            map.setMapView(view);
            map.setDisplayName("§aMCDashboard link §7(scan with the app)");
            long minutes = Math.max(1, (expiresAtMs - System.currentTimeMillis() + 59_999) / 60_000);
            map.setLore(List.of(
                    "§7Only you can read this.",
                    "§7It self-destructs in " + minutes + " min or when dropped or stored."));
            map.getPersistentDataContainer().set(MARKER, PersistentDataType.BYTE, (byte) 1);
            map.getPersistentDataContainer().set(OWNER, PersistentDataType.STRING, owner.getUniqueId().toString());
            map.getPersistentDataContainer().set(EXPIRES, PersistentDataType.LONG, expiresAtMs);
            item.setItemMeta(map);
        }
        return item;
    }

    /**
     * Whether this item is one of our QR maps (not just any filled map).
     *
     * @param item The item stack to check.
     * @return True if this is a QrMap, false otherwise.
     */
    public static boolean isQrMap(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        if (meta.getPersistentDataContainer().has(MARKER, PersistentDataType.BYTE)) return true;
        // Maps created by an older plugin version have no marker; their display name still identifies them.
        String name = meta.getDisplayName();
        return name != null && (name.contains("MCDashboard link") || name.contains("MinecraftDashboard link"));
    }

    /**
     * The owner stored in the map, or null when it is not one of ours or the data is unreadable.
     *
     * @param item The QR map item stack.
     * @return The owner's UUID, or null if not a valid map.
     */
    public static UUID ownerOf(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return null;
        String raw = meta.getPersistentDataContainer().get(OWNER, PersistentDataType.STRING);
        if (raw == null) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * When the map stops being valid, or 0 when it is not tagged.
     *
     * @param item The QR map item stack.
     * @return The expiry epoch millisecond, or 0 if not tagged.
     */
    public static long expiresAt(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return 0;
        Long value = meta.getPersistentDataContainer().get(EXPIRES, PersistentDataType.LONG);
        return value == null ? 0 : value;
    }

    /**
     * Removes every QR map from the player's inventory and cursor.
     *
     * @param player The player whose inventory to clear.
     * @param quiet True to suppress the destruction message.
     * @return True if any maps were removed.
     */
    public static boolean destroyFor(Player player, boolean quiet) {
        boolean removed = false;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isQrMap(contents[i])) {
                player.getInventory().setItem(i, null);
                removed = true;
            }
        }
        if (isQrMap(player.getItemOnCursor())) {
            player.setItemOnCursor(new ItemStack(Material.AIR));
            removed = true;
        }
        if (removed && !quiet) player.sendMessage("§7The MCDashboard QR map was destroyed.");
        return removed;
    }

    /**
     * Removes maps that expired, or that no longer belong to this player (for example after an inventory handover).
     *
     * @param player The player whose inventory to check.
     * @return True if any expired maps were removed.
     */
    public static boolean destroyExpired(Player player) {
        boolean removed = false;
        long now = System.currentTimeMillis();
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (isQrMap(item) && (expiresAt(item) <= now || !player.getUniqueId().equals(ownerOf(item)))) {
                player.getInventory().setItem(i, null);
                removed = true;
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (isQrMap(cursor) && (expiresAt(cursor) <= now || !player.getUniqueId().equals(ownerOf(cursor)))) {
            player.setItemOnCursor(new ItemStack(Material.AIR));
            removed = true;
        }
        if (removed) player.sendMessage("§7The MCDashboard QR map self-destructed.");
        return removed;
    }

    private static final class Renderer extends MapRenderer {
        private final QrCode qr;
        private final UUID owner;
        private final QrMapLayout layout;

        Renderer(QrCode qr, UUID owner) {
            super(true); // contextual: every viewer gets their own canvas
            this.qr = qr;
            this.owner = owner;
            this.layout = new QrMapLayout(qr.size());
        }

        @SuppressWarnings("deprecation") // matchColor is the way to get palette bytes on 1.18
        private static byte lightByte() {
            return MapPalette.matchColor(255, 255, 255);
        }

        @SuppressWarnings("deprecation")
        private static byte darkByte() {
            return MapPalette.matchColor(0, 0, 0);
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player viewer) {
            boolean mine = viewer.getUniqueId().equals(owner);
            byte light = lightByte();
            byte dark = darkByte();
            for (int y = 0; y < QrMapLayout.MAP_SIZE; y++) {
                for (int x = 0; x < QrMapLayout.MAP_SIZE; x++) {
                    canvas.setPixel(x, y, mine && layout.dark(qr, x, y) ? dark : light);
                }
            }
            if (!mine) return; // everyone else sees a blank map, logo included
            // The logo sits in a light frame so it reads as a badge and not as part of the code.
            for (int y = 0; y < QrMapLayout.MAP_SIZE; y++) {
                for (int x = 0; x < QrMapLayout.MAP_SIZE; x++) {
                    if (layout.logo(x, y)) canvas.setPixel(x, y, light);
                }
            }
            drawLogo(canvas);
        }

        private void drawLogo(MapCanvas canvas) {
            byte[] pixels = Logo.pixels(layout.logoSize());
            if (pixels == null) return;
            int size = layout.logoSize();
            int start = layout.logoStart();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    canvas.setPixel(start + x, start + y, pixels[y * size + x]);
                }
            }
        }
    }

    /** The app icon ({@code mcdashboard-app/app/resources/icon.png}), read once from the jar and scaled to each size. */
    private static final class Logo {
        private static final BufferedImage SOURCE = read();
        private static final Map<Integer, byte[]> CACHE = new HashMap<>();

        private static BufferedImage read() {
            try (InputStream in = QrMap.class.getResourceAsStream("/org/mcdashboard/qr/qr-logo.png")) {
                if (in == null) throw new IOException("qr-logo.png is missing from the jar");
                BufferedImage image = ImageIO.read(in);
                if (image == null) throw new IOException("qr-logo.png is not a readable image");
                return image;
            } catch (IOException | RuntimeException e) {
                // A broken or missing asset must not break the map; it is still scannable without the logo.
                Bukkit.getLogger().log(Level.WARNING, "MCDashboard could not load its QR map logo", e);
                return null;
            }
        }

        @SuppressWarnings("deprecation") // matchColor is the way to get palette bytes on 1.18
        private static byte[] pixels(int size) {
            if (SOURCE == null) return null;
            synchronized (CACHE) {
                byte[] cached = CACHE.get(size);
                if (cached != null) return cached;
                BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = scaled.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                            RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    graphics.drawImage(SOURCE, 0, 0, size, size, null);
                } finally {
                    graphics.dispose();
                }
                byte[] pixels = new byte[size * size];
                for (int y = 0; y < size; y++) {
                    for (int x = 0; x < size; x++) {
                        int rgb = scaled.getRGB(x, y);
                        pixels[y * size + x] = MapPalette.matchColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF,
                                rgb & 0xFF);
                    }
                }
                CACHE.put(size, pixels);
                return pixels;
            }
        }
    }
}
