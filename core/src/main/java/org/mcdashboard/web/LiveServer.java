/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import org.mcdashboard.auth.ClientAssertions;
import org.mcdashboard.auth.TokenService;
import org.mcdashboard.json.Json;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.placeholder.PlaceholderService;
import org.mcdashboard.util.Threads;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live value feed over a hand-written RFC 6455 WebSocket (the JDK has no server implementation
 * and the plugin takes no dependencies). Protocol, JSON text frames:
 * <pre>
 *  client: {"type":"auth","token":"&lt;access token&gt;"}   (optional unless the server requires auth)
 *  client: {"type":"subscribe","ids":["tps","online"]}
 *  server: {"type":"values","values":[{"n":"tps","t":1.7E9,"v":19.9}]}   (SenML records, RFC 8428)
 * </pre>
 * Values are pushed when they change, never faster than the placeholder's min-interval.
 */
public final class LiveServer {
    private static final int MAX_FRAME = 16 * 1024;
    private static final int MAX_SUBSCRIPTIONS = 100;
    private static final int MAX_HEAD = 8 * 1024;
    private static final int MAX_HEADERS = 50;
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final int AUTH_TIMEOUT_MS = 10_000;
    private static final int IDLE_TIMEOUT_MS = 60_000;

    private final ApiContext ctx;
    private final ClientPolicy clients;
    private final int maxConnections;
    private volatile ClientAssertions assertions;
    private final AtomicInteger connections = new AtomicInteger();
    private ServerSocket socket;
    private ExecutorService acceptors;
    private ScheduledExecutorService ticker;

    /** A live feed that accepts only the official app's User-Agent. */
    public LiveServer(ApiContext ctx, int maxConnections) {
        this(ctx, maxConnections, ClientPolicy.defaults(Clock.systemUTC()));
    }

    public LiveServer(ApiContext ctx, int maxConnections, ClientPolicy clients) {
        this.ctx = ctx;
        this.maxConnections = maxConnections;
        this.clients = clients;
    }

    /** Requires every connection to present a valid client assertion in its {@code auth} message. */
    public void verifyClients(ClientAssertions verifier) {
        this.assertions = verifier;
    }

    public void start(String host, int port) throws IOException {
        start(host, port, null);
    }

    /** Serves TLS ({@code wss://}) when {@code ssl} is given. */
    public void start(String host, int port, javax.net.ssl.SSLContext ssl) throws IOException {
        socket = ssl == null ? new ServerSocket() : ssl.getServerSocketFactory().createServerSocket();
        socket.setReuseAddress(true);
        socket.bind(host == null || host.isBlank() ? new InetSocketAddress(port) : new InetSocketAddress(host, port));
        acceptors = Executors.newCachedThreadPool(Threads.daemon("MCDashboard-live"));
        ticker = Executors.newScheduledThreadPool(1, Threads.daemon("MCDashboard-live-ticker"));
        acceptors.execute(this::acceptLoop);
    }

    public int port() {
        return socket.getLocalPort();
    }

    public void stop() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // shutting down
        }
        if (acceptors != null) acceptors.shutdownNow();
        if (ticker != null) ticker.shutdownNow();
    }

    private void acceptLoop() {
        while (!socket.isClosed()) {
            try {
                Socket s = socket.accept();
                if (connections.incrementAndGet() > maxConnections) {
                    connections.decrementAndGet();
                    s.close();
                    continue;
                }
                acceptors.execute(() -> {
                    try (Connection c = new Connection(s)) {
                        c.run();
                    } catch (IOException ignored) {
                        // client went away or violated the protocol
                    } finally {
                        connections.decrementAndGet();
                    }
                });
            } catch (IOException e) {
                if (socket.isClosed()) return;
            }
        }
    }

    private final class Connection implements AutoCloseable {
        private final Socket sock;
        private final InputStream in;
        private final OutputStream out;
        private final Map<String, Long> lastSent = new HashMap<>();
        private final Map<String, String> lastValue = new HashMap<>();
        private List<String> subscribed = List.of();
        private volatile String player;
        private volatile String deviceId;
        private volatile String hostHeader;
        private volatile boolean verified;
        private ScheduledFuture<?> task;

        Connection(Socket sock) throws IOException {
            this.sock = sock;
            this.in = sock.getInputStream();
            this.out = sock.getOutputStream();
            sock.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            sock.setTcpNoDelay(true);
        }

        void run() throws IOException {
            if (!handshake()) return;
            boolean mustAuthenticate = ctx.requireAuthentication() || assertions != null;
            long deadline = System.currentTimeMillis() + AUTH_TIMEOUT_MS;
            sock.setSoTimeout(mustAuthenticate ? AUTH_TIMEOUT_MS : IDLE_TIMEOUT_MS);
            task = ticker.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
            while (true) {
                if (mustAuthenticate && deviceId == null && !verified && System.currentTimeMillis() > deadline) return;
                WebSocketFrames.Frame f;
                try {
                    f = WebSocketFrames.read(in, MAX_FRAME);
                } catch (SocketTimeoutException e) {
                    if (mustAuthenticate && deviceId == null && !verified) return;
                    send(WebSocketFrames.PING, new byte[0]);
                    continue;
                } catch (EOFException e) {
                    return;
                }
                switch (f.opcode()) {
                    case WebSocketFrames.PING -> send(WebSocketFrames.PONG, f.payload());
                    case WebSocketFrames.PONG -> {
                    }
                    case WebSocketFrames.CLOSE -> {
                        send(WebSocketFrames.CLOSE, f.payload());
                        return;
                    }
                    default -> {
                        if (!message(f.text())) return;
                    }
                }
            }
        }

        /** Reads the upgrade request from the raw stream, bounded in size and time, so a slow client cannot hold a slot. */
        private boolean handshake() throws IOException {
            List<String> lines = head();
            if (lines == null) return false;
            if (lines.isEmpty() || !lines.get(0).startsWith("GET /v1/live")) {
                reject("404 Not Found");
                return false;
            }
            Map<String, String> headers = new HashMap<>();
            for (String line : lines.subList(1, lines.size())) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
            }
            hostHeader = headers.get("host");
            String key = headers.get("sec-websocket-key");
            if (key == null || !"websocket".equalsIgnoreCase(headers.get("upgrade"))) {
                reject("400 Bad Request");
                return false;
            }
            Optional<ClientPolicy.Client> client = clients.identify(headers.get("user-agent"));
            if (client.isEmpty()) {
                reject("403 Forbidden");
                return false;
            }
            if (clients.tooOld(client.get())) {
                reject("426 Upgrade Required");
                return false;
            }
            String resp = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + WebSocketFrames.acceptKey(key) + "\r\n\r\n";
            out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return true;
        }

        /** The request line and header lines, or null when the head is too large, has too many headers or is cut off. */
        private List<String> head() throws IOException {
            long deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS;
            List<String> lines = new ArrayList<>();
            StringBuilder line = new StringBuilder();
            int total = 0;
            while (true) {
                int b = in.read();
                if (b < 0 || ++total > MAX_HEAD || System.currentTimeMillis() > deadline) return null;
                if (b == '\n') {
                    int end = line.length() > 0 && line.charAt(line.length() - 1) == '\r' ? line.length() - 1 : line.length();
                    if (end == 0) return lines;
                    if (lines.size() > MAX_HEADERS) return null;
                    lines.add(line.substring(0, end));
                    line.setLength(0);
                } else {
                    line.append((char) b);
                }
            }
        }

        private void reject(String status) throws IOException {
            out.write(("HTTP/1.1 " + status + "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        }

        private boolean message(String text) throws IOException {
            Map<String, Object> m;
            try {
                m = Json.parseObject(text);
            } catch (IllegalArgumentException e) {
                sendJson(Map.of("type", "error", "error", "invalid JSON"));
                return true;
            }
            Object type = m.get("type");
            if ("auth".equals(type)) {
                ClientAssertions verifier = assertions;
                if (verifier != null) {
                    verified = verifier.verify(m.get("attestation") instanceof String a ? a : null, hostHeader).isPresent();
                    if (!verified) {
                        sendJson(Map.of("type", "error", "error", "client verification failed"));
                        return false;
                    }
                    if (m.get("token") == null && !ctx.requireAuthentication()) {
                        sendJson(Map.of("type", "authenticated"));
                        return true;
                    }
                }
                Optional<TokenService.Claims> c = HttpApi.authenticate(ctx, String.valueOf(m.get("token")));
                if (c.isEmpty()) {
                    sendJson(Map.of("type", "error", "error", "authentication failed"));
                    return false;
                }
                player = c.get().player();
                deviceId = c.get().deviceId();
                sock.setSoTimeout(IDLE_TIMEOUT_MS);
                sendJson(Map.of("type", "authenticated"));
            } else if ("subscribe".equals(type) && m.get("ids") instanceof List<?> ids) {
                if ((ctx.requireAuthentication() && deviceId == null) || (assertions != null && !verified)) {
                    sendJson(Map.of("type", "error", "error", "authentication required"));
                    return false;
                }
                List<String> accepted = new ArrayList<>();
                for (Object o : ids) {
                    if (accepted.size() >= MAX_SUBSCRIPTIONS) break;
                    Optional<PlaceholderDefinition> d = ctx.placeholders().definition(String.valueOf(o));
                    if (d.isPresent() && d.get().readableBy(player, ctx.permissions())) accepted.add(d.get().id());
                }
                synchronized (this) {
                    subscribed = accepted;
                    lastSent.clear();
                    lastValue.clear();
                }
                sendJson(Map.of("type", "subscribed", "ids", accepted));
                tick();
            } else {
                sendJson(Map.of("type", "error", "error", "unknown message"));
            }
            return true;
        }

        private void tick() {
            if (deviceId != null && ctx.devices().get(deviceId).isEmpty()) {
                close(); // revoked while connected
                return;
            }
            List<Object> due = new ArrayList<>();
            long now = System.currentTimeMillis();
            List<String> ids;
            synchronized (this) {
                ids = subscribed;
            }
            for (String id : ids) {
                PlaceholderDefinition d = ctx.placeholders().definition(id).orElse(null);
                if (d == null || !d.readableBy(player, ctx.permissions())) continue; // permissions can change while subscribed
                Long last;
                synchronized (this) {
                    last = lastSent.get(id);
                }
                if (last != null && now - last < Math.max(1000, ctx.placeholders().intervalMs(d, player))) continue;
                Optional<PlaceholderService.Value> v = ctx.placeholders().read(id, player);
                synchronized (this) {
                    lastSent.put(id, now);
                    if (v.isPresent() && !v.get().value().equals(lastValue.put(id, v.get().value()))) {
                        due.add(SenMl.record(d, v.get()));
                    }
                }
            }
            if (!due.isEmpty()) {
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("type", "values");
                msg.put("values", due);
                try {
                    sendJson(msg);
                } catch (IOException e) {
                    close();
                }
            }
        }

        private void sendJson(Object o) throws IOException {
            synchronized (out) {
                WebSocketFrames.writeText(out, Json.write(o));
            }
        }

        private void send(int opcode, byte[] payload) throws IOException {
            synchronized (out) {
                WebSocketFrames.write(out, opcode, payload);
            }
        }

        @Override
        public void close() {
            if (task != null) task.cancel(false);
            try {
                sock.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }
}
