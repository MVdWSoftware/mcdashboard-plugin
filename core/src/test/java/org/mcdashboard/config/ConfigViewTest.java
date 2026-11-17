/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mcdashboard.SnakeYamlCodec;

class ConfigViewTest {
    private static ConfigView view(String yaml) {
        return ConfigView.of(new SnakeYamlCodec().parse(yaml));
    }

    @Test
    void readsByDottedPathAndFallsBackWhenMissingOrMistyped() {
        ConfigView c = view("""
                server:
                  name: Survival
                  port: 1553
                  polling: { enabled: false }
                  backlog: "25"
                  empty: null
                """);
        assertEquals("Survival", c.getString("server.name"));
        assertEquals("fallback", c.getString("server.missing", "fallback"));
        assertNull(c.getString("server.empty"), "a YAML null is absent");
        assertNull(c.getString("server.name.deeper"), "a scalar has no children");
        assertEquals(1553, c.getInt("server.port", 0));
        assertEquals(25, c.getInt("server.backlog", 0), "numbers written as text are accepted, like Bukkit does");
        assertEquals(7, c.getInt("server.name", 7), "text that is not a number is the default");
        assertFalse(c.getBoolean("server.polling.enabled", true));
        assertTrue(c.getBoolean("server.polling.missing", true));
        assertTrue(c.getBoolean("server.port", true), "only a real boolean counts");
    }

    @Test
    void listsKeepTheirScalarsAndMaps() {
        ConfigView c = view("""
                ids: [a, 2, true, { x: 1 }]
                hooks:
                  - { url: "https://example.org", secret: s }
                  - not-a-map
                text: plain
                """);
        assertEquals(List.of("a", "2", "true"), c.getStringList("ids"));
        assertEquals(List.of(), c.getStringList("text"));
        assertEquals(1, c.getMapList("hooks").size());
        assertEquals("https://example.org", c.getMapList("hooks").get(0).get("url"));
        assertEquals(List.of(), c.getMapList("missing"));
    }

    @Test
    void sectionIsAMapOrNull() {
        ConfigView c = view("history:\n  storage: { type: sqlite }\nflag: true\n");
        assertEquals(Map.of("type", "sqlite"), c.getSection("history.storage"));
        assertNull(c.getSection("flag"));
        assertNull(c.getSection("nothing"));
    }

    @Test
    void mergeLayersTheOwnersFileOverTheDefaultsKeyByKey() {
        var defaults = new SnakeYamlCodec().parse("server: { enabled: false, port: 1553 }\nsecurity: { max-devices: 5 }\n");
        var mine = new SnakeYamlCodec().parse("server: { enabled: true }\nextra: 1\n");
        ConfigView c = ConfigView.of(ConfigView.merge(defaults, mine));
        assertTrue(c.getBoolean("server.enabled", false), "the owner's value wins");
        assertEquals(1553, c.getInt("server.port", 0), "a key the file lacks keeps its default");
        assertEquals(5, c.getInt("security.max-devices", 0));
        assertEquals(1, c.getInt("extra", 0));
        assertFalse(defaults.toString().contains("extra"), "the defaults are not modified");
    }
}
