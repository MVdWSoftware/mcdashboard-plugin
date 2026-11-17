package org.mcdashboard.events;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.json.Json;

class EventsTest {
    @Test
    void idsAreOrderedAndReplayCoversWhatWasMissed() {
        EventBus bus = new EventBus(Clock.systemUTC());
        DeviceEvent a = bus.publish(DeviceEvent.REVOKED, "d1", "p1");
        DeviceEvent b = bus.publish(DeviceEvent.REVOKED, "d2", "p1");
        bus.publish(DeviceEvent.REVOKED, "d3", "p2");
        assertEquals(a.epoch() + ":1", a.id());
        assertEquals(b.seq(), a.seq() + 1);

        List<String> got = new ArrayList<>();
        bus.subscribe(a.id(), e -> got.add(e.deviceId()), () -> fail("no reset expected"));
        assertEquals(List.of("d2", "d3"), got, "everything after the last seen id, once");
        bus.publish(DeviceEvent.REVOKED, "d4", "p2");
        assertEquals(List.of("d2", "d3", "d4"), got, "then live events follow without a gap or duplicate");
    }

    @Test
    void aNewConsumerGetsNoHistoryAndAnUnresumableOneGetsAReset() {
        EventBus bus = new EventBus(Clock.systemUTC());
        bus.publish(DeviceEvent.REVOKED, "d1", "p1");
        List<String> got = new ArrayList<>();
        bus.subscribe(null, e -> got.add(e.deviceId()), () -> fail("fresh consumers need no reset"));
        assertTrue(got.isEmpty());

        AtomicInteger resets = new AtomicInteger();
        bus.subscribe("someoldrun:5", e -> { }, resets::incrementAndGet); // plugin restarted
        bus.subscribe(bus.epoch() + ":999", e -> { }, resets::incrementAndGet); // ahead of us: cannot be ours
        bus.subscribe("garbage", e -> { }, resets::incrementAndGet);
        for (int i = 0; i < 300; i++) bus.publish(DeviceEvent.REVOKED, "x" + i, "p");
        bus.subscribe(bus.epoch() + ":1", e -> { }, resets::incrementAndGet); // history moved past it
        assertEquals(4, resets.get());
    }

    @Test
    void aRemovedDeviceIsPublishedOnceWhoeverRemovesIt() throws Exception {
        DeviceStore store = new DeviceStore(Files.createTempFile("devices", ".json"));
        EventBus bus = new EventBus(Clock.systemUTC());
        List<DeviceEvent> seen = new ArrayList<>();
        bus.subscribe(null, seen::add, () -> { });
        store.onRemoved(d -> bus.publish(DeviceEvent.REVOKED, d.id(), d.playerUuid()));
        store.onRemoved(d -> { throw new IllegalStateException("broken listener"); });

        var d = store.add("uuid-1", "Steve", "Pixel", 5).orElseThrow();
        assertTrue(store.remove(d.id()), "a failing listener does not undo the revocation");
        assertFalse(store.remove(d.id()));
        assertFalse(store.remove("nope"));
        assertEquals(1, seen.size());
        assertEquals(d.id(), seen.get(0).deviceId());
        assertEquals("uuid-1", seen.get(0).player());
        assertEquals(List.of("type", "epoch", "seq", "time", "deviceId", "player"), List.copyOf(seen.get(0).toJson().keySet()));
    }

    @Test
    void webhookIsSignedAndRetriedUntilAccepted() throws Exception {
        BlockingQueue<Object[]> received = new LinkedBlockingQueue<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            received.add(new Object[]{body, ex.getRequestHeaders().getFirst("X-MCDashboard-Signature"),
                    ex.getRequestHeaders().getFirst("X-MCDashboard-Timestamp"), ex.getRequestHeaders().getFirst("X-MCDashboard-Delivery"),
                    ex.getRequestHeaders().getFirst("X-MCDashboard-Event")});
            ex.sendResponseHeaders(calls.incrementAndGet() == 1 ? 500 : 204, -1);
            ex.close();
        });
        server.start();
        try {
            String secret = "0123456789abcdef-secret";
            EventBus bus = new EventBus(Clock.systemUTC());
            WebhookDispatcher hooks = new WebhookDispatcher(
                    List.of(new WebhookDispatcher.Hook(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook"), secret)),
                    Clock.systemUTC(), "test", m -> fail(m), new long[]{0, 0});
            hooks.start(bus);
            DeviceEvent e = bus.publish(DeviceEvent.REVOKED, "dev1", "uuid-1");

            Object[] first = received.poll(5, TimeUnit.SECONDS);
            Object[] retry = received.poll(5, TimeUnit.SECONDS);
            assertNotNull(first);
            assertNotNull(retry, "a 500 is retried");
            assertNull(received.poll(300, TimeUnit.MILLISECONDS), "a 2xx ends the retries");
            byte[] body = (byte[]) retry[0];
            assertEquals(WebhookDispatcher.sign(secret, Long.parseLong((String) retry[2]), body), retry[1]);
            assertNotEquals(WebhookDispatcher.sign("another-secret-value", Long.parseLong((String) retry[2]), body), retry[1]);
            assertEquals(e.id(), retry[3]);
            assertEquals("device.revoked", retry[4]);
            var json = Json.parseObject(new String(body, StandardCharsets.UTF_8));
            assertEquals("dev1", json.get("deviceId"));
            assertEquals("uuid-1", json.get("player"));
            hooks.stop();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void webhookGivesUpAfterTheLastRetryAndSaysSo() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/", ex -> { calls.incrementAndGet(); ex.sendResponseHeaders(503, -1); ex.close(); });
        server.start();
        try {
            BlockingQueue<String> warnings = new LinkedBlockingQueue<>();
            EventBus bus = new EventBus(Clock.systemUTC());
            var hooks = new WebhookDispatcher(List.of(new WebhookDispatcher.Hook(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), "0123456789abcdef")),
                    Clock.systemUTC(), "test", warnings::add, new long[]{0, 0});
            hooks.start(bus);
            bus.publish(DeviceEvent.REVOKED, "d", "p");
            String w = warnings.poll(5, TimeUnit.SECONDS);
            assertNotNull(w);
            assertTrue(w.contains("giving up"), w);
            assertEquals(3, calls.get(), "first attempt plus two retries");
            hooks.stop();
        } finally {
            server.stop(0);
        }
    }

    /** The same vector is asserted by MCDashboard-proxy (test/events.test.ts), so the two sides cannot drift apart. */
    @Test
    void signatureMatchesTheSharedTestVector() {
        String body = "{\"type\":\"device.revoked\",\"epoch\":\"a1b2c3d4\",\"seq\":1,\"time\":1700000000,\"deviceId\":\"d1\",\"player\":\"p1\"}";
        assertEquals("sha256=3c3de4487ff7c14502e2546a8887a60bf3c93e72e75887eb89aff022e1eb4a11",
                WebhookDispatcher.sign("0123456789abcdef-secret", 1700000000L, body.getBytes(StandardCharsets.UTF_8)));
    }
}
