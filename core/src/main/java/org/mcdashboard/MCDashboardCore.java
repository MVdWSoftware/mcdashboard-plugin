/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.auth.OtpService;
import org.mcdashboard.config.YamlCodec;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.platform.Log;
import org.mcdashboard.platform.Platform;
import org.mcdashboard.stats.PluginStats;
import org.mcdashboard.tunnel.TunnelClient;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The plugin without a server platform: reads the configuration, runs the API, the live feed, device events, the
 * tunnel and history, and answers what the platform's commands need. A platform plugin builds one with its
 * {@link Platform} and {@link YamlCodec}, calls {@link #start()} when it is enabled and {@link #stop()} when it is disabled.
 */
public final class MCDashboardCore {
    private final Platform platform;
    private final Log log;
    private final DataFolder files;
    private final YamlCodec yaml;
    private Settings settings;
    private Services services;
    private volatile boolean developerMode;
    private final PluginStats stats = new PluginStats(java.time.Clock.systemUTC());

    /** {@code dataDir} is {@code plugins/MCDashboard/}: the configuration, the signing key, the dashboards and the history live there. */
    public MCDashboardCore(Platform platform, YamlCodec yaml, Path dataDir) {
        this.platform = platform;
        this.log = platform.log();
        this.yaml = yaml;
        this.files = new DataFolder(dataDir, yaml, platform.defaultsFolder(), platform.defaultDashboards(), log::warning);
    }

    /** Writes the missing default files, reads the configuration and starts the services when {@code server.enabled} is on. */
    public void start() {
        try {
            files.exportDefaults();
        } catch (IOException e) {
            log.severe("Could not write the default files: " + e.getMessage());
            return;
        }
        settings = Settings.load(files.config(), log::warning);
        developerMode = files.developerMode(settings.developerMode());
        stats.version(platform.pluginVersion()).licensed(settings.licenseId() != null);
        if (!settings.enabled()) {
            log.warning("The web server is disabled. Set server.enabled to true in config.yml and restart.");
            return;
        }
        DashboardStore dashboards = new DashboardStore(files.resolve("dashboards"), yaml, log::warning);
        services = new Services(platform, settings, files, dashboards, this::developerMode, stats);
        services.start();
    }

    public void stop() {
        if (services != null) services.stop();
    }

    /** The plugin's own counters, for the platform's {@code %mcdashboard_*%} placeholders. Never null; reads memory only. */
    public PluginStats stats() {
        return stats;
    }

    /** The version of the plugin, as the platform reports it. */
    public String pluginVersion() {
        return platform.pluginVersion();
    }

    public boolean developerMode() {
        return developerMode;
    }

    /** Turns developer mode on or off until it is changed again, surviving restarts. */
    public void setDeveloperMode(boolean enabled) {
        developerMode = enabled;
        try {
            files.saveDeveloperMode(enabled);
        } catch (IOException e) {
            log.warning("Could not save developer mode: " + e.getMessage());
        }
    }

    /** Null while the web server is off or failed to start. */
    public OtpService otps() {
        return services == null ? null : services.otps();
    }

    public DeviceStore devices() {
        return services == null ? null : services.devices();
    }

    /** Null when the tunnel is off. */
    public TunnelClient tunnel() {
        return services == null ? null : services.tunnel();
    }

    public long otpTimeoutSeconds() {
        return settings.otpTimeoutSeconds();
    }

    /** SHA-256 fingerprint of the self-signed certificate the API serves, or null (CA-signed, custom or no TLS): a client pins it. */
    public String pinnedFingerprint() {
        return services == null || !settings.tls().mode().equals("self-signed") ? null : services.tlsFingerprint();
    }

    /** {@code server.public-url}, or null when it is not set. */
    public String publicUrl() {
        return settings.publicUrl();
    }
}
