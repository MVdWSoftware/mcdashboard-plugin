/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mcdashboard.json.Json;

/** The core runs on a platform that is not Bukkit: everything it needs comes through {@link Platform}. */
class MCDashboardCoreTest {
    private static final String APP = "MCDashboard/1.0.0 (dev)";

    @TempDir
    Path data;

    private MCDashboardCore core;
    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        if (core != null) core.stop();
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private int writeConfig(String extra) throws Exception {
        int port = freePort();
        Files.writeString(data.resolve("config.yml"), "server:\n  enabled: true\n  host: 127.0.0.1\n  port: " + port
                + "\n  tls:\n    mode: off\n  websocket:\n    enabled: false\n" + extra);
        return port;
    }

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).setHeader("User-Agent", APP).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void startsTheApiFromTheDefaultsItWritesAndTheNamesThePlatformGives() throws Exception {
        int port = writeConfig("");
        FakePlatform platform = new FakePlatform();
        core = new MCDashboardCore(platform, new SnakeYamlCodec(), data);
        core.start();

        for (String file : List.of("placeholders.yml", "dashboards/player.yml", "dashboards/server.yml")) {
            assertTrue(Files.isRegularFile(data.resolve(file)), file + " is exported");
        }
        var info = (java.util.Map<?, ?>) Json.parse(get(port, "/v1/info").body());
        assertEquals("Fake Server", info.get("name"), "server.name is unset, so the platform's name is used");
        assertEquals("9.9.9", info.get("version"));
        assertEquals(1, platform.repeating.get(), "the server list data is refreshed on the main thread");
        assertEquals("A fake MOTD", info.get("motd"));
        assertEquals("1.21", info.get("gameVersion"));
        assertEquals(2L, ((Number) info.get("online")).longValue());
        assertTrue(platform.permissionCalls.get(0).startsWith("start "), "the platform's permissions are started with the placeholders");
        assertNotNull(core.otps());
        assertNotNull(core.devices());
        assertNull(core.tunnel());
    }

    @Test
    void theSetupSummaryForBstatsReadsTheConfigWithoutLeakingValues() throws Exception {
        writeConfig("");
        FakePlatform platform = new FakePlatform();
        core = new MCDashboardCore(platform, new SnakeYamlCodec(), data);
        // Before start() there is no configuration to read; every flag stays off.
        assertFalse(core.setupSummary().serverEnabled());
        assertEquals("unknown", core.setupSummary().historyBackend());
        core.start();
        var summary = core.setupSummary();
        assertTrue(summary.serverEnabled());
        assertFalse(summary.licensed(), "no licence id is configured");
        assertFalse(summary.tunnelEnabled());
        assertEquals("memory", summary.historyBackend());
    }

    @Test
    void permissionBasedRefreshOverridesAreWatchedByThePermissionCache() throws Exception {
        writeConfig("security:\n  min-interval: 900\n  min-interval-permissions:\n    x.fast: 15\n");
        FakePlatform platform = new FakePlatform();
        core = new MCDashboardCore(platform, new SnakeYamlCodec(), data);
        core.start();

        assertTrue(platform.permissionCalls.contains("watch 1"), platform.permissionCalls.toString());
    }

    @Test
    void anExplicitServerNameWinsAndMissingKeysKeepTheirBundledDefaults() throws Exception {
        int port = writeConfig("  name: My Network\nsecurity:\n  max-devices: 2\n");
        core = new MCDashboardCore(new FakePlatform(), new SnakeYamlCodec(), data);
        core.start();
        var info = (java.util.Map<?, ?>) Json.parse(get(port, "/v1/info").body());
        assertEquals("My Network", info.get("name"));
        assertEquals(300, core.otpTimeoutSeconds(), "not in the file: the bundled default");
    }

    @Test
    void doesNotStartWithoutThePlaceholderEngine() throws Exception {
        int port = writeConfig("");
        FakePlatform platform = new FakePlatform();
        platform.available = false;
        core = new MCDashboardCore(platform, new SnakeYamlCodec(), data);
        core.start();
        assertTrue(platform.logged.stream().anyMatch(m -> m.startsWith("SEVERE") && m.contains("PlaceholderAPI is required")), platform.logged.toString());
        assertNull(core.otps());
        try {
            get(port, "/v1/info");
            throw new AssertionError("nothing should be listening");
        } catch (java.net.ConnectException expected) {
            // not started
        }
    }

    @Test
    void aFailedBindStopsWhatAlreadyStarted() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            int port = occupied.getLocalPort();
            Files.writeString(data.resolve("config.yml"), "server:\n  enabled: true\n  host: 127.0.0.1\n  port: " + port
                    + "\n  tls:\n    mode: off\n  websocket:\n    enabled: false\n");
            FakePlatform platform = new FakePlatform();
            core = new MCDashboardCore(platform, new SnakeYamlCodec(), data);
            core.start();
            assertNull(core.otps(), "a dead API must not answer /mcdashboard link with a pairing code");
            assertEquals(1, platform.cancelled.get(), "the repeating status task is cancelled again");
            assertTrue(platform.logged.stream().anyMatch(m -> m.startsWith("SEVERE") && m.contains("Could not start MCDashboard")),
                    platform.logged.toString());
        }
    }

    @Test
    void staysOffUntilTheOwnerEnablesIt() throws Exception {
        FakePlatform platform = new FakePlatform();
        core = new MCDashboardCore(platform, new SnakeYamlCodec(), data);
        core.start();
        assertNull(core.otps());
        assertTrue(platform.logged.stream().anyMatch(m -> m.startsWith("WARN") && m.contains("server.enabled")));
        assertTrue(Files.isRegularFile(data.resolve("config.yml")), "the default config is exported for the owner to edit");
    }

    @Test
    void developerModeSurvivesARestartAndWinsOverTheConfig() throws Exception {
        writeConfig("developer:\n  enabled: false\n");
        FakePlatform platform = new FakePlatform();
        core = new MCDashboardCore(platform, new SnakeYamlCodec(), data);
        core.start();
        assertFalse(core.developerMode());
        core.setDeveloperMode(true);
        assertTrue(core.developerMode());
        core.stop();

        writeConfig("developer:\n  enabled: false\n");
        core = new MCDashboardCore(new FakePlatform(), new SnakeYamlCodec(), data);
        core.start();
        assertTrue(core.developerMode(), "developer.yml overrides developer.enabled");
        assertEquals(List.of(), new ArrayList<>(platform.logged.stream().filter(m -> m.startsWith("SEVERE")).toList()));
    }
}
