/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import org.mcdashboard.json.Json;
import org.mcdashboard.platform.Log;
import org.mcdashboard.util.Threads;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Reports the licence and the address this server runs on to the portal, once in a while.
 *
 * <p>This is <strong>UI/UX and a help against putting a licence id on the wrong server</strong>, not copy protection.
 * The plugin is open source, so a modified copy can simply not run this. What actually unlocks features is the signed,
 * host-bound licence token the app verifies; the portal cannot be talked into granting anything by this heartbeat. The
 * portal records it so a platform administrator can see which license id is in use and on which hosts.</p>
 */
public final class LicenseHeartbeat implements AutoCloseable {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String licenseId;
    private final String apiUrl;
    private final String host;
    private final int minutes;
    private final String version;
    private final Log log;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    private ScheduledExecutorService executor;
    /** The last problem reported, so a repeated failure does not spam the log every hour. */
    private volatile String lastProblem;

    /**
     * Creates a heartbeat reporter for the licence server.
     *
     * @param licenseId The public id from {@code config.yml}; null or blank disables the heartbeat.
     * @param apiUrl The portal API base (for example {@code https://portal.mcdashboard.org/api}); blank disables it.
     * @param host The address players connect to; blank disables it (the plugin cannot know it).
     * @param minutes How often to report; 0 disables it.
     * @param version The plugin version, so the portal can show it.
     * @param log Where warnings go.
     */
    public LicenseHeartbeat(String licenseId, String apiUrl, String host, int minutes, String version, Log log) {
        this.licenseId = blankToNull(licenseId);
        this.apiUrl = trimSlash(blankToNull(apiUrl));
        this.host = normalise(host);
        this.minutes = minutes;
        this.version = version;
        this.log = log;
    }

    /** Starts the schedule; does nothing when the licence, API URL, address or interval is missing. */
    public void start() {
        if (licenseId == null || apiUrl == null || host == null || minutes <= 0) return;
        executor = Executors.newSingleThreadScheduledExecutor(Threads.daemon("MCDashboard-license"));
        // Wait a little after startup, then keep reporting for as long as the server runs.
        executor.scheduleWithFixedDelay(this::beat, 15, minutes * 60L, TimeUnit.SECONDS);
    }

    /** Sends one heartbeat now (also used by the test). Never throws. */
    void beat() {
        try {
            String body = Json.write(Map.of("host", host, "version", version));
            HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl + "/v1/licenses/"
                            + URLEncoder.encode(licenseId, StandardCharsets.UTF_8) + "/heartbeat"))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                problem("the portal answered HTTP " + response.statusCode());
                return;
            }
            Object parsed;
            try {
                parsed = Json.parse(response.body());
            } catch (IllegalArgumentException e) {
                problem("the portal answered an unexpected body");
                return;
            }
            if (!(parsed instanceof Map<?, ?> answer)) {
                problem("the portal answered an unexpected body");
                return;
            }
            if (Boolean.FALSE.equals(answer.get("active"))) {
                problem("the portal reports that this licence has been revoked by a platform administrator");
            } else if (Boolean.FALSE.equals(answer.get("registered"))) {
                problem("this address (" + host + ") is not registered for the licence; register it in the portal");
            } else if (Boolean.FALSE.equals(answer.get("licensed"))) {
                problem("the licence has no running subscription; see the portal");
            } else if (lastProblem != null) {
                lastProblem = null;
                log.info("The licence heartbeat is healthy again.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            problem("could not reach the portal: " + e.getMessage());
        }
    }

    /** Logs a problem once, until it changes or a heartbeat succeeds. */
    private void problem(String message) {
        if (message.equals(lastProblem)) return;
        lastProblem = message;
        log.warning("Licence heartbeat: " + message + ".");
    }

    /** Stops the schedule. */
    @Override
    public void close() {
        if (executor != null) executor.shutdownNow();
    }

    /**
     * Derives the address players use: the explicit {@code license.host}, else the host of {@code server.public-url},
     * else the host of the first {@code server.endpoints} entry. Returns null when none of them is usable.
     * @param configured {@code license.host}.
     * @param publicUrl {@code server.public-url}.
     * @param endpoints {@code server.endpoints}.
     * @return The normalised host, or null.
     */
    public static String hostFor(String configured, String publicUrl, List<String> endpoints) {
        String explicit = normalise(configured);
        if (explicit != null) return explicit;
        String fromPublic = hostOf(publicUrl);
        if (fromPublic != null) return fromPublic;
        if (endpoints != null) {
            for (String endpoint : endpoints) {
                String fromEndpoint = hostOf(endpoint);
                if (fromEndpoint != null) return fromEndpoint;
            }
        }
        return null;
    }

    /** The host part of a URL or a bare host, without a port. */
    private static String hostOf(String value) {
        String v = blankToNull(value);
        if (v == null) return null;
        try {
            if (v.contains("://")) return normalise(URI.create(v).getHost());
            int slash = v.indexOf('/');
            return normalise(slash < 0 ? v : v.substring(0, slash));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Lower case, no port, no trailing dot; null for a blank or unusable value. */
    private static String normalise(String value) {
        String v = blankToNull(value);
        if (v == null) return null;
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            if (end < 0) return null;
            v = v.substring(1, end); // [IPv6] or [IPv6]:port
        } else {
            int port = v.lastIndexOf(':');
            if (port > 0 && v.indexOf(':') == port) v = v.substring(0, port); // host:port, not a bare IPv6 literal
        }
        v = v.toLowerCase(java.util.Locale.ROOT);
        while (v.endsWith(".")) v = v.substring(0, v.length() - 1);
        // A host name, IPv4 or IPv6 literal; anything else (spaces, a path, a typo) is not an address to report.
        if (!v.matches("[a-z0-9]([a-z0-9.-]*[a-z0-9])?") && !v.matches("[0-9a-f:]+")) return null;
        return v;
    }

    private static String trimSlash(String value) {
        if (value == null) return null;
        String v = value;
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        return v;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
