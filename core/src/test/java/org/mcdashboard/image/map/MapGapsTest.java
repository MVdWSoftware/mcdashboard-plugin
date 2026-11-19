/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.image.map;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mcdashboard.image.ImageCache;

class MapGapsTest {
    HttpServer server;
    String base;
    final AtomicReference<byte[]> tile = new AtomicReference<>();
    final long[] now = {1_000_000};
    final Clock clock = new Clock() {
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId z) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(now[0]);
        }
    };

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = tile.get();
            if (body == null) {
                ex.sendResponseHeaders(404, -1);
            } else {
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    static byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, 0xFF336699);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static Map<String, Object> block(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private MapSource source(String x, String z, org.mcdashboard.placeholder.ValueSource values) {
        MapSpec spec = MapSpec.parse("squaremap", block("url", base + "/sq", "world", "w", "x", x, "z", z, "size", 64));
        return new MapSource(spec, new ImageCache(clock), values, 300_000);
    }

    private static void bad(String provider, Object block, String part) {
        var e = assertThrows(IllegalArgumentException.class, () -> MapSpec.parse(provider, block));
        assertTrue(e.getMessage().contains(part), e.getMessage());
    }

    @Test
    void specsAreValidated() {
        Map<String, Object> ok = block("url", "https://map.example.com", "world", "w", "map", "m", "x", 1, "z", 2);
        bad("squaremap", "x", "needs a map: block");
        bad("squaremap", block("nope", 1), "unknown key");
        bad("squaremap", block("world", "w", "x", 1, "z", 1), "map url");
        bad("squaremap", block("url", "https://a/%p%", "world", "w", "x", 1, "z", 1), "map url");
        bad("squaremap", block("url", "https://a", "world", "w", "z", 1), "x is required");
        bad("squaremap", block("url", "https://a", "world", "w", "x", 1), "z is required");
        bad("squaremap", block("url", "https://a", "world", "w", "x", 1e12, "z", 1), "out of range");
        bad("squaremap", block("url", "https://a", "world", "w", "x", "abc", "z", 1), "must be a number");
        bad("squaremap", block("url", "https://a", "world", "w", "x", "%p%" + "a".repeat(300), "z", 1), "too long");
        bad("squaremap", block("url", "https://a", "world", "w", "x", 1, "z", 1, "size", 10), "size");
        bad("squaremap", block("url", "https://a", "world", "w", "x", 1, "z", 1, "size", 100.5), "size");
        bad("squaremap", block("url", "https://a", "world", "w", "x", 1, "z", 1, "size", "big"), "size");
        bad("squaremap", block("url", "https://a", "world", "..", "x", 1, "z", 1), "world may only contain");
        bad("squaremap", block("url", "https://a", "world", "bad name", "x", 1, "z", 1), "world may only contain");
        bad("squaremap", block("url", "https://a", "x", 1, "z", 1), "world is required");
        bad("pl3xmap", block("url", "https://a", "world", "w", "x", 1, "z", 1), "map is required");
        bad("pl3xmap", block("url", "https://a", "world", "w", "map", "m", "x", 1, "z", 1, "format", "bmp"), "format must be one of");
        bad("bluemap", block("url", "https://a", "x", 1, "z", 1), "map is required");
        bad("dynmap", block("url", "https://a", "world", "w", "x", 1, "z", 1), "map is required");
        bad("dynmap", block("url", "https://a", "world", "w", "map", "flat", "x", 1, "z", 1, "perspective", "weird"), "perspective");
        bad("dynmap", block("url", "https://a", "world", "w", "map", "flat", "x", 1, "z", 1, "perspective", "iso_S_10_lowres"), "inclination");
        assertThrows(IllegalArgumentException.class, () -> MapSpec.provider(null));
        assertEquals("squaremap", MapSpec.provider(" SquareMap "));
        assertEquals("pl3xmap", MapSpec.parse("pl3xmap", with(ok, "format", "jpg")).provider());
        assertEquals("bluemap", MapSpec.parse("bluemap", ok).provider());
        assertEquals("bluemap", MapSpec.parse("bluemap", block("url", "https://a/", "world", "w", "x", 1, "z", 1)).provider());
        assertEquals("dynmap", MapSpec.parse("dynmap", with(ok, "perspective", "default")).provider());
        assertFalse(MapSpec.parse("squaremap", ok).dynamic());
        assertTrue(MapSpec.parse("squaremap", with(ok, "x", "%player_x%")).dynamic());
        assertTrue(MapSpec.parse("squaremap", with(ok, "z", "%player_z%")).dynamic());
        assertEquals(12.5, MapSpec.number("§a1,2_ 12.5".replace("1,2_ ", "")));
        assertEquals(1000.0, MapSpec.number("1,000"));
        assertNull(MapSpec.number(null));
        assertNull(MapSpec.number("x"));
        assertNull(MapSpec.number("NaN"));
        assertNull(MapSpec.number("1e12"));
    }

    private static Map<String, Object> with(Map<String, Object> m, String k, Object v) {
        Map<String, Object> c = new LinkedHashMap<>(m);
        c.put(k, v);
        return c;
    }

    @Test
    void everyProviderDescribesItsTiles() {
        Map<String, Object> ok = block("url", "https://map.example.com", "world", "w", "map", "m", "x", 1, "z", 2);
        for (String id : MapSpec.PROVIDERS) {
            TileProvider t = MapSpec.parse(id, ok).tiles();
            assertEquals(id, t.id());
            assertTrue(t.tileSize() > 0);
            assertTrue(t.maxZoom() >= 0);
            assertEquals(2, t.pixel(10, 20, 0).length);
            assertFalse(t.tilePath(0, 0, 0).startsWith("/"));
        }
        assertEquals("tiles/w/1/m/-2_3.png", MapSpec.parse("pl3xmap", ok).tiles().tilePath(-2, 3, 1));
        assertEquals("maps/m/tiles/1/x1/2/3/z-1/0.png", MapSpec.parse("bluemap", ok).tiles().tilePath(123, -10, 0));
    }

    @Test
    void aTileThatCannotBeReadOrIsTooSmallIsJustMissing() throws Exception {
        tile.set("\u0089PNG\r\n\u001a\n garbage".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        assertTrue(source("0", "0", (t, u) -> null).load(null).isEmpty(), "undecodable");
        tile.set(png(2, 2));
        var img = source("1", "1", (t, u) -> null).load(null).orElseThrow();
        assertNotNull(img.bytes(), "a tiny tile leaves most of the picture transparent");
        tile.set(png(300, 300));
        assertTrue(source("2", "2", (t, u) -> null).load(null).isPresent());
    }

    @Test
    void anInterruptedRenderGivesNothing() throws Exception {
        tile.set(png(512, 512));
        Thread.currentThread().interrupt();
        try {
            assertTrue(source("3", "3", (t, u) -> null).load(null).isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void failuresAreRememberedAndTheLastGoodPictureIsServed() throws Exception {
        tile.set(png(512, 512));
        MapSource src = source("%x%", "5", (t, u) -> u == null ? null : t.replace("%x%", u));
        assertTrue(src.load(null).isEmpty(), "no player, nothing expanded");
        assertTrue(src.load("7").isPresent());
        assertTrue(src.load("7").isPresent(), "cached picture");
        tile.set(null);
        now[0] += 300_001;
        assertTrue(src.load("7").isPresent(), "stale picture while the map is down");
        assertTrue(src.load("7").isPresent(), "the failure is cached; the stale picture stays");
        assertTrue(src.load("5000").isEmpty(), "another centre with nothing to fall back on");
        assertTrue(src.load("5000").isEmpty(), "the failure is cached");
        assertTrue(src.load(null).isEmpty());
        now[0] += 30_001;
        tile.set(png(512, 512));
        assertTrue(src.load("5000").isPresent());
        assertTrue(source("1", "1", (t, u) -> "abc").load("u").isPresent());
        assertTrue(source("%x%", "1", (t, u) -> "%x%").load("u").isEmpty(), "an unexpanded template is no coordinate");
    }

    @Test
    void theCachesStayBounded() throws Exception {
        tile.set(png(512, 512));
        MapSource src = source("%x%", "5", (t, u) -> t.replace("%x%", u));
        for (int i = 0; i < 300; i++) src.load(String.valueOf(i * 64));
        now[0] += 400_000;
        for (int i = 0; i < 300; i++) src.load(String.valueOf(i * 64 + 1));
        assertTrue(src.load("5").isPresent());
    }

    @Test
    void largeNoisePicturesAreEncodedAsJpegAndAnImpossibleOneIsRefused() {
        int[] noise = new int[512 * 512];
        java.util.Random r = new java.util.Random(1);
        for (int i = 0; i < noise.length; i++) noise[i] = 0xFF000000 | r.nextInt(0xFFFFFF);
        byte[] jpeg = MapSource.encode(noise, 512);
        assertNotNull(jpeg);
        assertTrue(jpeg.length <= org.mcdashboard.image.ImageFormat.MAX_BYTES);
        assertNull(MapSource.encode(new int[10], 512), "a buffer that does not match the size");
    }
}
