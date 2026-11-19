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
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mcdashboard.SnakeYamlCodec;
import org.mcdashboard.TestContexts;
import org.mcdashboard.auth.*;
import org.mcdashboard.dashboard.Dashboard;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.dashboard.LocalizedText;
import org.mcdashboard.events.EventBus;
import org.mcdashboard.events.EventStream;
import org.mcdashboard.json.Json;
import org.mcdashboard.placeholder.*;
import org.mcdashboard.stats.PluginStats;

/** Refusals and options of the HTTP API that the main API tests do not touch. */
class HttpApiGapsTest {
    static final String APP = "MCDashboard/1.2.3 (android)";

    @TempDir
    Path temp;

    HttpApi api;
    OtpService otps;
    String base;
    final HttpClient http = HttpClient.newHttpClient();
    final List<AutoCloseable> closers = new ArrayList<>();

    @AfterEach
    void stop() {
        if (api != null) api.stop();
    }

    private static List<PlaceholderDefinition> defs() {
        return PlaceholderDefinition.parseAll(List.of(Map.of("id", "tps", "value", "%server_tps%", "type", "DECIMAL")), m -> fail(m));
    }

    private ApiContext context(boolean requireAuth, boolean polling, boolean discoveryOnly, Supplier<ServerStatus> status,
                               List<Dashboard> dashboards, int ws, Set<String> trusted, int authLimit, PluginStats stats) throws Exception {
        Clock clock = Clock.systemUTC();
        otps = new OtpService(clock, 60);
        var defs = defs();
        ApiContext c = new ApiContext("T", "1", status, requireAuth, 5, 900, 3600, ws,
                new PlaceholderService((t, u) -> "7", clock, 0, defs), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("devices", ".json")), otps, new RateLimiter(clock, authLimit, 60000),
                DashboardStore.inMemory(dashboards), trusted, new HistoryService(clock, defs), null, d -> {
        }, TestContexts.allowAll(), "en", () -> false, () -> {
        }, List.of("https://a.example"), discoveryOnly, polling, stats);
        return c;
    }

    private void start(ApiContext ctx, ClientPolicy policy, String host) throws Exception {
        api = policy == null ? new HttpApi(ctx) : new HttpApi(ctx, policy);
        api.start(host, 0, null);
        base = "http://127.0.0.1:" + api.port();
    }

    private HttpResponse<String> send(String method, String path, String token, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).method(method, HttpRequest.BodyPublishers.noBody()).setHeader("User-Agent", APP);
        if (token != null) b.setHeader("Authorization", "Bearer " + token);
        for (int i = 0; i < headers.length; i += 2) b.setHeader(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String pair() throws Exception {
        String code = otps.create(UUID.randomUUID().toString(), "Steve");
        var r = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/auth")).POST(HttpRequest.BodyPublishers.ofString("{\"otp\":\"" + code + "\"}"))
                .setHeader("User-Agent", APP).build(), HttpResponse.BodyHandlers.ofString());
        return (String) Json.parseObject(r.body()).get("accessToken");
    }

    private static Dashboard board(String id) {
        return new Dashboard(id, LocalizedText.of(id), null, 1, null, null,
                List.of(new Dashboard.Item(List.of("tps"), "text", null, 0, 0, 1, 1)));
    }

    @Test
    void optionsAnswersWithoutAuthentication() throws Exception {
        start(context(true, true, false, () -> ServerStatus.EMPTY, List.of(), 0, Set.of(), 100, null), null, null);
        assertEquals(204, send("OPTIONS", "/v1/values", null).statusCode());
    }

    @Test
    void authenticationIsRequiredAndDeviceRemovalRevokesTheToken() throws Exception {
        start(context(true, true, false, () -> ServerStatus.EMPTY, List.of(board("default")), 0, Set.of(), 100, null), null, "");
        assertEquals(401, send("GET", "/v1/values?ids=tps", null).statusCode());
        assertEquals(401, send("DELETE", "/v1/devices/self", null).statusCode());
        String token = pair();
        assertEquals(200, send("GET", "/v1/dashboard", token).statusCode(), "the first dashboard");
        assertEquals(200, send("DELETE", "/v1/devices/self", token).statusCode());
        assertEquals(401, send("GET", "/v1/values?ids=tps", token).statusCode());
    }

    @Test
    void unauthenticatedDeleteWithoutRequiredAuthIsStill401AndUnknownRoutesAre404() throws Exception {
        start(context(false, true, false, () -> ServerStatus.EMPTY, List.of(), 0, Set.of(), 100, null), null, null);
        assertEquals(401, send("DELETE", "/v1/devices/self", null).statusCode());
        assertEquals(404, send("GET", "/v1/nothing", null).statusCode());
        assertEquals(404, send("GET", "/v1/dashboard", null).statusCode(), "no dashboard at all");
        assertEquals(404, send("GET", "/v1/placeholders/unknown", null).statusCode());
        assertEquals(404, send("GET", "/v1/history/unknown", null).statusCode());
        assertEquals(400, send("GET", "/v1/values", null).statusCode());
        assertEquals(400, send("GET", "/v1/values?ids=" + "a,".repeat(150) + "b", null).statusCode());
    }

    @Test
    void pollingCanBeSwitchedOff() throws Exception {
        start(context(false, false, false, () -> ServerStatus.EMPTY, List.of(), 0, Set.of(), 100, null), null, null);
        assertEquals(403, send("GET", "/v1/values?ids=tps", null).statusCode());
        assertEquals(403, send("GET", "/v1/placeholders/tps", null).statusCode());
        assertEquals(false, Json.parseObject(send("GET", "/v1/info", null).body()).get("polling"));
        assertEquals(200, send("GET", "/v1/placeholders", null).statusCode(), "the catalog stays");
    }

    @Test
    void backgroundRequestsCanBeRefusedOrLimited() throws Exception {
        ApiContext ctx = context(false, true, false, () -> ServerStatus.EMPTY, List.of(), 0, Set.of(), 100, null);
        Clock clock = Clock.systemUTC();
        ClientPolicy policy = new ClientPolicy(List.of(), null, new RateLimiter(clock, 100, 60000), new RateLimiter(clock, 100, 60000));
        policy.background(new RateLimiter(clock, 1, 60000));
        start(ctx, policy, null);
        assertEquals(200, send("GET", "/v1/placeholders/tps", null, HttpApi.PURPOSE_HEADER, "background").statusCode());
        assertEquals(429, send("GET", "/v1/placeholders/tps", null, HttpApi.PURPOSE_HEADER, "background").statusCode());
        api.background(false);
        assertEquals(403, send("GET", "/v1/placeholders/tps", null, HttpApi.PURPOSE_HEADER, "background").statusCode());
        assertFalse(((List<?>) Json.parseObject(send("GET", "/v1/info", null).body()).get("features")).contains("background"));
    }

    @Test
    void rateLimitsAreCountedAndStatisticsAreOptional() throws Exception {
        PluginStats stats = new PluginStats(Clock.systemUTC());
        ApiContext ctx = context(false, true, false, () -> ServerStatus.EMPTY, List.of(), 0, Set.of(), 100, stats);
        Clock clock = Clock.systemUTC();
        start(ctx, new ClientPolicy(List.of(), null, new RateLimiter(clock, 100, 60000), new RateLimiter(clock, 1, 60000)), null);
        api.stats(stats);
        assertEquals(200, send("GET", "/v1/placeholders/tps", null).statusCode());
        assertEquals(429, send("GET", "/v1/placeholders/tps", null).statusCode());
        assertEquals(1, stats.rateLimitedRequests());
        assertEquals(2, stats.apiRequests());
        api.stop();
        ApiContext plain = context(false, true, false, () -> ServerStatus.EMPTY, List.of(), 0, Set.of(), 100, null);
        start(plain, new ClientPolicy(List.of(), null, new RateLimiter(clock, 100, 60000), new RateLimiter(clock, 1, 60000)), null);
        send("GET", "/v1/placeholders/tps", null);
        assertEquals(429, send("GET", "/v1/placeholders/tps", null).statusCode());
        plain.rateLimited();
    }

    @Test
    void discoveryOnlyServesJustTheInfoToStrangers() throws Exception {
        start(context(false, true, true, () -> ServerStatus.EMPTY, List.of(), 1554, Set.of("10.9.9.9"), 100, null), null, null);
        assertEquals(404, send("GET", "/v1/placeholders", null).statusCode());
        var info = Json.parseObject(send("GET", "/v1/info", null).body());
        assertNull(info.get("websocketPort"));
        assertEquals(List.of("https://a.example"), info.get("endpoints"));
    }

    @Test
    void infoReportsTlsIconAndCaps() throws Exception {
        ApiContext base = context(false, true, false, () -> new ServerStatus("m", "data:image/png;base64,AA==", 1, 2, "1.21", 25565), List.of(), 1554, Set.of(), 100, null);
        ApiContext ctx = TestContexts.withLimits(base, 3, 0);
        start(ctx, null, null);
        api.tls("AB:CD");
        api.verifyClients(new ClientAssertions(List.of(), Clock.systemUTC(), Set.of(), Set.of(), true));
        var info = Json.parseObject(send("GET", "/v1/info", null).body());
        assertEquals("data:image/png;base64,AA==", info.get("icon"));
        assertEquals(true, info.get("websocketTls"));
        assertEquals("AB:CD", info.get("tlsFingerprint"));
        assertEquals(Map.of("maxDashboards", 3L), info.get("limits"));
        assertEquals(25565L, info.get("gamePort"));
        assertTrue(((List<?>) info.get("features")).contains("tls"));
        assertTrue(((List<?>) info.get("features")).contains("client-verification"));
        assertEquals(403, send("GET", "/v1/placeholders", null).statusCode(), "no assertion");
        api.stop();
        start(TestContexts.withLimits(base, 0, 4), null, null);
        api.tls(null);
        var tilesOnly = Json.parseObject(send("GET", "/v1/info", null).body());
        assertEquals(Map.of("maxTiles", 4L), tilesOnly.get("limits"));
        assertNull(tilesOnly.get("tlsFingerprint"));
    }

    @Test
    void anInternalFailureIsA500() throws Exception {
        start(context(false, true, false, () -> {
            throw new IllegalStateException("boom");
        }, List.of(), 0, Set.of(), 100, null), null, null);
        assertEquals(500, send("GET", "/v1/info", null).statusCode());
    }

    @Test
    void theEventStreamNeedsItsTokenAndHasLimits() throws Exception {
        start(context(false, true, false, () -> ServerStatus.EMPTY, List.of(), 0, Set.of(), 3, null), null, null);
        assertEquals(404, send("GET", "/v1/events", null).statusCode(), "off by default");
        EventStream stream = new EventStream(new EventBus(Clock.systemUTC()), 15);
        api.events(stream, "tokentokentoken");
        assertEquals(401, send("GET", "/v1/events", null).statusCode());
        assertEquals(401, send("GET", "/v1/events", "wrong").statusCode());
        assertEquals(401, send("GET", "/v1/events", "wrong").statusCode());
        assertEquals(429, send("GET", "/v1/events", "wrong").statusCode(), "attempts are limited");
        api.stop();
        start(context(false, true, false, () -> ServerStatus.EMPTY, List.of(), 0, Set.of(), 100, null), null, null);
        api.events(stream, "tokentokentoken");
        List<java.net.Socket> open = new ArrayList<>();
        try {
            for (int i = 0; i < EventStream.MAX_CLIENTS; i++) {
                java.net.Socket s = new java.net.Socket("127.0.0.1", api.port());
                open.add(s);
                s.getOutputStream().write(("GET /v1/events HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer tokentokentoken\r\nUser-Agent: " + APP + "\r\n\r\n").getBytes());
                s.getOutputStream().flush();
                s.getInputStream().read();
            }
            assertEquals(503, send("GET", "/v1/events", "tokentokentoken").statusCode());
        } finally {
            for (var s : open) s.close();
        }
    }

    @Test
    void aFailingDashboardSaveIsA500() throws Exception {
        Clock clock = Clock.systemUTC();
        Path dashboards = temp.resolve("dashboards");
        Files.createDirectories(dashboards.resolve("dev-x.yml.tmp")); // cannot be written as a file
        otps = new OtpService(clock, 60);
        var defs = defs();
        PermissionCache perms = new PermissionCache();
        String uuid = UUID.randomUUID().toString();
        perms.update(UUID.fromString(uuid), Set.of("mcdashboard.developer"));
        DashboardStore store = new DashboardStore(dashboards, new SnakeYamlCodec(), m -> {
        });
        ApiContext ctx = TestContexts.context("T", "1", () -> ServerStatus.EMPTY, false, 5, 900, 3600, 0,
                new PlaceholderService((t, u) -> "7", clock, 0, defs), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("devices", ".json")), otps, new RateLimiter(clock, 100, 60000),
                store, Set.of(), new HistoryService(clock, defs), null, d -> {
                }, perms, "en", () -> true, () -> {
                });
        start(ctx, null, null);
        String code = otps.create(uuid, "Dev");
        var paired = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/auth")).POST(HttpRequest.BodyPublishers.ofString("{\"otp\":\"" + code + "\"}"))
                .setHeader("User-Agent", APP).build(), HttpResponse.BodyHandlers.ofString());
        String token = (String) Json.parseObject(paired.body()).get("accessToken");
        String body = "{\"name\":\"x\",\"items\":[{\"placeholder\":\"tps\",\"widget\":\"text\"}]}";
        String id = "dev-" + uuid.replace("-", "").substring(0, 12);
        Files.createDirectories(dashboards.resolve(id + ".yml.tmp"));
        var r = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/dashboards")).POST(HttpRequest.BodyPublishers.ofString(body))
                .setHeader("User-Agent", APP).setHeader("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(500, r.statusCode(), r.body());
    }
}
