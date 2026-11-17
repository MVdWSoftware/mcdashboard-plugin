/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

import org.mcdashboard.image.ImageCache;
import org.mcdashboard.image.ImageSource;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Allowlist plus cache in front of a {@link ValueSource}. Each (placeholder, player) pair is
 * resolved at most once per {@code min-interval}, so many clients cannot load the main thread.
 * Lookups are by id; the catalog search uses a lowercase haystack computed once per definition.
 */
public final class PlaceholderService {
    /** A resolved value. IMAGE values are the image bytes as base64url without padding. */
    public record Value(String id, String value, long updatedMs) {
    }

    /** A group with the number of placeholders in it. */
    public record Group(String name, int count) {
    }

    /** One page of a catalog search. */
    public record Page(List<PlaceholderDefinition> items, int total, List<Group> groups) {
    }

    /** Fetched URL images are cached at least this long. */
    static final long IMAGE_URL_MIN_TTL_MS = 300_000;

    private record Entry(PlaceholderDefinition def, String haystack, ImageSource image) {
    }

    private record Snapshot(Map<String, Entry> byId, List<Entry> sorted) {
    }

    private static final Comparator<Entry> ORDER = Comparator
            .comparing((Entry e) -> e.def().group(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(e -> e.def().name(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(e -> e.def().id());

    private volatile Snapshot snapshot = new Snapshot(Map.of(), List.of());
    private final Map<String, Value> cache = new ConcurrentHashMap<>();
    private final ValueSource source;
    private final Clock clock;
    private final long globalMinIntervalMs;
    private final Path imagesDir;
    private final ImageCache images;

    public PlaceholderService(ValueSource source, Clock clock, long globalMinIntervalMs,
                              Collection<PlaceholderDefinition> defs) {
        this(source, clock, globalMinIntervalMs, defs, Path.of("images"), new ImageCache(clock));
    }

    public PlaceholderService(ValueSource source, Clock clock, long globalMinIntervalMs,
                              Collection<PlaceholderDefinition> defs, Path imagesDir, ImageCache images) {
        this.source = source;
        this.clock = clock;
        this.globalMinIntervalMs = Math.max(0, globalMinIntervalMs);
        this.imagesDir = imagesDir;
        this.images = images;
        setDefinitions(defs);
    }

    /** Atomically replaces all definitions (used once the expansion catalog is loaded). The first one per id wins. */
    public void setDefinitions(Collection<PlaceholderDefinition> defs) {
        Map<String, Entry> byId = new HashMap<>();
        for (PlaceholderDefinition d : defs) {
            if (byId.containsKey(d.id())) continue;
            String hay = (d.id() + '\n' + d.name() + '\n' + (d.description() == null ? "" : d.description()) + '\n' + d.group())
                    .toLowerCase(Locale.ROOT);
            ImageSource img = null;
            if (d.type() == PlaceholderDefinition.Type.IMAGE) {
                img = ImageSource.parse(d.source(), imagesDir, images, source,
                        Math.max(intervalMs(d), IMAGE_URL_MIN_TTL_MS));
            }
            byId.put(d.id(), new Entry(d, hay, img));
        }
        List<Entry> sorted = new ArrayList<>(byId.values());
        sorted.sort(ORDER);
        snapshot = new Snapshot(byId, sorted);
    }

    public Optional<PlaceholderDefinition> definition(String id) {
        Entry e = snapshot.byId().get(id);
        return e == null ? Optional.empty() : Optional.of(e.def());
    }

    /** All definitions sorted by group, then name. */
    public Collection<PlaceholderDefinition> all() {
        return snapshot.sorted().stream().map(Entry::def).toList();
    }

    /**
     * Searches the placeholders {@code visible} to the caller. {@code q} is a case-insensitive substring of id, name,
     * description or group; {@code group} is an exact group name. {@code groups} counts the matches of {@code q} only.
     */
    public Page search(Predicate<PlaceholderDefinition> visible, String q, String group, int limit, int offset) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        Map<String, Integer> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()));
        List<PlaceholderDefinition> items = new ArrayList<>();
        int total = 0;
        for (Entry e : snapshot.sorted()) {
            if (!needle.isEmpty() && !e.haystack().contains(needle)) continue;
            if (!visible.test(e.def())) continue;
            groups.merge(e.def().group(), 1, Integer::sum);
            if (group != null && !group.equals(e.def().group())) continue;
            if (total >= offset && items.size() < limit) items.add(e.def());
            total++;
        }
        List<Group> counts = new ArrayList<>();
        groups.forEach((n, c) -> counts.add(new Group(n, c)));
        return new Page(items, total, counts);
    }

    /** Effective refresh interval for a placeholder, in milliseconds. */
    public long intervalMs(PlaceholderDefinition d) {
        long ms = Math.max(d.minIntervalMs(), globalMinIntervalMs);
        if (d.type() == PlaceholderDefinition.Type.IMAGE) ms = Math.max(ms, d.source().startsWith("url:") ? IMAGE_URL_MIN_TTL_MS : 30_000);
        return ms;
    }

    /**
     * Reads a value. PUBLIC placeholders resolve without a player; PLAYER placeholders need
     * {@code playerUuid} (the paired device's player) and are otherwise refused.
     */
    public Optional<Value> read(String id, String playerUuid) {
        Entry entry = snapshot.byId().get(id);
        if (entry == null) return Optional.empty();
        PlaceholderDefinition d = entry.def();
        if (d.access() == PlaceholderDefinition.Access.PLAYER && playerUuid == null) return Optional.empty();
        String ctx = d.access() == PlaceholderDefinition.Access.PLAYER ? playerUuid : null;
        if (entry.image() != null) {
            return entry.image().load(ctx).map(img -> new Value(id,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(img.bytes()), img.timeMs()));
        }
        String key = id + '|' + (ctx == null ? "" : ctx);
        long now = clock.millis();
        Value cached = cache.get(key);
        if (cached != null && now - cached.updatedMs() < intervalMs(d)) return Optional.of(cached);
        String resolved = source.resolve(d.value(), ctx);
        if (resolved == null || resolved.equals(d.value())) return Optional.ofNullable(cached); // unresolved: keep stale value
        Value fresh = new Value(id, resolved, now);
        cache.put(key, fresh);
        return Optional.of(fresh);
    }
}
