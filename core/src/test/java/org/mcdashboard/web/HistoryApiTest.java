/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mcdashboard.TestContexts;
import org.mcdashboard.auth.*;
import org.mcdashboard.json.Json;
import org.mcdashboard.placeholder.*;

/** {@code GET /v1/history/{id}}: the window, the number of points and who may ask. */
class HistoryApiTest {
    static final long T0 = 1_700_000_000_000L;
    HttpApi api;
    HttpClient http = HttpClient.newHttpClient();
    String base;

    @BeforeEach
    void start() throws Exception {
        long[] now = {T0};
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.ofEpochMilli(now[0]);
            }
        };
        var defs = PlaceholderDefinition.parseAll(List.of(
                Map.of("id", "tps", "value", "%server_tps%", "type", "DECIMAL", "history", 600),
                Map.of("id", "plain", "value", "%x%", "type", "INTEGER"),
                Map.of("id", "me", "value", "%player_name%", "access", "PLAYER", "history", 60)), m -> fail(m));
        HistoryService history = new HistoryService(clock, defs);
        for (int i = 0; i < 300; i++) {
            history.record("tps", String.valueOf(i), 0);
            now[0] += 15_000;
        }
        var ctx = TestContexts.context("Test", "1.0", () -> ServerStatus.EMPTY, false, 2, 900, 3600, 0,
                new PlaceholderService((t, u) -> "7", clock, 1000, defs), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("devices", ".json")), new OtpService(clock, 60),
                new RateLimiter(clock, 100, 60000), List.of(), Set.of(), history, null, d -> {}, TestContexts.allowAll(), "en");
        api = new HttpApi(ctx);
        api.start("127.0.0.1", 0, null);
        base = "http://127.0.0.1:" + api.port();
    }

    @AfterEach
    void stop() {
        api.stop();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).setHeader("User-Agent", "MCDashboard/1.2.3 (android)").build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private List<?> records(String path) throws Exception {
        HttpResponse<String> r = get(path);
        assertEquals(200, r.statusCode());
        assertEquals("application/senml+json", r.headers().firstValue("Content-Type").orElse("").split(";")[0]);
        return (List<?>) Json.parse(r.body());
    }

    @Test
    void returnsEverythingByDefaultAndReducesWithPoints() throws Exception {
        assertEquals(300, records("/v1/history/tps").size());
        List<?> reduced = records("/v1/history/tps?points=20");
        assertTrue(reduced.size() <= 20 && reduced.size() >= 18, "got " + reduced.size());
        double previous = -1;
        for (Object o : reduced) {
            Map<?, ?> rec = (Map<?, ?>) o;
            assertEquals("tps", rec.get("n"));
            double t = ((Number) rec.get("t")).doubleValue();
            assertTrue(t > previous, "oldest first");
            previous = t;
        }
        assertEquals(299.0, ((Number) ((Map<?, ?>) reduced.get(reduced.size() - 1)).get("v")).doubleValue(), 15, "the latest bucket is the newest values");
        assertEquals(2, records("/v1/history/tps?points=2").size());
        assertEquals(2, records("/v1/history/tps?points=1").size(), "never fewer than two points");
        assertEquals(300, records("/v1/history/tps?points=999999").size(), "capped at 2000, here all 300");
    }

    @Test
    void sinceCutsTheWindow() throws Exception {
        long since = (T0 + 290 * 15_000L) / 1000;
        assertEquals(10, records("/v1/history/tps?since=" + since).size());
        assertEquals(5, records("/v1/history/tps?points=5&since=" + (T0 / 1000)).size());
    }

    @Test
    void unknownUntrackedAndPerPlayerPlaceholdersHaveNoHistory() throws Exception {
        assertEquals(404, get("/v1/history/nothing").statusCode());
        assertEquals(404, get("/v1/history/plain").statusCode());
        assertEquals(404, get("/v1/history/me").statusCode(), "per-player values are never recorded");
    }
}
