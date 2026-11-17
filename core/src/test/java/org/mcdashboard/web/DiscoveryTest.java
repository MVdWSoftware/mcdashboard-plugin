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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mcdashboard.TestContexts;
import org.mcdashboard.auth.*;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.json.Json;
import org.mcdashboard.placeholder.*;

class DiscoveryTest {
    private HttpApi start(List<String> endpoints, boolean discoveryOnly, Set<String> trusted) throws Exception {
        return start(endpoints, discoveryOnly, trusted, true);
    }

    private HttpApi start(List<String> endpoints, boolean discoveryOnly, Set<String> trusted, boolean polling) throws Exception {
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "online", "value", "%server_online%", "type", "INTEGER")), m -> fail(m));
        var ctx = new ApiContext("Test", "1.0", () -> ServerStatus.EMPTY, false, 2, 900, 3600, 1554,
                new PlaceholderService((t, u) -> "7", clock, 1000, defs), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("devices", ".json")), new OtpService(clock, 60), new RateLimiter(clock, 100, 60000),
                DashboardStore.inMemory(List.of()), trusted, new HistoryService(clock, defs), null, d -> {}, TestContexts.allowAll(), "en",
                () -> false, () -> {}, endpoints, discoveryOnly, polling);
        HttpApi api = new HttpApi(ctx);
        api.start("127.0.0.1", 0, null);
        return api;
    }

    private HttpResponse<String> get(HttpApi api, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + api.port() + path))
                .header("User-Agent", "MCDashboard/1.0.0 (dev)").build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void infoAdvertisesEndpointsInOrder() throws Exception {
        HttpApi api = start(List.of("https://mc-api.example.com", "http://10.0.0.2:1553"), false, Set.of());
        try {
            var body = Json.parseObject(get(api, "/v1/info").body());
            assertEquals(List.of("https://mc-api.example.com", "http://10.0.0.2:1553"), body.get("endpoints"));
            assertEquals(1554, ((Number) body.get("websocketPort")).intValue());
        } finally {
            api.stop();
        }
    }

    @Test
    void pollingOffRefusesValueReadsAndSaysSoInInfo() throws Exception {
        HttpApi api = start(List.of(), false, Set.of(), false);
        try {
            assertEquals(false, Json.parseObject(get(api, "/v1/info").body()).get("polling"));
            assertEquals(403, get(api, "/v1/values?ids=online").statusCode());
            assertEquals(403, get(api, "/v1/placeholders/online").statusCode());
            assertEquals(200, get(api, "/v1/placeholders").statusCode());
        } finally {
            api.stop();
        }
        HttpApi on = start(List.of(), false, Set.of());
        try {
            assertFalse(Json.parseObject(get(on, "/v1/info").body()).containsKey("polling"));
            assertEquals(200, get(on, "/v1/values?ids=online").statusCode());
        } finally {
            on.stop();
        }
    }

    @Test
    void noEndpointsFieldWhenNoneConfigured() throws Exception {
        HttpApi api = start(List.of(), false, Set.of());
        try {
            assertFalse(Json.parseObject(get(api, "/v1/info").body()).containsKey("endpoints"));
        } finally {
            api.stop();
        }
    }

    @Test
    void discoveryOnlyAnswersInfoToStrangersButTheApiOnlyToTrustedPeers() throws Exception {
        HttpApi strangers = start(List.of("https://mc-api.example.com"), true, Set.of());
        try {
            assertEquals(200, get(strangers, "/v1/info").statusCode());
            assertFalse(Json.parseObject(get(strangers, "/v1/info").body()).containsKey("websocketPort"));
            assertEquals(404, get(strangers, "/v1/values?ids=online").statusCode());
            assertEquals(404, get(strangers, "/v1/placeholders").statusCode());
        } finally {
            strangers.stop();
        }
        HttpApi trusted = start(List.of(), true, Set.of("127.0.0.1"));
        try {
            assertEquals(200, get(trusted, "/v1/values?ids=online").statusCode());
        } finally {
            trusted.stop();
        }
    }
}
