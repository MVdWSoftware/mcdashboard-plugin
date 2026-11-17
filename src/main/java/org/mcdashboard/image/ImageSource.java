package org.mcdashboard.image;

import org.mcdashboard.placeholder.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where an IMAGE placeholder gets its bytes: {@code file:<relative path>} inside the images folder (reloaded when the
 * file changes) or {@code url:<template>} with PlaceholderAPI placeholders expanded for the paired player.
 */
public interface ImageSource {
    /** The current image for this player (null for server-wide placeholders), or empty when unavailable. */
    Optional<Image> load(String playerUuid);

    /** Throws {@link IllegalArgumentException} when {@code spec} is not a valid image source. */
    static void validate(String spec) {
        if (spec == null) throw new IllegalArgumentException("IMAGE needs a source");
        if (spec.startsWith("file:")) {
            relative(spec.substring(5));
        } else if (spec.startsWith("url:")) {
            if (!URL_TEMPLATE.matcher(spec.substring(4)).matches()) {
                throw new IllegalArgumentException("url source must be http(s)://host/... with a fixed host");
            }
        } else {
            throw new IllegalArgumentException("source must start with file: or url:");
        }
    }

    /**
     * @param ttlMs how long a fetched URL is cached (files are re-read when their modification time changes)
     */
    static ImageSource parse(String spec, Path imagesDir, ImageCache cache, ValueSource values, long ttlMs) {
        validate(spec);
        if (spec.startsWith("file:")) return new FileSource(imagesDir, relative(spec.substring(5)));
        return new UrlSource(spec.substring(4), cache, values, ttlMs);
    }

    Pattern URL_TEMPLATE = Pattern.compile("https?://[^/%\\s]+(/\\S*)?", Pattern.CASE_INSENSITIVE);

    private static String relative(String p) {
        String path = p.trim();
        if (path.isEmpty() || path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:.*")
                || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("image path must be relative: " + p);
        }
        for (String part : path.split("[/\\\\]")) {
            if (part.equals("..")) throw new IllegalArgumentException("image path must not contain '..': " + p);
        }
        return path;
    }

    /** A file under the images folder, cached until its modification time or size changes. */
    final class FileSource implements ImageSource {
        private final Path dir;
        private final String relative;
        private long mtime = -1;
        private long size = -1;
        private Image cached;

        FileSource(Path dir, String relative) {
            this.dir = dir;
            this.relative = relative;
        }

        @Override
        public synchronized Optional<Image> load(String playerUuid) {
            try {
                Path root = dir.toRealPath();
                Path file = root.resolve(relative).toRealPath();
                if (!file.startsWith(root) || !Files.isRegularFile(file)) return Optional.empty();
                long m = Files.getLastModifiedTime(file).toMillis();
                long s = Files.size(file);
                if (cached != null && m == mtime && s == size) return Optional.of(cached);
                if (s > ImageFormat.MAX_BYTES) return Optional.empty();
                byte[] bytes;
                try (InputStream in = Files.newInputStream(file)) {
                    bytes = in.readNBytes(ImageFormat.MAX_BYTES + 1);
                }
                if (bytes.length > ImageFormat.MAX_BYTES || ImageFormat.sniff(bytes) == null) return Optional.empty();
                cached = new Image(bytes, m);
                mtime = m;
                size = s;
                return Optional.of(cached);
            } catch (IOException | RuntimeException e) {
                return Optional.empty();
            }
        }
    }

    /** A URL template; the expanded URL is the cache key, so player heads are cached per player. */
    final class UrlSource implements ImageSource {
        private final String template;
        private final ImageCache cache;
        private final ValueSource values;
        private final long ttlMs;

        UrlSource(String template, ImageCache cache, ValueSource values, long ttlMs) {
            this.template = template.trim();
            this.cache = cache;
            this.values = values;
            this.ttlMs = ttlMs;
        }

        @Override
        public Optional<Image> load(String playerUuid) {
            String url = template;
            if (template.indexOf('%') >= 0) {
                url = values.resolve(template, playerUuid);
                if (url == null || url.equals(template)) return Optional.empty(); // nothing expanded
            }
            return cache.get(url.trim(), ttlMs);
        }
    }
}
