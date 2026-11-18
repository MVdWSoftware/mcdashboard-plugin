/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class FederationTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static Map<String, Object> entry(String name, String url, String key, String pin) {
        return Map.of("name", name, "url", url, "key", key, "pin", pin);
    }

    @Test
    void onlyHttpUrlsWithoutCredentialsAreAcceptedAndBadEntriesAreReported() {
        List<String> warnings = new ArrayList<>();
        List<BackendConfig> ok = BackendConfig.parseAll(List.of(
                entry("lobby", "https://lobby.example:1553/", "k", ""),
                entry("a_b", "https://x", "", ""),
                entry("file", "file:///etc/passwd", "", ""),
                entry("cred", "https://user:pw@host", "", ""),
                entry("query", "https://host/?x=1", "", ""),
                entry("pin", "https://host", "", "nothex"),
                entry("LOBBY", "https://other", "", "")), warnings::add);
        assertEquals(1, ok.size());
        assertEquals("https://lobby.example:1553", ok.get(0).url().toString());
        assertEquals(6, warnings.size(), warnings.toString());
    }

    @Test
    void valuesAreServedByNameAndDroppedWhenStale() {
        AtomicLong now = new AtomicLong(1_000);
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(now.get());
            }
        };
        List<String> warnings = new ArrayList<>();
        AtomicBoolean fail = new AtomicBoolean();
        Federation fed = new Federation(List.of(BackendConfig.fromMap(Map.of("name", "lobby", "url", "http://h"))), b -> {
            if (fail.get()) throw new IOException("down");
            return Map.of("tps", "20", "online", "5");
        }, clock, warnings::add);
        assertNull(fed.value("lobby_tps"), "nothing read yet");
        fed.refresh();
        assertEquals("20", fed.value("lobby_tps"));
        assertEquals("5", fed.value("LOBBY_online"));
        assertNull(fed.value("lobby_missing"));
        now.addAndGet(61_000);
        assertNull(fed.value("lobby_tps"), "an old value is not shown as current");
        fail.set(true);
        fed.refresh();
        fed.refresh();
        assertEquals(1, warnings.size(), "a backend that stays down is reported once");
        fail.set(false);
        fed.refresh();
        assertEquals("20", fed.value("lobby_tps"));
        assertTrue(warnings.get(1).contains("reachable again"));
    }

    private HttpServer backend(String valuesBody, AtomicBoolean sawRedirectTarget, List<String> agents, List<String> auth) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/placeholders", ex -> {
            agents.add(ex.getRequestHeaders().getFirst("User-Agent"));
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            byte[] body = ("{\"placeholders\":[{\"id\":\"tps\",\"access\":\"PUBLIC\"},{\"id\":\"online\",\"access\":\"PUBLIC\"},"
                    + "{\"id\":\"health\",\"access\":\"PLAYER\"},{\"id\":\"bad id\",\"access\":\"PUBLIC\"}],\"total\":4,\"groups\":[]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/v1/values", ex -> {
            byte[] body = valuesBody.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/target");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/target", ex -> {
            sawRedirectTarget.set(true);
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
        return server;
    }

    @Test
    void theHttpFetcherReadsPublicValuesAsTextWithAFixedUserAgentAndTheKey() throws Exception {
        List<String> agents = new ArrayList<>();
        List<String> auth = new ArrayList<>();
        backend("[{\"n\":\"tps\",\"v\":19.5},{\"n\":\"online\",\"v\":12},{\"n\":\"x\",\"vb\":true},{\"n\":\"y\",\"vs\":\"hi\"},{\"n\":\"health\",\"v\":20}]",
                new AtomicBoolean(), agents, auth);
        BackendConfig b = BackendConfig.fromMap(Map.of("name", "lobby", "url", "http://127.0.0.1:" + server.getAddress().getPort(), "key", "tok"));
        Map<String, String> values = new HttpBackendFetcher("1.2.3").fetch(b);
        assertEquals("19.5", values.get("tps"));
        assertEquals("12", values.get("online"));
        assertNull(values.get("health"), "a PLAYER placeholder is never kept, even if the backend sends it");
        assertEquals("MCDashboard-Proxy/1.2.3 (federation)", agents.get(0));
        assertEquals("Bearer tok", auth.get(0));
    }

    @Test
    void aPlayerPlaceholderIsNeverRequested() throws Exception {
        List<String> asked = new ArrayList<>();
        backend("[]", new AtomicBoolean(), new ArrayList<>(), new ArrayList<>());
        server.removeContext("/v1/values");
        server.createContext("/v1/values", ex -> {
            asked.add(ex.getRequestURI().getRawQuery());
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        BackendConfig b = BackendConfig.fromMap(Map.of("name", "lobby", "url", "http://127.0.0.1:" + server.getAddress().getPort()));
        new HttpBackendFetcher("1").fetch(b);
        assertEquals(List.of("ids=tps%2Conline"), asked);
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        AtomicBoolean followed = new AtomicBoolean();
        backend("[]", followed, new ArrayList<>(), new ArrayList<>());
        server.removeContext("/v1/placeholders");
        server.createContext("/v1/placeholders", ex -> {
            ex.getResponseHeaders().set("Location", "http://127.0.0.1:" + server.getAddress().getPort() + "/target");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        BackendConfig b = BackendConfig.fromMap(Map.of("name", "lobby", "url", "http://127.0.0.1:" + server.getAddress().getPort()));
        try {
            new HttpBackendFetcher("1").fetch(b);
            throw new AssertionError("a redirect is an error");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("302"), expected.getMessage());
        }
        assertEquals(false, followed.get());
    }

    @Test
    void numbersKeepTheirShape() {
        assertEquals("20", HttpBackendFetcher.text(Map.of("v", 20.0)));
        assertEquals("19.75", HttpBackendFetcher.text(Map.of("v", 19.75)));
        assertEquals("7", HttpBackendFetcher.text(Map.of("v", 7L)));
        assertEquals("true", HttpBackendFetcher.text(Map.of("vb", true)));
        assertEquals("hi", HttpBackendFetcher.text(Map.of("vs", "hi")));
        assertNull(HttpBackendFetcher.text(Map.of("vd", "x")));
    }
}
