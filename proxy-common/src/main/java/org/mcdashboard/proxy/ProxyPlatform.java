/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.placeholder.ExpansionLookup;
import org.mcdashboard.placeholder.ValueSource;
import org.mcdashboard.platform.Log;
import org.mcdashboard.platform.Permissions;
import org.mcdashboard.platform.Platform;
import org.mcdashboard.web.ServerStatus;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.mcdashboard.config.YamlCodec;

/**
 * The core's {@link Platform} on a proxy. There is no placeholder engine, so {@link ProxyValueSource} answers; there is
 * no main thread or tick, so "main thread" work runs on the proxy's scheduler and a tick is 50 ms. Call {@link #close()}
 * when the plugin is disabled.
 */
public final class ProxyPlatform implements Platform, AutoCloseable {
    private final ProxyAdapter adapter;
    private final Log log;
    private final String version;
    private final ProxyConfig config;
    private final long startedMs;
    private final ServerPinger pinger;
    private final Federation federation;
    private final ProxyValueSource values;
    private ProxyPermissions permissions;

    public ProxyPlatform(ProxyAdapter adapter, Log log, String pluginVersion, Path dataDir, YamlCodec yaml, BackendFetcher fetcher,
                         ServerPinger.Probe probe, Clock clock, long proxyStartedMs) {
        this.adapter = adapter;
        this.log = log;
        this.version = pluginVersion;
        this.config = ProxyConfig.load(dataDir, yaml, log::warning);
        this.startedMs = proxyStartedMs;
        this.pinger = probe == null ? new ServerPinger(adapter) : new ServerPinger(adapter, probe);
        this.federation = config.backends().isEmpty() ? null : new Federation(config.backends(), fetcher, clock, log::warning);
        this.values = new ProxyValueSource(adapter, pinger, federation, clock, proxyStartedMs);
    }

    /** The production wiring: real TCP checks, the HTTP fetcher and the JVM start as proxy start. */
    public static ProxyPlatform create(ProxyAdapter adapter, Log log, String pluginVersion, Path dataDir, YamlCodec yaml) {
        return new ProxyPlatform(adapter, log, pluginVersion, dataDir, yaml, new HttpBackendFetcher(pluginVersion), null,
                Clock.systemUTC(), java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    public ProxyConfig config() {
        return config;
    }

    public ServerPinger pinger() {
        return pinger;
    }

    public Federation federation() {
        return federation;
    }

    /** The permission snapshots; null until the core has started. Platform listeners call {@code playerChanged}. */
    public ProxyPermissions permissions() {
        return permissions;
    }

    @Override
    public Log log() {
        return log;
    }

    @Override
    public String defaultsFolder() {
        return "proxy/";
    }

    @Override
    public List<String> defaultDashboards() {
        return List.of("player", "network");
    }

    @Override
    public String pluginVersion() {
        return version;
    }

    @Override
    public String serverName() {
        return adapter.platformName() + " network";
    }

    /** The built-in placeholders need nothing installed. */
    @Override
    public boolean placeholdersAvailable() {
        return true;
    }

    @Override
    public ValueSource values() {
        pinger.start(config.pingIntervalSeconds());
        if (federation != null) {
            federation.start(config.federationIntervalSeconds());
            log.info("Federation on: " + config.backends().size() + " backend server(s) are read every " + config.federationIntervalSeconds() + " s.");
        }
        return values;
    }

    @Override
    public ExpansionLookup expansions() {
        return values.lookup();
    }

    @Override
    public synchronized Permissions permissions(DashboardStore dashboards) {
        if (permissions != null) permissions.stop();
        permissions = new ProxyPermissions(adapter, dashboards);
        return permissions;
    }

    /** A name typed by a client: a player connected to this proxy. */
    @Override
    public String playerUuid(String name) {
        return adapter.playerByName(name).map(p -> p.uuid().toString()).orElse(null);
    }

    @Override
    public ServerStatus status() {
        return new ServerStatus(adapter.motd(), null, adapter.onlinePlayers(), Math.max(0, adapter.maxPlayers()),
                adapter.proxyVersion(), adapter.port());
    }

    @Override
    public void runSync(Runnable task) {
        adapter.schedule(task, 0, 0);
    }

    @Override
    public void runSyncLater(Runnable task, long ticks) {
        adapter.schedule(task, Ticks.toMillis(ticks), 0);
    }

    @Override
    public void runSyncRepeating(Runnable task, long ticks) {
        adapter.schedule(task, 0, Math.max(1, ticks) * Ticks.MILLIS_PER_TICK);
    }

    @Override
    public void notifyLinked(DeviceStore.Device device, String prefix) {
        adapter.message(UUID.fromString(device.playerUuid()), prefix + "§aA device (§f" + device.label()
                + "§a) was linked to your account for MCDashboard.\n §8» §7Not you? Run §f/mcd revoke " + device.id() + "§7 to remove it.");
    }

    @Override
    public synchronized void close() {
        pinger.close();
        if (federation != null) federation.close();
        if (permissions != null) permissions.stop();
    }
}
