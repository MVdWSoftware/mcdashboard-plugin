/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

/** Proxies have no tick; the core's ticks (1/20 s) become milliseconds for the proxy's scheduler. */
public final class Ticks {
    public static final long MILLIS_PER_TICK = 50;

    private Ticks() {
    }

    public static long toMillis(long ticks) {
        return Math.max(0, ticks) * MILLIS_PER_TICK;
    }
}
