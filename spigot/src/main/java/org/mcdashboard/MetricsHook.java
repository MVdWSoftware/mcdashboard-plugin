/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.Plugin;

/**
 * bStats usage metrics (plugin id 35535, registered for the Bukkit/Spigot platform). The default charts (server
 * count, player count, server and Java version, OS and country) are collected by bStats itself. The custom charts
 * here only say which features are on and which history backend is configured; they never carry a server name,
 * address, licence id, player name or placeholder value. Server owners can disable the whole thing in
 * {@code plugins/bStats/config.yml}.
 */
final class MetricsHook {
    /** The bStats plugin id of the Spigot/Paper jar. The proxies have their own ids. */
    private static final int PLUGIN_ID = 35535;

    private MetricsHook() {
    }

    /**
     * Starts bStats and registers the anonymous custom charts.
     *
     * @param plugin The plugin instance bStats reports as.
     * @param core The started core, read for the configuration summary.
     * @return The running metrics instance, to shut down when the plugin disables.
     */
    static Metrics start(Plugin plugin, MCDashboardCore core) {
        Metrics metrics = new Metrics(plugin, PLUGIN_ID);
        metrics.addCustomChart(new SimplePie("server_enabled", () -> yesNo(core.setupSummary().serverEnabled())));
        metrics.addCustomChart(new SimplePie("licensed", () -> core.setupSummary().licensed() ? "licensed" : "free"));
        metrics.addCustomChart(new SimplePie("tunnel", () -> yesNo(core.setupSummary().tunnelEnabled())));
        metrics.addCustomChart(new SimplePie("history_backend", () -> core.setupSummary().historyLabel()));
        return metrics;
    }

    /**
     * Maps a boolean to the two chart values.
     *
     * @param value The flag.
     * @return {@code enabled} or {@code disabled}.
     */
    private static String yesNo(boolean value) {
        return value ? "enabled" : "disabled";
    }
}
