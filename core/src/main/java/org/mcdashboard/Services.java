/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import org.mcdashboard.auth.ClientAssertions;
import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.auth.OtpService;
import org.mcdashboard.auth.RateLimiter;
import org.mcdashboard.auth.TokenService;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.diagnostics.ErrorReporter;
import org.mcdashboard.events.DeviceEvent;
import org.mcdashboard.events.EventBus;
import org.mcdashboard.events.EventStream;
import org.mcdashboard.events.WebhookDispatcher;
import org.mcdashboard.history.HistorySettings;
import org.mcdashboard.history.HistoryStore;
import org.mcdashboard.history.JdbcConnector;
import org.mcdashboard.history.JdbcHistoryStore;
import org.mcdashboard.image.ImageCache;
import org.mcdashboard.placeholder.ExpansionCatalog;
import org.mcdashboard.placeholder.HistoryService;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.placeholder.PlaceholderService;
import org.mcdashboard.platform.Log;
import org.mcdashboard.platform.Permissions;
import org.mcdashboard.platform.Platform;
import org.mcdashboard.push.PushClient;
import org.mcdashboard.push.PushService;
import org.mcdashboard.push.PushStore;
import org.mcdashboard.platform.ScheduledTask;
import org.mcdashboard.stats.PluginStats;
import org.mcdashboard.tls.TlsSupport;
import org.mcdashboard.tunnel.TunnelClient;
import org.mcdashboard.util.Threads;
import org.mcdashboard.web.ApiContext;
import org.mcdashboard.web.ClientPolicy;
import org.mcdashboard.web.HttpApi;
import org.mcdashboard.web.LiveServer;
import org.mcdashboard.web.ServerStatus;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Everything that runs while the web server is enabled: the API, the live feed, device events, the tunnel and history. */
final class Services {
    private final Platform platform;
    private final Log log;
    private final Settings settings;
    private final DataFolder files;
    private final DashboardStore dashboards;
    private final BooleanSupplier developerMode;
    private final ErrorReporter errorReporter;
    private final Clock clock = Clock.systemUTC();
    private volatile ServerStatus status = ServerStatus.EMPTY;
    private OtpService otps;
    private DeviceStore devices;
    private HttpApi http;
    private LiveServer live;
    private TunnelClient tunnel;
    private HttpApi loopbackHttp;
    private LiveServer loopbackLive;
    private String tlsFingerprint;
    private EventStream eventStream;
    private WebhookDispatcher webhooks;
    private HistoryService history;
    private LicenseHeartbeat licenseHeartbeat;
    private volatile PushService push;
    private ScheduledExecutorService sampler;
    private ScheduledTask statusTask;
    private final PluginStats stats;
    private volatile int dashboardCount;

    Services(Platform platform, Settings settings, DataFolder files, DashboardStore dashboards, BooleanSupplier developerMode, PluginStats stats,
             ErrorReporter errorReporter) {
        this.stats = stats;
        this.platform = platform;
        this.log = platform.log();
        this.settings = settings;
        this.files = files;
        this.dashboards = dashboards;
        this.developerMode = developerMode;
        this.errorReporter = errorReporter;
    }

    /** Starts everything; a failure stops whatever started already and returns false, so commands report it as not running. */
    boolean start() {
        if (!platform.placeholdersAvailable()) {
            log.severe("PlaceholderAPI is required. Install it and restart.");
            return false;
        }
        try {
            startServices();
            return true;
        } catch (IOException | RuntimeException e) {
            if (errorReporter != null) errorReporter.report(e, "startup");
            log.severe("Could not start MCDashboard: " + e.getMessage());
            // Never leave history, the sampler, the licence heartbeat or the tunnel running behind a dead API.
            stop();
            return false;
        }
    }

    /** Starts the services; a failure propagates so {@link #start()} can undo the parts that did start. */
    private void startServices() throws IOException {
        otps = new OtpService(clock, settings.otpTimeoutSeconds());
        devices = new DeviceStore(files.resolve("devices.json"));
        TokenService tokens = new TokenService(files.secret(), clock);

        var yaml = files.placeholders();
        HistorySettings historySettings = settings.history();
        List<PlaceholderDefinition> parsed = historySettings.apply(PlaceholderDefinition.parseAll(yaml.getMapList("placeholders"),
                msg -> log.warning("placeholders.yml: " + msg)));
        List<PlaceholderDefinition> defs = offeredDefinitions(parsed);
        Path images = files.resolve("images");
        Files.createDirectories(images);
        Permissions permissions = platform.permissions(dashboards);
        PlaceholderService placeholders = new PlaceholderService(platform.values(), clock,
                settings.minIntervalMs(), settings.minIntervalOverrides(), permissions.cache(),
                defs, images, new ImageCache(clock));

        history = new HistoryService(clock, defs, historySettings, historyStore(historySettings), log::warning);
        history.start();
        stats.historyStorage(historySettings.storage().type()).placeholders(defs::size)
                .devicesEntitled(devices::countEntitled)
                .devices(devices::count, () -> devices.countSeenSince(clock.millis() - PluginStats.ONLINE_MINUTES * 60_000L))
                .historySamples(history::samplesWritten).dashboards(() -> dashboardCount);
        refreshDashboardCount();
        permissions.start(defs);
        permissions.addPermissions(settings.minIntervalOverrides().permissions());
        List<ExpansionCatalog.Entry> catalog = ExpansionCatalog.parseAll(yaml.getMapList("catalog"),
                msg -> log.warning("placeholders.yml: " + msg));
        new CatalogLoader(platform, placeholders, history, historySettings, permissions, defs, catalog).start();
        statusTask = platform.runSyncRepeating(this::refreshStatus, 100L);
        startSampler(placeholders);

        String name = settings.nameOr(platform.serverName());
        ApiContext ctx = new ApiContext(name, platform.pluginVersion(), () -> status,
                settings.requireAuthentication(), settings.maxDevices(),
                settings.accessTokenSeconds(), settings.refreshTokenSeconds(), settings.websocketPort(),
                placeholders, tokens, devices, otps,
                new RateLimiter(clock, settings.authAttemptsPerMinute(), 60_000), dashboards,
                settings.trustedProxies(), history, settings.licenseId(), d -> platform.notifyLinked(d, settings.prefix()), permissions.cache(),
                settings.defaultLanguage(), developerMode, permissions::dashboardsChanged,
                settings.endpoints(), settings.discoveryOnly(), settings.polling(), stats,
                settings.maxDashboards(), settings.maxTiles(), settings.allowPlayerNames(), platform::playerUuid);
        ClientPolicy clients = ClientPolicy.of(clock, settings.allowedClients(), settings.minClientVersion(),
                settings.requestsPerMinute(), settings.anonymousRequestsPerMinute(), log::warning);
        // Slow or idle connections must not hold the API's worker threads: the JDK server has no timeouts unless told.
        System.getProperties().putIfAbsent("sun.net.httpserver.maxReqTime", "30");
        System.getProperties().putIfAbsent("sun.net.httpserver.maxRspTime", "30");
        TlsSupport.Identity tls = settings.tls().enabled() ? loadTls() : null;
        // loadTls() has already reported the reason; never fall back to serving in the clear.
        if (settings.tls().enabled() && tls == null) throw new IOException("TLS could not be loaded");
        tlsFingerprint = tls == null ? null : tls.fingerprint();
        clients.background(new RateLimiter(clock, settings.backgroundRequestsPerMinute(), 60_000));
        ClientAssertions assertions = clientAssertions();
        http = new HttpApi(ctx, clients).stats(stats).workers(settings.workerThreads());
        http.background(settings.backgroundEnabled());
        if (assertions != null) http.verifyClients(assertions);
        if (tls != null) http.tls(tls.selfSigned() ? tls.fingerprint() : null);
        EventBus bus = startEvents();
        http.start(settings.host(), settings.port(), tls == null ? null : tls.context());
        log.info("REST API listening on port " + http.port() + (tls == null ? " (plain HTTP)" : " (HTTPS)"));
        // UI/UX only: tell the portal which licence and address this server uses. The app's signed, host-bound
        // check is the enforcement; this heartbeat is open-source code and can be removed by a fork.
        licenseHeartbeat = new LicenseHeartbeat(settings.licenseId(), settings.licenseApiUrl(),
                LicenseHeartbeat.hostFor(settings.licenseHost(), settings.publicUrl(), settings.endpoints()),
                settings.licenseHeartbeatMinutes(), platform.pluginVersion(), log);
        licenseHeartbeat.start();
        startPush(name, ctx, placeholders, permissions);
        if (settings.websocketPort() > 0) {
            live = new LiveServer(ctx, settings.maxLiveConnections(), clients);
            stats.liveClients(this::liveConnections);
            if (assertions != null) live.verifyClients(assertions);
            live.start(settings.host(), settings.websocketPort(), tls == null ? null : tls.context());
            log.info("Live feed listening on port " + live.port() + (tls == null ? " (plain)" : " (TLS)"));
        }
        if (tls != null && settings.tunnelEnabled()) startLoopback(ctx, clients);
        startTunnel(name, bus);
        log.info(defs.size() + " placeholder(s) exposed.");
        if (!settings.requireAuthentication()) {
            log.warning("security.require-authentication is off: anyone who can reach this server can read its PUBLIC placeholders without pairing.");
        }
        if (settings.allowPlayerNames()) {
            log.warning("security.allow-player-names is on: anyone who can reach this server can read PLAYER placeholders "
                    + "and permission-gated dashboards as any player by typing that player's name. There is no authentication; "
                    + "only enable this on a private or trusted network.");
        }
    }

    /**
     * The client verifier when {@code security.client-verification.mode} is {@code required}, else null. The service's public
     * keys are the vendor keys in {@code vendor-keys.json} (empty in this release) plus the owner's {@code keys}; with none,
     * every request is refused and the log says why, rather than quietly accepting everybody.
     */
    private ClientAssertions clientAssertions() {
        if (!settings.verifyClients()) return null;
        List<java.util.Map<String, Object>> keys = new ArrayList<>(vendorKeys());
        keys.addAll(settings.verifyKeys());
        ClientAssertions v;
        try {
            v = new ClientAssertions(keys, clock, new java.util.HashSet<>(settings.verifyKinds()),
                    new java.util.HashSet<>(settings.verifyHosts().stream().map(h -> h.toLowerCase(java.util.Locale.ROOT)).toList()), settings.verifyDeviceOnly(), settings.licenseId());
        } catch (IllegalArgumentException e) {
            log.severe("security.client-verification.keys: " + e.getMessage() + ". Every client is refused until this is fixed.");
            v = new ClientAssertions(List.of(), clock, java.util.Set.of(), java.util.Set.of(), true);
        }
        if (!v.hasKeys()) log.severe("security.client-verification is required but no public key is trusted: every client is refused. "
                + "This release ships no vendor keys (Android/iOS verification is not available yet); add the verification service's key to security.client-verification.keys, or set mode: off.");
        else log.info("Client verification is required (" + (settings.verifyDeviceOnly() ? "platform-attested apps only" : "attested apps and registered clients") + ").");
        return v;
    }

    /** The public keys of the MVdW attestation service that this release trusts ({@code vendor-keys.json}, a JSON array of JWKs). */
    @SuppressWarnings("unchecked")
    private List<java.util.Map<String, Object>> vendorKeys() {
        try (var in = Services.class.getResourceAsStream("/vendor-keys.json")) {
            if (in == null) return List.of();
            Object parsed = org.mcdashboard.json.Json.parse(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            return parsed instanceof List<?> l ? (List<java.util.Map<String, Object>>) l : List.of();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** The certificate (or null without TLS); a failure is reported with what to change. */
    private TlsSupport.Identity loadTls() {
        try {
            List<String> names = new ArrayList<>();
            if (settings.publicUrl() != null) {
                try {
                    names.add(URI.create(settings.publicUrl()).getHost());
                } catch (IllegalArgumentException ignored) {
                    // a bad public-url is reported elsewhere
                }
            }
            if (!settings.host().isBlank() && !settings.host().equals("0.0.0.0") && !settings.host().equals("::")) names.add(settings.host());
            TlsSupport.Identity id = TlsSupport.load(files.dir(), settings.tls(), names, settings.nameOr(platform.serverName()), log::info);
            log.info("TLS certificate " + (id.selfSigned() ? "(self-signed) " : "") + "SHA-256 fingerprint: " + id.fingerprint());
            return id;
        } catch (IOException e) {
            log.severe("Could not start HTTPS: " + e.getMessage() + " (set server.tls.mode: off only behind a TLS-terminating proxy).");
            return null;
        }
    }

    /** Plain listeners on 127.0.0.1 for the tunnel client only, on free ports. */
    private void startLoopback(ApiContext ctx, ClientPolicy clients) throws IOException {
        ClientAssertions assertions = clientAssertions();
        loopbackHttp = new HttpApi(ctx, clients).stats(stats).workers(settings.workerThreads()).push(push);
        loopbackHttp.background(settings.backgroundEnabled());
        if (assertions != null) loopbackHttp.verifyClients(assertions);
        loopbackHttp.start("127.0.0.1", 0, null);
        if (settings.websocketPort() > 0) {
            loopbackLive = new LiveServer(ctx, settings.maxLiveConnections(), clients);
            if (assertions != null) loopbackLive.verifyClients(assertions);
            loopbackLive.start("127.0.0.1", 0);
        }
    }

    /** Without {@code stats.offer-to-app} the {@code MCDashboard} group (values {@code %mcdashboard_*%}) is not offered to the app. */
    private List<PlaceholderDefinition> offeredDefinitions(List<PlaceholderDefinition> defs) {
        if (settings.offerStatsToApp()) return defs;
        return defs.stream().filter(d -> !isStatsEntry(d)).toList();
    }

    static boolean isStatsEntry(PlaceholderDefinition d) {
        return "MCDashboard".equals(d.group()) && d.value() != null && d.value().startsWith("%mcdashboard_");
    }

    /** Live-feed connections on the public and the loopback listener; 0 for either listener that is not running. */
    int liveConnections() {
        return (live == null ? 0 : live.connections()) + (loopbackLive == null ? 0 : loopbackLive.connections());
    }

    /** Reads the dashboard folder, so it runs on the sampler thread (and once at start), never when a placeholder is read. */
    private void refreshDashboardCount() {
        try {
            dashboardCount = dashboards.all().size();
        } catch (RuntimeException ignored) {
            // keep the last count
        }
    }

    String tlsFingerprint() {
        return tlsFingerprint;
    }

    void stop() {
        if (statusTask != null) {
            statusTask.cancel();
            statusTask = null;
        }
        if (loopbackHttp != null) {
            loopbackHttp.stop();
            loopbackHttp = null;
        }
        if (loopbackLive != null) {
            loopbackLive.stop();
            loopbackLive = null;
        }
        if (tunnel != null) {
            tunnel.stop();
            tunnel = null;
        }
        if (webhooks != null) {
            webhooks.stop();
            webhooks = null;
        }
        if (eventStream != null) {
            eventStream.close();
            eventStream = null;
        }
        if (http != null) {
            http.stop();
            http = null;
        }
        if (live != null) {
            live.stop();
            live = null;
        }
        PushService pushService = push;
        push = null;
        if (pushService != null) pushService.close();
        if (licenseHeartbeat != null) {
            licenseHeartbeat.close();
            licenseHeartbeat = null;
        }
        if (sampler != null) {
            sampler.shutdownNow();
            sampler = null;
        }
        if (history != null) {
            history.close();
            history = null;
        }
    }

    OtpService otps() {
        return otps;
    }

    DeviceStore devices() {
        return devices;
    }

    TunnelClient tunnel() {
        return tunnel;
    }

    /** Main thread: the server is not thread safe, so the server list data is copied here for the API threads. */
    private void refreshStatus() {
        status = platform.status();
    }

    private void startSampler(PlaceholderService placeholders) {
        sampler = Executors.newSingleThreadScheduledExecutor(Threads.daemon("MCDashboard-history"));
        sampler.scheduleWithFixedDelay(() -> {
            refreshDashboardCount();
            for (String id : history.trackedIds()) {
                placeholders.definition(id).ifPresent(d -> placeholders.read(id, null)
                        .ifPresent(v -> history.record(id, v.value(), history.stepMs(d, placeholders.intervalMs(d)))));
            }
            PushService alerts = push;
            if (alerts != null) {
                try {
                    alerts.evaluate();
                } catch (RuntimeException e) {
                    log.warning("Push alert rules could not be evaluated: " + e.getMessage());
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * Starts push notifications: bindings of the paired devices, their alert rules and the portal relay. Only licensed
     * servers use it ({@link PushService#available()}); with {@code push.enabled: false} nothing is created.
     */
    private void startPush(String name, ApiContext ctx, PlaceholderService placeholders, Permissions permissions) {
        if (!settings.pushEnabled()) return;
        PushService service = new PushService(new PushStore(files.resolve("push.json")),
                new PushClient(settings.licenseId(), settings.licenseApiUrl()), licenseHeartbeat::licensed, true, name, ctx.licenseHash(),
                id -> devices.get(id).map(DeviceStore.Device::playerUuid),
                PushService.values(placeholders, permissions.cache()),
                clock, log);
        devices.onRemoved(d -> service.unbind(d.id()));
        push = service;
        http.push(service);
    }

    /** The database for history, or null to keep it in memory. */
    private HistoryStore historyStore(HistorySettings historySettings) {
        HistorySettings.Storage storage = historySettings.storage();
        if (storage.dialect() == null) return null;
        Path data = files.dir();
        String url = storage.jdbcUrl(data);
        log.info("History is stored in " + storage.type() + " (" + url.replaceAll("(?i)(password|pwd)=[^&;]*", "$1=***") + ")");
        return new JdbcHistoryStore(storage.dialect(),
                new JdbcConnector(url, storage.user(), storage.password(), files.resolve("drivers")),
                storage.baseUri(), settings.nameOr("Minecraft server"), clock);
    }

    /** Publishes device revocations to the proxy stream and the webhooks; the tunnel subscribes to the same bus. */
    private EventBus startEvents() {
        EventBus bus = new EventBus(clock);
        devices.onRemoved(d -> bus.publish(DeviceEvent.REVOKED, d.id(), d.playerUuid()));
        if (settings.eventsToken() != null) {
            eventStream = new EventStream(bus, 15);
            http.events(eventStream, settings.eventsToken());
            log.info("Device events stream enabled at /v1/events.");
        }
        if (!settings.webhooks().isEmpty()) {
            webhooks = new WebhookDispatcher(settings.webhooks(), clock, platform.pluginVersion(), log::warning);
            webhooks.start(bus);
            log.info(settings.webhooks().size() + " webhook(s) receive device events.");
        }
        return bus;
    }

    /** Dials out to MCDashboard-proxy; a configuration that would be unsafe is reported and the tunnel stays off. */
    private void startTunnel(String name, EventBus bus) {
        if (!settings.tunnelEnabled()) return;
        try {
            URI uri = TunnelClient.validate(settings.tunnelUrl(), settings.tunnelKey(), settings.tunnelAllowInsecure());
            // With TLS on, the tunnel talks to the loopback-only plain listeners: the proxy terminates TLS towards the app.
            HttpApi api = loopbackHttp != null ? loopbackHttp : http;
            LiveServer feed = loopbackLive != null ? loopbackLive : live;
            tunnel = new TunnelClient(uri, settings.tunnelKey(), settings.localHost(), api.port(), feed == null ? 0 : feed.port(),
                    name, platform.pluginVersion(), bus, log::info, log::warning);
            // /v1/info may only promise what the proxy in front of it can carry.
            api.proxyFeatures(tunnel::proxyFeatures);
            tunnel.start();
        } catch (IllegalArgumentException e) {
            log.severe("Tunnel not started: " + e.getMessage());
        }
    }
}
