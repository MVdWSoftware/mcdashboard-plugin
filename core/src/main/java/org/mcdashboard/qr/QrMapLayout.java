/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.qr;

/**
 * Geometry of the square picture that shows a {@link QrCode} on a Minecraft map: the pixel size of a module, the
 * centred quiet zone and the small logo in the middle. Pure arithmetic, so the picture is checked without a running
 * server; {@code QrMap} in the Spigot module draws it.
 */
public final class QrMapLayout {
    /** The side of a map picture in pixels. */
    public static final int MAP_SIZE = 128;
    /** The largest side of the embedded logo in pixels. */
    public static final int LOGO_MAX = 20;
    /** The smallest side of the embedded logo in pixels. */
    public static final int LOGO_MIN = 10;
    /** The light border around the logo, in pixels. */
    public static final int LOGO_MARGIN = 2;
    /** The quiet zone around the code, in modules. */
    public static final int QUIET_MODULES = 4;

    private final int scale;
    private final int offset;
    private final int logoSize;

    /**
     * Computes the layout for a code with the given side length. The logo covers about a fifth of the code picture; a
     * large code at the smallest module size (version 10, one pixel per module) gets a smaller logo so that the hole
     * stays within the error correction.
     *
     * @param modules The code's side length in modules, from {@link QrCode#size()}.
     */
    public QrMapLayout(int modules) {
        int total = modules + 2 * QUIET_MODULES;
        this.scale = Math.max(1, MAP_SIZE / total);
        this.offset = (MAP_SIZE - total * scale) / 2;
        int drawn = total * scale;
        this.logoSize = Math.max(LOGO_MIN, Math.min(LOGO_MAX, (int) Math.round(drawn * 0.18)));
    }

    /**
     * The pixel size of one module.
     *
     * @return The scale in pixels per module.
     */
    public int scale() {
        return scale;
    }

    /**
     * The distance between the quiet zone and the left or top edge of the picture.
     *
     * @return The offset in pixels.
     */
    public int offset() {
        return offset;
    }

    /**
     * Whether the map pixel at {@code (x, y)} shows a dark module of {@code code}.
     *
     * @param code The code that is drawn.
     * @param x The pixel column.
     * @param y The pixel row.
     * @return True for a dark module; false for a light one or outside the code.
     */
    public boolean dark(QrCode code, int x, int y) {
        return code.dark(Math.floorDiv(x - offset, scale) - QUIET_MODULES,
                Math.floorDiv(y - offset, scale) - QUIET_MODULES);
    }

    /**
     * The side of the logo in pixels.
     *
     * @return The logo size, between {@value #LOGO_MIN} and {@value #LOGO_MAX} pixels.
     */
    public int logoSize() {
        return logoSize;
    }

    /**
     * The left and top edge of the logo, not counting its border. The picture is square, so one value covers both.
     *
     * @return The pixel column and row where the logo starts.
     */
    public int logoStart() {
        return (MAP_SIZE - logoSize) / 2;
    }

    /**
     * Whether the map pixel at {@code (x, y)} belongs to the logo or its light border.
     *
     * @param x The pixel column.
     * @param y The pixel row.
     * @return True when the pixel is covered by the logo area.
     */
    public boolean logo(int x, int y) {
        int start = logoStart() - LOGO_MARGIN;
        int end = logoStart() + logoSize + LOGO_MARGIN;
        return x >= start && y >= start && x < end && y < end;
    }
}
