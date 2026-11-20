/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mcdashboard.TestContexts;
import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.auth.OtpService;
import org.mcdashboard.auth.PermissionCache;
import org.mcdashboard.auth.RateLimiter;
import org.mcdashboard.auth.TokenService;
import org.mcdashboard.json.Json;
import org.mcdashboard.placeholder.HistoryService;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.placeholder.PlaceholderService;

/** The opt-in where a client types a player name instead of pairing (`security.allow-player-names`). */
class PlayerNamesApiTest {
    private static final String APP = "MCDashboard/1.2.3 (android)";
    private static final String STEVE = UUID.randomUUID().toString();
    private final HttpClient http = HttpClient.newHttpClient();
    private HttpApi api;
    private OtpService otps;
    private String base;

    @BeforeEach
    void start() throws Exception {
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(
                Map.of("id", "me", "value", "%me%", "access", "PLAYER")), m -> fail(m));
        otps = new OtpService(clock, 60);
        ApiContext unpaired = TestContexts.context("T", "1", () -> ServerStatus.EMPTY, true, 5, 900, 3600, 0,
                new PlaceholderService((t, u) -> "5", clock, 0, defs), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("devices", ".json")), otps,
                new RateLimiter(clock, 100, 60000), List.of(), Set.of(), new HistoryService(clock, defs), null,
                d -> { }, new PermissionCache(), "en");
        ApiContext ctx = TestContexts.withPlayerNames(unpaired, name -> name.equals("Steve") ? STEVE : null);
        api = new HttpApi(ctx);
        api.start("127.0.0.1", 0, null);
        base = "http://127.0.0.1:" + api.port();
    }

    @AfterEach
    void stop() {
        api.stop();
    }

    private HttpResponse<String> get(String path, String player, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).setHeader("User-Agent", APP);
        if (player != null) b.setHeader(HttpApi.PLAYER_HEADER, player);
        if (token != null) b.setHeader("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String pair() throws Exception {
        String otp = otps.create(UUID.randomUUID().toString(), "S");
        var r = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/auth"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"otp\":\"" + otp + "\"}"))
                .setHeader("User-Agent", APP).build(), HttpResponse.BodyHandlers.ofString());
        return (String) Json.parseObject(r.body()).get("accessToken");
    }

    @Test
    void theServerAnnouncesTheOptInInInfo() throws Exception {
        Map<String, Object> info = Json.parseObject(get("/v1/info", null, null).body());
        assertEquals(true, info.get("allowPlayerNames"));
        assertTrue(((List<?>) info.get("features")).contains("player-names"));
    }

    @Test
    void aKnownNameReadsPlayerPlaceholdersEvenWhenAuthenticationIsRequired() throws Exception {
        assertEquals(401, get("/v1/placeholders/me", null, null).statusCode());
        var answer = get("/v1/placeholders/me", "Steve", null);
        assertEquals(200, answer.statusCode());
        assertTrue(answer.headers().firstValue("Vary").orElse("").contains(HttpApi.PLAYER_HEADER));
    }

    @Test
    void unknownAndImpossibleNamesAreRefused() throws Exception {
        assertEquals(403, get("/v1/placeholders/me", "Alex", null).statusCode());
        assertEquals(400, get("/v1/placeholders/me", "x".repeat(33), null).statusCode());
    }

    @Test
    void aValidTokenWinsOverATypedName() throws Exception {
        assertEquals(200, get("/v1/placeholders/me", "Alex", pair()).statusCode());
    }
}
