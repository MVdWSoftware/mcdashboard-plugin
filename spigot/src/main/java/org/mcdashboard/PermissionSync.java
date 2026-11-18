/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.mcdashboard.auth.PermissionCache;
import org.mcdashboard.dashboard.Dashboard;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.platform.Permissions;
import org.mcdashboard.web.HttpApi;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Registers the dashboard permissions and fills the {@link PermissionCache} the API reads. Bukkit only answers
 * permission questions on the main thread, so snapshots are taken there: on join, on quit and every 5 seconds.
 */
final class PermissionSync implements Listener, Permissions {
    private final Plugin plugin;
    private final DashboardStore dashboards;
    private final PermissionCache cache = new PermissionCache();
    private volatile Set<String> nodes = Set.of();

    PermissionSync(Plugin plugin, DashboardStore dashboards) {
        this.plugin = plugin;
        this.dashboards = dashboards;
    }

    @Override
    public PermissionCache cache() {
        return cache;
    }

    /** Main thread: watches the nodes of these placeholders and of every dashboard. */
    @Override
    public void start(Collection<PlaceholderDefinition> placeholders) {
        addPlaceholders(placeholders);
        refreshDashboards();
        Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::snapshot), 20L, 100L);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** Main thread: also watches the nodes of these (catalog) placeholders. */
    @Override
    public void addPlaceholders(Collection<PlaceholderDefinition> placeholders) {
        Set<String> next = new HashSet<>(nodes);
        for (PlaceholderDefinition d : placeholders) {
            if (d.permission() != null) next.add(d.permission());
            next.addAll(d.minIntervalOverrides().permissions());
        }
        nodes = next;
    }

    /** Main thread: also watches these nodes, such as the ones in {@code security.min-interval-permissions}. */
    @Override
    public void addPermissions(Collection<String> watch) {
        Set<String> next = new HashSet<>(nodes);
        next.addAll(watch);
        nodes = next;
    }

    /** A dashboard was written by the app: register its permission and refresh the online players' snapshots. */
    @Override
    public void dashboardsChanged() {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            refreshDashboards();
            Bukkit.getOnlinePlayers().forEach(this::snapshot);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Permission plugins attach their data a moment after the join event.
        Bukkit.getScheduler().runTaskLater(plugin, () -> snapshot(e.getPlayer()), 20L);
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
        PluginManager manager = Bukkit.getPluginManager();
        Permission developer = manager.getPermission(HttpApi.DEVELOPER_PERMISSION);
        for (Dashboard d : all) {
            String node = d.permission();
            next.add(node);
            if (manager.getPermission(node) == null) {
                manager.addPermission(new Permission(node, "See the " + d.id() + " dashboard", PermissionDefault.FALSE));
            }
            if (d.id().startsWith("dev-") && developer != null && !developer.getChildren().containsKey(node)) {
                developer.getChildren().put(node, true);
                developer.recalculatePermissibles();
            }
        }
        nodes = next;
    }
}
