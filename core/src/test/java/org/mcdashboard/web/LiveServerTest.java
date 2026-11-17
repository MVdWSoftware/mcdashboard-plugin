/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mcdashboard.TestContexts;
import org.mcdashboard.auth.*;
import org.mcdashboard.json.Json;
import org.mcdashboard.placeholder.*;

class LiveServerTest {
    @Test
    void handshakeSubscribeAndReceiveSenMl() throws Exception {
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "tps", "value", "%server_tps%", "type", "DECIMAL")), m -> fail(m));
        var ctx = TestContexts.context("T", "1", () -> ServerStatus.EMPTY, false, 5, 900, 900, 0,
                new PlaceholderService((t, u) -> "19.5", clock, 0, defs), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("d", ".json")), new OtpService(clock, 60), new RateLimiter(clock, 5, 1000), List.of(), Set.of(), new HistoryService(clock, defs), null, d -> {}, TestContexts.allowAll(), "en");
        LiveServer live = new LiveServer(ctx, 5);
        live.start("127.0.0.1", 0);
        try (Socket s = new Socket("127.0.0.1", live.port())) {
            s.setSoTimeout(5000);
            s.getOutputStream().write(("GET /v1/live HTTP/1.1\r\nHost: x\r\nUser-Agent: MCDashboard/1.0.0 (ios)\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            InputStream in = s.getInputStream();
            String head = readHead(in);
            assertTrue(head.startsWith("HTTP/1.1 101"));
            assertTrue(head.contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo="));
            s.getOutputStream().write(WebSocketFramesTest.masked(0x81, "{\"type\":\"subscribe\",\"ids\":[\"tps\",\"nope\"]}".getBytes()));
            boolean sawValue = false;
            for (int i = 0; i < 4 && !sawValue; i++) {
                String msg = readServerText(in);
                if (msg.contains("\"values\"")) {
                    var rec = (Map<?, ?>) ((List<?>) Json.parseObject(msg).get("values")).get(0);
                    assertEquals("tps", rec.get("n"));
                    assertEquals(19.5, rec.get("v"));
                    sawValue = true;
                } else {
                    assertTrue(msg.contains("subscribed") && !msg.contains("nope"));
                }
            }
            assertTrue(sawValue);
        } finally {
            live.stop();
        }
    }

    @Test
    void handshakeNeedsTheOfficialClient() throws Exception {
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "tps", "value", "%server_tps%", "type", "DECIMAL")), m -> fail(m));
        var ctx = TestContexts.context("T", "1", () -> ServerStatus.EMPTY, false, 5, 900, 900, 0,
                new PlaceholderService((t, u) -> "19.5", clock, 0, defs), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("d", ".json")), new OtpService(clock, 60), new RateLimiter(clock, 5, 1000), List.of(), Set.of(), new HistoryService(clock, defs), null, d -> {}, TestContexts.allowAll(), "en");
        LiveServer live = new LiveServer(ctx, 5, ClientPolicy.of(clock, List.of(), "2.0.0", 10, 10, m -> fail(m)));
        live.start("127.0.0.1", 0);
        try {
            assertEquals("HTTP/1.1 403", handshake(live.port(), null).substring(0, 12));
            assertEquals("HTTP/1.1 403", handshake(live.port(), "curl/8.0").substring(0, 12));
            assertEquals("HTTP/1.1 426", handshake(live.port(), "MCDashboard/1.9.9 (ios)").substring(0, 12));
            assertEquals("HTTP/1.1 101", handshake(live.port(), "MCDashboard/2.0.0 (ios)").substring(0, 12));
        } finally {
            live.stop();
        }
    }

    @Test
    void closesTheFeedOfARevokedDevice() throws Exception {
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "tps", "value", "%server_tps%", "type", "DECIMAL")), m -> fail(m));
        var devices = new DeviceStore(Files.createTempFile("d", ".json"));
        var tokens = new TokenService(new byte[48], clock);
        var ctx = TestContexts.context("T", "1", () -> ServerStatus.EMPTY, false, 5, 900, 900, 0,
                new PlaceholderService((t, u) -> "19.5", clock, 0, defs), tokens, devices, new OtpService(clock, 60),
                new RateLimiter(clock, 5, 1000), List.of(), Set.of(), new HistoryService(clock, defs), null, d -> {}, TestContexts.allowAll(), "en");
        var device = devices.add("p-uuid", "Steve", "phone", 5).orElseThrow();
        String token = tokens.issue(TokenService.Type.ACCESS, device.id(), device.playerUuid(), device.serial(), 900);
        LiveServer live = new LiveServer(ctx, 5);
        live.start("127.0.0.1", 0);
        try (Socket s = new Socket("127.0.0.1", live.port())) {
            s.setSoTimeout(5000);
            assertTrue(handshake(s, "MCDashboard/1.0.0 (ios)").startsWith("HTTP/1.1 101"));
            InputStream in = s.getInputStream();
            s.getOutputStream().write(WebSocketFramesTest.masked(0x81, ("{\"type\":\"auth\",\"token\":\"" + token + "\"}").getBytes()));
            assertTrue(readServerText(in).contains("authenticated"));
            devices.remove(device.id());
            try {
                while (in.read() >= 0) {
                    // drain until the server closes the connection
                }
            } catch (SocketException closed) {
                // also fine: the server reset it
            }
        } finally {
            live.stop();
        }
    }

    private static String handshake(Socket s, String userAgent) throws IOException {
        s.getOutputStream().write(("GET /v1/live HTTP/1.1\r\nHost: x\r\n" + (userAgent == null ? "" : "User-Agent: " + userAgent + "\r\n")
                + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        return readHead(s.getInputStream());
    }

    private static String handshake(int port, String userAgent) throws IOException {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            return handshake(s, userAgent);
        }
    }

    static String readHead(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (!sb.toString().endsWith("\r\n\r\n")) sb.append((char) in.read());
        return sb.toString();
    }

    static String readServerText(InputStream in) throws IOException {
        int b0 = in.read();
        assertEquals(0x81, b0);
        int len = in.read();
        if (len == 126) len = (in.read() << 8) | in.read();
        return new String(in.readNBytes(len), StandardCharsets.UTF_8);
    }
}
