/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.bungee;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PlayerDisconnectEvent;
import net.md_5.bungee.api.event.PostLoginEvent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.api.plugin.TabExecutor;
import net.md_5.bungee.event.EventHandler;
import org.mcdashboard.commands.DashboardCommands;
import org.mcdashboard.platform.Log;
import org.mcdashboard.proxy.ProxyRuntime;
import org.mcdashboard.proxy.ProxyYaml;

/** The network dashboard for the MCDashboard app, on BungeeCord (and Waterfall). */
public final class MCDashboardBungee extends Plugin implements Listener {
    private ProxyRuntime runtime;

    @Override
    public void onEnable() {
        runtime = new ProxyRuntime(new BungeeAdapter(this), Log.of(getLogger()), getDescription().getVersion(),
                getDataFolder().toPath(), new ProxyYaml());
        runtime.start();
        getProxy().getPluginManager().registerCommand(this, new Cmd());
        getProxy().getPluginManager().registerListener(this, this);
    }

    @Override
    public void onDisable() {
        if (runtime != null) runtime.stop();
    }

    @EventHandler
    public void onLogin(PostLoginEvent event) {
        runtime.playerChanged(event.getPlayer().getUniqueId(), true);
    }

    @EventHandler
    public void onDisconnect(PlayerDisconnectEvent event) {
        runtime.playerChanged(event.getPlayer().getUniqueId(), false);
    }

    private final class Cmd extends Command implements TabExecutor {
        Cmd() {
            super("mcdashboard", null, "dashboard");
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            DashboardCommands.Sender who = new DashboardCommands.Sender() {
                @Override
                public void send(String message) {
                    for (String line : message.split("\n")) sender.sendMessage(TextComponent.fromLegacy(line));
                }

                @Override
                public boolean hasPermission(String node) {
                    return sender.hasPermission(node);
                }

                @Override
                public String playerUuid() {
                    return sender instanceof ProxiedPlayer p ? p.getUniqueId().toString() : null;
                }
            };
            runtime.execute(who, "mcdashboard", args);
        }

        @Override
        public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
            return runtime.complete(args);
        }
    }
}
