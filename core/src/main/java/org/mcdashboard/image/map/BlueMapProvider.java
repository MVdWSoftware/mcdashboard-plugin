/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.image.map;

/**
 * BlueMap, through its low-resolution tiles only (the high-resolution ones are 3D models in {@code .prbm}):
 * {@code maps/<map>/tiles/<lod>/x<digits>z<digits>.png} where each digit of a coordinate is its own folder
 * ({@code x=-12, z=3} gives {@code x-1/2/z3}). LOD 1 is one pixel per block in tiles of {@code lowres.tile-size}
 * (default 500) blocks; every further LOD covers {@code lod-factor} (default 5) times more blocks per pixel, up to
 * {@code lod-count} (default 3). The PNG is {@code (size + 1) x 2 * (size + 1)} pixels: the colours on top (the extra
 * column and row repeat the neighbour for seamless edges) and height and light data in the bottom half, which is
 * ignored here; so the picture is flat colour without BlueMap's shading. +X right, +Z down. The map's default
 * settings are assumed (tile size 500, lod factor 5).
 * Verified against the source (2026-11):
 * https://github.com/BlueMap-Minecraft/BlueMap/blob/master/common/webapp/src/js/map/LowresTileLoader.js,
 * https://github.com/BlueMap-Minecraft/BlueMap/blob/master/common/webapp/src/js/map/Map.js (loadMapArea),
 * https://github.com/BlueMap-Minecraft/BlueMap/blob/master/common/webapp/src/js/util/Utils.js (pathFromCoords),
 * https://github.com/BlueMap-Minecraft/BlueMap/blob/master/core/src/main/java/de/bluecolored/bluemap/core/map/lowres/LowresTile.java,
 * https://github.com/BlueMap-Minecraft/BlueMap/blob/master/core/src/main/java/de/bluecolored/bluemap/core/map/lowres/LowresLayer.java,
 * https://github.com/BlueMap-Minecraft/BlueMap/blob/master/common/src/main/java/de/bluecolored/bluemap/common/config/MapConfig.java,
 * https://github.com/BlueMap-Minecraft/BlueMap/blob/master/common/src/main/java/de/bluecolored/bluemap/common/web/MapStorageRequestHandler.java
 */
final class BlueMapProvider implements TileProvider {
    static final int DEFAULT_MAX_ZOOM = 2;
    static final int TILE_SIZE = 500;
    static final int LOD_FACTOR = 5;
    private final String map;
    private final int maxZoom;

    BlueMapProvider(String map, int maxZoom) {
        this.map = map;
        this.maxZoom = maxZoom;
    }

    @Override
    public String id() {
        return "bluemap";
    }

    @Override
    public int tileSize() {
        return TILE_SIZE;
    }

    @Override
    public int maxZoom() {
        return maxZoom;
    }

    @Override
    public double[] pixel(double x, double z, int zoom) {
        double scale = Math.pow(LOD_FACTOR, zoom);
        return new double[]{x / scale, z / scale};
    }

    @Override
    public String tilePath(int i, int j, int zoom) {
        return "maps/" + map + "/tiles/" + (zoom + 1) + "/" + pathFromCoords(i, j) + ".png";
    }

    /** BlueMap's {@code pathFromCoords}: {@code x} and {@code z}, each digit followed by a slash, without the last one. */
    static String pathFromCoords(int x, int z) {
        String path = "x" + split(x) + "z" + split(z);
        return path.substring(0, path.length() - 1);
    }

    private static String split(int n) {
        StringBuilder b = new StringBuilder();
        String s = Long.toString(Math.abs((long) n));
        if (n < 0) b.append('-');
        for (int i = 0; i < s.length(); i++) b.append(s.charAt(i)).append('/');
        return b.toString();
    }
}
