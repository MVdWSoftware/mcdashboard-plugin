/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.mcdashboard.placeholder.PlaceholderService;
import org.mcdashboard.platform.Log;
import org.mcdashboard.push.PushClient;
import org.mcdashboard.push.PushCrypto;
import org.mcdashboard.push.PushService;
import org.mcdashboard.push.PushStore;

/**
 * {@code /v1/devices/self/push} and the {@code push} feature. A plugin without the push service (or a server that is not
 * licensed) must behave exactly as plugin 1.0 did: no feature, and a 404 on the route.
 */
class PushApiTest {
    private static final String APP = "MCDashboard/1.2.3 (android)";
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicBoolean licensed = new AtomicBoolean(true);
    private HttpApi api;
    private OtpService otps;
    private String base;
    private String token;
    private PushService push;

    @BeforeEach
    void start() throws Exception {
        Clock clock = Clock.systemUTC();
        otps = new OtpService(clock, 60);
        ApiContext ctx = TestContexts.context("T", "1", () -> ServerStatus.EMPTY, true, 5, 900, 3600, 0,
                new PlaceholderService((t, u) -> "5", clock, 0, List.of()), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("devices", ".json")), otps,
                new RateLimiter(clock, 100, 60000), List.of(), Set.of(), new HistoryService(clock, List.of()), null,
                d -> { }, new PermissionCache(), "en");
        push = new PushService(new PushStore(Files.createTempFile("push", ".json")), new PushClient("mcl_test", "http://127.0.0.1:1/api"),
                licensed::get, true, "T", null, id -> Optional.of("steve"), (p, u) -> OptionalDouble.empty(), clock,
                Log.of(java.util.logging.Logger.getAnonymousLogger()));
        api = new HttpApi(ctx).push(push);
        api.start("127.0.0.1", 0, null);
        base = "http://127.0.0.1:" + api.port();
        String otp = otps.create(UUID.randomUUID().toString(), "S");
        token = (String) Json.parseObject(send("POST", "/v1/auth", "{\"otp\":\"" + otp + "\"}", null).body()).get("accessToken");
    }

    @AfterEach
    void stop() {
        api.stop();
        push.close();
    }

    private HttpResponse<String> send(String method, String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).setHeader("User-Agent", APP)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) b.setHeader("Authorization", "Bearer " + bearer);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String binding(String route) {
        return Json.write(Map.of("routeToken", route, "publicKey", PushCrypto.encodePublicKey(PushCrypto.generateKeyPair().getPublic()),
                "rules", List.of(Map.of("id", "tps", "placeholder", "server_tps", "op", "lt", "threshold", 15, "title", "t", "body", "b"))));
    }

    @SuppressWarnings("unchecked")
    private List<String> features() throws Exception {
        return (List<String>) Json.parseObject(send("GET", "/v1/info", null, null).body()).get("features");
    }

    @Test
    void aLicensedServerAdvertisesPushAndAnUnlicensedOneDoesNot() throws Exception {
        assertTrue(features().contains("push"));
        licensed.set(false);
        api.stop();
        api = new HttpApi(TestContexts.context("T", "1", () -> ServerStatus.EMPTY, true, 5, 900, 3600, 0,
                new PlaceholderService((t, u) -> "5", Clock.systemUTC(), 0, List.of()), new TokenService(new byte[48], Clock.systemUTC()),
                new DeviceStore(Files.createTempFile("devices", ".json")), otps, new RateLimiter(Clock.systemUTC(), 100, 60000),
                List.of(), Set.of(), new HistoryService(Clock.systemUTC(), List.of()), null, d -> { }, new PermissionCache(), "en")).push(push);
        api.start("127.0.0.1", 0, null);
        base = "http://127.0.0.1:" + api.port();
        assertFalse(features().contains("push"));
    }

    @Test
    void withoutThePushServiceTheRouteDoesNotExistAsInPlugin10() throws Exception {
        api.push(null);
        assertFalse(features().contains("push"));
        assertEquals(404, send("GET", "/v1/devices/self/push", null, token).statusCode());
    }

    @Test
    void aDeviceEnablesReadsAndDisablesItsOwnPush() throws Exception {
        assertEquals(401, send("GET", "/v1/devices/self/push", null, null).statusCode());
        assertEquals(false, Json.parseObject(send("GET", "/v1/devices/self/push", null, token).body()).get("enabled"));
        HttpResponse<String> put = send("PUT", "/v1/devices/self/push", binding("route-token-123456"), token);
        assertEquals(200, put.statusCode());
        Map<String, Object> got = Json.parseObject(send("GET", "/v1/devices/self/push", null, token).body());
        assertEquals(true, got.get("enabled"));
        assertEquals(1, ((List<?>) got.get("rules")).size());
        assertFalse(send("GET", "/v1/devices/self/push", null, token).body().contains("route-token"), "the route is never echoed back");
        assertEquals(true, Json.parseObject(send("DELETE", "/v1/devices/self/push", null, token).body()).get("removed"));
        assertEquals(false, Json.parseObject(send("GET", "/v1/devices/self/push", null, token).body()).get("enabled"));
        assertEquals(405, send("POST", "/v1/devices/self/push", "{}", token).statusCode());
    }

    @Test
    void invalidBindingsAreRefusedAndTheRouteIs404WhileUnlicensed() throws Exception {
        assertEquals(400, send("PUT", "/v1/devices/self/push", "{\"routeToken\":\"x\"}", token).statusCode());
        licensed.set(false);
        assertEquals(404, send("PUT", "/v1/devices/self/push", binding("route-token-123456"), token).statusCode());
    }
}
