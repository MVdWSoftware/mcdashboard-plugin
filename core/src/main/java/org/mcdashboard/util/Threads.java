/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.util;

import java.util.concurrent.ThreadFactory;

/** Thread factories for the plugin's background work. */
public final class Threads {
    private Threads() {
    }

    /** Named daemon threads, so a pool that is not shut down never keeps the server from stopping. */
    public static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
