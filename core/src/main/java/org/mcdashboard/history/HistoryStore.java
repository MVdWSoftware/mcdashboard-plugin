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
    /** A placeholder that is recorded: a sosa:Sensor with its observed property and sampling procedure.
     *
     * @param id         The unique placeholder identifier used as sensor key.
     * @param label      Human-readable name displayed to users.
     * @param unit       Measurement unit symbol (e.g. "MB", "%").
     * @param intervalMs The configured time between samples in milliseconds.
     */
    record Series(String id, String label, String unit, long intervalMs) {
    }

    /** Creates or updates the schema and the sensors; may be called again when the set changes.
     *
     * @param series The collection of placeholder series to register as sensors.
     * @throws java.lang.Exception When database operations fail during registration.
     */
    void register(Collection<Series> series) throws Exception;

    /** Whether {@link #register} succeeded, so reads and writes can be served.
     *
     * @return True when the store is ready to accept queries and writes.
     */
    boolean ready();

    /** Stores samples; a sample that exists already (same sensor and time) is skipped.
     *
     * @param samples The list of samples to persist.
     * @throws java.lang.Exception When database operations fail during append.
     */
    void append(List<Samples.Sample> samples) throws Exception;

    /** Samples at or after {@code sinceMs}, oldest first, reduced to at most {@code points}.
     *
     * @param id      The placeholder identifier whose samples to retrieve.
     * @param sinceMs The minimum timestamp in milliseconds for returned samples.
     * @param points  Maximum number of samples to return after reduction.
     * @return A list of history points, empty when no matching data exists.
     * @throws java.lang.Exception When database operations fail during query.
     */
    List<HistoryService.Point> since(String id, long sinceMs, int points) throws Exception;

    /** Deletes samples older than the given time, per placeholder id.
     *
     * @param cutoffMs Map from placeholder id to the cutoff timestamp in milliseconds.
     * @throws java.lang.Exception When database operations fail during pruning.
     */
    void prune(Map<String, Long> cutoffMs) throws Exception;

    @Override
    void close();
}
