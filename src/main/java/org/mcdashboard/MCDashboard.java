package org.mcdashboard;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;
import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.auth.OtpService;
import org.mcdashboard.auth.RateLimiter;
import org.mcdashboard.auth.TokenService;
import org.mcdashboard.commands.MCDashboardCommand;
import org.mcdashboard.dashboard.Dashboard;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.dashboard.Languages;
import org.mcdashboard.placeholder.PapiValueSource;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.placeholder.PlaceholderService;
import org.mcdashboard.web.ApiContext;
import org.mcdashboard.web.ClientPolicy;
import org.mcdashboard.web.HttpApi;
import org.mcdashboard.web.LiveServer;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * MCDashboard: exposes an allowlist of PlaceholderAPI placeholders over a REST API and a live
 * WebSocket feed for the MinecraftDashboard mobile app. No runtime dependencies besides the server.
 */
public final class MCDashboard extends JavaPlugin {
    private HttpApi http;
    private LiveServer live;
    private OtpService otps;
    private DeviceStore devices;
    private org.mcdashboard.events.EventStream eventStream;
    private org.mcdashboard.events.EventBus eventBus;
    private org.mcdashboard.tunnel.TunnelClient tunnel;
    private org.mcdashboard.events.WebhookDispatcher webhooks;
    private int maxDevices;
    private volatile org.mcdashboard.web.ServerStatus status = org.mcdashboard.web.ServerStatus.EMPTY;
    private org.mcdashboard.placeholder.HistoryService history;
    private org.mcdashboard.history.HistorySettings historySettings = org.mcdashboard.history.HistorySettings.defaults();
    private final org.mcdashboard.auth.PermissionCache permissions = new org.mcdashboard.auth.PermissionCache();
    private volatile java.util.Set<String> permissionNodes = java.util.Set.of();
    private DashboardStore dashboardStore;
    private volatile boolean developerMode;
    private java.util.concurrent.ScheduledExecutorService sampler;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("placeholders.yml", false);
        dashboardStore = new DashboardStore(getDataFolder().toPath().resolve("dashboards"));
        // Preset dashboards are only created on the first start, so a preset the owner deleted does not come back.
        if (!new File(getDataFolder(), "dashboards").exists()) {
            saveResource("dashboards/player.yml", false);
            saveResource("dashboards/server.yml", false);
        }
        FileConfiguration cfg = getConfig();
        developerMode = loadDeveloperMode(cfg);

        if (!cfg.getBoolean("server.enabled", false)) {
            getLogger().warning("The web server is disabled. Set server.enabled to true in config.yml and restart.");
        } else {
            startServer(cfg);
        }
        PluginCommand cmd = getCommand("mcdashboard");
        if (cmd != null) {
            MCDashboardCommand handler = new MCDashboardCommand(this);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }
    }

    private void startServer(FileConfiguration cfg) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().severe("PlaceholderAPI is required. Install it and restart.");
            return;
        }
        try {
            Clock clock = Clock.systemUTC();
            maxDevices = cfg.getInt("security.max-devices", 5);
            otps = new OtpService(clock, cfg.getLong("security.otp-timeout", 600));
            devices = new DeviceStore(getDataFolder().toPath().resolve("devices.json"));
            TokenService tokens = new TokenService(secret(), clock);

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "placeholders.yml"));
            historySettings = org.mcdashboard.history.HistorySettings.parse(org.mcdashboard.history.HistorySettings.toMap(cfg.getConfigurationSection("history")),
                    cfg.getString("server.name") != null ? cfg.getString("server.name") : "server", msg -> getLogger().warning("config.yml: " + msg));
            List<PlaceholderDefinition> defs = historySettings.apply(PlaceholderDefinition.parseAll(yaml.getMapList("placeholders"),
                    msg -> getLogger().warning("placeholders.yml: " + msg)));
            Path images = getDataFolder().toPath().resolve("images");
            Files.createDirectories(images);
            PlaceholderService placeholders = new PlaceholderService(new PapiValueSource(this), clock,
                    cfg.getLong("security.min-interval", 1) * 1000, defs, images,
                    new org.mcdashboard.image.ImageCache(clock));

            history = new org.mcdashboard.placeholder.HistoryService(clock, defs, historySettings, historyStore(clock, cfg),
                    msg -> getLogger().warning(msg));
            history.start();
            startPermissionSnapshots(defs);
            // Expansions register with PlaceholderAPI after plugins enable, so read their lists on the first main-thread tick.
            List<org.mcdashboard.placeholder.ExpansionCatalog.Entry> catalog = org.mcdashboard.placeholder.ExpansionCatalog.parseAll(
                    yaml.getMapList("catalog"), msg -> getLogger().warning("placeholders.yml: " + msg));
            if (!catalog.isEmpty()) Bukkit.getScheduler().runTask(this, () -> loadCatalog(placeholders, defs, catalog, 0));
            Bukkit.getScheduler().runTaskTimer(this, this::refreshStatus, 0L, 100L);
            startSampler(placeholders);

            int websocketPort = cfg.getBoolean("server.websocket.enabled", true) ? cfg.getInt("server.websocket.port", 1554) : 0;
            boolean polling = cfg.getBoolean("server.polling.enabled", true);
            if (!polling && websocketPort <= 0) {
                getLogger().warning("server.polling.enabled and server.websocket.enabled are both off: no client could read values. Polling stays on.");
                polling = true;
            }
            ApiContext ctx = new ApiContext(
                    cfg.getString("server.name") != null ? cfg.getString("server.name") : Bukkit.getServer().getName(),
                    getDescription().getVersion(), () -> status,
                    cfg.getBoolean("security.require-authentication", false), maxDevices,
                    cfg.getLong("security.access-token-timeout", 900), cfg.getLong("security.refresh-token-timeout", 31536000),
                    websocketPort,
                    placeholders, tokens, devices, otps,
                    new RateLimiter(clock, Math.max(1, cfg.getInt("security.auth-attempts-per-minute", 10)), 60_000), dashboardStore,
                    trustedProxies(cfg), history,
                    licenseId(), this::notifyLinked, permissions,
                    Languages.normaliseOrDefault(cfg.getString("server.default-language", "en")),
                    () -> developerMode, this::dashboardsChanged,
                    cfg.getStringList("server.endpoints"), cfg.getBoolean("server.discovery-only", false), polling);

            ClientPolicy clients = ClientPolicy.of(clock, cfg.getStringList("security.allowed-clients"),
                    cfg.getString("security.min-client-version", ""), cfg.getInt("security.requests-per-minute", 120),
                    cfg.getInt("security.anonymous-requests-per-minute", 60), getLogger()::warning);
            http = new HttpApi(ctx, clients);
            startEvents(cfg, clock);
            http.start(cfg.getString("server.host", ""), cfg.getInt("server.port", 1553), null);
            getLogger().info("REST API listening on port " + http.port());
            if (ctx.websocketPort() > 0) {
                live = new LiveServer(ctx, cfg.getInt("server.websocket.max-connections", 200), clients);
                live.start(cfg.getString("server.host", ""), ctx.websocketPort());
                getLogger().info("Live feed listening on port " + live.port());
            }
            startTunnel(cfg);
            getLogger().info(defs.size() + " placeholder(s) exposed.");
        } catch (IOException | RuntimeException e) {
            getLogger().severe("Could not start MCDashboard: " + e.getMessage());
        }
    }

    /**
     * Main thread: expands the catalog through PlaceholderAPI and adds it behind the explicit entries. PlaceholderAPI
     * registers external expansions a moment after the plugins enable, so an expansion that is not there yet is retried
     * every 5 seconds (up to 6 times) before it is reported as missing.
     */
    private void loadCatalog(PlaceholderService placeholders, List<PlaceholderDefinition> explicit,
                             List<org.mcdashboard.placeholder.ExpansionCatalog.Entry> catalog, int attempt) {
        List<String> problems = new java.util.ArrayList<>();
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        try {
            List<PlaceholderDefinition> built = org.mcdashboard.placeholder.ExpansionCatalog.build(catalog,
                    new org.mcdashboard.placeholder.PapiExpansionLookup().orElse(new org.mcdashboard.placeholder.PapiCloudLookup()),
                    problems::add, counts);
            List<PlaceholderDefinition> all = historySettings.apply(org.mcdashboard.placeholder.ExpansionCatalog.merge(explicit, built));
            placeholders.setDefinitions(all);
            history.track(all);
            addPermissionNodes(built);
            getLogger().info(built.size() + " catalog placeholder(s) loaded, " + all.size() + " in total.");
        } catch (RuntimeException e) {
            getLogger().warning("Could not load the placeholder catalog: " + e.getMessage());
        }
        boolean notYet = problems.stream().anyMatch(m -> m.contains("not installed"))
                || org.mcdashboard.placeholder.ExpansionCatalog.worthRetrying(catalog, counts);
        if (notYet && attempt < 6) {
            Bukkit.getScheduler().runTaskLater(this, () -> loadCatalog(placeholders, explicit, catalog, attempt + 1), 100L);
        } else {
            problems.forEach(m -> getLogger().warning(m));
            for (String line : org.mcdashboard.placeholder.ExpansionCatalog.report(catalog, counts)) {
                if (line.startsWith("WARN ")) getLogger().warning(line.substring(5));
                else getLogger().info(line);
            }
        }
    }

    private void addPermissionNodes(List<PlaceholderDefinition> defs) {
        java.util.Set<String> nodes = new java.util.HashSet<>(permissionNodes);
        for (PlaceholderDefinition d : defs) if (d.permission() != null) nodes.add(d.permission());
        permissionNodes = nodes;
    }

    /** Main-thread snapshot of everything the server list needs (Bukkit is not thread safe). */
    private void refreshStatus() {
        String icon = null;
        File f = new File("server-icon.png");
        try {
            if (f.isFile() && f.length() < 64 * 1024) {
                icon = "data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(f.toPath()));
            }
        } catch (IOException ignored) {
            // no icon
        }
        status = new org.mcdashboard.web.ServerStatus(Bukkit.getMotd(), icon, Bukkit.getOnlinePlayers().size(),
                Bukkit.getMaxPlayers(), Bukkit.getVersion(), Bukkit.getPort());
    }

    /**
     * Takes permission snapshots on the main thread: the nodes dashboards and placeholders ask for, per online player,
     * on join, on quit and every 5 seconds (see {@code PermissionCache} for why).
     */
    private void startPermissionSnapshots(List<PlaceholderDefinition> defs) {
        java.util.Set<String> nodes = new java.util.HashSet<>();
        for (PlaceholderDefinition d : defs) if (d.permission() != null) nodes.add(d.permission());
        permissionNodes = nodes;
        List<Dashboard> dashboards = dashboardStore.all();
        addDashboardPermissionNodes(dashboards);
        registerDashboardPermissions(dashboards);
        Bukkit.getScheduler().runTaskTimer(this, () -> Bukkit.getOnlinePlayers().forEach(this::snapshot), 20L, 100L);
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
                // Permission plugins attach their data a moment after the join event.
                Bukkit.getScheduler().runTaskLater(MCDashboard.this, () -> snapshot(e.getPlayer()), 20L);
            }

            @org.bukkit.event.EventHandler
            public void onQuit(org.bukkit.event.player.PlayerQuitEvent e) {
                snapshot(e.getPlayer());
            }
        }, this);
    }

    private void snapshot(org.bukkit.entity.Player p) {
        java.util.Set<String> held = new java.util.HashSet<>();
        for (String node : permissionNodes) if (p.hasPermission(node)) held.add(node);
        permissions.update(p.getUniqueId(), held);
    }

    /** Tells the player in game that a device was linked to their account, with the way to undo it. */
    private void notifyLinked(org.mcdashboard.auth.DeviceStore.Device device) {
        Bukkit.getScheduler().runTask(this, () -> {
            org.bukkit.entity.Player p = Bukkit.getPlayer(java.util.UUID.fromString(device.playerUuid()));
            if (p != null) {
                p.sendMessage("§aA device (§f" + device.label() + "§a) was linked to your account for MinecraftDashboard.");
                p.sendMessage("§7Not you? Run §f/mcdashboard revoke " + device.id() + "§7 to remove it.");
            }
        });
    }

    /** The public license id from the config, or null. It identifies the subscription; it is not a secret. */
    private String licenseId() {
        String id = getConfig().getString("license.id", "");
        return id == null || id.isBlank() ? null : id.trim();
    }

    private void startSampler(PlaceholderService placeholders) {
        sampler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MCDashboard-history");
            t.setDaemon(true);
            return t;
        });
        sampler.scheduleWithFixedDelay(() -> {
            for (String id : history.trackedIds()) {
                placeholders.definition(id).ifPresent(d ->
                        placeholders.read(id, null).ifPresent(v -> history.record(id, v.value(), history.stepMs(d, placeholders.intervalMs(d)))));
            }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
    }

    /** Whether developer mode is on: admins can toggle it with {@code /mcdashboard developer on|off}. */
    public boolean developerMode() {
        return developerMode;
    }

    /** Turns developer mode on or off and remembers the choice in {@code developer.yml}, keeping config comments intact. */
    public void setDeveloperMode(boolean enabled) {
        developerMode = enabled;
        Path file = getDataFolder().toPath().resolve("developer.yml");
        try {
            Files.createDirectories(file.getParent());
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.set("enabled", enabled);
            Files.writeString(file, "# Managed by /mcdashboard developer. Overrides developer.enabled in config.yml.\n"
                    + yaml.saveToString(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().warning("Could not save developer mode: " + e.getMessage());
        }
    }

    /** The runtime toggle from developer.yml wins over the default in config.yml. */
    private boolean loadDeveloperMode(FileConfiguration cfg) {
        File state = new File(getDataFolder(), "developer.yml");
        if (state.isFile()) {
            try {
                return YamlConfiguration.loadConfiguration(state)
                        .getBoolean("enabled", cfg.getBoolean("developer.enabled", false));
            } catch (RuntimeException e) {
                getLogger().warning("Could not read developer.yml: " + e.getMessage());
            }
        }
        return cfg.getBoolean("developer.enabled", false);
    }

    /** Registers the permission of every dashboard; {@code dev-*} dashboards are also granted to developers. */
    private void registerDashboardPermissions(List<Dashboard> dashboards) {
        org.bukkit.plugin.PluginManager manager = Bukkit.getPluginManager();
        Permission developer = manager.getPermission(org.mcdashboard.web.HttpApi.DEVELOPER_PERMISSION);
        for (Dashboard d : dashboards) {
            String node = d.permission();
            if (manager.getPermission(node) == null) {
                manager.addPermission(new Permission(node, "See the " + d.id() + " dashboard", PermissionDefault.FALSE));
            }
            if (d.id().startsWith("dev-") && developer != null && !developer.getChildren().containsKey(node)) {
                developer.getChildren().put(node, true);
                developer.recalculatePermissibles();
            }
        }
    }

    /** Adds the permission nodes the snapshots must watch for dashboards and developer mode. */
    private void addDashboardPermissionNodes(List<Dashboard> dashboards) {
        java.util.Set<String> nodes = new java.util.HashSet<>(permissionNodes);
        nodes.add(org.mcdashboard.web.HttpApi.DEVELOPER_PERMISSION);
        for (Dashboard d : dashboards) if (d.permission() != null) nodes.add(d.permission());
        permissionNodes = nodes;
    }

    /** Main thread: a dashboard was written by the app, so refresh its permission and the online players' snapshots. */
    private void dashboardsChanged() {
        if (!isEnabled()) return;
        Bukkit.getScheduler().runTask(this, () -> {
            List<Dashboard> all = dashboardStore.all();
            addDashboardPermissionNodes(all);
            registerDashboardPermissions(all);
            Bukkit.getOnlinePlayers().forEach(this::snapshot);
        });
    }

    /** Signing secret, generated on first start and readable by the server user only. */
    private byte[] secret() throws IOException {
        Path file = getDataFolder().toPath().resolve("secret.key");
        if (Files.exists(file)) return Base64.getDecoder().decode(Files.readString(file).trim());
        byte[] key = new byte[48];
        new SecureRandom().nextBytes(key);
        Files.createDirectories(file.getParent());
        Files.writeString(file, Base64.getEncoder().encodeToString(key), StandardOpenOption.CREATE_NEW);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // non-POSIX filesystem
        }
        return key;
    }

    /** Publishes device revocations to the proxy stream ({@code events.token}) and to the configured webhooks. */
    /** The loopback API address the tunnel uses: the bind address when {@code server.host} is set, else loopback. */
    private static String localHost(FileConfiguration cfg) {
        String host = cfg.getString("server.host", "");
        return host == null || host.isBlank() || host.equals("0.0.0.0") || host.equals("::") ? "127.0.0.1" : host;
    }

    /** Trusted proxies; with the tunnel the plugin's own loopback address is one too, so the client address it forwards is believed. */
    private static java.util.Set<String> trustedProxies(FileConfiguration cfg) {
        java.util.Set<String> out = new java.util.HashSet<>(cfg.getStringList("security.trusted-proxies"));
        if (cfg.getBoolean("tunnel.enabled", false)) out.add(localHost(cfg));
        return out;
    }

    /** Dials out to MCDashboard-proxy when {@code tunnel.enabled}; a bad configuration is reported and the tunnel stays off. */
    /** The tunnel client, or null when it is off. */
    public org.mcdashboard.tunnel.TunnelClient tunnel() {
        return tunnel;
    }

    private void startTunnel(FileConfiguration cfg) {
        if (!cfg.getBoolean("tunnel.enabled", false)) return;
        try {
            java.net.URI uri = org.mcdashboard.tunnel.TunnelClient.validate(cfg.getString("tunnel.url", ""),
                    cfg.getString("tunnel.key", ""), cfg.getBoolean("tunnel.allow-insecure", false));
            tunnel = new org.mcdashboard.tunnel.TunnelClient(uri, cfg.getString("tunnel.key", ""), localHost(cfg), http.port(),
                    live == null ? 0 : live.port(),
                    cfg.getString("server.name") != null ? cfg.getString("server.name") : Bukkit.getServer().getName(),
                    getDescription().getVersion(), eventBus, getLogger()::info, getLogger()::warning);
            tunnel.start();
        } catch (IllegalArgumentException e) {
            getLogger().severe("Tunnel not started: " + e.getMessage());
        }
    }

    private void startEvents(FileConfiguration cfg, Clock clock) {
        org.mcdashboard.events.EventBus bus = new org.mcdashboard.events.EventBus(clock);
        eventBus = bus;
        devices.onRemoved(d -> bus.publish(org.mcdashboard.events.DeviceEvent.REVOKED, d.id(), d.playerUuid()));
        String token = cfg.getString("events.token", "");
        if (token != null && !token.isBlank()) {
            if (token.length() < 16) getLogger().warning("events.token is short; use at least 16 random characters.");
            eventStream = new org.mcdashboard.events.EventStream(bus, 15);
            http.events(eventStream, token);
            getLogger().info("Device events stream enabled at /v1/events.");
        }
        List<org.mcdashboard.events.WebhookDispatcher.Hook> hooks = new java.util.ArrayList<>();
        for (Map<?, ?> m : cfg.getMapList("events.webhooks")) {
            String url = String.valueOf(m.get("url"));
            Object secret = m.get("secret");
            try {
                java.net.URI uri = java.net.URI.create(url);
                if (uri.getHost() == null || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) throw new IllegalArgumentException("not an http(s) URL");
                if (secret == null || String.valueOf(secret).length() < 16) throw new IllegalArgumentException("secret must be at least 16 characters");
                if (uri.getScheme().equals("http") && !isLocal(uri.getHost())) getLogger().warning("Webhook " + url + " uses plain HTTP; use HTTPS unless it stays on a private network.");
                hooks.add(new org.mcdashboard.events.WebhookDispatcher.Hook(uri, String.valueOf(secret)));
            } catch (IllegalArgumentException | NullPointerException e) {
                getLogger().warning("events.webhooks: ignoring " + url + ": " + e.getMessage());
            }
        }
        if (!hooks.isEmpty()) {
            webhooks = new org.mcdashboard.events.WebhookDispatcher(hooks, clock, getDescription().getVersion(), getLogger()::warning);
            webhooks.start(bus);
            getLogger().info(hooks.size() + " webhook(s) receive device events.");
        }
    }

    private static boolean isLocal(String host) {
        return host.equals("localhost") || host.startsWith("127.") || host.equals("::1") || host.startsWith("10.")
                || host.startsWith("192.168.") || host.matches("172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

    @Override
    public void onDisable() {
        if (tunnel != null) tunnel.stop();
        if (webhooks != null) webhooks.stop();
        if (eventStream != null) eventStream.close();
        if (http != null) http.stop();
        if (live != null) live.stop();
        if (sampler != null) sampler.shutdownNow();
        if (history != null) history.close();
    }

    /** The database for history, or null for memory only (also when the database settings are unusable). */
    private org.mcdashboard.history.HistoryStore historyStore(Clock clock, FileConfiguration cfg) {
        var storage = historySettings.storage();
        if (storage.dialect() == null) return null;
        String name = cfg.getString("server.name") != null ? cfg.getString("server.name") : "Minecraft server";
        Path data = getDataFolder().toPath();
        getLogger().info("History is stored in " + storage.type() + " (" + storage.jdbcUrl(data).replaceAll("(?i)(password|pwd)=[^&;]*", "$1=***") + ")");
        return new org.mcdashboard.history.JdbcHistoryStore(storage.dialect(),
                new org.mcdashboard.history.JdbcConnector(storage.jdbcUrl(data), storage.user(), storage.password(), data.resolve("drivers")),
                storage.baseUri(), name, clock);
    }

    public OtpService otps() {
        return otps;
    }

    public DeviceStore devices() {
        return devices;
    }

    public int maxDevices() {
        return maxDevices;
    }
}
