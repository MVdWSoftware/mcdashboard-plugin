/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The portal client: statuses become outcomes, and it never throws. */
class PushClientTest {
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> reply = new AtomicReference<>("{}");

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String portal() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] out = reply.get().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status.get(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static final PushClient.Message MESSAGE = new PushClient.Message(List.of("r"), "alert", Map.of("v", 1), null, 60);

    @Test
    void anUnconfiguredClientRefusesWithoutCalling() {
        assertFalse(new PushClient(null, "http://x").configured());
        assertFalse(new PushClient("id", " ").configured());
        assertTrue(new PushClient(" id ", "http://x//").configured());
        PushClient.Outcome out = new PushClient("", null).send(List.of(MESSAGE));
        assertTrue(out.unavailable());
        assertFalse(new PushClient(null, null).revokeRoute("r"));
    }

    @Test
    void statusCodesBecomeOutcomes() throws Exception {
        PushClient client = new PushClient("mcl", portal());
        for (int code : new int[]{402, 403, 404, 501}) {
            status.set(code);
            assertTrue(client.send(List.of(MESSAGE)).unavailable(), "HTTP " + code);
        }
        status.set(429);
        PushClient.Outcome limited = client.send(List.of(MESSAGE));
        assertFalse(limited.unavailable());
        assertNotNull(limited.failure());
        status.set(500);
        assertNotNull(client.send(List.of(MESSAGE)).failure());
    }

    @Test
    void resultsAreParsedAndGarbageIsIgnored() throws Exception {
        PushClient client = new PushClient("mcl", portal());
        reply.set("{\"results\":[{\"route\":\"a\",\"status\":\"throttled\",\"retryAfter\":30},{\"route\":\"b\",\"status\":\"ok\"},{\"route\":1},5]}");
        PushClient.Outcome out = client.send(List.of(new PushClient.Message(List.of("a", "b"), "alert", Map.of(), "key", 60)));
        assertEquals(new PushClient.RouteResult("throttled", 30), out.results().get("a"));
        assertEquals(new PushClient.RouteResult("ok", 0), out.results().get("b"));
        assertEquals(2, out.results().size());
        reply.set("not json");
        assertTrue(client.send(List.of(MESSAGE)).results().isEmpty());
        reply.set("[]");
        assertTrue(client.send(List.of(MESSAGE)).results().isEmpty());
    }

    @Test
    void anUnreachablePortalIsAFailureNotAnException() {
        PushClient client = new PushClient("mcl", "http://127.0.0.1:1");
        assertNotNull(client.send(List.of(MESSAGE)).failure());
        assertFalse(client.revokeRoute("r"));
    }

    @Test
    void revokingARouteAcceptsDoneAndAlreadyGone() throws Exception {
        PushClient client = new PushClient("mcl", portal());
        reply.set("");
        status.set(204);
        assertTrue(client.revokeRoute("r"));
        status.set(404);
        assertTrue(client.revokeRoute("r"));
        status.set(500);
        assertFalse(client.revokeRoute("r"));
    }

    @Test
    void anInterruptedCallStopsQuietlyAndKeepsTheFlag() throws Exception {
        PushClient client = new PushClient("mcl", portal());
        Thread.currentThread().interrupt();
        PushClient.Outcome out = client.send(List.of(MESSAGE));
        assertTrue(Thread.interrupted());
        assertNotNull(out.failure());
        Thread.currentThread().interrupt();
        assertFalse(client.revokeRoute("r"));
        assertTrue(Thread.interrupted());
    }
}
