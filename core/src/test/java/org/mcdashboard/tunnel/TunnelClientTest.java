/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.tunnel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mcdashboard.TestContexts;
import org.mcdashboard.auth.*;
import org.mcdashboard.events.DeviceEvent;
import org.mcdashboard.events.EventBus;
import org.mcdashboard.json.Json;
import org.mcdashboard.placeholder.*;
import org.mcdashboard.web.*;

class TunnelClientTest {
    @Test
    void readsOnlyUsableTunnelFeatureNames() {
        assertEquals(Set.of("presets", "history-points"), TunnelClient.features(List.of("presets", 5, "", "history-points")));
        assertEquals(Set.of(), TunnelClient.features("nope"));
        assertEquals(Set.of(), TunnelClient.features(null));
        List<Object> many = new java.util.ArrayList<>();
        many.add("x".repeat(65)); // too long
        many.add("    "); // blank
        for (int i = 0; i < 70; i++) many.add("f" + i);
        Set<String> kept = TunnelClient.features(many);
        assertEquals(64, kept.size());
        assertFalse(kept.contains("x".repeat(65)));
        assertTrue(kept.contains("f63"));
        assertFalse(kept.contains("f64"));
    }

    @Test
    void refusesInsecureOrWeakConfiguration() {
        String key = "k".repeat(40);
        assertThrows(IllegalArgumentException.class, () -> TunnelClient.validate("wss://p.example.com/tunnel", "short", false));
        assertThrows(IllegalArgumentException.class, () -> TunnelClient.validate("ws://p.example.com/tunnel", key, false));
        assertThrows(IllegalArgumentException.class, () -> TunnelClient.validate("https://p.example.com/tunnel", key, false));
        assertThrows(IllegalArgumentException.class, () -> TunnelClient.validate("", key, false));
        assertDoesNotThrow(() -> TunnelClient.validate("wss://p.example.com/tunnel", key, false));
        assertDoesNotThrow(() -> TunnelClient.validate("ws://10.0.0.5:8080/tunnel", key, false));
        assertDoesNotThrow(() -> TunnelClient.validate("ws://p.example.com/tunnel", key, true));
        assertDoesNotThrow(() -> TunnelClient.validate("ws://[::1]:8080/tunnel", key, false));
        assertThrows(IllegalArgumentException.class, () -> TunnelClient.validate("ws://10.evil.example/tunnel", key, false),
                "a host name that starts like a private address is still public");
    }

    @Test
    void forwardsOnlyTheApiAndNeverTheEventStream() {
        assertTrue(LoopbackRequests.allowed("/v1/values?ids=a,b"));
        assertTrue(LoopbackRequests.allowed("/v1/placeholders/a..b"));
        for (String path : new String[]{"/v1/events", "/v1/events?x=1", "/v1/%65vents", "/v1/./events", "/v1/x/../events",
                "/v1/../secret", "/v2/info", "v1/info", "http://other/v1/info"}) {
            assertFalse(LoopbackRequests.allowed(path), path);
        }
    }

    @Test
    void anOlderProxyWithoutProtocolKeepsConnectingAndOffersNoExtraFeatures() throws Exception {
        String key = "k".repeat(40);
        List<String> infos = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            TunnelClient client = new TunnelClient(TunnelClient.validate("ws://127.0.0.1:" + proxy.getLocalPort() + "/tunnel", key, false),
                    key, "127.0.0.1", 1, 0, "Test", "1.0", new EventBus(Clock.systemUTC()), infos::add, m -> {})
                    .timing(50, 200, 300, 400, 600, 100);
            client.start();
            try (Socket ignored = accept(proxy, key, true)) {
                long deadline = System.currentTimeMillis() + 3000;
                while (!client.connected() && System.currentTimeMillis() < deadline) Thread.sleep(20);
                assertTrue(client.connected());
                assertEquals(0, client.proxyProtocol());
                assertTrue(client.proxyFeatures().isEmpty());
                assertTrue(infos.stream().anyMatch(m -> m.contains("does not report its tunnel protocol")), infos.toString());
            } finally {
                client.stop();
            }
        }
    }

    /** A fake proxy: accepts the plugin's connection, checks the key, then drives the protocol like the real one. */
    @Test
    void answersRequestsOverTheTunnelAndSendsEvents() throws Exception {
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "online", "value", "%server_online%", "type", "INTEGER")), m -> fail(m));
        var devices = new DeviceStore(Files.createTempFile("devices", ".json"));
        var ctx = TestContexts.context("Test", "1.0", () -> ServerStatus.EMPTY, false, 2, 900, 3600, 0,
                new PlaceholderService((t, u) -> "7", clock, 1000, defs), new TokenService(new byte[48], clock),
                devices, new OtpService(clock, 60), new RateLimiter(clock, 100, 60000), List.of(), Set.of("127.0.0.1"),
                new HistoryService(clock, defs), null, d -> {}, TestContexts.allowAll(), "en");
        HttpApi api = new HttpApi(ctx);
        api.start("127.0.0.1", 0, null);
        EventBus bus = new EventBus(clock);
        devices.onRemoved(d -> bus.publish(DeviceEvent.REVOKED, d.id(), d.playerUuid()));

        String key = "k".repeat(40);
        try (ServerSocket proxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            TunnelClient client = new TunnelClient(TunnelClient.validate("ws://127.0.0.1:" + proxy.getLocalPort() + "/tunnel", key, false),
                    key, "127.0.0.1", api.port(), 0, "Test", "1.0", bus, m -> {}, m -> {});
            client.start();
            try (Socket s = proxy.accept()) {
                s.setSoTimeout(5000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                String head = readHead(in);
                assertTrue(head.contains("Authorization: Bearer " + key) || head.toLowerCase().contains("authorization: bearer " + key));
                String wsKey = head.lines().filter(l -> l.toLowerCase().startsWith("sec-websocket-key:")).findFirst().orElseThrow().split(":", 2)[1].trim();
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                        + WebSocketFrames.acceptKey(wsKey) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();

                Map<String, Object> hello = next(in);
                assertEquals("hello", hello.get("t"));
                assertEquals(TunnelClient.PROTOCOL, ((Number) hello.get("protocol")).intValue());
                send(out, Map.of("t", "ready", "since", "", "protocol", 7,
                        "features", java.util.Arrays.asList("presets", 5, "history-points")));
                long protocolDeadline = System.currentTimeMillis() + 3000;
                while (client.proxyProtocol() != 7 && System.currentTimeMillis() < protocolDeadline) Thread.sleep(20);
                assertEquals(7, client.proxyProtocol());
                assertEquals(Set.of("presets", "history-points"), client.proxyFeatures());

                // a REST request is answered by the plugin's own API
                send(out, Map.of("t", "req", "id", 1, "m", "GET", "p", "/v1/values?ids=online",
                        "h", Map.of("user-agent", "MCDashboard/1.0.0 (dev)", "x-forwarded-for", "203.0.113.9")));
                Map<String, Object> res = untilType(in, "res");
                assertEquals(1, ((Number) res.get("id")).intValue());
                assertEquals(200, ((Number) res.get("s")).intValue());
                assertTrue(new String(Base64.getDecoder().decode((String) res.get("b")), StandardCharsets.UTF_8).contains("online"));

                // the event stream is not reachable through the tunnel
                send(out, Map.of("t", "req", "id", 2, "m", "GET", "p", "/v1/events", "h", Map.of()));
                assertEquals(404, ((Number) untilType(in, "res").get("s")).intValue());
                send(out, Map.of("t", "req", "id", 3, "m", "GET", "p", "/etc/passwd", "h", Map.of()));
                assertEquals(404, ((Number) untilType(in, "res").get("s")).intValue());

                // a revocation arrives as an event frame
                var d = devices.add("p-uuid", "Steve", "phone", 5).orElseThrow();
                devices.remove(d.id());
                Map<String, Object> ev = untilType(in, "ev");
                assertEquals(d.id(), ((Map<?, ?>) ev.get("e")).get("deviceId"));
            } finally {
                client.stop();
            }
        } finally {
            api.stop();
        }
    }

    static String readHead(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (!sb.toString().endsWith("\r\n\r\n")) sb.append((char) in.read());
        return sb.toString();
    }

    static Map<String, Object> next(InputStream in) throws IOException {
        while (true) {
            var f = WebSocketFrames.read(in, 4 * 1024 * 1024);
            if (f.opcode() == WebSocketFrames.TEXT) return Json.parseObject(f.text());
        }
    }

    static Map<String, Object> untilType(InputStream in, String type) throws IOException {
        for (int i = 0; i < 20; i++) {
            Map<String, Object> m = next(in);
            if (type.equals(m.get("t"))) return m;
        }
        return fail("no " + type + " frame");
    }

    /** The server side of a WebSocket sends unmasked frames. */
    static void send(OutputStream out, Map<String, Object> m) throws IOException {
        WebSocketFrames.writeText(out, Json.write(m));
        out.flush();
    }

    /** Accepts one connection, completes the handshake, and returns the socket (after the hello). */
    private static Socket accept(ServerSocket proxy, String key, boolean answerReady) throws Exception {
        Socket s = proxy.accept();
        s.setSoTimeout(5000);
        String head = readHead(s.getInputStream());
        assertTrue(head.toLowerCase().contains("authorization: bearer " + key));
        String wsKey = head.lines().filter(l -> l.toLowerCase().startsWith("sec-websocket-key:")).findFirst().orElseThrow().split(":", 2)[1].trim();
        s.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + WebSocketFrames.acceptKey(wsKey) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().flush();
        assertEquals("hello", next(s.getInputStream()).get("t"));
        if (answerReady) send(s.getOutputStream(), Map.of("t", "ready"));
        return s;
    }

    private static TunnelClient client(ServerSocket proxy, String key, EventBus bus, AtomicInteger warnings) {
        return new TunnelClient(TunnelClient.validate("ws://127.0.0.1:" + proxy.getLocalPort() + "/tunnel", key, false), key, "127.0.0.1", 1, 0,
                "Test", "1.0", bus, m -> {}, m -> warnings.incrementAndGet())
                .timing(50, 200, 300, 400, 600, 100);
    }

    @Test
    void reconnectsAfterTheProxyDropsTheConnection() throws Exception {
        String key = "k".repeat(40);
        var warnings = new AtomicInteger();
        try (ServerSocket proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            TunnelClient c = client(proxy, key, new EventBus(Clock.systemUTC()), warnings);
            c.start();
            try {
                Socket first = accept(proxy, key, true);
                long deadline = System.currentTimeMillis() + 3000;
                while (!c.connected() && System.currentTimeMillis() < deadline) Thread.sleep(20);
                assertTrue(c.connected());
                first.close(); // the proxy restarts
                deadline = System.currentTimeMillis() + 3000;
                while (c.connected() && System.currentTimeMillis() < deadline) Thread.sleep(20);
                assertFalse(c.connected());
                Socket second = accept(proxy, key, true); // the reconnect loop found it again
                deadline = System.currentTimeMillis() + 3000;
                while (!c.connected() && System.currentTimeMillis() < deadline) Thread.sleep(20);
                assertTrue(c.connected());
                assertEquals(1, warnings.get(), "one warning for the outage, not one per attempt");
                second.close();
            } finally {
                c.stop();
            }
        }
    }

    @Test
    void keepsRetryingWhileTheProxyIsDownAndBacksOff() throws Exception {
        String key = "k".repeat(40);
        var warnings = new AtomicInteger();
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        TunnelClient c = new TunnelClient(TunnelClient.validate("ws://127.0.0.1:" + port + "/tunnel", key, false), key, "127.0.0.1", 1, 0, "Test", "1.0",
                new EventBus(Clock.systemUTC()), m -> {}, m -> warnings.incrementAndGet()).timing(50, 200, 300, 400, 600, 100);
        c.start();
        try {
            Thread.sleep(1200);
            var st = c.status();
            assertFalse(st.connected());
            assertTrue(st.attempts() >= 3, "kept retrying: " + st.attempts());
            assertTrue(st.attempts() < 30, "backed off instead of spinning: " + st.attempts());
            assertNotNull(st.lastError());
            assertTrue(warnings.get() >= 1 && warnings.get() * 3 < st.attempts(), "logs the outage, not every attempt: " + warnings.get());
            // the proxy comes up: the next attempt connects
            try (ServerSocket proxy = new ServerSocket(port, 5, InetAddress.getLoopbackAddress())) {
                Socket s = accept(proxy, key, true);
                long deadline = System.currentTimeMillis() + 3000;
                while (!c.connected() && System.currentTimeMillis() < deadline) Thread.sleep(20);
                assertTrue(c.connected());
                s.close();
            }
        } finally {
            c.stop();
        }
    }

    @Test
    void replacesAConnectionTheProxyNeverAnswers() throws Exception {
        String key = "k".repeat(40);
        try (ServerSocket proxy = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            TunnelClient c = client(proxy, key, new EventBus(Clock.systemUTC()), new AtomicInteger());
            c.start();
            try {
                Socket silent = accept(proxy, key, false); // upgraded, hello read, but never ready: a dead endpoint
                Socket again = accept(proxy, key, true);   // the client gave up on it and dialled again
                long deadline = System.currentTimeMillis() + 3000;
                while (!c.connected() && System.currentTimeMillis() < deadline) Thread.sleep(20);
                assertTrue(c.connected());
                silent.close();
                again.close();
            } finally {
                c.stop();
            }
        }
    }
}
