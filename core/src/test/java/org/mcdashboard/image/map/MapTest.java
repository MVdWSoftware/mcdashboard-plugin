/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.image.map;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mcdashboard.image.ImageCache;
import org.mcdashboard.image.ImageFormat;
import org.mcdashboard.image.ImageSource;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.placeholder.PlaceholderService;
import org.mcdashboard.placeholder.ValueSource;

class MapTest {
    HttpServer server;
    String base;
    final AtomicInteger hits = new AtomicInteger();
    /** Squaremap tiles served at zoom folder 3 (the most detailed with the default max-zoom): {i, j} -> tile id. */
    final Map<String, Integer> squaremapTiles = new ConcurrentHashMap<>();
    final Set<String> requested = ConcurrentHashMap.newKeySet();
    volatile boolean down;

    final long[] now = {1_000_000};
    final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now[0]); }
    };

    private static final Pattern SQUAREMAP = Pattern.compile("/sq/tiles/w/3/(-?\\d+)_(-?\\d+)\\.png");

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            String path = ex.getRequestURI().getPath();
            requested.add(path);
            byte[] body = null;
            if (!down) {
                Matcher m = SQUAREMAP.matcher(path);
                if (m.matches()) {
                    Integer id = squaremapTiles.get(m.group(1) + "," + m.group(2));
                    if (id != null) body = png(gridTile(512, id));
                } else if (path.startsWith("/dyn/tiles/")) {
                    body = png(noiseTile(128, path.hashCode()));
                }
            }
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

    /** Every pixel says which tile it is from and where: {@code id << 18 | y << 9 | x}. */
    static BufferedImage gridTile(int size, int id) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) img.setRGB(x, y, 0xFF000000 | id << 18 | y << 9 | x);
        return img;
    }

    static BufferedImage noiseTile(int size, long seed) {
        Random r = new Random(seed);
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) img.setRGB(x, y, r.nextInt());
        return img;
    }

    static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static BufferedImage read(byte[] bytes) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    static Map<String, Object> block(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    // --- coordinate math and URLs per provider ---

    @Test
    void squaremapTilesAreRegionsAtTheMostDetailedLevel() {
        var p = new SquaremapProvider("minecraft_overworld", 3);
        assertArrayEquals(new double[]{0.5, 0.5}, p.pixel(0.5, 0.5, 0));
        assertArrayEquals(new double[]{-0.25, 300.25}, p.pixel(-0.5, 600.5, 1));
        assertEquals("tiles/minecraft_overworld/3/0_0.png", p.tilePath(0, 0, 0));
        assertEquals("tiles/minecraft_overworld/1/-1_2.png", p.tilePath(-1, 2, 2), "zoom 2 out of 3 is folder 1");
        // block 2048 at zoom 2: 4 blocks per pixel, 512-pixel tiles = 2048 blocks per tile
        assertEquals(1, Math.floorDiv((long) Math.floor(p.pixel(2048.5, 0, 2)[0]), 512));
        assertEquals(-1, Math.floorDiv((long) Math.floor(p.pixel(-0.5, 0, 0)[0]), 512));
    }

    @Test
    void pl3xmapZoomZeroIsOnePixelPerBlock() {
        var p = new Pl3xMapProvider("world", "vintage_story", "png", 3);
        assertArrayEquals(new double[]{-2.25, 2.75}, p.pixel(-4.5, 5.5, 1));
        assertEquals("tiles/world/1/vintage_story/-1_0.png", p.tilePath(-1, 0, 1));
        assertEquals("tiles/world/0/basic/3_-4.jpg", new Pl3xMapProvider("world", "basic", "jpg", 3).tilePath(3, -4, 0));
    }

    @Test
    void bluemapUsesLowresLodsAndDigitFolders() {
        assertEquals("x1/2/z-3", BlueMapProvider.pathFromCoords(12, -3));
        assertEquals("x0/z0", BlueMapProvider.pathFromCoords(0, 0));
        assertEquals("x-1/0/0/z4/5", BlueMapProvider.pathFromCoords(-100, 45));
        var p = new BlueMapProvider("world", 2);
        assertEquals("maps/world/tiles/1/x-1/z0.png", p.tilePath(-1, 0, 0), "zoom 0 is lod 1");
        assertEquals("maps/world/tiles/3/x0/z1.png", p.tilePath(0, 1, 2));
        assertArrayEquals(new double[]{2500.5 / 5, 0.1}, p.pixel(2500.5, 0.5, 1), 1e-9);
        assertEquals(1, Math.floorDiv((long) p.pixel(2500.5, 0, 1)[0], 500), "lod 2 tiles cover 2500 blocks");
    }

    @Test
    void dynmapFlatMapMatchesTheWebMap() {
        // iso_S_90_lowres (the default "flat" map): 4 pixels per block, +X right, +Z down
        double[] m = DynmapProvider.worldToMap("iso_S_90_lowres");
        assertArrayEquals(new double[]{4, 0, 0, 0, 0, -4}, new double[]{m[0], m[1], m[2], m[3], m[4], m[5]}, 1e-9);
        var p = new DynmapProvider("world", "flat", "iso_S_90_lowres", 64, "png", 8);
        assertArrayEquals(new double[]{2, 130}, p.pixel(0.5, 0.5, 0), 1e-9);
        // map y 0..127 (blocks z -31..0) is file row 0, the web map's first row; z 0..31 is file row -1
        assertEquals("tiles/world/flat/0_-1/0_-1.png", p.tilePath(0, 1, 0));
        assertEquals("tiles/world/flat/0_0/0_0.png", p.tilePath(0, 0, 0));
        assertEquals("tiles/world/flat/-1_0/-1_0.png", p.tilePath(-1, 0, 0));
        assertEquals("tiles/world/flat/-2_-2/-40_-33.png", p.tilePath(-40, 33, 0));
        // zoomed out: names carry the first base tile, multiples of 2^zoom, rows counted downwards
        assertEquals("tiles/world/flat/0_-1/z_0_-2.png", p.tilePath(0, 1, 1));
        assertEquals("tiles/world/flat/-1_0/zzz_-8_0.png", p.tilePath(-1, 0, 3));
        assertArrayEquals(new double[]{1, 65}, p.pixel(0.5, 0.5, 1), 1e-9);
    }

    @Test
    void dynmapIsometricPerspectivesUseTheirOwnMatrix() {
        // iso_SE_30_hires: the default hires "surface" map. Height moves a point up the picture (map y grows).
        var p = new DynmapProvider("world", "t", "iso_SE_30_hires", 64, "png", 8);
        double[] low = p.toMap(0, 0, 0);
        double[] high = p.toMap(0, 100, 0);
        assertEquals(low[0], high[0], 1e-9, "straight up stays in the same column");
        assertTrue(high[1] > low[1]);
        // looking from the south east (the near corner at the bottom): +X goes right and down, +Z left and down
        double[] east = p.toMap(16, 0, 0);
        double[] south = p.toMap(0, 0, 16);
        assertTrue(east[0] > 0 && east[1] < 0, "east");
        assertTrue(south[0] < 0 && south[1] < 0, "south");
        assertEquals(16 * 16 * Math.cos(Math.toRadians(45)), east[0], 1e-6, "16 pixels per block, rotated 45 degrees");
        assertEquals(-16 * 16 * Math.sin(Math.toRadians(45)) * Math.sin(Math.toRadians(30)), east[1], 1e-6, "foreshortened by the inclination");
        assertThrows(IllegalArgumentException.class, () -> DynmapProvider.worldToMap("iso_S_10_lowres"));
        assertThrows(IllegalArgumentException.class, () -> DynmapProvider.worldToMap("custom"));
        assertDoesNotThrow(() -> DynmapProvider.worldToMap("default"));
    }

    // --- validation ---

    @Test
    void validatesTheBlock() {
        Map<String, Object> ok = block("url", "http://localhost:8080/", "world", "w", "x", 0, "z", "%z%");
        MapSpec spec = MapSpec.parse("squaremap", ok);
        assertEquals("http://localhost:8080", spec.url(), "trailing slash removed");
        assertEquals(256, spec.size());
        assertEquals(0, spec.zoom());
        assertTrue(spec.dynamic());
        assertEquals("squaremap", spec.provider());

        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("unknown provider", "googlemaps");
        for (var e : bad.entrySet()) {
            assertThrows(IllegalArgumentException.class, () -> MapSpec.parse(e.getValue(), ok), e.getKey());
        }
        assertThrows(IllegalArgumentException.class, () -> MapSpec.parse("squaremap", null), "no block");
        assertThrows(IllegalArgumentException.class, () -> MapSpec.parse("squaremap", "flat"), "not a map");
        List<Map<String, Object>> invalid = List.of(
                with(ok, "size", 32), with(ok, "size", 600), with(ok, "size", 100.5),
                with(ok, "zoom", 4), with(ok, "zoom", -1), with(ok, "max-zoom", 9),
                with(ok, "url", "http://%host%/"), with(ok, "url", "ftp://host"), with(ok, "url", "http://a/b?c=d"),
                with(ok, "url", "http://user@host/"), with(ok, "url", "http://host/%path%"),
                with(ok, "world", null), with(ok, "world", "../etc"), with(ok, "world", "a/b"), with(ok, "world", ".."),
                with(ok, "x", null), with(ok, "x", "abc"), with(ok, "z", 40_000_000), with(ok, "colour", "red"));
        for (Map<String, Object> m : invalid) {
            assertThrows(IllegalArgumentException.class, () -> MapSpec.parse("squaremap", m), m.toString());
        }
        assertEquals(5, MapSpec.parse("squaremap", with(with(ok, "max-zoom", 5), "zoom", 5)).zoom());
        var dyn = block("url", "https://map.example.com", "world", "world", "map", "flat", "x", "1,024", "z", -3);
        assertEquals("dynmap", MapSpec.parse("dynmap", dyn).provider());
        assertThrows(IllegalArgumentException.class, () -> MapSpec.parse("dynmap", with(dyn, "map", null)));
        assertThrows(IllegalArgumentException.class, () -> MapSpec.parse("dynmap", with(dyn, "perspective", "iso_UP_90_lowres")));
        assertThrows(IllegalArgumentException.class, () -> MapSpec.parse("dynmap", with(dyn, "format", "webp")));
        assertThrows(IllegalArgumentException.class, () -> MapSpec.parse("pl3xmap", with(dyn, "map", null)), "pl3xmap needs a renderer");
        assertEquals("bluemap", MapSpec.parse("BlueMap", with(dyn, "map", null)).provider(), "bluemap falls back to world as map id");
        assertThrows(IllegalArgumentException.class, () -> MapSpec.parse("bluemap", with(dyn, "zoom", 5)));
        assertEquals(1024.0, MapSpec.number("§a1,024"));
        assertNull(MapSpec.number("NaN"));
    }

    @Test
    void placeholderDefinitionsCarryTheBlock() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "spawn-map");
        m.put("type", "IMAGE");
        m.put("source", "map:squaremap");
        m.put("map", block("url", base + "/sq", "world", "w", "x", 0, "z", 0, "size", 64));
        PlaceholderDefinition def = PlaceholderDefinition.fromMap(m);
        assertNotNull(def.map());
        assertTrue(def.encode());
        m.remove("map");
        var e = assertThrows(IllegalArgumentException.class, () -> PlaceholderDefinition.fromMap(m));
        assertTrue(e.getMessage().contains("spawn-map"), e.getMessage());
        m.put("source", "map:google");
        assertThrows(IllegalArgumentException.class, () -> PlaceholderDefinition.fromMap(m));
        assertThrows(IllegalArgumentException.class, () -> ImageSource.validate("map:"));
        assertDoesNotThrow(() -> ImageSource.validate("map:dynmap"));
    }

    private static Map<String, Object> with(Map<String, Object> m, String k, Object v) {
        Map<String, Object> c = new LinkedHashMap<>(m);
        if (v == null) c.remove(k);
        else c.put(k, v);
        return c;
    }

    // --- stitching against a local tile server ---

    private MapSource squaremap(String x, String z, int size, ValueSource values) {
        MapSpec spec = MapSpec.parse("squaremap", block("url", base + "/sq", "world", "w", "x", x, "z", z, "size", size));
        return new MapSource(spec, new ImageCache(clock), values, 300_000);
    }

    @Test
    void stitchesAndCropsTilesAroundTheBlock() throws Exception {
        squaremapTiles.put("0,0", 1);
        squaremapTiles.put("-1,0", 2);
        squaremapTiles.put("0,-1", 3); // -1,-1 is missing (404)
        var src = squaremap("0", "0", 64, (t, u) -> null);
        BufferedImage img = read(src.load(null).orElseThrow().bytes());
        assertEquals(64, img.getWidth());
        assertEquals(64, img.getHeight());
        // block (0, 0) is at picture pixel (32, 32): left edge is grid pixel -32
        assertEquals(0xFF000000 | 1 << 18, img.getRGB(32, 32), "tile 0,0 pixel 0,0");
        assertEquals(0xFF000000 | 1 << 18 | 8 << 9 | 8, img.getRGB(40, 40));
        assertEquals(0xFF000000 | 2 << 18 | 8 << 9 | 511, img.getRGB(31, 40), "tile -1,0, last column");
        assertEquals(0xFF000000 | 3 << 18 | 511 << 9 | 5, img.getRGB(37, 31), "tile 0,-1, last row");
        assertEquals(0, img.getRGB(10, 10) >>> 24, "missing tile is transparent");
        assertTrue(requested.contains("/sq/tiles/w/3/-1_-1.png"));
        assertEquals(4, requested.size());
    }

    @Test
    void cachesThePictureAndServesTheLastGoodCopy() {
        squaremapTiles.put("0,0", 1);
        var src = squaremap("100", "100", 64, (t, u) -> null);
        byte[] first = src.load(null).orElseThrow().bytes();
        int after = hits.get();
        assertEquals(1, after, "one tile covers the square");
        src.load(null);
        assertEquals(after, hits.get(), "cached");
        down = true;
        now[0] += 300_001;
        assertArrayEquals(first, src.load(null).orElseThrow().bytes(), "last good copy while the map is down");
        assertTrue(hits.get() > after);
        down = false;
        now[0] += 30_001;
        assertTrue(src.load(null).isPresent());
    }

    @Test
    void nothingAtAllIsUnavailable() {
        var src = squaremap("5000", "5000", 64, (t, u) -> null);
        assertTrue(src.load(null).isEmpty());
    }

    @Test
    void coordinatesExpandPerPlayer() throws Exception {
        squaremapTiles.put("0,0", 1);
        squaremapTiles.put("1,0", 4);
        var src = squaremap("%x%", "10", 64, (t, u) -> u == null ? null : t.replace("%x%", u.equals("a") ? "10" : "600"));
        assertTrue(src.load(null).isEmpty(), "nothing expanded without a player");
        BufferedImage a = read(src.load("a").orElseThrow().bytes());
        BufferedImage b = read(src.load("b").orElseThrow().bytes());
        assertEquals(1, (a.getRGB(32, 32) >> 18) & 0x3F);
        assertEquals(4, (b.getRGB(32, 32) >> 18) & 0x3F, "x 600 is in the next region");
        assertEquals(0xFF000000 | 4 << 18 | 10 << 9 | (600 - 512), b.getRGB(32, 32));
    }

    @Test
    void largePicturesFallBackToJpeg() throws Exception {
        MapSpec spec = MapSpec.parse("dynmap", block("url", base + "/dyn", "world", "world", "map", "flat", "x", 0, "z", 0,
                "size", 512));
        var src = new MapSource(spec, new ImageCache(clock), (t, u) -> null, 300_000);
        byte[] bytes = src.load(null).orElseThrow().bytes();
        assertEquals("image/jpeg", ImageFormat.sniff(bytes), "noise does not fit 256 KB as PNG");
        assertTrue(bytes.length <= ImageFormat.MAX_BYTES);
        assertEquals(512, read(bytes).getWidth());
        // 512 pixels at 4 per block from block (0.5, 0.5): grid -254..257 horizontally -> tiles -2..2, rows -124..387 -> -1..3
        assertTrue(requested.contains("/dyn/tiles/world/flat/-1_-1/-2_-3.png"), requested.toString());
        assertTrue(requested.contains("/dyn/tiles/world/flat/0_0/2_1.png"), requested.toString());
        assertEquals(25, requested.size());
    }

    @Test
    void placeholderServiceServesTheMap() {
        squaremapTiles.put("0,0", 1);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "spawn");
        m.put("type", "IMAGE");
        m.put("source", "map:squaremap");
        m.put("map", block("url", base + "/sq", "world", "w", "x", 100, "z", 100, "size", 64));
        var svc = new PlaceholderService((t, u) -> null, clock, 0, List.of(PlaceholderDefinition.fromMap(m)),
                Path.of("images"), new ImageCache(clock));
        var v = svc.read("spawn", null).orElseThrow();
        assertEquals("image/png", ImageFormat.sniff(Base64.getUrlDecoder().decode(v.value())));
        assertEquals(300_000, svc.intervalMs(svc.definition("spawn").orElseThrow()));
    }
}
