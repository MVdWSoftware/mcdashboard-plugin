/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import org.mcdashboard.dashboard.Languages;
import org.mcdashboard.events.WebhookDispatcher;
import org.mcdashboard.history.HistorySettings;
import org.mcdashboard.config.ConfigView;
import org.mcdashboard.placeholder.IntervalOverrides;
import org.mcdashboard.util.PrivateNetwork;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** What {@code config.yml} says, read once at startup with its defaults applied. */
record Settings(boolean enabled, String name, String defaultLanguage, String publicUrl, List<String> endpoints,
                boolean discoveryOnly, String host, int port, boolean polling, int websocketPort, int maxLiveConnections, int workerThreads,
                boolean requireAuthentication, boolean allowPlayerNames, int maxDevices, long otpTimeoutSeconds, long accessTokenSeconds,
                long refreshTokenSeconds, int authAttemptsPerMinute, long minIntervalMs,
                IntervalOverrides minIntervalOverrides, List<String> allowedClients,
                String minClientVersion, int requestsPerMinute, int anonymousRequestsPerMinute, Set<String> trustedProxies,
                HistorySettings history, boolean tunnelEnabled, String tunnelUrl, String tunnelKey,
                boolean tunnelAllowInsecure, String eventsToken, List<WebhookDispatcher.Hook> webhooks,
                boolean developerMode, String licenseId, org.mcdashboard.tls.TlsSupport.Settings tls,
                boolean verifyClients, boolean verifyDeviceOnly, List<String> verifyKinds, List<String> verifyHosts,
                List<Map<String, Object>> verifyKeys, boolean backgroundEnabled, int backgroundRequestsPerMinute, boolean offerStatsToApp,
                int maxDashboards, int maxTiles, String chatPrefix,
                String licenseApiUrl, String licenseHost, int licenseHeartbeatMinutes, boolean errorReports) {

    static final String DEFAULT_CHAT_PREFIX = "&8[&bMCDashboard&8] &r";

    /** Reads the configuration; problems are reported through {@code warn} and replaced by a safe default. */
    static Settings load(ConfigView cfg, Consumer<String> warn) {
        String name = cfg.getString("server.name");
        String host = cfg.getString("server.host", "");
        int websocketPort = cfg.getBoolean("server.websocket.enabled", true) ? cfg.getInt("server.websocket.port", 1554) : 0;
        boolean polling = cfg.getBoolean("server.polling.enabled", true);
        if (!polling && websocketPort <= 0) {
            warn.accept("server.polling.enabled and server.websocket.enabled are both off: no client could read values. Polling stays on.");
            polling = true;
        }
        boolean tunnel = cfg.getBoolean("tunnel.enabled", false);
        Set<String> trusted = new HashSet<>(cfg.getStringList("security.trusted-proxies"));
        // The tunnel forwards the app's address from the loopback API port, so that peer must be believed.
        if (tunnel) trusted.add(localHost(host));
        String eventsToken = blankToNull(cfg.getString("events.token", ""));
        if (eventsToken != null && eventsToken.length() < 16) warn.accept("events.token is short; use at least 16 random characters.");
        return new Settings(
                cfg.getBoolean("server.enabled", false),
                blankToNull(name),
                Languages.normaliseOrDefault(cfg.getString("server.default-language", "en")),
                blankToNull(cfg.getString("server.public-url", "")),
                cfg.getStringList("server.endpoints"),
                cfg.getBoolean("server.discovery-only", false),
                host,
                cfg.getInt("server.port", 1553),
                polling,
                websocketPort,
                cfg.getInt("server.websocket.max-connections", 200),
                Math.max(1, Math.min(256, cfg.getInt("server.worker-threads", 16))),
                cfg.getBoolean("security.require-authentication", true),
                cfg.getBoolean("security.allow-player-names", false),
                cfg.getInt("security.max-devices", 5),
                cfg.getLong("security.otp-timeout", 300),
                positiveSeconds(cfg, "security.access-token-timeout", 900, warn),
                positiveSeconds(cfg, "security.refresh-token-timeout", 31_536_000, warn),
                Math.max(1, cfg.getInt("security.auth-attempts-per-minute", 10)),
                cfg.getLong("security.min-interval", 1) * 1000,
                minIntervalOverrides(cfg, warn),
                cfg.getStringList("security.allowed-clients"),
                cfg.getString("security.min-client-version", ""),
                cfg.getInt("security.requests-per-minute", 120),
                cfg.getInt("security.anonymous-requests-per-minute", 60),
                Set.copyOf(trusted),
                HistorySettings.parse(cfg.getSection("history"), name == null ? "server" : name,
                        msg -> warn.accept("config.yml: " + msg)),
                tunnel,
                cfg.getString("tunnel.url", ""),
                cfg.getString("tunnel.key", ""),
                cfg.getBoolean("tunnel.allow-insecure", false),
                eventsToken,
                webhooks(cfg.getMapList("events.webhooks"), warn),
                cfg.getBoolean("developer.enabled", false),
                blankToNull(cfg.getString("license.id", "")),
                new org.mcdashboard.tls.TlsSupport.Settings(tlsMode(cfg),
                        cfg.getString("server.tls.certificate", ""), cfg.getString("server.tls.key", ""),
                        cfg.getString("server.tls.keystore", ""), cfg.getString("server.tls.keystore-password", ""),
                        cfg.getStringList("server.tls.names")),
                "required".equalsIgnoreCase(cfg.getString("security.client-verification.mode", "off")),
                "device".equalsIgnoreCase(cfg.getString("security.client-verification.min-level", "registered")),
                cfg.getStringList("security.client-verification.kinds"),
                cfg.getStringList("security.client-verification.hosts"),
                cfg.getMapList("security.client-verification.keys").stream().map(Settings::stringKeys).toList(),
                cfg.getBoolean("security.background.enabled", true),
                Math.max(1, cfg.getInt("security.background.requests-per-minute", 30)),
                cfg.getBoolean("stats.offer-to-app", false),
                cap(cfg, "limits.max-dashboards", warn), cap(cfg, "limits.max-tiles", warn),
                cfg.getString("messages.prefix", DEFAULT_CHAT_PREFIX),
                blankToNull(cfg.getString("license.api-url", "https://portal.mcdashboard.org/api")),
                blankToNull(cfg.getString("license.host", "")),
                Math.max(0, cfg.getInt("license.heartbeat-minutes", 60)),
                cfg.getBoolean("diagnostics.error-reports", false));
    }

    /** {@code limits.max-*}: a whole number of at least 1 caps, 0 (or absent) means no cap; negatives and garbage become 0. */
    static int cap(ConfigView cfg, String key, Consumer<String> warn) {
        Object value = cfg.get(key);
        if (value == null) return 0;
        int parsed;
        try {
            parsed = value instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) ? n.intValue()
                    : Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            warn.accept(key + " must be a whole number (0 = no cap), got '" + value + "'. No cap is applied.");
            return 0;
        }
        if (parsed < 0) {
            warn.accept(key + " must not be negative (0 = no cap), got " + parsed + ". No cap is applied.");
            return 0;
        }
        return parsed;
    }

    /**
     * A token lifetime in seconds; a negative value would issue a token without an {@code exp} (never expires) and zero
     * would expire it at once, so anything below one second falls back to the default and the owner is warned.
     */
    static long positiveSeconds(ConfigView cfg, String key, long fallback, Consumer<String> warn) {
        long seconds = cfg.getLong(key, fallback);
        if (seconds < 1) {
            warn.accept(key + " must be at least 1 second, got " + seconds + ". Using " + fallback + ".");
            return fallback;
        }
        return seconds;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringKeys(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    /** {@code messages.prefix} with {@code &} colour codes turned into {@code §}; empty means no prefix. */
    String prefix() {
        return chatPrefix.replace('&', '§');
    }

    /** {@code server.name}, or {@code fallback} when it is not set. */
    String nameOr(String fallback) {
        return name == null ? fallback : name;
    }

    /** The address the plugin reaches its own API on: the bind address when one is set, else loopback. */
    String localHost() {
        return localHost(host);
    }

    private static String localHost(String host) {
        return host.isBlank() || host.equals("0.0.0.0") || host.equals("::") ? "127.0.0.1" : host;
    }

    /** {@code server.tls.mode}; a bare {@code off} is read by YAML 1.1 as the boolean false, so that means off too. */
    private static String tlsMode(ConfigView cfg) {
        Object value = cfg.get("server.tls.mode");
        if (value instanceof Boolean b) return b ? "self-signed" : "off";
        if (value == null) return "self-signed";
        String mode = value.toString().trim().toLowerCase(java.util.Locale.ROOT);
        return mode.isEmpty() ? "self-signed" : mode;
    }

    /** {@code security.min-interval-permissions}: permission nodes that may refresh faster or slower than the default. */
    private static IntervalOverrides minIntervalOverrides(ConfigView cfg, Consumer<String> warn) {
        return IntervalOverrides.parse(cfg.getSection("security.min-interval-permissions"),
                msg -> warn.accept("security.min-interval-permissions: " + msg));
    }

    private static List<WebhookDispatcher.Hook> webhooks(List<Map<?, ?>> entries, Consumer<String> warn) {
        List<WebhookDispatcher.Hook> hooks = new ArrayList<>();
        for (Map<?, ?> m : entries) {
            String url = String.valueOf(m.get("url"));
            Object secret = m.get("secret");
            try {
                URI uri = URI.create(url);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme();
                if (uri.getHost() == null || !(scheme.equals("http") || scheme.equals("https"))) {
                    throw new IllegalArgumentException("not an http(s) URL");
                }
                if (secret == null || String.valueOf(secret).length() < 16) {
                    throw new IllegalArgumentException("secret must be at least 16 characters");
                }
                if (scheme.equals("http") && !PrivateNetwork.isPrivate(uri.getHost())) {
                    warn.accept("Webhook " + url + " uses plain HTTP; use HTTPS unless it stays on a private network.");
                }
                hooks.add(new WebhookDispatcher.Hook(uri, String.valueOf(secret)));
            } catch (IllegalArgumentException e) {
                warn.accept("events.webhooks: ignoring " + url + ": " + e.getMessage());
            }
        }
        return List.copyOf(hooks);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
