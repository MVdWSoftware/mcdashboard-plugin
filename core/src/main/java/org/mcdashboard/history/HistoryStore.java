/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.history;

import org.mcdashboard.placeholder.HistoryService;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Where samples live beyond the in-memory window. Implementations may block: callers keep them off the main thread. */
public interface HistoryStore extends AutoCloseable {
    /** A placeholder that is recorded: a sosa:Sensor with its observed property and sampling procedure. */
    record Series(String id, String label, String unit, long intervalMs) {
    }

    /** Creates or updates the schema and the sensors; may be called again when the set changes. */
    void register(Collection<Series> series) throws Exception;

    /** Whether {@link #register} succeeded, so reads and writes can be served. */
    boolean ready();

    /** Stores samples; a sample that exists already (same sensor and time) is skipped. */
    void append(List<Samples.Sample> samples) throws Exception;

    /** Samples at or after {@code sinceMs}, oldest first, reduced to at most {@code points}. */
    List<HistoryService.Point> since(String id, long sinceMs, int points) throws Exception;

    /** Deletes samples older than the given time, per placeholder id. */
    void prune(Map<String, Long> cutoffMs) throws Exception;

    @Override
    void close();
}
