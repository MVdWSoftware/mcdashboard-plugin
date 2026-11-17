package org.mcdashboard.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.mcdashboard.MCDashboard;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.auth.OtpService;
import org.mcdashboard.qr.QrMap;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** {@code /mcdashboard link [player]|devices|revoke|about}; {@code link <player>} is for admins and the console. */
public final class MCDashboardCommand implements CommandExecutor, TabCompleter {
    private final MCDashboard plugin;

    public MCDashboardCommand(MCDashboard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "about" : args[0].toLowerCase();
        if (sub.equals("about")) {
            sender.sendMessage("MCDashboard v" + plugin.getDescription().getVersion() + " - https://mcdashboard.org");
            return true;
        }
        if (sub.equals("developer")) {
            if (!sender.hasPermission("mcdashboard.admin")) {
                sender.sendMessage("You need mcdashboard.admin to change developer mode.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage("Developer mode is " + (plugin.developerMode() ? "on" : "off") + ".");
                sender.sendMessage("Use /" + label + " developer on|off. Players also need mcdashboard.developer to upload.");
                return true;
            }
            String value = args[1].toLowerCase();
            if (!value.equals("on") && !value.equals("off")) {
                sender.sendMessage("Usage: /" + label + " developer on|off");
                return true;
            }
            plugin.setDeveloperMode(value.equals("on"));
            sender.sendMessage("Developer mode is now " + value + ". Players with mcdashboard.developer can upload a dashboard.");
            return true;
        }
        if (sub.equals("tunnel")) {
            if (!sender.hasPermission("mcdashboard.admin")) {
                sender.sendMessage("You need mcdashboard.admin to see the tunnel.");
                return true;
            }
            var t = plugin.tunnel();
            if (t == null) {
                sender.sendMessage("The tunnel is off (tunnel.enabled in config.yml).");
                return true;
            }
            var st = t.status();
            if (st.connected()) {
                sender.sendMessage("§aTunnel connected§7 for " + ago(st.connectedSinceMs()).replace(" ago", "") + ", ping " + st.rttMs() + " ms, "
                        + st.channels() + " live stream(s).");
            } else {
                sender.sendMessage("§cTunnel down§7, attempt " + st.attempts() + (st.lastError() == null ? "" : ": " + st.lastError()
                        + " (" + ago(st.lastErrorMs()) + ")") + ". It keeps retrying.");
            }
            return true;
        }
        if (plugin.otps() == null) {
            sender.sendMessage("MCDashboard is not running. Ask an admin to enable it in config.yml.");
            return true;
        }
        if (sub.equals("link")) {
            OfflinePlayer target = linkTarget(sender, args);
            if (target == null) {
                sender.sendMessage("Usage: /" + label + " link" + (sender instanceof Player ? "" : " <player>"));
                return true;
            }
            String name = target.getName() == null ? args[1] : target.getName();
            String code = plugin.otps().create(target.getUniqueId().toString(), name);
            sender.sendMessage("Your pairing code is " + OtpService.display(code) + ". Enter it in the MinecraftDashboard app.");
            String link = connectLink(code);
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
                sender.sendMessage("§7The code works once and expires in " + plugin.getConfig().getLong("security.otp-timeout", 300) / 60 + " min.");
            }
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player.");
            return true;
        }
        switch (sub) {
            case "qr" -> {
                // A fresh code for the player themselves, as a map only they can read.
                String code = plugin.otps().create(player.getUniqueId().toString(), player.getName());
                String link = connectLink(code);
                if (link == null) {
                    sender.sendMessage("QR codes need server.public-url in the plugin config. Use the code instead: " + OtpService.display(code));
                    return true;
                }
                var leftover = player.getInventory().addItem(QrMap.create(player, link));
                leftover.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
                sender.sendMessage("§aHere is your QR map. Scan it with the MinecraftDashboard app, then throw it away.");
            }
            case "devices" -> {
                List<DeviceStore.Device> list = plugin.devices().devicesOf(player.getUniqueId().toString());
                if (list.isEmpty()) sender.sendMessage("No devices linked. Use /" + label + " qr to link one.");
                else sender.sendMessage("§6Devices with access to your data §7(" + list.size() + "):");
                for (DeviceStore.Device d : list) {
                    String seen = plugin.devices().lastSeen(d.id()).map(t -> "last seen " + ago(t)).orElse("not seen since the server started");
                    TextComponent line = new TextComponent("§e" + d.label() + " §7- " + (d.client() == null ? "unknown app" : d.client())
                            + ", linked " + ago(d.createdMs()) + ", " + seen + "  ");
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
                boolean mine = plugin.devices().get(args[1]).filter(d -> d.playerUuid().equals(player.getUniqueId().toString())).isPresent();
                sender.sendMessage(mine && plugin.devices().remove(args[1]) ? "Device removed." : "Unknown device.");
            }
            default -> sender.sendMessage("Usage: /" + label + " <link|qr|devices|revoke|developer|tunnel|about>");
        }
        return true;
    }

    /** "5 min ago", "2 h ago", "3 days ago". */
    static String ago(long ms) {
        long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
        if (s < 90) return "just now";
        if (s < 5400) return Math.round(s / 60.0) + " min ago";
        if (s < 129600) return Math.round(s / 3600.0) + " h ago";
        return Math.round(s / 86400.0) + " days ago";
    }

    /** The deep link for a code, or null when the server has no public address configured. */
    private String connectLink(String code) {
        String publicUrl = plugin.getConfig().getString("server.public-url", "");
        if (publicUrl == null || publicUrl.isBlank()) return null;
        return "mcdashboard://connect?url=" + URLEncoder.encode(publicUrl.trim(), StandardCharsets.UTF_8) + "&code=" + code;
    }

    /** A player links themselves; an admin (or the console) can link any player by name. */
    private OfflinePlayer linkTarget(CommandSender sender, String[] args) {
        if (args.length > 1 && sender.hasPermission("mcdashboard.admin")) return Bukkit.getOfflinePlayer(args[1]);
        return sender instanceof Player p ? p : null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return args.length == 1 ? List.of("link", "qr", "devices", "revoke", "developer", "tunnel", "about")
                : args.length == 2 && args[0].equalsIgnoreCase("developer") ? List.of("on", "off") : List.of();
    }
}
