/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

/** Proxies have no tick; the core's ticks (1/20 s) become milliseconds for the proxy's scheduler. */
public final class Ticks {
    /** Duration of one Minecraft tick in milliseconds (50 ms, matching 1/20 second). */
    public static final long MILLIS_PER_TICK = 50;

    private Ticks() {
    }

    /**
     * Converts a number of Minecraft ticks to milliseconds, clamped to non-negative values.
     *
     * @param ticks The number of ticks to convert.
     * @return The equivalent duration in milliseconds.
     */
    public static long toMillis(long ticks) {
        return Math.max(0, ticks) * MILLIS_PER_TICK;
    }
}
