/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mcdashboard.config.ConfigView;

class StatsConfigTest {
    @TempDir
    Path data;

    private MCDashboardCore core;

    @AfterEach
    void stop() {
        if (core != null) core.stop();
    }

    @Test
    void offeringTheCountersToTheAppIsOffByDefault() {
        assertFalse(Settings.load(ConfigView.of(Map.of()), m -> {
        }).offerStatsToApp());
        assertTrue(Settings.load(ConfigView.of(Map.of("stats", Map.of("offer-to-app", true))), m -> {
        }).offerStatsToApp());
    }

    private String placeholders(String stats) throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Files.writeString(data.resolve("config.yml"), "server:\n  enabled: true\n  host: 127.0.0.1\n  port: " + port
                + "\n  tls:\n    mode: off\n  websocket:\n    enabled: false\nsecurity:\n  require-authentication: false\n" + stats);
        core = new MCDashboardCore(new FakePlatform(), new SnakeYamlCodec(), data);
        core.start();
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/placeholders"))
                .setHeader("User-Agent", "MCDashboard/1.0.0 (dev)").build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void theShippedCountersAreHiddenFromTheAppUnlessTheOwnerOptsIn() throws Exception {
        String body = placeholders("");
        assertTrue(body.contains("\"online\""), body);
        assertFalse(body.contains("mcdashboard-devices"), "stats are not offered by default");
        assertEquals(core.stats().placeholders(), core.stats().placeholders());
        int hidden = core.stats().placeholders();
        core.stop();
        core = null;

        String on = placeholders("stats:\n  offer-to-app: true\n");
        assertTrue(on.contains("mcdashboard-devices-online"), on);
        assertTrue(on.contains("mcdashboard-api-requests-per-minute"), on);
        assertEquals(hidden + 18, core.stats().placeholders());
        assertEquals("9.9.9", core.stats().version());
        assertEquals("off", core.stats().historyStorage());
        assertEquals(0, core.stats().devices());
        assertTrue(core.stats().apiRequests() >= 1, "the request above was counted");
        assertEquals(2, core.stats().dashboards(), "the two preset dashboards");
    }
}
