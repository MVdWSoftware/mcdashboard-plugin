/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mcdashboard.TestContexts;
import org.mcdashboard.auth.*;
import org.mcdashboard.json.Json;
import org.mcdashboard.placeholder.*;

class RoutesGapsTest {
    static final String APP = "MCDashboard/1.2.3 (android)";
    HttpApi api;
    OtpService otps;
    String base;
    PermissionCache perms = new PermissionCache();
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(
                Map.of("id", "tps", "value", "%server_tps%", "type", "DECIMAL", "history", 10),
                Map.of("id", "secret", "value", "%secret%", "permission", "x.y"),
                Map.of("id", "none", "value", "%none%"),
                Map.of("id", "me", "value", "%me%", "access", "PLAYER")), m -> fail(m));
        otps = new OtpService(clock, 60);
        HistoryService history = new HistoryService(clock, defs);
        history.record("tps", "19.5", 1000);
        ApiContext ctx = TestContexts.context("T", "1", () -> ServerStatus.EMPTY, false, 5, 900, 3600, 0,
                new PlaceholderService((t, u) -> t.equals("%none%") ? null : "5", clock, 0, defs), new TokenService(new byte[48], clock),
                new DeviceStore(Files.createTempFile("devices", ".json")), otps, new RateLimiter(clock, 100, 60000),
                List.of(), Set.of(), history, null, d -> {
                }, perms, "en");
        api = new HttpApi(ctx);
        api.start("127.0.0.1", 0, null);
        base = "http://127.0.0.1:" + api.port();
    }

    @AfterEach
    void stop() {
        api.stop();
    }

    HttpResponse<String> get(String path, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).setHeader("User-Agent", APP);
        if (token != null) b.setHeader("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    String pair() throws Exception {
        String uuid = UUID.randomUUID().toString();
        var r = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/auth")).POST(HttpRequest.BodyPublishers.ofString("{\"otp\":\"" + otps.create(uuid, "S") + "\"}"))
                .setHeader("User-Agent", APP).build(), HttpResponse.BodyHandlers.ofString());
        return (String) Json.parseObject(r.body()).get("accessToken");
    }

    @Test
    void valuesAreGatedAndMayBeUnavailable() throws Exception {
        assertEquals(401, get("/v1/placeholders/me", null).statusCode());
        assertEquals(401, get("/v1/placeholders/secret", null).statusCode());
        String token = pair();
        assertEquals(403, get("/v1/placeholders/secret", token).statusCode());
        assertEquals(503, get("/v1/placeholders/none", null).statusCode());
        assertEquals(200, get("/v1/placeholders/me", token).statusCode());
        assertEquals("[]", get("/v1/values?ids=none,secret,unknown", null).body());
        assertEquals(200, get("/v1/values?ids=tps,me", token).statusCode());
    }

    @Test
    void historyParametersAreOptionalAndValidated() throws Exception {
        assertEquals(404, get("/v1/history/secret", null).statusCode());
        assertEquals(200, get("/v1/history/tps", null).statusCode());
        assertEquals(200, get("/v1/history/tps?since=abc&points=zz", null).statusCode());
        assertEquals(200, get("/v1/history/tps?since=1&points=5", null).statusCode());
    }

    @Test
    void theCatalogListHandlesIdsGroupsAndBadPaging() throws Exception {
        assertEquals(200, get("/v1/placeholders?ids=", null).statusCode());
        var ids = Json.parseObject(get("/v1/placeholders?ids=tps,tps,secret,nope", null).body());
        assertEquals(1L, ((Number) ids.get("total")).longValue());
        assertEquals(400, get("/v1/placeholders?ids=" + "a,".repeat(101) + "b", null).statusCode());
        assertEquals(200, get("/v1/placeholders?group=&q=tps&offset=0&limit=10", null).statusCode());
        assertEquals(400, get("/v1/placeholders?limit=0", null).statusCode());
        assertEquals(400, get("/v1/placeholders?limit=abc", null).statusCode());
        assertEquals(400, get("/v1/placeholders?offset=-1", null).statusCode());
        assertEquals(200, get("/v1/placeholders?&limit=%31&limit=2&flag", null).statusCode());
        String token = pair();
        assertTrue(get("/v1/placeholders", token).headers().firstValue("Cache-Control").orElse("").startsWith("private"));
    }

    @Test
    void apiBodiesAreLimitedAndAuthRoutesWork() throws Exception {
        var big = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/auth")).POST(HttpRequest.BodyPublishers.ofString("{\"otp\":\"" + "x".repeat(40_000) + "\"}"))
                .setHeader("User-Agent", APP).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, big.statusCode());
        var bad = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/auth")).POST(HttpRequest.BodyPublishers.ofString("not json"))
                .setHeader("User-Agent", APP).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(400, bad.statusCode());
    }

    // ---- frames
    private static byte[] masked(int first, byte[] payload, int lengthForm) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(first);
        if (lengthForm == 126) {
            o.write(0x80 | 126);
            o.write(payload.length >> 8);
            o.write(payload.length);
        } else if (lengthForm == 127) {
            o.write(0x80 | 127);
            for (int s = 56; s >= 0; s -= 8) o.write((int) ((long) payload.length >> s));
        } else {
            o.write(0x80 | payload.length);
        }
        byte[] mask = {1, 2, 3, 4};
        o.writeBytes(mask);
        for (int i = 0; i < payload.length; i++) o.write(payload[i] ^ mask[i & 3]);
        return o.toByteArray();
    }

    private static void protocolError(byte[] frame, int max, String part) {
        var e = assertThrows(WebSocketFrames.ProtocolException.class, () -> WebSocketFrames.read(new ByteArrayInputStream(frame), max));
        assertTrue(e.getMessage().contains(part), e.getMessage());
    }

    @Test
    void framesCoverEveryLengthFormAndEveryProtocolViolation() throws Exception {
        assertEquals("hi", WebSocketFrames.read(new ByteArrayInputStream(masked(0x81, "hi".getBytes(), 127)), 100).text());
        assertEquals("hi", WebSocketFrames.read(new ByteArrayInputStream(masked(0x81, "hi".getBytes(), 126)), 100).text());
        protocolError(masked(0x89, new byte[200], 126), 1000, "bad control frame");
        protocolError(masked(0x09, "x".getBytes(), 0), 1000, "bad control frame");
        protocolError(masked(0x82, "x".getBytes(), 0), 1000, "unsupported opcode");
        protocolError(masked(0x80, "x".getBytes(), 0), 1000, "unexpected continuation");
        byte[] twoTexts = new byte[0];
        ByteArrayOutputStream both = new ByteArrayOutputStream();
        both.writeBytes(masked(0x01, "a".getBytes(), 0));
        both.writeBytes(masked(0x01, "b".getBytes(), 0));
        protocolError(both.toByteArray(), 1000, "interleaved");
        ByteArrayOutputStream big = new ByteArrayOutputStream();
        big.writeBytes(masked(0x01, new byte[60], 0));
        big.writeBytes(masked(0x80, new byte[60], 0));
        protocolError(big.toByteArray(), 100, "message too large");
        byte[] negative = {(byte) 0x81, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        protocolError(negative, 100, "frame too large");
        assertNotNull(twoTexts);
        assertEquals(WebSocketFrames.PING, WebSocketFrames.read(new ByteArrayInputStream(masked(0x89, "p".getBytes(), 0)), 100).opcode());
        assertThrows(java.io.EOFException.class, () -> WebSocketFrames.read(new ByteArrayInputStream(new byte[0]), 100));
    }

    @Test
    void writesTheThreeLengthForms() throws Exception {
        for (int n : new int[]{5, 300, 70_000}) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            WebSocketFrames.write(out, WebSocketFrames.TEXT, new byte[n]);
            assertEquals(n + (n < 126 ? 2 : n <= 0xFFFF ? 4 : 10), out.size());
        }
    }

    // ---- upload validation
    private PlaceholderService service() {
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "tps", "value", "%x%")), m -> fail(m));
        return new PlaceholderService((t, u) -> "1", Clock.systemUTC(), 0, defs);
    }

    private static Map<String, Object> item(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("widget", "text");
        m.put("placeholder", "tps");
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private void badUpload(Map<String, Object> body, String part) {
        var e = assertThrows(BadRequest.class, () -> DashboardUpload.parse(body, service()));
        assertTrue(e.getMessage().contains(part), e.getMessage());
    }

    private static Map<String, Object> board(Object items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "N");
        m.put("items", items);
        return m;
    }

    @Test
    void uploadsAreValidatedPerField() {
        badUpload(board(null), "non-empty list");
        badUpload(board("x"), "non-empty list");
        badUpload(board(List.of()), "non-empty list");
        badUpload(board(java.util.Collections.nCopies(500, item())), "at most");
        badUpload(board(List.of("x")), "each item must be an object");
        badUpload(board(List.of(item("column", 4))), "column must be 0-3");
        badUpload(board(List.of(item("column", -1))), "column must be 0-3");
        badUpload(board(List.of(item("row", 1000))), "row must be 0-999");
        badUpload(board(List.of(item("row", -1))), "row must be 0-999");
        badUpload(board(List.of(item("width", 5))), "width must be 1-4");
        badUpload(board(List.of(item("width", 0))), "width must be 1-4");
        badUpload(board(List.of(item("height", 65))), "height must be 1-64");
        badUpload(board(List.of(item("height", 0))), "height must be 1-64");
        badUpload(board(List.of(item("column", "a"))), "grid values must be numbers");
        badUpload(board(List.of(item("options", "x"))), "options must be an object");
        badUpload(board(List.of(item("icon", "!!"))), "icon must be pack:name");
        badUpload(board(List.of(item("placeholder", null))), "an item needs a placeholder");
        badUpload(board(List.of(item("placeholders", List.of(" ", 1, "tps", "tps"), "placeholder", null))), "unknown placeholder: 1");
        badUpload(board(List.of(item("placeholders", List.of()))), "an item needs a placeholder");
        badUpload(board(List.of(item("placeholder", "x".repeat(200)))), "longer than");
        badUpload(board(List.of(item("widget", ""))), "missing text");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add("p" + i);
        badUpload(board(List.of(item("placeholders", many))), "placeholder");
        Map<String, Object> noName = board(List.of(item()));
        noName.remove("name");
        badUpload(noName, "missing text");
        Map<String, Object> ok = board(List.of(item("placeholders", List.of("tps", "tps"))));
        ok.put("description", "d");
        ok.put("background", "STONE");
        assertEquals(1, DashboardUpload.parse(ok, service()).items().get(0).placeholders().size());
    }

    // ---- SenML
    private static String sen(PlaceholderDefinition.Type type, String raw) {
        var def = PlaceholderDefinition.parseAll(List.of(Map.of("id", "x", "value", "%x%", "type", type.name())), m -> fail(m)).get(0);
        return Json.write(SenMl.record(def, new PlaceholderService.Value("x", raw, 0)));
    }

    @Test
    void autoTypedValuesAreInferredAndBadNumbersFallBackToStrings() {
        assertTrue(sen(PlaceholderDefinition.Type.AUTO, "+5").contains("\"v\":5"));
        assertTrue(sen(PlaceholderDefinition.Type.AUTO, "~5,5").contains("\"v\":5.5"));
        assertTrue(sen(PlaceholderDefinition.Type.AUTO, "007").contains("\"vs\":\"007\""));
        assertTrue(sen(PlaceholderDefinition.Type.AUTO, "99999999999999999999").contains("\"vs\""));
        assertTrue(sen(PlaceholderDefinition.Type.AUTO, "TRUE").contains("\"vb\":true"));
        assertTrue(sen(PlaceholderDefinition.Type.AUTO, "hello").contains("\"vs\":\"hello\""));
        assertTrue(sen(PlaceholderDefinition.Type.BOOLEAN, "false").contains("\"vb\":false"));
        assertTrue(sen(PlaceholderDefinition.Type.BOOLEAN, "maybe").contains("\"vs\""));
        assertTrue(sen(PlaceholderDefinition.Type.INTEGER, "abc").contains("\"vs\""));
        assertTrue(sen(PlaceholderDefinition.Type.STRING, "abc").contains("\"vs\""));
    }
}
