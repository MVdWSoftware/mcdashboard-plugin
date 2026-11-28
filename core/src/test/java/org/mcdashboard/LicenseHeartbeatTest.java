/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mcdashboard.platform.Log;

/** The licence heartbeat: what it derives and what it tells the administrator (never a lock). */
class LicenseHeartbeatTest {
    /** Collects log lines. */
    private static final class CapturingLog implements Log {
        final List<String> warnings = new ArrayList<>();
        final List<String> infos = new ArrayList<>();

        @Override
        public void info(String message) {
            infos.add(message);
        }

        @Override
        public void warning(String message) {
            warnings.add(message);
        }

        @Override
        public void severe(String message) {
            warnings.add(message);
        }
    }

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** Starts a local portal stand-in that records the request body and answers with the given JSON. */
    private int portal(AtomicReference<String> body, AtomicReference<String> reply, AtomicReference<Integer> status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/licenses/mcl_test/heartbeat", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server.getAddress().getPort();
    }

    @Test
    void derivesTheAddressPlayersUse() {
        assertEquals("play.example.com", LicenseHeartbeat.hostFor("Play.Example.com.", null, null));
        assertEquals("mc.example.com", LicenseHeartbeat.hostFor(null, "https://MC.Example.com:25565/v1", null));
        assertEquals("lobby.example.com", LicenseHeartbeat.hostFor(null, null, List.of("https://lobby.example.com:25566", "https://other.example.com")));
        assertEquals("bare.example.com", LicenseHeartbeat.hostFor(null, null, List.of("bare.example.com:25565/")));
        assertEquals("2001:db8::1", LicenseHeartbeat.hostFor(null, null, List.of("[2001:db8::1]")));
        assertNull(LicenseHeartbeat.hostFor(null, "not a url", null));
        assertNull(LicenseHeartbeat.hostFor("", null, List.of()));
    }

    @Test
    void reportsTheLicenceAndTheHost() throws IOException {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> reply = new AtomicReference<>("{\"active\":true,\"licensed\":true,\"registered\":true,\"hosts\":[\"mc.example.com\"]}");
        AtomicReference<Integer> status = new AtomicReference<>(200);
        String base = "http://127.0.0.1:" + portal(body, reply, status) + "/api/";
        CapturingLog log = new CapturingLog();
        LicenseHeartbeat heartbeat = new LicenseHeartbeat("mcl_test", base, "MC.Example.com.", 60, "1.2.3", log);

        assertFalse(heartbeat.licensed(), "not licensed until the portal says so");
        heartbeat.beat();
        assertTrue(heartbeat.licensed());
        assertTrue(body.get().contains("\"host\":\"mc.example.com\""));
        assertTrue(body.get().contains("\"version\":\"1.2.3\""));
        assertTrue(log.warnings.isEmpty());

        reply.set("{\"active\":false}");
        heartbeat.beat();
        heartbeat.beat();
        assertFalse(heartbeat.licensed());
        assertEquals(1, log.warnings.size(), "the same problem is reported once");
        assertTrue(log.warnings.get(0).contains("revoked"));

        reply.set("{\"active\":true,\"registered\":false}");
        heartbeat.beat();
        assertTrue(log.warnings.get(1).contains("not registered"));

        reply.set("{\"active\":true,\"licensed\":false,\"registered\":true}");
        heartbeat.beat();
        assertTrue(log.warnings.get(2).contains("no running subscription"));
        assertFalse(heartbeat.licensed());

        reply.set("{\"active\":true,\"licensed\":true,\"registered\":true}");
        heartbeat.beat();
        assertTrue(log.infos.stream().anyMatch(line -> line.contains("healthy again")));
        assertTrue(heartbeat.licensed());

        status.set(500);
        heartbeat.beat();
        assertTrue(log.warnings.get(3).contains("HTTP 500"));

        reply.set("not json");
        status.set(200);
        heartbeat.beat();
        assertTrue(log.warnings.get(4).contains("unexpected body"));

        heartbeat.close();
    }

    @Test
    void isOffWithoutALicenceAddressOrIntervalAndSurvivesAnUnreachablePortal() {
        CapturingLog log = new CapturingLog();
        new LicenseHeartbeat(null, "http://127.0.0.1:1/api", "mc.example.com", 60, "1", log).start();
        new LicenseHeartbeat("mcl_test", null, "mc.example.com", 60, "1", log).start();
        new LicenseHeartbeat("mcl_test", "http://127.0.0.1:1/api", null, 60, "1", log).start();
        LicenseHeartbeat off = new LicenseHeartbeat("mcl_test", "http://127.0.0.1:1/api", "mc.example.com", 0, "1", log);
        off.start();
        off.close();
        assertTrue(log.warnings.isEmpty());

        LicenseHeartbeat unreachable = new LicenseHeartbeat("mcl_test", "http://127.0.0.1:1/api", "mc.example.com", 60, "1", log);
        unreachable.beat();
        assertTrue(log.warnings.get(0).contains("could not reach the portal"));
        unreachable.close();
    }
}
