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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mcdashboard.TestContexts;
import org.mcdashboard.SnakeYamlCodec;
import org.mcdashboard.auth.*;
import org.mcdashboard.dashboard.Dashboard;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.image.ImageCache;
import org.mcdashboard.json.Json;
import org.mcdashboard.placeholder.*;
import org.mcdashboard.web.ServerStatus;

class ApiTest {
    @TempDir
    Path temp;
    HttpApi api;
    OtpService otps;
    HttpClient http = HttpClient.newHttpClient();
    String base;
    ApiContext ctx;
    /** What the official app sends; every helper adds it unless a test passes its own. */
    static final String APP = "MCDashboard/1.2.3 (android)";

    @BeforeEach
    void start() throws Exception {
        Clock clock = Clock.systemUTC();
        ValueSource src = (tpl, uuid) -> tpl.equals("%server_tps%") ? "19.9" : tpl.equals("%player_name%") ? "Steve:" + uuid : "7";
        var defs = PlaceholderDefinition.parseAll(List.of(
                Map.of("id", "tps", "value", "%server_tps%", "type", "DECIMAL", "min-interval", 5, "unit", "tps"),
                Map.of("id", "online", "value", "%server_online%", "type", "INTEGER"),
                Map.of("id", "me", "value", "%player_name%", "access", "PLAYER")), m -> fail(m));
        otps = new OtpService(clock, 60);
        ctx = TestContexts.context("Test", "1.0", () -> ServerStatus.EMPTY, false, 2, 900, 3600, 0,
                new PlaceholderService(src, clock, 1000, defs), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("devices", ".json")), otps, new RateLimiter(clock, 100, 60000), List.of(), Set.of(), new HistoryService(clock, defs), null, d -> {}, TestContexts.allowAll(), "en");
        api = new HttpApi(ctx);
        api.start("127.0.0.1", 0, null);
        base = "http://127.0.0.1:" + api.port();
    }

    @AfterEach
    void stop() {
        api.stop();
    }

    HttpResponse<String> get(String path, String... headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).setHeader("User-Agent", APP);
        for (int i = 0; i < headers.length; i += 2) b.setHeader(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.ofString(body))
                .setHeader("User-Agent", APP).build(), HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).POST(HttpRequest.BodyPublishers.ofString(body))
                .setHeader("User-Agent", APP);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void publicValueIsSenMlAndCacheable() throws Exception {
        var r = get("/v1/placeholders/tps");
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/senml+json"));
        assertEquals("public, max-age=5", r.headers().firstValue("Cache-Control").orElseThrow());
        var rec = (Map<?, ?>) ((List<?>) Json.parse(r.body())).get(0);
        assertEquals("tps", rec.get("n"));
        assertEquals(19.9, rec.get("v"));
        assertEquals("tps", rec.get("u"));
        String etag = r.headers().firstValue("ETag").orElseThrow();
        assertEquals(304, get("/v1/placeholders/tps", "If-None-Match", etag).statusCode());
    }

    @Test
    void permissionOverridesChangeTheAdvertisedIntervalAndCacheLifetime() throws Exception {
        api.stop();
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "tps", "value", "%server_tps%")), m -> fail(m));
        PermissionCache perms = new PermissionCache();
        var service = new PlaceholderService((t, u) -> "19.9", clock, 900_000,
                IntervalOverrides.parse(Map.of("x.fast", 15), m -> fail(m)), perms, defs,
                temp.resolve("images"), new ImageCache(clock));
        ctx = TestContexts.context("Test", "1.0", () -> ServerStatus.EMPTY, false, 2, 900, 3600, 0,
                service, new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("devices", ".json")), otps, new RateLimiter(clock, 100, 60000), List.of(), Set.of(),
                new HistoryService(clock, defs), null, d -> {}, perms, "en");
        api = new HttpApi(ctx);
        api.start("127.0.0.1", 0, null);
        base = "http://127.0.0.1:" + api.port();

        var anonymous = get("/v1/placeholders/tps");
        assertEquals("public, max-age=900", anonymous.headers().firstValue("Cache-Control").orElseThrow());
        var anonymousItem = (Map<?, ?>) ((List<?>) ((Map<?, ?>) Json.parse(get("/v1/placeholders").body())).get("placeholders")).get(0);
        assertEquals(900L, ((Number) anonymousItem.get("minInterval")).longValue());

        String uuid = "00000000-0000-0000-0000-00000000000a";
        String admin = pair(uuid, "Admin");
        perms.update(UUID.fromString(uuid), Set.of("x.fast"));

        var adminValue = get("/v1/placeholders/tps", "Authorization", "Bearer " + admin);
        assertEquals("private, max-age=15", adminValue.headers().firstValue("Cache-Control").orElseThrow());
        var adminItems = (Map<?, ?>) Json.parse(get("/v1/placeholders", "Authorization", "Bearer " + admin).body());
        var adminItem = (Map<?, ?>) ((List<?>) adminItems.get("placeholders")).get(0);
        assertEquals(15L, ((Number) adminItem.get("minInterval")).longValue());
    }

    @Test
    void bulkValuesAndUnknownIds() throws Exception {
        var r = get("/v1/values?ids=tps,online,nope,me");
        var list = (List<?>) Json.parse(r.body());
        assertEquals(2, list.size(), "unknown and player-only ids are skipped for anonymous callers");
        assertEquals(400, get("/v1/values").statusCode());
    }

    @Test
    void playerPlaceholderNeedsPairingAndIsPrivate() throws Exception {
        assertEquals(401, get("/v1/placeholders/me").statusCode());
        String code = otps.create("uuid-1", "Steve");
        var auth = Json.parseObject(post("/v1/auth", "{\"otp\":\"" + code + "\",\"deviceLabel\":\"Pixel\"}").body());
        String access = (String) auth.get("accessToken");
        var r = get("/v1/placeholders/me", "Authorization", "Bearer " + access);
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Cache-Control").orElseThrow().startsWith("private"));
        assertTrue(r.body().contains("Steve:uuid-1"));
        assertEquals(401, post("/v1/auth", "{\"otp\":\"" + code + "\"}").statusCode(), "code is single use");
    }

    @Test
    void anInvalidTokenIsRefusedInsteadOfAnsweredAsAnonymous() throws Exception {
        // Public data for a stale token would leave the app on the public view without ever refreshing.
        var r = get("/v1/dashboards", "Authorization", "Bearer not-a-valid-token");
        assertEquals(401, r.statusCode());
        assertEquals("Bearer error=\"invalid_token\"", r.headers().firstValue("WWW-Authenticate").orElseThrow());
        assertEquals(200, get("/v1/dashboards").statusCode(), "without a token the public view stays open");
    }

    @Test
    void refreshRotatesAndOldRefreshTokenDies() throws Exception {
        var auth = Json.parseObject(post("/v1/auth", "{\"otp\":\"" + otps.create("u", "S") + "\"}").body());
        String r1 = (String) auth.get("refreshToken");
        var second = post("/v1/auth/refresh", "{\"refreshToken\":\"" + r1 + "\"}");
        assertEquals(200, second.statusCode());
        // A lost answer: the old token still works once for a short grace period...
        var retry = post("/v1/auth/refresh", "{\"refreshToken\":\"" + r1 + "\"}");
        assertEquals(200, retry.statusCode());
        // ...but not after the next rotation, which the client only does once it holds the newer token.
        String r2 = (String) Json.parseObject(second.body()).get("refreshToken");
        assertEquals(200, post("/v1/auth/refresh", "{\"refreshToken\":\"" + r2 + "\"}").statusCode());
        assertEquals(401, post("/v1/auth/refresh", "{\"refreshToken\":\"" + r1 + "\"}").statusCode());
    }

    @Test
    void deviceLimitAndMalformedBodies() throws Exception {
        for (int i = 0; i < 2; i++) assertEquals(200, post("/v1/auth", "{\"otp\":\"" + otps.create("u", "S") + "\"}").statusCode());
        assertEquals(403, post("/v1/auth", "{\"otp\":\"" + otps.create("u", "S") + "\"}").statusCode());
        assertEquals(400, post("/v1/auth", "not json").statusCode());
    }

    @Test
    void deviceLabelCannotCarryChatFormatting() throws Exception {
        String body = "{\"otp\":\"" + otps.create("u", "S") + "\",\"deviceLabel\":\"§4Admin\\n§aAll good " + "x".repeat(60) + "\"}";
        assertEquals(200, post("/v1/auth", body).statusCode());
        String label = ctx.devices().devicesOf("u").get(0).label();
        assertFalse(label.contains("§") || label.contains("\n"), label);
        assertEquals(40, label.length());
    }

    @Test
    void tooManyAuthAttemptsGetRateLimited() throws Exception {
        api.stop();
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "tps", "value", "%server_tps%")), m -> fail(m));
        var ctx = TestContexts.context("Test", "1.0", () -> ServerStatus.EMPTY, false, 2, 900, 3600, 0,
                new PlaceholderService((t, u) -> "1", clock, 0, defs), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("devices", ".json")), otps, new RateLimiter(clock, 3, 60000), List.of(), Set.of(),
                new HistoryService(clock, defs), null, d -> {}, TestContexts.allowAll(), "en");
        var stats = new org.mcdashboard.stats.PluginStats(clock);
        api = new HttpApi(TestContexts.withStats(ctx, stats));
        api.start("127.0.0.1", 0, null);
        base = "http://127.0.0.1:" + api.port();
        for (int i = 0; i < 3; i++) assertEquals(401, post("/v1/auth", "{\"otp\":\"AAAAAAAA\"}").statusCode());
        assertEquals(0, stats.rateLimitedRequests());
        assertEquals(429, post("/v1/auth", "{\"otp\":\"AAAAAAAA\"}").statusCode());
        assertEquals(429, post("/v1/auth/refresh", "{\"refreshToken\":\"x\"}").statusCode(), "refresh shares the limit");
        assertEquals(2, stats.rateLimitedRequests(), "refused pairing and refresh attempts count as rate limited");
    }

    @Test
    void aDeviceReportsWhichLicenceItUsesAndOnlyKnownValuesCount() throws Exception {
        String access = pair("Pixel");
        String id = ctx.devices().devicesOf("uuid-Pixel").get(0).id();
        assertNull(ctx.devices().get(id).orElseThrow().entitlement());
        assertEquals(200, get("/v1/placeholders/tps", "Authorization", "Bearer " + access, HttpApi.ENTITLEMENT_HEADER, "app").statusCode());
        assertEquals("app", ctx.devices().get(id).orElseThrow().entitlement());
        get("/v1/placeholders/tps", "Authorization", "Bearer " + access, HttpApi.ENTITLEMENT_HEADER, "platinum");
        assertEquals("app", ctx.devices().get(id).orElseThrow().entitlement(), "anything else is ignored");
        get("/v1/placeholders/tps", "Authorization", "Bearer " + access, HttpApi.ENTITLEMENT_HEADER, "server");
        assertEquals("server", ctx.devices().get(id).orElseThrow().entitlement());
        get("/v1/placeholders/tps", HttpApi.ENTITLEMENT_HEADER, "free"); // not authenticated: nobody to attribute it to
        assertEquals(1, ctx.devices().countEntitled("server"));
        assertEquals(0, ctx.devices().countEntitled("free"));
    }

    /** A server with a permissioned overview, a staff-only dashboard, a player dashboard and a staff-only placeholder. */
    private void presetServer(PermissionCache perms) throws Exception {
        api.stop();
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(
                Map.of("id", "tps", "value", "%server_tps%", "type", "DECIMAL", "history", 10),
                Map.of("id", "online", "value", "%server_online%", "type", "INTEGER", "max-from", "max"),
                Map.of("id", "max", "value", "%server_max_players%", "type", "INTEGER"),
                Map.of("id", "me", "value", "%player_name%", "access", "PLAYER"),
                Map.of("id", "secret", "value", "%server_version%", "permission", "x.staff")), m -> fail(m));
        var dashboards = List.of(
                Dashboard.fromMap("staff", Map.of("name", "Staff", "order", 30,
                        "items", List.of(Map.of("placeholder", "tps", "widget", "gauge")))),
                Dashboard.fromMap("mine", Map.of("name", "Mine", "order", 20,
                        "items", List.of(Map.of("placeholder", "me", "widget", "text")))),
                Dashboard.fromMap("overview", Map.of("name", Map.of("en", "Overview", "nl", "Overzicht", "de", "Übersicht"),
                        "description", Map.of("en", "Public", "nl", "Openbaar"), "order", 10, "background", " Stone ",
                        "items", List.of(Map.of("placeholder", "online", "widget", "gauge"), Map.of("placeholder", "secret", "widget", "text"),
                                Map.of("placeholders", List.of("tps", "secret", "online"), "widget", "line")))));
        var ctx = TestContexts.context("Test", "1.0", () -> ServerStatus.EMPTY, false, 5, 900, 3600, 0,
                new PlaceholderService((t, u) -> "7", clock, 0, defs), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("devices", ".json")), otps, new RateLimiter(clock, 100, 60000), dashboards, Set.of(),
                new HistoryService(clock, defs), null, d -> {}, perms, "en");
        api = new HttpApi(ctx);
        api.start("127.0.0.1", 0, null);
        base = "http://127.0.0.1:" + api.port();
    }

    private String pair(String uuid, String name) throws Exception {
        var res = Json.parseObject(post("/v1/auth", "{\"otp\":\"" + otps.create(uuid, name) + "\"}").body());
        return (String) res.get("accessToken");
    }

    @SuppressWarnings("unchecked")
    private List<String> dashboardIds(String token) throws Exception {
        var res = token == null ? get("/v1/dashboards") : get("/v1/dashboards", "Authorization", "Bearer " + token);
        return ((List<Map<String, Object>>) ((Map<String, Object>) Json.parse(res.body())).get("dashboards")).stream().map(m -> (String) m.get("id")).toList();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboardCarriesItsBackground() throws Exception {
        presetServer(TestContexts.allowAll());
        assertEquals("stone", ((Map<String, Object>) Json.parse(get("/v1/dashboards/overview").body())).get("background"), "normalised to lower case");
    }

    @Test
    void presetsAreOfferedByPermissionAndOrder() throws Exception {
        PermissionCache perms = new PermissionCache();
        presetServer(perms);
        assertEquals(List.of(), dashboardIds(null), "anonymous: a dashboard needs mcdashboard.dashboard.<id>");
        String steveUuid = "00000000-0000-0000-0000-000000000001";
        String steve = pair(steveUuid, "Steve");
        perms.update(UUID.fromString(steveUuid),
                Set.of("mcdashboard.dashboard.overview", "mcdashboard.dashboard.mine"));
        assertEquals(List.of("overview", "mine"), dashboardIds(steve), "a paired player with the dashboard permissions");
        String staffUuid = "00000000-0000-0000-0000-000000000002";
        String staffToken = pair(staffUuid, "Alex");
        perms.update(UUID.fromString(staffUuid), Set.of("mcdashboard.dashboard.overview",
                "mcdashboard.dashboard.mine", "mcdashboard.dashboard.staff"));
        assertEquals(List.of("overview", "mine", "staff"), dashboardIds(staffToken), "ordered by `order`");
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboardsFollowAcceptLanguageAndFallBackToTheServerDefault() throws Exception {
        presetServer(TestContexts.allowAll());
        var dutch = (Map<String, Object>) Json.parse(get("/v1/dashboards/overview", "Accept-Language", "nl-BE,nl;q=0.9,en;q=0.8").body());
        assertEquals("Overzicht", dutch.get("name"));
        assertEquals("Openbaar", dutch.get("description"));

        var german = (Map<String, Object>) Json.parse(get("/v1/dashboards/overview", "Accept-Language", "de-AT").body());
        assertEquals("Übersicht", german.get("name"));
        assertEquals("Public", german.get("description"), "the untranslated description falls back to the server default");

        var listing = (Map<String, Object>) Json.parse(get("/v1/dashboards", "Accept-Language", "fr").body());
        var first = (Map<String, Object>) ((List<?>) listing.get("dashboards")).get(0);
        assertEquals("Overview", first.get("name"), "no French: the server default is English");
        assertEquals("Authorization, Accept-Language", get("/v1/dashboards", "Accept-Language", "fr").headers().firstValue("Vary").orElseThrow());
    }

    @Test
    @SuppressWarnings("unchecked")
    void infoAdvertisesTheDefaultLanguageAndEveryDashboardLanguage() throws Exception {
        presetServer(new PermissionCache());
        var info = (Map<String, Object>) Json.parse(get("/v1/info").body());
        assertEquals("en", info.get("defaultLanguage"));
        assertEquals(List.of("de", "en", "nl"), info.get("languages"));
    }

    @Test
    void aPresetThatIsNotOfferedCanNotBeFetchedEither() throws Exception {
        PermissionCache perms = new PermissionCache();
        presetServer(perms);
        String steveUuid = "00000000-0000-0000-0000-000000000001";
        String steve = pair(steveUuid, "Steve");
        perms.update(UUID.fromString(steveUuid), Set.of("mcdashboard.dashboard.overview"));
        assertEquals(404, get("/v1/dashboards/staff", "Authorization", "Bearer " + steve).statusCode());
        assertEquals(404, get("/v1/dashboards/mine").statusCode());
        var overview = (Map<?, ?>) Json.parse(get("/v1/dashboards/overview", "Authorization", "Bearer " + steve).body());
        assertEquals("Public", overview.get("description"));
        assertEquals("mcdashboard.dashboard.overview", overview.get("permission"), "the permission is derived from the file name");
        var items = (List<?>) overview.get("items");
        assertEquals(2, items.size(), "the tile with only the staff-only placeholder is left out of the public dashboard");
        var combined = (Map<?, ?>) items.get(1);
        assertEquals(List.of("tps", "online"), combined.get("placeholders"), "a combined tile keeps what the caller may read");
        assertEquals("tps", combined.get("placeholder"), "the first one stays in `placeholder` for older apps");
        assertFalse(((Map<?, ?>) items.get(0)).containsKey("placeholders"), "a single placeholder does not repeat itself");
    }

    @Test
    void permissionedPlaceholdersAreGatedOnEveryRoute() throws Exception {
        PermissionCache perms = new PermissionCache();
        presetServer(perms);
        String steve = pair("uuid-steve", "Steve");
        assertEquals(401, get("/v1/placeholders/secret").statusCode(), "anonymous");
        assertEquals(403, get("/v1/placeholders/secret", "Authorization", "Bearer " + steve).statusCode(), "paired but without the permission");
        assertEquals("[]", get("/v1/values?ids=secret", "Authorization", "Bearer " + steve).body());
        assertFalse(get("/v1/placeholders", "Authorization", "Bearer " + steve).body().contains("secret"));
        perms.update(UUID.fromString("00000000-0000-0000-0000-000000000002"), Set.of("x.staff"));
        String staff = pair("00000000-0000-0000-0000-000000000002", "Staff");
        assertEquals(200, get("/v1/placeholders/secret", "Authorization", "Bearer " + staff).statusCode());
    }

    @Test
    void aDashboardMaximumCanComeFromAnotherPlaceholder() throws Exception {
        presetServer(new PermissionCache());
        var list = (List<?>) ((Map<?, ?>) Json.parse(get("/v1/placeholders").body())).get("placeholders");
        var online = list.stream().map(o -> (Map<?, ?>) o).filter(m -> "online".equals(m.get("id"))).findFirst().orElseThrow();
        assertEquals("max", online.get("maxFrom"));
    }

    @Test
    void linkingADeviceNotifiesTheOwner() throws Exception {
        api.stop();
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "tps", "value", "%server_tps%")), m -> fail(m));
        var linked = new ArrayList<DeviceStore.Device>();
        var ctx = TestContexts.context("Test", "1.0", () -> ServerStatus.EMPTY, false, 2, 900, 3600, 0,
                new PlaceholderService((t, u) -> "1", clock, 0, defs), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("devices", ".json")), otps, new RateLimiter(clock, 100, 60000), List.of(), Set.of(),
                new HistoryService(clock, defs), null, linked::add, TestContexts.allowAll(), "en");
        api = new HttpApi(ctx);
        api.start("127.0.0.1", 0, null);
        base = "http://127.0.0.1:" + api.port();
        assertEquals(401, post("/v1/auth", "{\"otp\":\"AAAAAAAA\"}").statusCode());
        assertTrue(linked.isEmpty(), "a failed pairing notifies nobody");
        assertEquals(200, post("/v1/auth", "{\"otp\":\"" + otps.create("uuid-9", "Alex") + "\",\"deviceLabel\":\"Pixel\"}").statusCode());
        assertEquals(1, linked.size());
        assertEquals("Alex", linked.get(0).playerName());
        assertEquals("Pixel", linked.get(0).label());
    }

    @Test
    void infoPublishesTheLicenseIdOnlyWhenConfigured() throws Exception {
        assertFalse(get("/v1/info").body().contains("licenseId"));
        api.stop();
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "tps", "value", "%server_tps%")), m -> fail(m));
        var ctx = TestContexts.context("Test", "1.0", () -> ServerStatus.EMPTY, false, 2, 900, 3600, 0,
                new PlaceholderService((t, u) -> "1", clock, 0, defs), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("devices", ".json")), otps, new RateLimiter(clock, 100, 60000), List.of(), Set.of(),
                new HistoryService(clock, defs), "mcl_abc", d -> {}, TestContexts.allowAll(), "en");
        api = new HttpApi(ctx);
        api.start("127.0.0.1", 0, null);
        base = "http://127.0.0.1:" + api.port();
        assertEquals("mcl_abc", ((Map<?, ?>) Json.parse(get("/v1/info").body())).get("licenseId"));
    }

    @Test
    void liveFeedHandshakeAndValues() throws Exception {
        // exercised end-to-end in LiveServerTest
        assertEquals(200, get("/v1/info").statusCode());
    }

    @Test
    void placeholderCatalogPagesSearchesAndLooksUpIds() throws Exception {
        api.stop();
        Clock clock = Clock.systemUTC();
        List<Map<String, Object>> raw = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            raw.add(Map.of("id", "p" + i, "name", "Item " + i, "value", "%x_" + i + "%", "group", i < 100 ? "Bulk" : "Other",
                    "description", i == 5 ? "Needle here" : "plain"));
        }
        raw.add(Map.of("id", "secret", "name", "Secret", "value", "%x_s%", "group", "Staff", "permission", "x.staff"));
        var defs = PlaceholderDefinition.parseAll(raw, m -> fail(m));
        var ctx = TestContexts.context("Test", "1.0", () -> ServerStatus.EMPTY, false, 2, 900, 3600, 0,
                new PlaceholderService((t, u) -> "1", clock, 0, defs), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("devices", ".json")), otps, new RateLimiter(clock, 100, 60000), List.of(), Set.of(),
                new HistoryService(clock, defs), null, d -> {}, new PermissionCache(), "en");
        api = new HttpApi(ctx);
        api.start("127.0.0.1", 0, null);
        base = "http://127.0.0.1:" + api.port();

        var page = (Map<?, ?>) Json.parse(get("/v1/placeholders").body());
        assertEquals(50, ((List<?>) page.get("placeholders")).size());
        assertEquals(120L, ((Number) page.get("total")).longValue(), "secret is hidden from anonymous callers");
        var groups = (List<?>) page.get("groups");
        assertEquals(Map.of("name", "Bulk", "count", 100L), normalise(groups.get(0)));
        assertEquals(2, groups.size());
        assertEquals("Bulk", ((Map<?, ?>) ((List<?>) page.get("placeholders")).get(0)).get("group"));
        assertTrue(get("/v1/placeholders").headers().firstValue("Cache-Control").orElse("").startsWith("public"));

        var second = (Map<?, ?>) Json.parse(get("/v1/placeholders?limit=100&offset=100").body());
        assertEquals(20, ((List<?>) second.get("placeholders")).size());
        assertEquals(1, ((List<?>) ((Map<?, ?>) Json.parse(get("/v1/placeholders?q=NEEDLE").body())).get("placeholders")).size());
        var other = (Map<?, ?>) Json.parse(get("/v1/placeholders?group=Other&q=item").body());
        assertEquals(20L, ((Number) other.get("total")).longValue());
        assertEquals(2, ((List<?>) other.get("groups")).size(), "groups ignore the group filter");
        assertEquals(400, get("/v1/placeholders?limit=0").statusCode());
        assertEquals(400, get("/v1/placeholders?limit=101").statusCode());
        assertEquals(400, get("/v1/placeholders?offset=-1").statusCode());
        assertEquals(400, get("/v1/placeholders?limit=x").statusCode());

        var byIds = (Map<?, ?>) Json.parse(get("/v1/placeholders?ids=p7,secret,nope,p3&q=zzz&limit=1").body());
        var items = (List<?>) byIds.get("placeholders");
        assertEquals(2, items.size(), "unknown and unreadable ids are left out; q and limit are ignored");
        assertEquals(2L, ((Number) byIds.get("total")).longValue());
        assertEquals(List.of(), byIds.get("groups"));
        assertEquals(400, get("/v1/placeholders?ids=" + String.join(",", Collections.nCopies(101, "p1"))).statusCode());
    }

    private int capDashboards;
    private int capTiles;

    @Test
    void infoPublishesTheOwnersCapsOnlyWhenSet() throws Exception {
        var none = Json.parseObject(get("/v1/info").body());
        assertFalse(none.containsKey("limits"), "no cap: nothing is published");
        capDashboards = 3;
        developerServer(new PermissionCache(), new AtomicBoolean(true));
        assertEquals(Map.of("maxDashboards", 3L), normalise(Json.parseObject(get("/v1/info").body()).get("limits")));
        capTiles = 5;
        developerServer(new PermissionCache(), new AtomicBoolean(true));
        assertEquals(Map.of("maxDashboards", 3L, "maxTiles", 5L), normalise(Json.parseObject(get("/v1/info").body()).get("limits")));
    }

    @Test
    void uploadAboveTheOwnersCapsIsRejected() throws Exception {
        PermissionCache perms = new PermissionCache();
        String devUuid = "00000000-0000-0000-0000-000000000010";
        String otherUuid = "11111111-0000-0000-0000-000000000011";
        perms.update(UUID.fromString(devUuid), Set.of("mcdashboard.developer"));
        perms.update(UUID.fromString(otherUuid), Set.of("mcdashboard.developer"));
        capTiles = 1;
        capDashboards = 1;
        developerServer(perms, new AtomicBoolean(true));
        String dev = pair(devUuid, "Dev");
        String other = pair(otherUuid, "Other");
        String tile = "{\"placeholder\":\"tps\",\"widget\":\"text\"}";
        var tooMany = post("/v1/dashboards", "{\"name\":\"x\",\"items\":[" + tile + "," + tile + "]}", dev);
        assertEquals(422, tooMany.statusCode());
        assertEquals("tiles-limit", Json.parseObject(tooMany.body()).get("code"));
        assertEquals(200, post("/v1/dashboards", "{\"name\":\"x\",\"items\":[" + tile + "]}", dev).statusCode());
        assertEquals(200, post("/v1/dashboards", "{\"name\":\"y\",\"items\":[" + tile + "]}", dev).statusCode(),
                "replacing the caller's own dashboard never adds one");
        var second = post("/v1/dashboards", "{\"name\":\"z\",\"items\":[" + tile + "]}", other);
        assertEquals(422, second.statusCode());
        assertEquals("dashboards-limit", Json.parseObject(second.body()).get("code"));
    }

    /** Starts a server whose dashboards are written to {@code temp} and whose developer mode is {@code mode}. */
    private DashboardStore developerServer(PermissionCache perms, AtomicBoolean mode) throws Exception {
        api.stop();
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(
                Map.of("id", "tps", "value", "%server_tps%", "type", "DECIMAL"),
                Map.of("id", "online", "value", "%server_online%", "type", "INTEGER")), m -> fail(m));
        DashboardStore store = new DashboardStore(temp.resolve("dashboards"), new SnakeYamlCodec(), m -> fail(m));
        var ctx = TestContexts.context("Test", "1.0", () -> ServerStatus.EMPTY, false, 5, 900, 3600, 0,
                new PlaceholderService((t, u) -> "7", clock, 0, defs), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("devices", ".json")), otps, new RateLimiter(clock, 100, 60000),
                store, Set.of(), new HistoryService(clock, defs), null, d -> {}, perms, "en", mode::get, () -> {});
        ctx = TestContexts.withLimits(ctx, capDashboards, capTiles);
        api = new HttpApi(ctx);
        api.start("127.0.0.1", 0, null);
        base = "http://127.0.0.1:" + api.port();
        return store;
    }

    @Test
    void developerUploadsAndReplacesOwnDashboard() throws Exception {
        PermissionCache perms = new PermissionCache();
        String developerUuid = "00000000-0000-0000-0000-000000000010";
        String otherUuid = "00000000-0000-0000-0000-000000000011";
        perms.update(UUID.fromString(developerUuid), Set.of("mcdashboard.developer"));
        AtomicBoolean mode = new AtomicBoolean(true);
        developerServer(perms, mode);
        String developer = pair(developerUuid, "Dev");
        String other = pair(otherUuid, "Other");

        var before = (Map<?, ?>) Json.parse(get("/v1/dashboards", "Authorization", "Bearer " + developer).body());
        assertEquals(true, before.get("canUpload"), "a developer may upload when developer mode is on");
        assertEquals(false, ((Map<?, ?>) Json.parse(get("/v1/dashboards", "Authorization", "Bearer " + other).body())).get("canUpload"));
        assertEquals(false, ((Map<?, ?>) Json.parse(get("/v1/dashboards").body())).get("canUpload"), "anonymous callers never upload");

        String first = "{\"name\":\"Staff board\",\"description\":\"For the staff\",\"background\":\"stone\",\"items\":[{\"placeholder\":\"tps\",\"widget\":\"gauge\",\"title\":\"TPS\",\"column\":0,\"row\":0,\"width\":2,\"height\":2}]}";
        assertEquals(401, post("/v1/dashboards", first).statusCode(), "a developer must be paired");
        assertEquals(403, post("/v1/dashboards", first, other).statusCode(), "the developer permission is required");
        var saved = (Map<?, ?>) Json.parse(post("/v1/dashboards", first, developer).body());
        String id = (String) saved.get("id");
        assertTrue(id.startsWith("dev-"), "one stable dashboard per developer");
        assertEquals("mcdashboard.dashboard." + id, saved.get("permission"), "derived from the file name");
        assertEquals("Staff board", saved.get("name"));

        assertTrue(dashboardIds(developer).contains(id), "the uploader sees the uploaded dashboard");
        assertFalse(dashboardIds(other).contains(id), "it is only for developers");
        Path file = temp.resolve("dashboards").resolve(id + ".yml");
        assertTrue(Files.readString(file).contains("Staff board"));
        assertTrue(Files.readString(file).contains("mcdashboard.dashboard." + id));

        String second = "{\"name\":\"Second board\",\"items\":[{\"placeholder\":\"online\",\"widget\":\"number\",\"column\":0,\"row\":0,\"width\":1,\"height\":1}]}";
        var replaced = (Map<?, ?>) Json.parse(post("/v1/dashboards", second, developer).body());
        assertEquals(id, replaced.get("id"), "uploading again replaces the developer's one dashboard");
        assertEquals("Second board", replaced.get("name"));
        try (var files = Files.list(temp.resolve("dashboards"))) {
            assertEquals(1, files.count(), "no duplicate YAML files");
        }
        assertEquals(1, dashboardIds(developer).stream().filter(id::equals).count());

        assertEquals(400, post("/v1/dashboards", "{\"name\":\"x\",\"items\":[{\"placeholder\":\"tps\",\"widget\":\"nope\"}]}", developer).statusCode());
        assertEquals(400, post("/v1/dashboards", "{\"name\":\"x\",\"items\":[{\"placeholder\":\"missing\",\"widget\":\"text\"}]}", developer).statusCode());
        assertEquals(400, post("/v1/dashboards", "{\"name\":\"x\",\"items\":[]}", developer).statusCode());
        assertEquals(400, post("/v1/dashboards", "{\"name\":\"x\",\"background\":\"lava\",\"items\":[{\"placeholder\":\"tps\",\"widget\":\"text\"}]}", developer).statusCode());

        String styled = "{\"name\":\"Styled\",\"backgroundImage\":{\"url\":\"https://cdn.example.com/bg.png\",\"dim\":0.5,\"fit\":\"tile\"},"
                + "\"items\":[{\"placeholder\":\"tps\",\"widget\":\"progress\",\"icon\":\"fa6-solid:heart\",\"options\":{\"style\":\"hearts\",\"color\":\"red\",\"junk\":1},\"column\":0,\"row\":0,\"width\":2,\"height\":1}]}";
        var styledSaved = (Map<?, ?>) Json.parse(post("/v1/dashboards", styled, developer).body());
        var styledItem = (Map<?, ?>) ((List<?>) styledSaved.get("items")).get(0);
        assertEquals("fa6-solid:heart", styledItem.get("icon"));
        assertEquals(Map.of("style", "hearts", "color", "red"), styledItem.get("options"), "unknown option keys are dropped");
        assertEquals("tile", ((Map<?, ?>) styledSaved.get("backgroundImage")).get("fit"));
        String yaml = Files.readString(file);
        assertTrue(yaml.contains("fa6-solid:heart") && yaml.contains("hearts") && yaml.contains("background-image"), "saved to the YAML file");
        assertEquals(400, post("/v1/dashboards", "{\"name\":\"x\",\"items\":[{\"placeholder\":\"tps\",\"widget\":\"text\",\"icon\":\"<svg>\"}]}", developer).statusCode());
        assertEquals(400, post("/v1/dashboards", "{\"name\":\"x\",\"backgroundImage\":{\"url\":\"http://x/a.png\"},\"items\":[{\"placeholder\":\"tps\",\"widget\":\"text\"}]}", developer).statusCode());
        assertEquals(200, post("/v1/dashboards", "{\"name\":\"x\",\"items\":[{\"placeholder\":\"tps\",\"widget\":\"heatmap\"}]}", developer).statusCode());

        mode.set(false);
        assertEquals(403, post("/v1/dashboards", second, developer).statusCode(), "the admin switch stops uploads");
        assertEquals(false, ((Map<?, ?>) Json.parse(get("/v1/dashboards", "Authorization", "Bearer " + developer).body())).get("canUpload"));
    }

    private static Map<String, Object> normalise(Object o) {
        Map<String, Object> m = new LinkedHashMap<>();
        ((Map<?, ?>) o).forEach((k, v) -> m.put(k.toString(), v instanceof Number n ? n.longValue() : v));
        return m;
    }

    private void restart(ClientPolicy policy) throws Exception {
        api.stop();
        api = new HttpApi(ctx, policy);
        api.start("127.0.0.1", 0, null);
        base = "http://127.0.0.1:" + api.port();
    }

    private String pair(String label) throws Exception {
        String code = otps.create("uuid-" + label, "Steve");
        return (String) Json.parseObject(post("/v1/auth", "{\"otp\":\"" + code + "\",\"deviceLabel\":\"" + label + "\"}").body()).get("accessToken");
    }

    @Test
    void onlyTheOfficialClientIsAccepted() throws Exception {
        assertEquals(200, get("/v1/placeholders/tps", "User-Agent", "MCDashboard/1.0.0 (ios)").statusCode());
        assertEquals(200, get("/v1/placeholders/tps", "User-Agent", "MCDashboard/2.0.0-rc.1 (dev)").statusCode());
        for (String ua : List.of("curl/8.0", "Mozilla/5.0 MCDashboard/1.2.3 (android)", "MCDashboard/1.2 (android)",
                "MCDashboard/1.2.3 (windows)", "MCDashboard/1.2.3 (android) x", "")) {
            var r = get("/v1/placeholders/tps", "User-Agent", ua);
            assertEquals(403, r.statusCode(), "User-Agent: " + ua);
            assertEquals("no-store", r.headers().firstValue("Cache-Control").orElseThrow());
        }
    }

    @Test
    void infoStaysOpenAndPairingNeedsTheClient() throws Exception {
        assertEquals(200, get("/v1/info", "User-Agent", "Uptime-Monitor/1").statusCode());
        String code = otps.create("uuid-1", "Steve");
        var denied = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/auth"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"otp\":\"" + code + "\"}")).setHeader("User-Agent", "curl/8.0").build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(403, denied.statusCode());
        assertEquals(200, post("/v1/auth", "{\"otp\":\"" + code + "\"}").statusCode(), "a refused client must not burn the code");
    }

    @Test
    void ownerCanAllowFurtherClients() throws Exception {
        restart(ClientPolicy.of(Clock.systemUTC(), List.of("Grafana/\\d+\\.\\d+.*"), null, 100, 100, m -> fail(m)));
        assertEquals(200, get("/v1/placeholders/tps", "User-Agent", "Grafana/10.2.1 (linux)").statusCode());
        assertEquals(403, get("/v1/placeholders/tps", "User-Agent", "Prometheus/2.0").statusCode());
        assertEquals(200, get("/v1/placeholders/tps").statusCode(), "the official app still works");
    }

    @Test
    void oldAppsGet426AndInfoPublishesTheMinimum() throws Exception {
        restart(ClientPolicy.of(Clock.systemUTC(), List.of(), "1.2.3", 100, 100, m -> fail(m)));
        assertEquals("1.2.3", ((Map<?, ?>) Json.parse(get("/v1/info", "User-Agent", "x").body())).get("minClientVersion"));
        assertEquals(200, get("/v1/placeholders/tps", "User-Agent", "MCDashboard/1.2.3 (ios)").statusCode());
        assertEquals(200, get("/v1/placeholders/tps", "User-Agent", "MCDashboard/1.10.0 (ios)").statusCode(), "1.10 is newer than 1.2");
        var old = get("/v1/placeholders/tps", "User-Agent", "MCDashboard/1.2.2 (ios)");
        assertEquals(426, old.statusCode());
        assertEquals("1.2.3", ((Map<?, ?>) Json.parse(old.body())).get("minVersion"));
        assertEquals(426, get("/v1/placeholders/tps", "User-Agent", "MCDashboard/0.9.9 (android)").statusCode());
    }

    @Test
    void noMinimumByDefaultAndBadConfigIsReported() throws Exception {
        assertNull(((Map<?, ?>) Json.parse(get("/v1/info").body())).get("minClientVersion"));
        List<String> warnings = new ArrayList<>();
        var p = ClientPolicy.of(Clock.systemUTC(), List.of("(unclosed"), "one.two", 10, 10, warnings::add);
        assertEquals(2, warnings.size());
        assertNull(p.minVersion());
        assertTrue(p.identify("MCDashboard/0.0.1 (dev)").isPresent());
        assertTrue(p.identify("(unclosed").isEmpty(), "a pattern that cannot compile never widens the policy");
    }

    @Test
    void limitsFollowThePairedDeviceNotTheUserAgent() throws Exception {
        String access = pair("Pixel");
        restart(ClientPolicy.of(Clock.systemUTC(), List.of("Spoof.*"), null, 3, 1000, m -> fail(m)));
        for (int i = 0; i < 3; i++) {
            assertEquals(200, get("/v1/placeholders/tps", "Authorization", "Bearer " + access,
                    "User-Agent", i % 2 == 0 ? APP : "Spoof/" + i).statusCode());
        }
        var limited = get("/v1/placeholders/tps", "Authorization", "Bearer " + access, "User-Agent", "Spoof/new");
        assertEquals(429, limited.statusCode());
        assertNotNull(limited.headers().firstValue("Retry-After").orElse(null));
        String other = pair("Tablet");
        assertEquals(200, get("/v1/placeholders/tps", "Authorization", "Bearer " + other).statusCode(), "another device has its own budget");
    }

    @Test
    void anonymousRequestsAreLimitedPerAddress() throws Exception {
        restart(ClientPolicy.of(Clock.systemUTC(), List.of(), null, 100, 2, m -> fail(m)));
        assertEquals(200, get("/v1/placeholders/tps").statusCode());
        assertEquals(200, get("/v1/placeholders/tps").statusCode());
        assertEquals(429, get("/v1/placeholders/tps").statusCode());
        assertEquals(200, get("/v1/info").statusCode(), "/info is not counted");
    }

    @Test
    void pairingRecordsTheClient() throws Exception {
        pair("Pixel");
        var device = ctx.devices().devicesOf("uuid-Pixel").get(0);
        assertEquals(APP, device.client());
    }

    @Test
    void infoStatesTheProtocolAndTheFeaturesOfThisPlugin() throws Exception {
        var info = Json.parseObject(get("/v1/info").body());
        assertEquals((long) ApiContext.PROTOCOL, ((Number) info.get("protocol")).longValue());
        assertTrue(((List<?>) info.get("features")).containsAll(ApiContext.FEATURES));
    }

    @Test
    void theBuiltInMinimumClientVersionCanOnlyBeRaisedByTheOwner() {
        assertEquals("1.4.0", ClientPolicy.higher("1.4.0", null));
        assertEquals("2.0.0", ClientPolicy.higher("1.4.0", "2.0.0"));
        assertEquals("2.1.0", ClientPolicy.higher("2.1.0", "2.0.0"));
        assertEquals("2.0.0", ClientPolicy.higher("", "2.0.0"));
    }

    /** Tokens signed by the portal's Node service (fixture generated with the service's own signer), key and all. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> fixture() throws Exception {
        try (var in = ApiTest.class.getResourceAsStream("/client-assertion-fixture.json")) {
            return Json.parseObject(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** The clock the fixture's tokens are valid at (issued 1_800_000_000 s, one hour). */
    private static Clock fixtureClock(long offsetSeconds) {
        return Clock.fixed(java.time.Instant.ofEpochSecond(1_800_000_000L + offsetSeconds), java.time.ZoneOffset.UTC);
    }

    @SuppressWarnings("unchecked")
    private ClientAssertions verifier(boolean deviceOnly, long offsetSeconds) throws Exception {
        return new ClientAssertions(List.of((Map<String, Object>) fixture().get("jwk")), fixtureClock(offsetSeconds), Set.of(), Set.of("mc.example.com"), deviceOnly);
    }

    @Test
    void verifiedClientsOnlyWhenTheOwnerRequiresIt() throws Exception {
        api.verifyClients(verifier(false, 10));
        String registered = (String) fixture().get("registered");
        assertEquals(200, get("/v1/info").statusCode(), "discovery stays open");
        assertEquals(403, get("/v1/values?ids=tps").statusCode());
        assertEquals(403, get("/v1/values?ids=tps", ClientAssertions.HEADER, "garbage").statusCode());
        assertEquals(200, get("/v1/values?ids=tps", ClientAssertions.HEADER, registered).statusCode());
        assertEquals(403, post("/v1/auth", "{\"otp\":\"x\"}").statusCode(), "pairing needs it too");
        assertTrue(get("/v1/info").body().contains("client-verification"));
    }

    @Test
    void anAssertionHasToBeCurrentAndOfTheRequiredLevel() throws Exception {
        String registered = (String) fixture().get("registered");
        String device = (String) fixture().get("device");
        api.verifyClients(verifier(true, 10));
        assertEquals(403, get("/v1/values?ids=tps", ClientAssertions.HEADER, registered).statusCode(), "device level only");
        assertEquals(200, get("/v1/values?ids=tps", ClientAssertions.HEADER, device).statusCode());
        api.verifyClients(verifier(false, 3600 + 10));
        assertEquals(403, get("/v1/values?ids=tps", ClientAssertions.HEADER, device).statusCode(), "expired");
        api.verifyClients(verifier(false, -3600));
        assertEquals(403, get("/v1/values?ids=tps", ClientAssertions.HEADER, device).statusCode(), "issued in the future");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAssertionIsOnlyGoodForItsHostAndOnlyFromTheServicesKey() throws Exception {
        String device = (String) fixture().get("device");
        var other = new ClientAssertions(List.of((Map<String, Object>) fixture().get("jwk")), fixtureClock(10), Set.of(), Set.of("another.example.com"), false);
        assertTrue(other.verify(device, null).isEmpty(), "issued for mc.example.com");
        var byHost = new ClientAssertions(List.of((Map<String, Object>) fixture().get("jwk")), fixtureClock(10), Set.of(), Set.of(), false);
        assertTrue(byHost.verify(device, "MC.example.com:1553").isPresent(), "the host the client connected to");
        assertTrue(byHost.verify(device, "evil.example.com").isEmpty());
        var wrongKey = new ClientAssertions(List.of(Map.of("kty", "EC", "crv", "P-256",
                "x", "f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU", "y", "x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0")),
                fixtureClock(10), Set.of(), Set.of("mc.example.com"), false);
        assertTrue(wrongKey.verify(device, null).isEmpty());
        var tampered = device.substring(0, device.length() - 4) + (device.endsWith("AAAA") ? "BBBB" : "AAAA");
        assertTrue(byHost.verify(tampered, "mc.example.com").isEmpty());
        assertTrue(new ClientAssertions(List.of(), fixtureClock(10), Set.of(), Set.of(), false).verify(device, "mc.example.com").isEmpty(),
                "no trusted key: nothing verifies");
    }

    @Test
    void theOwnerCanSwitchBackgroundUpdatesOff() throws Exception {
        assertEquals(200, get("/v1/values?ids=tps", HttpApi.PURPOSE_HEADER, "background").statusCode());
        assertTrue(get("/v1/info").body().contains("background"));
        api.background(false);
        assertEquals(403, get("/v1/values?ids=tps", HttpApi.PURPOSE_HEADER, "background").statusCode());
        assertEquals(200, get("/v1/values?ids=tps").statusCode(), "foreground requests are not affected");
        assertFalse(get("/v1/info").body().contains("\"background\""));
    }

    @Test
    void backgroundRequestsHaveTheirOwnLimit() throws Exception {
        api.stop();
        Clock clock = Clock.systemUTC();
        var policy = ClientPolicy.defaults(clock).background(new RateLimiter(clock, 2, 60_000));
        api = new HttpApi(ctx, policy);
        api.start("127.0.0.1", 0, null);
        base = "http://127.0.0.1:" + api.port();
        assertEquals(200, get("/v1/values?ids=tps", HttpApi.PURPOSE_HEADER, "background").statusCode());
        assertEquals(200, get("/v1/values?ids=tps", HttpApi.PURPOSE_HEADER, "background").statusCode());
        assertEquals(429, get("/v1/values?ids=tps", HttpApi.PURPOSE_HEADER, "background").statusCode());
        assertEquals(200, get("/v1/values?ids=tps").statusCode(), "a screen the player looks at is not limited by it");
    }
}
