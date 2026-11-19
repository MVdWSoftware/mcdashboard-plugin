/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.tunnel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mcdashboard.tunnel.TunnelClientTest.next;
import static org.mcdashboard.tunnel.TunnelClientTest.readHead;
import static org.mcdashboard.tunnel.TunnelClientTest.send;
import static org.mcdashboard.tunnel.TunnelClientTest.untilType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mcdashboard.events.DeviceEvent;
import org.mcdashboard.events.EventBus;
import org.mcdashboard.web.WebSocketFrames;

class TunnelClientGapsTest {
    static final String KEY = "k".repeat(40);
    final List<AutoCloseable> closers = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : closers) c.close();
    }

    private ServerSocket proxy() throws IOException {
        ServerSocket p = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        closers.add(p);
        return p;
    }

    private TunnelClient client(ServerSocket proxy, EventBus bus, int livePort, List<String> warnings) {
        return client(proxy, bus, livePort, warnings, 10_000);
    }

    private TunnelClient client(ServerSocket proxy, EventBus bus, int livePort, List<String> warnings, long deadMs) {
        TunnelClient c = new TunnelClient(TunnelClient.validate("ws://127.0.0.1:" + proxy.getLocalPort() + "/tunnel", KEY, false), KEY, "127.0.0.1", 1, livePort,
                "Test", "1.0", bus, m -> {
                }, warnings::add).timing(50, 200, 300, 400, deadMs, 100);
        closers.add(c::stop);
        return c;
    }

    private static void waitFor(java.util.function.BooleanSupplier c) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("condition not met");
            Thread.sleep(20);
        }
    }

    private static Socket upgrade(ServerSocket proxy) throws Exception {
        Socket s = proxy.accept();
        s.setSoTimeout(5000);
        String head = readHead(s.getInputStream());
        String wsKey = head.lines().filter(l -> l.toLowerCase().startsWith("sec-websocket-key:")).findFirst().orElseThrow().split(":", 2)[1].trim();
        s.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: "
                + WebSocketFrames.acceptKey(wsKey) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().flush();
        assertEquals("hello", next(s.getInputStream()).get("t"));
        return s;
    }

    @Test
    void explainsWhyTheProxyRefusedTheHandshake() throws Exception {
        for (int status : new int[]{401, 404, 429, 500}) {
            ServerSocket p = proxy();
            List<String> warnings = new ArrayList<>();
            TunnelClient c = client(p, new EventBus(Clock.systemUTC()), 0, warnings);
            c.start();
            try (Socket s = p.accept()) {
                readHead(s.getInputStream());
                s.getOutputStream().write(("HTTP/1.1 " + status + " X\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").getBytes());
                s.getOutputStream().flush();
            }
            waitFor(() -> c.status().lastError() != null);
            String e = c.status().lastError();
            assertTrue(switch (status) {
                case 401 -> e.contains("tunnel.key");
                case 404 -> e.contains("tunnel.url");
                case 429 -> e.contains("rate limiting");
                default -> e.contains("HTTP 500");
            }, status + ": " + e);
            c.stop();
        }
    }

    @Test
    void speaksTheWholeProtocol() throws Exception {
        ServerSocket live = proxy(); // an echo "live feed"
        Thread echo = new Thread(() -> {
            try (Socket s = live.accept()) {
                byte[] b = new byte[64];
                int n;
                while ((n = s.getInputStream().read(b)) > 0) {
                    s.getOutputStream().write(b, 0, n);
                    s.getOutputStream().flush();
                }
            } catch (IOException ignored) {
                // closed
            }
        });
        echo.setDaemon(true);
        echo.start();
        EventBus bus = new EventBus(Clock.systemUTC());
        bus.publish(DeviceEvent.REVOKED, "old", "p");
        ServerSocket p = proxy();
        List<String> warnings = new ArrayList<>();
        TunnelClient c = client(p, bus, live.getLocalPort(), warnings);
        c.start();
        try (Socket s = upgrade(p)) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            // garbage and unknown frames are ignored; a fragmented message is reassembled
            send(out, Map.of("t", "nothing"));
            out.write(new byte[]{0x01, 0x07});
            out.write("{\"t\":\"r".getBytes());
            out.write(new byte[]{(byte) 0x80, 0x04});
            out.write("eady".getBytes()); // this is not valid JSON ({"t":"ready ... no closing), ignored
            out.write(new byte[]{0x01, 0x0c});
            out.write("{\"t\":\"ready\"".getBytes());
            out.write(new byte[]{(byte) 0x80, 0x01});
            out.write("}".getBytes());
            out.flush();
            waitFor(c::connected);
            send(out, Map.of("t", "x", "c", "oops"));
            // a ping from the proxy is answered with a pong
            out.write(new byte[]{(byte) 0x89, 0x00});
            out.flush();
            // channels: open, echo, a broken chunk, close
            send(out, Map.of("t", "open", "c", 7));
            send(out, Map.of("t", "d", "c", 7, "b", Base64.getEncoder().encodeToString("hey".getBytes())));
            Map<String, Object> d = untilType(in, "d");
            assertEquals("hey", new String(Base64.getDecoder().decode((String) d.get("b"))));
            send(out, Map.of("t", "d", "c", 7, "b", "!!!"));
            assertEquals("x", untilType(in, "x").get("t"));
            send(out, Map.of("t", "open", "c", 8));
            send(out, Map.of("t", "x", "c", 8));
            send(out, Map.of("t", "d", "c", "bad", "b", 5));
            // a request without a body or id still gets an answer (the loopback port is dead)
            send(out, Map.of("t", "req", "m", "GET", "p", "/v1/info"));
            assertEquals(502, ((Number) untilType(in, "res").get("s")).intValue());
            // events are streamed, with a replay from what the proxy says it has
            bus.publish(DeviceEvent.REVOKED, "dev", "p");
            assertEquals("dev", ((Map<?, ?>) untilType(in, "ev").get("e")).get("deviceId"));
            assertTrue(c.status().channels() >= 0);
        }
        waitFor(() -> c.status().lastError() != null);
        assertFalse(c.connected());
    }

    @Test
    void anUnknownResumePointAsksTheProxyToResetAndAReadyWithASinceIsReplayed() throws Exception {
        EventBus bus = new EventBus(Clock.systemUTC());
        bus.publish(DeviceEvent.REVOKED, "first", "p");
        ServerSocket p = proxy();
        TunnelClient c = client(p, bus, 0, new ArrayList<>());
        c.start();
        try (Socket s = upgrade(p)) {
            send(s.getOutputStream(), Map.of("t", "ready", "since", "unknown:1"));
            assertEquals("reset", untilType(s.getInputStream(), "reset").get("t"));
        }
        waitFor(() -> !c.connected());
        ServerSocket p2 = proxy();
        TunnelClient c2 = client(p2, bus, 0, new ArrayList<>());
        c2.start();
        try (Socket s = upgrade(p2)) {
            send(s.getOutputStream(), Map.of("t", "ready", "since", bus.epoch() + ":0"));
            assertEquals("first", ((Map<?, ?>) untilType(s.getInputStream(), "ev").get("e")).get("deviceId"));
        }
    }

    @Test
    void theProxyClosingOrSendingTooMuchEndsTheConnection() throws Exception {
        ServerSocket p = proxy();
        List<String> warnings = new ArrayList<>();
        TunnelClient c = client(p, new EventBus(Clock.systemUTC()), 0, warnings);
        c.start();
        try (Socket s = upgrade(p)) {
            send(s.getOutputStream(), Map.of("t", "ready"));
            waitFor(c::connected);
            WebSocketFrames.write(s.getOutputStream(), WebSocketFrames.CLOSE, new byte[]{3, (byte) 232, 'b', 'y', 'e'});
            s.getOutputStream().flush();
            waitFor(() -> c.status().lastError() != null);
            assertTrue(c.status().lastError().contains("closed by the proxy (1000, bye)"), c.status().lastError());
        }
        try (Socket s = upgrade(p)) {
            WebSocketFrames.write(s.getOutputStream(), WebSocketFrames.CLOSE, new byte[]{3, (byte) 232});
            waitFor(() -> c.status().lastError().contains("(1000)"));
        }
        try (Socket s = upgrade(p)) {
            send(s.getOutputStream(), Map.of("t", "ready"));
            waitFor(c::connected);
            // 5 MiB of text in 1 MiB pieces: more than the client accepts
            byte[] piece = new byte[1024 * 1024];
            java.util.Arrays.fill(piece, (byte) 'a');
            OutputStream out = s.getOutputStream();
            for (int i = 0; i < 6; i++) {
                out.write(new byte[]{(byte) (i == 0 ? 0x01 : 0x00), 127, 0, 0, 0, 0, 0, 0x10, 0, 0});
                out.write(piece);
            }
            out.flush();
            waitFor(() -> c.status().lastError().contains("larger than"));
        }
    }

    @Test
    void aSilentProxyIsReplacedAndPingsAreAnswered() throws Exception {
        ServerSocket p = proxy();
        TunnelClient c = client(p, new EventBus(Clock.systemUTC()), 0, new ArrayList<>());
        c.start();
        try (Socket s = upgrade(p)) {
            send(s.getOutputStream(), Map.of("t", "ready"));
            waitFor(c::connected);
            // answer the client's pings for a while: the connection stays up and a round trip is measured
            InputStream in = s.getInputStream();
            s.setSoTimeout(300);
            long end = System.currentTimeMillis() + 1000;
            while (System.currentTimeMillis() < end) {
                try {
                    var f = WebSocketFrames.read(in, 1 << 20);
                    if (f.opcode() == WebSocketFrames.PING) {
                        WebSocketFrames.write(s.getOutputStream(), WebSocketFrames.PONG, f.payload());
                        s.getOutputStream().flush();
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    // no frame in this slice
                }
            }
            assertTrue(c.connected());
        }
        ServerSocket q = proxy();
        TunnelClient silent = client(q, new EventBus(Clock.systemUTC()), 0, new ArrayList<>(), 600);
        silent.start();
        try (Socket s = upgrade(q)) {
            send(s.getOutputStream(), Map.of("t", "ready"));
            waitFor(() -> silent.status().lastError() != null && silent.status().lastError().contains("stopped answering"));
        }
    }

    @Test
    void validatesUrlsAndStopsCleanlyBeforeStarting() {
        assertThrows(IllegalArgumentException.class, () -> TunnelClient.validate("ws://bad host/x", KEY, false));
        assertThrows(IllegalArgumentException.class, () -> TunnelClient.validate(null, KEY, false));
        assertThrows(IllegalArgumentException.class, () -> TunnelClient.validate("wss://x.example/t", null, false));
        assertThrows(IllegalArgumentException.class, () -> TunnelClient.validate("ws:///nohost", KEY, false));
        TunnelClient c = new TunnelClient(TunnelClient.validate("wss://x.example/t", KEY, false), KEY, "127.0.0.1", 1, 0, "T", "1",
                new EventBus(Clock.systemUTC()), m -> {
        }, m -> {
        });
        assertFalse(c.connected());
        c.stop();
        c.stop();
    }
}
