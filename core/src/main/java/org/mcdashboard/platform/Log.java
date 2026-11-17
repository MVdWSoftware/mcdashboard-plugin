/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.platform;

import java.util.logging.Logger;

/** Where the plugin writes its messages; each server platform has its own logger, so the core only needs these three. */
public interface Log {
    void info(String message);

    void warning(String message);

    void severe(String message);

    /** A log that writes to a {@code java.util.logging} logger. */
    static Log of(Logger logger) {
        return new Log() {
            @Override
            public void info(String message) {
                logger.info(message);
            }

            @Override
            public void warning(String message) {
                logger.warning(message);
            }

            @Override
            public void severe(String message) {
                logger.severe(message);
            }
        };
    }
}
