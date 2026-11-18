/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.commands;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.mcdashboard.MCDashboard;
import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.auth.OtpService;
import org.mcdashboard.commands.DashboardCommands;
import org.mcdashboard.qr.QrMap;

import java.util.List;
import java.util.Locale;

/** {@code /mcdashboard link [player]|qr|devices|revoke|developer|tunnel|about}; {@code link <player>} is for admins and the console. */
public final class MCDashboardCommand implements CommandExecutor, TabCompleter {
    private final MCDashboard plugin;
    private final DashboardCommands commands;

    public MCDashboardCommand(MCDashboard plugin, DashboardCommands commands) {
        this.plugin = plugin;
        this.commands = commands;
    }

    private static DashboardCommands.Sender sender(CommandSender sender) {
        return new DashboardCommands.Sender() {
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
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "about" : args[0].toLowerCase(Locale.ROOT);
        DashboardCommands.Sender who = sender(sender);
        switch (sub) {
            case "about" -> {
                commands.about(who);
                return true;
            }
            case "developer" -> {
                commands.developer(who, label, args);
                return true;
            }
            case "tunnel" -> {
                commands.tunnel(who);
                return true;
            }
            default -> {
            }
        }
        if (!commands.running(who)) return true;
        if (sub.equals("link")) {
            OfflinePlayer target = linkTarget(sender, args);
            if (target == null) {
                sender.sendMessage("Usage: /" + label + " link" + (sender instanceof Player ? "" : " <player>"));
                return true;
            }
            String name = target.getName() == null ? args[1] : target.getName();
            if (target instanceof Player targetPlayer) QrMap.destroyFor(targetPlayer, true); // the old map is stale
            String code = plugin.otps().create(target.getUniqueId().toString(), name);
            boolean self = sender instanceof Player me && me.getUniqueId().equals(target.getUniqueId());
            sender.sendMessage((self ? "Your pairing code is " : "The pairing code for " + name + " is ") + OtpService.display(code)
                    + ". Enter it in the MCDashboard app.");
            String link = commands.connectLink(code);
            if (link != null) sender.sendMessage("Or open: " + link);
            if (sender instanceof Player p) {
                TextComponent copy = new TextComponent("[Copy code]");
                copy.setColor(ChatColor.GREEN);
                copy.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, OtpService.display(code)));
                copy.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Copy the code to the clipboard")));
                TextComponent line = new TextComponent(copy);
                if (link != null) {
                    TextComponent qr = new TextComponent("  [Get QR map]");
                    qr.setColor(ChatColor.AQUA);
                    qr.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mcdashboard qr"));
                    qr.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("A map with a QR code only you can see")));
                    line.addExtra(qr);
                }
                p.spigot().sendMessage(line);
                sender.sendMessage("§7The code works once and expires in " + plugin.otpTimeoutSeconds() / 60 + " min.");
            }
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        switch (sub) {
            case "qr" -> {
                if (player.getInventory().firstEmpty() == -1) {
                    sender.sendMessage("§cMake room in your inventory for the QR map, then run /" + label + " qr again.");
                    return true;
                }
                QrMap.destroyFor(player, true); // one live map per player
                String code = plugin.otps().create(player.getUniqueId().toString(), player.getName());
                String link = commands.connectLink(code);
                if (link == null) {
                    sender.sendMessage("QR codes need server.public-url in the plugin config. Use the code instead: " + OtpService.display(code));
                    return true;
                }
                long seconds = plugin.otpTimeoutSeconds() < 0 ? 300 : Math.min(300, plugin.otpTimeoutSeconds());
                player.getInventory().addItem(QrMap.create(player, link, System.currentTimeMillis() + seconds * 1000));
                sender.sendMessage("§aHere is your QR map. Scan it with the MCDashboard app; it self-destructs in "
                        + Math.max(1, seconds / 60) + " min or as soon as you drop or store it.");
            }
            case "devices" -> {
                List<DeviceStore.Device> list = plugin.devices().devicesOf(player.getUniqueId().toString());
                if (list.isEmpty()) sender.sendMessage("No devices linked. Use /" + label + " qr to link one.");
                else sender.sendMessage("§6Devices with access to your data §7(" + list.size() + "):");
                for (DeviceStore.Device d : list) {
                    String seen = plugin.devices().lastSeen(d.id()).map(t -> "last seen " + DashboardCommands.ago(t)).orElse("not seen since the server started");
                    TextComponent line = new TextComponent("§e" + d.label() + " §7- " + (d.client() == null ? "unknown app" : d.client())
                            + ", linked " + DashboardCommands.ago(d.createdMs()) + ", " + seen + "  ");
                    TextComponent revoke = new TextComponent("[Revoke]");
                    revoke.setColor(ChatColor.RED);
                    revoke.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/mcdashboard revoke " + d.id()));
                    revoke.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Remove this device's access (press Enter to confirm)")));
                    line.addExtra(revoke);
                    player.spigot().sendMessage(line);
                }
            }
            case "revoke" -> {
                if (args.length < 2) {
                    sender.sendMessage("Usage: /" + label + " revoke <device id>");
                    return true;
                }
                commands.revoke(who, label, args, player.getUniqueId().toString());
            }
            default -> sender.sendMessage(DashboardCommands.usage(label));
        }
        return true;
    }

    /** A player links themselves; an admin (or the console) can link any player by name. */
    private OfflinePlayer linkTarget(CommandSender sender, String[] args) {
        if (args.length > 1 && sender.hasPermission("mcdashboard.admin")) return Bukkit.getOfflinePlayer(args[1]);
        return sender instanceof Player p ? p : null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? DashboardCommands.SUBCOMMANDS
                : args.length == 2 && args[0].equalsIgnoreCase("developer") ? List.of("on", "off") : List.of();
    }
}
