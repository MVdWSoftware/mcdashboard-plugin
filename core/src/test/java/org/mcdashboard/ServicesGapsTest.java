/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mcdashboard.config.ConfigView;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.stats.PluginStats;

/**
 * The wiring in {@link Services} that a running server never shows: stopping something that never started, and the
 * live-feed counter before and after the listeners are up. A started-but-unused {@link MCDashboardCore} always stops a
 * fully started {@code Services}, so those two cases need the services themselves.
 */
class ServicesGapsTest {
    @TempDir
    Path data;

    private Services services;

    @AfterEach
    void stopEverything() {
        if (services != null) services.stop();
    }

    private static Map<String, Object> nested(Object... kv) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            String[] path = ((String) kv[i]).split("\\.");
            Map<String, Object> m = root;
            for (int j = 0; j < path.length - 1; j++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> next = (Map<String, Object>) m.computeIfAbsent(path[j], k -> new LinkedHashMap<String, Object>());
                m = next;
            }
            m.put(path[path.length - 1], kv[i + 1]);
        }
        return root;
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private Settings settings(Object... kv) {
        return Settings.load(ConfigView.of(nested(kv)), m -> {
        });
    }

    private Services services(Settings settings, FakePlatform platform) {
        SnakeYamlCodec yaml = new SnakeYamlCodec();
        DataFolder files = new DataFolder(data, yaml, "defaults", List.of("player.yml"), platform.log());
        return new Services(platform, settings, files, new DashboardStore(files.resolve("dashboards"), yaml, platform.log()::warning),
                () -> false, new PluginStats(Clock.systemUTC()), null);
    }

    @Test
    void stoppingServicesThatNeverStartedIsQuietAndChangesNothing() {
        FakePlatform platform = new FakePlatform();
        services = services(settings(), platform);
        assertEquals(0, services.liveConnections(), "no listener is running yet");
        assertNull(services.tlsFingerprint());
        services.stop();
        services.stop(); // a second stop, and a stop after a failed start, must not complain
        assertTrue(platform.logged.isEmpty(), platform.logged.toString());
    }

    @Test
    void theLiveFeedCounterCountsTheListenerThatIsUp() throws Exception {
        FakePlatform platform = new FakePlatform();
        services = services(settings("server.enabled", true, "server.host", "127.0.0.1", "server.port", freePort(),
                "server.tls.mode", "off", "server.websocket.port", freePort()), platform);
        assertTrue(services.start(), platform.logged.toString());
        assertNull(services.tunnel(), "no tunnel is configured here");
        assertEquals(0, services.liveConnections(), "the live feed is up but no client is connected");
        services.stop();
        assertEquals(0, services.liveConnections(), "and after stopping it again");
    }

    @Test
    void theStatsGroupIsRecognisedByItsValueNotItsId() {
        List<PlaceholderDefinition> defs = PlaceholderDefinition.parseAll(List.of(
                Map.of("id", "tps", "value", "%mcdashboard_tps%", "group", "MCDashboard"),
                Map.of("id", "health", "value", "%player_health%", "group", "MCDashboard"),
                Map.of("id", "other", "value", "%mcdashboard_tps%", "group", "Players"),
                Map.of("id", "plain", "value", "%mcdashboard_tps%"),
                Map.of("id", "picture", "type", "IMAGE", "source", "url:https://example.org/a.png", "group", "MCDashboard")),
                m -> fail(m));
        assertTrue(Services.isStatsEntry(defs.get(0)), "the MCDashboard group offers its own values");
        assertFalse(Services.isStatsEntry(defs.get(1)), "a PlaceholderAPI value in that group is the owner's own choice");
        assertFalse(Services.isStatsEntry(defs.get(2)), "another group is not the built-in one");
        assertFalse(Services.isStatsEntry(defs.get(3)), "no group, so nothing to offer");
        assertFalse(Services.isStatsEntry(defs.get(4)), "an image carries no value to offer");
    }
}
