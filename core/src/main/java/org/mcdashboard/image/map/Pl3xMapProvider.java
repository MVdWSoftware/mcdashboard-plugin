/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.image.map;

/**
 * Pl3xMap v3 (granny/Pl3xMap): {@code tiles/<world>/<zoom>/<renderer>/<x>_<z>.<format>}, 512 pixel tiles, top-down,
 * +X right, +Z down. Zoom 0 is one pixel per block and one tile per region; zoom {@code n} tiles are
 * {@code region >> n} and draw every {@code 1 << n}th block. {@code <world>} is the world's folder under {@code tiles/}
 * (its name, {@code :} replaced by {@code -}); {@code <renderer>} the renderer key (e.g. {@code vintage_story},
 * {@code basic}, {@code biomes}); {@code <format>} is {@code settings.web-directory.tile-format} (default png).
 * Verified against the source (2026-11):
 * https://github.com/granny/Pl3xMap/blob/v3/webmap/src/tilelayer/ReversedZoomTileLayer.ts,
 * https://github.com/granny/Pl3xMap/blob/v3/core/src/main/java/net/pl3x/map/core/image/TileImage.java,
 * https://github.com/granny/Pl3xMap/blob/v3/core/src/main/java/net/pl3x/map/core/world/World.java,
 * https://github.com/granny/Pl3xMap/blob/v3/core/src/main/java/net/pl3x/map/core/configuration/WorldConfig.java
 */
final class Pl3xMapProvider implements TileProvider {
    static final int DEFAULT_MAX_ZOOM = 3;
    private final String world;
    private final String renderer;
    private final String format;
    private final int maxZoom;

    Pl3xMapProvider(String world, String renderer, String format, int maxZoom) {
        this.world = world;
        this.renderer = renderer;
        this.format = format;
        this.maxZoom = maxZoom;
    }

    @Override
    public String id() {
        return "pl3xmap";
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
        return "tiles/" + world + "/" + zoom + "/" + renderer + "/" + i + "_" + j + "." + format;
    }
}
