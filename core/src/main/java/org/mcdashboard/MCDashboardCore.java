/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.auth.OtpService;
import org.mcdashboard.config.YamlCodec;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.diagnostics.ErrorReporter;
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
    private ErrorReporter errorReporter;
    private volatile boolean developerMode;
    private final PluginStats stats = new PluginStats(java.time.Clock.systemUTC());

    /**
     * Creates a core instance backed by the given platform and YAML codec.
     *
     * @param platform The server platform providing logger, placeholders, permissions and main thread access.
     * @param yaml Codec for reading and writing config.yml.
     * @param dataDir {@code plugins/MCDashboard/}: where configuration, keys, dashboards and history reside.
     */
    public MCDashboardCore(Platform platform, YamlCodec yaml, Path dataDir) {
        this.platform = platform;
        this.log = platform.log();
        this.yaml = yaml;
        this.files = new DataFolder(dataDir, yaml, platform.defaultsFolder(), platform.defaultDashboards(), log);
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
        // Opt-in: only unexpected plugin errors, never placeholder values or player data (see diagnostics.error-reports).
        errorReporter = ErrorReporter.start(settings.errorReports(), platform.pluginVersion(), log);
        developerMode = files.developerMode(settings.developerMode());
        stats.version(platform.pluginVersion()).licensed(settings.licenseId() != null);
        if (!settings.enabled()) {
            log.warning("The web server is disabled. Set server.enabled to true in config.yml and restart.");
            return;
        }
        DashboardStore dashboards = new DashboardStore(files.resolve("dashboards"), yaml, log::warning);
        services = new Services(platform, settings, files, dashboards, this::developerMode, stats, errorReporter);
        services.start();
    }

    /** Stops all services. */
    public void stop() {
        if (services != null) services.stop();
        if (errorReporter != null) errorReporter.close();
    }

    /** The plugin's own counters, for the platform's {@code %mcdashboard_*%} placeholders. Never null; reads memory only.
     *
     * @return The plugin stats instance.
     */
    public PluginStats stats() {
        return stats;
    }

    /** The version of the plugin, as the platform reports it.
     *
     * @return The plugin version string.
     */
    public String pluginVersion() {
        return platform.pluginVersion();
    }

    /** Whether developer mode is currently active.
     *
     * @return True when developer mode is on.
     */
    public boolean developerMode() {
        return developerMode;
    }

    /** Turns developer mode on or off until it is changed again, surviving restarts.
     *
     * @param enabled True to enable developer mode, false to disable it.
     */
    public void setDeveloperMode(boolean enabled) {
        developerMode = enabled;
        try {
            files.saveDeveloperMode(enabled);
        } catch (IOException e) {
            log.warning("Could not save developer mode: " + e.getMessage());
        }
    }

    /** The OTP service, or null while the web server is off or failed to start.
     *
     * @return The OTP service, or null.
     */
    public OtpService otps() {
        return services == null ? null : services.otps();
    }

    /** The device store, or null while the web server is off or failed to start.
     *
     * @return The device store, or null.
     */
    public DeviceStore devices() {
        return services == null ? null : services.devices();
    }

    /** The tunnel client, or null when the tunnel is off.
     *
     * @return The tunnel client, or null.
     */
    public TunnelClient tunnel() {
        return services == null ? null : services.tunnel();
    }

    /** The OTP timeout in seconds, as read from configuration.
     *
     * @return The OTP timeout in seconds.
     */
    public long otpTimeoutSeconds() {
        return settings.otpTimeoutSeconds();
    }

    /** SHA-256 fingerprint of the self-signed certificate the API serves, or null (CA-signed, custom or no TLS): a client pins it.
     *
     * @return The lower-case hex fingerprint, or null when there is nothing to pin.
     */
    public String pinnedFingerprint() {
        return services == null || !settings.tls().mode().equals("self-signed") ? null : services.tlsFingerprint();
    }

    /** {@code messages.prefix} with {@code &} colour codes turned into {@code §}; empty means no prefix.
     *
     * @return The rendered chat prefix.
     */
    public String chatPrefix() {
        return settings.prefix();
    }

    /** {@code server.public-url}, or null when it is not set.
     *
     * @return The configured public URL, or null.
     */
    public String publicUrl() {
        return settings.publicUrl();
    }
}
