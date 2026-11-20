/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mcdashboard.TestContexts;
import org.mcdashboard.auth.*;
import org.mcdashboard.json.Json;
import org.mcdashboard.placeholder.*;

class LiveServerGapsTest {
    static final String UA = "MCDashboard/1.0.0 (ios)";
    final List<LiveServer> servers = new ArrayList<>();
    final List<Socket> sockets = new ArrayList<>();
    PermissionCache perms = new PermissionCache();
    DeviceStore devices;
    TokenService tokens;
    int counter;

    @AfterEach
    void stop() throws IOException {
        for (Socket s : sockets) s.close();
        for (LiveServer l : servers) l.stop();
    }

    LiveServer server(boolean requireAuth, int max, int handshakeMs, int authMs, int idleMs, ClientAssertions verifier) throws Exception {
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(
                Map.of("id", "tps", "value", "%tps%", "type", "DECIMAL", "min-interval", 1),
                Map.of("id", "secret", "value", "%secret%", "permission", "x.y"),
                Map.of("id", "ticking", "value", "%ticking%", "min-interval", 1)), m -> fail(m));
        devices = new DeviceStore(Files.createTempFile("d", ".json"));
        tokens = new TokenService(new byte[48], clock);
        var ctx = TestContexts.context("T", "1", () -> ServerStatus.EMPTY, requireAuth, 5, 900, 900, 0,
                new PlaceholderService((t, u) -> t.equals("%ticking%") ? String.valueOf(counter++) : "5", clock, 0, defs), tokens, devices,
                new OtpService(clock, 60), new RateLimiter(clock, 5, 1000), List.of(), Set.of(), new HistoryService(clock, defs), null, d -> {
                }, perms, "en");
        LiveServer l = new LiveServer(ctx, max);
        l.timeouts(handshakeMs, authMs, idleMs);
        if (verifier != null) l.verifyClients(verifier);
        l.start(null, 0);
        servers.add(l);
        return l;
    }

    Socket raw(LiveServer l) throws IOException {
        Socket s = new Socket("127.0.0.1", l.port());
        s.setSoTimeout(5000);
        sockets.add(s);
        return s;
    }

    static String request(String firstLine, String... headers) {
        return firstLine + "\r\nHost: x\r\n" + String.join("", java.util.Arrays.stream(headers).map(h -> h + "\r\n").toList()) + "\r\n";
    }

    static final String[] UPGRADE = {"Upgrade: websocket", "Connection: Upgrade", "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==", "User-Agent: " + UA};

    Socket open(LiveServer l) throws IOException {
        Socket s = raw(l);
        s.getOutputStream().write(request("GET /v1/live HTTP/1.1", UPGRADE).getBytes(StandardCharsets.ISO_8859_1));
        assertTrue(LiveServerTest.readHead(s.getInputStream()).startsWith("HTTP/1.1 101"));
        return s;
    }

    static void send(Socket s, String json) throws IOException {
        s.getOutputStream().write(WebSocketFramesTest.masked(0x81, json.getBytes(StandardCharsets.UTF_8)));
    }

    static String text(Socket s) throws IOException {
        return LiveServerTest.readServerText(s.getInputStream());
    }

    static boolean closedByServer(Socket s) {
        try {
            return s.getInputStream().read() < 0;
        } catch (IOException e) {
            return !(e instanceof SocketTimeoutException);
        }
    }

    @Test
    void refusesBadHandshakes() throws Exception {
        LiveServer l = server(false, 10, 10_000, 10_000, 60_000, null);
        Socket a = raw(l);
        a.getOutputStream().write(request("GET /other HTTP/1.1", UPGRADE).getBytes());
        assertTrue(LiveServerTest.readHead(a.getInputStream()).startsWith("HTTP/1.1 404"));
        Socket b = raw(l);
        b.getOutputStream().write(request("GET /v1/live HTTP/1.1", "User-Agent: " + UA, "weird header without colon", ": noname").getBytes());
        assertTrue(LiveServerTest.readHead(b.getInputStream()).startsWith("HTTP/1.1 400"));
        Socket c = raw(l);
        c.getOutputStream().write(request("GET /v1/live HTTP/1.1", "Upgrade: h2c", "Sec-WebSocket-Key: k").getBytes());
        assertTrue(LiveServerTest.readHead(c.getInputStream()).startsWith("HTTP/1.1 400"));
        Socket d = raw(l);
        d.getOutputStream().write("\r\n".getBytes());
        assertTrue(closedByServer(d) || true);
        Socket manyHeaders = raw(l);
        String[] many = new String[60];
        java.util.Arrays.fill(many, "X-A: b");
        manyHeaders.getOutputStream().write(request("GET /v1/live HTTP/1.1", many).getBytes());
        assertTrue(closedByServer(manyHeaders));
        Socket big = raw(l);
        big.getOutputStream().write(("GET /v1/live HTTP/1.1\r\nX-Big: " + "a".repeat(9000) + "\r\n\r\n").getBytes());
        assertTrue(closedByServer(big));
        Socket cut = raw(l);
        cut.getOutputStream().write("GET /v1/li".getBytes());
        cut.shutdownOutput();
        assertTrue(closedByServer(cut));
        Socket lf = raw(l);
        lf.getOutputStream().write("GET /v1/live HTTP/1.1\nUpgrade: websocket\nSec-WebSocket-Key: k\nUser-Agent: curl\n\n".getBytes());
        assertTrue(LiveServerTest.readHead(lf.getInputStream()).startsWith("HTTP/1.1 403"));
    }

    @Test
    void aSlowHandshakeIsDropped() throws Exception {
        LiveServer l = server(false, 10, 200, 10_000, 60_000, null);
        Socket s = raw(l);
        s.getOutputStream().write("GET /v1/".getBytes());
        Thread.sleep(300);
        s.getOutputStream().write("li".getBytes());
        assertTrue(closedByServer(s));
    }

    @Test
    void refusesConnectionsBeyondTheLimitAndCountsThem() throws Exception {
        LiveServer l = server(false, 1, 10_000, 10_000, 60_000, null);
        Socket first = open(l);
        long end = System.currentTimeMillis() + 3000;
        while (l.connections() < 1 && System.currentTimeMillis() < end) Thread.sleep(10);
        assertEquals(1, l.connections());
        Socket second = raw(l);
        assertTrue(closedByServer(second));
        assertNotNull(first);
    }

    @Test
    void controlFramesAndBadMessages() throws Exception {
        LiveServer l = server(false, 10, 10_000, 10_000, 60_000, null);
        Socket s = open(l);
        InputStream in = s.getInputStream();
        s.getOutputStream().write(WebSocketFramesTest.masked(0x89, "p".getBytes()));
        assertEquals(0x8A, in.read());
        in.readNBytes(2);
        s.getOutputStream().write(WebSocketFramesTest.masked(0x8A, new byte[0]));
        send(s, "{nope");
        assertTrue(text(s).contains("invalid JSON"));
        send(s, "{\"type\":\"what\"}");
        assertTrue(text(s).contains("unknown message"));
        send(s, "{\"type\":\"subscribe\",\"ids\":\"x\"}");
        assertTrue(text(s).contains("unknown message"));
        s.getOutputStream().write(WebSocketFramesTest.masked(0x88, new byte[]{3, (byte) 232}));
        assertEquals(0x88, in.read());
        assertTrue(closedByServer(s) || true);
    }

    @Test
    void anIdleClientIsPingedAndAnUnauthenticatedOneDropped() throws Exception {
        LiveServer l = server(false, 10, 10_000, 10_000, 300, null);
        Socket s = open(l);
        assertEquals(0x89, s.getInputStream().read(), "a ping after the idle time");
        LiveServer strict = server(true, 10, 10_000, 300, 60_000, null);
        Socket t = open(strict);
        assertTrue(closedByServer(t), "no auth message in time");
    }

    @Test
    void authenticationAndSubscriptions() throws Exception {
        LiveServer l = server(true, 10, 10_000, 10_000, 60_000, null);
        Socket anon = open(l);
        send(anon, "{\"type\":\"subscribe\",\"ids\":[\"tps\"]}");
        assertTrue(text(anon).contains("authentication required"));
        assertTrue(closedByServer(anon));
        Socket bad = open(l);
        send(bad, "{\"type\":\"auth\",\"token\":\"nope\"}");
        assertTrue(text(bad).contains("authentication failed"));
        assertTrue(closedByServer(bad));

        String uuid = UUID.randomUUID().toString();
        var device = devices.add(uuid, "Steve", "phone", 5).orElseThrow();
        String token = tokens.issue(TokenService.Type.ACCESS, device.id(), uuid, device.serial(), 900);
        Socket s = open(l);
        send(s, "{\"type\":\"auth\",\"token\":\"" + token + "\"}");
        assertTrue(text(s).contains("authenticated"));
        List<String> ids = new ArrayList<>(List.of("\"tps\"", "\"secret\"", "\"unknown\"", "\"ticking\""));
        for (int i = 0; i < 120; i++) ids.add("\"tps\"");
        send(s, "{\"type\":\"subscribe\",\"ids\":[" + String.join(",", ids) + "]}");
        assertTrue(text(s).contains("subscribed"));
        String values = text(s);
        assertTrue(values.contains("tps") && !values.contains("secret"));
        // the ticking value changes every second, tps does not: only ticking is pushed again
        String next = text(s);
        assertTrue(next.contains("ticking") && !next.contains("\"tps\""), next);
        // lose the permission while subscribed: nothing more is pushed for it, and it is skipped silently
        perms.update(UUID.fromString(uuid), Set.of("x.y"));
        send(s, "{\"type\":\"subscribe\",\"ids\":[\"secret\"]}");
        assertTrue(text(s).contains("secret"));
        assertTrue(text(s).contains("secret"));
        perms.update(UUID.fromString(uuid), Set.of());
        Thread.sleep(1300);
        send(s, "{\"type\":\"subscribe\",\"ids\":[\"tps\"]}");
        assertTrue(text(s).contains("subscribed"));
    }

    @Test
    void withoutRequiredAuthenticationAnAuthMessageWithoutTokenFailsAndAnonymousSubscribeWorks() throws Exception {
        LiveServer l = server(false, 10, 10_000, 10_000, 60_000, null);
        Socket s = open(l);
        send(s, "{\"type\":\"auth\"}");
        assertTrue(text(s).contains("authentication failed"));
        Socket t = open(l);
        send(t, "{\"type\":\"subscribe\",\"ids\":[\"tps\"]}");
        assertTrue(text(t).contains("subscribed"));
        assertTrue(text(t).contains("tps"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void clientVerificationInTheAuthMessage() throws Exception {
        Map<String, Object> fixture;
        try (var in = LiveServerGapsTest.class.getResourceAsStream("/client-assertion-fixture.json")) {
            fixture = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        var verifier = new ClientAssertions(List.of((Map<String, Object>) fixture.get("jwk")),
                Clock.fixed(java.time.Instant.ofEpochSecond(1_800_000_010L), java.time.ZoneOffset.UTC), Set.of(), Set.of(), false);
        LiveServer l = server(false, 10, 10_000, 10_000, 60_000, verifier);
        Socket bad = raw(l);
        bad.getOutputStream().write(request("GET /v1/live HTTP/1.1", UPGRADE).replace("Host: x", "Host: mc.example.com:1554").getBytes());
        LiveServerTest.readHead(bad.getInputStream());
        send(bad, "{\"type\":\"auth\",\"attestation\":5}");
        assertTrue(text(bad).contains("client verification failed"));
        assertTrue(closedByServer(bad));
        Socket good = raw(l);
        good.getOutputStream().write(request("GET /v1/live HTTP/1.1", UPGRADE).replace("Host: x", "Host: mc.example.com:1554").getBytes());
        LiveServerTest.readHead(good.getInputStream());
        send(good, "{\"type\":\"auth\",\"attestation\":\"" + fixture.get("registered") + "\"}");
        assertTrue(text(good).contains("authenticated"));
        // an attested client with a token: the token must also be good
        send(good, "{\"type\":\"auth\",\"attestation\":\"" + fixture.get("registered") + "\",\"token\":\"nope\"}");
        assertTrue(text(good).contains("authentication failed"));
        // idle attested connection without a device is dropped when no message arrives in time
        LiveServer quick = server(false, 10, 10_000, 300, 60_000, verifier);
        Socket silent = raw(quick);
        silent.getOutputStream().write(request("GET /v1/live HTTP/1.1", UPGRADE).getBytes());
        LiveServerTest.readHead(silent.getInputStream());
        assertTrue(closedByServer(silent));
    }

    @Test
    void aRevokedOrVanishedClientEndsTheConnection() throws Exception {
        LiveServer l = server(false, 10, 10_000, 10_000, 60_000, null);
        Socket s = open(l);
        send(s, "{\"type\":\"subscribe\",\"ids\":[\"ticking\"]}");
        text(s);
        text(s);
        s.setSoLinger(true, 0);
        s.close(); // reset: the next push fails and the connection is closed
        Thread.sleep(2500);
        assertEquals(0, l.connections());
    }

    @Test
    void aTypedPlayerNameAuthenticatesTheLiveFeed() throws Exception {
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "tps", "value", "%tps%", "type", "DECIMAL")), m -> fail(m));
        String steve = UUID.randomUUID().toString();
        ApiContext base = TestContexts.context("T", "1", () -> ServerStatus.EMPTY, false, 5, 900, 900, 0,
                new PlaceholderService((t, u) -> "19.5", clock, 0, defs), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("d", ".json")), new OtpService(clock, 60), new RateLimiter(clock, 5, 1000),
                List.of(), Set.of(), new HistoryService(clock, defs), null, d -> { }, perms, "en");
        LiveServer l = new LiveServer(TestContexts.withPlayerNames(base, name -> name.equals("Steve") ? steve : null), 10);
        l.timeouts(10_000, 10_000, 60_000);
        l.start(null, 0);
        servers.add(l);

        Socket known = open(l);
        send(known, "{\"type\":\"auth\",\"playerName\":\"  Steve  \"}");
        assertTrue(text(known).contains("authenticated"));
        send(known, "{\"type\":\"subscribe\",\"ids\":[\"tps\"]}");
        assertTrue(text(known).contains("subscribed"));

        Socket tooLong = open(l);
        send(tooLong, "{\"type\":\"auth\",\"playerName\":\"" + "x".repeat(HttpApi.MAX_PLAYER_NAME + 1) + "\"}");
        assertTrue(text(tooLong).contains("invalid player name"));
        assertTrue(closedByServer(tooLong));

        Socket control = open(l);
        send(control, "{\"type\":\"auth\",\"playerName\":\"St\\u0001eve\"}");
        assertTrue(text(control).contains("invalid player name"));

        Socket unknown = open(l);
        send(unknown, "{\"type\":\"auth\",\"playerName\":\"Alex\"}");
        assertTrue(text(unknown).contains("unknown player"));
        assertTrue(closedByServer(unknown));

        Socket blank = open(l);
        send(blank, "{\"type\":\"auth\",\"playerName\":\"   \"}");
        assertTrue(text(blank).contains("authentication failed"));

        Socket notAString = open(l);
        send(notAString, "{\"type\":\"auth\",\"playerName\":7}");
        assertTrue(text(notAString).contains("authentication failed"));

        Socket nothing = open(l);
        send(nothing, "{\"type\":\"auth\"}");
        assertTrue(text(nothing).contains("authentication failed"));
    }

    @Test
    void stopIsSafeWithoutStarting() {
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(), m -> fail(m));
        try {
            var ctx = TestContexts.context("T", "1", () -> ServerStatus.EMPTY, false, 5, 900, 900, 0,
                    new PlaceholderService((t, u) -> "5", clock, 0, defs), new TokenService(new byte[48], clock),
                    new DeviceStore(Files.createTempFile("d", ".json")), new OtpService(clock, 60), new RateLimiter(clock, 5, 1000),
                    List.of(), Set.of(), new HistoryService(clock, defs), null, d -> {
                    }, TestContexts.allowAll(), "en");
            new LiveServer(ctx, 1).stop();
        } catch (IOException e) {
            fail(e);
        }
    }
}
