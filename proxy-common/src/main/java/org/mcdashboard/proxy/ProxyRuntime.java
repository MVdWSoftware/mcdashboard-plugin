/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import org.mcdashboard.MCDashboardCore;
import org.mcdashboard.commands.DashboardCommands;
import org.mcdashboard.config.YamlCodec;
import org.mcdashboard.platform.Log;

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

    public ProxyRuntime(ProxyAdapter adapter, Log log, String pluginVersion, Path dataDir, YamlCodec yaml) {
        this.adapter = adapter;
        this.log = log;
        this.version = pluginVersion;
        this.dataDir = dataDir;
        this.yaml = yaml;
    }

    public synchronized void start() {
        platform = ProxyPlatform.create(adapter, log, version, dataDir, yaml);
        core = new MCDashboardCore(platform, yaml, dataDir);
        commands = new ProxyCommands(adapter, core, this::reload);
        core.start();
    }

    public synchronized void stop() {
        if (core != null) core.stop();
        if (platform != null) platform.close();
        core = null;
        platform = null;
    }

    public synchronized void reload() {
        stop();
        start();
    }

    public synchronized void execute(DashboardCommands.Sender who, String label, String[] args) {
        if (commands != null) commands.execute(who, label, args);
    }

    public List<String> complete(String[] args) {
        return ProxyCommands.complete(args);
    }

    /** A player joined or left: refresh their permission snapshot now and, for a join, again once permission plugins have their data. */
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
