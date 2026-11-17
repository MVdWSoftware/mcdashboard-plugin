/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.nukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mcdashboard.config.ConfigView;
import org.mcdashboard.placeholder.PlaceholderDefinition;

/** The defaults this platform ships, read through the YAML library the platform provides. */
class NukkitYamlTest {
    private final NukkitYaml yaml = new NukkitYaml();

    @Test
    void invalidDocumentsAreIllegalArguments() {
        assertThrows(IllegalArgumentException.class, () -> yaml.parse("a: [unclosed"));
        assertThrows(IllegalArgumentException.class, () -> yaml.parse("- just\n- a list\n"));
        assertEquals(Map.of(), yaml.parse(""));
    }

    @Test
    void dumpedMapsReadBackTheSame() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("name", "Staff");
        doc.put("items", List.of(Map.of("placeholder", "tps")));
        assertTrue(yaml.dump(doc).contains("name: Staff"));
        assertEquals(doc, yaml.parse(yaml.dump(doc)));
    }

    @Test
    void theNukkitPlaceholderDefaultsUseNukkitNamesAndParse() throws Exception {
        String text;
        try (var in = getClass().getResourceAsStream("/nukkit/placeholders.yml")) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ConfigView view = ConfigView.of(yaml.parse(text));
        List<String> problems = new java.util.ArrayList<>();
        List<PlaceholderDefinition> defs = PlaceholderDefinition.parseAll(view.getMapList("placeholders"), problems::add);
        assertEquals(List.of(), problems);
        assertEquals(List.of("online", "tps", "max-players", "player-name", "player-health"), defs.stream().map(PlaceholderDefinition::id).toList());
        assertEquals("%server_tps%", defs.get(1).value(), "PowerNukkitX has no %server_tps_1%");
        assertEquals("%player%", defs.get(3).value(), "PowerNukkitX has no %player_name%");
        assertTrue(view.getMapList("catalog").isEmpty(), "the PlaceholderAPI for PowerNukkitX cannot list placeholders");
    }

    @Test
    void everyDashboardItemReferencesAPlaceholderTheDefaultsDefine() throws Exception {
        String defsText;
        try (var in = getClass().getResourceAsStream("/nukkit/placeholders.yml")) {
            defsText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var ids = PlaceholderDefinition.parseAll(ConfigView.of(yaml.parse(defsText)).getMapList("placeholders"), m -> { })
                .stream().map(PlaceholderDefinition::id).toList();
        for (String dashboard : List.of("player", "server")) {
            try (var in = getClass().getResourceAsStream("/dashboards/" + dashboard + ".yml")) {
                var d = org.mcdashboard.dashboard.Dashboard.fromMap(dashboard, yaml.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                for (var item : d.items()) assertTrue(ids.containsAll(item.placeholders()), dashboard + ": " + item.placeholders());
            }
        }
    }
}
