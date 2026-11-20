/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mcdashboard.FakePlatform;
import org.mcdashboard.MCDashboardCore;
import org.mcdashboard.SnakeYamlCodec;

/** The command text is the same on every platform; only who runs it differs. */
class DashboardCommandsTest {
    private static final String STEVE = "00000000-0000-0000-0000-000000000001";

    @TempDir
    Path data;

    private MCDashboardCore core;

    @AfterEach
    void stop() {
        if (core != null) core.stop();
    }

    private static final class Who implements DashboardCommands.Sender {
        final List<String> said = new ArrayList<>();
        final Set<String> permissions;
        final String uuid;

        Who(String uuid, String... permissions) {
            this.uuid = uuid;
            this.permissions = Set.of(permissions);
        }

        @Override
        public void send(String message) {
            said.add(message);
        }

        @Override
        public boolean hasPermission(String node) {
            return permissions.contains(node);
        }

        @Override
        public String playerUuid() {
            return uuid;
        }
    }

    private DashboardCommands start(boolean enabled, String publicUrl) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Files.writeString(data.resolve("config.yml"), "server:\n  enabled: " + enabled + "\n  host: 127.0.0.1\n  port: " + port
                + "\n  public-url: \"" + publicUrl + "\"\n  websocket:\n    enabled: false\n");
        core = new MCDashboardCore(new FakePlatform(), new SnakeYamlCodec(), data);
        core.start();
        return new DashboardCommands(core);
    }

    private DashboardCommands startWith(String extraYaml) throws Exception {
        Files.writeString(data.resolve("config.yml"), "server:\n  enabled: false\n" + extraYaml);
        core = new MCDashboardCore(new FakePlatform(), new SnakeYamlCodec(), data);
        core.start();
        return new DashboardCommands(core);
    }

    @Test
    void chatPrefixIsConfigurableAndStoresAreFixed() throws Exception {
        DashboardCommands c = startWith("messages:\n  prefix: \"&6[MCD] \"\n");
        Who who = new Who(null);
        c.info(who, "hello");
        c.success(who, "done");
        c.error(who, "bad");
        assertEquals(List.of("§6[MCD] §7hello", "§6[MCD] §adone", "§6[MCD] §cbad"), who.said);

        Who about = new Who(null);
        c.about(about, "mcd");
        String text = String.join("\n", about.said);
        assertTrue(text.contains(DashboardCommands.ANDROID_URL) && text.contains(DashboardCommands.IOS_URL), text);
        assertTrue(text.contains("not available yet"), "linking explains itself while the server is off: " + text);
    }

    @Test
    void emptyPrefixSendsPlainMessages() throws Exception {
        DashboardCommands c = startWith("messages:\n  prefix: \"\"\n");
        Who who = new Who(null);
        c.error(who, "bad");
        assertEquals("§cbad", who.said.get(0));
    }

    @Test
    void sharedMessages() throws Exception {
        DashboardCommands c = start(true, "");
        Who who = new Who(STEVE);
        c.denied(who, "mcdashboard.admin");
        c.usage(who, "mcd", "link");
        c.usageOverview(who, "mcd", List.of("link", "about"));
        c.playersOnly(who);
        c.notOnline(who, "Alex");
        c.detail(who, "more");
        String p = "§8[§bMCDashboard§8] §r§c";
        assertEquals(List.of(p + "You don't have permission to do that. §7(needs §fmcdashboard.admin§7)", p + "Usage: §f/mcd link",
                p + "Unknown command. Try §f/mcd <link|about>§c, or §f/mcd about§c.", p + "This command can only be used by a player.",
                p + "Player §fAlex§c is not online.", " §8» §7more"), who.said);
        Who actions = new Who(STEVE);
        actions.sendActions(List.of(DashboardCommands.Action.link("Site", "https://mcdashboard.org", "open"),
                DashboardCommands.Action.command("Link", "/mcd link", "run")));
        assertEquals(List.of(" §8» §7Site: §fhttps://mcdashboard.org", " §8» §7Link: §f/mcd link"), actions.said);
    }

    @Test
    void aboutAndUsage() throws Exception {
        DashboardCommands c = start(true, "");
        Who who = new Who(null);
        c.about(who, "mcd");
        String text = String.join("\n", who.said);
        assertTrue(text.contains("MCDashboard §7v9.9.9"), text);
        assertTrue(text.contains("play.google.com") && text.contains("apps.apple.com"), "both stores are offered: " + text);
        assertTrue(text.contains("/mcd link"), text);
        assertFalse(text.contains("Admin:"), "admin hints are for admins");
        Who admin = new Who(null, DashboardCommands.ADMIN_PERMISSION);
        c.about(admin, "mcd");
        assertTrue(String.join("\n", admin.said).contains("Admin: §f/mcd devices"));
        assertEquals("Usage: /dashboard <link|qr|devices|revoke|developer|tunnel|about>", DashboardCommands.usage("dashboard"));
    }

    @Test
    void developerModeNeedsAdminAndPersists() throws Exception {
        DashboardCommands c = start(true, "");
        Who player = new Who(STEVE);
        c.developer(player, "mcdashboard", new String[] {"developer", "on"});
        assertEquals("§8[§bMCDashboard§8] §r§c" + "You don't have permission to do that. §7(needs §fmcdashboard.admin§7)", player.said.get(0));
        assertFalse(core.developerMode());

        Who admin = new Who(null, DashboardCommands.ADMIN_PERMISSION);
        c.developer(admin, "mcdashboard", new String[] {"developer"});
        assertEquals("§8[§bMCDashboard§8] §r§7Developer mode is §coff§7.", admin.said.get(0));
        c.developer(admin, "mcdashboard", new String[] {"developer", "maybe"});
        assertEquals("§8[§bMCDashboard§8] §r§cUsage: §f/mcdashboard developer on|off", admin.said.get(2));
        c.developer(admin, "mcdashboard", new String[] {"developer", "ON"});
        assertTrue(core.developerMode());
        assertTrue(Files.readString(data.resolve("developer.yml")).contains("enabled: true"));
    }

    @Test
    void tunnelIsAdminOnlyAndReportsWhenOff() throws Exception {
        DashboardCommands c = start(true, "");
        Who player = new Who(STEVE);
        c.tunnel(player);
        assertTrue(player.said.get(0).contains("mcdashboard.admin"));
        Who admin = new Who(null, DashboardCommands.ADMIN_PERMISSION);
        c.tunnel(admin);
        assertTrue(admin.said.get(0).endsWith("The tunnel is off (tunnel.enabled in config.yml)."), admin.said.get(0));
    }

    @Test
    void linkGivesACodeThatPairsAndALinkWhenThereIsAPublicUrl() throws Exception {
        DashboardCommands c = start(true, "https://mc.example.org");
        Who steve = new Who(STEVE);
        assertTrue(c.running(steve));
        String code = c.link(steve, STEVE, "Steve");
        assertNotNull(code);
        assertTrue(steve.said.get(0).startsWith("§8[§bMCDashboard§8] §r§aYour pairing code is "), steve.said.get(0));
        assertTrue(steve.said.get(0).contains(org.mcdashboard.auth.OtpService.display(code)));
        // The default is a self-signed certificate, so the link carries the fingerprint the app pins.
        assertTrue(steve.said.get(1).matches(" §8» §7Or open: §fmcdashboard://connect\\?url=https%3A%2F%2Fmc.example.org&code=" + code + "&pin=[0-9a-f]{64}"), steve.said.get(1));
        c.expiryNote(steve);
        assertTrue(steve.said.get(2).contains("expires in 5 min"));

        Who console = new Who(null);
        c.link(console, STEVE, "Steve");
        assertTrue(console.said.get(0).contains("The pairing code for Steve is "));
    }

    @Test
    void withoutAPublicUrlThereIsNoLink() throws Exception {
        DashboardCommands c = start(true, "");
        assertNull(c.connectLink("ABCD2345"));
        Who steve = new Who(STEVE);
        c.link(steve, STEVE, "Steve");
        assertEquals(1, steve.said.size());
    }

    @Test
    void devicesShowWhichLicenceEachOneSaysItUses() throws Exception {
        DashboardCommands c = start(true, "");
        Who steve = new Who(STEVE);
        var phone = core.devices().add(STEVE, "Steve", "Pixel", 5, "MCDashboard/1.0.0 (android)").orElseThrow();
        var tablet = core.devices().add(STEVE, "Steve", "Tablet", 5, null).orElseThrow();
        core.devices().add(STEVE, "Steve", "Old", 5, null);
        core.devices().entitle(phone.id(), "server");
        core.devices().entitle(tablet.id(), "app");
        c.devices(steve, "mcdashboard", STEVE);
        assertTrue(steve.said.get(0).contains("(3 devices: 1 via your licence, 1 app unlock, 0 free, 1 not reported)"), steve.said.get(0));
        assertTrue(steve.said.get(1).contains("via your licence"), steve.said.get(1));
        assertTrue(steve.said.get(2).contains("app unlock"), steve.said.get(2));
    }

    @Test
    void devicesAndRevokeOnlyTouchTheCallersOwnDevices() throws Exception {
        DashboardCommands c = start(true, "");
        Who steve = new Who(STEVE);
        c.devices(steve, "mcdashboard", STEVE);
        assertTrue(steve.said.get(0).endsWith("No devices linked. Use §f/mcdashboard link§7 to link one."), steve.said.get(0));

        var device = core.devices().add(STEVE, "Steve", "Pixel", 5, "MCDashboard/1.0.0 (android)").orElseThrow();
        c.devices(steve, "mcdashboard", STEVE);
        assertTrue(steve.said.get(1).contains("(1 device: 0 via your licence, 0 app unlock, 0 free, 1 not reported)"), steve.said.get(1));
        assertTrue(steve.said.get(2).contains("licence not reported"), steve.said.get(2));
        assertTrue(steve.said.get(2).contains("Pixel") && steve.said.get(2).contains("revoke " + device.id()), steve.said.get(2));

        Who other = new Who("00000000-0000-0000-0000-000000000002");
        c.revoke(other, "mcdashboard", new String[] {"revoke", device.id()}, other.uuid);
        assertTrue(other.said.get(0).endsWith("Unknown device."), other.said.get(0));
        c.revoke(steve, "mcdashboard", new String[] {"revoke"}, STEVE);
        assertTrue(steve.said.get(3).endsWith("Usage: §f/mcdashboard revoke <device id>"), steve.said.get(3));
        c.revoke(steve, "mcdashboard", new String[] {"revoke", device.id()}, STEVE);
        assertTrue(steve.said.get(4).endsWith("§aDevice removed."), steve.said.get(4));
        assertTrue(core.devices().devicesOf(STEVE).isEmpty());
    }

    @Test
    void saysSoWhenTheServerIsNotRunning() throws Exception {
        DashboardCommands c = start(false, "");
        Who steve = new Who(STEVE);
        assertFalse(c.running(steve));
        assertTrue(steve.said.get(0).contains("not running"));
    }

    @Test
    void agoReadsNaturally() {
        long now = System.currentTimeMillis();
        assertEquals("just now", DashboardCommands.ago(now - 30_000));
        assertEquals("10 min ago", DashboardCommands.ago(now - 600_000));
        assertEquals("3 h ago", DashboardCommands.ago(now - 3 * 3_600_000L));
        assertEquals("3 days ago", DashboardCommands.ago(now - 3 * 86_400_000L));
    }
}
