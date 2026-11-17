package org.mcdashboard.image;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetches images over http(s) and keeps them per resolved URL for a caller-chosen time. Blocks the calling thread
 * (at most about 5 s), so it must never be called from the server main thread. Redirects are followed by hand and only
 * to http(s); the body is capped at {@link ImageFormat#MAX_BYTES} and must be a PNG, JPEG, GIF or WebP.
 */
public final class ImageCache {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_ENTRIES = 512;
    private static final long FAILURE_TTL_MS = 30_000;

    private record Entry(Image image, long storedMs, long ttlMs) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Clock clock;
    private volatile HttpClient client;

    public ImageCache(Clock clock) {
        this.clock = clock;
    }

    /** The image at {@code url}, fetched when the cached copy is older than {@code ttlMs}. Empty when unavailable. */
    public Optional<Image> get(String url, long ttlMs) {
        Entry e = entries.get(url);
        if (e != null && fresh(e)) return Optional.ofNullable(e.image());
        Object lock = locks.computeIfAbsent(url, k -> new Object());
        synchronized (lock) {
            e = entries.get(url);
            if (e != null && fresh(e)) return Optional.ofNullable(e.image());
            Image img = fetch(url);
            Image stale = e == null ? null : e.image();
            if (img == null) {
                // Keep serving the last good image while the origin is down; remember the failure briefly.
                put(url, new Entry(stale, clock.millis(), FAILURE_TTL_MS));
                return Optional.ofNullable(stale);
            }
            put(url, new Entry(img, clock.millis(), ttlMs));
            return Optional.of(img);
        }
    }

    private boolean fresh(Entry e) {
        return clock.millis() - e.storedMs() < e.ttlMs();
    }

    private void put(String url, Entry e) {
        if (entries.size() >= MAX_ENTRIES) {
            entries.entrySet().removeIf(x -> !fresh(x.getValue()));
            if (entries.size() >= MAX_ENTRIES) {
                entries.clear();
                locks.clear();
            }
        }
        entries.put(url, e);
    }

    private HttpClient client() {
        HttpClient c = client;
        if (c == null) {
            c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(TIMEOUT).build();
            client = c;
        }
        return c;
    }

    private Image fetch(String url) {
        try {
            URI uri = URI.create(url);
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                if (!httpScheme(uri)) return null;
                HttpRequest req = HttpRequest.newBuilder(uri).timeout(TIMEOUT).header("Accept", "image/*").GET().build();
                HttpResponse<InputStream> res = client().send(req, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = res.body()) {
                    int status = res.statusCode();
                    if (status >= 300 && status < 400) {
                        String loc = res.headers().firstValue("Location").orElse(null);
                        if (loc == null) return null;
                        uri = uri.resolve(loc);
                        continue;
                    }
                    if (status != 200) return null;
                    long declared = res.headers().firstValueAsLong("Content-Length").orElse(-1);
                    if (declared > ImageFormat.MAX_BYTES) return null;
                    byte[] body = readCapped(in);
                    return body == null || ImageFormat.sniff(body) == null ? null : new Image(body, clock.millis());
                }
            }
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static boolean httpScheme(URI uri) {
        String s = uri.getScheme();
        return ("http".equalsIgnoreCase(s) || "https".equalsIgnoreCase(s)) && uri.getHost() != null;
    }

    /** Reads at most the size cap; null when the body is larger or takes longer than the timeout. */
    private static byte[] readCapped(InputStream in) throws IOException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > ImageFormat.MAX_BYTES || System.nanoTime() > deadline) return null;
        }
        return out.toByteArray();
    }
}
