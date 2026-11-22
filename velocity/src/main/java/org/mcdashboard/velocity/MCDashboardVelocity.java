/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.mcdashboard.commands.DashboardCommands;
import org.mcdashboard.platform.Log;
import org.mcdashboard.proxy.ProxyRuntime;
import org.mcdashboard.proxy.ProxyYaml;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;

/** The network dashboard for the MCDashboard app, on Velocity. The descriptor is {@code velocity-plugin.json}. */
@Plugin(id = "mcdashboard", name = "MCDashboard", authors = "MVdW-Software")
public final class MCDashboardVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;
    private ProxyRuntime runtime;

    /**
     * Creates the plugin instance injected by Velocity.
     *
     * @param proxy The proxy server this plugin runs on.
     * @param logger The plugin's logger.
     * @param dataDir The plugin's data directory.
     */
    @Inject
    public MCDashboardVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    /**
     * Starts the runtime and registers the {@code /mcdashboard} command.
     *
     * @param event The proxy initialize event.
     */
    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        String version = proxy.getPluginManager().fromInstance(this).flatMap(c -> c.getDescription().getVersion()).orElse("dev");
        Log log = new Log() {
            @Override
            public void info(String message) {
                logger.info("{}", message);
            }

            @Override
            public void warning(String message) {
                logger.warn("{}", message);
            }

            @Override
            public void severe(String message) {
                logger.error("{}", message);
            }
        };
        runtime = new ProxyRuntime(new VelocityAdapter(proxy, this), log, version, dataDir, new ProxyYaml());
        runtime.start();
        var meta = proxy.getCommandManager().metaBuilder("mcdashboard").aliases("mcd", "dashboard").plugin(this).build();
        proxy.getCommandManager().register(meta, new Command());
    }

    /**
     * Stops the runtime when the proxy shuts down.
     *
     * @param event The shutdown event.
     */
    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (runtime != null) runtime.stop();
    }

    /**
     * A player joined: refresh their permission snapshot.
     *
     * @param event The post-login event.
     */
    @Subscribe
    public void onLogin(PostLoginEvent event) {
        if (runtime != null) runtime.playerChanged(event.getPlayer().getUniqueId(), true);
    }

    /**
     * A player disconnected: refresh their permission snapshot.
     *
     * @param event The disconnect event.
     */
    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (runtime != null) runtime.playerChanged(event.getPlayer().getUniqueId(), false);
    }

    private final class Command implements SimpleCommand {
        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            DashboardCommands.Sender who = new DashboardCommands.Sender() {
                @Override
                public void send(String message) {
                    for (String line : message.split("\n")) source.sendMessage(LegacyComponentSerializer.legacySection().deserialize(line));
                }

                @Override
                public boolean hasPermission(String node) {
                    return source.hasPermission(node);
                }

                @Override
                public String playerUuid() {
                    return source instanceof Player p ? p.getUniqueId().toString() : null;
                }
            };
            if (runtime != null) runtime.execute(who, invocation.alias(), invocation.arguments());
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            return runtime == null ? List.of() : runtime.complete(invocation.arguments());
        }
    }
}
