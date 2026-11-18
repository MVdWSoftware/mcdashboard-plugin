/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mcdashboard.auth.PermissionCache;
import org.mcdashboard.image.ImageCache;

class PlaceholderServiceTest {
    private static final String ADMIN = "00000000-0000-0000-0000-000000000001";
    private static final String VIP = "00000000-0000-0000-0000-000000000002";

    /** A clock the test moves by hand, so refresh intervals are tested without sleeping. */
    static final class MutableClock extends Clock {
        private long millis;

        MutableClock(long millis) {
            this.millis = millis;
        }

        void advance(long ms) {
            millis += ms;
        }

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
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }

    private static PlaceholderService service(MutableClock clock, AtomicInteger calls, long globalMs,
                                              Map<String, Object> overrides, PermissionCache permissions,
                                              List<PlaceholderDefinition> defs) {
        return new PlaceholderService((t, u) -> "v" + calls.incrementAndGet(), clock, globalMs,
                IntervalOverrides.parse(overrides, m -> fail(m)), permissions, defs,
                Path.of("images"), new ImageCache(clock));
    }

    @Test
    void aHeldPermissionReplacesTheGlobalIntervalForThatPlayer() {
        MutableClock clock = new MutableClock(1_000_000);
        AtomicInteger calls = new AtomicInteger();
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "tps", "value", "%server_tps%")), m -> fail(m));
        PermissionCache permissions = new PermissionCache();
        var service = service(clock, calls, 900_000, Map.of("x.admin", 15), permissions, defs);

        assertEquals("v1", service.read("tps", null).orElseThrow().value());
        clock.advance(15_000);
        assertEquals("v1", service.read("tps", null).orElseThrow().value(), "anonymous callers keep the 15 minute default");
        assertEquals(900_000, service.intervalMs(defs.get(0), null));
        assertEquals(1, calls.get());

        String admin = UUID.fromString(ADMIN).toString();
        permissions.update(UUID.fromString(ADMIN), Set.of("x.admin"));
        assertEquals(15_000, service.intervalMs(defs.get(0), admin));
        assertEquals("v2", service.read("tps", admin).orElseThrow().value(), "the admin may resolve at 15 seconds");

        clock.advance(14_999);
        assertEquals("v2", service.read("tps", admin).orElseThrow().value());
        clock.advance(1);
        assertEquals("v3", service.read("tps", admin).orElseThrow().value(), "and again after another 15 seconds");
        assertEquals(3, calls.get());
    }

    @Test
    void theFastestHeldNodeWinsAndUnknownPlayersGetTheDefault() {
        MutableClock clock = new MutableClock(1_000_000);
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "tps", "value", "%server_tps%")), m -> fail(m));
        var overrides = new LinkedHashMap<String, Object>();
        overrides.put("x.admin", 15);
        overrides.put("x.vip", 60);
        PermissionCache permissions = new PermissionCache();
        var service = service(clock, new AtomicInteger(), 900_000, overrides, permissions, defs);

        assertEquals(900_000, service.intervalMs(defs.get(0), ADMIN), "nothing known about this player yet");

        permissions.update(UUID.fromString(VIP), Set.of("x.vip"));
        assertEquals(60_000, service.intervalMs(defs.get(0), VIP));

        permissions.update(UUID.fromString(ADMIN), Set.of("x.vip", "x.admin"));
        assertEquals(15_000, service.intervalMs(defs.get(0), ADMIN), "the fastest held node wins");
    }

    @Test
    void aPlaceholderPermissionOverrideCanMakeItFasterThanItsOwnMinimum() {
        MutableClock clock = new MutableClock(1_000_000);
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "expensive", "value", "%x_y%",
                "min-interval", 60, "min-interval-permissions", Map.of("x.fast", 2))), m -> fail(m));
        PermissionCache permissions = new PermissionCache();
        var service = service(clock, new AtomicInteger(), 900_000, Map.of("x.admin", 15), permissions, defs);

        assertEquals(900_000, service.intervalMs(defs.get(0), null), "the placeholder is never faster than the global floor");
        permissions.update(UUID.fromString(ADMIN), Set.of("x.admin"));
        assertEquals(60_000, service.intervalMs(defs.get(0), ADMIN), "the placeholder's own minimum still applies");
        permissions.update(UUID.fromString(ADMIN), Set.of("x.admin", "x.fast"));
        assertEquals(15_000, service.intervalMs(defs.get(0), ADMIN),
                "the placeholder override lowers its own minimum, but the global floor still wins");
    }

    @Test
    void invalidOverridesAreReportedAndSkipped() {
        List<String> warnings = new ArrayList<>();
        var raw = new LinkedHashMap<String, Object>();
        raw.put("x.good", 15);
        raw.put("x.negative", -1);
        raw.put("x.text", "soon");
        raw.put("", 30);
        var parsed = IntervalOverrides.parse(raw, warnings::add);

        assertEquals(Set.of("x.good"), parsed.permissions());
        assertEquals(3, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().allMatch(w -> w.contains("positive number of seconds")));
        List<String> nonMapWarnings = new ArrayList<>();
        assertTrue(IntervalOverrides.parse("nope", nonMapWarnings::add).isEmpty());
        assertEquals(1, nonMapWarnings.size(), "a non-map adds one warning");
    }

    @Test
    void aValueThatStopsResolvingGoesStaleInsteadOfLookingLive() {
        MutableClock clock = new MutableClock(1_000_000);
        AtomicInteger calls = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean();
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "tps", "value", "%server_tps%")), m -> fail(m));
        var svc = new PlaceholderService((t, u) -> down.get() ? null : "v" + calls.incrementAndGet(), clock, 1000,
                IntervalOverrides.empty(), new PermissionCache(), defs, Path.of("images"), new ImageCache(clock));
        assertEquals("v1", svc.read("tps", null).orElseThrow().value());
        down.set(true);
        clock.advance(2_000);
        assertEquals("v1", svc.read("tps", null).orElseThrow().value(), "a short outage keeps the last value");
        clock.advance(PlaceholderService.STALE_AFTER_MS + 10_000);
        assertTrue(svc.read("tps", null).isEmpty(), "a dead value is dropped");
    }

    @Test
    void aSourceThatKeepsFailingOpensTheBreakerSoCallersStopQueueing() {
        MutableClock clock = new MutableClock(1_000_000);
        AtomicInteger calls = new AtomicInteger();
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "a", "value", "%a%"), Map.of("id", "b", "value", "%b%"),
                Map.of("id", "c", "value", "%c%"), Map.of("id", "d", "value", "%d%")), m -> fail(m));
        var svc = new PlaceholderService((t, u) -> {
            calls.incrementAndGet();
            return null;
        }, clock, 1000, IntervalOverrides.empty(), new PermissionCache(), defs, Path.of("images"), new ImageCache(clock));
        for (String id : List.of("a", "b", "c", "d")) svc.read(id, null);
        assertEquals(PlaceholderService.BREAKER_FAILURES, calls.get(), "the fourth read does not call the source");
        clock.advance(PlaceholderService.BREAKER_PAUSE_MS + 1);
        svc.read("d", null);
        assertEquals(PlaceholderService.BREAKER_FAILURES + 1, calls.get(), "it tries again after the pause");
    }

    @Test
    void concurrentReadersOfOneValueCostOneResolution() throws Exception {
        MutableClock clock = new MutableClock(1_000_000);
        AtomicInteger calls = new AtomicInteger();
        var gate = new java.util.concurrent.CountDownLatch(1);
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "tps", "value", "%server_tps%")), m -> fail(m));
        var svc = new PlaceholderService((t, u) -> {
            calls.incrementAndGet();
            try {
                gate.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "19.9";
        }, clock, 1000, IntervalOverrides.empty(), new PermissionCache(), defs, Path.of("images"), new ImageCache(clock));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
        for (int i = 0; i < 8; i++) futures.add(pool.submit(() -> svc.read("tps", null).orElseThrow().value()));
        Thread.sleep(200);
        gate.countDown();
        for (var f : futures) assertEquals("19.9", f.get(5, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdownNow();
        assertEquals(1, calls.get());
    }
}
