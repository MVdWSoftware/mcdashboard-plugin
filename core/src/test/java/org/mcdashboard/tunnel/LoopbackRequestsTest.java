/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.tunnel;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LoopbackRequestsTest {
    private HttpServer server;
    private LoopbackRequests requests;
    private String lastHeader;
    private String lastBody;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/", x -> {
            lastHeader = x.getRequestHeaders().getFirst("Authorization") + "|" + x.getRequestHeaders().getFirst("X-Other");
            lastBody = new String(x.getRequestBody().readAllBytes());
            byte[] out = x.getRequestURI().getPath().equals("/v1/big") ? new byte[3 * 1024 * 1024] : "ok".getBytes();
            x.getResponseHeaders().add("ETag", "\"a\"");
            x.sendResponseHeaders(x.getRequestURI().getPath().equals("/v1/created") ? 201 : 200, out.length == 0 ? -1 : out.length);
            x.getResponseBody().write(out);
            x.close();
        });
        server.start();
        requests = new LoopbackRequests(HttpClient.newHttpClient(), "127.0.0.1", server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static Map<String, Object> req(String m, String p) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("m", m);
        r.put("p", p);
        return r;
    }

    private static String bodyOf(Map<String, Object> res) {
        return new String(Base64.getDecoder().decode((String) res.get("b")));
    }

    @Test
    void forwardsAllowedHeadersAndBodyAndMapsTheResponse() {
        Map<String, Object> r = req("POST", "/v1/created");
        r.put("h", Map.of("Authorization", "Bearer a\r\nb", "X-Other", "no", "accept", 5));
        r.put("b", Base64.getEncoder().encodeToString("payload".getBytes()));
        Map<String, Object> res = requests.answer(r, 7);
        assertEquals(7L, res.get("id"));
        assertEquals(201, res.get("s"));
        assertEquals("ok", bodyOf(res));
        assertEquals("Bearer ab|null", lastHeader);
        assertEquals("payload", lastBody);
        assertEquals("\"a\"", ((Map<?, ?>) res.get("h")).get("etag"));
        assertEquals(200, requests.answer(req("GET", "/v1/info"), 1).get("s"));
    }

    @Test
    void refusesWhatTheProxyMayNotAsk() {
        assertEquals(404, requests.answer(req("PUT", "/v1/info"), 1).get("s"));
        assertEquals(404, requests.answer(req("GET", "/v1/events"), 1).get("s"));
        assertEquals(404, requests.answer(req("GET", "nope"), 1).get("s"));
    }

    @Test
    void anOversizedAnswerBecomesA502() {
        assertEquals(502, requests.answer(req("GET", "/v1/big"), 1).get("s"));
    }

    @Test
    void aDeadPluginIsA502AndAnInterruptIsNull() {
        server.stop(0);
        assertEquals(502, requests.answer(req("GET", "/v1/info"), 1).get("s"));
        Thread.currentThread().interrupt();
        try {
            assertNull(requests.answer(req("GET", "/v1/info"), 1));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void wrapsIpv6Hosts() {
        LoopbackRequests v6 = new LoopbackRequests(HttpClient.newHttpClient(), "::1", 1);
        assertEquals(502, v6.answer(req("GET", "/v1/info"), 1).get("s"));
        assertEquals(502, new LoopbackRequests(HttpClient.newHttpClient(), "[::1]", 1).answer(req("GET", "/v1/info"), 1).get("s"));
    }

    @Test
    void onlyV1PathsOtherThanTheEventStreamAreAllowed() {
        assertTrue(LoopbackRequests.allowed("/v1/info"));
        assertTrue(LoopbackRequests.allowed("/v1/values?ids=a,b"));
        assertFalse(LoopbackRequests.allowed("v1/info"));
        assertFalse(LoopbackRequests.allowed("/v1/a b"));
        assertFalse(LoopbackRequests.allowed("/v2/info"));
        assertFalse(LoopbackRequests.allowed("/v1/../admin"));
        assertFalse(LoopbackRequests.allowed("/v1/events"));
        assertFalse(LoopbackRequests.allowed("/v1/%65vents"));
        assertFalse(LoopbackRequests.allowed("/v1/events/x"));
        assertFalse(LoopbackRequests.allowed("//v1"));
        assertFalse(LoopbackRequests.allowed("/v1/x/..%2f..%2fetc"));
    }
}
