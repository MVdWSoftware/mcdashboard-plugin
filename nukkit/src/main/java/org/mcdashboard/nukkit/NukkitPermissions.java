/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.nukkit;

import org.mcdashboard.auth.PermissionCache;
import org.mcdashboard.dashboard.Dashboard;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.platform.Permissions;
import org.mcdashboard.web.HttpApi;
import org.powernukkitx.Player;
import org.powernukkitx.event.EventHandler;
import org.powernukkitx.event.Listener;
import org.powernukkitx.event.player.PlayerJoinEvent;
import org.powernukkitx.event.player.PlayerQuitEvent;
import org.powernukkitx.permission.Permission;
import org.powernukkitx.plugin.PluginBase;
import org.powernukkitx.plugin.PluginManager;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Registers the dashboard permissions and fills the {@link PermissionCache} the API reads. The server only answers
 * permission questions on its main thread, so snapshots are taken there: on join, on quit and every 5 seconds.
 */
final class NukkitPermissions implements Listener, Permissions {
    private final PluginBase plugin;
    private final DashboardStore dashboards;
    private final PermissionCache cache = new PermissionCache();
    private volatile Set<String> nodes = Set.of();

    NukkitPermissions(PluginBase plugin, DashboardStore dashboards) {
        this.plugin = plugin;
        this.dashboards = dashboards;
    }

    @Override
    public PermissionCache cache() {
        return cache;
    }

    @Override
    public void start(Collection<PlaceholderDefinition> placeholders) {
        addPlaceholders(placeholders);
        refreshDashboards();
        plugin.getServer().getScheduler().scheduleDelayedRepeatingTask(plugin,
                () -> plugin.getServer().getOnlinePlayers().values().forEach(this::snapshot), 20, 100);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void addPlaceholders(Collection<PlaceholderDefinition> placeholders) {
        Set<String> next = new HashSet<>(nodes);
        for (PlaceholderDefinition d : placeholders) if (d.permission() != null) next.add(d.permission());
        nodes = next;
    }

    @Override
    public void dashboardsChanged() {
        if (!plugin.isEnabled()) return;
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            refreshDashboards();
            plugin.getServer().getOnlinePlayers().values().forEach(this::snapshot);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Permission plugins attach their data a moment after the join event.
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> snapshot(e.getPlayer()), 20);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        snapshot(e.getPlayer());
    }

    private void snapshot(Player p) {
        Set<String> held = new HashSet<>();
        for (String node : nodes) if (p.hasPermission(node)) held.add(node);
        cache.update(p.getUniqueId(), held);
    }

    /** Registers every dashboard's permission; {@code dev-*} dashboards are also granted to developers. */
    private void refreshDashboards() {
        List<Dashboard> all = dashboards.all();
        Set<String> next = new HashSet<>(nodes);
        next.add(HttpApi.DEVELOPER_PERMISSION);
        PluginManager manager = plugin.getServer().getPluginManager();
        Permission developer = manager.getPermission(HttpApi.DEVELOPER_PERMISSION);
        for (Dashboard d : all) {
            String node = d.permission();
            next.add(node);
            if (manager.getPermission(node) == null) {
                manager.addPermission(new Permission(node, "See the " + d.id() + " dashboard", Permission.DEFAULT_FALSE));
            }
            if (d.id().startsWith("dev-") && developer != null && !developer.getChildren().containsKey(node)) {
                developer.getChildren().put(node, true);
                developer.recalculatePermissibles();
            }
        }
        nodes = next;
    }
}
