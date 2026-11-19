/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.image.map;

/**
 * squaremap (jpenilla/squaremap): {@code tiles/<world>/<zoom>/<x>_<z>.png}, 512 pixel tiles, top-down, +X east (right),
 * +Z south (down). The most detailed level is the map's {@code map.zoom.maximum} (default 3) where one tile is one
 * region (512 x 512 blocks, one pixel per block); each level out halves the scale ({@code TileCoordinate.parent()} is a
 * floorDiv by 2). {@code <world>} is the dimension key with {@code :} replaced by {@code _}, e.g. {@code minecraft_overworld}.
 * Verified against the source (2026-11):
 * https://github.com/jpenilla/squaremap/blob/master/web/src/js/LayerControl.js (url, tileSize 512, maxNativeZoom),
 * https://github.com/jpenilla/squaremap/blob/master/common/src/main/java/xyz/jpenilla/squaremap/common/render/output/TileImages.java,
 * https://github.com/jpenilla/squaremap/blob/master/common/src/main/java/xyz/jpenilla/squaremap/common/render/output/RegionImage.java,
 * https://github.com/jpenilla/squaremap/blob/master/common/src/main/java/xyz/jpenilla/squaremap/common/world/WorldIdentifiers.java,
 * https://github.com/jpenilla/squaremap/blob/master/common/src/main/java/xyz/jpenilla/squaremap/common/config/WorldConfig.java
 */
final class SquaremapProvider implements TileProvider {
    static final int DEFAULT_MAX_ZOOM = 3;
    private final String world;
    private final int maxZoom;

    SquaremapProvider(String world, int maxZoom) {
        this.world = world;
        this.maxZoom = maxZoom;
    }

    @Override
    public String id() {
        return "squaremap";
    }

    @Override
    public int tileSize() {
        return 512;
    }

    @Override
    public int maxZoom() {
        return maxZoom;
    }

    @Override
    public double[] pixel(double x, double z, int zoom) {
        double scale = 1 << zoom;
        return new double[]{x / scale, z / scale};
    }

    @Override
    public String tilePath(int i, int j, int zoom) {
        return "tiles/" + world + "/" + (maxZoom - zoom) + "/" + i + "_" + j + ".png";
    }
}
