/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.commands;

import org.mcdashboard.MCDashboardCore;
import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.auth.OtpService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The text of {@code /mcdashboard}, independent of the server: each platform parses its own command line, builds a
 * {@link Sender} and calls the sub-command; a platform that can show clickable text or maps adds that on top.
 */
public final class DashboardCommands {
    /** The sub-commands, for tab completion and the usage line. */
    public static final List<String> SUBCOMMANDS = List.of("link", "qr", "devices", "revoke", "developer", "tunnel", "about");
    /** The permission node that gates the admin sub-commands. */
    public static final String ADMIN_PERMISSION = "mcdashboard.admin";

    /** Whoever ran the command: a player or the console. */
    public interface Sender {
        /** Sends a chat message to this sender.
         *
         * @param message The text to send.
         */
        void send(String message);

        /** Whether this sender holds the given permission node.
         *
         * @param node The permission node to test.
         * @return True when the sender holds it.
         */
        boolean hasPermission(String node);

        /** The player's UUID, or null for the console.
         *
         * @return The player's UUID as a string, or null for the console.
         */
        String playerUuid();

        /** Links and commands to offer; a platform with clickable text overrides this, the rest get plain lines.
         *
         * @param actions The links or commands to present to the sender.
         */
        default void sendActions(List<Action> actions) {
            for (Action a : actions) send(" §8» §7" + a.label() + ": §f" + (a.url() != null ? a.url() : a.command()));
        }
    }

    /** Something to open or run: a link ({@code url}) or a command, with a hover text on platforms that have one.
     *
     * @param label The text shown for the action.
     * @param url The link to open, or null.
     * @param command The command to run, or null.
     * @param hover The hover text, or null.
     */
    public record Action(String label, String url, String command, String hover) {
        /** Factory for an external link.
         *
         * @param label The text shown for the action.
         * @param url The link to open.
         * @param hover The hover text, or null.
         * @return The link action.
         */
        public static Action link(String label, String url, String hover) {
            return new Action(label, url, null, hover);
        }

        /** Factory for a chat command.
         *
         * @param label The text shown for the action.
         * @param command The command to run.
         * @param hover The hover text, or null.
         * @return The command action.
         */
        public static Action command(String label, String command, String hover) {
            return new Action(label, null, command, hover);
        }
    }

    private final MCDashboardCore core;

    /** Creates a command handler for the given core instance.
     *
     * @param core The core whose services the commands use.
     */
    public DashboardCommands(MCDashboardCore core) {
        this.core = core;
    }

    /** Returns the usage string for {@code /mcdashboard}.
     *
     * @param label The command label as typed by the player.
     * @return A usage hint like {@code Usage: /mcdashboard <link|qr|...>}.
     */
    public static String usage(String label) {
        return "Usage: /" + label + " <" + String.join("|", SUBCOMMANDS) + ">";
    }

    /**
     * Sends an informational message in grey.
     *
     * @param sender The player or console receiving the message.
     * @param message The text to display.
     */
    public void info(Sender sender, String message) {
        sender.send(core.chatPrefix() + "§7" + message);
    }

    /**
     * Sends good news in green.
     *
     * @param sender The player or console receiving the message.
     * @param message The text to display.
     */
    public void success(Sender sender, String message) {
        sender.send(core.chatPrefix() + "§a" + message);
    }

    /**
     * Reports a problem in red.
     *
     * @param sender The player or console receiving the message.
     * @param message The text to display.
     */
    public void error(Sender sender, String message) {
        sender.send(core.chatPrefix() + "§c" + message);
    }

    /**
     * Appends an indented follow-up line without prefix.
     *
     * @param sender The player or console receiving the message.
     * @param message The text to indent.
     */
    public void detail(Sender sender, String message) {
        sender.send(" §8» §7" + message);
    }

    /** Tells the sender they may not do this, and which permission would let them.
     *
     * @param sender The player or console being refused.
     * @param node The permission node that would allow it.
     */
    public void denied(Sender sender, String node) {
        error(sender, "You don't have permission to do that. §7(needs §f" + node + "§7)");
    }

    /** Shows the usage line for a sub-command, e.g. {@code Usage: /mcd revoke <device id>}.
     *
     * @param sender The player or console to tell.
     * @param label The command label used in the usage line.
     * @param arguments The sub-command arguments to show.
     */
    public void usage(Sender sender, String label, String arguments) {
        error(sender, "Usage: §f/" + label + " " + arguments);
    }

    /** Shows an unknown-command error with available sub-commands listed.
     *
     * @param sender The player or console to tell.
     * @param label The command label used in the hint.
     * @param subcommands The sub-commands to list.
     */
    public void usageOverview(Sender sender, String label, List<String> subcommands) {
        error(sender, "Unknown command. Try §f/" + label + " <" + String.join("|", subcommands) + ">§c, or §f/" + label + " about§c.");
    }

    /** Tells a sender they must be a player to use this command.
     *
     * @param sender The console or other non-player sender.
     */
    public void playersOnly(Sender sender) {
        error(sender, "This command can only be used by a player.");
    }

    /** Tells a sender that a named player is not online.
     *
     * @param sender The player or console to tell.
     * @param name The player name that is not online.
     */
    public void notOnline(Sender sender, String name) {
        error(sender, "Player §f" + name + "§c is not online.");
    }

    /** Placeholders until the app is listed; change them here when the store pages exist. */
    static final String ANDROID_URL = "https://play.google.com/store/apps/details?id=org.mcdashboard.app";
    static final String IOS_URL = "https://apps.apple.com/app/mcdashboard/id0000000000";

    private static final List<Action> STORE_LINKS = List.of(
            Action.link("Android (Google Play)", ANDROID_URL, "Open the Google Play page of the MCDashboard app"),
            Action.link("iPhone & iPad (App Store)", IOS_URL, "Open the App Store page of the MCDashboard app"));

    /**
     * What MCDashboard is, how to get the app and how to link it; the first thing a player sees.
     *
     * @param sender The player receiving the about information.
     * @param label The command label used in sub-command references.
     */
    public void about(Sender sender, String label) {
        sender.send("§8§m      §r §b§lMCDashboard §7v" + core.pluginVersion() + " §8§m      ");
        sender.send("§7Your server in your pocket: live stats, dashboards and alerts in the MCDashboard app.");
        sender.send("§e1. Get the app");
        sender.sendActions(STORE_LINKS);
        sender.send("§e2. Link it to your account");
        if (core.otps() == null) {
            detail(sender, "§cLinking is not available yet: an admin still has to enable MCDashboard on this server.");
        } else {
            sender.sendActions(List.of(Action.command("Get a pairing code", "/" + label + " link", "Run /" + label + " link")));
        }
        sender.send("§7More: §fhttps://mcdashboard.org §8| §fhttps://mcdashboard.org/en/docs/");
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            detail(sender, "Admin: §f/" + label + " devices §7(your devices), §f/" + label + " developer§7, §f/" + label + " tunnel");
        }
    }

    /** Toggles developer mode on or off for admins.
     *
     * @param sender The admin running the command.
     * @param label The command label used in usage hints.
     * @param args The sub-command arguments.
     */
    public void developer(Sender sender, String label, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            denied(sender, ADMIN_PERMISSION);
            return;
        }
        if (args.length < 2) {
            info(sender, "Developer mode is " + (core.developerMode() ? "§aon" : "§coff") + "§7.");
            detail(sender, "Use §f/" + label + " developer on|off§7. Players also need §fmcdashboard.developer§7 to upload.");
            return;
        }
        String value = args[1].toLowerCase(java.util.Locale.ROOT);
        if (!value.equals("on") && !value.equals("off")) {
            usage(sender, label, "developer on|off");
            return;
        }
        core.setDeveloperMode(value.equals("on"));
        success(sender, "Developer mode is now " + value + ". Players with mcdashboard.developer can upload a dashboard.");
    }

    /** Reports the current tunnel status to an admin.
     *
     * @param sender The admin running the command.
     */
    public void tunnel(Sender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            denied(sender, ADMIN_PERMISSION);
            return;
        }
        var t = core.tunnel();
        if (t == null) {
            info(sender, "The tunnel is off (tunnel.enabled in config.yml).");
            return;
        }
        var st = t.status();
        if (st.connected()) {
            sender.send(core.chatPrefix() + "§aTunnel connected§7 for " + ago(st.connectedSinceMs()).replace(" ago", "") + ", ping " + st.rttMs() + " ms, "
                    + st.channels() + " live stream(s).");
        } else {
            sender.send(core.chatPrefix() + "§cTunnel down§7, attempt " + st.attempts() + (st.lastError() == null ? "" : ": " + st.lastError()
                    + " (" + ago(st.lastErrorMs()) + ")") + ". It keeps retrying.");
        }
    }

    /** Whether MCDashboard is running and ready to accept commands.
     *
     * @param sender The sender to notify when the server is not running.
     * @return True if the web server is active and OTPs are available.
     */
    public boolean running(Sender sender) {
        if (core.otps() != null) return true;
        error(sender, "MCDashboard is not running. Ask an admin to enable it in config.yml.");
        return false;
    }

    /** Creates a pairing code for the player and tells the sender; returns the code.
     *
     * @param sender The player receiving the code message.
     * @param targetUuid UUID of the player to pair.
     * @param targetName Display name of the player to pair.
     * @return The generated pairing code.
     */
    public String link(Sender sender, String targetUuid, String targetName) {
        String code = core.otps().create(targetUuid, targetName);
        boolean self = targetUuid.equals(sender.playerUuid());
        success(sender, (self ? "Your pairing code is " : "The pairing code for " + targetName + " is ") + "§e§l" + OtpService.display(code)
                + "§a. Enter it in the MCDashboard app.");
        String link = connectLink(code);
        if (link != null) detail(sender, "Or open: §f" + link);
        return code;
    }

    /** Tells a player how long a code works.
     *
     * @param sender The player being told.
     */
    public void expiryNote(Sender sender) {
        detail(sender, "The code works once and expires in " + core.otpTimeoutSeconds() / 60 + " min. No app yet? §f/mcd about");
    }

    /** Lists the player's devices as plain text, each with the command that removes it.
     *
     * @param sender The player receiving the device list.
     * @param label The command label used in sub-command references.
     * @param playerUuid UUID of the player whose devices to list.
     */
    public void devices(Sender sender, String label, String playerUuid) {
        List<DeviceStore.Device> list = core.devices().devicesOf(playerUuid);
        if (list.isEmpty()) {
            info(sender, "No devices linked. Use §f/" + label + " link§7 to link one.");
            return;
        }
        info(sender, "§6Devices with access to your data §7(" + summary(list) + "):");
        for (DeviceStore.Device d : list) {
            String seen = core.devices().lastSeen(d.id()).map(t -> "last seen " + ago(t)).orElse("not seen since the server started");
            detail(sender, "§e" + d.label() + " §7- " + (d.client() == null ? "unknown app" : d.client()) + ", " + entitlementText(d.entitlement()) + ", linked " + ago(d.createdMs())
                    + ", " + seen + " §7(remove: /" + label + " revoke " + d.id() + ")");
        }
    }

    /** {@code 12 devices: 7 via your licence, 3 app unlock, 2 free}; devices that have not said yet are counted last. Self-reported by the app. */
    static String summary(List<DeviceStore.Device> list) {
        int server = 0, app = 0, free = 0;
        for (DeviceStore.Device d : list) {
            if ("server".equals(d.entitlement())) server++;
            else if ("app".equals(d.entitlement())) app++;
            else if ("free".equals(d.entitlement())) free++;
        }
        int unknown = list.size() - server - app - free;
        return list.size() + (list.size() == 1 ? " device: " : " devices: ") + server + " via your licence, " + app + " app unlock, " + free + " free"
                + (unknown > 0 ? ", " + unknown + " not reported" : "");
    }

    /** What a device says it runs under (self-reported, informational). */
    static String entitlementText(String entitlement) {
        if (entitlement == null) return "licence not reported";
        return switch (entitlement) {
            case "server" -> "via your licence";
            case "app" -> "app unlock";
            default -> "free";
        };
    }

    /** Revokes a device by id; tells the sender whether it was removed or unknown.
     *
     * @param sender The player or console running the command.
     * @param label The command label used in usage hints.
     * @param args The sub-command arguments; {@code args[1]} is the device id.
     * @param playerUuid The UUID of the player whose devices are checked.
     */
    public void revoke(Sender sender, String label, String[] args, String playerUuid) {
        if (args.length < 2) {
            usage(sender, label, "revoke <device id>");
            return;
        }
        boolean mine = core.devices().get(args[1]).filter(d -> d.playerUuid().equals(playerUuid)).isPresent();
        if (mine && core.devices().remove(args[1])) success(sender, "Device removed.");
        else error(sender, "Unknown device.");
    }

    /** The deep link for a code, or null when the server has no public address configured.
     *
     * @param code The pairing code to embed in the link.
     * @return The {@code mcdashboard://connect} URI string, or null.
     */
    public String connectLink(String code) {
        String publicUrl = core.publicUrl();
        if (publicUrl == null) return null;
        String pin = core.pinnedFingerprint();
        return "mcdashboard://connect?url=" + URLEncoder.encode(publicUrl, StandardCharsets.UTF_8) + "&code=" + code
                + (pin == null ? "" : "&pin=" + pin);
    }

    /** A human-friendly relative time like {@code "5 min ago"} or {@code "just now"}.
     *
     * @param ms Epoch milliseconds to convert.
     * @return A relative-time string from the current moment.
     */
    public static String ago(long ms) {
        long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
        if (s < 90) return "just now";
        if (s < 5400) return Math.round(s / 60.0) + " min ago";
        if (s < 129600) return Math.round(s / 3600.0) + " h ago";
        return Math.round(s / 86400.0) + " days ago";
    }
}
