/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PluginStatsTest {
    static final class FakeClock extends Clock {
        long now = 1_000_000_000_000L;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }

        @Override
        public long millis() {
            return now;
        }
    }

    @Test
    void theRollingMinuteForgetsOldRequestsSecondBySecond() {
        FakeClock clock = new FakeClock();
        PluginStats stats = new PluginStats(clock);
        for (int i = 0; i < 5; i++) stats.apiRequest();
        clock.now += 30_000;
        for (int i = 0; i < 3; i++) stats.apiRequest();
        assertEquals(8, stats.apiRequestsPerMinute());
        clock.now += 29_000; // the first five are 59 s old
        assertEquals(8, stats.apiRequestsPerMinute());
        clock.now += 1_000; // 60 s old: out of the window
        assertEquals(3, stats.apiRequestsPerMinute());
        clock.now += 40_000;
        assertEquals(0, stats.apiRequestsPerMinute());
        assertEquals(8, stats.apiRequests(), "the total never forgets");
    }

    @Test
    void aBucketIsReusedAfterAMinuteWithoutKeepingItsOldCount() {
        FakeClock clock = new FakeClock();
        PluginStats stats = new PluginStats(clock);
        stats.apiRequest();
        clock.now += 60_000; // same bucket index, a minute later
        stats.apiRequest();
        stats.apiRequest();
        assertEquals(2, stats.apiRequestsPerMinute());
        assertEquals(3, stats.apiRequests());
    }

    @Test
    void uptimeFollowsTheClockAndRateLimitsAreCounted() {
        FakeClock clock = new FakeClock();
        PluginStats stats = new PluginStats(clock);
        clock.now += 3_725_000;
        assertEquals(3725, stats.uptimeSeconds());
        assertEquals("1h 2m 5s", StatsPlaceholders.format(3725));
        assertEquals("0s", StatsPlaceholders.format(0));
        assertEquals("2d 0h 0m 1s", StatsPlaceholders.format(2 * 86_400 + 1));
        stats.rateLimited();
        stats.rateLimited();
        assertEquals(2, stats.rateLimitedRequests());
    }

    @Test
    void gaugesDefaultToZeroAndAFailingGaugeReadsZero() {
        PluginStats stats = new PluginStats(new FakeClock());
        assertEquals(0, stats.devices());
        assertEquals(0, stats.liveClients());
        assertEquals("off", stats.historyStorage());
        assertEquals("free", stats.licenseState());
        stats.devices(() -> {
            throw new IllegalStateException("boom");
        }, () -> 4);
        assertEquals(0, stats.devices());
        assertEquals(4, stats.devicesOnline());
    }

    @Test
    void memoryStorageIsReportedAsOffAndLicensingFollowsTheConfiguredId() {
        PluginStats stats = new PluginStats(new FakeClock()).historyStorage("memory").licensed(true);
        assertEquals("off", stats.historyStorage());
        assertTrue(stats.licensed());
        assertEquals("licensed", stats.licenseState());
        assertEquals("sqlite", stats.historyStorage("sqlite").historyStorage());
        assertFalse(stats.licensed(false).licensed());
    }

    @Test
    void concurrentRecordingLosesNothing() throws Exception {
        PluginStats stats = new PluginStats(new FakeClock());
        var pool = Executors.newFixedThreadPool(8);
        CountDownLatch done = new CountDownLatch(8);
        for (int t = 0; t < 8; t++) {
            pool.execute(() -> {
                for (int i = 0; i < 1000; i++) stats.apiRequest();
                done.countDown();
            });
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(8000, stats.apiRequests());
        assertEquals(8000, stats.apiRequestsPerMinute());
        assertNull(StatsPlaceholders.resolve(stats, "nope"));
    }
}
