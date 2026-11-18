/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.stats;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Cheap, thread safe counters and gauges about the plugin itself. Components record into it ({@link #apiRequest()},
 * {@link #rateLimited()}) or register a gauge that reads their own in-memory state; a reader (the PlaceholderAPI
 * expansion, a proxy placeholder) only ever reads memory: no I/O, no database, no waiting on other threads for more than
 * a few instructions. It holds counts and settings, never player data, addresses, tokens or device identifiers.
 */
public final class PluginStats {
    private static final int WINDOW_SECONDS = 60;

    private final Clock clock;
    private final long startedMs;
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong limited = new AtomicLong();
    // One bucket per second of the last minute: the second a bucket counts, and how many requests it saw.
    private final long[] bucketSecond = new long[WINDOW_SECONDS];
    private final long[] bucketCount = new long[WINDOW_SECONDS];

    private volatile String version = "";
    private volatile String historyStorage = "off";
    private volatile boolean licensed;
    private volatile IntSupplier devices = () -> 0;
    private volatile java.util.function.ToIntFunction<String> devicesEntitled = e -> 0;
    private volatile LongSupplier devicesOnline = () -> 0;
    private volatile IntSupplier liveClients = () -> 0;
    private volatile LongSupplier historySamples = () -> 0;
    private volatile IntSupplier placeholders = () -> 0;
    private volatile IntSupplier dashboards = () -> 0;

    public PluginStats(Clock clock) {
        this.clock = clock;
        this.startedMs = clock.millis();
        java.util.Arrays.fill(bucketSecond, -1);
    }

    // Recording (any thread)

    /** One request reached the API (every route, accepted or refused). */
    public void apiRequest() {
        requests.incrementAndGet();
        long second = clock.millis() / 1000;
        int i = (int) (second % WINDOW_SECONDS);
        synchronized (bucketCount) {
            if (bucketSecond[i] != second) {
                bucketSecond[i] = second;
                bucketCount[i] = 0;
            }
            bucketCount[i]++;
        }
    }

    /** One request was refused because a rate limit was exceeded. */
    public void rateLimited() {
        limited.incrementAndGet();
    }

    // Gauges (set once by the component that owns the state)

    public PluginStats version(String version) {
        this.version = version == null ? "" : version;
        return this;
    }

    /** {@code sqlite}, {@code mysql}, {@code mariadb}, {@code postgresql} or {@code off} (memory only). */
    public PluginStats historyStorage(String storage) {
        this.historyStorage = storage == null || storage.isBlank() || storage.equals("memory") ? "off" : storage;
        return this;
    }

    public PluginStats licensed(boolean licensed) {
        this.licensed = licensed;
        return this;
    }

    public PluginStats devices(IntSupplier paired, LongSupplier seenSince) {
        this.devices = paired;
        this.devicesOnline = seenSince;
        return this;
    }

    /** How many devices reported each entitlement ({@code server}, {@code app}, {@code free}); self-reported, informational. */
    public PluginStats devicesEntitled(java.util.function.ToIntFunction<String> counter) {
        this.devicesEntitled = counter;
        return this;
    }

    public PluginStats liveClients(IntSupplier open) {
        this.liveClients = open;
        return this;
    }

    public PluginStats historySamples(LongSupplier written) {
        this.historySamples = written;
        return this;
    }

    public PluginStats placeholders(IntSupplier offered) {
        this.placeholders = offered;
        return this;
    }

    public PluginStats dashboards(IntSupplier defined) {
        this.dashboards = defined;
        return this;
    }

    // Reading (any thread, never blocks)

    public long startedMs() {
        return startedMs;
    }

    public long uptimeSeconds() {
        return Math.max(0, (clock.millis() - startedMs) / 1000);
    }

    public String version() {
        return version;
    }

    public String historyStorage() {
        return historyStorage;
    }

    public boolean licensed() {
        return licensed;
    }

    /**
     * The license state: {@code licensed} when {@code license.id} is configured (config based, as the plugin cannot and does
     * not ask the MVdW service), else {@code free}. This is the hook for refining it from what devices report, which is
     * self-reported and informational: the per-entitlement device counts are {@link #devicesEntitled(String)}.
     */
    public String licenseState() {
        return licensed ? "licensed" : "free";
    }

    /** Devices that reported {@code entitlement} ({@code server}, {@code app} or {@code free}); 0 for anything else. */
    public int devicesEntitled(String entitlement) {
        try {
            return devicesEntitled.applyAsInt(entitlement);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public int devices() {
        return safe(devices);
    }

    /** Paired devices that made a request in the last {@value #ONLINE_MINUTES} minutes. */
    public long devicesOnline() {
        try {
            return devicesOnline.getAsLong();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** How long a device counts as online after its last request. */
    public static final int ONLINE_MINUTES = 5;

    public long apiRequests() {
        return requests.get();
    }

    /** Requests in the last 60 seconds. */
    public long apiRequestsPerMinute() {
        long now = clock.millis() / 1000;
        long sum = 0;
        synchronized (bucketCount) {
            for (int i = 0; i < WINDOW_SECONDS; i++) {
                if (bucketSecond[i] > now - WINDOW_SECONDS && bucketSecond[i] <= now) sum += bucketCount[i];
            }
        }
        return sum;
    }

    public long rateLimitedRequests() {
        return limited.get();
    }

    public int liveClients() {
        return safe(liveClients);
    }

    public long historySamples() {
        try {
            return historySamples.getAsLong();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public int placeholders() {
        return safe(placeholders);
    }

    public int dashboards() {
        return safe(dashboards);
    }

    private static int safe(IntSupplier s) {
        try {
            return s.getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
