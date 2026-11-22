/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.image.map;

/**
 * How one web map plugin lays out its tiles. Every provider maps a block to a position in a pixel grid per zoom level
 * in which tile {@code (i, j)} covers the pixels {@code [i * tileSize, (i + 1) * tileSize)} horizontally and
 * {@code [j * tileSize, (j + 1) * tileSize)} vertically, with {@code j} growing downwards (towards south on the supported
 * top-down maps). Providers that number their tiles the other way round (dynmap) translate in {@link #tilePath}.
 * Zoom {@code 0} is the most detailed level the provider renders; every step out shows a larger area per pixel.
 */
public interface TileProvider {
    /** Provider id as written after {@code map:}.
     *
     * @return The provider id.
     */
    String id();

    /** Side of the square of map pixels each tile image starts with (BlueMap's images carry extra metadata below it).
     *
     * @return The tile side length in pixels.
     */
    int tileSize();

    /** Highest accepted {@code zoom}.
     *
     * @return The maximum zoom level.
     */
    int maxZoom();

    /** The pixel position of the block coordinate {@code (x, z)} at {@code zoom}: {@code {px, py}}.
     *
     * @param x The block x coordinate.
     * @param z The block z coordinate.
     * @param zoom The zoom level.
     * @return The pixel position as a two-element array.
     */
    double[] pixel(double x, double z, int zoom);

    /** The tile's path relative to the map's web root, without a leading slash.
     *
     * @param i The tile column index in the pixel grid.
     * @param j The tile row index in the pixel grid.
     * @param zoom The zoom level.
     * @return The path segment to append to the map's web root.
     */
    String tilePath(int i, int j, int zoom);
}
