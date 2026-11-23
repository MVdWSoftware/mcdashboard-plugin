/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mcdashboard.commands.DashboardCommands;
import org.mcdashboard.json.Json;
import org.mcdashboard.platform.Log;

/** The core boots on the proxy platform against a fake proxy: defaults, catalog, values and the link command. */
class ProxyCoreIntegrationTest {
    private static final String APP = "MCDashboard/1.0.0 (dev)";

    @TempDir
    Path data;

    private final List<String> logged = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private ProxyRuntime runtime;

    @AfterEach
    void stop() {
        if (runtime != null) runtime.stop();
    }

    private Log log() {
        return new Log() {
            @Override
            public void info(String m) {
                logged.add("INFO " + m);
            }

            @Override
            public void warning(String m) {
                logged.add("WARN " + m);
            }

            @Override
            public void severe(String m) {
                logged.add("SEVERE " + m);
            }
        };
    }

    private int port() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private String get(int port, String path) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).setHeader("User-Agent", APP).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), path + " " + r.body());
        return r.body();
    }

    @Test
    void aFreshInstallWritesTheProxyDefaultsAndStaysOff() {
        FakeAdapter adapter = new FakeAdapter();
        runtime = new ProxyRuntime(adapter, log(), "1.0.0", data, new ProxyYaml());
        runtime.start();
        assertTrue(Files.isRegularFile(data.resolve("dashboards/player.yml")));
        assertTrue(Files.isRegularFile(data.resolve("dashboards/network.yml")));
        assertFalse(Files.exists(data.resolve("dashboards/server.yml")), "no TPS dashboard on a proxy");
        try {
            String config = Files.readString(data.resolve("config.yml"));
            assertTrue(config.contains("network:") && config.contains("backends: []"), "the proxy settings are appended");
            assertTrue(config.contains("enabled: false"));
            assertTrue(Files.readString(data.resolve("placeholders.yml")).contains("%proxy_online%"));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertTrue(logged.stream().anyMatch(m -> m.contains("server.enabled")), logged.toString());
    }

    @Test
    void theBstatsSummaryIsOffBeforeStartAndReadsTheConfigAfter() {
        runtime = new ProxyRuntime(new FakeAdapter(), log(), "1.0.0", data, new ProxyYaml());
        assertFalse(runtime.setupSummary().serverEnabled());
        assertEquals("unknown", runtime.setupSummary().historyBackend());
        runtime.start();
        assertFalse(runtime.setupSummary().serverEnabled(), "a fresh proxy config has the server off");
        assertEquals("memory", runtime.setupSummary().historyLabel());
    }

    @Test
    void theDefaultDashboardsFitTheFreeLimits() throws Exception {
        runtime = new ProxyRuntime(new FakeAdapter(), log(), "1.0.0", data, new ProxyYaml());
        runtime.start();
        long dashboards;
        try (var files = Files.list(data.resolve("dashboards"))) {
            dashboards = files.count();
        }
        assertEquals(2, dashboards);
        for (String name : List.of("player", "network")) {
            long items = Files.readAllLines(data.resolve("dashboards/" + name + ".yml")).stream().filter(l -> l.startsWith("  - {")).count();
            assertTrue(items > 0 && items <= 12, name + " has " + items + " tiles");
        }
    }

    @Test
    void servesTheNetworkValuesAndTheCatalogThroughTheRealApiAndLinksAPlayer() throws Exception {
        int port = port();
        Files.writeString(data.resolve("config.yml"), "server:\n  enabled: true\n  host: 127.0.0.1\n  port: " + port
                + "\n  tls:\n    mode: off\n  websocket:\n    enabled: false\nsecurity:\n  require-authentication: false\n");
        FakeAdapter adapter = new FakeAdapter();
        adapter.inline = true;
        runtime = new ProxyRuntime(adapter, log(), "1.0.0", data, new ProxyYaml());
        runtime.start();

        var info = (Map<?, ?>) Json.parse(get(port, "/v1/info"));
        assertEquals("FakeProxy network", info.get("name"));
        assertEquals("A network", info.get("motd"));
        assertEquals(25577L, ((Number) info.get("gamePort")).longValue());

        String list = get(port, "/v1/placeholders?limit=100");
        assertTrue(list.contains("proxy_server_lobby_online"), "the catalog lists each backend server: " + list);
        assertTrue(list.contains("proxy_server_sky_block_up"));
        assertFalse(list.contains("player-ping"), "PLAYER placeholders are not offered anonymously");

        var records = (List<?>) Json.parse(get(port, "/v1/values?ids=online,max-players,proxy_server_lobby_online,uptime"));
        assertEquals(4, records.size(), records.toString());
        for (Object o : records) {
            var r = (Map<?, ?>) o;
            if (r.get("n").equals("online")) assertEquals(3L, ((Number) r.get("v")).longValue());
            if (r.get("n").equals("proxy_server_lobby_online")) assertEquals(2L, ((Number) r.get("v")).longValue());
        }

        // /mcdashboard link gives the player a one-time code
        List<String> said = new ArrayList<>();
        DashboardCommands.Sender alex = new DashboardCommands.Sender() {
            @Override
            public void send(String message) {
                said.add(message);
            }

            @Override
            public boolean hasPermission(String node) {
                return false;
            }

            @Override
            public String playerUuid() {
                return FakeAdapter.ALEX.toString();
            }
        };
        runtime.execute(alex, "mcdashboard", new String[] {"link"});
        assertTrue(said.stream().anyMatch(m -> m.contains("Your pairing code is ")), said.toString());
        said.clear();
        runtime.execute(alex, "mcdashboard", new String[] {"reload"});
        assertEquals(List.of("§8[§bMCDashboard§8] §r§cYou don't have permission to do that. §7(needs §fmcdashboard.admin§7)"), said);
        runtime.execute(alex, "mcdashboard", new String[] {"devices"});
        assertTrue(said.get(1).contains("No devices linked"), said.toString());
        assertNotNull(runtime);
    }

    @Test
    void reloadRestartsTheCoreAndRereadsTheNetworkSection() throws Exception {
        int port = port();
        Files.writeString(data.resolve("config.yml"), "server:\n  enabled: true\n  host: 127.0.0.1\n  port: " + port
                + "\n  tls:\n    mode: off\n  websocket:\n    enabled: false\nsecurity:\n  require-authentication: false\n");
        runtime = new ProxyRuntime(new FakeAdapter(), log(), "1.0.0", data, new ProxyYaml());
        runtime.start();
        get(port, "/v1/info");
        DashboardCommands.Sender console = new DashboardCommands.Sender() {
            @Override
            public void send(String message) {
                logged.add("SAID " + message);
            }

            @Override
            public boolean hasPermission(String node) {
                return true;
            }

            @Override
            public String playerUuid() {
                return null;
            }
        };
        runtime.execute(console, "mcdashboard", new String[] {"reload"});
        get(port, "/v1/info");
        assertTrue(logged.stream().anyMatch(l -> l.startsWith("SAID") && l.endsWith("MCDashboard restarted with the files on disk.")), logged.toString());
    }
}
