/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import org.mcdashboard.dashboard.Languages;
import org.mcdashboard.events.WebhookDispatcher;
import org.mcdashboard.history.HistorySettings;
import org.mcdashboard.config.ConfigView;
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
                boolean discoveryOnly, String host, int port, boolean polling, int websocketPort, int maxLiveConnections,
                boolean requireAuthentication, int maxDevices, long otpTimeoutSeconds, long accessTokenSeconds,
                long refreshTokenSeconds, int authAttemptsPerMinute, long minIntervalMs, List<String> allowedClients,
                String minClientVersion, int requestsPerMinute, int anonymousRequestsPerMinute, Set<String> trustedProxies,
                HistorySettings history, boolean tunnelEnabled, String tunnelUrl, String tunnelKey,
                boolean tunnelAllowInsecure, String eventsToken, List<WebhookDispatcher.Hook> webhooks,
                boolean developerMode, String licenseId) {

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
                host == null ? "" : host,
                cfg.getInt("server.port", 1553),
                polling,
                websocketPort,
                cfg.getInt("server.websocket.max-connections", 200),
                cfg.getBoolean("security.require-authentication", false),
                cfg.getInt("security.max-devices", 5),
                cfg.getLong("security.otp-timeout", 300),
                cfg.getLong("security.access-token-timeout", 900),
                cfg.getLong("security.refresh-token-timeout", 31_536_000),
                Math.max(1, cfg.getInt("security.auth-attempts-per-minute", 10)),
                cfg.getLong("security.min-interval", 1) * 1000,
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
                blankToNull(cfg.getString("license.id", "")));
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
        return host == null || host.isBlank() || host.equals("0.0.0.0") || host.equals("::") ? "127.0.0.1" : host;
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
