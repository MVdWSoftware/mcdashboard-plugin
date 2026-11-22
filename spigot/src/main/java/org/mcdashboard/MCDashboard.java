/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.auth.OtpService;
import org.mcdashboard.commands.DashboardCommands;
import org.mcdashboard.commands.MCDashboardCommand;
import org.mcdashboard.placeholder.McDashboardExpansion;
import org.mcdashboard.qr.QrMapGuard;
import org.mcdashboard.tunnel.TunnelClient;

/** Publishes an allowlist of PlaceholderAPI placeholders to the MCDashboard app over REST and a live feed. */
public final class MCDashboard extends JavaPlugin {
    /** Creates the plugin; the Spigot server instantiates it once on start-up. */
    public MCDashboard() {
    }

    private MCDashboardCore core;
    private McDashboardExpansion expansion;

    @Override
    public void onEnable() {
        core = new MCDashboardCore(new SpigotPlatform(this), new BukkitYaml(), getDataFolder().toPath());
        core.start();
        // The counters exist whether or not the web server runs; they are only offered to the app with stats.offer-to-app.
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            expansion = new McDashboardExpansion(core::stats, "MVdW-Software");
            expansion.register();
        }
        getServer().getPluginManager().registerEvents(new QrMapGuard(this), this);
        PluginCommand cmd = getCommand("mcdashboard");
        if (cmd != null) {
            MCDashboardCommand handler = new MCDashboardCommand(this, new DashboardCommands(core));
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }
    }

    @Override
    public void onDisable() {
        if (expansion != null) expansion.unregister();
        if (core != null) core.stop();
    }

    /**
     * Whether developer mode is currently active.
     *
     * @return True if developer mode is enabled.
     */
    public boolean developerMode() {
        return core.developerMode();
    }

    /**
     * Turns developer mode on or off until it is changed again, surviving restarts.
     *
     * @param enabled True to enable, false to disable developer mode.
     */
    public void setDeveloperMode(boolean enabled) {
        core.setDeveloperMode(enabled);
    }

    /**
     * Null while the web server is off or failed to start.
     *
     * @return The OTP service instance, or null when unavailable.
     */
    public OtpService otps() {
        return core.otps();
    }

    /**
     * Returns the device store for managing linked devices.
     *
     * @return The device store instance.
     */
    public DeviceStore devices() {
        return core.devices();
    }

    /**
     * Null when the tunnel is off.
     *
     * @return The tunnel client, or null when tunnels are disabled.
     */
    public TunnelClient tunnel() {
        return core.tunnel();
    }

    /**
     * Returns the configured OTP timeout in seconds (negative means default 5 minutes).
     *
     * @return The OTP timeout value.
     */
    public long otpTimeoutSeconds() {
        return core.otpTimeoutSeconds();
    }

    /**
     * {@code server.public-url}, or null when it is not set.
     *
     * @return The configured public URL, or null if not set.
     */
    public String publicUrl() {
        return core.publicUrl();
    }
}
