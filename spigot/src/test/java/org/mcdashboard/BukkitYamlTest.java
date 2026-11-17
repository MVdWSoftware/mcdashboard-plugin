/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mcdashboard.config.ConfigView;
import org.mcdashboard.dashboard.Dashboard;
import org.mcdashboard.dashboard.Languages;
import org.mcdashboard.history.HistorySettings;
import org.mcdashboard.history.SqlDialect;

/** Bukkit's YAML library feeds the core through plain maps, whatever it does with sections internally. */
class BukkitYamlTest {
    private final BukkitYaml yaml = new BukkitYaml();

    @Test
    void aDashboardReadThroughBukkitYamlKeepsItsLanguageMaps() {
        Dashboard d = Dashboard.fromMap("player", yaml.parse("""
                name: { en: Player, nl: Speler }
                description: { nl: Jouw statistieken }
                items:
                  - { placeholder: online, widget: gauge, title: { en: Players, nl: Spelers } }
                """));
        assertEquals("Speler", d.name().resolve(List.of("nl"), "en"));
        assertEquals("Jouw statistieken", d.description().resolve(List.of("nl"), "en"));
        assertEquals("Spelers", d.items().get(0).title().resolve(List.of("nl"), "en"));
    }

    @Test
    void parsedDocumentsContainNoBukkitTypes() {
        Map<String, Object> root = yaml.parse("a:\n  b:\n    - { c: 1 }\n    - [x, y]\n");
        Object a = root.get("a");
        assertInstanceOf(Map.class, a);
        Object list = ((Map<?, ?>) a).get("b");
        assertInstanceOf(List.class, list);
        assertInstanceOf(Map.class, ((List<?>) list).get(0));
        assertInstanceOf(List.class, ((List<?>) list).get(1));
        assertFalse(root.toString().contains("MemorySection"));
    }

    @Test
    void anInvalidDocumentIsAnIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> yaml.parse("a: [unclosed"));
    }

    @Test
    void dumpedMapsReadBackTheSame() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("placeholder", "tps");
        item.put("title", Map.of("en", "TPS", "nl", "TPS"));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("name", "Staff");
        doc.put("order", 3);
        doc.put("items", List.of(item));
        String text = yaml.dump(doc);
        assertTrue(text.contains("name: Staff"), text);
        assertEquals(doc, yaml.parse(text));
    }

    @Test
    void theShippedConfigAndPresetsParseThroughBukkit() throws Exception {
        String config;
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            config = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> warnings = new java.util.ArrayList<>();
        HistorySettings s = HistorySettings.parse(ConfigView.of(yaml.parse(config)).getSection("history"), "server", warnings::add);
        assertEquals(List.of(), warnings);
        assertEquals(30_000, s.intervalMs());

        HistorySettings custom = HistorySettings.parse(ConfigView.of(yaml.parse(
                "history:\n  storage:\n    type: postgresql\n    host: db\n  include: [\"tps\", \"ram-*\"]\n  sampling:\n    interval: 60\n"))
                .getSection("history"), "server", warnings::add);
        assertEquals(SqlDialect.POSTGRESQL, custom.storage().dialect());
        assertEquals(List.of("tps", "ram-*"), custom.include());
        assertEquals(List.of(), warnings);

        for (String id : List.of("player", "server")) {
            try (var in = getClass().getResourceAsStream("/dashboards/" + id + ".yml")) {
                Dashboard d = Dashboard.fromMap(id, yaml.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                assertTrue(d.languages().containsAll(Languages.SUPPORTED), id);
            }
        }
    }
}
