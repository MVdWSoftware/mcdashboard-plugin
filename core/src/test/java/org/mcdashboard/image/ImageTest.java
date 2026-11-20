/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.image;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.placeholder.PlaceholderService;

class ImageTest {
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    HttpServer server;
    final AtomicInteger hits = new AtomicInteger();
    String base;

    @Test
    void imagesCompareTheirBytesByContent() {
        Image image = new Image(new byte[]{1, 2}, 5);
        assertEquals(image, new Image(new byte[]{1, 2}, 5));
        assertEquals(image.hashCode(), new Image(new byte[]{1, 2}, 5).hashCode());
        assertNotEquals(image, new Image(new byte[]{1, 3}, 5));
        assertNotEquals(image, new Image(new byte[]{1, 2}, 6));
        assertFalse(image.equals("image"), "a different type is not equal");
        assertTrue(image.toString().contains("bytes=[1, 2]"), image.toString());
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serve("/ok.png", 200, PNG, "text/plain"); // Content-Type is ignored on purpose
        serve("/big.png", 200, bigPng(), "image/png");
        serve("/text", 200, "<html>not an image</html>".getBytes(), "image/png");
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().set("Location", "file:///etc/passwd");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/hop", ex -> {
            ex.getResponseHeaders().set("Location", "/ok.png");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void serve(String path, int status, byte[] body, String type) {
        server.createContext(path, ex -> {
            hits.incrementAndGet();
            ex.getResponseHeaders().set("Content-Type", type);
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
    }

    private static byte[] bigPng() {
        byte[] b = new byte[ImageFormat.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, b, 0, PNG.length);
        return b;
    }

    @Test
    void sniffsFormatsByMagicBytes() {
        assertEquals("image/png", ImageFormat.sniff(PNG));
        assertEquals("image/jpeg", ImageFormat.sniff(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0}));
        assertEquals("image/gif", ImageFormat.sniff("GIF89a....".getBytes()));
        assertEquals("image/gif", ImageFormat.sniff("GIF87a....".getBytes()));
        assertEquals("image/webp", ImageFormat.sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes()));
        assertNull(ImageFormat.sniff("RIFF\0\0\0\0WAVEfmt ".getBytes()));
        assertNull(ImageFormat.sniff("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes()));
        assertNull(ImageFormat.sniff(new byte[0]));
    }

    @Test
    void fetchesAnImageRegardlessOfContentType() {
        var cache = new ImageCache(Clock.systemUTC());
        assertArrayEquals(PNG, cache.get(base + "/ok.png", 1000).orElseThrow().bytes());
    }

    @Test
    void oversizeAndNonImageBodiesAreRejected() {
        var cache = new ImageCache(Clock.systemUTC());
        assertTrue(cache.get(base + "/big.png", 1000).isEmpty());
        assertTrue(cache.get(base + "/text", 1000).isEmpty());
        assertTrue(cache.get(base + "/missing", 1000).isEmpty());
    }

    @Test
    void redirectsToNonHttpAreRejectedButHttpRedirectsFollowed() {
        var cache = new ImageCache(Clock.systemUTC());
        assertTrue(cache.get(base + "/redirect", 1000).isEmpty());
        assertArrayEquals(PNG, cache.get(base + "/hop", 1000).orElseThrow().bytes());
        assertTrue(cache.get("file:///etc/passwd", 1000).isEmpty());
    }

    @Test
    void cacheHitDoesNotRefetchUntilTheTtlPasses() {
        long[] now = {1_000_000};
        Clock clock = new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId z) { return this; }
            public Instant instant() { return Instant.ofEpochMilli(now[0]); }
        };
        var cache = new ImageCache(clock);
        cache.get(base + "/ok.png", 5000);
        cache.get(base + "/ok.png", 5000);
        assertEquals(1, hits.get());
        now[0] += 5001;
        cache.get(base + "/ok.png", 5000);
        assertEquals(2, hits.get());
    }

    @Test
    void urlSourceExpandsTheTemplateForThePlayerOnly(@TempDir Path dir) {
        var cache = new ImageCache(Clock.systemUTC());
        var src = ImageSource.parse("url:" + base + "/ok.png?u=%player_uuid%", dir, cache,
                (tpl, uuid) -> uuid == null ? null : tpl.replace("%player_uuid%", uuid), 1000);
        assertTrue(src.load(null).isEmpty(), "no player, nothing expanded");
        assertArrayEquals(PNG, src.load("abc").orElseThrow().bytes());
        src.load("abc");
        assertEquals(1, hits.get());
        src.load("def");
        assertEquals(2, hits.get(), "cached per resolved URL");
    }

    @Test
    void fileSourceReadsReloadsAndStaysInsideTheFolder(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("logo.png");
        Files.write(f, PNG);
        var src = ImageSource.parse("file:logo.png", dir, new ImageCache(Clock.systemUTC()), (t, u) -> null, 1000);
        assertArrayEquals(PNG, src.load(null).orElseThrow().bytes());
        byte[] changed = PNG.clone();
        changed[8] = 9;
        Files.write(f, changed);
        Files.setLastModifiedTime(f, FileTime.fromMillis(Files.getLastModifiedTime(f).toMillis() + 5000));
        assertArrayEquals(changed, src.load(null).orElseThrow().bytes(), "reloaded after the file changed");
        Files.writeString(f, "not an image");
        Files.setLastModifiedTime(f, FileTime.fromMillis(Files.getLastModifiedTime(f).toMillis() + 5000));
        assertTrue(src.load(null).isEmpty());
        Files.write(dir.resolve("big.png"), bigPng());
        assertTrue(ImageSource.parse("file:big.png", dir, null, null, 0).load(null).isEmpty());
        assertTrue(ImageSource.parse("file:missing.png", dir, null, null, 0).load(null).isEmpty());
    }

    @Test
    void invalidSourcesAreRejected() {
        for (String bad : new String[]{"file:../secret.png", "file:a/../../b.png", "file:/etc/passwd", "file:..\\x.png", "file:",
                "file:C:/x.png", "url:file:///etc/passwd", "url:ftp://x/y.png", "url:http://%host%/x.png", "http://x/y.png", "logo.png"}) {
            assertThrows(IllegalArgumentException.class, () -> ImageSource.validate(bad), bad);
        }
        assertDoesNotThrow(() -> ImageSource.validate("file:sub/logo.png"));
        assertDoesNotThrow(() -> ImageSource.validate("url:https://mc-heads.net/avatar/%player_uuid%/64"));
        assertThrows(IllegalArgumentException.class,
                () -> PlaceholderDefinition.fromMap(Map.of("id", "x", "type", "IMAGE", "source", "file:../x.png")));
    }

    @Test
    void serviceServesImagesAsBase64UrlAndPerPlayer(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("logo.png"), PNG);
        var defs = PlaceholderDefinition.parseAll(List.of(
                Map.of("id", "logo", "type", "IMAGE", "source", "file:logo.png", "group", "Images"),
                Map.of("id", "head", "type", "IMAGE", "access", "PLAYER", "source", "url:" + base + "/ok.png?u=%player_uuid%")),
                m -> fail(m));
        var service = new PlaceholderService((t, u) -> u == null ? null : t.replace("%player_uuid%", u), Clock.systemUTC(), 0,
                defs, dir, new ImageCache(Clock.systemUTC()));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(PNG);
        assertEquals(expected, service.read("logo", null).orElseThrow().value());
        assertTrue(service.read("head", null).isEmpty(), "PLAYER images need a paired player");
        assertEquals(expected, service.read("head", "abc").orElseThrow().value());
        assertEquals(0, defs.get(0).historyMinutes());
        assertEquals(300_000, service.intervalMs(defs.get(1)));
        assertFalse(expected.contains("="));
    }
}
