/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The little the proxy-neutral code needs from Velocity or BungeeCord. Implementations must be safe to call from any
 * thread (both proxies' player and server lists are), and must never block on the network.
 */
public interface ProxyAdapter {
    /** A backend server the proxy knows. */
    record ServerInfo(String name, String host, int port, int players) {
    }

    /** What the proxy knows of a connected player; {@code pingMs} is the proxy to client latency. */
    record PlayerView(UUID uuid, String name, long pingMs, String server) {
    }

    /** "Velocity" or "BungeeCord". */
    String platformName();

    /** The proxy software's own version string. */
    String proxyVersion();

    String motd();

    /** The port players connect to, or 0 when unknown. */
    int port();

    int onlinePlayers();

    /** The configured player limit; 0 or less when the proxy has none. */
    int maxPlayers();

    List<ServerInfo> servers();

    List<PlayerView> players();

    Optional<PlayerView> player(UUID uuid);

    Optional<PlayerView> playerByName(String name);

    /**
     * What the proxy's permission check says: TRUE (granted), FALSE (explicitly denied) or null (undefined).
     * BungeeCord cannot tell denied from undefined and answers TRUE or null. LuckPerms, when installed, backs this
     * check on both proxies, so it needs no code of its own here.
     */
    Boolean permission(UUID player, String node);

    /** Sends a chat line to an online player (legacy colour codes allowed); ignored when they are gone. */
    void message(UUID player, String text);

    /** Runs the task on the proxy's scheduler after {@code delayMs}, and then every {@code periodMs} when that is above 0. */
    Cancellable schedule(Runnable task, long delayMs, long periodMs);

    /** A scheduled task. */
    @FunctionalInterface
    interface Cancellable {
        void cancel();
    }
}
