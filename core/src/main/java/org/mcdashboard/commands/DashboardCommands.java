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
    public static final String ADMIN_PERMISSION = "mcdashboard.admin";

    /** Whoever ran the command: a player or the console. */
    public interface Sender {
        void send(String message);

        boolean hasPermission(String node);

        /** The player's UUID, or null for the console. */
        String playerUuid();
    }

    private final MCDashboardCore core;

    public DashboardCommands(MCDashboardCore core) {
        this.core = core;
    }

    public static String usage(String label) {
        return "Usage: /" + label + " <" + String.join("|", SUBCOMMANDS) + ">";
    }

    public void about(Sender sender) {
        sender.send("MCDashboard v" + core.pluginVersion() + " - https://mcdashboard.org");
    }

    public void developer(Sender sender, String label, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.send("You need mcdashboard.admin to change developer mode.");
            return;
        }
        if (args.length < 2) {
            sender.send("Developer mode is " + (core.developerMode() ? "on" : "off") + ".");
            sender.send("Use /" + label + " developer on|off. Players also need mcdashboard.developer to upload.");
            return;
        }
        String value = args[1].toLowerCase(java.util.Locale.ROOT);
        if (!value.equals("on") && !value.equals("off")) {
            sender.send("Usage: /" + label + " developer on|off");
            return;
        }
        core.setDeveloperMode(value.equals("on"));
        sender.send("Developer mode is now " + value + ". Players with mcdashboard.developer can upload a dashboard.");
    }

    public void tunnel(Sender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.send("You need mcdashboard.admin to see the tunnel.");
            return;
        }
        var t = core.tunnel();
        if (t == null) {
            sender.send("The tunnel is off (tunnel.enabled in config.yml).");
            return;
        }
        var st = t.status();
        if (st.connected()) {
            sender.send("§aTunnel connected§7 for " + ago(st.connectedSinceMs()).replace(" ago", "") + ", ping " + st.rttMs() + " ms, "
                    + st.channels() + " live stream(s).");
        } else {
            sender.send("§cTunnel down§7, attempt " + st.attempts() + (st.lastError() == null ? "" : ": " + st.lastError()
                    + " (" + ago(st.lastErrorMs()) + ")") + ". It keeps retrying.");
        }
    }

    /** False, after telling the sender, while the web server is off or failed to start. */
    public boolean running(Sender sender) {
        if (core.otps() != null) return true;
        sender.send("MCDashboard is not running. Ask an admin to enable it in config.yml.");
        return false;
    }

    /** Creates a pairing code for the player and tells the sender; returns the code. */
    public String link(Sender sender, String targetUuid, String targetName) {
        String code = core.otps().create(targetUuid, targetName);
        boolean self = targetUuid.equals(sender.playerUuid());
        sender.send((self ? "Your pairing code is " : "The pairing code for " + targetName + " is ") + OtpService.display(code)
                + ". Enter it in the MinecraftDashboard app.");
        String link = connectLink(code);
        if (link != null) sender.send("Or open: " + link);
        return code;
    }

    /** Tells a player how long a code works. */
    public void expiryNote(Sender sender) {
        sender.send("§7The code works once and expires in " + core.otpTimeoutSeconds() / 60 + " min.");
    }

    /** Lists the player's devices as plain text, each with the command that removes it. */
    public void devices(Sender sender, String label, String playerUuid) {
        List<DeviceStore.Device> list = core.devices().devicesOf(playerUuid);
        if (list.isEmpty()) {
            sender.send("No devices linked. Use /" + label + " link to link one.");
            return;
        }
        sender.send("§6Devices with access to your data §7(" + summary(list) + "):");
        for (DeviceStore.Device d : list) {
            String seen = core.devices().lastSeen(d.id()).map(t -> "last seen " + ago(t)).orElse("not seen since the server started");
            sender.send("§e" + d.label() + " §7- " + (d.client() == null ? "unknown app" : d.client()) + ", " + entitlementText(d.entitlement()) + ", linked " + ago(d.createdMs())
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

    public void revoke(Sender sender, String label, String[] args, String playerUuid) {
        if (args.length < 2) {
            sender.send("Usage: /" + label + " revoke <device id>");
            return;
        }
        boolean mine = core.devices().get(args[1]).filter(d -> d.playerUuid().equals(playerUuid)).isPresent();
        sender.send(mine && core.devices().remove(args[1]) ? "Device removed." : "Unknown device.");
    }

    /** The deep link for a code, or null when the server has no public address configured. */
    public String connectLink(String code) {
        String publicUrl = core.publicUrl();
        if (publicUrl == null) return null;
        String pin = core.pinnedFingerprint();
        return "mcdashboard://connect?url=" + URLEncoder.encode(publicUrl, StandardCharsets.UTF_8) + "&code=" + code
                + (pin == null ? "" : "&pin=" + pin);
    }

    /** "5 min ago", "2 h ago", "3 days ago". */
    public static String ago(long ms) {
        long s = Math.max(0, (System.currentTimeMillis() - ms) / 1000);
        if (s < 90) return "just now";
        if (s < 5400) return Math.round(s / 60.0) + " min ago";
        if (s < 129600) return Math.round(s / 3600.0) + " h ago";
        return Math.round(s / 86400.0) + " days ago";
    }
}
