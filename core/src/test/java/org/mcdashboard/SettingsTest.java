/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mcdashboard.auth.PermissionCache;
import org.mcdashboard.config.ConfigView;

class SettingsTest {
    private static Settings load(Map<String, Object> security, List<String> warnings) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("security", security);
        Consumer<String> warn = warnings::add;
        return Settings.load(ConfigView.of(root), warn);
    }

    @Test
    void minIntervalPermissionOverridesAreReadAndTheFastestNodeWins() {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("x.admin", 15);
        overrides.put("x.vip", 60);
        Map<String, Object> security = new LinkedHashMap<>();
        security.put("min-interval", 900);
        security.put("min-interval-permissions", overrides);
        List<String> warnings = new ArrayList<>();

        Settings settings = load(security, warnings);

        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(900_000, settings.minIntervalMs());
        PermissionCache permissions = new PermissionCache();
        String player = "00000000-0000-0000-0000-000000000001";
        permissions.update(UUID.fromString(player), Set.of("x.vip"));
        assertEquals(60_000, settings.minIntervalOverrides().select(settings.minIntervalMs(), player, permissions));
        permissions.update(UUID.fromString(player), Set.of("x.vip", "x.admin"));
        assertEquals(15_000, settings.minIntervalOverrides().select(settings.minIntervalMs(), player, permissions));
        assertEquals(900_000, settings.minIntervalOverrides().select(settings.minIntervalMs(), null, permissions));
    }

    @Test
    void invalidMinIntervalPermissionOverridesAreReportedAndSkipped() {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("x.good", 15);
        overrides.put("x.bad", 0);
        Map<String, Object> security = new LinkedHashMap<>();
        security.put("min-interval-permissions", overrides);
        List<String> warnings = new ArrayList<>();

        Settings settings = load(security, warnings);

        assertEquals(Set.of("x.good"), settings.minIntervalOverrides().permissions());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith("security.min-interval-permissions:"), warnings.get(0));
    }

    @Test
    void unauthenticatedPlayerNamesAreOffUnlessTheOwnerOptsIn() {
        List<String> warnings = new ArrayList<>();
        assertTrue(!Settings.load(ConfigView.of(Map.of()), warnings::add).allowPlayerNames());
        Settings on = load(Map.of("allow-player-names", true), warnings);
        assertTrue(on.allowPlayerNames());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void serverCapsDefaultToNoneAndRejectNegativesAndGarbage() {
        List<String> warnings = new ArrayList<>();
        Settings none = Settings.load(ConfigView.of(Map.of()), warnings::add);
        assertEquals(0, none.maxDashboards());
        assertEquals(0, none.maxTiles());
        assertTrue(warnings.isEmpty());

        Settings set = Settings.load(ConfigView.of(Map.of("limits", Map.of("max-dashboards", 4, "max-tiles", "20"))), warnings::add);
        assertEquals(4, set.maxDashboards());
        assertEquals(20, set.maxTiles());
        assertTrue(warnings.isEmpty());

        Settings bad = Settings.load(ConfigView.of(Map.of("limits", Map.of("max-dashboards", -2, "max-tiles", "lots"))), warnings::add);
        assertEquals(0, bad.maxDashboards());
        assertEquals(0, bad.maxTiles());
        assertEquals(2, warnings.size());
    }
}
