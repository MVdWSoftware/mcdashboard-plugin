/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.nukkit;

import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.placeholder.ExpansionLookup;
import org.mcdashboard.placeholder.ValueSource;
import org.mcdashboard.platform.Log;
import org.mcdashboard.platform.Permissions;
import org.mcdashboard.platform.Platform;
import org.mcdashboard.web.ServerStatus;
import org.powernukkitx.placeholderapi.PlaceholderAPI;
import org.powernukkitx.plugin.PluginBase;

import java.util.UUID;

/** PowerNukkitX: the hub's PlaceholderAPI answers the placeholders and the server scheduler is the main thread. */
final class NukkitPlatform implements Platform {
    private final PluginBase plugin;
    private final Log log;

    NukkitPlatform(PluginBase plugin) {
        this.plugin = plugin;
        var logger = plugin.getLogger();
        this.log = new Log() {
            @Override
            public void info(String message) {
                logger.info(message);
            }

            @Override
            public void warning(String message) {
                logger.warning(message);
            }

            @Override
            public void severe(String message) {
                logger.error(message);
            }
        };
    }

    @Override
    public Log log() {
        return log;
    }

    /** Placeholders here have PowerNukkitX names ({@code %player%}, {@code %server_tps%}), so the defaults differ. */
    @Override
    public String defaultsFolder() {
        return "nukkit/";
    }

    /** The PlaceholderAPI for PowerNukkitX has no way to list what is registered. */
    @Override
    public boolean supportsCatalog() {
        return false;
    }

    @Override
    public String pluginVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String serverName() {
        return plugin.getServer().getName();
    }

    @Override
    public boolean placeholdersAvailable() {
        return plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") != null && PlaceholderAPI.get() != null;
    }

    @Override
    public ValueSource values() {
        return new NukkitValueSource(plugin);
    }

    @Override
    public ExpansionLookup expansions() {
        return id -> null;
    }

    @Override
    public Permissions permissions(DashboardStore dashboards) {
        return new NukkitPermissions(plugin, dashboards);
    }

    /** A name typed by a client: an online player first, then someone with saved player data on this server. */
    @Override
    public String playerUuid(String name) {
        var server = plugin.getServer();
        var online = server.getPlayerExact(name);
        if (online != null) return online.getUniqueId().toString();
        return server.hasOfflinePlayerData(name) ? server.getOfflinePlayer(name).getUniqueId().toString() : null;
    }

    @Override
    public ServerStatus status() {
        var server = plugin.getServer();
        return new ServerStatus(server.getMotd(), null, server.getOnlinePlayers().size(), server.getMaxPlayers(),
                server.getVersion(), server.getPort());
    }

    @Override
    public void runSync(Runnable task) {
        plugin.getServer().getScheduler().scheduleTask(plugin, task);
    }

    @Override
    public void runSyncLater(Runnable task, long ticks) {
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, task, (int) ticks);
    }

    @Override
    public void runSyncRepeating(Runnable task, long ticks) {
        plugin.getServer().getScheduler().scheduleDelayedRepeatingTask(plugin, task, 1, (int) ticks);
    }

    @Override
    public void notifyLinked(DeviceStore.Device device, String prefix) {
        runSync(() -> plugin.getServer().getPlayer(UUID.fromString(device.playerUuid())).ifPresent(p -> {
            p.sendMessage(prefix + "§aA device (§f" + device.label() + "§a) was linked to your account for MCDashboard.");
            p.sendMessage(" §8» §7Not you? Run §f/mcd revoke " + device.id() + "§7 to remove it.");
        }));
    }
}
