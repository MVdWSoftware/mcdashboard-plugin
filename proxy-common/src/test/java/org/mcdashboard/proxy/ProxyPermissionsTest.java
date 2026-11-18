/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mcdashboard.dashboard.DashboardStore;

class ProxyPermissionsTest {
    @TempDir
    Path dir;

    private ProxyPermissions permissions(FakeAdapter adapter) throws Exception {
        Path dashboards = Files.createDirectories(dir.resolve("dashboards"));
        for (String id : List.of("player", "network", "dev-alex")) {
            Files.writeString(dashboards.resolve(id + ".yml"), "name: " + id + "\nitems: []\n");
        }
        ProxyPermissions p = new ProxyPermissions(adapter, new DashboardStore(dashboards, new ProxyYaml(), m -> { }));
        p.start(List.of());
        return p;
    }

    private static String key(UUID id, String node) {
        return id + ":" + node;
    }

    @Test
    void everyoneHasTheDefaultsButNotTheNetworkDashboard() throws Exception {
        FakeAdapter adapter = new FakeAdapter();
        ProxyPermissions p = permissions(adapter);
        p.snapshotAll();
        String alex = FakeAdapter.ALEX.toString();
        assertTrue(p.cache().has(alex, "mcdashboard.dashboard.player"));
        assertTrue(p.cache().has(alex, "mcdashboard.player"));
        assertFalse(p.cache().has(alex, "mcdashboard.dashboard.network"), "proxies have no operators: staff get it from the permission plugin");
    }

    @Test
    void theProxysPermissionCheckDecidesAndAnExplicitDenialWins() throws Exception {
        FakeAdapter adapter = new FakeAdapter();
        adapter.permissions.put(key(FakeAdapter.ALEX, "mcdashboard.dashboard.network"), true);
        adapter.permissions.put(key(FakeAdapter.ALEX, "mcdashboard.dashboard.player"), false);
        ProxyPermissions p = permissions(adapter);
        p.snapshotAll();
        String alex = FakeAdapter.ALEX.toString();
        assertTrue(p.cache().has(alex, "mcdashboard.dashboard.network"));
        assertFalse(p.cache().has(alex, "mcdashboard.dashboard.player"));
    }

    @Test
    void developersGetTheDevDashboards() throws Exception {
        FakeAdapter adapter = new FakeAdapter();
        ProxyPermissions p = permissions(adapter);
        p.snapshotAll();
        assertFalse(p.cache().has(FakeAdapter.ALEX.toString(), "mcdashboard.dashboard.dev-alex"));
        adapter.permissions.put(key(FakeAdapter.ALEX, "mcdashboard.developer"), true);
        p.playerChanged(FakeAdapter.ALEX);
        assertTrue(p.cache().has(FakeAdapter.ALEX.toString(), "mcdashboard.dashboard.dev-alex"));
    }

    @Test
    void aPlayerWhoLeftKeepsTheirLastSnapshot() throws Exception {
        FakeAdapter adapter = new FakeAdapter();
        adapter.permissions.put(key(FakeAdapter.ALEX, "mcdashboard.dashboard.network"), true);
        ProxyPermissions p = permissions(adapter);
        p.snapshotAll();
        adapter.players.removeIf(v -> v.uuid().equals(FakeAdapter.ALEX));
        adapter.permissions.clear();
        p.playerChanged(FakeAdapter.ALEX);
        assertTrue(p.cache().has(FakeAdapter.ALEX.toString(), "mcdashboard.dashboard.network"));
    }

    @Test
    void snapshotsRunEveryFiveSecondsOnTheProxySchedulerAndReloadReplacesThem() throws Exception {
        FakeAdapter adapter = new FakeAdapter();
        ProxyPermissions p = permissions(adapter);
        assertEquals(1, adapter.scheduled.size());
        assertEquals(5000, adapter.scheduled.get(0).periodMs());
        p.start(List.of());
        assertEquals(1, adapter.scheduled.size(), "the previous task was cancelled");
        p.stop();
        assertEquals(0, adapter.scheduled.size());
    }
}
