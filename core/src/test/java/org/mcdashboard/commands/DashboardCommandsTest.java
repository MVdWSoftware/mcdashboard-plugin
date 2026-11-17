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

    @Test
    void aboutAndUsage() throws Exception {
        DashboardCommands c = start(true, "");
        Who who = new Who(null);
        c.about(who);
        assertEquals(List.of("MCDashboard v9.9.9 - https://mcdashboard.org"), who.said);
        assertEquals("Usage: /dashboard <link|qr|devices|revoke|developer|tunnel|about>", DashboardCommands.usage("dashboard"));
    }

    @Test
    void developerModeNeedsAdminAndPersists() throws Exception {
        DashboardCommands c = start(true, "");
        Who player = new Who(STEVE);
        c.developer(player, "mcdashboard", new String[] {"developer", "on"});
        assertTrue(player.said.get(0).contains("mcdashboard.admin"));
        assertFalse(core.developerMode());

        Who admin = new Who(null, DashboardCommands.ADMIN_PERMISSION);
        c.developer(admin, "mcdashboard", new String[] {"developer"});
        assertEquals("Developer mode is off.", admin.said.get(0));
        c.developer(admin, "mcdashboard", new String[] {"developer", "maybe"});
        assertEquals("Usage: /mcdashboard developer on|off", admin.said.get(2));
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
        assertEquals("The tunnel is off (tunnel.enabled in config.yml).", admin.said.get(0));
    }

    @Test
    void linkGivesACodeThatPairsAndALinkWhenThereIsAPublicUrl() throws Exception {
        DashboardCommands c = start(true, "https://mc.example.org");
        Who steve = new Who(STEVE);
        assertTrue(c.running(steve));
        String code = c.link(steve, STEVE, "Steve");
        assertNotNull(code);
        assertTrue(steve.said.get(0).startsWith("Your pairing code is "), steve.said.get(0));
        assertTrue(steve.said.get(0).contains(org.mcdashboard.auth.OtpService.display(code)));
        assertEquals("Or open: mcdashboard://connect?url=https%3A%2F%2Fmc.example.org&code=" + code, steve.said.get(1));
        c.expiryNote(steve);
        assertTrue(steve.said.get(2).contains("expires in 5 min"));

        Who console = new Who(null);
        c.link(console, STEVE, "Steve");
        assertTrue(console.said.get(0).startsWith("The pairing code for Steve is "));
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
    void devicesAndRevokeOnlyTouchTheCallersOwnDevices() throws Exception {
        DashboardCommands c = start(true, "");
        Who steve = new Who(STEVE);
        c.devices(steve, "mcdashboard", STEVE);
        assertEquals("No devices linked. Use /mcdashboard link to link one.", steve.said.get(0));

        var device = core.devices().add(STEVE, "Steve", "Pixel", 5, "MCDashboard/1.0.0 (android)").orElseThrow();
        c.devices(steve, "mcdashboard", STEVE);
        assertTrue(steve.said.get(1).contains("(1)"));
        assertTrue(steve.said.get(2).contains("Pixel") && steve.said.get(2).contains("revoke " + device.id()), steve.said.get(2));

        Who other = new Who("00000000-0000-0000-0000-000000000002");
        c.revoke(other, "mcdashboard", new String[] {"revoke", device.id()}, other.uuid);
        assertEquals("Unknown device.", other.said.get(0), "someone else's device is not theirs to remove");
        c.revoke(steve, "mcdashboard", new String[] {"revoke"}, STEVE);
        assertEquals("Usage: /mcdashboard revoke <device id>", steve.said.get(3));
        c.revoke(steve, "mcdashboard", new String[] {"revoke", device.id()}, STEVE);
        assertEquals("Device removed.", steve.said.get(4));
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
