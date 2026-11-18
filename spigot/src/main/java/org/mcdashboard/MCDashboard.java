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

/** Publishes an allowlist of PlaceholderAPI placeholders to the MinecraftDashboard app over REST and a live feed. */
public final class MCDashboard extends JavaPlugin {
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

    public boolean developerMode() {
        return core.developerMode();
    }

    /** Turns developer mode on or off until it is changed again, surviving restarts. */
    public void setDeveloperMode(boolean enabled) {
        core.setDeveloperMode(enabled);
    }

    /** Null while the web server is off or failed to start. */
    public OtpService otps() {
        return core.otps();
    }

    public DeviceStore devices() {
        return core.devices();
    }

    /** Null when the tunnel is off. */
    public TunnelClient tunnel() {
        return core.tunnel();
    }

    public long otpTimeoutSeconds() {
        return core.otpTimeoutSeconds();
    }

    /** {@code server.public-url}, or null when it is not set. */
    public String publicUrl() {
        return core.publicUrl();
    }
}
