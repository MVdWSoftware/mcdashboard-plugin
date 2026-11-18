/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.mcdashboard.dashboard.Languages;
import org.mcdashboard.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reading requests and writing JSON responses on the JDK HTTP server. */
final class Exchanges {
    /** A dashboard upload can be larger than a pairing request, but stays bounded. */
    private static final int MAX_BODY = 32 * 1024;

    private Exchanges() {
    }

    static void respond(HttpExchange ex, int status, Object json) throws IOException {
        byte[] bytes = Json.write(json).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    static void error(HttpExchange ex, int status, String message) throws IOException {
        respond(ex, status, Map.of("error", message));
    }

    /** Defaults for every response: any origin may call the API, and nothing is cached unless a route says so. */
    static void cors(HttpExchange ex) {
        Headers h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Headers", "Authorization, Content-Type, X-MCDashboard-Attestation, X-MCDashboard-Purpose");
        h.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        h.set("Cache-Control", "no-store");
    }

    static Map<String, Object> body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) throw new BadRequest("body too large");
            return Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new BadRequest("invalid JSON body");
        }
    }

    /** The decoded query parameters; the first of a repeated name wins. */
    static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(k, v);
        }
        return out;
    }

    static int intParam(Map<String, String> q, String name, int fallback) {
        String v = q.get(name);
        if (v == null || v.isEmpty()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new BadRequest(name + " must be an integer");
        }
    }

    /** The bearer token, or null. */
    static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        return h != null && h.startsWith("Bearer ") ? h.substring(7).trim() : null;
    }

    static List<String> languages(HttpExchange ex) {
        return Languages.preferred(ex.getRequestHeaders().getFirst("Accept-Language"));
    }

    /** Translated answers differ by language, so shared caches must key on {@code Accept-Language} too. */
    static void varyLanguage(HttpExchange ex) {
        ex.getResponseHeaders().set("Vary", "Authorization, Accept-Language");
    }

    static String peer(HttpExchange ex) {
        return ex.getRemoteAddress().getAddress().getHostAddress();
    }

    /** The client's address; {@code X-Forwarded-For} counts only from a trusted proxy, which appends the hop it saw. */
    static String remote(HttpExchange ex, Set<String> trustedProxies) {
        String peer = peer(ex);
        String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && trustedProxies.contains(peer)) {
            String[] hops = fwd.split(",");
            return hops[hops.length - 1].trim();
        }
        return peer;
    }
}
