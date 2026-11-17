package org.mcdashboard.web;

/**
 * Snapshot of what the server list shows. {@code motd} uses legacy {@code §} colour codes exactly as
 * Minecraft's own server list does; the app renders them. {@code icon} is a PNG data URI (64x64) or null.
 */
public record ServerStatus(String motd, String icon, int online, int max, String gameVersion, int gamePort) {
    public static final ServerStatus EMPTY = new ServerStatus("", null, 0, 0, "", 0);
}
