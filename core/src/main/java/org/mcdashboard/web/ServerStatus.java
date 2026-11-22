/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Snapshot of what the server list shows. {@code motd} uses legacy {@code §} colour codes exactly as
 * Minecraft's own server list does; the app renders them. {@code icon} is a PNG data URI (64x64) or null.
 *
 * @param motd        The message of the day, with legacy colour codes.
 * @param icon        The server icon as a PNG data URI, or null.
 * @param online      How many players are online.
 * @param max         The player slot limit.
 * @param gameVersion The game version string.
 * @param gamePort    The game port, or zero when unknown.
 */
public record ServerStatus(String motd, String icon, int online, int max, String gameVersion, int gamePort) {
    public static final ServerStatus EMPTY = new ServerStatus("", null, 0, 0, "", 0);
    private static final long MAX_ICON_BYTES = 64 * 1024;

    /**
     * The server icon file as a PNG data URI, or null when it is absent, too large or unreadable.
     *
     * @param file The icon file to read.
     * @return The data URI, or null when there is no usable icon.
     */
    public static String icon(Path file) {
        try {
            if (Files.isRegularFile(file) && Files.size(file) < MAX_ICON_BYTES) {
                return "data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(file));
            }
        } catch (IOException ignored) {
            // served without an icon
        }
        return null;
    }
}
