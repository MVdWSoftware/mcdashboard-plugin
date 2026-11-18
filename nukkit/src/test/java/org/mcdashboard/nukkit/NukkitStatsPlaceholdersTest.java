/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.nukkit;

import org.junit.jupiter.api.Test;
import org.mcdashboard.stats.PluginStats;
import org.mcdashboard.stats.StatsPlaceholders;
import org.powernukkitx.placeholderapi.placeholder.Placeholder;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NukkitStatsPlaceholdersTest {
    @Test
    void everyStatsParameterIsRegisteredUnderItsFullName() {
        PluginStats stats = new PluginStats(Clock.systemUTC()).version("2.0.0").licensed(true).devices(() -> 4, () -> 1)
                .devicesEntitled(e -> e.equals("server") ? 3 : 0);
        Map<String, Placeholder> registered = new HashMap<>();
        NukkitStatsPlaceholders.register(registered::put, () -> stats);
        assertEquals(StatsPlaceholders.PARAMS.size(), registered.size());
        assertEquals("4", registered.get("mcdashboard_devices").process(null, new String[0]));
        assertEquals("3", registered.get("mcdashboard_devices_server").process(null, new String[0]));
        assertEquals("licensed", registered.get("mcdashboard_license_state").process(null, new String[0]));
        assertEquals("2.0.0", registered.get("mcdashboard_version").process(null, new String[0]));
    }

    @Test
    void withoutRunningStatsTheValueIsEmpty() {
        Map<String, Placeholder> registered = new HashMap<>();
        NukkitStatsPlaceholders.register(registered::put, () -> null);
        assertEquals("", registered.get("mcdashboard_devices").process(null, new String[0]));
    }
}
