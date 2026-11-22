/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.nukkit;

import org.mcdashboard.MCDashboardCore;
import org.mcdashboard.commands.DashboardCommands;
import org.powernukkitx.Player;
import org.powernukkitx.command.Command;
import org.powernukkitx.command.CommandSender;
import org.powernukkitx.plugin.PluginBase;

import java.util.Locale;

/** Publishes an allowlist of PlaceholderAPI placeholders to the MCDashboard app, on PowerNukkitX. */
public final class MCDashboardNukkit extends PluginBase {
    /** Creates the plugin; PowerNukkitX instantiates it once on start-up. */
    public MCDashboardNukkit() {
    }

    private MCDashboardCore core;
    private DashboardCommands commands;

    @Override
    public void onEnable() {
        core = new MCDashboardCore(new NukkitPlatform(this), new NukkitYaml(), getDataFolder().toPath());
        commands = new DashboardCommands(core);
        var papi = org.powernukkitx.placeholderapi.PlaceholderAPI.get();
        if (papi != null) NukkitStatsPlaceholders.register(papi::register, core::stats);
        core.start();
    }

    @Override
    public void onDisable() {
        if (core != null) core.stop();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("mcdashboard")) return false;
        DashboardCommands.Sender who = new DashboardCommands.Sender() {
            @Override
            public void send(String message) {
                sender.sendMessage(message);
            }

            @Override
            public boolean hasPermission(String node) {
                return sender.hasPermission(node);
            }

            @Override
            public String playerUuid() {
                return sender instanceof Player p ? p.getUniqueId().toString() : null;
            }
        };
        String sub = args.length == 0 ? "about" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "about" -> commands.about(who, label);
            case "developer" -> commands.developer(who, label, args);
            case "tunnel" -> commands.tunnel(who);
            case "link", "qr" -> link(sender, who, label, args);
            case "devices" -> {
                if (commands.running(who)) {
                    if (who.playerUuid() == null) commands.playersOnly(who);
                    else commands.devices(who, label, who.playerUuid());
                }
            }
            case "revoke" -> {
                if (commands.running(who)) {
                    if (who.playerUuid() == null) commands.playersOnly(who);
                    else commands.revoke(who, label, args, who.playerUuid());
                }
            }
            default -> commands.usageOverview(who, label, DashboardCommands.SUBCOMMANDS);
        }
        return true;
    }

    /** A player links themselves; an admin (or the console) can link any online player by name. QR maps do not exist on Bedrock, so {@code qr} gives the code and link too. */
    private void link(CommandSender sender, DashboardCommands.Sender who, String label, String[] args) {
        if (!commands.running(who)) return;
        Player target = null;
        if (args.length > 1 && sender.hasPermission(DashboardCommands.ADMIN_PERMISSION)) {
            target = getServer().getPlayerExact(args[1]);
            if (target == null) {
                commands.notOnline(who, args[1]);
                return;
            }
        } else if (sender instanceof Player p) {
            target = p;
        }
        if (target == null) {
            commands.usage(who, label, "link <player>");
            return;
        }
        commands.link(who, target.getUniqueId().toString(), target.getName());
        if (sender instanceof Player) commands.expiryNote(who);
    }
}
