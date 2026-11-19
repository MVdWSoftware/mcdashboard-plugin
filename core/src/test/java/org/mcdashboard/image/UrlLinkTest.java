/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.image;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.placeholder.PlaceholderService;
import org.mcdashboard.web.SenMl;

/** {@code url:} IMAGE sources with {@code encode: false}: the app gets the expanded https URL, the plugin fetches nothing. */
class UrlLinkTest {
    static Map<String, Object> entry(String source, Object encode) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "head");
        m.put("type", "IMAGE");
        m.put("access", "PLAYER");
        m.put("source", source);
        if (encode != null) m.put("encode", encode);
        return m;
    }

    @Test
    void encodeDefaultsToTrueAndFalseNeedsHttps() {
        assertTrue(PlaceholderDefinition.fromMap(entry("url:http://example.com/a.png", null)).encode());
        assertFalse(PlaceholderDefinition.fromMap(entry("url:https://mc-heads.net/avatar/%player_uuid%/64", false)).encode());
        var http = assertThrows(IllegalArgumentException.class,
                () -> PlaceholderDefinition.fromMap(entry("url:http://mc-heads.net/avatar/x/64", false)));
        assertTrue(http.getMessage().contains("https"), http.getMessage());
        assertThrows(IllegalArgumentException.class, () -> PlaceholderDefinition.fromMap(entry("file:logo.png", false)));
        Map<String, Object> map = entry("map:squaremap", false);
        map.put("map", Map.of("url", "https://map.example.com", "world", "w", "x", 0, "z", 0));
        var e = assertThrows(IllegalArgumentException.class, () -> PlaceholderDefinition.fromMap(map));
        assertTrue(e.getMessage().contains("always"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> PlaceholderDefinition.fromMap(entry("url:https://a.b/c", "no")));
    }

    @Test
    void expandsForThePlayerOnTheSameHost() {
        var link = new ImageSource.UrlLink("url:https://mc-heads.net/avatar/%player_uuid%/64",
                (t, u) -> u == null ? null : t.replace("%player_uuid%", u));
        assertNull(link.resolve(null), "nothing expanded without a player");
        assertEquals("https://mc-heads.net/avatar/abc/64", link.resolve("abc"));
        assertNull(link.resolve("x y"), "not a valid URL");
        var evil = new ImageSource.UrlLink("url:https://mc-heads.net/%p%", (t, u) -> "https://evil.example/x.png");
        assertNull(evil.resolve("p"), "the expanded URL must stay on the template's host");
        var http = new ImageSource.UrlLink("url:https://mc-heads.net/%p%", (t, u) -> "http://mc-heads.net/x.png");
        assertNull(http.resolve("p"));
        assertEquals("https://a.example/x.png", new ImageSource.UrlLink("url:https://a.example/x.png", (t, u) -> null).resolve(null));
    }

    @Test
    void sentAsVsWithoutFetching() {
        AtomicInteger resolves = new AtomicInteger();
        var def = PlaceholderDefinition.fromMap(entry("url:https://mc-heads.net/avatar/%player_uuid%/64", false));
        var svc = new PlaceholderService((t, u) -> {
            resolves.incrementAndGet();
            return t.replace("%player_uuid%", u);
        }, Clock.systemUTC(), 0, List.of(def), Path.of("images"), new ImageCache(Clock.systemUTC()));
        assertTrue(svc.read("head", null).isEmpty(), "PLAYER access");
        var v = svc.read("head", "abc").orElseThrow();
        assertEquals("https://mc-heads.net/avatar/abc/64", v.value());
        svc.read("head", "abc");
        assertEquals(1, resolves.get(), "cached for the interval like other values");
        Map<String, Object> rec = SenMl.record(def, v);
        assertEquals("https://mc-heads.net/avatar/abc/64", rec.get("vs"));
        assertFalse(rec.containsKey("vd"));
    }
}
