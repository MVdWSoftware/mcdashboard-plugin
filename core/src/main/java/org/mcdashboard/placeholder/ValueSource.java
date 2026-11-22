/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

/** Resolves a {@code %placeholder%} template. A platform implementation hops to the main thread when its placeholder engine needs it. */
public interface ValueSource {
    /** Resolves a single {@code %placeholder%} for the player (null: server wide); null when it could not be resolved.
     *
     * @param template   The {@code %placeholder%} template to resolve.
     * @param playerUuid The player to resolve it for, or null for a server-wide value.
     * @return The resolved value, or null when it could not be resolved.
     */
    String resolve(String template, String playerUuid);
}
