/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.placeholder.ExpansionLookup;
import org.mcdashboard.placeholder.PapiCloudLookup;
import org.mcdashboard.placeholder.PapiExpansionLookup;
import org.mcdashboard.placeholder.PapiValueSource;
import org.mcdashboard.placeholder.ValueSource;
import org.mcdashboard.platform.Log;
import org.mcdashboard.platform.Permissions;
import org.mcdashboard.platform.Platform;
import org.mcdashboard.qr.QrMap;
import org.mcdashboard.web.ServerStatus;

import java.nio.file.Path;
import java.util.UUID;

/** Spigot and Paper: PlaceholderAPI answers the placeholders and the Bukkit scheduler is the main thread. */
final class SpigotPlatform implements Platform {
    private final JavaPlugin plugin;
    private final Log log;

    SpigotPlatform(JavaPlugin plugin) {
        this.plugin = plugin;
        this.log = Log.of(plugin.getLogger());
    }

    @Override
    public Log log() {
        return log;
    }

    @Override
    public String pluginVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String serverName() {
        return Bukkit.getServer().getName();
    }

    @Override
    public boolean placeholdersAvailable() {
        return Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    }

    @Override
    public ValueSource values() {
        return new PapiValueSource(plugin);
    }

    @Override
    public ExpansionLookup expansions() {
        return new PapiExpansionLookup().orElse(new PapiCloudLookup());
    }

    @Override
    public Permissions permissions(DashboardStore dashboards) {
        return new PermissionSync(plugin, dashboards);
    }

    /** A name typed by a client: an online player first, then someone this server has seen before. */
    @Override
    public String playerUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId().toString();
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline.getUniqueId().toString() : null;
    }

    @Override
    public ServerStatus status() {
        return new ServerStatus(Bukkit.getMotd(), ServerStatus.icon(Path.of("server-icon.png")), Bukkit.getOnlinePlayers().size(),
                Bukkit.getMaxPlayers(), Bukkit.getVersion(), Bukkit.getPort());
    }

    @Override
    public void runSync(Runnable task) {
        Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override
    public void runSyncLater(Runnable task, long ticks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
    }

    @Override
    public void runSyncRepeating(Runnable task, long ticks) {
        Bukkit.getScheduler().runTaskTimer(plugin, task, 0L, ticks);
    }

    @Override
    public void notifyLinked(DeviceStore.Device device, String prefix) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(UUID.fromString(device.playerUuid()));
            if (p != null) {
                QrMap.destroyFor(p, true); // the code has been used; the map must not stay readable
                p.sendMessage(prefix + "§aA device (§f" + device.label() + "§a) was linked to your account for MCDashboard.");
                p.sendMessage(" §8» §7Not you? Run §f/mcd revoke " + device.id() + "§7 to remove it.");
            }
        });
    }
}
