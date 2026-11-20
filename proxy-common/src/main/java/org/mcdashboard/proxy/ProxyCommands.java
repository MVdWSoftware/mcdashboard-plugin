/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import org.mcdashboard.MCDashboardCore;
import org.mcdashboard.commands.DashboardCommands;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code /mcdashboard} on a proxy, for both Velocity and BungeeCord: the shared text commands plus {@code reload}. There is no QR
 * map on a proxy (it is a Bukkit item), so {@code qr} gives the one-time code and link like {@code link}.
 */
public final class ProxyCommands {
    /** What the usage line and tab completion offer. */
    public static final List<String> SUBCOMMANDS = List.of("link", "devices", "revoke", "reload", "developer", "tunnel", "about");

    /** Restarts the core: stop, then start again with the files as they are now. */
    public interface Reloader {
        void reload();
    }

    private final ProxyAdapter adapter;
    private final DashboardCommands commands;
    private final Reloader reloader;

    public ProxyCommands(ProxyAdapter adapter, MCDashboardCore core, Reloader reloader) {
        this.adapter = adapter;
        this.commands = new DashboardCommands(core);
        this.reloader = reloader;
    }

    public void execute(DashboardCommands.Sender who, String label, String[] args) {
        String sub = args.length == 0 ? "about" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "about" -> commands.about(who, label);
            case "developer" -> commands.developer(who, label, args);
            case "tunnel" -> commands.tunnel(who);
            case "reload" -> {
                if (!who.hasPermission(DashboardCommands.ADMIN_PERMISSION)) commands.denied(who, DashboardCommands.ADMIN_PERMISSION);
                else {
                    reloader.reload();
                    commands.success(who, "MCDashboard restarted with the files on disk.");
                }
            }
            case "link", "qr" -> link(who, label, args);
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
            default -> commands.usageOverview(who, label, SUBCOMMANDS);
        }
    }

    /** A player links themselves; an admin (or the console) links any online player by name. */
    private void link(DashboardCommands.Sender who, String label, String[] args) {
        if (!commands.running(who)) return;
        String uuid = who.playerUuid();
        String name = null;
        if (args.length > 1 && who.hasPermission(DashboardCommands.ADMIN_PERMISSION)) {
            Optional<ProxyAdapter.PlayerView> target = adapter.playerByName(args[1]);
            if (target.isEmpty()) {
                commands.notOnline(who, args[1]);
                return;
            }
            uuid = target.get().uuid().toString();
            name = target.get().name();
        } else if (uuid != null) {
            name = adapter.player(java.util.UUID.fromString(uuid)).map(ProxyAdapter.PlayerView::name).orElse(uuid);
        }
        if (uuid == null) {
            commands.usage(who, label, "link <player>");
            return;
        }
        commands.link(who, uuid, name);
        if (who.playerUuid() != null) commands.expiryNote(who);
    }

    /** Tab completion for the first argument. */
    public static List<String> complete(String[] args) {
        if (args.length <= 1) return SUBCOMMANDS;
        if (args.length == 2 && args[0].equalsIgnoreCase("developer")) return List.of("on", "off");
        return List.of();
    }
}
