/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.auth.PermissionCache;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.placeholder.ExpansionLookup;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.placeholder.ValueSource;
import org.mcdashboard.platform.Log;
import org.mcdashboard.platform.Permissions;
import org.mcdashboard.platform.Platform;
import org.mcdashboard.web.ServerStatus;

/** A platform that records what the core asks of it and runs "main thread" work at once. */
public final class FakePlatform implements Platform {
    public final List<String> logged = new CopyOnWriteArrayList<>();
    public final AtomicInteger repeating = new AtomicInteger();
    public final List<String> permissionCalls = new CopyOnWriteArrayList<>();
    public final PermissionCache cache = new PermissionCache();
    public boolean available = true;

    @Override
    public Log log() {
        return new Log() {
            @Override
            public void info(String m) {
                logged.add("INFO " + m);
            }

            @Override
            public void warning(String m) {
                logged.add("WARN " + m);
            }

            @Override
            public void severe(String m) {
                logged.add("SEVERE " + m);
            }
        };
    }

    @Override
    public String pluginVersion() {
        return "9.9.9";
    }

    @Override
    public String serverName() {
        return "Fake Server";
    }

    @Override
    public boolean placeholdersAvailable() {
        return available;
    }

    @Override
    public ValueSource values() {
        return (template, player) -> template.equals("%server_online%") ? "3" : "0";
    }

    @Override
    public ExpansionLookup expansions() {
        return id -> null;
    }

    @Override
    public Permissions permissions(DashboardStore dashboards) {
        return new Permissions() {
            @Override
            public PermissionCache cache() {
                return cache;
            }

            @Override
            public void start(Collection<PlaceholderDefinition> placeholders) {
                permissionCalls.add("start " + placeholders.size());
            }

            @Override
            public void addPlaceholders(Collection<PlaceholderDefinition> placeholders) {
                permissionCalls.add("add " + placeholders.size());
            }

            @Override
            public void addPermissions(Collection<String> nodes) {
                permissionCalls.add("watch " + nodes.size());
            }

            @Override
            public void dashboardsChanged() {
                permissionCalls.add("changed");
            }
        };
    }

    @Override
    public ServerStatus status() {
        return new ServerStatus("A fake MOTD", null, 2, 20, "1.21", 25565);
    }

    @Override
    public void runSync(Runnable task) {
        task.run();
    }

    @Override
    public void runSyncLater(Runnable task, long ticks) {
        task.run();
    }

    @Override
    public void runSyncRepeating(Runnable task, long ticks) {
        repeating.incrementAndGet();
        task.run();
    }

    @Override
    public void notifyLinked(DeviceStore.Device device) {
        // nobody to tell
    }
}
