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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
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
    /** Header on authenticated requests in which the app says what it runs under: {@code server}, {@code app} or {@code free}. Informational only. */
    public static final String ENTITLEMENT_HEADER = "X-MCDashboard-Entitlement";
    /**
     * The player name an unpaired client types when the owner enabled {@code security.allow-player-names}. The server
     * resolves it to a UUID; a valid bearer token always wins and this header is then ignored.
     */
    public static final String PLAYER_HEADER = "X-MCDashboard-Player";
    /** A Minecraft name is at most 16 characters; some platforms allow a little more, so leave room without accepting a paragraph. */
    static final int MAX_PLAYER_NAME = 32;
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
    /** The features the reverse-tunnel proxy can carry; null when no tunnel uses this API (or it is the public listener). */
    private volatile Supplier<Set<String>> proxyFeatures;

    /**
     * An API that accepts only the official app's User-Agent, with the default limits.
     *
     * @param ctx The shared API context.
     */
    public HttpApi(ApiContext ctx) {
        this(ctx, ClientPolicy.defaults(Clock.systemUTC()));
    }

    /**
     * An API that uses the given client policy.
     *
     * @param ctx     The shared API context.
     * @param clients The client policy that decides who may connect and how often.
     */
    public HttpApi(ApiContext ctx, ClientPolicy clients) {
        this.ctx = ctx;
        this.clients = clients;
        this.auth = new AuthRoutes(ctx);
        this.placeholders = new PlaceholderRoutes(ctx);
        this.dashboards = new DashboardRoutes(ctx);
    }

    /**
     * Counts the requests this API serves and the ones it refuses for exceeding a rate limit.
     *
     * @param stats The plugin statistics to count in.
     * @return This API, for chaining.
     */
    public HttpApi stats(org.mcdashboard.stats.PluginStats stats) {
        this.stats = stats;
        return this;
    }

    private void limited() {
        if (stats != null) stats.rateLimited();
    }

    /**
     * Starts listening; pass an {@link SSLContext} to serve HTTPS directly. Port 0 picks a free port.
     *
     * @param host The address to bind, or null or blank for all interfaces.
     * @param port The port to listen on, or 0 for a free one.
     * @param ssl  The TLS context to serve HTTPS, or null for plain HTTP.
     * @throws java.io.IOException When the server cannot be created or bound.
     */
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

    /**
     * Enables {@code GET /v1/events} for callers presenting {@code token}; without it the endpoint does not exist.
     *
     * @param stream The event stream to attach listeners to.
     * @param token  The token clients must present to read the stream.
     */
    public void events(EventStream stream, String token) {
        this.events = stream;
        this.eventsToken = token.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * This server terminates TLS itself, for the API and the live feed ({@code websocketTls} in {@code /v1/info}).
     * {@code selfSignedFingerprint} is published as {@code tlsFingerprint} when the certificate is the plugin's own, else null.
     *
     * @param selfSignedFingerprint The fingerprint of the plugin's own certificate, or null.
     */
    public void tls(String selfSignedFingerprint) {
        this.tls = true;
        this.tlsFingerprint = selfSignedFingerprint;
    }

    /**
     * Requires every request (but {@code /v1/info}) to carry a valid client assertion.
     *
     * @param verifier The assertion verifier, or null to stop requiring assertions.
     */
    public void verifyClients(ClientAssertions verifier) {
        this.assertions = verifier;
    }

    /**
     * Whether clients may poll in the background (notifications, home-screen widgets); published as the {@code background} feature.
     *
     * @param enabled Whether background polling is allowed.
     */
    public void background(boolean enabled) {
        this.background = enabled;
    }

    /**
     * The features the proxy that reaches this API over the reverse tunnel reported it can carry. Until it reports them
     * (an older proxy) the carried ones stay hidden; only plugin-local features such as {@code tls} and {@code background}
     * are published. See {@link ProxyLink#CARRIED}.
     *
     * @param features The proxy's features, read when a request arrives through the tunnel.
     */
    public void proxyFeatures(Supplier<Set<String>> features) {
        this.proxyFeatures = features;
    }

    /**
     * The port the API is listening on.
     *
     * @return The bound port.
     */
    public int port() {
        return server.getAddress().getPort();
    }

    /** Stops the server and its worker threads. */
    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    /**
     * Validates a bearer access token and that its device was not revoked.
     *
     * @param ctx   The context whose tokens and devices are checked.
     * @param token The bearer token to validate.
     * @return The claims when the token is valid and its device still paired, or empty otherwise.
     */
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
                respond(ex, 200, info(ex));
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
        if (token != null && who.isEmpty()) {
            // An expired or revoked token is not an anonymous call: answering with public data would leave the app showing
            // the public view without ever refreshing. RFC 6750 invalid_token tells it to refresh (or pair again).
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\"");
            error(ex, 401, "invalid or expired token");
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
        if (player == null && ctx.allowPlayerNames()) {
            String name = playerName(ex);
            if (name != null) {
                String uuid = ctx.playerUuid() == null ? null : ctx.playerUuid().apply(name);
                if (uuid == null) {
                    error(ex, 403, "unknown player");
                    return;
                }
                player = uuid;
            }
        }
        if (ctx.requireAuthentication() && player == null) {
            error(ex, 401, "authentication required");
            return;
        }
        who.ifPresent(c -> {
            ctx.devices().touch(c.deviceId());
            String entitlement = ex.getRequestHeaders().getFirst(ENTITLEMENT_HEADER);
            if (entitlement != null) ctx.devices().entitle(c.deviceId(), entitlement);
        });
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

    /** The player name a client typed, or null when it sent none; refused when it could never be a name. */
    private String playerName(HttpExchange ex) {
        String raw = ex.getRequestHeaders().getFirst(PLAYER_HEADER);
        if (raw == null || raw.isBlank()) return null;
        String name = raw.trim();
        if (name.length() > MAX_PLAYER_NAME || name.chars().anyMatch(Character::isISOControl)) {
            throw new BadRequest("invalid player name");
        }
        return name;
    }

    private Map<String, Object> info(HttpExchange ex) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", ctx.serverName());
        o.put("plugin", "MCDashboard");
        o.put("version", ctx.pluginVersion());
        o.put("protocol", ApiContext.PROTOCOL);
        List<String> features = new ArrayList<>(ApiContext.FEATURES);
        if (tls) features.add("tls");
        if (background) features.add("background");
        if (assertions != null) features.add("client-verification");
        boolean allowPlayerNames = ctx.allowPlayerNames();
        if (allowPlayerNames) features.add("player-names");
        // Through a proxy, only what that proxy can carry is usable: an older proxy reports nothing and hides the rest.
        String via = ex.getRequestHeaders().getFirst(ProxyLink.HEADER);
        Set<String> carried = null;
        if (ProxyLink.TUNNEL.equalsIgnoreCase(via)) {
            Supplier<Set<String>> supplier = proxyFeatures;
            carried = supplier == null ? Set.of() : supplier.get();
        } else if (ProxyLink.PROXY.equalsIgnoreCase(via)) {
            carried = ProxyLink.parse(ex.getRequestHeaders().getFirst(ProxyLink.FEATURES_HEADER));
        }
        if (carried != null) {
            ProxyLink.Advertised advertised = ProxyLink.advertised(features, carried, allowPlayerNames);
            features = advertised.features();
            allowPlayerNames = advertised.allowPlayerNames();
        }
        o.put("features", features);
        o.put("authRequired", ctx.requireAuthentication());
        o.put("allowPlayerNames", allowPlayerNames);
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
        if (ctx.licenseHash() != null) o.put("licenseHash", ctx.licenseHash());
        if (clients.minVersion() != null) o.put("minClientVersion", clients.minVersion());
        if (ctx.maxDashboards() > 0 || ctx.maxTiles() > 0) {
            Map<String, Object> limits = new LinkedHashMap<>();
            if (ctx.maxDashboards() > 0) limits.put("maxDashboards", ctx.maxDashboards());
            if (ctx.maxTiles() > 0) limits.put("maxTiles", ctx.maxTiles());
            o.put("limits", limits);
        }
        return o;
    }
}
