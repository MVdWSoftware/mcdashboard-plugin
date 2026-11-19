/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.dashboard;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mcdashboard.SnakeYamlCodec;

class DashboardGapsTest {
    @TempDir
    Path dir;

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void tileOptionsKeepOnlyWhatIsValid() {
        assertNull(TileLook.options("x"));
        assertNull(TileLook.options(map("unknown", 1)));
        assertNull(TileLook.icon(null));
        assertNull(TileLook.icon("x".repeat(70) + ":a"));
        assertNull(TileLook.icon("no-colon"));
        assertEquals("fa6-solid:heart", TileLook.icon(" FA6-solid:heart "));
        List<Object> rules = new ArrayList<>();
        rules.add("junk");
        rules.add(map("when", "sideways", "value", 1, "color", "red"));
        rules.add(map("when", "above", "value", "x", "color", "red"));
        rules.add(map("when", "above", "value", Double.NaN, "color", "red"));
        rules.add(map("when", "above", "value", 1, "color", 5));
        rules.add(map("when", "above", "value", 1, "color", "nocolor"));
        rules.add(map("when", "below", "value", 2, "color", "red"));
        for (int i = 0; i < 20; i++) rules.add(map("when", "above", "value", i, "color", "red"));
        Map<String, Object> o = TileLook.options(map("style", "hearts", "color", "red", "thresholds", rules, "decimals", 9, "compact", true,
                "unit", "x".repeat(30), "unitSize", "m", "unitColor", "red", "prefix", "p".repeat(20), "hideTitle", true, "align", "end",
                "size", "l", "card", "plain", "target", 5, "invertTrend", true, "count", 99, "fit", "fill", "position", "top", "padding", "s",
                "smoothing", "pixelated", "markerIcon", "fa6-solid:map", "speed", "fast",
                "background", map("color", "red", "gradient", "blue", "url", "https://x.example/a.png", "dim", 5, "blur", 99, "fit", "tile")));
        assertEquals(4, o.get("decimals"));
        assertEquals(16, ((String) o.get("unit")).length());
        assertEquals(8, ((String) o.get("prefix")).length());
        assertEquals(8, ((List<?>) o.get("thresholds")).size() <= 8 ? 8 : 0);
        assertEquals(20, o.get("count"));
        assertEquals(5.0, o.get("target"));
        Map<?, ?> bg = (Map<?, ?>) o.get("background");
        assertEquals(0.9, bg.get("dim"));
        assertEquals(20.0, bg.get("blur"));
        assertEquals("tile", bg.get("fit"));
        // wrong types and values are dropped one by one
        assertNull(TileLook.options(map("style", 5, "color", "nocolor", "thresholds", List.of(), "decimals", "x", "compact", "yes", "unit", 5,
                "unitSize", "xl", "unitColor", "nocolor", "prefix", "", "hideTitle", false, "align", "left", "target", Double.NaN, "invertTrend", false,
                "count", "x", "markerIcon", "bad", "background", "x", "fit", 5)));
        assertNull(TileLook.options(map("background", map("color", "nocolor", "gradient", "nocolor"))));
        assertNotNull(TileLook.options(map("background", map("gradient", "red"))));
        assertEquals(1, TileLook.options(map("count", 0)).get("count"));
    }

    @Test
    void backgroundImagesNeedHttps() {
        assertNull(TileLook.backgroundImage("x"));
        assertNull(TileLook.backgroundImage(map("dim", 1)));
        assertNull(TileLook.backgroundImage(map("url", "http://x.example/a.png")));
        assertNull(TileLook.backgroundImage(map("url", "https://x.example/" + "a".repeat(3000))));
        var img = TileLook.backgroundImage(map("url", " https://x.example/a.png ", "dim", Double.NaN, "blur", "x", "fit", "stretch"));
        assertEquals(0.4, img.dim());
        assertEquals(0.0, img.blur());
        assertEquals("cover", img.fit());
        assertEquals("contain", TileLook.backgroundImage(map("url", "https://x.example/a.png", "fit", "contain", "dim", -1, "blur", -1)).fit());
    }

    @Test
    void localizedTextsResolveInEveryFallbackOrder() {
        assertNull(LocalizedText.of(null));
        assertNull(LocalizedText.of(" "));
        assertNull(LocalizedText.parse(null));
        assertNull(LocalizedText.parse(map()));
        assertNull(LocalizedText.parse(map("xx", "text", "nl", " ", "fr", null)));
        assertEquals("5", LocalizedText.parse(5).resolve(List.of(), "en"));
        LocalizedText t = LocalizedText.parse(map("nl", "Hoi", "de", "Hallo"));
        assertTrue(t.isLocalized());
        assertEquals("Hoi", t.resolve(List.of("fr", "nl"), "en"));
        assertEquals("Hallo", t.resolve(List.of(), "de"));
        assertEquals("Hoi", t.resolve(List.of(), "en"), "supported order when the default is missing too");
        assertEquals("plain", LocalizedText.of("plain").resolve(List.of("nl"), "en"));
        assertFalse(LocalizedText.of("plain").isLocalized());
    }

    @Test
    void languagesParseAcceptHeaders() {
        assertNull(Languages.normalise(null));
        assertNull(Languages.normalise("xx"));
        assertNull(Languages.normalise("eng"));
        assertEquals("es", Languages.normalise("es_419"));
        assertEquals(List.of(), Languages.preferred(null));
        assertEquals(List.of(), Languages.preferred(" "));
        assertEquals(List.of("fr", "nl", "en"), Languages.preferred("nl;q=0.8, fr-CA, en;q=0.1, xx, de;q=0, es;q=abc, it;q=1, fr;other=1"));
        assertEquals("fr", Languages.resolve("fr", "nl"));
        assertEquals("nl", Languages.resolve(null, "nl"));
        assertEquals("en", Languages.normaliseOrDefault("zz"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dashboardsBuiltFromOddYaml() {
        Dashboard d = Dashboard.fromMap("x", map("background", "  ", "items", Arrays.asList("junk", map("placeholder", null), map("placeholders", Arrays.asList(null, " ", "a"), "widget", null),
                map("placeholder", " b ", "row", 3))));
        assertEquals(2, d.items().size());
        assertEquals("x", d.name().resolve(List.of(), "en"));
        assertEquals("text", d.items().get(0).widget());
        assertEquals(List.of("a"), d.items().get(0).placeholders());
        assertEquals("b", d.items().get(1).placeholder());
        assertNull(d.toJson(List.of(), "en").get("background"));
        assertEquals(0, Dashboard.fromMap("y", map()).items().size());
        assertEquals("stone", ((Map<String, Object>) Dashboard.fromMap("z", map("background", " Stone ")).toJson(List.of(), "en")).get("background"));
    }

    @Test
    void storesSaveInMemoryAndToFilesAndReportBrokenFiles() throws IOException {
        DashboardStore memory = DashboardStore.inMemory(List.of(Dashboard.fromMap("b", map("items", List.of(map("placeholder", "a", "widget", "text"))))));
        memory.save(Dashboard.fromMap("default", map("items", List.of(map("placeholder", "a")))), null);
        memory.save(Dashboard.fromMap("b", map("name", "B2")), null);
        assertEquals(List.of("default", "b"), memory.all().stream().map(Dashboard::id).toList());
        assertThrows(IllegalArgumentException.class, () -> memory.save(Dashboard.fromMap("Bad Id", map()), null));

        List<String> warnings = new ArrayList<>();
        DashboardStore files = new DashboardStore(dir.resolve("d"), new SnakeYamlCodec(), warnings::add);
        assertTrue(files.all().isEmpty());
        Dashboard full = new Dashboard("full", LocalizedText.parse(map("en", "Full")), LocalizedText.of("desc"), 5, "stone",
                TileLook.backgroundImage(map("url", "https://x.example/b.png")), List.of(
                new Dashboard.Item(List.of("a", "b"), "list", LocalizedText.of("T"), 0, 0, 2, 1, "fa6-solid:heart", Map.of("style", "hearts")),
                new Dashboard.Item(List.of("a"), "text", null, 1, 0, 1, 1)));
        files.save(full, "Up<script>-1");
        files.save(Dashboard.fromMap("default", map("items", List.of(map("placeholder", "a")))), null);
        String yaml = Files.readString(dir.resolve("d/full.yml"));
        assertTrue(yaml.contains("Uploaded from the MCDashboard app by Upscript-1."), yaml);
        assertTrue(yaml.contains("owner") && yaml.contains("background-image") && yaml.contains("hearts"));
        assertTrue(Files.readString(dir.resolve("d/default.yml")).contains("Saved by the MCDashboard plugin."));
        assertEquals(List.of("default", "full"), files.all().stream().map(Dashboard::id).toList());
        Files.writeString(dir.resolve("d/broken.yml"), "- not a mapping");
        assertEquals(3, files.all().size());
        assertTrue(warnings.get(0).contains("Could not read dashboards/broken.yml"), warnings.toString());
    }
}
