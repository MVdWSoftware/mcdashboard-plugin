/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.image.map;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dynmap 3 HD maps (every map of the default templates: {@code flat}, {@code surface}, {@code cave}) with tiles served
 * under {@code tiles/}: the internal web server, or a web server in front of the file tree storage. SQL storage
 * behind the standalone PHP scripts is not supported.
 *
 * <p>Tile path: {@code tiles/<world>/<prefix>/<x >> 5>_<y >> 5>/<zoom prefix><x>_<y>.<png|jpg>}, 128 pixel tiles (the
 * default {@code tilescale} 0). The map's perspective turns a block into map coordinates with dynmap's own
 * {@code world_to_map} matrix (ported from {@code IsoHDPerspective} and {@code Matrix3D}); tile {@code y} grows
 * upwards, so the web map's (downward) row {@code r} is file tile {@code -r}. Zoomed out level {@code k} tiles are named
 * {@code "z" * k + "_"} plus the coordinates of their first base tile, which are multiples of {@code 2^k}.
 * Isometric perspectives draw the column above a block up and to the side, so the picture is centred on the point of
 * the block at height {@code y} (default 64).
 *
 * <p>Verified against the source (webbukkit/dynmap, branch v3.0, 2026-11):
 * https://github.com/webbukkit/dynmap/blob/v3.0/DynmapCore/src/main/resources/extracted/web/js/hdmap.js (getTileName, projection),
 * https://github.com/webbukkit/dynmap/blob/v3.0/DynmapCore/src/main/resources/extracted/web/js/dynmaputils.js (getTileInfo),
 * https://github.com/webbukkit/dynmap/blob/v3.0/DynmapCore/src/main/java/org/dynmap/hdmap/IsoHDPerspective.java (world_to_map, getTileCoords),
 * https://github.com/webbukkit/dynmap/blob/v3.0/DynmapCore/src/main/java/org/dynmap/utils/Matrix3D.java,
 * https://github.com/webbukkit/dynmap/blob/v3.0/DynmapCore/src/main/java/org/dynmap/storage/filetree/FileTreeMapStorage.java (paths, getZoomOutTile),
 * https://github.com/webbukkit/dynmap/blob/v3.0/DynmapCore/src/main/resources/perspectives.txt,
 * https://github.com/webbukkit/dynmap/blob/v3.0/DynmapCore/src/main/resources/templates/normal.txt
 */
final class DynmapProvider implements TileProvider {
    static final int DEFAULT_MAX_ZOOM = 8;
    static final String DEFAULT_PERSPECTIVE = "iso_S_90_lowres";
    static final int TILE = 128;

    /** Perspective names of dynmap's {@code perspectives.txt}: {@code iso_<direction>_<inclination>_<resolution>}. */
    static final Pattern PERSPECTIVE = Pattern.compile("iso_(N|NE|E|SE|S|SW|W|NW)_(\\d{2})_(vlowres|lowres|medres|hires)");
    private static final Map<String, Integer> AZIMUTH = Map.of("N", 0, "NE", 45, "E", 90, "SE", 135, "S", 180, "SW", 225,
            "W", 270, "NW", 315);
    private static final Map<String, Integer> SCALE = Map.of("vlowres", 2, "lowres", 4, "medres", 8, "hires", 16);

    private final String world;
    private final String prefix;
    private final String format;
    private final int maxZoom;
    private final double y;
    /** {@code world_to_map}, rows m11..m33. */
    private final double[] m;

    DynmapProvider(String world, String prefix, String perspective, double y, String format, int maxZoom) {
        this.world = world;
        this.prefix = prefix;
        this.format = format;
        this.maxZoom = maxZoom;
        this.y = y;
        this.m = worldToMap(perspective);
    }

    /** Throws {@link IllegalArgumentException} for a name that is not one of dynmap's standard perspectives. */
    static double[] worldToMap(String perspective) {
        double azimuth;
        double inclination;
        double scale;
        if (perspective.equals("default")) {
            azimuth = 135;
            inclination = 60;
            scale = 4;
        } else {
            Matcher x = PERSPECTIVE.matcher(perspective);
            if (!x.matches()) {
                throw new IllegalArgumentException("unknown dynmap perspective '" + perspective + "' (expected e.g. iso_S_90_lowres)");
            }
            azimuth = AZIMUTH.get(x.group(1));
            inclination = Integer.parseInt(x.group(2));
            scale = SCALE.get(x.group(3).toLowerCase(Locale.ROOT));
            if (inclination < 20 || inclination > 90) throw new IllegalArgumentException("dynmap inclination must be 20-90");
        }
        // IsoHDPerspective's constructor: azimuth is stored as 90 + the configured value.
        double az = 90.0 + azimuth;
        double[] t = {0, 0, -1, -1, 0, 0, 0, 1, 0};
        t = rotateXY(t, 180 - az);
        t = rotateYZ(t, 90.0 - inclination);
        t = multiply(t, new double[]{1, 0, 0, 0, 1, 0, 0, Math.tan(Math.toRadians(90.0 - inclination)), 1}); // shearZ(0, tan)
        t = multiply(t, new double[]{scale, 0, 0, 0, scale, 0, 0, 0, Math.sin(Math.toRadians(inclination))});
        for (int i = 0; i < t.length; i++) t[i] = Math.abs(t[i]) < 1e-9 ? 0 : t[i]; // cos(90°) is not quite 0
        return t;
    }

    private static double[] rotateXY(double[] t, double deg) {
        double s = Math.sin(Math.toRadians(deg));
        double c = Math.cos(Math.toRadians(deg));
        return multiply(t, new double[]{c, s, 0, -s, c, 0, 0, 0, 1});
    }

    private static double[] rotateYZ(double[] t, double deg) {
        double s = Math.sin(Math.toRadians(deg));
        double c = Math.cos(Math.toRadians(deg));
        return multiply(t, new double[]{1, 0, 0, 0, c, s, 0, -s, c});
    }

    /** {@code Matrix3D.multiply(mat)}: the result is {@code mat x this}. */
    private static double[] multiply(double[] t, double[] mat) {
        double[] r = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                r[row * 3 + col] = mat[row * 3] * t[col] + mat[row * 3 + 1] * t[3 + col] + mat[row * 3 + 2] * t[6 + col];
            }
        }
        return r;
    }

    /** Map coordinates {@code {x, y}} of a world position (dynmap: x right, y up, one unit per tile pixel at zoom 0). */
    double[] toMap(double x, double y, double z) {
        return new double[]{m[0] * x + m[1] * y + m[2] * z, m[3] * x + m[4] * y + m[5] * z};
    }

    @Override
    public String id() {
        return "dynmap";
    }

    @Override
    public int tileSize() {
        return TILE;
    }

    @Override
    public int maxZoom() {
        return maxZoom;
    }

    @Override
    public double[] pixel(double x, double z, int zoom) {
        double[] p = toMap(x, y, z);
        double scale = 1 << zoom;
        // hdmap.js fromLocationToLatLng: the web map's row is (128 - map y), downwards.
        return new double[]{p[0] / scale, (TILE - p[1]) / scale};
    }

    @Override
    public String tilePath(int i, int j, int zoom) {
        int x = i << zoom;
        int y = -(j << zoom);
        String zoomPrefix = zoom == 0 ? "" : "z".repeat(zoom) + "_";
        return "tiles/" + world + "/" + prefix + "/" + (x >> 5) + "_" + (y >> 5) + "/" + zoomPrefix + x + "_" + y + "." + format;
    }
}
