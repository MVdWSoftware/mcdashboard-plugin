/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import org.mcdashboard.auth.PermissionCache;
import org.mcdashboard.dashboard.Dashboard;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.platform.Permissions;
import org.mcdashboard.web.HttpApi;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Fills the {@link PermissionCache} from the proxy's permission check (which LuckPerms backs when it is installed on the
 * proxy). The proxies have no permission defaults, so the nodes below are granted unless the proxy explicitly denies them
 * (BungeeCord cannot tell a denial from silence, so there they are always granted). Snapshots are taken every 5 seconds and
 * when a player joins or leaves.
 */
public final class ProxyPermissions implements Permissions {
    /** Held by everyone unless denied: linking devices and the personal dashboard. */
    public static final Set<String> DEFAULT_GRANTED = Set.of("mcdashboard.player", "mcdashboard.dashboard.player");

    private final ProxyAdapter adapter;
    private final DashboardStore dashboards;
    private final PermissionCache cache = new PermissionCache();
    private volatile Set<String> nodes = Set.of();
    private ProxyAdapter.Cancellable task;

    public ProxyPermissions(ProxyAdapter adapter, DashboardStore dashboards) {
        this.adapter = adapter;
        this.dashboards = dashboards;
    }

    @Override
    public PermissionCache cache() {
        return cache;
    }

    @Override
    public synchronized void start(Collection<PlaceholderDefinition> placeholders) {
        addPlaceholders(placeholders);
        refreshNodes();
        if (task != null) task.cancel(); // a reload starts again
        task = adapter.schedule(this::snapshotAll, 1000, 5000);
    }

    /** Stops the periodic snapshots. */
    public synchronized void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    @Override
    public void addPlaceholders(Collection<PlaceholderDefinition> placeholders) {
        Set<String> next = new HashSet<>(nodes);
        for (PlaceholderDefinition d : placeholders) {
            if (d.permission() != null) next.add(d.permission());
            next.addAll(d.minIntervalOverrides().permissions());
        }
        nodes = next;
    }

    @Override
    public void addPermissions(Collection<String> watch) {
        Set<String> next = new HashSet<>(nodes);
        next.addAll(watch);
        nodes = next;
    }

    @Override
    public void dashboardsChanged() {
        adapter.schedule(() -> {
            refreshNodes();
            snapshotAll();
        }, 0, 0);
    }

    /** A player joined or left: their snapshot is current (a join is snapshotted again a second later, when permission plugins have loaded their data). */
    public void playerChanged(UUID player) {
        snapshot(player);
    }

    public void snapshotAll() {
        for (ProxyAdapter.PlayerView p : adapter.players()) snapshot(p.uuid());
    }

    /** Only for a player who is online: a leaving player keeps the snapshot from their last one. */
    void snapshot(UUID player) {
        if (adapter.player(player).isEmpty()) return;
        Set<String> held = new HashSet<>();
        boolean developer = granted(player, HttpApi.DEVELOPER_PERMISSION);
        for (String node : nodes) {
            if (granted(player, node) || developer && isDeveloperDashboard(node)) held.add(node);
        }
        cache.update(player, held);
    }

    private boolean granted(UUID player, String node) {
        Boolean answer = adapter.permission(player, node);
        return answer != null ? answer : DEFAULT_GRANTED.contains(node);
    }

    /** Every {@code dev-*} dashboard is granted to developers, as on the other platforms. */
    private static boolean isDeveloperDashboard(String node) {
        return node.startsWith("mcdashboard.dashboard.dev-");
    }

    private void refreshNodes() {
        Set<String> next = new HashSet<>(nodes);
        next.add(HttpApi.DEVELOPER_PERMISSION);
        next.addAll(DEFAULT_GRANTED);
        for (Dashboard d : dashboards.all()) next.add(d.permission());
        nodes = next;
    }
}
