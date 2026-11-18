/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class StatsPlaceholdersTest {
    private static PluginStats stats(PluginStatsTest.FakeClock clock) {
        return new PluginStats(clock).version("1.2.3").historyStorage("postgresql").licensed(true)
                .devices(() -> 3, () -> 2).devicesEntitled(e -> switch (e) { case "server" -> 2; case "app" -> 1; default -> 0; }).liveClients(() -> 1).historySamples(() -> 99).placeholders(() -> 20).dashboards(() -> 2);
    }

    @Test
    void everyDocumentedParameterResolves() {
        PluginStatsTest.FakeClock clock = new PluginStatsTest.FakeClock();
        PluginStats stats = stats(clock);
        stats.apiRequest();
        stats.apiRequest();
        stats.rateLimited();
        clock.now += 61_000;
        assertEquals("3", StatsPlaceholders.resolve(stats, "devices"));
        assertEquals("2", StatsPlaceholders.resolve(stats, "devices_online"));
        assertEquals("2", StatsPlaceholders.resolve(stats, "devices_server"));
        assertEquals("1", StatsPlaceholders.resolve(stats, "devices_app"));
        assertEquals("0", StatsPlaceholders.resolve(stats, "devices_free"));
        assertEquals("true", StatsPlaceholders.resolve(stats, "licensed"));
        assertEquals("licensed", StatsPlaceholders.resolve(stats, "license_state"));
        assertEquals("2", StatsPlaceholders.resolve(stats, "api_requests"));
        assertEquals("0", StatsPlaceholders.resolve(stats, "api_requests_per_minute"));
        assertEquals("1", StatsPlaceholders.resolve(stats, "rate_limited"));
        assertEquals("1", StatsPlaceholders.resolve(stats, "live_clients"));
        assertEquals("99", StatsPlaceholders.resolve(stats, "history_samples"));
        assertEquals("postgresql", StatsPlaceholders.resolve(stats, "history_storage"));
        assertEquals("20", StatsPlaceholders.resolve(stats, "placeholders"));
        assertEquals("2", StatsPlaceholders.resolve(stats, "dashboards"));
        assertEquals("61", StatsPlaceholders.resolve(stats, "uptime"));
        assertEquals("1m 1s", StatsPlaceholders.resolve(stats, "uptime_formatted"));
        assertEquals("1.2.3", StatsPlaceholders.resolve(stats, "version"));
        for (String p : StatsPlaceholders.PARAMS) assertNotNull(StatsPlaceholders.resolve(stats, p), p);
    }

    @Test
    void unknownParametersAreNull() {
        PluginStats stats = stats(new PluginStatsTest.FakeClock());
        for (String p : new String[]{"", "nope", "devices_other", "player", "token", "address"}) {
            assertNull(StatsPlaceholders.resolve(stats, p), p);
        }
        assertNull(StatsPlaceholders.resolve(stats, null));
        assertNull(StatsPlaceholders.resolve(null, "devices"));
    }

    @Test
    void resolvingNeverWaitsForAnything() {
        PluginStats stats = stats(new PluginStatsTest.FakeClock());
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            for (int i = 0; i < 10_000; i++) for (String p : StatsPlaceholders.PARAMS) StatsPlaceholders.resolve(stats, p);
        });
    }

    @Test
    void parametersAreCaseInsensitive() {
        assertEquals("3", StatsPlaceholders.resolve(stats(new PluginStatsTest.FakeClock()), "Devices"));
    }
}
