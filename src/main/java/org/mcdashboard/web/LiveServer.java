package org.mcdashboard.web;

import org.mcdashboard.auth.TokenService;
import org.mcdashboard.json.Json;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.placeholder.PlaceholderService;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
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
    private static final long AUTH_TIMEOUT_MS = 10_000;

    private final ApiContext ctx;
    private final ClientPolicy clients;
    private final int maxConnections;
    private final AtomicInteger connections = new AtomicInteger();
    private ServerSocket socket;
    private ExecutorService acceptors;
    private ScheduledExecutorService ticker;

    /** A live feed that accepts only the official app's User-Agent. */
    public LiveServer(ApiContext ctx, int maxConnections) {
        this(ctx, maxConnections, ClientPolicy.defaults(java.time.Clock.systemUTC()));
    }

    public LiveServer(ApiContext ctx, int maxConnections, ClientPolicy clients) {
        this.ctx = ctx;
        this.maxConnections = maxConnections;
        this.clients = clients;
    }

    public void start(String host, int port) throws IOException {
        socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(host == null || host.isBlank() ? new InetSocketAddress(port) : new InetSocketAddress(host, port));
        acceptors = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "MCDashboard-live");
            t.setDaemon(true);
            return t;
        });
        ticker = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "MCDashboard-live-ticker");
            t.setDaemon(true);
            return t;
        });
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
        private final OutputStream out;
        private final Map<String, Long> lastSent = new HashMap<>();
        private final Map<String, String> lastValue = new HashMap<>();
        private List<String> subscribed = List.of();
        private String player;
        private boolean authenticated;
        private ScheduledFuture<?> task;

        Connection(Socket sock) throws IOException {
            this.sock = sock;
            this.out = sock.getOutputStream();
            sock.setSoTimeout(60_000);
            sock.setTcpNoDelay(true);
        }

        void run() throws IOException {
            if (!handshake()) return;
            task = ticker.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
            long started = System.currentTimeMillis();
            while (true) {
                WebSocketFrames.Frame f;
                try {
                    f = WebSocketFrames.read(sock.getInputStream(), MAX_FRAME);
                } catch (SocketTimeoutException e) {
                    if (ctx.requireAuthentication() && !authenticated && System.currentTimeMillis() - started > AUTH_TIMEOUT_MS) return;
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

        private boolean handshake() throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
            String request = in.readLine();
            if (request == null || !request.startsWith("GET /v1/live")) {
                reject("404 Not Found");
                return false;
            }
            Map<String, String> headers = new HashMap<>();
            String line;
            int count = 0;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (++count > 50) return false;
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
            }
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
                Optional<TokenService.Claims> c = HttpApi.authenticate(ctx, String.valueOf(m.get("token")));
                if (c.isEmpty()) {
                    sendJson(Map.of("type", "error", "error", "authentication failed"));
                    return false;
                }
                player = c.get().player();
                authenticated = true;
                sendJson(Map.of("type", "authenticated"));
            } else if ("subscribe".equals(type) && m.get("ids") instanceof List<?> ids) {
                if (ctx.requireAuthentication() && !authenticated) {
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
                if (last != null && now - last < Math.max(1000, ctx.placeholders().intervalMs(d))) continue;
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
