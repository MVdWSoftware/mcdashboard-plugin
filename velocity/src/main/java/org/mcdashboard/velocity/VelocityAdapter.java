/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.velocity;

import com.velocitypowered.api.permission.Tristate;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.mcdashboard.proxy.ProxyAdapter;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Velocity behind {@link ProxyAdapter}. */
final class VelocityAdapter implements ProxyAdapter {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private final ProxyServer proxy;
    private final Object plugin;

    VelocityAdapter(ProxyServer proxy, Object plugin) {
        this.proxy = proxy;
        this.plugin = plugin;
    }

    @Override
    public String platformName() {
        return "Velocity";
    }

    @Override
    public String proxyVersion() {
        return proxy.getVersion().getVersion();
    }

    @Override
    public String motd() {
        return LEGACY.serialize(proxy.getConfiguration().getMotd());
    }

    @Override
    public int port() {
        return proxy.getBoundAddress().getPort();
    }

    @Override
    public int onlinePlayers() {
        return proxy.getPlayerCount();
    }

    @Override
    public int maxPlayers() {
        return proxy.getConfiguration().getShowMaxPlayers();
    }

    @Override
    public List<ServerInfo> servers() {
        List<ServerInfo> out = new ArrayList<>();
        for (RegisteredServer s : proxy.getAllServers()) {
            InetSocketAddress address = s.getServerInfo().getAddress();
            out.add(new ServerInfo(s.getServerInfo().getName(), address.getHostString(), address.getPort(), s.getPlayersConnected().size()));
        }
        return out;
    }

    @Override
    public List<PlayerView> players() {
        return proxy.getAllPlayers().stream().map(VelocityAdapter::view).toList();
    }

    @Override
    public Optional<PlayerView> player(UUID uuid) {
        return proxy.getPlayer(uuid).map(VelocityAdapter::view);
    }

    @Override
    public Optional<PlayerView> playerByName(String name) {
        return proxy.getPlayer(name).map(VelocityAdapter::view);
    }

    private static PlayerView view(Player p) {
        return new PlayerView(p.getUniqueId(), p.getUsername(), Math.max(0, p.getPing()),
                p.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse(""));
    }

    /** Velocity's own check, which LuckPerms replaces when it is installed; UNDEFINED stays null so the defaults apply. */
    @Override
    public Boolean permission(UUID player, String node) {
        return proxy.getPlayer(player).map(p -> {
            Tristate t = p.getPermissionValue(node);
            return t == Tristate.UNDEFINED ? null : Boolean.valueOf(t.asBoolean());
        }).orElse(null);
    }

    @Override
    public void message(UUID player, String text) {
        proxy.getPlayer(player).ifPresent(p -> {
            for (String line : text.split("\n")) p.sendMessage(LEGACY.deserialize(line));
        });
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMs, long periodMs) {
        var builder = proxy.getScheduler().buildTask(plugin, task).delay(delayMs, TimeUnit.MILLISECONDS);
        if (periodMs > 0) builder = builder.repeat(periodMs, TimeUnit.MILLISECONDS);
        var scheduled = builder.schedule();
        return scheduled::cancel;
    }
}
