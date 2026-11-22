/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

import org.mcdashboard.auth.PermissionCache;
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
 * resolved at most once per its effective {@code min-interval}, so many clients cannot load the main thread.
 * Permission overrides can make that interval shorter (or longer) for a caller.
 * Lookups are by id; the catalog search uses a lowercase haystack computed once per definition.
 */
public final class PlaceholderService {
    /** A resolved value. IMAGE values are the image bytes as base64url without padding.
     *
     * @param id        The placeholder identifier.
     * @param value     The resolved value; IMAGE values are base64url image bytes.
     * @param updatedMs When the value was resolved, in epoch milliseconds.
     */
    public record Value(String id, String value, long updatedMs) {
    }

    /** A group with the number of placeholders in it.
     *
     * @param name  The group name.
     * @param count The number of placeholders in the group.
     */
    public record Group(String name, int count) {
    }

    /** One page of a catalog search.
     *
     * @param items  The definitions on this page.
     * @param total  The total number of matches.
     * @param groups The number of matches per group.
     */
    public record Page(List<PlaceholderDefinition> items, int total, List<Group> groups) {
    }

    /** Fetched URL images are cached at least this long. */
    static final long IMAGE_URL_MIN_TTL_MS = 300_000;

    /** {@code link} replaces {@code image} for a {@code url:} IMAGE with {@code encode: false}. */
    private record Entry(PlaceholderDefinition def, String haystack, ImageSource image, ImageSource.UrlLink link) {
    }

    private record Snapshot(Map<String, Entry> byId, List<Entry> sorted) {
    }

    private static final Comparator<Entry> ORDER = Comparator
            .comparing((Entry e) -> e.def().group(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(e -> e.def().name(), String.CASE_INSENSITIVE_ORDER)
            .thenComparing(e -> e.def().id());

    private volatile Snapshot snapshot = new Snapshot(Map.of(), List.of());
    /** A value that can no longer be resolved is dropped after this long (or five refresh intervals when that is longer). */
    static final long STALE_AFTER_MS = 60_000;
    private final Map<String, Value> cache = new ConcurrentHashMap<>();
    private final ValueSource source;
    private final Clock clock;
    private final long globalMinIntervalMs;
    private final IntervalOverrides globalIntervalOverrides;
    private final PermissionCache permissions;
    private final Path imagesDir;
    private final ImageCache images;

    /** A service without permission overrides or image cache, ready with the given definitions.
     *
     * @param source              Resolves the raw placeholder templates.
     * @param clock               The clock used for cache timestamps.
     * @param globalMinIntervalMs The default refresh interval in milliseconds.
     * @param defs                The placeholder definitions to serve.
     */
    public PlaceholderService(ValueSource source, Clock clock, long globalMinIntervalMs,
                              Collection<PlaceholderDefinition> defs) {
        this(source, clock, globalMinIntervalMs, IntervalOverrides.empty(), new PermissionCache(), defs,
                Path.of("images"), new ImageCache(clock));
    }

    /** A service with an images directory and image cache instead of the defaults.
     *
     * @param source              Resolves the raw placeholder templates.
     * @param clock               The clock used for cache timestamps.
     * @param globalMinIntervalMs The default refresh interval in milliseconds.
     * @param defs                The placeholder definitions to serve.
     * @param imagesDir           The directory where fetched images are cached.
     * @param images              The image cache to use.
     */
    public PlaceholderService(ValueSource source, Clock clock, long globalMinIntervalMs,
                              Collection<PlaceholderDefinition> defs, Path imagesDir, ImageCache images) {
        this(source, clock, globalMinIntervalMs, IntervalOverrides.empty(), new PermissionCache(), defs, imagesDir, images);
    }

    /** A service with full control over permission overrides, image storage and definitions.
     *
     * @param source                  Resolves the raw placeholder templates.
     * @param clock                   The clock used for cache timestamps.
     * @param globalMinIntervalMs     The default refresh interval in milliseconds.
     * @param globalIntervalOverrides Global per-permission interval overrides.
     * @param permissions             The permission snapshots to consult.
     * @param defs                    The placeholder definitions to serve.
     * @param imagesDir               The directory where fetched images are cached.
     * @param images                  The image cache to use.
     */
    public PlaceholderService(ValueSource source, Clock clock, long globalMinIntervalMs,
                              IntervalOverrides globalIntervalOverrides, PermissionCache permissions,
                              Collection<PlaceholderDefinition> defs, Path imagesDir, ImageCache images) {
        this.source = source;
        this.clock = clock;
        this.globalMinIntervalMs = Math.max(0, globalMinIntervalMs);
        this.globalIntervalOverrides = globalIntervalOverrides == null ? IntervalOverrides.empty() : globalIntervalOverrides;
        this.permissions = permissions == null ? new PermissionCache() : permissions;
        this.imagesDir = imagesDir;
        this.images = images;
        setDefinitions(defs);
    }

    /** Atomically replaces all definitions (used once the expansion catalog is loaded). The first one per id wins.
     *
     * @param defs The placeholder definitions to serve.
     */
    public void setDefinitions(Collection<PlaceholderDefinition> defs) {
        Map<String, Entry> byId = new HashMap<>();
        for (PlaceholderDefinition d : defs) {
            if (byId.containsKey(d.id())) continue;
            String hay = (d.id() + '\n' + d.name() + '\n' + (d.description() == null ? "" : d.description()) + '\n' + d.group())
                    .toLowerCase(Locale.ROOT);
            ImageSource img = null;
            ImageSource.UrlLink link = null;
            if (d.type() == PlaceholderDefinition.Type.IMAGE && !d.encode()) {
                link = new ImageSource.UrlLink(d.source(), source);
            } else if (d.type() == PlaceholderDefinition.Type.IMAGE) {
                img = ImageSource.parse(d.source(), d.map(), imagesDir, images, source,
                        Math.max(intervalMs(d), IMAGE_URL_MIN_TTL_MS));
            }
            byId.put(d.id(), new Entry(d, hay, img, link));
        }
        List<Entry> sorted = new ArrayList<>(byId.values());
        sorted.sort(ORDER);
        snapshot = new Snapshot(byId, sorted);
    }

    /** The definition with this id, if any.
     *
     * @param id The placeholder identifier.
     * @return The definition, or empty when no such placeholder is served.
     */
    public Optional<PlaceholderDefinition> definition(String id) {
        Entry e = snapshot.byId().get(id);
        return e == null ? Optional.empty() : Optional.of(e.def());
    }

    /** All definitions sorted by group, then name.
     *
     * @return The definitions in display order.
     */
    public Collection<PlaceholderDefinition> all() {
        return snapshot.sorted().stream().map(Entry::def).toList();
    }

    /**
     * Searches the placeholders {@code visible} to the caller. {@code q} is a case-insensitive substring of id, name,
     * description or group; {@code group} is an exact group name. {@code groups} counts the matches of {@code q} only.
     *
     * @param visible Decides whether a definition is visible to the caller.
     * @param q       The search text, or null for everything.
     * @param group   An exact group name to restrict to, or null.
     * @param limit   The maximum number of items on the page.
     * @param offset  The number of matches to skip before filling the page.
     * @return One page of matching definitions with their total and group counts.
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

    /** Effective refresh interval for a placeholder, in milliseconds, without a player's permission overrides.
     *
     * @param d The placeholder definition.
     * @return The effective refresh interval in milliseconds.
     */
    public long intervalMs(PlaceholderDefinition d) {
        return intervalMs(d, null);
    }

    /**
     * Effective refresh interval for this caller, in milliseconds. A permission override replaces the configured
     * interval (the fastest node the player holds wins); the global and the placeholder interval are combined by
     * taking the larger, so a placeholder can stay slower than the global default.
     *
     * @param d          The placeholder definition.
     * @param playerUuid The caller's player UUID, or null for an anonymous caller.
     * @return The effective refresh interval in milliseconds.
     */
    public long intervalMs(PlaceholderDefinition d, String playerUuid) {
        long global = globalIntervalOverrides.select(globalMinIntervalMs, playerUuid, permissions);
        long own = d.minIntervalOverrides().select(d.minIntervalMs(), playerUuid, permissions);
        long ms = Math.max(own, global);
        if (d.type() == PlaceholderDefinition.Type.IMAGE) {
            ms = Math.max(ms, d.source().startsWith("url:") || d.source().startsWith("map:") ? IMAGE_URL_MIN_TTL_MS : 30_000);
        }
        return ms;
    }

    /** Resolutions in flight, so many callers of one value (every connected app) cost the server thread one call. */
    private final Map<String, java.util.concurrent.CompletableFuture<String>> inflight = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger failures = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long breakerUntilMs;
    /** After this many resolutions in a row that got no answer, resolving pauses for {@link #BREAKER_PAUSE_MS}. */
    static final int BREAKER_FAILURES = 3;
    static final long BREAKER_PAUSE_MS = 5_000;

    /**
     * One resolution per key at a time; other callers wait for its answer. A source that keeps failing (the main
     * thread is stuck or very slow) opens a circuit breaker so API threads stop queueing behind it, and the last
     * values keep being served until they go stale.
     */
    private String resolveOnce(String key, String template, String ctx, long now) {
        return resolveOnce(key, () -> source.resolve(template, ctx));
    }

    private String resolveOnce(String key, java.util.function.Supplier<String> resolver) {
        var mine = new java.util.concurrent.CompletableFuture<String>();
        var running = inflight.putIfAbsent(key, mine);
        if (running != null) {
            try {
                return running.get(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                return null;
            }
        }
        String resolved = null;
        try {
            resolved = resolver.get();
            return resolved;
        } finally {
            if (resolved == null) {
                if (failures.incrementAndGet() >= BREAKER_FAILURES) breakerUntilMs = clock.millis() + BREAKER_PAUSE_MS;
            } else {
                failures.set(0);
            }
            mine.complete(resolved);
            inflight.remove(key);
        }
    }

    /**
     * Reads a value. PUBLIC placeholders resolve without a player; PLAYER placeholders need
     * {@code playerUuid} (the paired device's player) and are otherwise refused.
     *
     * @param id         The placeholder identifier to read.
     * @param playerUuid The paired player's UUID, or null for an anonymous caller.
     * @return The value, or empty when the placeholder is unknown or not readable.
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
        if (cached != null && now - cached.updatedMs() < intervalMs(d, playerUuid)) return Optional.of(cached);
        if (now < breakerUntilMs) return Optional.ofNullable(cached); // the server thread is not answering: do not pile up behind it
        String resolved = d.list() != null
                ? resolveOnce(key, () -> d.list().resolve(t -> source.resolve(t, ctx)))
                : entry.link() != null ? resolveOnce(key, () -> entry.link().resolve(ctx))
                : resolveOnce(key, d.value(), ctx, now);
        if (resolved == null || resolved.equals(d.value())) {
            // Unresolved: a short outage keeps the last value, but a dead value must not look live for ever.
            long keep = Math.max(STALE_AFTER_MS, 5 * intervalMs(d, playerUuid));
            if (cached != null && now - cached.updatedMs() > keep) cache.remove(key);
            else return Optional.ofNullable(cached);
            return Optional.empty();
        }
        Value fresh = new Value(id, resolved, now);
        cache.put(key, fresh);
        return Optional.of(fresh);
    }
}
