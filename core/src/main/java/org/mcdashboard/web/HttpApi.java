/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.mcdashboard.auth.ClientAssertions;
import org.mcdashboard.auth.TokenService;
import org.mcdashboard.events.EventStream;
import org.mcdashboard.util.Threads;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;

import static org.mcdashboard.web.Exchanges.bearer;
import static org.mcdashboard.web.Exchanges.error;
import static org.mcdashboard.web.Exchanges.respond;

/** REST API on the JDK's built-in HTTP server. All routes live under {@code /v1}. */
public final class HttpApi implements HttpHandler {
    /** The permission that enables developer mode for one player. */
    public static final String DEVELOPER_PERMISSION = "mcdashboard.developer";
    /** Sent by clients on requests made for notifications and home-screen widgets rather than for a screen the player is looking at. */
    public static final String PURPOSE_HEADER = "X-MCDashboard-Purpose";
    private static final String PLACEHOLDER = "/v1/placeholders/";
    private static final String HISTORY = "/v1/history/";
    private static final String DASHBOARD = "/v1/dashboards/";

    private final ApiContext ctx;
    private final ClientPolicy clients;
    private final AuthRoutes auth;
    private final PlaceholderRoutes placeholders;
    private final DashboardRoutes dashboards;
    private HttpServer server;
    private ExecutorService executor;
    private EventStream events;
    private byte[] eventsToken = new byte[0];
    private String tlsFingerprint;
    private boolean tls;
    private ClientAssertions assertions;
    private boolean background = true;
    private org.mcdashboard.stats.PluginStats stats;

    /** An API that accepts only the official app's User-Agent, with the default limits. */
    public HttpApi(ApiContext ctx) {
        this(ctx, ClientPolicy.defaults(Clock.systemUTC()));
    }

    public HttpApi(ApiContext ctx, ClientPolicy clients) {
        this.ctx = ctx;
        this.clients = clients;
        this.auth = new AuthRoutes(ctx);
        this.placeholders = new PlaceholderRoutes(ctx);
        this.dashboards = new DashboardRoutes(ctx);
    }

    /** Counts the requests this API serves and the ones it refuses for exceeding a rate limit. */
    public HttpApi stats(org.mcdashboard.stats.PluginStats stats) {
        this.stats = stats;
        return this;
    }

    private void limited() {
        if (stats != null) stats.rateLimited();
    }

    /** Starts listening; pass an {@link SSLContext} to serve HTTPS directly. Port 0 picks a free port. */
    public void start(String host, int port, SSLContext ssl) throws IOException {
        InetSocketAddress addr = host == null || host.isBlank() ? new InetSocketAddress(port) : new InetSocketAddress(host, port);
        if (ssl != null) {
            HttpsServer https = HttpsServer.create(addr, 0);
            https.setHttpsConfigurator(new HttpsConfigurator(ssl));
            server = https;
        } else {
            server = HttpServer.create(addr, 0);
        }
        executor = Executors.newFixedThreadPool(16, Threads.daemon("MCDashboard-http"));
        server.setExecutor(executor);
        server.createContext("/v1", this);
        server.start();
    }

    /** Enables {@code GET /v1/events} for callers presenting {@code token}; without it the endpoint does not exist. */
    public void events(EventStream stream, String token) {
        this.events = stream;
        this.eventsToken = token.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * This server terminates TLS itself, for the API and the live feed ({@code websocketTls} in {@code /v1/info}).
     * {@code selfSignedFingerprint} is published as {@code tlsFingerprint} when the certificate is the plugin's own, else null.
     */
    public void tls(String selfSignedFingerprint) {
        this.tls = true;
        this.tlsFingerprint = selfSignedFingerprint;
    }

    /** Requires every request (but {@code /v1/info}) to carry a valid client assertion. */
    public void verifyClients(ClientAssertions verifier) {
        this.assertions = verifier;
    }

    /** Whether clients may poll in the background (notifications, home-screen widgets); published as the {@code background} feature. */
    public void background(boolean enabled) {
        this.background = enabled;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    /** Validates a bearer access token and that its device was not revoked. */
    public static Optional<TokenService.Claims> authenticate(ApiContext ctx, String token) {
        return ctx.tokens().verify(token, TokenService.Type.ACCESS).filter(c -> ctx.devices().get(c.deviceId()).isPresent());
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // A local, not an exchange attribute: the JDK server shares attributes between all exchanges of a context.
        boolean keepOpen = false;
        if (stats != null) stats.apiRequest();
        try {
            Exchanges.cors(ex);
            String method = ex.getRequestMethod();
            if (method.equals("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            String path = ex.getRequestURI().getPath();
            boolean info = path.equals("/v1/info") && method.equals("GET");
            if (ctx.discoveryOnly() && !info && !ctx.trustedProxies().contains(Exchanges.peer(ex))) {
                error(ex, 404, "not found");
            } else if (info) {
                // Open to every client, so the app can read minClientVersion before it is refused.
                ex.getResponseHeaders().set("Cache-Control", "public, max-age=60");
                respond(ex, 200, info());
            } else if (path.equals("/v1/events") && method.equals("GET")) {
                if (events == null) error(ex, 404, "not found");
                else keepOpen = eventStream(ex);
            } else {
                Optional<ClientPolicy.Client> client = client(ex);
                if (client.isEmpty()) return;
                if (assertions != null && assertions.verify(ex.getRequestHeaders().getFirst(ClientAssertions.HEADER),
                        ex.getRequestHeaders().getFirst("Host")).isEmpty()) {
                    ex.getResponseHeaders().set("WWW-Authenticate", "MCDashboard-Attestation");
                    error(ex, 403, "client verification required");
                    return;
                }
                if (path.equals("/v1/auth") && method.equals("POST")) auth.pair(ex, client.get());
                else if (path.equals("/v1/auth/refresh") && method.equals("POST")) auth.refresh(ex);
                else authenticated(ex, path, method);
            }
        } catch (BadRequest e) {
            error(ex, 400, e.getMessage());
        } catch (RuntimeException e) {
            error(ex, 500, "internal error");
        } finally {
            if (!keepOpen) ex.close();
        }
    }

    /** Answers 403 (not an allowed client) or 426 (an official app below the minimum version) itself and returns empty then. */
    private Optional<ClientPolicy.Client> client(HttpExchange ex) throws IOException {
        Optional<ClientPolicy.Client> client = clients.identify(ex.getRequestHeaders().getFirst("User-Agent"));
        if (client.isEmpty()) {
            error(ex, 403, "unsupported client; use the MCDashboard app");
        } else if (clients.tooOld(client.get())) {
            respond(ex, 426, Map.of("error", "client too old; update the MCDashboard app", "minVersion", clients.minVersion()));
            return Optional.empty();
        }
        return client;
    }

    /** Server-sent device events for a service (the proxy), authenticated by {@code events.token} instead of a device. */
    private boolean eventStream(HttpExchange ex) throws IOException {
        if (!ctx.authLimiter().allow(Exchanges.remote(ex, ctx.trustedProxies()))) {
            limited();
            error(ex, 429, "too many attempts");
            return false;
        }
        String token = bearer(ex);
        byte[] given = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, eventsToken)) {
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            error(ex, 401, "invalid events token");
            return false;
        }
        if (events.clients() >= EventStream.MAX_CLIENTS) {
            error(ex, 503, "too many event streams");
            return false;
        }
        Headers headers = ex.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        return events.attach(ex.getResponseBody(), ex.getRequestHeaders().getFirst("Last-Event-ID"), ex::close);
    }

    private void authenticated(HttpExchange ex, String path, String method) throws IOException {
        String token = bearer(ex);
        Optional<TokenService.Claims> who = token == null ? Optional.empty() : authenticate(ctx, token);
        if (ctx.requireAuthentication() && who.isEmpty()) {
            error(ex, 401, "authentication required");
            return;
        }
        // Keyed by the paired device when there is one, so a client that changes its User-Agent or address gains nothing.
        boolean allowed = who.isPresent() ? clients.allowDevice(who.get().deviceId())
                : clients.allowAddress(Exchanges.remote(ex, ctx.trustedProxies()));
        if (!allowed) {
            ex.getResponseHeaders().set("Retry-After", "60");
            limited();
            error(ex, 429, "too many requests");
            return;
        }
        boolean isBackground = "background".equalsIgnoreCase(ex.getRequestHeaders().getFirst(PURPOSE_HEADER));
        if (isBackground && !background) {
            error(ex, 403, "background updates are disabled on this server");
            return;
        }
        if (isBackground && !clients.allowBackground(who.isPresent() ? who.get().deviceId() : Exchanges.remote(ex, ctx.trustedProxies()))) {
            ex.getResponseHeaders().set("Retry-After", "60");
            limited();
            error(ex, 429, "too many background requests");
            return;
        }
        String player = who.map(TokenService.Claims::player).orElse(null);
        who.ifPresent(c -> ctx.devices().touch(c.deviceId()));
        boolean get = method.equals("GET");
        if (!ctx.polling() && get && (path.equals("/v1/values") || (path.startsWith(PLACEHOLDER) && path.length() > PLACEHOLDER.length()))) {
            error(ex, 403, "polling is disabled on this server, use the live feed");
        } else if (get && path.equals("/v1/placeholders")) {
            placeholders.list(ex, player);
        } else if (get && path.startsWith(PLACEHOLDER)) {
            placeholders.value(ex, path.substring(PLACEHOLDER.length()), player);
        } else if (get && path.equals("/v1/values")) {
            placeholders.values(ex, player);
        } else if (get && path.startsWith(HISTORY)) {
            placeholders.history(ex, path.substring(HISTORY.length()), player);
        } else if (get && path.equals("/v1/dashboards")) {
            dashboards.list(ex, player);
        } else if (method.equals("POST") && path.equals("/v1/dashboards")) {
            dashboards.upload(ex, who);
        } else if (get && path.startsWith(DASHBOARD)) {
            dashboards.one(ex, path.substring(DASHBOARD.length()), player);
        } else if (get && path.equals("/v1/dashboard")) {
            dashboards.first(ex, player);
        } else if (method.equals("DELETE") && path.equals("/v1/devices/self")) {
            if (who.isEmpty()) {
                error(ex, 401, "authentication required");
                return;
            }
            ctx.devices().remove(who.get().deviceId());
            respond(ex, 200, Map.of("revoked", true));
        } else {
            error(ex, 404, "not found");
        }
    }

    private Map<String, Object> info() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", ctx.serverName());
        o.put("plugin", "MCDashboard");
        o.put("version", ctx.pluginVersion());
        o.put("protocol", ApiContext.PROTOCOL);
        List<String> features = new ArrayList<>(ApiContext.FEATURES);
        if (tls) features.add("tls");
        if (background) features.add("background");
        if (assertions != null) features.add("client-verification");
        o.put("features", features);
        o.put("authRequired", ctx.requireAuthentication());
        if (!ctx.polling()) o.put("polling", false);
        o.put("defaultLanguage", ctx.defaultLanguage());
        o.put("languages", ctx.languages());
        if (ctx.websocketPort() > 0 && !ctx.discoveryOnly()) o.put("websocketPort", ctx.websocketPort());
        if (!ctx.endpoints().isEmpty()) o.put("endpoints", ctx.endpoints());
        ServerStatus st = ctx.status().get();
        o.put("motd", st.motd());
        o.put("online", st.online());
        o.put("maxPlayers", st.max());
        o.put("gameVersion", st.gameVersion());
        if (st.gamePort() > 0) o.put("gamePort", st.gamePort());
        if (st.icon() != null) o.put("icon", st.icon());
        if (tls && ctx.websocketPort() > 0 && !ctx.discoveryOnly()) o.put("websocketTls", true);
        if (tlsFingerprint != null) o.put("tlsFingerprint", tlsFingerprint);
        if (ctx.licenseId() != null) o.put("licenseId", ctx.licenseId());
        if (clients.minVersion() != null) o.put("minClientVersion", clients.minVersion());
        return o;
    }
}
