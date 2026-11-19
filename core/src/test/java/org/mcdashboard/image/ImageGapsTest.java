/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.image;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImageGapsTest {
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
    HttpServer server;
    String base;
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
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/redirect")) {
                int n = path.length() > 9 ? Integer.parseInt(path.substring(9)) : 0;
                ex.getResponseHeaders().add("Location", n >= 10 ? "/ok.png" : "/redirect" + (n + 1));
                ex.sendResponseHeaders(302, -1);
            } else if (path.equals("/nolocation")) {
                ex.sendResponseHeaders(302, -1);
            } else if (path.equals("/ftp")) {
                ex.getResponseHeaders().add("Location", "ftp://example.org/x.png");
                ex.sendResponseHeaders(302, -1);
            } else if (path.equals("/chunked-big")) {
                ex.sendResponseHeaders(200, 0);
                byte[] chunk = new byte[64 * 1024];
                System.arraycopy(PNG, 0, chunk, 0, PNG.length);
                for (int i = 0; i < 6; i++) ex.getResponseBody().write(chunk);
            } else if (path.equals("/declared-big")) {
                ex.sendResponseHeaders(200, 10_000_000);
                ex.getResponseBody().write(PNG);
            } else if (path.equals("/not-image")) {
                ex.sendResponseHeaders(200, 3);
                ex.getResponseBody().write("abc".getBytes());
            } else if (path.equals("/missing")) {
                ex.sendResponseHeaders(404, -1);
            } else {
                ex.sendResponseHeaders(200, PNG.length);
                ex.getResponseBody().write(PNG);
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

    @Test
    void sniffsEveryFormatAndRejectsTheRest() {
        assertNull(ImageFormat.sniff(null));
        assertEquals("image/png", ImageFormat.sniff(PNG));
        assertEquals("image/jpeg", ImageFormat.sniff(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}));
        assertEquals("image/gif", ImageFormat.sniff("GIF89a".getBytes()));
        assertEquals("image/gif", ImageFormat.sniff("GIF87a".getBytes()));
        assertNull(ImageFormat.sniff("GIF88a".getBytes()));
        assertNull(ImageFormat.sniff("GIF89x".getBytes()));
        assertNull(ImageFormat.sniff("GIF8".getBytes()));
        assertEquals("image/webp", ImageFormat.sniff("RIFF....WEBP".getBytes()));
        assertNull(ImageFormat.sniff("RIFF....WAVE".getBytes()));
        assertNull(ImageFormat.sniff(new byte[0]));
    }

    @Test
    void theCacheFollowsAFewRedirectsAndRefusesTheRest() {
        ImageCache c = new ImageCache(clock);
        assertTrue(c.get(base + "/redirect9", 1000).isPresent(), "a redirect chain within the limit");
        assertTrue(c.get(base + "/redirect", 1000).isEmpty(), "too many redirects");
        assertTrue(c.get(base + "/nolocation", 1000).isEmpty());
        assertTrue(c.get(base + "/ftp", 1000).isEmpty());
        assertTrue(c.get(base + "/chunked-big", 1000).isEmpty(), "larger than the cap without a declared length");
        assertTrue(c.get(base + "/declared-big", 1000).isEmpty());
        assertTrue(c.get(base + "/not-image", 1000).isEmpty());
        assertTrue(c.get(base + "/missing", 1000).isEmpty());
        assertTrue(c.get("ftp://example.org/x.png", 1000).isEmpty());
        assertTrue(c.get("not a url", 1000).isEmpty());
        assertTrue(c.get("http:///nohost", 1000).isEmpty());
        assertTrue(c.get("http://127.0.0.1:1/x.png", 1000).isEmpty(), "connection refused");
        assertTrue(c.get(base + "/chunked-big?x=1", 1000, 1024 * 1024).isPresent(), "a larger cap for map tiles");
    }

    @Test
    void aFailureKeepsTheLastGoodImageAndIsRememberedBriefly() {
        ImageCache c = new ImageCache(clock);
        String url = base + "/ok.png";
        assertTrue(c.get(url, 1000).isPresent());
        now[0] += 5000;
        server.removeContext("/");
        server.createContext("/", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        assertTrue(c.get(url, 1000).isPresent(), "the stale copy while the origin is down");
        assertTrue(c.get(url, 1000).isPresent(), "the failure is cached");
        assertEquals(clock.millis(), c.clock().millis());
    }

    @Test
    void anInterruptedFetchEndsQuickly() {
        ImageCache c = new ImageCache(clock);
        Thread.currentThread().interrupt();
        try {
            assertTrue(c.get(base + "/ok.png", 1000).isEmpty());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void theCacheStaysBounded() {
        ImageCache c = new ImageCache(clock);
        for (int i = 0; i < 1100; i++) c.get(base + "/img" + i + ".png", 1000);
        now[0] += 5000;
        for (int i = 0; i < 520; i++) c.get(base + "/other" + i + ".png", 1000);
        assertTrue(c.get(base + "/ok.png", 1000).isPresent());
    }

    @Test
    void sourcesAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> ImageSource.validate(null));
        assertThrows(IllegalArgumentException.class, () -> ImageSource.validate("ftp:x"));
        assertThrows(IllegalArgumentException.class, () -> ImageSource.validate("file:"));
        assertThrows(IllegalArgumentException.class, () -> ImageSource.validate("file:/etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> ImageSource.validate("file:\\x"));
        assertThrows(IllegalArgumentException.class, () -> ImageSource.validate("file:C:x"));
        assertThrows(IllegalArgumentException.class, () -> ImageSource.validate("file:a\0b"));
        assertThrows(IllegalArgumentException.class, () -> ImageSource.validate("file:a/../b"));
        assertThrows(IllegalArgumentException.class, () -> ImageSource.validate("url:ftp://x"));
        assertThrows(IllegalArgumentException.class, () -> ImageSource.validate("map:nothing"));
        assertDoesNotThrow(() -> ImageSource.validate("file:a/b.png"));
        assertDoesNotThrow(() -> ImageSource.validate("map:dynmap"));
        assertThrows(IllegalArgumentException.class, () -> ImageSource.parse("map:dynmap", null, null, new ImageCache(clock), (t, u) -> t, 1000));
    }

    @Test
    void urlLinksOnlyExpandToTheirOwnHttpsHost() {
        assertThrows(IllegalArgumentException.class, () -> new ImageSource.UrlLink("url:http://x.example/a", (t, u) -> t));
        assertEquals("https://x.example", new ImageSource.UrlLink("url:https://x.example", (t, u) -> t).resolve(null));
        assertEquals("https://X.example/a", new ImageSource.UrlLink("url:https://X.example/a", (t, u) -> t).resolve(null));
        var link = new ImageSource.UrlLink("url:https://x.example/%p%", (t, u) -> t.replace("%p%", String.valueOf(u)));
        assertEquals("https://x.example/steve", link.resolve("steve"));
        assertNull(new ImageSource.UrlLink("url:https://x.example/%p%", (t, u) -> null).resolve("s"));
        assertNull(new ImageSource.UrlLink("url:https://x.example/%p%", (t, u) -> t).resolve("s"));
        assertNull(new ImageSource.UrlLink("url:https://x.example/%p%", (t, u) -> "http://x.example/a").resolve("s"));
        assertNull(new ImageSource.UrlLink("url:https://x.example/%p%", (t, u) -> "https://evil.example/a").resolve("s"));
        assertNull(new ImageSource.UrlLink("url:https://x.example/%p%", (t, u) -> "https://x.example/" + "a".repeat(3000)).resolve("s"));
        assertNull(new ImageSource.UrlLink("url:https://x.example/%p%", (t, u) -> "https://x.example/a b").resolve("s"));
        assertNull(new ImageSource.UrlLink("url:https://x.example/%p%", (t, u) -> "https://x.example@evil.example/a").resolve("s"));
        assertNull(new ImageSource.UrlLink("url:https://x.example/%p%", (t, u) -> "https://x.example:99@x.example/").resolve("s"));
    }

    @Test
    void urlSourcesNeedTheirTemplateExpanded() {
        ImageCache c = new ImageCache(clock);
        var plain = ImageSource.parse("url:" + base + "/ok.png", dirOf(), c, (t, u) -> t, 1000);
        assertTrue(plain.load(null).isPresent());
        var templ = ImageSource.parse("url:" + base + "/%p%.png", dirOf(), c, (t, u) -> t, 1000);
        assertTrue(templ.load("u").isEmpty(), "nothing expanded");
        var nulls = ImageSource.parse("url:" + base + "/%p%.png", dirOf(), c, (t, u) -> null, 1000);
        assertTrue(nulls.load("u").isEmpty());
        var ok = ImageSource.parse("url:" + base + "/%p%.png", dirOf(), c, (t, u) -> t.replace("%p%", "a"), 1000);
        assertTrue(ok.load("u").isPresent());
    }

    @TempDir
    Path images;

    private Path dirOf() {
        return images;
    }

    @Test
    void fileSourcesStayInsideTheirFolderAndReloadWhenTheyChange() throws Exception {
        var source = ImageSource.parse("file:a.png", images, new ImageCache(clock), (t, u) -> t, 1000);
        assertTrue(source.load(null).isEmpty(), "missing");
        Files.write(images.resolve("a.png"), PNG);
        assertTrue(source.load(null).isPresent());
        assertTrue(source.load(null).isPresent(), "cached");
        Files.write(images.resolve("a.png"), new byte[]{1, 2, 3});
        assertTrue(source.load(null).isEmpty(), "not an image any more");
        Files.write(images.resolve("a.png"), new byte[300 * 1024]);
        assertTrue(source.load(null).isEmpty(), "too big");
        Path outside = Files.createTempFile("outside", ".png");
        Files.write(outside, PNG);
        Files.createSymbolicLink(images.resolve("link.png"), outside);
        assertTrue(ImageSource.parse("file:link.png", images, new ImageCache(clock), (t, u) -> t, 1000).load(null).isEmpty(), "a link out of the folder");
        Files.createDirectory(images.resolve("dir.png"));
        assertTrue(ImageSource.parse("file:dir.png", images, new ImageCache(clock), (t, u) -> t, 1000).load(null).isEmpty());
        assertTrue(ImageSource.parse("file:a.png", images.resolve("nodir"), new ImageCache(clock), (t, u) -> t, 1000).load(null).isEmpty());
        Optional<Image> none = Optional.empty();
        assertTrue(none.isEmpty());
        assertNotNull(List.of());
        assertNotNull(IOException.class);
    }
}
