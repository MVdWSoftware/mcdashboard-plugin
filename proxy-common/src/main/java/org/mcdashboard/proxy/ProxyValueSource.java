/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import org.mcdashboard.placeholder.ExpansionLookup;
import org.mcdashboard.placeholder.ValueSource;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The built-in network placeholders: the proxies have no PlaceholderAPI, so the values a network dashboard needs are
 * answered here. Everything is a cached or in-memory read, never a network call, so any thread may call it.
 *
 * <pre>
 * %proxy_online%  %proxy_max%  %proxy_servers%  %proxy_version%  %proxy_platform%  %proxy_uptime%
 * %proxy_server_&lt;name&gt;_online%  %proxy_server_&lt;name&gt;_up%
 * %proxy_player_name%  %proxy_player_ping%  %proxy_player_server%      (the paired player only)
 * %backend_&lt;name&gt;_&lt;id&gt;%                                          (federation, when configured)
 * </pre>
 */
public final class ProxyValueSource implements ValueSource {
    /** Placeholder names are matched this strictly before a template is looked at. */
    private static final Pattern TEMPLATE = Pattern.compile("%[A-Za-z0-9_.\\-]{1,200}%");
    private static final String SERVER_PREFIX = "server_";

    private final ProxyAdapter adapter;
    private final ServerPinger pinger;
    private final Federation federation;
    private final Clock clock;
    private final long startedMs;

    /**
     * Creates a new value source with the given proxy adapter components. {@code federation} may be null.
     * {@code startedMs} is when the proxy started (for {@code proxy_uptime}).
     *
     * @param adapter The proxy adapter for server and player queries.
     * @param pinger The server reachability checker.
     * @param federation The federation manager for cross-backend placeholders; may be null.
     * @param clock The clock used for time-based operations.
     * @param startedMs When the proxy process started, in milliseconds since epoch.
     */
    public ProxyValueSource(ProxyAdapter adapter, ServerPinger pinger, Federation federation, Clock clock, long startedMs) {
        this.adapter = adapter;
        this.pinger = pinger;
        this.federation = federation;
        this.clock = clock;
        this.startedMs = startedMs;
    }

    @Override
    public String resolve(String template, String playerUuid) {
        if (template == null || !TEMPLATE.matcher(template).matches()) return null;
        String key = template.substring(1, template.length() - 1);
        try {
            if (key.startsWith("backend_")) return federation == null ? null : federation.value(key.substring("backend_".length()));
            if (!key.startsWith("proxy_")) return null;
            String k = key.substring("proxy_".length());
            return switch (k) {
                case "online" -> String.valueOf(adapter.onlinePlayers());
                case "max" -> String.valueOf(Math.max(0, adapter.maxPlayers())); // BungeeCord reports -1 for no limit
                case "servers" -> String.valueOf(adapter.servers().size());
                case "version" -> adapter.proxyVersion();
                case "platform" -> adapter.platformName();
                case "uptime" -> String.valueOf(Math.max(0, (clock.millis() - startedMs) / 1000));
                case "player_name", "player_ping", "player_server" -> player(k, playerUuid);
                default -> k.startsWith(SERVER_PREFIX) ? server(k.substring(SERVER_PREFIX.length())) : null;
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Only the paired player's own values: without a player (a PUBLIC read) there is nothing to say. */
    private String player(String k, String playerUuid) {
        if (playerUuid == null) return null;
        UUID id;
        try {
            id = UUID.fromString(playerUuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Optional<ProxyAdapter.PlayerView> p = adapter.player(id);
        if (p.isEmpty()) return null; // offline: these are live values
        return switch (k) {
            case "player_name" -> p.get().name();
            case "player_ping" -> String.valueOf(p.get().pingMs());
            default -> p.get().server();
        };
    }

    /** {@code <name>_online} and {@code <name>_up}; the name may itself contain underscores. */
    private String server(String rest) {
        for (String suffix : List.of("_online", "_up")) {
            if (!rest.toLowerCase(Locale.ROOT).endsWith(suffix) || rest.length() == suffix.length()) continue;
            String name = rest.substring(0, rest.length() - suffix.length());
            for (ProxyAdapter.ServerInfo s : adapter.servers()) {
                if (!s.name().equalsIgnoreCase(name)) continue;
                if (suffix.equals("_online")) return String.valueOf(s.players());
                Boolean up = pinger.isUp(s.name());
                return up == null ? null : String.valueOf(up);
            }
        }
        return null;
    }

    /**
     * What the catalog may list for {@code proxy} (the network wide and per server placeholders; the player ones are not
     * PUBLIC) and {@code backend}; null for any other expansion.
     *
     * @return A lookup function that returns placeholder lists by identifier, or null for unknown identifiers.
     */
    public ExpansionLookup lookup() {
        return id -> switch (id) {
            case "proxy" -> proxyPlaceholders();
            case "backend" -> federation == null ? List.of() : federation.templates();
            default -> null;
        };
    }

    List<String> proxyPlaceholders() {
        List<String> out = new ArrayList<>(List.of("%proxy_online%", "%proxy_max%", "%proxy_servers%", "%proxy_version%",
                "%proxy_platform%", "%proxy_uptime%"));
        for (ProxyAdapter.ServerInfo s : adapter.servers()) {
            out.add("%proxy_server_" + s.name() + "_online%");
            out.add("%proxy_server_" + s.name() + "_up%");
        }
        return out;
    }
}
