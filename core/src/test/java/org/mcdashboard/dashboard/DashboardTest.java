/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mcdashboard.SnakeYamlCodec;

class DashboardTest {
    private static Map<String, String> languages(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return out;
    }

    @Test
    void plainTextIsTheSameInEveryLanguage() {
        Dashboard d = Dashboard.fromMap("player", Map.of("name", "Player", "description", "Your stats"));
        assertEquals("Player", d.name().resolve(List.of("nl"), "en"));
        assertEquals("Your stats", d.description().resolve(List.of("de"), "en"));
        assertFalse(d.name().isLocalized());
        assertTrue(d.name().languages().isEmpty());
    }

    @Test
    void aLanguageMapFollowsTheCallerThenTheServerDefault() {
        Dashboard d = Dashboard.fromMap("player", Map.of(
                "name", languages("en", "Player", "nl", "Speler", "de", "Spieler"),
                "description", languages("en", "Your stats", "nl", "Jouw statistieken")));

        assertEquals("Speler", d.name().resolve(Languages.preferred("nl-BE,nl;q=0.9,en;q=0.8"), "en"));
        assertEquals("Spieler", d.name().resolve(Languages.preferred("ja,de;q=0.5"), "en"));
        assertEquals("Jouw statistieken", d.description().resolve(List.of(), "nl"));
        assertEquals("Player", d.name().resolve(List.of("fr"), "en"), "no French: the server default");
        assertEquals("Player", d.name().resolve(List.of("fr"), "fr"), "the default has no French either: English");
    }

    @Test
    void itemTitlesAreTranslatedAndMissingOnesStayAbsent() {
        Dashboard d = Dashboard.fromMap("server", Map.of("name", "Server", "items", List.of(
                Map.of("placeholder", "online", "widget", "gauge", "title", languages("en", "Players", "es", "Jugadores")),
                Map.of("placeholder", "tps", "widget", "gauge"))));

        Map<String, Object> spanish = d.toJson(List.of("es"), "en");
        var items = (List<?>) spanish.get("items");
        assertEquals("Jugadores", ((Map<?, ?>) items.get(0)).get("title"));
        assertFalse(((Map<?, ?>) items.get(1)).containsKey("title"));
        assertEquals("Server", spanish.get("name"));
    }

    @Test
    void permissionIsDerivedFromTheFileName() {
        Dashboard d = Dashboard.fromMap("admin", Map.of("name", "Admin", "permission", "something.else"));
        assertEquals("mcdashboard.dashboard.admin", d.permission(), "the file name wins over an explicit permission");
        assertEquals("mcdashboard.dashboard.admin", d.toJson(List.of(), "en").get("permission"));
    }

    @Test
    void shippedPresetsParseAndCarryEveryShippedLanguage() throws Exception {
        for (String id : List.of("player", "server")) {
            try (var in = getClass().getResourceAsStream("/dashboards/" + id + ".yml")) {
                assertNotNull(in, id + ".yml is on the classpath");
                Dashboard d = Dashboard.fromMap(id, new SnakeYamlCodec().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                assertTrue(d.name().isLocalized());
                String nl = d.name().resolve(Languages.preferred("nl-BE"), "en");
                assertEquals(id.equals("player") ? "Speler" : "Server", nl);
                assertTrue(d.languages().containsAll(Languages.SUPPORTED), id + " translates its name into every shipped language");
            }
        }
    }

    @Test
    void onlySupportedLanguagesAreKeptAndPlainValuesStillParse() {
        LocalizedText text = LocalizedText.parse(languages("en", " Players ", "xx", "Spelers", "NL", "Spelers"));
        assertEquals("Players", text.resolve(List.of("en"), "en"));
        assertEquals("Spelers", text.resolve(List.of("nl"), "en"));
        assertEquals(Map.of("en", "Players", "nl", "Spelers"), text.translations());
        assertNull(LocalizedText.parse("  "));
        assertNull(LocalizedText.parse(Map.of("xx", "niets")));
    }

    @Test
    void acceptLanguageIsParsedWithWeightsAndRegions() {
        assertEquals(List.of("fr", "en"), Languages.preferred("fr-CA,fr;q=0.9,en;q=0.8"));
        assertEquals(List.of("de"), Languages.preferred("de;q=0.2,ja"));
        assertEquals(List.of(), Languages.preferred("ja,xx;q=0.5"));
        assertEquals(List.of(), Languages.preferred(null));
        assertEquals("nl", Languages.resolve("nl-NL", "en"));
        assertEquals("en", Languages.resolve("ja", "en"));
        assertEquals("en", Languages.normaliseOrDefault("xx"));
        assertEquals("es", Languages.normaliseOrDefault("es_419"));
    }

    @Test
    void iconsOptionsAndPicturesArePassedOnOnlyWhenWellFormed() {
        Map<String, Object> rule = Map.of("when", "below", "value", 18, "color", "orange");
        Map<String, Object> bad = Map.of("when", "between", "value", 1, "color", "red");
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("style", "hearts");
        options.put("color", "#ff0000");
        options.put("thresholds", List.of(rule, bad));
        options.put("decimals", 9);
        options.put("card", "default");
        options.put("count", 40);
        options.put("script", "<x>");
        Dashboard d = Dashboard.fromMap("look", Map.of(
                "name", "Look",
                "background-image", Map.of("url", "https://cdn.example.com/bg.jpg", "dim", 2, "blur", 4),
                "items", List.of(
                        Map.of("placeholder", "tps", "widget", "progress", "icon", "Game-Icons:Mining", "options", options),
                        Map.of("placeholder", "tps", "widget", "text", "icon", "not an icon", "options", "nope"))));

        Dashboard.Item first = d.items().get(0);
        assertEquals("game-icons:mining", first.icon());
        assertEquals(Map.of("style", "hearts", "thresholds", List.of(Map.of("when", "below", "value", 18.0, "color", "orange")),
                "decimals", 4, "count", 20), first.options());
        assertNull(d.items().get(1).icon());
        assertNull(d.items().get(1).options());
        assertEquals(new TileLook.BackgroundImage("https://cdn.example.com/bg.jpg", 0.9, 4, "cover"), d.backgroundImage());

        Map<String, Object> json = d.toJson(List.of(), "en");
        assertEquals("https://cdn.example.com/bg.jpg", ((Map<?, ?>) json.get("backgroundImage")).get("url"));
        Map<?, ?> item = (Map<?, ?>) ((List<?>) json.get("items")).get(0);
        assertEquals("game-icons:mining", item.get("icon"));
        assertFalse(((Map<?, ?>) ((List<?>) json.get("items")).get(1)).containsKey("icon"));

        for (String url : List.of("http://x.com/a.png", "javascript:alert(1)", "https://x.com/a\") b.png")) {
            assertNull(TileLook.backgroundImage(Map.of("url", url)), url);
        }
        assertNotNull(TileLook.backgroundImage(Map.of("url", "https://x.com/a.png")));
    }
}
