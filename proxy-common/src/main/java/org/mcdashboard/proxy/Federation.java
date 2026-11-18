/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.time.Clock;

import org.mcdashboard.util.Threads;

/**
 * Pulls the PUBLIC values of the configured backend servers on a timer and answers {@code %backend_<name>_<id>%} from
 * that cache. A value older than three rounds is dropped rather than shown as current. Off unless backends are configured.
 */
public final class Federation implements AutoCloseable {
    private record Snapshot(Map<String, String> values, long atMs) {
    }

    private final List<BackendConfig> backends;
    private final BackendFetcher fetcher;
    private final Clock clock;
    private final Consumer<String> warn;
    private final Map<String, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Boolean> failing = new ConcurrentHashMap<>();
    private volatile long staleAfterMs = 60_000;
    private ScheduledExecutorService timer;

    public Federation(List<BackendConfig> backends, BackendFetcher fetcher, Clock clock, Consumer<String> warn) {
        this.backends = List.copyOf(backends);
        this.fetcher = fetcher;
        this.clock = clock;
        this.warn = warn;
    }

    public boolean enabled() {
        return !backends.isEmpty();
    }

    public synchronized void start(int intervalSeconds) {
        if (timer != null || backends.isEmpty()) return;
        int interval = Math.max(5, intervalSeconds);
        staleAfterMs = interval * 3000L;
        timer = Executors.newSingleThreadScheduledExecutor(Threads.daemon("MCDashboard-federation"));
        timer.scheduleWithFixedDelay(this::refresh, 0, interval, TimeUnit.SECONDS);
    }

    /** Reads every backend now, one after the other, on the caller's thread. A failing one is logged once and keeps no values. */
    public void refresh() {
        for (BackendConfig b : backends) {
            try {
                snapshots.put(b.name().toLowerCase(Locale.ROOT), new Snapshot(Map.copyOf(fetcher.fetch(b)), clock.millis()));
                if (failing.remove(b.name()) != null) warn.accept("network.backends: " + b.name() + " is reachable again");
            } catch (Exception e) {
                if (failing.put(b.name(), true) == null) warn.accept("network.backends: could not read " + b.name() + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
        }
    }

    /** The value for {@code <name>_<id>}; null when the backend is unknown, unreadable or the value is stale. */
    public String value(String nameAndId) {
        int cut = nameAndId.indexOf('_');
        if (cut <= 0) return null;
        Snapshot s = snapshots.get(nameAndId.substring(0, cut).toLowerCase(Locale.ROOT));
        if (s == null || clock.millis() - s.atMs() > staleAfterMs) return null;
        return s.values().get(nameAndId.substring(cut + 1));
    }

    /** {@code %backend_<name>_<id>%} for everything the backends offered at the last read. */
    public List<String> templates() {
        List<String> out = new ArrayList<>();
        for (BackendConfig b : backends) {
            Snapshot s = snapshots.get(b.name().toLowerCase(Locale.ROOT));
            if (s == null) continue;
            for (String id : s.values().keySet()) out.add("%backend_" + b.name() + "_" + id + "%");
        }
        return out;
    }

    @Override
    public synchronized void close() {
        if (timer != null) timer.shutdownNow();
        timer = null;
    }
}
