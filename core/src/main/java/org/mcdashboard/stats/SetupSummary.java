/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.stats;

/**
 * The configuration choices the plugin may publish to bStats as anonymous custom charts. It never contains a
 * server name, address, licence id, player name or placeholder value: only whether a feature is on and which
 * storage backend is configured.
 *
 * @param serverEnabled Whether the web server is enabled in {@code config.yml}.
 * @param licensed Whether a licence id is configured.
 * @param tunnelEnabled Whether the tunnel is enabled.
 * @param historyBackend The configured history storage type ({@code memory}, {@code sqlite}, {@code mysql},
 *                       {@code mariadb} or {@code postgresql}).
 */
public record SetupSummary(boolean serverEnabled, boolean licensed, boolean tunnelEnabled, String historyBackend) {
    /**
     * The chart label of the configured history backend.
     *
     * @return One of {@code memory}, {@code sqlite}, {@code mysql}, {@code mariadb}, {@code postgresql} or {@code unknown}.
     */
    public String historyLabel() {
        return switch (historyBackend.toLowerCase(java.util.Locale.ROOT)) {
            case "sqlite" -> "sqlite";
            case "mysql" -> "mysql";
            case "mariadb" -> "mariadb";
            case "postgresql" -> "postgresql";
            case "memory" -> "memory";
            default -> "unknown";
        };
    }
}
