/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.image.map;

import org.mcdashboard.image.Image;
import org.mcdashboard.image.ImageCache;
import org.mcdashboard.image.ImageFormat;
import org.mcdashboard.image.ImageSource;
import org.mcdashboard.placeholder.ValueSource;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A {@code map:} IMAGE source: fetches the tiles of a web map that cover a {@code size} square centred on a block,
 * copies them into one picture and encodes it as PNG (JPEG when the PNG is over {@link ImageFormat#MAX_BYTES}).
 * Tiles go through the {@link ImageCache} like {@code url:} images (http(s) only, 5 second timeout, cached for
 * {@code ttlMs}); a missing tile (404, unreadable) stays transparent. The finished picture is cached per centre for
 * {@code ttlMs}; when no tile at all can be read the last good picture for that caller is served. Blocks the calling
 * thread (tiles are fetched in parallel, at most about 10 seconds), so never call it on the server main thread.
 * Pixels are copied by hand into a {@link BufferedImage}: no {@code Graphics2D}, so it works on headless servers.
 */
public final class MapSource implements ImageSource {
    /** Tiles can be larger than the pictures sent to the app; they are only read to make one. */
    static final int MAX_TILE_BYTES = 1024 * 1024;
    private static final long DEADLINE_MS = 10_000;
    private static final long FAILURE_TTL_MS = 30_000;
    private static final int MAX_ENTRIES = 256;
    private static final ExecutorService FETCHERS = fetchers();

    private record Entry(Image image, long storedMs, long ttlMs) {
    }

    private final MapSpec spec;
    private final ImageCache cache;
    private final ValueSource values;
    private final long ttlMs;
    private final Map<String, Entry> pictures = new ConcurrentHashMap<>();
    private final Map<String, Image> lastGood = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public MapSource(MapSpec spec, ImageCache cache, ValueSource values, long ttlMs) {
        this.spec = spec;
        this.cache = cache;
        this.values = values;
        this.ttlMs = ttlMs;
    }

    private static ExecutorService fetchers() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(6, 6, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "MCDashboard map tiles");
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    @Override
    public Optional<Image> load(String playerUuid) {
        String who = playerUuid == null ? "" : playerUuid;
        Double x = coordinate(spec.x(), playerUuid);
        Double z = coordinate(spec.z(), playerUuid);
        if (x == null || z == null) return Optional.ofNullable(lastGood.get(who));
        long now = cache.clock().millis();
        String key = Math.floor(x) + "," + Math.floor(z);
        Entry e = pictures.get(key);
        if (e != null && now - e.storedMs() < e.ttlMs() && e.image() != null) return remember(who, e.image());
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            e = pictures.get(key);
            now = cache.clock().millis();
            if (e != null && now - e.storedMs() < e.ttlMs()) {
                return e.image() != null ? remember(who, e.image()) : Optional.ofNullable(lastGood.get(who));
            }
            Image img = render(Math.floor(x) + 0.5, Math.floor(z) + 0.5, now);
            if (img == null) {
                Image stale = e == null ? null : e.image();
                put(key, new Entry(stale, now, FAILURE_TTL_MS));
                return Optional.ofNullable(stale != null ? stale : lastGood.get(who));
            }
            put(key, new Entry(img, now, ttlMs));
            return remember(who, img);
        }
    }

    private Optional<Image> remember(String who, Image img) {
        if (lastGood.size() >= MAX_ENTRIES && !lastGood.containsKey(who)) lastGood.clear();
        lastGood.put(who, img);
        return Optional.of(img);
    }

    private void put(String key, Entry e) {
        if (pictures.size() >= MAX_ENTRIES && !pictures.containsKey(key)) {
            long now = cache.clock().millis();
            pictures.entrySet().removeIf(x -> now - x.getValue().storedMs() >= x.getValue().ttlMs());
            if (pictures.size() >= MAX_ENTRIES) pictures.clear();
        }
        pictures.put(key, e);
        if (locks.size() > 2 * MAX_ENTRIES) locks.keySet().retainAll(pictures.keySet());
    }

    /** A number, or a template expanded for this player like a {@code url:} source; null when it does not resolve. */
    private Double coordinate(String template, String playerUuid) {
        if (template.indexOf('%') < 0) return MapSpec.number(template);
        String v = values.resolve(template, playerUuid);
        if (v == null || v.equals(template)) return null;
        return MapSpec.number(v);
    }

    /** Stitches the picture centred on the block position {@code (x, z)}; null when not a single tile could be read. */
    Image render(double x, double z, long now) {
        TileProvider tiles = spec.tiles();
        int size = spec.size();
        int t = tiles.tileSize();
        double[] c = tiles.pixel(x, z, spec.zoom());
        long left = (long) Math.floor(c[0] - size / 2.0);
        long top = (long) Math.floor(c[1] - size / 2.0);
        int i0 = (int) Math.floorDiv(left, t);
        int i1 = (int) Math.floorDiv(left + size - 1, t);
        int j0 = (int) Math.floorDiv(top, t);
        int j1 = (int) Math.floorDiv(top + size - 1, t);

        List<int[]> cells = new ArrayList<>();
        List<Callable<BufferedImage>> tasks = new ArrayList<>();
        for (int j = j0; j <= j1; j++) {
            for (int i = i0; i <= i1; i++) {
                String url = spec.url() + "/" + tiles.tilePath(i, j, spec.zoom());
                cells.add(new int[]{i, j});
                tasks.add(() -> decode(cache.get(url, ttlMs, MAX_TILE_BYTES).orElse(null)));
            }
        }
        List<Future<BufferedImage>> done;
        try {
            done = FETCHERS.invokeAll(tasks, DEADLINE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        }

        int[] out = new int[size * size]; // transparent
        int loaded = 0;
        for (int n = 0; n < cells.size(); n++) {
            BufferedImage tile = result(done.get(n));
            if (tile == null) continue;
            loaded++;
            long tileLeft = (long) cells.get(n)[0] * t;
            long tileTop = (long) cells.get(n)[1] * t;
            // The overlap of this tile with the picture, in grid pixels; only the tile's first t x t pixels are map.
            long gx0 = Math.max(left, tileLeft);
            long gx1 = Math.min(left + size, tileLeft + Math.min(t, tile.getWidth()));
            long gy0 = Math.max(top, tileTop);
            long gy1 = Math.min(top + size, tileTop + Math.min(t, tile.getHeight()));
            if (gx1 <= gx0 || gy1 <= gy0) continue;
            tile.getRGB((int) (gx0 - tileLeft), (int) (gy0 - tileTop), (int) (gx1 - gx0), (int) (gy1 - gy0),
                    out, (int) ((gy0 - top) * size + (gx0 - left)), size);
        }
        if (loaded == 0) return null;
        byte[] bytes = encode(out, size);
        return bytes == null ? null : new Image(bytes, now);
    }

    private static BufferedImage result(Future<BufferedImage> f) {
        if (f.isCancelled()) return null;
        try {
            return f.get(); // already done: invokeAll returned
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // a tile that is missing must not swallow the shutdown
            return null;
        } catch (ExecutionException e) {
            return null; // a failed tile is a missing tile
        }
    }

    private static BufferedImage decode(Image img) {
        if (img == null) return null;
        try {
            // ImageIO.read closes the stream; null for a format ImageIO cannot read (WebP): drawn as missing
            return ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(img.bytes())));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** PNG, or JPEG (transparent parts black) at falling quality when the PNG is too large; null if nothing fits. */
    static byte[] encode(int[] argb, int size) {
        try {
            BufferedImage png = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            png.setRGB(0, 0, size, size, argb, 0, size);
            byte[] bytes = write(png, "png", -1);
            if (bytes.length <= ImageFormat.MAX_BYTES) return bytes;
            int[] rgb = new int[argb.length];
            for (int i = 0; i < argb.length; i++) {
                int p = argb[i];
                int a = p >>> 24;
                rgb[i] = (((p >> 16) & 0xFF) * a / 255) << 16 | (((p >> 8) & 0xFF) * a / 255) << 8 | (p & 0xFF) * a / 255;
            }
            BufferedImage jpeg = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            jpeg.setRGB(0, 0, size, size, rgb, 0, size);
            for (float q : new float[]{0.85f, 0.7f, 0.5f, 0.3f}) {
                bytes = write(jpeg, "jpeg", q);
                if (bytes.length <= ImageFormat.MAX_BYTES) return bytes;
            }
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Writes through a memory cache, so ImageIO never creates temporary files. */
    private static byte[] write(BufferedImage img, String format, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (quality >= 0) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(img, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
