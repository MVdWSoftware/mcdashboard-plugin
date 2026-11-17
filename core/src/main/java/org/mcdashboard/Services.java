/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.auth.OtpService;
import org.mcdashboard.auth.RateLimiter;
import org.mcdashboard.auth.TokenService;
import org.mcdashboard.dashboard.DashboardStore;
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
    private final Clock clock = Clock.systemUTC();
    private volatile ServerStatus status = ServerStatus.EMPTY;
    private OtpService otps;
    private DeviceStore devices;
    private HttpApi http;
    private LiveServer live;
    private TunnelClient tunnel;
    private EventStream eventStream;
    private WebhookDispatcher webhooks;
    private HistoryService history;
    private ScheduledExecutorService sampler;

    Services(Platform platform, Settings settings, DataFolder files, DashboardStore dashboards, BooleanSupplier developerMode) {
        this.platform = platform;
        this.log = platform.log();
        this.settings = settings;
        this.files = files;
        this.dashboards = dashboards;
        this.developerMode = developerMode;
    }

    /** Starts everything; a failure is logged and leaves the rest of the server untouched. */
    void start() {
        if (!platform.placeholdersAvailable()) {
            log.severe("PlaceholderAPI is required. Install it and restart.");
            return;
        }
        try {
            otps = new OtpService(clock, settings.otpTimeoutSeconds());
            devices = new DeviceStore(files.resolve("devices.json"));
            TokenService tokens = new TokenService(files.secret(), clock);

            var yaml = files.placeholders();
            HistorySettings historySettings = settings.history();
            List<PlaceholderDefinition> defs = historySettings.apply(PlaceholderDefinition.parseAll(yaml.getMapList("placeholders"),
                    msg -> log.warning("placeholders.yml: " + msg)));
            Path images = files.resolve("images");
            Files.createDirectories(images);
            PlaceholderService placeholders = new PlaceholderService(platform.values(), clock,
                    settings.minIntervalMs(), defs, images, new ImageCache(clock));

            history = new HistoryService(clock, defs, historySettings, historyStore(historySettings), log::warning);
            history.start();
            Permissions permissions = platform.permissions(dashboards);
            permissions.start(defs);
            List<ExpansionCatalog.Entry> catalog = ExpansionCatalog.parseAll(yaml.getMapList("catalog"),
                    msg -> log.warning("placeholders.yml: " + msg));
            new CatalogLoader(platform, placeholders, history, historySettings, permissions, defs, catalog).start();
            platform.runSyncRepeating(this::refreshStatus, 100L);
            startSampler(placeholders);

            String name = settings.nameOr(platform.serverName());
            ApiContext ctx = new ApiContext(name, platform.pluginVersion(), () -> status,
                    settings.requireAuthentication(), settings.maxDevices(),
                    settings.accessTokenSeconds(), settings.refreshTokenSeconds(), settings.websocketPort(),
                    placeholders, tokens, devices, otps,
                    new RateLimiter(clock, settings.authAttemptsPerMinute(), 60_000), dashboards,
                    settings.trustedProxies(), history, settings.licenseId(), platform::notifyLinked, permissions.cache(),
                    settings.defaultLanguage(), developerMode, permissions::dashboardsChanged,
                    settings.endpoints(), settings.discoveryOnly(), settings.polling());
            ClientPolicy clients = ClientPolicy.of(clock, settings.allowedClients(), settings.minClientVersion(),
                    settings.requestsPerMinute(), settings.anonymousRequestsPerMinute(), log::warning);
            http = new HttpApi(ctx, clients);
            EventBus bus = startEvents();
            http.start(settings.host(), settings.port(), null);
            log.info("REST API listening on port " + http.port());
            if (settings.websocketPort() > 0) {
                live = new LiveServer(ctx, settings.maxLiveConnections(), clients);
                live.start(settings.host(), settings.websocketPort());
                log.info("Live feed listening on port " + live.port());
            }
            startTunnel(name, bus);
            log.info(defs.size() + " placeholder(s) exposed.");
        } catch (IOException | RuntimeException e) {
            log.severe("Could not start MCDashboard: " + e.getMessage());
        }
    }

    void stop() {
        if (tunnel != null) tunnel.stop();
        if (webhooks != null) webhooks.stop();
        if (eventStream != null) eventStream.close();
        if (http != null) http.stop();
        if (live != null) live.stop();
        if (sampler != null) sampler.shutdownNow();
        if (history != null) history.close();
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
            for (String id : history.trackedIds()) {
                placeholders.definition(id).ifPresent(d -> placeholders.read(id, null)
                        .ifPresent(v -> history.record(id, v.value(), history.stepMs(d, placeholders.intervalMs(d)))));
            }
        }, 5, 5, TimeUnit.SECONDS);
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
            tunnel = new TunnelClient(uri, settings.tunnelKey(), settings.localHost(), http.port(), live == null ? 0 : live.port(),
                    name, platform.pluginVersion(), bus, log::info, log::warning);
            tunnel.start();
        } catch (IllegalArgumentException e) {
            log.severe("Tunnel not started: " + e.getMessage());
        }
    }
}
