/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import org.mcdashboard.MCDashboardCore;
import org.mcdashboard.commands.DashboardCommands;
import org.mcdashboard.config.YamlCodec;
import org.mcdashboard.platform.Log;
import org.mcdashboard.stats.SetupSummary;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Everything a proxy plugin does besides talking to its proxy: builds the platform and the core, runs the commands and
 * restarts both for {@code /mcdashboard reload} (so {@code network:} changes apply too). The Velocity and BungeeCord
 * plugins each own one.
 */
public final class ProxyRuntime {
    private final ProxyAdapter adapter;
    private final Log log;
    private final String version;
    private final Path dataDir;
    private final YamlCodec yaml;
    private ProxyPlatform platform;
    private MCDashboardCore core;
    private ProxyCommands commands;

    /**
     * Creates a new runtime with the given proxy adapter and configuration.
     *
     * @param adapter The proxy-specific adapter implementation.
     * @param log The logger to use for output.
     * @param pluginVersion The version string of this plugin.
     * @param dataDir The plugin's data directory path.
     * @param yaml The YAML codec for configuration parsing.
     */
    public ProxyRuntime(ProxyAdapter adapter, Log log, String pluginVersion, Path dataDir, YamlCodec yaml) {
        this.adapter = adapter;
        this.log = log;
        this.version = pluginVersion;
        this.dataDir = dataDir;
        this.yaml = yaml;
    }

    /** Starts the platform and core, and registers the command handler. */
    public synchronized void start() {
        platform = ProxyPlatform.create(adapter, log, version, dataDir, yaml);
        core = new MCDashboardCore(platform, yaml, dataDir);
        commands = new ProxyCommands(adapter, core, this::reload);
        core.start();
    }

    /** Stops the core and closes the platform cleanly. */
    public synchronized void stop() {
        if (core != null) core.stop();
        if (platform != null) platform.close();
        core = null;
        platform = null;
    }

    /**
     * The anonymous configuration summary the proxy's bStats custom charts publish: whether the web server, a
     * licence and the tunnel are on, and which history backend is configured. Before {@link #start()} every flag is
     * off. It never contains a server name, address, licence id or placeholder value.
     *
     * @return The setup summary; never null.
     */
    public synchronized SetupSummary setupSummary() {
        return core == null ? new SetupSummary(false, false, false, "unknown") : core.setupSummary();
    }

    /** Performs a full reload: stops everything, then starts again with current files on disk. */
    public synchronized void reload() {
        stop();
        start();
    }

    /**
     * Executes the command handler with the given sender and arguments.
     *
     * @param who The sender to answer.
     * @param label The command label that was used.
     * @param args The raw command arguments.
     */
    public synchronized void execute(DashboardCommands.Sender who, String label, String[] args) {
        if (commands != null) commands.execute(who, label, args);
    }

    /**
     * Returns tab completion suggestions for the given arguments.
     *
     * @param args The command arguments typed so far.
     * @return A list of valid completions.
     */
    public List<String> complete(String[] args) {
        return ProxyCommands.complete(args);
    }

    /**
     * A player joined or left: refresh their permission snapshot now and, for a join, again once permission plugins have their data.
     *
     * @param player The UUID of the player who joined or left.
     * @param joined True if the player joined, false if they disconnected.
     */
    public void playerChanged(UUID player, boolean joined) {
        ProxyPlatform p;
        synchronized (this) {
            p = platform;
        }
        if (p == null || p.permissions() == null) return;
        p.permissions().playerChanged(player);
        if (joined) adapter.schedule(() -> p.permissions().playerChanged(player), 1000, 0);
    }
}
