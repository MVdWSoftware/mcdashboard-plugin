package org.mcdashboard.qr;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.UUID;

/**
 * A filled map that shows a QR code, so a player can scan it with the app straight from the screen. The map is
 * rendered per viewer and only draws the code for the player it was made for: anyone else who sees it (item frame,
 * dropped item, another player) just sees a blank map. The code inside is single-use and short-lived anyway.
 */
public final class QrMap {
    private QrMap() {
    }

    /** Creates the map item for {@code owner}. Must run on the main thread. */
    public static ItemStack create(Player owner, String text) {
        QrCode qr = QrCode.encode(text);
        MapView view = Bukkit.createMap(owner.getWorld());
        view.getRenderers().forEach(view::removeRenderer);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.addRenderer(new Renderer(qr, owner.getUniqueId()));
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        if (meta != null) {
            meta.setMapView(view);
            meta.setDisplayName("§aMinecraftDashboard link §7(scan with the app)");
            item.setItemMeta(meta);
        }
        return item;
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
