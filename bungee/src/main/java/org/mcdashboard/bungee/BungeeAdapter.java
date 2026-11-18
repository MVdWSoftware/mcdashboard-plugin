/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ListenerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;
import org.mcdashboard.proxy.ProxyAdapter;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** BungeeCord behind {@link ProxyAdapter}. */
final class BungeeAdapter implements ProxyAdapter {
    private final Plugin plugin;

    BungeeAdapter(Plugin plugin) {
        this.plugin = plugin;
    }

    private static ProxyServer proxy() {
        return ProxyServer.getInstance();
    }

    private static ListenerInfo listener() {
        var listeners = proxy().getConfig().getListeners();
        return listeners.isEmpty() ? null : listeners.iterator().next();
    }

    @Override
    public String platformName() {
        return "BungeeCord";
    }

    @Override
    public String proxyVersion() {
        return proxy().getVersion();
    }

    /** The listener's MOTD uses {@code &} colour codes; the server list shows {@code §}. */
    @Override
    public String motd() {
        ListenerInfo l = listener();
        return l == null ? "" : net.md_5.bungee.api.ChatColor.translateAlternateColorCodes('&', l.getMotd());
    }

    @Override
    public int port() {
        ListenerInfo l = listener();
        return l != null && l.getSocketAddress() instanceof InetSocketAddress a ? a.getPort() : 0;
    }

    @Override
    public int onlinePlayers() {
        return proxy().getOnlineCount();
    }

    @Override
    public int maxPlayers() {
        return proxy().getConfig().getPlayerLimit();
    }

    /** A server on a unix socket has no host and port to check, so it is left out. */
    @Override
    public List<ServerInfo> servers() {
        List<ServerInfo> out = new ArrayList<>();
        for (var s : proxy().getServers().values()) {
            if (s.getSocketAddress() instanceof InetSocketAddress a) out.add(new ServerInfo(s.getName(), a.getHostString(), a.getPort(), s.getPlayers().size()));
        }
        return out;
    }

    @Override
    public List<PlayerView> players() {
        return proxy().getPlayers().stream().map(BungeeAdapter::view).toList();
    }

    @Override
    public Optional<PlayerView> player(UUID uuid) {
        return Optional.ofNullable(proxy().getPlayer(uuid)).map(BungeeAdapter::view);
    }

    @Override
    public Optional<PlayerView> playerByName(String name) {
        return Optional.ofNullable(proxy().getPlayer(name)).map(BungeeAdapter::view);
    }

    private static PlayerView view(ProxiedPlayer p) {
        return new PlayerView(p.getUniqueId(), p.getName(), Math.max(0, p.getPing()),
                p.getServer() == null ? "" : p.getServer().getInfo().getName());
    }

    /** BungeeCord cannot tell a denied node from an undefined one, so a missing permission is "undefined". */
    @Override
    public Boolean permission(UUID player, String node) {
        ProxiedPlayer p = proxy().getPlayer(player);
        return p != null && p.hasPermission(node) ? Boolean.TRUE : null;
    }

    @Override
    public void message(UUID player, String text) {
        ProxiedPlayer p = proxy().getPlayer(player);
        if (p == null) return;
        for (String line : text.split("\n")) p.sendMessage(TextComponent.fromLegacy(line));
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMs, long periodMs) {
        var scheduler = proxy().getScheduler();
        var scheduled = periodMs > 0 ? scheduler.schedule(plugin, task, delayMs, periodMs, TimeUnit.MILLISECONDS)
                : scheduler.schedule(plugin, task, delayMs, TimeUnit.MILLISECONDS);
        return scheduled::cancel;
    }
}
