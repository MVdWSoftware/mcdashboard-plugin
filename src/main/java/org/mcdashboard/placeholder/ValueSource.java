package org.mcdashboard.placeholder;

/** Resolves a {@code %placeholder%} template. The Bukkit implementation hops to the main thread. */
public interface ValueSource {
    /**
     * @param template   a single {@code %placeholder%}
     * @param playerUuid player context, or null for server-wide placeholders
     * @return the resolved text, or null when it could not be resolved
     */
    String resolve(String template, String playerUuid);
}
