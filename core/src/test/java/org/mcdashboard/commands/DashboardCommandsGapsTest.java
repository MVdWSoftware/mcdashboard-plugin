/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.commands;

import static org.junit.jupiter.api.Assertions.*;

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
import org.mcdashboard.auth.DeviceStore;

class DashboardCommandsGapsTest {
    @TempDir
    Path data;

    MCDashboardCore core;

    @AfterEach
    void stop() {
        if (core != null) core.stop();
    }

    static final class Who implements DashboardCommands.Sender {
        final List<String> said = new ArrayList<>();
        final Set<String> permissions;
        final String uuid;

        Who(String uuid, String... permissions) {
            this.uuid = uuid;
            this.permissions = Set.of(permissions);
        }

        public void send(String message) {
            said.add(message);
        }

        public boolean hasPermission(String node) {
            return permissions.contains(node);
        }

        public String playerUuid() {
            return uuid;
        }
    }

    private DashboardCommands start(String extra) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Files.writeString(data.resolve("config.yml"), "server:\n  enabled: true\n  host: 127.0.0.1\n  port: " + port
                + "\n  tls:\n    mode: off\n  websocket:\n    enabled: false\n" + extra);
        core = new MCDashboardCore(new FakePlatform(), new SnakeYamlCodec(), data);
        core.start();
        return new DashboardCommands(core);
    }

    @Test
    void developerModeReportsItsStateAndRefusesOtherValues() throws Exception {
        DashboardCommands c = start("");
        Who admin = new Who("u", "mcdashboard.admin");
        c.developer(admin, "mcd", new String[]{"developer"});
        assertTrue(admin.said.get(0).contains("off"));
        core.setDeveloperMode(true);
        c.developer(admin, "mcd", new String[]{"developer"});
        assertTrue(admin.said.get(2).contains("on"));
        c.developer(admin, "mcd", new String[]{"developer", "maybe"});
        assertTrue(admin.said.get(admin.said.size() - 1).startsWith("Usage"));
    }

    @Test
    void theTunnelStatusSaysWhyItIsDown() throws Exception {
        DashboardCommands c = start("tunnel:\n  enabled: true\n  url: ws://127.0.0.1:9/tunnel\n  key: kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk\n  allow-insecure: true\n");
        Who admin = new Who("u", "mcdashboard.admin");
        c.tunnel(admin);
        assertTrue(admin.said.get(0).contains("Tunnel down"), admin.said.toString());
        long end = System.currentTimeMillis() + 5000;
        while (core.tunnel().status().lastError() == null && System.currentTimeMillis() < end) Thread.sleep(20);
        c.tunnel(admin);
        assertTrue(admin.said.get(1).contains("keeps retrying") && admin.said.get(1).contains(":"), admin.said.toString());
    }

    @Test
    void devicesAreSummarisedAndEntitlementsDescribed() throws Exception {
        DashboardCommands c = start("");
        assertEquals("licence not reported", DashboardCommands.entitlementText(null));
        assertEquals("via your licence", DashboardCommands.entitlementText("server"));
        assertEquals("app unlock", DashboardCommands.entitlementText("app"));
        assertEquals("free", DashboardCommands.entitlementText("free"));
        DeviceStore.Device d = core.devices().add("u1", "S", "L", 5).orElseThrow();
        core.devices().entitle(d.id(), "free");
        core.devices().add("u1", "S", "L2", 5);
        String summary = DashboardCommands.summary(core.devices().devicesOf("u1"));
        assertTrue(summary.contains("1 free") && summary.contains("1 not reported"), summary);
        assertTrue(DashboardCommands.summary(core.devices().devicesOf("u1").subList(0, 1)).startsWith("1 device:"));
        Who player = new Who("u1");
        c.revoke(player, "mcd", new String[]{"revoke", d.id()}, "u1");
        assertEquals("Device removed.", player.said.get(0));
        c.revoke(player, "mcd", new String[]{"revoke", "nope"}, "u1");
        assertEquals("Unknown device.", player.said.get(1));
        c.revoke(player, "mcd", new String[]{"revoke"}, "u1");
        assertTrue(player.said.get(2).startsWith("Usage"));
    }
}
