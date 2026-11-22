/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.image;

import org.mcdashboard.image.map.MapSource;
import org.mcdashboard.image.map.MapSpec;
import org.mcdashboard.placeholder.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Where an IMAGE placeholder gets its bytes: {@code file:<relative path>} inside the images folder (reloaded when the
 * file changes), {@code url:<template>} with PlaceholderAPI placeholders expanded for the paired player, or
 * {@code map:<provider>} with a {@code map:} block ({@link MapSpec}): tiles of a web map stitched into one picture.
 */
public interface ImageSource {
    /** Returns the current image for this player, or empty when unavailable.
     *
     * @param playerUuid The paired player's UUID, or null for server-wide placeholders.
     * @return An image ready to serve, or empty if it cannot be loaded.
     */
    Optional<Image> load(String playerUuid);

    /** Validates that {@code spec} starts with a recognised prefix ({@code file:}, {@code url:} or {@code map:}).
     *
     * @param spec The raw source string from configuration.
     * @throws IllegalArgumentException When the specification is invalid.
     */
    static void validate(String spec) {
        if (spec == null) throw new IllegalArgumentException("IMAGE needs a source");
        if (spec.startsWith("file:")) {
            relative(spec.substring(5));
        } else if (spec.startsWith("url:")) {
            if (!URL_TEMPLATE.matcher(spec.substring(4)).matches()) {
                throw new IllegalArgumentException("url source must be http(s)://host/... with a fixed host");
            }
        } else if (spec.startsWith("map:")) {
            MapSpec.provider(spec.substring(4));
        } else {
            throw new IllegalArgumentException("source must start with file:, url: or map:");
        }
    }

    /** Parses a source specification without an explicit {@code map:} block.
     *
     * @param spec The full source string, e.g. {@code file:bg.png}, {@code url:...} or {@code map:dynmap}.
     * @param imagesDir Directory under which {@code file:} paths are resolved.
     * @param cache Cache for fetched URL images.
     * @param values Placeholder expansion service for template URLs.
     * @param ttlMs How long a fetched URL remains fresh in milliseconds.
     * @return A new {@link ImageSource} implementation suited to the prefix.
     */
    static ImageSource parse(String spec, Path imagesDir, ImageCache cache, ValueSource values, long ttlMs) {
        return parse(spec, null, imagesDir, cache, values, ttlMs);
    }

    /** Parses a source specification with an explicit {@code map:} configuration block.
     *
     * @param spec The full source string, e.g. {@code file:bg.png}, {@code url:...} or {@code map:dynmap}.
     * @param map The parsed {@link MapSpec} when {@code spec} starts with {@code map:}.
     * @param imagesDir Directory under which {@code file:} paths are resolved.
     * @param cache Cache for fetched URL images.
     * @param values Placeholder expansion service for template URLs.
     * @param ttlMs How long a fetched URL remains fresh in milliseconds.
     * @return A new {@link ImageSource} implementation suited to the prefix.
     */
    static ImageSource parse(String spec, MapSpec map, Path imagesDir, ImageCache cache, ValueSource values, long ttlMs) {
        validate(spec);
        if (spec.startsWith("file:")) return new FileSource(imagesDir, relative(spec.substring(5)));
        if (spec.startsWith("map:")) {
            if (map == null) throw new IllegalArgumentException("map source needs a map: block");
            return new MapSource(map, cache, values, ttlMs);
        }
        return new UrlSource(spec.substring(4), cache, values, ttlMs);
    }

    /** Pattern matching an http(s) URL with a fixed host (no placeholders in the authority). */
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

    /**
     * A {@code url:} source with {@code encode: false}: nothing is fetched, the app loads the expanded URL itself. The
     * result must still be https on the template's own host (placeholders can only fill in the path).
     */
    final class UrlLink {
        private final String template;
        private final String host;
        private final ValueSource values;

        /** Parses an {@code url:https://...} specification that the app loads directly.
         *
         * @param spec The source string, which must start with {@code url:https://}.
         * @param values The placeholder expansion service.
         */
        public UrlLink(String spec, ValueSource values) {
            validate(spec);
            if (!spec.regionMatches(true, 0, "url:https://", 0, 12)) {
                throw new IllegalArgumentException("encode: false needs an https:// url");
            }
            this.template = spec.substring(4).trim();
            String rest = template.substring(8);
            int slash = rest.indexOf('/');
            this.host = (slash < 0 ? rest : rest.substring(0, slash)).toLowerCase(Locale.ROOT);
            this.values = values;
        }

        /** The URL for this player (null for server-wide placeholders).
         *
         * @param playerUuid The paired player's UUID, or null for a server-wide placeholder.
         * @return The expanded URL, or null when a placeholder did not expand.
         */
        public String resolve(String playerUuid) {
            String url = template;
            if (template.indexOf('%') >= 0) {
                url = values.resolve(template, playerUuid);
                if (url == null || url.equals(template)) return null;
                url = url.trim();
            }
            if (url.length() > 2048 || !url.regionMatches(true, 0, "https://", 0, 8)) return null;
            String rest = url.substring(8);
            int end = 0;
            while (end < rest.length() && "/?#".indexOf(rest.charAt(end)) < 0) end++;
            if (!rest.substring(0, end).equalsIgnoreCase(host)) return null;
            try {
                URI uri = new URI(url);
                return host.equalsIgnoreCase(uri.getRawAuthority()) ? url : null;
            } catch (URISyntaxException e) {
                return null; // a space or another character a browser would refuse
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
