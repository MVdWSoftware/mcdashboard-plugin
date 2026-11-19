/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mcdashboard.auth.OtpService;
import org.mcdashboard.auth.PermissionCache;
import org.mcdashboard.auth.RateLimiter;
import org.mcdashboard.auth.TokenService;
import org.mcdashboard.config.ConfigView;
import org.mcdashboard.events.EventBus;
import org.mcdashboard.platform.Log;
import org.mcdashboard.stats.PluginStats;
import org.mcdashboard.util.PrivateNetwork;
import org.mcdashboard.web.ServerStatus;

/** The small classes whose branches the bigger tests do not reach. */
class SmallGapsTest {
    private static final class Ticking extends Clock {
        long now = 1_800_000_000_000L;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public long millis() {
            return now;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }
    }

    @TempDir
    Path dir;

    @Test
    void rateLimiterSweepsIdleKeys() {
        Ticking c = new Ticking();
        RateLimiter r = new RateLimiter(c, 2, 1000);
        for (int i = 0; i < 1100; i++) assertTrue(r.allow("k" + i));
        c.now += 5000;
        assertTrue(r.allow("fresh")); // sweeps the 1100 idle keys
        assertTrue(r.allow("fresh"));
        assertFalse(r.allow("fresh"));
    }

    @Test
    void otpCodesExpireAndAreDisplayedInTwoGroups() {
        Ticking c = new Ticking();
        OtpService otp = new OtpService(c, 10);
        String a = otp.create("u1", "A");
        assertEquals(9, OtpService.display(a).length());
        assertEquals("abc", OtpService.display("abc"));
        c.now += 20_000;
        String b = otp.create("u2", "B"); // purges the expired one
        assertTrue(otp.redeem(a).isEmpty());
        assertTrue(otp.redeem(null).isEmpty());
        assertTrue(otp.redeem(OtpService.display(b).toLowerCase()).isPresent());
        assertTrue(otp.redeem(b).isEmpty(), "single use");
        c.now += 20_000;
        String late = new OtpService(c, 10).create("u", "n");
        OtpService forever = new OtpService(c, -1);
        String f = forever.create("u", "n");
        c.now += 10_000_000_000L;
        assertTrue(forever.redeem(f).isPresent());
        assertNotNull(late);
    }

    @Test
    void tokensRejectEveryMalformedShape() {
        Ticking c = new Ticking();
        byte[] secret = new byte[32];
        assertThrows(IllegalArgumentException.class, () -> new TokenService(new byte[8], c));
        TokenService t = new TokenService(secret, c);
        String forever = t.issue(TokenService.Type.REFRESH, "d", "p", 1, -1);
        assertEquals(-1, t.verify(forever, TokenService.Type.REFRESH).orElseThrow().expiresAtMs());
        assertTrue(t.verify(null, TokenService.Type.ACCESS).isEmpty());
        assertTrue(t.verify("a.b", TokenService.Type.ACCESS).isEmpty());
        assertTrue(t.verify("%%%.%%%.%%%", TokenService.Type.ACCESS).isEmpty());
        assertTrue(t.verify(forever, TokenService.Type.ACCESS).isEmpty(), "wrong type");
        String expired = t.issue(TokenService.Type.ACCESS, "d", "p", 1, 10);
        c.now += 60_000;
        assertTrue(t.verify(expired, TokenService.Type.ACCESS).isEmpty());
        // a correctly signed token with an unknown type, and one without serial
        String unknownType = signed(secret, "{\"alg\":\"HS256\"}", "{\"typ\":\"x\",\"ser\":1}");
        assertTrue(t.verify(unknownType, TokenService.Type.ACCESS).isEmpty());
        String noSerial = signed(secret, "{\"alg\":\"HS256\"}", "{\"typ\":\"access\",\"sub\":\"d\"}");
        assertTrue(t.verify(noSerial, TokenService.Type.ACCESS).isEmpty());
        String none = signed(secret, "{\"alg\":\"none\"}", "{\"typ\":\"access\",\"ser\":1}");
        assertTrue(t.verify(none, TokenService.Type.ACCESS).isEmpty());
    }

    private static String signed(byte[] secret, String header, String payload) {
        try {
            var b64 = java.util.Base64.getUrlEncoder().withoutPadding();
            String body = b64.encodeToString(header.getBytes()) + "." + b64.encodeToString(payload.getBytes());
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret, "HmacSHA256"));
            return body + "." + b64.encodeToString(mac.doFinal(body.getBytes()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void permissionCacheKnowsNullNodesAndUnknownPlayers() {
        PermissionCache p = new PermissionCache();
        UUID u = UUID.randomUUID();
        p.update(u, Set.of("a"));
        assertTrue(p.has(null, null));
        assertTrue(p.has("x", " "));
        assertFalse(p.has(null, "a"));
        assertFalse(p.has("unknown", "a"));
        assertTrue(p.has(u.toString(), "a"));
        assertFalse(p.has(u.toString(), "b"));
    }

    @Test
    void serverIconIsReadOnlyWhenSmallAndPresent() throws Exception {
        Path png = dir.resolve("icon.png");
        assertNull(ServerStatus.icon(png));
        Files.write(png, new byte[]{1, 2, 3});
        assertTrue(ServerStatus.icon(png).startsWith("data:image/png;base64,"));
        Files.write(png, new byte[70 * 1024]);
        assertNull(ServerStatus.icon(png));
        assertNull(ServerStatus.icon(dir)); // a directory is not a regular file
    }

    @Test
    void logWrapsAJavaLogger() {
        Logger l = Logger.getLogger("test-" + UUID.randomUUID());
        l.setUseParentHandlers(false);
        List<String> seen = new ArrayList<>();
        l.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                seen.add(r.getLevel() + " " + r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        Log log = Log.of(l);
        log.info("a");
        log.warning("b");
        log.severe("c");
        assertEquals(List.of("INFO a", "WARNING b", "SEVERE c"), seen);
    }

    @Test
    void platformDefaultsSupportTheCatalog() {
        assertTrue(new FakePlatform().supportsCatalog());
        assertEquals("", new FakePlatform().defaultsFolder());
        assertEquals(List.of("player", "server"), new FakePlatform().defaultDashboards());
    }

    @Test
    void privateNetworkClassifiesHostsLiterally() {
        assertTrue(PrivateNetwork.isPrivate("[::1]"));
        assertTrue(PrivateNetwork.isPrivate("fd00::1"));
        assertTrue(PrivateNetwork.isPrivate("10.1.2.3"));
        assertTrue(PrivateNetwork.isPrivate("LOCALHOST"));
        assertFalse(PrivateNetwork.isPrivate("8.8.8.8"));
        assertFalse(PrivateNetwork.isPrivate("2001:db8::1"));
        assertFalse(PrivateNetwork.isPrivate("example.org"));
        assertFalse(PrivateNetwork.isPrivate("999.1.1.1"));
        assertFalse(PrivateNetwork.isPrivate("1:2:3:4:5:6:7:8:9:g"));
        assertFalse(PrivateNetwork.isPrivate("["));
        assertFalse(PrivateNetwork.isPrivate(" "));
        assertFalse(PrivateNetwork.isPrivate(null));
    }

    @Test
    void configViewMergesAndReadsLists() {
        assertEquals("x", ConfigView.of(null).getString("a", "x"));
        Map<String, Object> base = new LinkedHashMap<>(Map.of("a", Map.of("b", 1, "c", 2), "d", 1));
        Map<String, Object> over = new LinkedHashMap<>(Map.of("a", Map.of("b", 9), "d", Map.of("e", 1)));
        Map<String, Object> merged = ConfigView.merge(base, over);
        assertEquals(Map.of("b", 9, "c", 2), merged.get("a"));
        assertEquals(Map.of("e", 1), merged.get("d"));
        assertEquals(1, ConfigView.merge(Map.of("a", Map.of("b", 1)), Map.of("a", 1)).size());
        ConfigView v = ConfigView.of(Map.of("l", List.of("s", 1, true, 'c', Map.of(), 2.5), "m", "notalist"));
        assertEquals(List.of("s", "1", "true", "c", "2.5"), v.getStringList("l"));
        assertEquals(List.of(), v.getStringList("m"));
        assertEquals(List.of(), v.getMapList("m"));
    }

    @Test
    void eventBusSurvivesBrokenListenersAndUnreadableIds() {
        EventBus bus = new EventBus(Clock.systemUTC());
        bus.subscribe(null, e -> {
            throw new IllegalStateException("broken");
        }, () -> {
        });
        AtomicInteger seen = new AtomicInteger();
        bus.subscribe(null, e -> seen.incrementAndGet(), () -> {
        });
        bus.publish("device.revoked", "d", "p");
        assertEquals(1, seen.get());
        AtomicInteger resets = new AtomicInteger();
        bus.subscribe(bus.epoch() + ":abc", e -> {
        }, resets::incrementAndGet);
        bus.subscribe("nocolon", e -> {
        }, resets::incrementAndGet);
        bus.subscribe(bus.epoch() + ":99", e -> {
        }, resets::incrementAndGet);
        assertEquals(3, resets.get());
        EventBus empty = new EventBus(Clock.systemUTC());
        AtomicInteger r2 = new AtomicInteger();
        empty.subscribe(empty.epoch() + ":0", e -> {
        }, r2::incrementAndGet);
        assertEquals(0, r2.get(), "nothing published yet and nothing missed");
        bus.subscribe(" ", e -> {
        }, resets::incrementAndGet);
    }

    @Test
    void statsGaugesSurviveFailingSuppliersAndWindowEdges() {
        Ticking c = new Ticking();
        PluginStats s = new PluginStats(c);
        assertTrue(s.startedMs() > 0);
        s.version(null).historyStorage(null);
        s.historyStorage("memory");
        s.historyStorage("sqlite");
        s.historyStorage(" ");
        s.version("1");
        s.devices(() -> {
            throw new IllegalStateException();
        }, () -> {
            throw new IllegalStateException();
        });
        s.devicesEntitled(e -> {
            throw new IllegalStateException();
        });
        s.historySamples(() -> {
            throw new IllegalStateException();
        });
        assertEquals(0, s.devices());
        assertEquals(0, s.devicesOnline());
        assertEquals(0, s.devicesEntitled("app"));
        assertEquals(0, s.historySamples());
        s.apiRequest();
        c.now += 30_000;
        s.apiRequest();
        assertEquals(2, s.apiRequestsPerMinute());
        c.now += 45_000;
        assertEquals(1, s.apiRequestsPerMinute());
        c.now -= 100_000; // a clock that went backwards: future buckets are ignored
        assertEquals(0, s.apiRequestsPerMinute());
    }
}
