/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.platform;

import java.util.logging.Logger;

/** Where the plugin writes its messages; each server platform has its own logger, so the core only needs these three. */
public interface Log {
    /** Writes an informational message.
     *
     * @param message The text to log.
     */
    void info(String message);

    /** Writes a warning.
     *
     * @param message The text to log.
     */
    void warning(String message);

    /** Writes a severe error.
     *
     * @param message The text to log.
     */
    void severe(String message);

    /** A log that writes to a {@code java.util.logging} logger.
     *
     * @param logger The JDK logger to forward to.
     * @return A {@link Log} backed by that logger.
     */
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
