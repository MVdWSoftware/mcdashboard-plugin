/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.tunnel;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Answers the proxy's {@code req} frames with the plugin's own REST API on the loopback address. */
final class LoopbackRequests {
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "DELETE");
    private static final Set<String> FORWARDED = Set.of("accept", "accept-language", "authorization", "content-type",
            "if-none-match", "user-agent", "x-forwarded-for");

    private final HttpClient http;
    private final String base;

    LoopbackRequests(HttpClient http, String host, int port) {
        this.http = http;
        this.base = "http://" + (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host) + ":" + port;
    }

    /** The {@code res} frame for a {@code req} frame, or null when the thread was interrupted. */
    Map<String, Object> answer(Map<String, Object> req, long id) {
        String method = String.valueOf(req.get("m"));
        String path = String.valueOf(req.get("p"));
        if (!METHODS.contains(method) || !allowed(path)) return error(id, 404, "not found");
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8));
            if (req.get("h") instanceof Map<?, ?> headers) {
                for (Map.Entry<?, ?> h : headers.entrySet()) {
                    String name = String.valueOf(h.getKey()).toLowerCase(Locale.ROOT);
                    if (FORWARDED.contains(name) && h.getValue() instanceof String v) b.header(name, v.replaceAll("[\\r\\n]", ""));
                }
            }
            byte[] body = req.get("b") instanceof String s ? Base64.getDecoder().decode(s) : new byte[0];
            b.method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            Map<String, Object> headers = new LinkedHashMap<>();
            r.headers().map().forEach((k, v) -> {
                if (!v.isEmpty()) headers.put(k.toLowerCase(Locale.ROOT), v.get(0));
            });
            boolean tooBig = r.body().length > MAX_BODY;
            return frame(id, tooBig ? 502 : r.statusCode(), headers, tooBig ? new byte[0] : r.body());
        } catch (IOException | RuntimeException e) {
            return error(id, 502, "internal error");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Only {@code /v1/*}, never {@code /v1/events}. Checked on the decoded path, because that is what the API routes
     * on: {@code /v1/%65vents} must not slip through.
     */
    static boolean allowed(String pathAndQuery) {
        if (!pathAndQuery.startsWith("/")) return false;
        String path;
        try {
            path = new URI(pathAndQuery).normalize().getPath();
        } catch (URISyntaxException e) {
            return false;
        }
        if (path == null || !path.startsWith("/v1/") || List.of(path.split("/")).contains("..")) return false;
        return !path.equals("/v1/events") && !path.startsWith("/v1/events/");
    }

    private static Map<String, Object> error(long id, int status, String message) {
        return frame(id, status, Map.of("content-type", "application/json"),
                ("{\"error\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> frame(long id, int status, Map<String, Object> headers, byte[] body) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("t", "res");
        out.put("id", id);
        out.put("s", status);
        out.put("h", headers);
        out.put("b", Base64.getEncoder().encodeToString(body));
        return out;
    }
}
