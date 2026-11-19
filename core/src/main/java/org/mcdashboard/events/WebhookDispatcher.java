/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.events;

import org.mcdashboard.json.Json;
import org.mcdashboard.util.Threads;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Delivers every event to the configured webhooks as a signed {@code POST}. Delivery is asynchronous and retried
 * with growing delays; a webhook that stays down loses the event after the last retry (and the owner is told in the log),
 * which is why the stream, which can resume, is the primary channel.
 *
 * <p>Headers: {@code X-MCDashboard-Event}, {@code X-MCDashboard-Delivery} (the event id),
 * {@code X-MCDashboard-Timestamp} (unix seconds) and {@code X-MCDashboard-Signature: sha256=<hex>}, the HMAC-SHA256 of
 * {@code "<timestamp>.<body>"} with the webhook's secret. Receivers must reject old timestamps.
 */
public final class WebhookDispatcher {
    public record Hook(URI url, String secret) {
    }

    public static final String SIGNATURE_HEADER = "X-MCDashboard-Signature";
    public static final String TIMESTAMP_HEADER = "X-MCDashboard-Timestamp";
    private static final long[] RETRY_SECONDS = {1, 5, 30, 120, 600};
    private static final int MAX_PENDING = 1000;

    private final List<Hook> hooks;
    private final HttpClient client;
    private final Clock clock;
    private final Consumer<String> warn;
    private final String userAgent;
    private final long[] retries;
    private final AtomicInteger pending = new AtomicInteger();
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, Threads.daemon("MCDashboard-webhooks"));
    private Runnable unsubscribe = () -> { };

    public WebhookDispatcher(List<Hook> hooks, Clock clock, String pluginVersion, Consumer<String> warn) {
        this(hooks, clock, pluginVersion, warn, RETRY_SECONDS);
    }

    WebhookDispatcher(List<Hook> hooks, Clock clock, String pluginVersion, Consumer<String> warn, long[] retrySeconds) {
        this.hooks = List.copyOf(hooks);
        this.clock = clock;
        this.warn = warn;
        this.retries = retrySeconds;
        this.userAgent = "MCDashboard/" + pluginVersion;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public void start(EventBus bus) {
        unsubscribe = bus.subscribe(null, this::dispatch, () -> { });
    }

    public void stop() {
        unsubscribe.run();
        executor.shutdownNow();
    }

    private void dispatch(DeviceEvent e) {
        byte[] body = Json.write(e.toJson()).getBytes(StandardCharsets.UTF_8);
        for (Hook h : hooks) {
            if (pending.incrementAndGet() > MAX_PENDING) {
                pending.decrementAndGet();
                warn.accept("Webhook queue is full; dropping " + e.id() + " for " + h.url());
                continue;
            }
            executor.execute(() -> attempt(h, e, body, 0));
        }
    }

    private void attempt(Hook h, DeviceEvent e, byte[] body, int n) {
        boolean ok = false;
        String why = "";
        try {
            long ts = clock.millis() / 1000;
            HttpRequest req = HttpRequest.newBuilder(h.url()).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", userAgent)
                    .header("X-MCDashboard-Event", e.type())
                    .header("X-MCDashboard-Delivery", e.id())
                    .header(TIMESTAMP_HEADER, Long.toString(ts))
                    .header(SIGNATURE_HEADER, sign(h.secret(), ts, body))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            int status = client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            ok = status / 100 == 2;
            if (!ok) why = "HTTP " + status;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            pending.decrementAndGet();
            return;
        } catch (Exception ex) {
            why = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
        }
        if (ok) {
            pending.decrementAndGet();
        } else if (n < retries.length) {
            executor.schedule(() -> attempt(h, e, body, n + 1), retries[n], TimeUnit.SECONDS);
        } else {
            pending.decrementAndGet();
            warn.accept("Webhook " + h.url() + " did not accept " + e.id() + " (" + why + "); giving up after " + (n + 1) + " attempts");
        }
    }

    /** {@code sha256=<hex>} of the HMAC over {@code "<timestamp>.<body>"}. */
    public static String sign(String secret, long timestampSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestampSeconds + ".").getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
