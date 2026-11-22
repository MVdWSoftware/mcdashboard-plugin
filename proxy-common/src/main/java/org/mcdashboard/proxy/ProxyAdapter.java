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
    /**
     * A backend server the proxy knows.
     *
     * @param name The server's display name.
     * @param host The hostname or IP address.
     * @param port The port number.
     * @param players The current player count.
     */
    record ServerInfo(String name, String host, int port, int players) {
    }

    /**
     * What the proxy knows of a connected player; {@code pingMs} is the proxy to client latency.
     *
     * @param uuid The player's unique identifier.
     * @param name The player's display name.
     * @param pingMs The round-trip latency in milliseconds.
     * @param server The server the player is currently on.
     */
    record PlayerView(UUID uuid, String name, long pingMs, String server) {
    }

    /**
     * "Velocity" or "BungeeCord".
     *
     * @return The platform name.
     */
    String platformName();

    /**
     * The proxy software's own version string.
     *
     * @return The version string.
     */
    String proxyVersion();

    /**
     * The proxy's message of the day.
     *
     * @return The MOTD text.
     */
    String motd();

    /**
     * The port players connect to, or 0 when unknown.
     *
     * @return The connection port.
     */
    int port();

    /**
     * The current number of online players.
     *
     * @return The player count.
     */
    int onlinePlayers();

    /**
     * The configured player limit; 0 or less when the proxy has none.
     *
     * @return The maximum player count.
     */
    int maxPlayers();

    /**
     * All servers known to the proxy.
     *
     * @return An unmodifiable list of all registered servers.
     */
    List<ServerInfo> servers();

    /**
     * All connected players.
     *
     * @return An unmodifiable list of all online players.
     */
    List<PlayerView> players();

    /**
     * Looks up a player by their UUID.
     *
     * @param uuid The player's unique identifier.
     * @return A present optional with the player view, or empty if offline.
     */
    Optional<PlayerView> player(UUID uuid);

    /**
     * Looks up a player by display name (case-insensitive).
     *
     * @param name The player's display name.
     * @return A present optional with the player view, or empty if not found.
     */
    Optional<PlayerView> playerByName(String name);

    /**
     * What the proxy's permission check says: TRUE (granted), FALSE (explicitly denied) or null (undefined).
     * BungeeCord cannot tell denied from undefined and answers TRUE or null. LuckPerms, when installed, backs this
     * check on both proxies, so it needs no code of its own here.
     *
     * @param player The player whose permissions to check.
     * @param node The permission node to test.
     * @return TRUE if granted, FALSE if explicitly denied, or null if undefined.
     */
    Boolean permission(UUID player, String node);

    /**
     * Sends a chat line to an online player (legacy colour codes allowed); ignored when they are gone.
     *
     * @param player The UUID of the target player.
     * @param text The message text, supporting legacy colour codes.
     */
    void message(UUID player, String text);

    /**
     * Runs the task on the proxy's scheduler after {@code delayMs}, and then every {@code periodMs} when that is above 0.
     *
     * @param task The runnable to schedule.
     * @param delayMs Milliseconds before the first execution; 0 for immediate.
     * @param periodMs Milliseconds between repeated executions; 0 for one-shot only.
     * @return A handle that can cancel the scheduled task.
     */
    Cancellable schedule(Runnable task, long delayMs, long periodMs);

    /** A scheduled task that can be cancelled. */
    @FunctionalInterface
    interface Cancellable {
        /** Cancels the task, doing nothing when it has already run or was cancelled. */
        void cancel();
    }
}
