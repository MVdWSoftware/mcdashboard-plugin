/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mcdashboard.placeholder.McDashboardExpansion;
import org.mcdashboard.stats.PluginStats;
import org.mcdashboard.stats.StatsPlaceholders;

class McDashboardExpansionTest {
    private static McDashboardExpansion expansion(PluginStats stats) {
        return new McDashboardExpansion(() -> stats, "MVdW-Software");
    }

    @Test
    void identifiesItselfAndStaysRegistered() {
        McDashboardExpansion e = expansion(new PluginStats(Clock.systemUTC()).version("2.0.0"));
        assertEquals("mcdashboard", e.getIdentifier());
        assertEquals("2.0.0", e.getVersion());
        assertTrue(e.persist());
    }

    @Test
    void answersFromTheStatsForAnyPlayerOrNone() {
        PluginStats stats = new PluginStats(Clock.systemUTC()).devices(() -> 4, () -> 1).licensed(true);
        McDashboardExpansion e = expansion(stats);
        assertEquals("4", e.onRequest(null, "devices"));
        assertEquals("1", e.onRequest(null, "devices_online"));
        assertEquals("licensed", e.onRequest(null, "license_state"));
        for (String p : StatsPlaceholders.PARAMS) assertTrue(e.onRequest(null, p) != null, p);
    }

    @Test
    void unknownParametersAreNullAndReadingIsInstant() {
        McDashboardExpansion e = expansion(new PluginStats(Clock.systemUTC()));
        assertNull(e.onRequest(null, "unknown"));
        assertNull(e.onRequest(null, "devices_server"));
        assertNull(new McDashboardExpansion(() -> null, "x").onRequest(null, "devices"));
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            for (int i = 0; i < 10_000; i++) e.onRequest(null, "api_requests_per_minute");
        });
    }
}
