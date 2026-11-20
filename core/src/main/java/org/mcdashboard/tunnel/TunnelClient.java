/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.tunnel;

import org.mcdashboard.events.DeviceEvent;
import org.mcdashboard.events.EventBus;
import org.mcdashboard.json.Json;
import org.mcdashboard.util.PrivateNetwork;
import org.mcdashboard.util.Threads;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The plugin's side of the reverse tunnel to MCDashboard-proxy: one outbound WebSocket carries REST requests (answered
 * by {@link LoopbackRequests}), live-feed streams ({@link LiveChannels}) and device events, so the server needs no
 * inbound port for the API.
 * <p>
 * Security: outbound only, authenticated by the key ({@code Authorization: Bearer}), which is never logged; {@code ws://}
 * is refused unless the proxy is on a private network or the owner opted in.
 */
public final class TunnelClient {
    /** Largest message either side sends (the proxy enforces the same). */
    static final int MAX_MESSAGE = 4 * 1024 * 1024;

    private final URI url;
    private final String key;
    private final String serverName;
    private final String version;
    private final EventBus bus;
    private final Consumer<String> info;
    private final Consumer<String> warn;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService workers = Executors.newCachedThreadPool(Threads.daemon("MCDashboard-tunnel"));
    /** Channel frames (open, data, close) must be applied in the order the proxy sent them: data follows open at once. */
    private final ExecutorService ordered = Executors.newSingleThreadExecutor(Threads.daemon("MCDashboard-tunnel-channels"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(Threads.daemon("MCDashboard-tunnel-timer"));
    private final LoopbackRequests requests;
    private final LiveChannels channels;

    private final AtomicInteger generation = new AtomicInteger();
    private long minDelayMs = 1000;
    private long maxDelayMs = 60_000;
    private long stableMs = 30_000;
    private long readyTimeoutMs = 10_000;
    private long deadMs = 60_000;
    private long pingMs = 20_000;
    private volatile boolean ready;
    private volatile long connectedSince;
    private volatile String lastError;
    private volatile long lastErrorMs;
    private volatile long rttMs;
    private volatile long pingSentAt;
    private volatile boolean stopped;
    private volatile WebSocket ws;
    private volatile Runnable unsubscribe = () -> {
    };
    private volatile long lastActivity;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
    private final AtomicInteger attempt = new AtomicInteger();

    /** {@code localHost} is where the plugin's own API listens; {@code livePort} is 0 when the live feed is off. */
    public TunnelClient(URI url, String key, String localHost, int httpPort, int livePort, String serverName, String version,
                        EventBus bus, Consumer<String> info, Consumer<String> warn) {
        this.url = url;
        this.key = key;
        this.serverName = serverName;
        this.version = version;
        this.bus = bus;
        this.info = info;
        this.warn = warn;
        this.requests = new LoopbackRequests(http, localHost, httpPort);
        this.channels = new LiveChannels(localHost, livePort, workers, this::send);
    }

    /** Rejects a configuration that would send the key in the clear or is too weak, with the reason. */
    public static URI validate(String url, String key, boolean allowInsecure) {
        if (key == null || key.length() < 32) throw new IllegalArgumentException("tunnel.key must be at least 32 random characters");
        URI uri;
        try {
            uri = URI.create(url == null ? "" : url.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("tunnel.url is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (uri.getHost() == null || !(scheme.equals("ws") || scheme.equals("wss"))) throw new IllegalArgumentException("tunnel.url must be a ws:// or wss:// URL");
        if (scheme.equals("ws") && !allowInsecure && !PrivateNetwork.isPrivate(uri.getHost())) {
            throw new IllegalArgumentException("tunnel.url uses ws:// to a public host, which would send the key in the clear; use wss:// (or set tunnel.allow-insecure)");
        }
        return uri;
    }

    /** What an admin sees with {@code /mcdashboard tunnel}. */
    public record Status(boolean connected, long connectedSinceMs, int attempts, String lastError, long lastErrorMs, long rttMs,
                         int channels) {
    }

    /** Backoff and health timings; tests shorten them. */
    TunnelClient timing(long minDelayMs, long maxDelayMs, long stableMs, long readyTimeoutMs, long deadMs, long pingMs) {
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.stableMs = stableMs;
        this.readyTimeoutMs = readyTimeoutMs;
        this.deadMs = deadMs;
        this.pingMs = pingMs;
        return this;
    }

    public void start() {
        connect();
        timer.scheduleWithFixedDelay(this::watchdog, pingMs, pingMs, TimeUnit.MILLISECONDS);
    }

    /** True once the proxy answered {@code ready}. */
    public boolean connected() {
        return ready && ws != null;
    }

    public Status status() {
        return new Status(connected(), connectedSince, attempt.get(), lastError, lastErrorMs, rttMs, channels.size());
    }

    public void stop() {
        stopped = true;
        generation.incrementAndGet();
        WebSocket w = ws;
        if (w != null) w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        ws = null;
        channels.closeAll();
        timer.shutdownNow();
        ordered.shutdownNow();
        workers.shutdownNow();
    }

    /**
     * One connection attempt. Every callback carries the generation it belongs to and is ignored once that connection
     * has been given up, so a socket that reports both an error and a close (or is aborted by the watchdog) schedules
     * exactly one reconnect and two connections can never run at once.
     */
    private void connect() {
        if (stopped) return;
        final int g = generation.incrementAndGet();
        StringBuilder partial = new StringBuilder();
        http.newWebSocketBuilder()
                .header("Authorization", "Bearer " + key)
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(url, new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket socket) {
                        if (generation.get() != g) {
                            socket.abort();
                            return;
                        }
                        ws = socket;
                        ready = false;
                        lastActivity = System.currentTimeMillis();
                        send(Map.of("t", "hello", "server", serverName, "version", version));
                        // The proxy answers `ready` at once; silence means it is not (or no longer) a working tunnel endpoint.
                        timer.schedule(() -> {
                            if (generation.get() == g && !ready) giveUp(g, "the proxy did not answer the hello");
                        }, readyTimeoutMs, TimeUnit.MILLISECONDS);
                        socket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                        if (generation.get() != g) return null;
                        lastActivity = System.currentTimeMillis();
                        partial.append(data);
                        if (partial.length() > MAX_MESSAGE) {
                            partial.setLength(0);
                            giveUp(g, "the proxy sent a message larger than " + MAX_MESSAGE + " bytes");
                            return null;
                        }
                        if (last) {
                            String text = partial.toString();
                            partial.setLength(0);
                            dispatch(g, text);
                        }
                        socket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) {
                        lastActivity = System.currentTimeMillis();
                        if (pingSentAt > 0) rttMs = lastActivity - pingSentAt;
                        socket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) {
                        lastActivity = System.currentTimeMillis();
                        socket.sendPong(message);
                        socket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket socket, int statusCode, String reason) {
                        giveUp(g, "closed by the proxy (" + statusCode + (reason == null || reason.isBlank() ? "" : ", " + reason) + ")");
                        return null;
                    }

                    @Override
                    public void onError(WebSocket socket, Throwable error) {
                        giveUp(g, String.valueOf(error.getMessage()));
                    }
                })
                .exceptionally(e -> {
                    Throwable c = e.getCause() == null ? e : e.getCause();
                    String m = String.valueOf(c.getMessage());
                    if (c instanceof WebSocketHandshakeException h) {
                        int status = h.getResponse().statusCode();
                        m = switch (status) {
                            case 401 -> "the proxy refused the key (tunnel.key)";
                            case 404 -> "the proxy does not know this address (wrong tunnel.url, or this IP is not allowed)";
                            case 429 -> "too many attempts, the proxy is rate limiting";
                            default -> "the proxy answered HTTP " + status;
                        };
                    }
                    giveUp(g, m);
                    return null;
                });
    }

    /**
     * Ends connection {@code g} (once) and schedules the next attempt. The backoff only resets after a connection stayed
     * up for {@code stableMs} (see {@link #handle}), so a proxy that accepts and closes at once, or two servers fighting
     * over one key, cannot cause a reconnect storm.
     */
    private void giveUp(int g, String reason) {
        if (!generation.compareAndSet(g, g + 1)) return;
        WebSocket w = ws;
        ws = null;
        if (w != null) w.abort();
        unsubscribe.run();
        unsubscribe = () -> {
        };
        channels.closeAll();
        ready = false;
        if (stopped) return;
        lastError = reason;
        lastErrorMs = System.currentTimeMillis();
        int tries = attempt.getAndIncrement();
        long delay = Math.min(maxDelayMs, minDelayMs << Math.min(tries, 6));
        delay = delay / 2 + (long) (Math.random() * delay / 2);
        if (tries <= 1 || tries % 10 == 0) {
            warn.accept("Tunnel to " + url.getHost() + " is down: " + reason + ". Retrying" + (tries > 1 ? " (attempt " + tries + ")" : "") + ".");
        }
        try {
            timer.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // shutting down
        }
    }

    /** Pings the proxy and replaces a connection it has not answered for {@code deadMs}. */
    private void watchdog() {
        WebSocket w = ws;
        int g = generation.get();
        if (w == null) return;
        if (System.currentTimeMillis() - lastActivity > deadMs) {
            giveUp(g, "the proxy stopped answering");
            return;
        }
        try {
            pingSentAt = System.currentTimeMillis();
            w.sendPing(ByteBuffer.allocate(0));
        } catch (RuntimeException ignored) {
            // a send was still pending; the next round tries again
        }
    }

    /** Queues a frame; the JDK allows one outstanding send per socket. Completes when it is sent; null without a connection. */
    private synchronized CompletableFuture<?> send(Map<String, Object> message) {
        WebSocket w = ws;
        if (w == null) return null;
        String text = Json.write(message);
        sendChain = sendChain.handle((v, e) -> null).thenCompose(v -> w.sendText(text, true));
        return sendChain;
    }

    /** Parses on the socket's own thread, so frames keep their order; slow work (REST requests) then runs in parallel. */
    private void dispatch(int g, String text) {
        Map<String, Object> m;
        try {
            m = Json.parseObject(text);
        } catch (RuntimeException e) {
            return;
        }
        Object t = m.get("t");
        boolean channel = "open".equals(t) || "d".equals(t) || "x".equals(t);
        (channel ? ordered : workers).execute(() -> handle(g, m));
    }

    private void handle(int g, Map<String, Object> m) {
        if (generation.get() != g) return;
        Object t = m.get("t");
        if ("ready".equals(t)) {
            ready = true;
            connectedSince = System.currentTimeMillis();
            timer.schedule(() -> {
                if (generation.get() == g && ready) attempt.set(0);
            }, stableMs, TimeUnit.MILLISECONDS);
            onReady(m.get("since") instanceof String s ? s : null);
        } else if ("req".equals(t)) {
            Map<String, Object> res = requests.answer(m, num(m.get("id")));
            if (res != null) send(res);
        } else if ("open".equals(t)) {
            channels.open(num(m.get("c")));
        } else if ("d".equals(t) && m.get("b") instanceof String b) {
            try {
                channels.write(num(m.get("c")), Base64.getDecoder().decode(b));
            } catch (IllegalArgumentException e) {
                channels.close(num(m.get("c")), true);
            }
        } else if ("x".equals(t)) {
            channels.close(num(m.get("c")), false);
        }
    }

    /** The proxy is ready: stream device events, replaying what it missed since {@code since}. */
    private void onReady(String since) {
        unsubscribe.run();
        unsubscribe = bus.subscribe(since, this::event, () -> send(Map.of("t", "reset")));
        info.accept("Tunnel connected to " + url.getHost() + (attempt.get() > 0 ? " (after " + attempt.get() + " failed attempt(s))." : "."));
    }

    private void event(DeviceEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "ev");
        m.put("id", e.id());
        m.put("e", e.toJson());
        send(m);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : -1;
    }
}
