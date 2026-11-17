package org.mcdashboard.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.auth.TokenService;
import org.mcdashboard.dashboard.Dashboard;
import org.mcdashboard.dashboard.Languages;
import org.mcdashboard.dashboard.LocalizedText;
import org.mcdashboard.json.Json;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.placeholder.PlaceholderService;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;

/** REST API on the JDK's built-in HTTP server. All routes live under {@code /v1}. */
public final class HttpApi implements HttpHandler {
    /** A dashboard upload can be larger than a pairing request, but stays bounded. */
    private static final int MAX_BODY = 32 * 1024;
    /** The permission that enables developer mode for one player. */
    public static final String DEVELOPER_PERMISSION = "mcdashboard.developer";
    /** Widgets the app knows; an upload may only use these. */
    private static final Set<String> WIDGETS = Set.of("text", "image", "number", "progress", "list", "status",
            "gauge", "ring", "line", "area", "bar", "pie");
    /** Backgrounds the app draws. */
    private static final Set<String> BACKGROUNDS = Set.of("grid", "dirt", "stone", "planks", "checker", "cobble", "creeper");
    /** The id of the bundled dashboard every caller may open, even before pairing. All other dashboards need their permission. */
    private static final String PUBLIC_DASHBOARD = "player";
    private static final int MAX_ITEMS = 40;
    private static final int MAX_ITEM_PLACEHOLDERS = 12;

    private final ApiContext ctx;
    private final ClientPolicy clients;
    private HttpServer server;
    private ExecutorService executor;
    private org.mcdashboard.events.EventStream events;
    private byte[] eventsToken = new byte[0];

    /** An API that accepts only the official app's User-Agent, with the default limits. */
    public HttpApi(ApiContext ctx) {
        this(ctx, ClientPolicy.defaults(java.time.Clock.systemUTC()));
    }

    public HttpApi(ApiContext ctx, ClientPolicy clients) {
        this.ctx = ctx;
        this.clients = clients;
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
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "MCDashboard-http");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/v1", this);
        server.start();
    }

    /** Enables {@code GET /v1/events} for callers presenting {@code token}; without it the endpoint does not exist. */
    public void events(org.mcdashboard.events.EventStream stream, String token) {
        this.events = stream;
        this.eventsToken = token.getBytes(StandardCharsets.UTF_8);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        // Not an exchange attribute: the JDK server shares those between all exchanges of a context.
        boolean keepOpen = false;
        try {
            cors(ex);
            if (ex.getRequestMethod().equals("OPTIONS")) {
                ex.sendResponseHeaders(204, -1);
                return;
            }
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            // Discovery-only: this port just tells apps where the real endpoint is; the API itself is reached through the
            // tunnel or a reverse proxy, i.e. from a trusted peer.
            if (ctx.discoveryOnly() && !(path.equals("/v1/info") && method.equals("GET"))
                    && !ctx.trustedProxies().contains(ex.getRemoteAddress().getAddress().getHostAddress())) {
                error(ex, 404, "not found");
                return;
            }
            if (path.equals("/v1/info") && method.equals("GET")) {
                // Open to every client (browsers, uptime checks), so the app can read minClientVersion before it is refused.
                ex.getResponseHeaders().set("Cache-Control", "public, max-age=60");
                respond(ex, 200, info());
            } else if (path.equals("/v1/events") && method.equals("GET")) {
                // Another credential (events.token) and another caller (the proxy), not an app.
                if (events == null) error(ex, 404, "not found");
                else keepOpen = eventStream(ex);
            } else {
                Optional<ClientPolicy.Client> client = client(ex);
                if (client.isEmpty()) return;
                if (path.equals("/v1/auth") && method.equals("POST")) {
                    auth(ex, client.get());
                } else if (path.equals("/v1/auth/refresh") && method.equals("POST")) {
                    refresh(ex);
                } else {
                    authenticated(ex, path, method);
                }
            }
        } catch (BadRequest e) {
            error(ex, 400, e.getMessage());
        } catch (RuntimeException e) {
            error(ex, 500, "internal error");
        } finally {
            if (!keepOpen) ex.close(); // an event stream outlives this call
        }
    }

    /**
     * Checks the User-Agent. Answers 403 (not an allowed client) or 426 (an official app older than
     * {@code security.min-client-version}) itself and returns empty in those cases.
     */
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

    /** Server-sent stream of device events for a service (the proxy), authenticated by the configured events token. */
    private boolean eventStream(HttpExchange ex) throws IOException {
        if (!ctx.authLimiter().allow(remote(ex))) {
            error(ex, 429, "too many attempts");
            return false;
        }
        String h = ex.getRequestHeaders().getFirst("Authorization");
        byte[] given = h != null && h.startsWith("Bearer ") ? h.substring(7).trim().getBytes(StandardCharsets.UTF_8) : new byte[0];
        if (!java.security.MessageDigest.isEqual(given, eventsToken)) {
            ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            error(ex, 401, "invalid events token");
            return false;
        }
        if (events.clients() >= org.mcdashboard.events.EventStream.MAX_CLIENTS) {
            error(ex, 503, "too many event streams");
            return false;
        }
        var headers = ex.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("X-Accel-Buffering", "no"); // nginx: do not buffer the stream
        ex.sendResponseHeaders(200, 0);
        return events.attach(ex.getResponseBody(), ex.getRequestHeaders().getFirst("Last-Event-ID"), ex::close);
    }

    private void authenticated(HttpExchange ex, String path, String method) throws IOException {
        Optional<TokenService.Claims> who = principal(ex);
        if (ctx.requireAuthentication() && who.isEmpty()) {
            error(ex, 401, "authentication required");
            return;
        }
        // Keyed by the paired device when there is one, so a client that changes its User-Agent or address gains nothing.
        boolean allowed = who.isPresent() ? clients.allowDevice(who.get().deviceId()) : clients.allowAddress(remote(ex));
        if (!allowed) {
            ex.getResponseHeaders().set("Retry-After", "60");
            error(ex, 429, "too many requests");
            return;
        }
        String player = who.map(TokenService.Claims::player).orElse(null);
        who.ifPresent(c -> ctx.devices().touch(c.deviceId()));
        if (!ctx.polling() && method.equals("GET")
                && (path.equals("/v1/values") || (path.startsWith("/v1/placeholders/") && path.length() > "/v1/placeholders/".length()))) {
            error(ex, 403, "polling is disabled on this server, use the live feed");
            return;
        }
        if (path.equals("/v1/placeholders") && method.equals("GET")) {
            placeholderList(ex, player);
        } else if (path.startsWith("/v1/placeholders/") && method.equals("GET")) {
            String id = path.substring("/v1/placeholders/".length());
            Optional<PlaceholderDefinition> def = ctx.placeholders().definition(id);
            if (def.isEmpty() || !visible(def.get(), player)) {
                error(ex, def.isEmpty() ? 404 : player == null ? 401 : 403, def.isEmpty() ? "unknown placeholder" : player == null ? "authentication required" : "not allowed");
                return;
            }
            Optional<PlaceholderService.Value> v = ctx.placeholders().read(id, player);
            if (v.isEmpty()) {
                error(ex, 503, "value unavailable");
                return;
            }
            cacheable(ex, List.of(SenMl.record(def.get(), v.get())), List.of(def.get()), player != null);
        } else if (path.equals("/v1/values") && method.equals("GET")) {
            String q = ex.getRequestURI().getRawQuery();
            List<Object> records = new ArrayList<>();
            List<PlaceholderDefinition> used = new ArrayList<>();
            String idsParam = q == null ? "" : java.net.URLDecoder.decode(q.replaceFirst("^(.*&)?ids=([^&]*).*$", "$2"), StandardCharsets.UTF_8);
            if (q == null || !q.matches("^(.*&)?ids=.*")) idsParam = "";
            String[] ids = idsParam.isEmpty() ? new String[0] : idsParam.split(",");
            if (ids.length == 0 || ids.length > 100) throw new BadRequest("ids must list 1-100 placeholder ids");
            java.util.Arrays.sort(ids); // stable order => stable ETag and proxy cache key
            for (String id : ids) {
                Optional<PlaceholderDefinition> d = ctx.placeholders().definition(id);
                if (d.isEmpty() || !visible(d.get(), player)) continue;
                ctx.placeholders().read(id, player).ifPresent(val -> {
                    records.add(SenMl.record(d.get(), val));
                    used.add(d.get());
                });
            }
            cacheable(ex, records, used, player != null);
        } else if (path.startsWith("/v1/history/") && method.equals("GET")) {
            String id = path.substring("/v1/history/".length());
            Optional<PlaceholderDefinition> def = ctx.placeholders().definition(id);
            if (def.isEmpty() || !ctx.history().tracked(id) || !visible(def.get(), player)) {
                error(ex, 404, "no history for this placeholder");
                return;
            }
            long since = 0;
            String q = ex.getRequestURI().getRawQuery();
            if (q != null && q.matches("^(.*&)?since=\\d+.*")) since = Long.parseLong(q.replaceFirst("^(.*&)?since=(\\d+).*$", "$2")) * 1000;
            List<Object> records = new ArrayList<>();
            int points = org.mcdashboard.placeholder.HistoryService.MAX_POINTS;
            if (q != null && q.matches("^(.*&)?points=\\d{1,9}(&.*)?$")) {
                points = Integer.parseInt(q.replaceFirst("^(.*&)?points=(\\d+).*$", "$2"));
            }
            for (var p : ctx.history().since(id, since, points)) {
                records.add(SenMl.record(def.get(), new PlaceholderService.Value(id, p.value(), p.timeMs())));
            }
            cacheable(ex, records, List.of(def.get()), false);
        } else if (path.equals("/v1/dashboards") && method.equals("GET")) {
            List<String> preferred = Languages.preferred(ex.getRequestHeaders().getFirst("Accept-Language"));
            List<Object> list = new ArrayList<>();
            for (Dashboard d : visibleDashboards(player)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.id());
                m.put("name", d.name().resolve(preferred, ctx.defaultLanguage()));
                if (d.description() != null) m.put("description", d.description().resolve(preferred, ctx.defaultLanguage()));
                list.add(m);
            }
            ex.getResponseHeaders().set("Cache-Control", "private, max-age=10");
            varyLanguage(ex);
            boolean canUpload = player != null && ctx.developerMode().getAsBoolean()
                    && ctx.permissions().has(player, DEVELOPER_PERMISSION);
            respond(ex, 200, Map.of("dashboards", list, "canUpload", canUpload));
        } else if (path.equals("/v1/dashboards") && method.equals("POST")) {
            uploadDashboard(ex, who);
        } else if (path.startsWith("/v1/dashboards/") && method.equals("GET")) {
            String id = path.substring("/v1/dashboards/".length());
            Optional<Dashboard> d = visibleDashboards(player).stream().filter(x -> x.id().equals(id)).findFirst();
            if (d.isEmpty()) {
                error(ex, 404, "unknown dashboard");
                return;
            }
            List<String> preferred = Languages.preferred(ex.getRequestHeaders().getFirst("Accept-Language"));
            ex.getResponseHeaders().set("Cache-Control", "private, max-age=10");
            varyLanguage(ex);
            respond(ex, 200, d.get().toJson(preferred, ctx.defaultLanguage()));
        } else if (path.equals("/v1/dashboard") && method.equals("GET")) {
            // The first dashboard the caller may open: kept for apps that predate dashboard presets.
            List<Dashboard> all = visibleDashboards(player);
            if (all.isEmpty()) {
                error(ex, 404, "no dashboard available");
                return;
            }
            List<String> preferred = Languages.preferred(ex.getRequestHeaders().getFirst("Accept-Language"));
            varyLanguage(ex);
            respond(ex, 200, all.get(0).toJson(preferred, ctx.defaultLanguage()));
        } else if (path.equals("/v1/devices/self") && method.equals("DELETE")) {
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

    /**
     * {@code GET /v1/placeholders}: a page of the placeholders the caller may read. {@code ids} (max 100) returns exactly
     * those instead and ignores {@code q}, {@code group}, {@code limit} and {@code offset}.
     */
    private void placeholderList(HttpExchange ex, String player) throws IOException {
        Map<String, String> q = query(ex);
        List<Object> list = new ArrayList<>();
        int total;
        List<Object> groups = new ArrayList<>();
        if (q.containsKey("ids")) {
            String[] ids = q.get("ids").isEmpty() ? new String[0] : q.get("ids").split(",");
            if (ids.length > 100) throw new BadRequest("ids must list at most 100 placeholder ids");
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String id : ids) {
                Optional<PlaceholderDefinition> d = ctx.placeholders().definition(id.trim());
                if (d.isPresent() && visible(d.get(), player) && seen.add(d.get().id())) list.add(d.get().toJson());
            }
            total = list.size();
        } else {
            int limit = intParam(q, "limit", 50);
            int offset = intParam(q, "offset", 0);
            if (limit < 1 || limit > 100) throw new BadRequest("limit must be 1-100");
            if (offset < 0) throw new BadRequest("offset must not be negative");
            String group = q.get("group");
            PlaceholderService.Page page = ctx.placeholders().search(d -> visible(d, player), q.get("q"),
                    group == null || group.isEmpty() ? null : group, limit, offset);
            for (PlaceholderDefinition d : page.items()) list.add(d.toJson());
            for (PlaceholderService.Group g : page.groups()) groups.add(Map.of("name", g.name(), "count", g.count()));
            total = page.total();
        }
        boolean priv = ctx.requireAuthentication() || player != null;
        ex.getResponseHeaders().set("Cache-Control", (priv ? "private" : "public") + ", max-age=" + (priv ? 10 : 60));
        ex.getResponseHeaders().set("Vary", "Authorization");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("placeholders", list);
        out.put("total", total);
        out.put("groups", groups);
        respond(ex, 200, out);
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) return out;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int i = pair.indexOf('=');
            String k = java.net.URLDecoder.decode(i < 0 ? pair : pair.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(k, v);
        }
        return out;
    }

    private static int intParam(Map<String, String> q, String name, int fallback) {
        String v = q.get(name);
        if (v == null || v.isEmpty()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new BadRequest(name + " must be an integer");
        }
    }

    private boolean visible(PlaceholderDefinition d, String player) {
        return d.readableBy(player, ctx.permissions());
    }

    /**
     * The dashboards this caller may open, in order. Every dashboard needs {@code mcdashboard.dashboard.<id>}, except
     * the bundled public player dashboard. An uploaded {@code dev-*} dashboard is also offered to developers, and a
     * dashboard is only offered when at least one item is readable.
     */
    private List<Dashboard> visibleDashboards(String player) {
        List<Dashboard> out = new ArrayList<>();
        for (Dashboard d : ctx.dashboards().all()) {
            boolean allowed = (player == null && d.id().equals(PUBLIC_DASHBOARD))
                    || ctx.permissions().has(player, d.permission())
                    || (d.id().startsWith("dev-") && ctx.permissions().has(player, DEVELOPER_PERMISSION));
            if (!allowed) continue;
            Dashboard shown = filtered(d, player);
            if (!shown.items().isEmpty()) out.add(shown);
        }
        out.sort(java.util.Comparator.comparingInt(Dashboard::order).thenComparing(Dashboard::id));
        return out;
    }

    private Dashboard filtered(Dashboard d, String player) {
        List<Dashboard.Item> items = new ArrayList<>();
        for (Dashboard.Item i : d.items()) {
            // A combined tile keeps the placeholders the caller may read; without any it is left out.
            List<String> readable = new ArrayList<>();
            for (String id : i.placeholders()) {
                Optional<PlaceholderDefinition> def = ctx.placeholders().definition(id);
                if (def.isPresent() && visible(def.get(), player)) readable.add(id);
            }
            if (!readable.isEmpty()) items.add(new Dashboard.Item(readable, i.widget(), i.title(), i.column(), i.row(), i.width(), i.height()));
        }
        return new Dashboard(d.id(), d.name(), d.description(), d.order(), d.background(), items);
    }

    /**
     * {@code POST /v1/dashboards}: saves the caller's own dashboard from the app as {@code dev-<player>.yml}. The file is
     * replaced on the next upload, so a developer always has one dashboard. It is only offered to players with
     * {@code mcdashboard.developer} (or the dashboard's own derived permission).
     */
    private void uploadDashboard(HttpExchange ex, Optional<TokenService.Claims> who) throws IOException {
        if (who.isEmpty()) {
            error(ex, 401, "authentication required");
            return;
        }
        String player = who.get().player();
        if (!ctx.developerMode().getAsBoolean()) {
            error(ex, 403, "developer mode is disabled");
            return;
        }
        if (!ctx.permissions().has(player, DEVELOPER_PERMISSION)) {
            error(ex, 403, "developer permission required");
            return;
        }
        Dashboard draft = draft(body(ex));
        String clean = player.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String id = "dev-" + (clean + "000000000000").substring(0, 12);
        Dashboard saved = new Dashboard(id, draft.name(), draft.description(), 900, draft.background(), draft.items());
        try {
            ctx.dashboards().save(saved, player);
        } catch (IOException e) {
            error(ex, 500, "could not save dashboard");
            return;
        }
        ctx.onDashboardsChanged().run();
        List<String> preferred = Languages.preferred(ex.getRequestHeaders().getFirst("Accept-Language"));
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        respond(ex, 200, saved.toJson(preferred, ctx.defaultLanguage()));
    }

    /** Validates the upload body. Only server-owned fields (id, order, permission) are assigned by the plugin. */
    private Dashboard draft(Map<String, Object> body) {
        String name = text(body.get("name"), 64, true);
        String description = text(body.get("description"), 240, false);
        String background = text(body.get("background"), 32, false);
        if (background != null) background = background.toLowerCase(java.util.Locale.ROOT);
        if (background != null && !BACKGROUNDS.contains(background)) throw new BadRequest("unknown background");
        Object raw = body.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) throw new BadRequest("items must be a non-empty list");
        if (list.size() > MAX_ITEMS) throw new BadRequest("at most " + MAX_ITEMS + " items");
        List<Dashboard.Item> items = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> item)) throw new BadRequest("each item must be an object");
            String widget = text(item.get("widget"), 32, true);
            if (!WIDGETS.contains(widget)) throw new BadRequest("unknown widget: " + widget);
            List<String> placeholders = new ArrayList<>();
            Object combined = item.get("placeholders");
            if (combined instanceof List<?> ids) {
                for (Object value : ids) {
                    String id = text(value, 128, false);
                    if (id != null) placeholders.add(id);
                }
            } else {
                String single = text(item.get("placeholder"), 128, false);
                if (single != null) placeholders.add(single);
            }
            placeholders = new ArrayList<>(new LinkedHashSet<>(placeholders));
            if (placeholders.isEmpty()) throw new BadRequest("an item needs a placeholder");
            if (placeholders.size() > MAX_ITEM_PLACEHOLDERS) throw new BadRequest("at most " + MAX_ITEM_PLACEHOLDERS + " placeholders per item");
            for (String id : placeholders) {
                if (ctx.placeholders().definition(id).isEmpty()) throw new BadRequest("unknown placeholder: " + id);
            }
            String title = text(item.get("title"), 80, false);
            int column = integer(item.get("column"), 0);
            int row = integer(item.get("row"), items.size());
            int width = integer(item.get("width"), 1);
            int height = integer(item.get("height"), 1);
            if (column < 0 || column > 3) throw new BadRequest("column must be 0-3");
            if (row < 0 || row > 999) throw new BadRequest("row must be 0-999");
            if (width < 1 || width > 4) throw new BadRequest("width must be 1-4");
            if (height < 1 || height > 64) throw new BadRequest("height must be 1-64");
            items.add(new Dashboard.Item(placeholders, widget, LocalizedText.of(title),
                    column, row, width, height));
        }
        return new Dashboard("dev", LocalizedText.of(name), LocalizedText.of(description), 900, background, items);
    }

    /** A trimmed string of at most {@code max} characters; empty becomes null unless it is required. */
    private static String text(Object value, int max, boolean required) {
        if (value == null) {
            if (required) throw new BadRequest("missing text");
            return null;
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            if (required) throw new BadRequest("missing text");
            return null;
        }
        if (text.length() > max) throw new BadRequest("text is longer than " + max + " characters");
        return text;
    }

    private static int integer(Object value, int fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Number n)) throw new BadRequest("grid values must be numbers");
        return n.intValue();
    }

    /** Dashboards differ by language, so shared caches and proxies must key on {@code Accept-Language} too. */
    private static void varyLanguage(HttpExchange ex) {
        ex.getResponseHeaders().set("Vary", "Authorization, Accept-Language");
    }

    /**
     * Responds with a SenML pack plus standard HTTP caching: {@code max-age} equals the shortest
     * min-interval of the placeholders, an ETag allows 304s, and {@code Vary: Authorization} keeps
     * per-player data apart. Only anonymous responses are {@code public}, so an nginx/Varnish/CDN
     * in front can cache them without ever mixing up players.
     */
    private void cacheable(HttpExchange ex, List<?> records, List<PlaceholderDefinition> defs, boolean authenticated) throws IOException {
        long maxAge = Long.MAX_VALUE;
        boolean anyPlayer = false;
        for (PlaceholderDefinition d : defs) {
            maxAge = Math.min(maxAge, ctx.placeholders().intervalMs(d) / 1000);
            anyPlayer |= d.access() == PlaceholderDefinition.Access.PLAYER;
        }
        if (maxAge == Long.MAX_VALUE) maxAge = 0;
        boolean shared = !ctx.requireAuthentication() && !anyPlayer && !authenticated;
        byte[] bytes = Json.write(records).getBytes(StandardCharsets.UTF_8);
        String etag = "\"" + java.util.HexFormat.of().formatHex(sha256(bytes), 0, 12) + "\"";
        var h = ex.getResponseHeaders();
        h.set("Cache-Control", (shared ? "public" : "private") + ", max-age=" + maxAge);
        h.set("ETag", etag);
        h.set("Vary", "Authorization");
        if (etag.equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
            ex.sendResponseHeaders(304, -1);
            return;
        }
        h.set("Content-Type", SenMl.MEDIA_TYPE + "; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> info() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", ctx.serverName());
        o.put("plugin", "MCDashboard");
        o.put("version", ctx.pluginVersion());
        o.put("protocol", ApiContext.PROTOCOL);
        o.put("authRequired", ctx.requireAuthentication());
        if (!ctx.polling()) o.put("polling", false);
        o.put("defaultLanguage", ctx.defaultLanguage());
        o.put("languages", ctx.languages());
        if (ctx.websocketPort() > 0 && !ctx.discoveryOnly()) o.put("websocketPort", ctx.websocketPort());
        // Where the app should really connect (e.g. the proxy), best first. The address the app was given stays the fallback.
        if (!ctx.endpoints().isEmpty()) o.put("endpoints", ctx.endpoints());
        ServerStatus st = ctx.status().get();
        o.put("motd", st.motd());
        o.put("online", st.online());
        o.put("maxPlayers", st.max());
        o.put("gameVersion", st.gameVersion());
        if (st.gamePort() > 0) o.put("gamePort", st.gamePort());
        if (st.icon() != null) o.put("icon", st.icon());
        if (ctx.licenseId() != null) o.put("licenseId", ctx.licenseId());
        if (clients.minVersion() != null) o.put("minClientVersion", clients.minVersion());
        return o;
    }

    private void auth(HttpExchange ex, ClientPolicy.Client client) throws IOException {
        if (!ctx.authLimiter().allow(remote(ex))) {
            error(ex, 429, "too many attempts");
            return;
        }
        Map<String, Object> body = body(ex);
        var pending = ctx.otps().redeem(String.valueOf(body.get("otp")));
        if (pending.isEmpty()) {
            error(ex, 401, "invalid or expired code");
            return;
        }
        Object label = body.get("deviceLabel");
        String cleanLabel = label == null ? null : label.toString().substring(0, Math.min(40, label.toString().length()));
        Optional<DeviceStore.Device> device = ctx.devices().add(pending.get().playerUuid(), pending.get().playerName(),
                cleanLabel, ctx.maxDevices(), client.agent());
        if (device.isEmpty()) {
            error(ex, 403, "device limit reached; remove a device with /mcdashboard devices");
            return;
        }
        ctx.onLinked().accept(device.get());
        respond(ex, 200, tokens(device.get()));
    }

    private void refresh(HttpExchange ex) throws IOException {
        if (!ctx.authLimiter().allow(remote(ex))) {
            error(ex, 429, "too many attempts");
            return;
        }
        Map<String, Object> body = body(ex);
        Optional<TokenService.Claims> claims = ctx.tokens().verify(String.valueOf(body.get("refreshToken")), TokenService.Type.REFRESH);
        Optional<DeviceStore.Device> rotated = claims.flatMap(c -> ctx.devices().rotate(c.deviceId(), c.serial()));
        if (rotated.isEmpty()) {
            error(ex, 401, "invalid refresh token");
            return;
        }
        respond(ex, 200, tokens(rotated.get()));
    }

    private Map<String, Object> tokens(DeviceStore.Device d) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("accessToken", ctx.tokens().issue(TokenService.Type.ACCESS, d.id(), d.playerUuid(), d.serial(), ctx.accessTokenSeconds()));
        o.put("refreshToken", ctx.tokens().issue(TokenService.Type.REFRESH, d.id(), d.playerUuid(), d.serial(), ctx.refreshTokenSeconds()));
        o.put("expiresIn", ctx.accessTokenSeconds());
        o.put("player", Map.of("uuid", d.playerUuid(), "name", d.playerName()));
        return o;
    }

    /** Validates a bearer access token and that its device was not revoked. */
    public static Optional<TokenService.Claims> authenticate(ApiContext ctx, String token) {
        return ctx.tokens().verify(token, TokenService.Type.ACCESS).filter(c -> ctx.devices().get(c.deviceId()).isPresent());
    }

    private Optional<TokenService.Claims> principal(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return Optional.empty();
        return authenticate(ctx, h.substring(7).trim());
    }

    /** Client address; {@code X-Forwarded-For} is honoured only from configured trusted proxies. */
    private String remote(HttpExchange ex) {
        String peer = ex.getRemoteAddress().getAddress().getHostAddress();
        String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && ctx.trustedProxies().contains(peer)) {
            String[] hops = fwd.split(",");
            return hops[hops.length - 1].trim();
        }
        return peer;
    }

    private static final class BadRequest extends RuntimeException {
        BadRequest(String m) {
            super(m);
        }
    }

    private Map<String, Object> body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) throw new BadRequest("body too large");
            return Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new BadRequest("invalid JSON body");
        }
    }

    private static void cors(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", "*");
        h.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
        h.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
        h.set("Cache-Control", "no-store");
    }

    private static void error(HttpExchange ex, int status, String message) throws IOException {
        respond(ex, status, Map.of("error", message));
    }

    private static void respond(HttpExchange ex, int status, Object json) throws IOException {
        byte[] bytes = Json.write(json).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }
}
