/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

import org.mcdashboard.history.HistorySettings;
import org.mcdashboard.history.HistoryStore;
import org.mcdashboard.history.Samples;
import org.mcdashboard.util.Threads;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Time series for PUBLIC placeholders that have a history window ({@code history: <minutes>} in
 * {@code placeholders.yml}, or {@code history.include} in {@code config.yml}). Samples are taken at the placeholder's
 * sampling step and always kept in memory for the window, bounded to {@link #MAX_POINTS} per placeholder. With a
 * {@link HistoryStore} they are also written to a database in batches off the server thread, and reads come from the
 * database, so history survives restarts and can span weeks. When the database is unreachable, samples wait (bounded)
 * and reads fall back to memory: the dashboard never breaks because of the history.
 */
public final class HistoryService implements AutoCloseable {
    /** One sample.
     *
     * @param timeMs The time the sample was taken, in epoch milliseconds.
     * @param value  The sampled value as text.
     */
    public record Point(long timeMs, String value) {
    }

    /** Upper bound on the in-memory points kept per placeholder. */
    public static final int MAX_POINTS = 2000;
    private static final int MAX_BACKLOG = 50_000;
    private static final long PRUNE_EVERY_MS = 3_600_000;
    private static final long WARN_EVERY_MS = 300_000;

    private final Map<String, ArrayDeque<Point>> series = new ConcurrentHashMap<>();
    private final Map<String, Long> windowMs = new ConcurrentHashMap<>();
    private final Map<String, PlaceholderDefinition> tracked = new ConcurrentHashMap<>();
    private final Clock clock;
    private final HistorySettings settings;
    private final HistoryStore store;
    private final Consumer<String> log;
    private final LinkedBlockingQueue<Samples.Sample> queue = new LinkedBlockingQueue<>(MAX_BACKLOG);
    private final List<Samples.Sample> inflight = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicLong written = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean dirty = true;
    private long lastPrune;
    private long lastWarn;
    private ScheduledExecutorService writer;

    /** Memory only, tracking the placeholders that have {@code history}.
     *
     * @param clock The clock used for sample timestamps.
     * @param defs  The placeholder definitions to track.
     */
    public HistoryService(Clock clock, Collection<PlaceholderDefinition> defs) {
        this(clock, defs, HistorySettings.defaults(), null, m -> {
        });
    }

    /** {@code store} keeps samples beyond memory (null: memory only); {@code log} receives warnings.
     *
     * @param clock    The clock used for sample timestamps.
     * @param defs     The placeholder definitions to track.
     * @param settings The history configuration.
     * @param store    The persistent store, or null to keep samples in memory only.
     * @param log      Receives warnings about the history database.
     */
    public HistoryService(Clock clock, Collection<PlaceholderDefinition> defs, HistorySettings settings, HistoryStore store,
                          Consumer<String> log) {
        this.clock = clock;
        this.settings = settings;
        this.store = store;
        this.log = log;
        track(defs);
    }

    /**
     * Starts or extends what is recorded: every PUBLIC definition with a history window. Called again when the
     * placeholder catalog has loaded; sensors that are new reach the database with the next write.
     *
     * @param defs The placeholder definitions to start tracking.
     */
    public void track(Collection<PlaceholderDefinition> defs) {
        for (PlaceholderDefinition d : defs) {
            if (d.historyMinutes() > 0 && d.access() == PlaceholderDefinition.Access.PUBLIC) {
                if (tracked.put(d.id(), d) == null) dirty = true;
                windowMs.put(d.id(), d.historyMinutes() * 60_000L);
                series.computeIfAbsent(d.id(), k -> new ArrayDeque<>());
            }
        }
    }

    /** Whether this placeholder has a history window.
     *
     * @param id The placeholder identifier to check.
     * @return True when the placeholder is tracked.
     */
    public boolean tracked(String id) {
        return windowMs.containsKey(id);
    }

    /** Ids that have history enabled.
     *
     * @return The tracked placeholder identifiers.
     */
    public Collection<String> trackedIds() {
        return List.copyOf(windowMs.keySet());
    }

    /** The time between two samples of this placeholder (see {@link HistorySettings#stepMs}).
     *
     * @param d                The placeholder definition.
     * @param sourceIntervalMs The minimum interval enforced by the data source.
     * @return The effective sampling interval in milliseconds.
     */
    public long stepMs(PlaceholderDefinition d, long sourceIntervalMs) {
        return settings.stepMs(d, sourceIntervalMs);
    }

    /** Records a value if the previous sample is old enough; trims by age and count, and queues it for the database.
     *
     * @param id     The placeholder identifier.
     * @param value  The sampled value.
     * @param stepMs The minimum interval between samples, in milliseconds.
     */
    public void record(String id, String value, long stepMs) {
        ArrayDeque<Point> q = series.get(id);
        if (q == null || value == null) return;
        long now = clock.millis();
        synchronized (q) {
            Point last = q.peekLast();
            if (last != null && now - last.timeMs() < Math.max(HistorySettings.MIN_INTERVAL_MS, stepMs)) return;
            q.addLast(new Point(now, value));
            long cutoff = now - windowMs.get(id);
            while (!q.isEmpty() && (q.peekFirst().timeMs() < cutoff || q.size() > MAX_POINTS)) q.pollFirst();
        }
        if (store != null) {
            Samples.Sample s = new Samples.Sample(id, now, value);
            while (!queue.offer(s)) queue.poll(); // database down for long: drop the oldest
        }
    }

    /** Samples at or after {@code sinceMs}, oldest first, at most {@link #MAX_POINTS}.
     *
     * @param id      The placeholder identifier.
     * @param sinceMs The minimum timestamp in milliseconds.
     * @return The samples in time order.
     */
    public List<Point> since(String id, long sinceMs) {
        return since(id, sinceMs, MAX_POINTS);
    }

    /** Samples at or after {@code sinceMs}, oldest first, reduced to at most {@code points} (2 to {@link #MAX_POINTS}).
     *
     * @param id      The placeholder identifier.
     * @param sinceMs The minimum timestamp in milliseconds.
     * @param points  The maximum number of points to return.
     * @return The samples in time order, reduced to {@code points}.
     */
    public List<Point> since(String id, long sinceMs, int points) {
        int max = Math.max(2, Math.min(points, MAX_POINTS));
        if (store != null && store.ready()) {
            try {
                List<Point> stored = store.since(id, sinceMs, max);
                if (!stored.isEmpty()) return stored;
            } catch (Exception e) {
                warn("history read failed, serving what is in memory: " + e.getMessage());
            }
        }
        ArrayDeque<Point> q = series.get(id);
        if (q == null) return List.of();
        List<Point> out = new ArrayList<>();
        synchronized (q) {
            for (Point p : q) if (p.timeMs() >= sinceMs) out.add(p);
        }
        return Samples.reduce(out, max);
    }

    /** Samples written to the database since start (0 without a store). In memory, safe on any thread.
     *
     * @return The number of samples persisted since startup.
     */
    public long samplesWritten() {
        return written.get();
    }

    /** Starts the database writer; a no-op without a store. */
    public void start() {
        if (store == null || writer != null) return;
        writer = Executors.newSingleThreadScheduledExecutor(Threads.daemon("MCDashboard-history-db"));
        writer.scheduleWithFixedDelay(this::flush, 1, settings.flushMs(), TimeUnit.MILLISECONDS);
    }

    /** Registers new sensors, writes what is queued and prunes now and then. Never throws; failures are retried. */
    public synchronized void flush() {
        if (store == null) return;
        try {
            if (dirty || !store.ready()) {
                dirty = false;
                List<HistoryStore.Series> all = new ArrayList<>();
                for (PlaceholderDefinition d : tracked.values()) {
                    all.add(new HistoryStore.Series(d.id(), d.name(), d.unit(), settings.stepMs(d, 0)));
                }
                store.register(all);
            }
            Samples.Sample s;
            while (inflight.size() < MAX_BACKLOG && (s = queue.poll()) != null) inflight.add(s);
            if (!inflight.isEmpty()) {
                store.append(inflight);
                written.addAndGet(inflight.size());
                inflight.clear();
            }
            long now = clock.millis();
            if (now - lastPrune >= PRUNE_EVERY_MS) {
                lastPrune = now;
                Map<String, Long> cutoffs = new HashMap<>();
                for (PlaceholderDefinition d : tracked.values()) {
                    long keep = settings.retentionMs(d);
                    if (keep > 0) cutoffs.put(d.id(), now - keep);
                }
                store.prune(cutoffs);
            }
        } catch (Exception e) {
            dirty = true;
            warn("history database: " + e.getMessage() + " (samples are kept in memory and retried)");
        }
    }

    private void warn(String message) {
        long now = clock.millis();
        if (now - lastWarn < WARN_EVERY_MS) return;
        lastWarn = now;
        log.accept(message);
    }

    /** Writes what is left and closes the database. */
    @Override
    public void close() {
        if (writer != null) writer.shutdownNow();
        writer = null;
        if (store != null) {
            flush();
            store.close();
        }
    }
}
