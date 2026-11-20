/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TileLookTest {
    @Test
    void newOptionsAreKeptWhenValid() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("fit", "cover");
        in.put("position", "top");
        in.put("padding", "none");
        in.put("smoothing", "pixelated");
        in.put("markerIcon", "Fa6-Solid:Sun");
        in.put("hideValue", true);
        in.put("speed", "fast");
        in.put("card", "default");
        in.put("biome", " snowy_plains ");
        Map<String, Object> out = TileLook.options(in);
        assertEquals("snowy_plains", out.get("biome"));
        assertEquals(null, TileLook.options(Map.of("biome", "<b>", "style", "end")).get("biome"));
        assertEquals(null, TileLook.options(Map.of("biome", 3, "style", "end")).get("biome"));
        assertEquals("cover", out.get("fit"));
        assertEquals("top", out.get("position"));
        assertEquals("none", out.get("padding"));
        assertEquals("pixelated", out.get("smoothing"));
        assertEquals("fa6-solid:sun", out.get("markerIcon"));
        assertEquals(true, out.get("hideValue"));
        assertEquals("fast", out.get("speed"));
        assertEquals("default", out.get("card"));
        assertEquals("plain", TileLook.options(Map.of("card", "plain")).get("card"));
    }

    @Test
    void invalidNewOptionsAreDropped() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("fit", "stretch");
        in.put("position", "left");
        in.put("padding", "xl");
        in.put("smoothing", 1);
        in.put("markerIcon", "not an icon");
        in.put("hideValue", false);
        in.put("speed", "ludicrous");
        in.put("background", Map.of("color", "pink", "url", "http://x.com/a.png"));
        assertNull(TileLook.options(in));
    }

    @Test
    void tileBackgroundKeepsOnlyValidParts() {
        Map<String, Object> bg = new LinkedHashMap<>();
        bg.put("color", "purple");
        bg.put("gradient", "nope");
        Map<String, Object> out = TileLook.options(Map.of("background", bg));
        assertEquals(Map.of("background", Map.of("color", "purple")), out);

        bg.put("gradient", "cyan");
        bg.put("url", "https://cdn.example.com/bg.png");
        bg.put("dim", 5);
        bg.put("blur", 4);
        bg.put("fit", "tile");
        Map<?, ?> full = (Map<?, ?>) TileLook.options(Map.of("background", bg)).get("background");
        assertEquals(Map.of("color", "purple", "gradient", "cyan", "url", "https://cdn.example.com/bg.png",
                "dim", 0.9, "blur", 4.0, "fit", "tile"), full);

        Map<?, ?> imageOnly = (Map<?, ?>) TileLook.options(Map.of("background", Map.of("url", "https://x.com/a.png"))).get("background");
        assertEquals(Map.of("url", "https://x.com/a.png", "dim", 0.4, "blur", 0.0, "fit", "cover"), imageOnly);
        assertNull(TileLook.options(Map.of("background", "red")));
    }
}
