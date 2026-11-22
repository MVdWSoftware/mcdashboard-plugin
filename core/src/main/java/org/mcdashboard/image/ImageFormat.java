/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.image;

/** Recognises the image formats the dashboard accepts by their magic bytes, never by extension or Content-Type. */
public final class ImageFormat {
    /** Largest image accepted, from a file or a URL. */
    public static final int MAX_BYTES = 256 * 1024;

    private ImageFormat() {
    }

    /** The MIME type of a PNG, JPEG, GIF or WebP image, or null when the bytes are anything else.
     *
     * @param b The raw image bytes to inspect.
     * @return One of {@code image/png}, {@code image/jpeg}, {@code image/gif} or {@code image/webp}.
     */
    public static String sniff(byte[] b) {
        if (b == null) return null;
        if (starts(b, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) return "image/png";
        if (starts(b, 0, 0xFF, 0xD8, 0xFF)) return "image/jpeg";
        if (starts(b, 0, 'G', 'I', 'F', '8') && b.length > 5 && (b[4] == '7' || b[4] == '9') && b[5] == 'a') return "image/gif";
        if (starts(b, 0, 'R', 'I', 'F', 'F') && starts(b, 8, 'W', 'E', 'B', 'P')) return "image/webp";
        return null;
    }

    private static boolean starts(byte[] b, int off, int... magic) {
        if (b.length < off + magic.length) return false;
        for (int i = 0; i < magic.length; i++) if ((b[off + i] & 0xFF) != magic[i]) return false;
        return true;
    }
}
