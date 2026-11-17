package org.mcdashboard.tunnel;

import org.mcdashboard.events.DeviceEvent;
import org.mcdashboard.events.EventBus;
import org.mcdashboard.json.Json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The plugin's side of the reverse tunnel to MCDashboard-proxy: ONE outbound WebSocket carries everything. The proxy
 * sends REST requests (answered from the plugin's own API on the loopback address), opens live-feed byte streams and
 * receives device events, so the server needs no inbound port for the API. Only the JDK is used.
 * <p>
 * Security: the connection is outbound only; it is authenticated by the API key ({@code Authorization: Bearer}), the
 * key is never logged, {@code ws://} is refused unless the proxy is on a private network (or the owner opted in), and
 * the proxy can only reach {@code /v1/*} (never {@code /v1/events}) and the live port.
 */
public final class TunnelClient {
    /** Largest message either side sends (the proxy enforces the same). */
    static final int MAX_MESSAGE = 4 * 1024 * 1024;
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static final int MAX_CHANNELS = 256;
    private static final Set<String> FORWARDED = Set.of("accept", "accept-language", "authorization", "content-type",
            "if-none-match", "user-agent", "x-forwarded-for");

    private final URI url;
    private final String key;
    private final String localHost;
    private final int httpPort;
    private final int livePort;
    private final String serverName;
    private final String version;
    private final EventBus bus;
    private final Consumer<String> info;
    private final Consumer<String> warn;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ExecutorService workers = Executors.newCachedThreadPool(daemon("MCDashboard-tunnel"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemon("MCDashboard-tunnel-timer"));
    private final Map<Long, Socket> channels = new ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();
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
    private volatile int attempt;

    /**
     * @param localHost address the plugin's own HTTP API listens on (the loopback address unless {@code server.host} is set)
     * @param livePort  the live feed's port, or 0 when it is disabled
     */
    public TunnelClient(URI url, String key, String localHost, int httpPort, int livePort, String serverName, String version,
                        EventBus bus, Consumer<String> info, Consumer<String> warn) {
        this.url = url;
        this.key = key;
        this.localHost = localHost;
        this.httpPort = httpPort;
        this.livePort = livePort;
        this.serverName = serverName;
        this.version = version;
        this.bus = bus;
        this.info = info;
        this.warn = warn;
    }

    /**
     * Rejects a configuration that would send the API key in the clear or is too weak, so a mistake cannot go unnoticed.
     *
     * @throws IllegalArgumentException with the reason
     */
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
        if (scheme.equals("ws") && !allowInsecure && !isPrivate(uri.getHost())) {
            throw new IllegalArgumentException("tunnel.url uses ws:// to a public host, which would send the key in the clear; use wss:// (or set tunnel.allow-insecure)");
        }
        return uri;
    }

    static boolean isPrivate(String host) {
        return host.equals("localhost") || host.equals("::1") || host.startsWith("127.") || host.startsWith("10.")
                || host.startsWith("192.168.") || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
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
        return new Status(connected(), connectedSince, attempt, lastError, lastErrorMs, rttMs, channels.size());
    }

    public void stop() {
        stopped = true;
        generation.incrementAndGet(); // late callbacks of the closing socket are ignored
        WebSocket w = ws;
        if (w != null) w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        ws = null;
        closeAll();
        timer.shutdownNow();
        workers.shutdownNow();
    }

    /**
     * One connection attempt. Every callback carries the generation it belongs to and is ignored once that connection has
     * been given up, so a socket that reports both an error and a close (or one that is aborted by the watchdog) schedules
     * exactly one reconnect and can never leave two connections running.
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
                            socket.sendClose(1009, "too big");
                        } else if (last) {
                            String text = partial.toString();
                            partial.setLength(0);
                            workers.execute(() -> handle(g, text));
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
                    if (c instanceof java.net.http.WebSocketHandshakeException h) {
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
     * Ends connection {@code g} (once) and schedules the next attempt. The backoff only resets when the connection that
     * just ended had been up for {@code stableMs} (the reset happens in {@link #handle}): a proxy that accepts and immediately closes (or two servers fighting over
     * one key) must not turn into a reconnect storm.
     */
    private void giveUp(int g, String reason) {
        if (!generation.compareAndSet(g, g + 1)) return; // already handled, or superseded
        WebSocket w = ws;
        ws = null;
        if (w != null) w.abort();
        unsubscribe.run();
        unsubscribe = () -> {
        };
        closeAll();
        long now = System.currentTimeMillis();
        ready = false;
        if (stopped) return;
        lastError = reason;
        lastErrorMs = now;
        long delay = Math.min(maxDelayMs, minDelayMs << Math.min(attempt++, 6));
        delay = delay / 2 + (long) (Math.random() * delay / 2);
        // Say it when it breaks and then now and again, not every attempt.
        if (attempt <= 1 || attempt % 10 == 0) {
            warn.accept("Tunnel to " + url.getHost() + " is down: " + reason + ". Retrying" + (attempt > 1 ? " (attempt " + attempt + ")" : "") + ".");
        }
        try {
            timer.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ignored) {
            // shutting down
        }
    }

    /** Health check: ping the proxy, and replace a connection it has not answered for {@code deadMs}. */
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

    /** Sends are chained: the JDK allows only one outstanding send per socket. */
    private synchronized boolean send(Map<String, Object> message) {
        WebSocket w = ws;
        if (w == null) return false;
        String text = Json.write(message);
        sendChain = sendChain.handle((v, e) -> null).thenCompose(v -> w.sendText(text, true));
        return true;
    }

    private void handle(int g, String text) {
        if (generation.get() != g) return;
        Map<String, Object> m;
        try {
            m = Json.parseObject(text);
        } catch (RuntimeException e) {
            return;
        }
        Object t = m.get("t");
        if ("ready".equals(t)) {
            ready = true;
            connectedSince = System.currentTimeMillis();
            // Only a connection that lasts resets the backoff (see giveUp).
            timer.schedule(() -> {
                if (generation.get() == g && ready) attempt = 0;
            }, stableMs, TimeUnit.MILLISECONDS);
            onReady(m.get("since") instanceof String s ? s : null);
        } else if ("req".equals(t)) {
            request(m);
        } else if ("open".equals(t)) {
            openChannel(num(m.get("c")));
        } else if ("d".equals(t)) {
            Socket s = channels.get(num(m.get("c")));
            if (s != null && m.get("b") instanceof String b) write(num(m.get("c")), s, Base64.getDecoder().decode(b));
        } else if ("x".equals(t)) {
            close(num(m.get("c")), false);
        }
    }

    /** The proxy is ready: stream device events, replaying what it missed since {@code since}. */
    private void onReady(String since) {
        unsubscribe.run();
        unsubscribe = bus.subscribe(since, this::event, () -> send(Map.of("t", "reset")));
        info.accept("Tunnel connected to " + url.getHost() + (attempt > 0 ? " (after " + attempt + " failed attempt(s))." : "."));
    }

    private void event(DeviceEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("t", "ev");
        m.put("id", e.id());
        m.put("e", e.toJson());
        send(m);
    }

    /** Answers a REST request from the proxy with the plugin's own API; only {@code /v1/*} except {@code /v1/events}. */
    private void request(Map<String, Object> m) {
        long id = num(m.get("id"));
        String method = String.valueOf(m.get("m"));
        String path = String.valueOf(m.get("p"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("t", "res");
        out.put("id", id);
        if (!path.startsWith("/v1/") || path.startsWith("/v1/events") || !Set.of("GET", "HEAD", "POST", "DELETE").contains(method)) {
            out.put("s", 404);
            out.put("h", Map.of("content-type", "application/json"));
            out.put("b", Base64.getEncoder().encodeToString("{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8)));
            send(out);
            return;
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://" + hostForUri(localHost) + ":" + httpPort + path))
                    .timeout(Duration.ofSeconds(8));
            if (m.get("h") instanceof Map<?, ?> headers) {
                for (Map.Entry<?, ?> h : headers.entrySet()) {
                    String name = String.valueOf(h.getKey()).toLowerCase(Locale.ROOT);
                    if (FORWARDED.contains(name) && h.getValue() instanceof String v) b.header(name, v.replaceAll("[\\r\\n]", ""));
                }
            }
            byte[] body = m.get("b") instanceof String s ? Base64.getDecoder().decode(s) : new byte[0];
            b.method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<byte[]> r = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            Map<String, Object> headers = new LinkedHashMap<>();
            r.headers().map().forEach((k, v) -> {
                if (!v.isEmpty()) headers.put(k.toLowerCase(Locale.ROOT), v.get(0));
            });
            byte[] payload = r.body().length > MAX_BODY ? new byte[0] : r.body();
            out.put("s", r.body().length > MAX_BODY ? 502 : r.statusCode());
            out.put("h", headers);
            out.put("b", Base64.getEncoder().encodeToString(payload));
        } catch (IOException | RuntimeException e) {
            out.put("s", 502);
            out.put("h", Map.of("content-type", "application/json"));
            out.put("b", Base64.getEncoder().encodeToString("{\"error\":\"internal error\"}".getBytes(StandardCharsets.UTF_8)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        send(out);
    }

    private void openChannel(long c) {
        if (livePort <= 0 || channels.size() >= MAX_CHANNELS) {
            send(Map.of("t", "x", "c", c));
            return;
        }
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(localHost, livePort), 3000);
            channels.put(c, s);
            workers.execute(() -> pump(c, s));
        } catch (IOException e) {
            send(Map.of("t", "x", "c", c));
        }
    }

    /** Live feed to proxy: raw bytes of the plugin's own WebSocket server, so nothing is parsed or rewritten. */
    private void pump(long c, Socket s) {
        byte[] buf = new byte[16 * 1024];
        try (InputStream in = s.getInputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("t", "d");
                d.put("c", c);
                d.put("b", Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(buf, n)));
                if (!send(d)) break;
            }
        } catch (IOException ignored) {
            // the live feed went away
        } finally {
            close(c, true);
        }
    }

    private void write(long c, Socket s, byte[] data) {
        try {
            OutputStream out = s.getOutputStream();
            synchronized (s) {
                out.write(data);
                out.flush();
            }
        } catch (IOException e) {
            close(c, true);
        }
    }

    private void close(long c, boolean tell) {
        Socket s = channels.remove(c);
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // already closed
        }
        if (tell) send(Map.of("t", "x", "c", c));
    }

    private void closeAll() {
        for (Long c : channels.keySet()) close(c, false);
    }

    private static String hostForUri(String host) {
        return host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : -1;
    }

    private static java.util.concurrent.ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
