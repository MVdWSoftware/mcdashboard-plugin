/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.mcdashboard.image.ImageCache;
import org.mcdashboard.json.Json;
import org.mcdashboard.web.SenMl;

class ListPlaceholderTest {
    private static Map<String, Object> entry() {
        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("name", "%lb_{n}_name%");
        columns.put("value", "%lb_{n}_value%");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "top");
        m.put("type", "LIST");
        m.put("rows", 5);
        m.put("columns", columns);
        m.put("empty", List.of("---", "N/A"));
        m.put("history", 60);
        return m;
    }

    private static PlaceholderService service(PlaceholderDefinition def, ValueSource source, List<String> seen) {
        ValueSource recording = (t, u) -> {
            seen.add(t);
            return source.resolve(t, u);
        };
        return new PlaceholderService(recording, Clock.systemUTC(), 0, List.of(def), Path.of("images"), new ImageCache(Clock.systemUTC()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(PlaceholderService s) {
        var v = s.read("top", null).orElseThrow();
        return (Map<String, Object>) Json.parse(SenMl.record(s.definition("top").orElseThrow(), v).get("vs").toString());
    }

    @Test
    void parsesRowsColumnsAndIgnoresHistory() {
        PlaceholderDefinition d = PlaceholderDefinition.fromMap(entry());
        assertEquals(PlaceholderDefinition.Type.LIST, d.type());
        assertEquals(5, d.list().rows());
        assertEquals(List.of("name", "value"), new ArrayList<>(d.list().columns().keySet()));
        assertEquals(0, d.historyMinutes());
        assertEquals(List.of("name", "value"), d.toJson().get("columns"));
        assertEquals(5, d.toJson().get("rows"));

        Map<String, Object> defaults = entry();
        defaults.remove("rows");
        assertEquals(10, PlaceholderDefinition.fromMap(defaults).list().rows());
        defaults.put("rows", 500);
        assertEquals(50, PlaceholderDefinition.fromMap(defaults).list().rows());
    }

    @Test
    void rejectsBadColumns() {
        Map<String, Object> noCols = entry();
        noCols.remove("columns");
        assertThrows(IllegalArgumentException.class, () -> PlaceholderDefinition.fromMap(noCols));
        Map<String, Object> badName = entry();
        badName.put("columns", Map.of("Name", "%x_{n}%"));
        assertThrows(IllegalArgumentException.class, () -> PlaceholderDefinition.fromMap(badName));
        Map<String, Object> badValue = entry();
        badValue.put("columns", Map.of("name", "hello %x%"));
        assertThrows(IllegalArgumentException.class, () -> PlaceholderDefinition.fromMap(badValue));
        Map<String, Object> tooMany = entry();
        Map<String, Object> seven = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) seven.put("c" + i, "%x_{n}%");
        tooMany.put("columns", seven);
        assertThrows(IllegalArgumentException.class, () -> PlaceholderDefinition.fromMap(tooMany));
    }

    @Test
    void expandsRowNumbersAndDropsOnlyTrailingEmptyRows() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("%lb_1_name%", "§6Steve");
        data.put("%lb_1_value%", "1,234");
        data.put("%lb_2_name%", "---");
        data.put("%lb_2_value%", "---");
        data.put("%lb_3_name%", "&aAlex");
        data.put("%lb_3_value%", "§c12.5");
        data.put("%lb_4_name%", "N/A");
        data.put("%lb_4_value%", "§7---");
        // row 5 stays unresolved
        List<String> seen = new ArrayList<>();
        var s = service(PlaceholderDefinition.fromMap(entry()), (t, u) -> data.get(t), seen);
        Map<String, Object> table = read(s);
        assertEquals(List.of("name", "value"), table.get("columns"));
        List<?> rows = (List<?>) table.get("rows");
        assertEquals(3, rows.size(), "trailing empty rows go, the empty row in the middle stays");
        assertEquals(List.of("§6Steve", 1234L), rows.get(0));
        assertEquals(List.of("---", "---"), rows.get(1));
        assertEquals(List.of("&aAlex", 12.5), rows.get(2));
        assertTrue(seen.contains("%lb_5_value%"));
    }

    @Test
    void formattedNumbersAreNotNumbers() {
        assertEquals(1234L, ListPlaceholderTestAccess.cell("§a1,234"));
        assertEquals(-3.5, ListPlaceholderTestAccess.cell("-3.5"));
        assertEquals("10k", ListPlaceholderTestAccess.cell("10k"));
        assertEquals("007", ListPlaceholderTestAccess.cell("007"));
        assertEquals("NaN", ListPlaceholderTestAccess.cell("NaN"));
        assertEquals("Infinity", ListPlaceholderTestAccess.cell("Infinity"));
        assertEquals(256, ((String) ListPlaceholderTestAccess.cell("x".repeat(400))).length());
    }

    @Test
    void nothingResolvedMeansNoValue() {
        var s = service(PlaceholderDefinition.fromMap(entry()), (t, u) -> null, new ArrayList<>());
        assertFalse(s.read("top", null).isPresent());
    }

    @Test
    void senMlCarriesTheJsonInVs() {
        var s = service(PlaceholderDefinition.fromMap(entry()), (t, u) -> t.contains("name") ? "Bob" : "7", new ArrayList<>());
        var rec = SenMl.record(s.definition("top").orElseThrow(), s.read("top", null).orElseThrow());
        assertEquals("{\"columns\":[\"name\",\"value\"],\"rows\":[[\"Bob\",7],[\"Bob\",7],[\"Bob\",7],[\"Bob\",7],[\"Bob\",7]]}", rec.get("vs"));
        assertFalse(rec.containsKey("v"));
    }

    /** {@code ListSpec.cell} is package private; this class is in the same package. */
    private static final class ListPlaceholderTestAccess {
        static Object cell(String raw) {
            return ListSpec.cell(raw);
        }
    }
}
