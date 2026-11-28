/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.push;

import org.mcdashboard.json.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The plugin's connection to the portal's push relay. The portal is the only party that talks to Google and Apple; this
 * class hands it ciphertext addressed to opaque routes. The licence id is the credential, sent as a bearer header (never in
 * the URL, so it stays out of access logs). Like {@code LicenseHeartbeat} it has a short timeout, never follows redirects
 * and never throws.
 */
public class PushClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    /**
     * One message for a group of routes. {@code envelope} is what {@link PushCrypto#encrypt} produced.
     *
     * @param routes      The routes to deliver to (the portal accepts up to 100).
     * @param category    The coarse kind, for the portal's per-category limits and the user's mutes ({@code alert}).
     * @param envelope    The encrypted payload.
     * @param collapseKey Messages with the same key replace each other on the phone.
     * @param ttlSeconds  How long the push services may hold the message.
     */
    public record Message(List<String> routes, String category, Map<String, Object> envelope, String collapseKey, int ttlSeconds) {
    }

    /**
     * What the portal said about one route.
     *
     * @param status      {@code ok}, {@code throttled}, {@code gone}, {@code unknown_route}, {@code muted} or {@code suspended}.
     * @param retryAfter  Seconds to wait before the next try, or 0.
     */
    public record RouteResult(String status, int retryAfter) {
    }

    /**
     * The outcome of a send.
     *
     * @param results     The status per route, empty when the call failed as a whole.
     * @param unavailable True when the portal refused push for this server (not licensed, suspended or no push service), so the plugin stops offering it.
     * @param failure     A short reason for the log, or null.
     */
    public record Outcome(Map<String, RouteResult> results, boolean unavailable, String failure) {
    }

    private final String licenseId;
    private final String apiUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();

    /**
     * Creates a client.
     *
     * @param licenseId The licence id (the credential), or null.
     * @param apiUrl    The portal API base, for example {@code https://portal.mcdashboard.org/api}, or null.
     */
    public PushClient(String licenseId, String apiUrl) {
        this.licenseId = licenseId == null || licenseId.isBlank() ? null : licenseId.trim();
        String url = apiUrl == null || apiUrl.isBlank() ? null : apiUrl.trim();
        while (url != null && url.endsWith("/")) url = url.substring(0, url.length() - 1);
        this.apiUrl = url;
    }

    /**
     * Whether the client has what it needs to call the portal.
     *
     * @return True with a licence id and an API URL.
     */
    public boolean configured() {
        return licenseId != null && apiUrl != null;
    }

    /**
     * Delivers messages through the portal.
     *
     * @param messages The messages (at most 50 per call).
     * @return The per-route statuses, or why the call failed. Never throws.
     */
    public Outcome send(List<Message> messages) {
        if (!configured()) return new Outcome(Map.of(), true, "push is not configured");
        List<Object> list = new java.util.ArrayList<>();
        for (Message m : messages) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("routes", m.routes());
            o.put("category", m.category());
            o.put("ciphertext", m.envelope());
            if (m.collapseKey() != null) o.put("collapseKey", m.collapseKey());
            o.put("ttl", m.ttlSeconds());
            o.put("priority", "high");
            list.add(o);
        }
        try {
            HttpResponse<String> response = call("POST", "/v1/push/send", Json.write(Map.of("messages", list)));
            int code = response.statusCode();
            if (code == 402 || code == 403 || code == 404 || code == 501) return new Outcome(Map.of(), true, "the portal refused push (HTTP " + code + ")");
            if (code == 429) return new Outcome(Map.of(), false, "the portal is rate limiting this server");
            if (code != 200) return new Outcome(Map.of(), false, "the portal answered HTTP " + code);
            return new Outcome(parse(response.body()), false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(Map.of(), false, "interrupted");
        } catch (Exception e) {
            return new Outcome(Map.of(), false, "could not reach the portal: " + e.getMessage());
        }
    }

    /**
     * Tells the portal a route is no longer used (the device was revoked or turned push off). Best effort.
     *
     * @param routeToken The route to delete.
     * @return True when the portal confirmed (or the route was already gone).
     */
    public boolean revokeRoute(String routeToken) {
        if (!configured()) return false;
        try {
            int code = call("DELETE", "/v1/push/routes/" + java.net.URLEncoder.encode(routeToken, java.nio.charset.StandardCharsets.UTF_8), null).statusCode();
            return code == 200 || code == 204 || code == 404;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(apiUrl + path)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + licenseId).header("Accept", "application/json");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Map<String, RouteResult> parse(String body) {
        Map<String, RouteResult> out = new HashMap<>();
        try {
            if (Json.parse(body) instanceof Map<?, ?> root && root.get("results") instanceof List<?> results) {
                for (Object o : results) {
                    if (o instanceof Map<?, ?> m && m.get("route") instanceof String route && m.get("status") instanceof String status) {
                        out.put(route, new RouteResult(status, m.get("retryAfter") instanceof Number n ? Math.max(0, n.intValue()) : 0));
                    }
                }
            }
        } catch (IllegalArgumentException ignored) {
            // an unreadable answer means no route result: nothing is dropped on a guess
        }
        return out;
    }
}
