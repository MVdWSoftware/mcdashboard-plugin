/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.diagnostics;

import org.mcdashboard.json.Json;
import org.mcdashboard.platform.Log;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Opt-in report of <em>unexpected</em> plugin errors (an uncaught exception or a failed start) to MVdW's GlitchTip.
 * Placeholder values, player names and server addresses are never sent: the event carries the exception and its
 * stack, masked for URLs, e-mail addresses and IP addresses. A placeholder that does not resolve is not an error
 * here; the plugin handles those itself and they stay in the server log.
 *
 * <p>Error reports are off unless {@code diagnostics.error-reports} is true. The report is best-effort: a tracker
 * that is down never affects the server.</p>
 */
public final class ErrorReporter {
    /** The public, write-only DSN of the {@code mcdashboard-plugin} GlitchTip project. */
    static final String DSN = "https://4b5f88e5eaed47b5811d149d0564dce2@monitor.mvdw-software.com/12";
    private static final int MAX_PER_HOUR = 20;
    private static final long DEDUPE_MS = 60_000;
    private static final long HOUR_MS = 3_600_000;
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63})*\\.[A-Za-z]{2,}");
    private static final Pattern IP = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    /** Sends one report; production posts it in the background, tests collect it. */
    interface Sender {
        void send(String endpoint, String body, String auth);
    }

    private final String endpoint;
    private final String publicKey;
    private final String release;
    private final LongSupplier clock;
    private final Sender sender;
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
    private long windowStart;
    private int windowCount;
    private Thread.UncaughtExceptionHandler previous;
    private Thread.UncaughtExceptionHandler handler;

    ErrorReporter(String endpoint, String publicKey, String release, LongSupplier clock, Sender sender) {
        this.endpoint = endpoint;
        this.publicKey = publicKey;
        this.release = release;
        this.clock = clock;
        this.sender = sender;
    }

    /**
     * Starts the reporter when the owner opted in. It also chains the JVM's uncaught-exception handler, so a crash in
     * a plugin thread is reported and the server keeps logging it as before.
     * @param enabled {@code diagnostics.error-reports}.
     * @param release The plugin version.
     * @param log The plugin log.
     * @return The reporter, or null when it is off.
     */
    public static ErrorReporter start(boolean enabled, String release, Log log) {
        if (!enabled) return null;
        ErrorReporter reporter = forDsn(DSN, release, System::currentTimeMillis, httpSender());
        if (reporter == null) {
            log.warning("Error reports are on, but the report address is invalid; nothing is sent.");
            return null;
        }
        reporter.install();
        log.info("Error reports are on: unexpected plugin errors go to MVdW's error tracker (no player data). "
                + "Turn them off with diagnostics.error-reports.");
        return reporter;
    }

    /** Builds a reporter for a DSN; null when it has no key or project. */
    static ErrorReporter forDsn(String dsn, String release, LongSupplier clock, Sender sender) {
        try {
            URI uri = URI.create(dsn);
            String key = uri.getUserInfo();
            String project = uri.getPath() == null ? "" : uri.getPath().replace("/", "");
            if (key == null || key.isBlank() || project.isBlank()) return null;
            String endpoint = uri.getScheme() + "://" + uri.getHost() + "/api/" + project + "/store/";
            return new ErrorReporter(endpoint, key, release, clock, sender);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Posts in the background; a failure is dropped, never logged per event (the tracker may simply be down). */
    static Sender httpSender() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return (endpoint, body, auth) -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(8))
                    .header("content-type", "application/json")
                    .header("x-sentry-auth", auth)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        };
    }

    /**
     * Reports one unexpected error.
     * @param error What was thrown.
     * @param context {@code startup} or {@code uncaught}; never player data.
     */
    public void report(Throwable error, String context) {
        if (error == null) return;
        long now = clock.getAsLong();
        if (!allow(key(error), now)) return;
        try {
            sender.send(endpoint, Json.write(event(error, context, now)), auth());
        } catch (RuntimeException e) {
            // Best-effort: a broken tracker (or a test sender) must never break the server.
        }
    }

    /** At most one report per distinct error a minute, and {@value #MAX_PER_HOUR} an hour. */
    private synchronized boolean allow(String key, long now) {
        Long previous = lastSent.get(key);
        if (previous != null && now - previous < DEDUPE_MS) return false;
        if (now - windowStart >= HOUR_MS) {
            windowStart = now;
            windowCount = 0;
            lastSent.clear();
        }
        if (windowCount >= MAX_PER_HOUR) return false;
        lastSent.put(key, now);
        windowCount++;
        return true;
    }

    private String key(Throwable error) {
        StackTraceElement[] stack = error.getStackTrace();
        return error.getClass().getName() + (stack.length == 0 ? "" : "@" + stack[0]);
    }

    /** The Sentry event: exception, stack, plugin version; no user, request, server or placeholder data. */
    Map<String, Object> event(Throwable error, String context, long now) {
        Map<String, Object> exception = new LinkedHashMap<>();
        exception.put("type", error.getClass().getName());
        exception.put("value", scrub(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        StackTraceElement[] stack = error.getStackTrace();
        if (stack.length > 0) {
            List<Map<String, Object>> frames = new ArrayList<>();
            for (StackTraceElement element : stack) {
                Map<String, Object> frame = new LinkedHashMap<>();
                frame.put("filename", element.getFileName() == null ? element.getClassName() : element.getFileName());
                frame.put("function", element.getMethodName());
                frame.put("lineno", element.getLineNumber());
                frame.put("in_app", element.getClassName().startsWith("org.mcdashboard"));
                frames.add(frame);
            }
            // Sentry expects the oldest frame first; Java stack traces start with the newest.
            Collections.reverse(frames);
            exception.put("stacktrace", Map.of("frames", frames));
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", UUID.randomUUID().toString().replace("-", ""));
        event.put("timestamp", Instant.ofEpochMilli(now).toString());
        event.put("platform", "java");
        event.put("level", "error");
        event.put("logger", "org.mcdashboard");
        event.put("release", release);
        event.put("environment", "production");
        event.put("tags", Map.of("service", "mcdashboard-plugin", "plugin_version", release));
        event.put("exception", Map.of("values", List.of(exception)));
        if (context != null && !context.isBlank()) event.put("extra", Map.of("context", context));
        return event;
    }

    /** Removes URLs, e-mail addresses and IP addresses from a message. */
    static String scrub(String text) {
        return IP.matcher(EMAIL.matcher(URL.matcher(text).replaceAll("[url]")).replaceAll("[email]")).replaceAll("[ip]");
    }

    private String auth() {
        return "Sentry sentry_version=7, sentry_key=" + publicKey + ", sentry_client=mcdashboard-plugin/" + release;
    }

    /** Chains the current uncaught-exception handler; the server still logs every crash as before. */
    synchronized void install() {
        if (handler != null) return;
        previous = Thread.getDefaultUncaughtExceptionHandler();
        handler = (thread, error) -> {
            // A JVM-wide handler also sees other plugins' crashes: report only errors that pass through our code.
            if (isOurs(error)) report(error, "uncaught");
            if (previous != null) previous.uncaughtException(thread, error);
            else error.printStackTrace();
        };
        Thread.setDefaultUncaughtExceptionHandler(handler);
    }

    /** Whether the throwable's stack names a class of this plugin. */
    static boolean isOurs(Throwable error) {
        for (StackTraceElement element : error.getStackTrace()) {
            if (element.getClassName().startsWith("org.mcdashboard")) return true;
        }
        return false;
    }

    /** Removes the handler again, e.g. on a plugin reload. */
    public synchronized void close() {
        if (handler != null && Thread.getDefaultUncaughtExceptionHandler() == handler) {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
        handler = null;
    }

    /** The endpoint events are posted to (tests and diagnostics). */
    String endpoint() {
        return endpoint;
    }
}
