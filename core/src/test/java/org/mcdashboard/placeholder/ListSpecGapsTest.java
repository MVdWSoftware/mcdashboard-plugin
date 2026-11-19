/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ListSpecGapsTest {
    private static Map<String, Object> entry(Object rows, Object columns, Object empty) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (rows != null) m.put("rows", rows);
        if (columns != null) m.put("columns", columns);
        if (empty != null) m.put("empty", empty);
        return m;
    }

    private static void bad(Map<String, Object> m, String part) {
        var e = assertThrows(IllegalArgumentException.class, () -> ListSpec.parse("lb", m));
        assertTrue(e.getMessage().contains(part), e.getMessage());
    }

    private static Map<String, String> ordered() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", "%n_{n}%");
        m.put("score", "%s_{n}%");
        return m;
    }

    @Test
    void parsesAndRejects() {
        bad(entry("x", Map.of("a", "%p%"), null), "rows must be a number");
        bad(entry(5, null, null), "needs columns");
        bad(entry(5, "text", null), "needs columns");
        bad(entry(5, Map.of(), null), "needs columns");
        Map<String, String> seven = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) seven.put("c" + i, "%p" + i + "%");
        bad(entry(5, seven, null), "at most");
        bad(entry(5, Map.of("Bad Name", "%p%"), null), "invalid column name");
        Map<String, Object> nullTemplate = new LinkedHashMap<>();
        nullTemplate.put("a", null);
        bad(entry(5, nullTemplate, null), "needs a single %placeholder%");
        bad(entry(5, Map.of("a", "text %p% more"), null), "needs a single %placeholder%");
        assertEquals(1, ListSpec.parse("lb", entry(0, Map.of("a", "%p%"), null)).rows());
        assertEquals(50, ListSpec.parse("lb", entry(500, Map.of("a", "%p%"), null)).rows());
        assertEquals(10, ListSpec.parse("lb", entry(null, Map.of("a", "%p%"), null)).rows());
        assertEquals(List.of("none", "x"), ListSpec.parse("lb", entry(null, Map.of("a", "%p%"), java.util.Arrays.asList("&7none", null, " x "))).empty());
        assertEquals(List.of("None"), ListSpec.parse("lb", entry(null, Map.of("a", "%p%"), "§aNone")).empty());
        assertEquals(List.of(), ListSpec.parse("lb", entry(null, Map.of("a", "%p%"), null)).empty());
    }

    @Test
    void resolvesRowsAndCellsAndDropsTrailingEmptyOnes() {
        ListSpec s = ListSpec.parse("lb", entry(4, ordered(), List.of("-")));
        String json = s.resolve(t -> switch (t) {
            case "%n_1%" -> "Steve";
            case "%s_1%" -> "1,250";
            case "%n_2%" -> "Alex";
            case "%s_2%" -> "&a3.5";
            case "%n_3%" -> "-";
            case "%s_3%" -> "-";
            default -> t; // unresolved placeholders stay as they are
        });
        assertEquals("{\"columns\":[\"name\",\"score\"],\"rows\":[[\"Steve\",1250],[\"Alex\",3.5]]}", json);
        assertNull(s.resolve(t -> t), "nothing resolved");
        assertNull(s.resolve(t -> null));
    }

    @Test
    void cellsAreNumbersOnlyWhenTheyAreOnes() {
        assertEquals(5L, ListSpec.cell("+5"));
        assertEquals("007", ListSpec.cell("007"));
        assertEquals(1.5, ListSpec.cell("1.5"));
        assertEquals(-2L, ListSpec.cell("-2"));
        assertEquals(0.5, ListSpec.cell(".5"));
        assertEquals("99999999999999999999", ListSpec.cell("99999999999999999999"));
        assertEquals("1e400", ListSpec.cell("1e400"));
        assertEquals("a".repeat(256), ListSpec.cell("a".repeat(300)));
        assertEquals(("1" + "0".repeat(400) + ".5").substring(0, 256), ListSpec.cell("1" + "0".repeat(400) + ".5"));
    }
}
