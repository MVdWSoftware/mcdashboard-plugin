/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.mcdashboard.json.Json;
import org.mcdashboard.placeholder.HistoryService;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.placeholder.PlaceholderService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mcdashboard.web.Exchanges.error;
import static org.mcdashboard.web.Exchanges.intParam;
import static org.mcdashboard.web.Exchanges.query;
import static org.mcdashboard.web.Exchanges.respond;

/** The placeholder catalog, their values (SenML) and their history. */
final class PlaceholderRoutes {
    private static final int MAX_IDS = 100;
    private static final long VALUES_BUDGET_MS = 4_000;

    private final ApiContext ctx;

    PlaceholderRoutes(ApiContext ctx) {
        this.ctx = ctx;
    }

    /**
     * {@code GET /v1/placeholders}: a page of the placeholders the caller may read. {@code ids} returns exactly those
     * instead and ignores {@code q}, {@code group}, {@code limit} and {@code offset}.
     */
    void list(HttpExchange ex, String player) throws IOException {
        Map<String, String> q = query(ex);
        List<Object> list = new ArrayList<>();
        List<Object> groups = new ArrayList<>();
        int total;
        if (q.containsKey("ids")) {
            String[] ids = q.get("ids").isEmpty() ? new String[0] : q.get("ids").split(",");
            if (ids.length > MAX_IDS) throw new BadRequest("ids must list at most 100 placeholder ids");
            Set<String> seen = new HashSet<>();
            for (String id : ids) {
                Optional<PlaceholderDefinition> d = ctx.placeholders().definition(id.trim());
                if (d.isPresent() && visible(d.get(), player) && seen.add(d.get().id())) {
                    list.add(d.get().toJson(ctx.placeholders().intervalMs(d.get(), player)));
                }
            }
            total = list.size();
        } else {
            int limit = intParam(q, "limit", 50);
            int offset = intParam(q, "offset", 0);
            if (limit < 1 || limit > 100) throw new BadRequest("limit must be 1-100");
            if (offset < 0) throw new BadRequest("offset must not be negative");
            String group = q.get("group");
            PlaceholderService.Page page = ctx.placeholders().search(d -> visible(d, player), q.get("q"),
                    group == null || group.isEmpty() ? null : group, limit, offset);
            for (PlaceholderDefinition d : page.items()) {
                list.add(d.toJson(ctx.placeholders().intervalMs(d, player)));
            }
            for (PlaceholderService.Group g : page.groups()) groups.add(Map.of("name", g.name(), "count", g.count()));
            total = page.total();
        }
        boolean priv = ctx.requireAuthentication() || player != null;
        ex.getResponseHeaders().set("Cache-Control", (priv ? "private" : "public") + ", max-age=" + (priv ? 10 : 60));
        ex.getResponseHeaders().set("Vary", "Authorization");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("placeholders", list);
        out.put("total", total);
        out.put("groups", groups);
        respond(ex, 200, out);
    }

    /** {@code GET /v1/placeholders/{id}}. */
    void value(HttpExchange ex, String id, String player) throws IOException {
        Optional<PlaceholderDefinition> def = ctx.placeholders().definition(id);
        if (def.isEmpty()) {
            error(ex, 404, "unknown placeholder");
            return;
        }
        if (!visible(def.get(), player)) {
            error(ex, player == null ? 401 : 403, player == null ? "authentication required" : "not allowed");
            return;
        }
        Optional<PlaceholderService.Value> v = ctx.placeholders().read(id, player);
        if (v.isEmpty()) {
            error(ex, 503, "value unavailable");
            return;
        }
        cacheable(ex, List.of(SenMl.record(def.get(), v.get())), List.of(def.get()), player != null, player);
    }

    /** {@code GET /v1/values?ids=a,b}: the readable ones, sorted so the ETag and a proxy's cache key are stable. */
    void values(HttpExchange ex, String player) throws IOException {
        String param = query(ex).getOrDefault("ids", "");
        String[] ids = param.isEmpty() ? new String[0] : param.split(",");
        if (ids.length == 0 || ids.length > MAX_IDS) throw new BadRequest("ids must list 1-100 placeholder ids");
        Arrays.sort(ids);
        List<Object> records = new ArrayList<>();
        List<PlaceholderDefinition> used = new ArrayList<>();
        long budgetEnd = System.currentTimeMillis() + VALUES_BUDGET_MS;
        for (String id : ids) {
            Optional<PlaceholderDefinition> d = ctx.placeholders().definition(id);
            if (d.isEmpty() || !visible(d.get(), player)) continue;
            // A slow server thread must not hold this request (and its worker) for 100 x 2 s: answer with what is ready.
            if (System.currentTimeMillis() > budgetEnd) break;
            ctx.placeholders().read(id, player).ifPresent(val -> {
                records.add(SenMl.record(d.get(), val));
                used.add(d.get());
            });
        }
        cacheable(ex, records, used, player != null, player);
    }

    /** {@code GET /v1/history/{id}?since=<unix seconds>&points=<n>}. */
    void history(HttpExchange ex, String id, String player) throws IOException {
        Optional<PlaceholderDefinition> def = ctx.placeholders().definition(id);
        if (def.isEmpty() || !ctx.history().tracked(id) || !visible(def.get(), player)) {
            error(ex, 404, "no history for this placeholder");
            return;
        }
        Map<String, String> q = query(ex);
        String sinceParam = q.get("since");
        long since = sinceParam != null && sinceParam.matches("\\d{1,12}") ? Long.parseLong(sinceParam) * 1000 : 0;
        String pointsParam = q.get("points");
        int points = pointsParam != null && pointsParam.matches("\\d{1,9}") ? Integer.parseInt(pointsParam) : HistoryService.MAX_POINTS;
        List<Object> records = new ArrayList<>();
        for (HistoryService.Point p : ctx.history().since(id, since, points)) {
            records.add(SenMl.record(def.get(), new PlaceholderService.Value(id, p.value(), p.timeMs())));
        }
        cacheable(ex, records, List.of(def.get()), false, player);
    }

    private boolean visible(PlaceholderDefinition d, String player) {
        return d.readableBy(player, ctx.permissions());
    }

    /**
     * A SenML pack with standard HTTP caching: {@code max-age} is the shortest effective refresh interval of the
     * placeholders for this caller and an ETag allows 304s. Only anonymous answers without PLAYER values are
     * {@code public}, so a shared cache in front can never hand one player's data to another.
     */
    private void cacheable(HttpExchange ex, List<?> records, List<PlaceholderDefinition> defs, boolean authenticated,
                           String player) throws IOException {
        long maxAge = Long.MAX_VALUE;
        boolean anyPlayer = false;
        for (PlaceholderDefinition d : defs) {
            maxAge = Math.min(maxAge, ctx.placeholders().intervalMs(d, player) / 1000);
            anyPlayer |= d.access() == PlaceholderDefinition.Access.PLAYER;
        }
        if (maxAge == Long.MAX_VALUE) maxAge = 0;
        boolean shared = !ctx.requireAuthentication() && !anyPlayer && !authenticated;
        byte[] bytes = Json.write(records).getBytes(StandardCharsets.UTF_8);
        String etag = "\"" + HexFormat.of().formatHex(sha256(bytes), 0, 12) + "\"";
        Headers h = ex.getResponseHeaders();
        h.set("Cache-Control", (shared ? "public" : "private") + ", max-age=" + maxAge);
        h.set("ETag", etag);
        h.set("Vary", "Authorization");
        if (etag.equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
            ex.sendResponseHeaders(304, -1);
            return;
        }
        h.set("Content-Type", SenMl.MEDIA_TYPE + "; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
