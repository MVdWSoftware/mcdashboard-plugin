/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mcdashboard.json.Json;
import org.mcdashboard.platform.Log;

/** The alert rules, their delivery to the portal and what the portal's answers change. */
class PushServiceTest {
    private static final String ROUTE = "route-token-123456";

    /** A clock the test moves. */
    private static final class Ticking extends Clock {
        long now = 1_790_000_000_000L;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
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

    private final List<String> sends = new CopyOnWriteArrayList<>();
    private final List<String> auth = new CopyOnWriteArrayList<>();
    private final List<String> deletes = new CopyOnWriteArrayList<>();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> reply = new AtomicReference<>("{\"results\":[{\"route\":\"" + ROUTE + "\",\"status\":\"ok\"}]}");
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicBoolean licensed = new AtomicBoolean(true);
    private final Map<String, Double> readings = new HashMap<>();
    private final Ticking clock = new Ticking();
    private HttpServer portal;
    private PushService service;
    private PushStore store;
    private KeyPair device;
    private String publicKey;

    @BeforeEach
    void start() throws Exception {
        portal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        portal.createContext("/api/v1/push/send", ex -> {
            sends.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] out = reply.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        portal.createContext("/api/v1/push/routes/", ex -> {
            deletes.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        portal.start();
        device = PushCrypto.generateKeyPair();
        publicKey = PushCrypto.encodePublicKey(device.getPublic());
        store = new PushStore(Files.createTempFile("push", ".json"));
        service = new PushService(store, new PushClient("mcl_test", "http://127.0.0.1:" + portal.getAddress().getPort() + "/api/"),
                licensed::get, true, "Aurora", "hash", id -> id.equals("gone") ? Optional.empty() : Optional.of("steve"),
                (placeholder, player) -> readings.containsKey(placeholder) ? OptionalDouble.of(readings.get(placeholder)) : OptionalDouble.empty(),
                clock, new Log() {
                    @Override
                    public void info(String message) {
                    }

                    @Override
                    public void warning(String message) {
                        warnings.add(message);
                    }

                    @Override
                    public void severe(String message) {
                        warnings.add(message);
                    }
                });
        readings.put("server_tps", 20.0);
    }

    @AfterEach
    void stop() {
        service.close();
        portal.stop(0);
    }

    private static PushRule immediate() {
        Map<String, Object> rule = PushRuleTest.rule();
        rule.put("forSeconds", 0);
        return PushRule.parse(rule);
    }

    private PushBinding bind(int forSeconds, int cooldown) {
        Map<String, Object> rule = PushRuleTest.rule();
        rule.put("forSeconds", forSeconds);
        rule.put("cooldownSeconds", cooldown);
        PushBinding b = new PushBinding(ROUTE, publicKey, List.of(PushRule.parse(rule)));
        service.bind("d1", b);
        return b;
    }

    /** Waits for the sender thread to finish what it was given. */
    private void settle(int expectedSends) throws Exception {
        for (int i = 0; i < 100 && sends.size() < expectedSends; i++) Thread.sleep(20);
        Thread.sleep(50);
        assertEquals(expectedSends, sends.size());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decrypt(int index) throws Exception {
        Map<String, Object> message = (Map<String, Object>) ((List<Object>) Json.parseObject(sends.get(index)).get("messages")).get(0);
        assertEquals("alert", message.get("category"));
        assertEquals(List.of(ROUTE), message.get("routes"));
        byte[] plain = PushCrypto.decrypt(device.getPrivate(), publicKey, ROUTE, "hash", (Map<String, Object>) message.get("ciphertext"));
        return Json.parseObject(new String(plain, StandardCharsets.UTF_8));
    }

    @Test
    void aRuleThatHoldsSendsOneEncryptedNotificationWithTheLicenceAsCredential() throws Exception {
        bind(0, 600);
        service.evaluate(); // 20 TPS: fine
        readings.put("server_tps", 12.0);
        service.evaluate();
        settle(1);
        assertEquals("Bearer mcl_test", auth.get(0));
        Map<String, Object> payload = decrypt(0);
        assertEquals("Aurora: TPS is low", payload.get("title"));
        assertEquals("TPS is 12", payload.get("body"));
        assertFalse(sends.get(0).contains("TPS is"), "the portal must only see ciphertext");
    }

    @Test
    void itFiresOncePerEpisodeAndAgainAfterTheValueRecoversAndTheCooldown() throws Exception {
        bind(0, 120);
        readings.put("server_tps", 12.5);
        service.evaluate();
        settle(1);
        assertEquals("TPS is 12.50", decrypt(0).get("body"));
        clock.now += 10 * 60_000;
        service.evaluate(); // still low, same episode
        Thread.sleep(100);
        assertEquals(1, sends.size());
        readings.put("server_tps", 20.0);
        service.evaluate(); // recovered
        readings.put("server_tps", 10.0);
        service.evaluate();
        settle(2);
        clock.now += 1000;
        readings.put("server_tps", 20.0);
        service.evaluate();
        readings.put("server_tps", 10.0);
        service.evaluate(); // inside the cooldown
        Thread.sleep(100);
        assertEquals(2, sends.size());
    }

    @Test
    void theConditionMustHoldForTheConfiguredTime() throws Exception {
        bind(60, 600);
        readings.put("server_tps", 10.0);
        service.evaluate();
        clock.now += 30_000;
        service.evaluate();
        Thread.sleep(100);
        assertEquals(0, sends.size());
        clock.now += 31_000;
        service.evaluate();
        settle(1);
    }

    @Test
    void nothingHappensWhenPushIsNotAvailable() throws Exception {
        bind(0, 600);
        readings.put("server_tps", 1.0);
        licensed.set(false);
        assertFalse(service.available());
        service.evaluate();
        Thread.sleep(100);
        assertEquals(0, sends.size());
        licensed.set(true);
        assertTrue(service.available());
        PushService off = new PushService(store, new PushClient(null, null), () -> true, true, "x", null, id -> Optional.empty(),
                (p, u) -> OptionalDouble.empty(), clock, Log.of(java.util.logging.Logger.getAnonymousLogger()));
        assertFalse(off.available());
        PushService disabled = new PushService(store, new PushClient("a", "http://x"), () -> true, false, "x", null, id -> Optional.empty(),
                (p, u) -> OptionalDouble.empty(), clock, Log.of(java.util.logging.Logger.getAnonymousLogger()));
        assertFalse(disabled.available());
        off.close();
        disabled.close();
    }

    @Test
    void aDeviceThatIsGoneOrHasNoRulesIsSkipped() throws Exception {
        service.bind("gone", new PushBinding(ROUTE, publicKey, List.of(PushRule.parse(PushRuleTest.rule()))));
        service.bind("d2", new PushBinding("route-token-654321", publicKey, List.of()));
        readings.put("server_tps", 1.0);
        service.evaluate();
        Thread.sleep(100);
        assertEquals(0, sends.size());
    }

    @Test
    void aGoneRouteDropsTheBinding() throws Exception {
        bind(0, 600);
        reply.set("{\"results\":[{\"route\":\"" + ROUTE + "\",\"status\":\"gone\"}]}");
        readings.put("server_tps", 1.0);
        service.evaluate();
        settle(1);
        for (int i = 0; i < 50 && service.binding("d1").isPresent(); i++) Thread.sleep(20);
        assertTrue(service.binding("d1").isEmpty());
    }

    @Test
    void aThrottledRouteIsLeftAloneForAWhile() throws Exception {
        bind(0, 60);
        reply.set("{\"results\":[{\"route\":\"" + ROUTE + "\",\"status\":\"throttled\",\"retryAfter\":3600}]}");
        readings.put("server_tps", 1.0);
        service.evaluate();
        settle(1);
        clock.now += 5 * 60_000;
        readings.put("server_tps", 20.0);
        service.evaluate();
        readings.put("server_tps", 1.0);
        service.evaluate(); // fires again, but the route is backed off
        Thread.sleep(150);
        assertEquals(1, sends.size());
        reply.set("{\"results\":[{\"route\":\"" + ROUTE + "\",\"status\":\"ok\"}]}");
        clock.now += 2 * 3600_000L;
        readings.put("server_tps", 20.0);
        service.evaluate();
        readings.put("server_tps", 1.0);
        service.evaluate();
        settle(2);
    }

    @Test
    void aRefusalStopsPushForAWhileAndIsLoggedOnce() throws Exception {
        bind(0, 60);
        status.set(402);
        readings.put("server_tps", 1.0);
        service.evaluate();
        settle(1);
        assertFalse(service.available());
        assertEquals(1, warnings.size());
        clock.now += 11 * 60_000;
        assertTrue(service.available());
    }

    @Test
    void aSuspendedRouteStopsPushToo() throws Exception {
        bind(0, 60);
        reply.set("{\"results\":[{\"route\":\"" + ROUTE + "\",\"status\":\"suspended\"}]}");
        readings.put("server_tps", 1.0);
        service.evaluate();
        settle(1);
        for (int i = 0; i < 50 && service.available(); i++) Thread.sleep(20);
        assertFalse(service.available());
    }

    @Test
    void failuresAreLoggedOnceAndRetriedLater() throws Exception {
        bind(0, 60);
        status.set(500);
        readings.put("server_tps", 1.0);
        service.evaluate();
        settle(1);
        assertTrue(service.available());
        assertEquals(1, warnings.size());
        clock.now += 120_000;
        readings.put("server_tps", 20.0);
        service.evaluate();
        readings.put("server_tps", 1.0);
        service.evaluate();
        settle(2);
        assertEquals(1, warnings.size(), "the same problem is not logged twice");
    }

    @Test
    void replacingOrRemovingABindingRevokesTheOldRoute() throws Exception {
        bind(0, 60);
        service.bind("d1", new PushBinding("route-token-NEW00001", publicKey, List.of()));
        for (int i = 0; i < 50 && deletes.isEmpty(); i++) Thread.sleep(20);
        assertEquals(List.of("DELETE /api/v1/push/routes/" + ROUTE), deletes);
        assertTrue(service.unbind("d1"));
        for (int i = 0; i < 50 && deletes.size() < 2; i++) Thread.sleep(20);
        assertEquals(2, deletes.size());
        assertFalse(service.unbind("d1"));
        // the same route again is not a replacement
        service.bind("d3", new PushBinding("route-token-SAME0001", publicKey, List.of()));
        service.bind("d3", new PushBinding("route-token-SAME0001", publicKey, List.of()));
        Thread.sleep(100);
        assertEquals(2, deletes.size());
    }

    @Test
    void aStoredKeyThatNoLongerParsesDropsTheBinding() throws Exception {
        store.put("d1", new PushBinding(ROUTE, "AAAA", List.of(immediate())));
        readings.put("server_tps", 1.0);
        service.evaluate();
        for (int i = 0; i < 50 && store.get("d1").isPresent(); i++) Thread.sleep(20);
        assertTrue(store.get("d1").isEmpty());
        assertEquals(0, sends.size());
    }

    @Test
    void theServerNameMayBeMissing() throws Exception {
        PushService unnamed = new PushService(store, new PushClient("mcl_test", "http://127.0.0.1:" + portal.getAddress().getPort() + "/api"),
                () -> true, true, null, "hash", id -> Optional.of("steve"), (p, u) -> OptionalDouble.of(1), clock,
                Log.of(java.util.logging.Logger.getAnonymousLogger()));
        store.put("d9", new PushBinding(ROUTE, publicKey, List.of(immediate())));
        unnamed.evaluate();
        settle(1);
        unnamed.close();
    }

    @Test
    void numbersAndValueSources() {
        assertEquals(19.8, PushService.number(" 19,8 ").getAsDouble());
        assertEquals(20, PushService.number("20").getAsDouble());
        assertTrue(PushService.number("abc").isEmpty());
        assertTrue(PushService.number("NaN").isEmpty());
        assertTrue(PushService.number(null).isEmpty());
        var defs = org.mcdashboard.placeholder.PlaceholderDefinition.parseAll(List.of(
                Map.of("id", "tps", "value", "%tps%", "access", "PUBLIC"),
                Map.of("id", "me", "value", "%me%", "access", "PLAYER")), m -> org.junit.jupiter.api.Assertions.fail(m));
        var placeholders = new org.mcdashboard.placeholder.PlaceholderService((t, u) -> "19.5", clock, 0, defs);
        PushService.Values values = PushService.values(placeholders, new org.mcdashboard.auth.PermissionCache());
        assertEquals(19.5, values.read("tps", "steve").getAsDouble());
        assertTrue(values.read("unknown", "steve").isEmpty());
        var text = new org.mcdashboard.placeholder.PlaceholderService((t, u) -> "hello", clock, 0, defs);
        assertTrue(PushService.values(text, new org.mcdashboard.auth.PermissionCache()).read("tps", "steve").isEmpty());
    }

    @Test
    void aClosedServiceDoesNotThrow() throws Exception {
        bind(0, 60);
        service.close();
        readings.put("server_tps", 1.0);
        service.evaluate();
        assertTrue(service.unbind("d1"));
        Thread.sleep(50);
        assertEquals(0, sends.size());
    }

    @Test
    void anOkAnswerClearsABackoffAndUnknownStatusesAreIgnored() throws Exception {
        bind(0, 60);
        reply.set("{\"results\":[{\"route\":\"" + ROUTE + "\",\"status\":\"muted\"},{\"route\":\"other\",\"status\":\"weird\"}]}");
        readings.put("server_tps", 1.0);
        service.evaluate();
        settle(1);
    }
}
