/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.qr;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A small QR code encoder (byte mode, error correction level M, versions 1 to 10, up to 213 bytes): enough for a
 * pairing link on a map, without a dependency. The tests decode its output with ZXing.
 */
public final class QrCode {
    /** Level M, indexed by version (index 0 unused). */
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] NUM_BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int MAX_VERSION = 10;

    private final int version;
    private final int size;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    private QrCode(int version, byte[] data) {
        this.version = version;
        this.size = version * 4 + 17;
        this.modules = new boolean[size][size];
        this.isFunction = new boolean[size][size];
        drawFunctionPatterns();
        drawCodewords(addEccAndInterleave(data));
        applyMask();
        drawFormatBits(0);
    }

    /** Encodes text (UTF-8). Throws {@link IllegalArgumentException} when it does not fit version 10.
     *
     * @param text The text to encode.
     * @return The rendered QR code.
     * @throws IllegalArgumentException When the text does not fit in a version 10 code.
     */
    public static QrCode encode(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int version = 1; version <= MAX_VERSION; version++) {
            int capacityBits = dataCodewords(version) * 8;
            int countBits = version < 10 ? 8 : 16;
            int needed = 4 + countBits + bytes.length * 8;
            if (needed > capacityBits) continue;
            BitBuffer bb = new BitBuffer();
            bb.append(0b0100, 4);
            bb.append(bytes.length, countBits);
            for (byte b : bytes) bb.append(b & 0xFF, 8);
            bb.append(0, Math.min(4, capacityBits - bb.length()));
            bb.append(0, (8 - bb.length() % 8) % 8);
            for (int pad = 0xEC; bb.length() < capacityBits; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
            return new QrCode(version, bb.toBytes());
        }
        throw new IllegalArgumentException("Text too long for a version 10 QR code: " + bytes.length + " bytes");
    }

    /** The code's side length in modules.
     *
     * @return The side length in modules.
     */
    public int size() {
        return size;
    }

    /** The QR version, 1 to 10.
     *
     * @return The version number.
     */
    public int version() {
        return version;
    }

    /** True for a dark module; false outside the code (so the quiet zone can be drawn without bounds checks).
     *
     * @param x The module column.
     * @param y The module row.
     * @return True when the module is dark.
     */
    public boolean dark(int x, int y) {
        return x >= 0 && y >= 0 && x < size && y < size && modules[y][x];
    }

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        int[] pos = alignmentPositions();
        for (int i = 0; i < pos.length; i++) {
            for (int j = 0; j < pos.length; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == pos.length - 1) || (i == pos.length - 1 && j == 0)) continue;
                drawAlignment(pos[i], pos[j]);
            }
        }
        drawFormatBits(0); // reserves the area; rewritten with the real mask afterwards
        drawVersion();
    }

    private void drawFinder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx;
                int y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) setFunction(x, y, dist != 2 && dist != 4);
            }
        }
    }

    private void drawAlignment(int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) setFunction(cx + dx, cy + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
    }

    private int[] alignmentPositions() {
        if (version == 1) return new int[0];
        int num = version / 7 + 2;
        int step = (version * 4 + num * 2 + 1) / (num * 2 - 2) * 2;
        int[] result = new int[num];
        result[0] = 6;
        for (int i = num - 1, pos = size - 7; i >= 1; i--, pos -= step) result[i] = pos;
        return result;
    }

    private void drawFormatBits(int mask) {
        int data = (0 << 3) | mask; // error correction level M is 0b00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setFunction(8, i, bit(bits, i));
        setFunction(8, 7, bit(bits, 6));
        setFunction(8, 8, bit(bits, 7));
        setFunction(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setFunction(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(8, size - 15 + i, bit(bits, i));
        setFunction(8, size - 8, true);
    }

    private void drawVersion() {
        if (version < 7) return;
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = version << 12 | rem;
        for (int i = 0; i < 18; i++) {
            boolean b = bit(bits, i);
            int a = size - 11 + i % 3;
            int c = i / 3;
            setFunction(a, c, b);
            setFunction(c, a, b);
        }
    }

    private void setFunction(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private static boolean bit(int value, int i) {
        return ((value >>> i) & 1) != 0;
    }

    private static int rawModules(int version) {
        int result = (16 * version + 128) * version + 64;
        if (version >= 2) {
            int num = version / 7 + 2;
            result -= (25 * num - 10) * num - 55;
            if (version >= 7) result -= 36;
        }
        return result;
    }

    private static int dataCodewords(int version) {
        return rawModules(version) / 8 - ECC_PER_BLOCK[version] * NUM_BLOCKS[version];
    }

    private byte[] addEccAndInterleave(byte[] data) {
        int blocks = NUM_BLOCKS[version];
        int eccLen = ECC_PER_BLOCK[version];
        int raw = rawModules(version) / 8;
        int shortBlocks = blocks - raw % blocks;
        int shortLen = raw / blocks;
        byte[][] all = new byte[blocks][];
        byte[] divisor = ReedSolomon.divisor(eccLen);
        for (int i = 0, k = 0; i < blocks; i++) {
            byte[] dat = Arrays.copyOfRange(data, k, k + shortLen - eccLen + (i < shortBlocks ? 0 : 1));
            k += dat.length;
            byte[] ecc = ReedSolomon.remainder(dat, divisor);
            byte[] block = Arrays.copyOf(dat, shortLen + 1);
            System.arraycopy(ecc, 0, block, block.length - eccLen, eccLen);
            all[i] = block;
        }
        byte[] result = new byte[raw];
        for (int i = 0, r = 0; i < all[0].length; i++) {
            for (int j = 0; j < blocks; j++) {
                if (i != shortLen - eccLen || j >= shortBlocks) result[r++] = all[j][i];
            }
        }
        return result;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    /**
     * Mask 0, the only mask this encoder uses: every data cell whose {@code x + y} is even is inverted. The mask number is
     * written into the format bits, so a scanner that prefers another mask still reads the picture; offering more than one
     * mask would only mean encoding every symbol eight times to score them.
     */
    private void applyMask() {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (!isFunction[y][x] && (x + y) % 2 == 0) modules[y][x] = !modules[y][x];
            }
        }
    }

    private static final class BitBuffer {
        private final List<Boolean> bits = new ArrayList<>();

        void append(int value, int count) {
            for (int i = count - 1; i >= 0; i--) bits.add(((value >>> i) & 1) != 0);
        }

        int length() {
            return bits.size();
        }

        byte[] toBytes() {
            byte[] out = new byte[bits.size() / 8];
            for (int i = 0; i < bits.size(); i++) if (bits.get(i)) out[i >>> 3] |= (byte) (1 << (7 - (i & 7)));
            return out;
        }
    }
}
