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

import java.util.List;
import java.util.UUID;

/**
 * A filled map that shows a QR code, so a player can scan it with the app straight from the screen. The map is
 * rendered per viewer and only draws the code for the player it was made for: anyone else who sees it (item frame,
 * dropped item, another player) just sees a blank map.
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

    /** Creates the map item for {@code owner}, valid until {@code expiresAtMs}. Must run on the main thread. */
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

    /** Whether this item is one of our QR maps (not just any filled map). */
    public static boolean isQrMap(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(MARKER, PersistentDataType.BYTE);
    }

    /** The owner stored in the map, or null when it is not one of ours or the data is unreadable. */
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

    /** When the map stops being valid, or 0 when it is not tagged. */
    public static long expiresAt(ItemStack item) {
        ItemMeta meta = item == null ? null : item.getItemMeta();
        if (meta == null) return 0;
        Long value = meta.getPersistentDataContainer().get(EXPIRES, PersistentDataType.LONG);
        return value == null ? 0 : value;
    }

    /**
     * Removes every QR map from the player's inventory and cursor.
     * @param player - The player.
     * @param quiet - Do not tell the player.
     * @returns Whether anything was removed.
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
     * @param player - The player.
     * @returns Whether anything was removed.
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

        Renderer(QrCode qr, UUID owner) {
            super(true); // contextual: every viewer gets their own canvas
            this.qr = qr;
            this.owner = owner;
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
            int quiet = 4;
            int scale = Math.max(1, 128 / (qr.size() + 2 * quiet));
            int offset = (128 - (qr.size() + 2 * quiet) * scale) / 2;
            byte light = lightByte();
            byte dark = darkByte();
            for (int y = 0; y < 128; y++) {
                for (int x = 0; x < 128; x++) {
                    int mx = Math.floorDiv(x - offset, scale) - quiet;
                    int my = Math.floorDiv(y - offset, scale) - quiet;
                    canvas.setPixel(x, y, mine && qr.dark(mx, my) ? dark : light);
                }
            }
        }
    }
}
