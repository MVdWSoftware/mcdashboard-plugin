/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SmallGapsTest {
    @TempDir
    Path dir;

    @Test
    void yamlRoundTripsAndRefusesNonMappings() {
        ProxyYaml y = new ProxyYaml();
        assertTrue(y.parse("").isEmpty());
        assertEquals("v", y.parse("k: v").get("k"));
        assertThrows(IllegalArgumentException.class, () -> y.parse("- a"));
        assertThrows(IllegalArgumentException.class, () -> y.parse("a: [unclosed"));
        assertTrue(y.dump(Map.of("k", List.of("a"))).contains("- a"));
    }

    @Test
    void tabCompletion() {
        assertEquals(ProxyCommands.complete(new String[0]), ProxyCommands.complete(new String[]{"x"}));
        assertEquals(List.of("on", "off"), ProxyCommands.complete(new String[]{"developer", ""}));
        assertEquals(List.of(), ProxyCommands.complete(new String[]{"link", "a"}));
        assertEquals(List.of(), ProxyCommands.complete(new String[]{"developer", "on", "x"}));
    }

    @Test
    void backendsAreValidated() {
        List<String> warnings = new ArrayList<>();
        String pin = "ab".repeat(32);
        var list = BackendConfig.parseAll(List.of(
                Map.of("name", "ok", "url", "https://a.example:8443/base/", "pin", pin, "key", "k"),
                Map.of("name", "OK", "url", "https://b.example"),
                Map.of("name", "bad name!", "url", "https://c.example"),
                Map.of("name", "x1", "url", "ht tp://bad"),
                Map.of("name", "x2", "url", "ftp://c.example"),
                Map.of("name", "x3", "url", "https://u:p@c.example"),
                Map.of("name", "x4", "url", "https://c.example?q=1"),
                Map.of("name", "x5", "url", "https://c.example#f"),
                Map.of("name", "x6", "url", "https://c.example", "pin", "zz"),
                Map.of("name", "x7", "url", "mailto:x")), warnings::add);
        assertEquals(1, list.size());
        assertEquals("https://a.example:8443/base", list.get(0).url().toString());
        assertEquals(9, warnings.size(), warnings.toString());
    }

    @Test
    void theNetworkSectionIsReadWithDefaultsAndLimits() throws Exception {
        List<String> warnings = new ArrayList<>();
        assertSame(ProxyConfig.DEFAULTS, ProxyConfig.load(dir, new ProxyYaml(), warnings::add));
        Files.writeString(dir.resolve("config.yml"), "network:\n  ping-interval: 1\n  federation-interval: 99999\n");
        var c = ProxyConfig.load(dir, new ProxyYaml(), warnings::add);
        assertEquals(5, c.pingIntervalSeconds());
        assertEquals(3600, c.federationIntervalSeconds());
        Files.writeString(dir.resolve("config.yml"), "- not a mapping");
        assertSame(ProxyConfig.DEFAULTS, ProxyConfig.load(dir, new ProxyYaml(), warnings::add));
        assertTrue(warnings.get(0).contains("Could not read the network section"));
    }
}
