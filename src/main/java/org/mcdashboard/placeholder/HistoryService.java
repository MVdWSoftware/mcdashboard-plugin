package org.mcdashboard.placeholder;

import java.time.Clock;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory time series for PUBLIC placeholders that opt in with {@code history: <minutes>}.
 * Samples are taken at the placeholder's interval (at least 10 s) and kept for the retention
 * window, bounded to {@link #MAX_POINTS} per placeholder. History is lost on restart by design:
 * it stays a lightweight "what happened lately" view, not a metrics database.
 */
public final class HistoryService {
    /** One sample. */
    public record Point(long timeMs, String value) {
    }

    public static final int MAX_POINTS = 2000;
    private static final long MIN_STEP_MS = 10_000;

    private final Map<String, ArrayDeque<Point>> series = new ConcurrentHashMap<>();
    private final Map<String, Long> retentionMs = new ConcurrentHashMap<>();
    private final Clock clock;

    public HistoryService(Clock clock, Collection<PlaceholderDefinition> defs) {
        this.clock = clock;
        for (PlaceholderDefinition d : defs) {
            if (d.historyMinutes() > 0 && d.access() == PlaceholderDefinition.Access.PUBLIC) {
                retentionMs.put(d.id(), d.historyMinutes() * 60_000L);
                series.put(d.id(), new ArrayDeque<>());
            }
        }
    }

    public boolean tracked(String id) {
        return retentionMs.containsKey(id);
    }

    /** Ids that have history enabled. */
    public Collection<String> trackedIds() {
        return List.copyOf(retentionMs.keySet());
    }

    /** Records a value if the previous sample is old enough; trims by age and count. */
    public void record(String id, String value, long stepMs) {
        ArrayDeque<Point> q = series.get(id);
        if (q == null || value == null) return;
        long now = clock.millis();
        synchronized (q) {
            Point last = q.peekLast();
            if (last != null && now - last.timeMs() < Math.max(MIN_STEP_MS, stepMs)) return;
            q.addLast(new Point(now, value));
            long cutoff = now - retentionMs.get(id);
            while (!q.isEmpty() && (q.peekFirst().timeMs() < cutoff || q.size() > MAX_POINTS)) q.pollFirst();
        }
    }

    /** Samples at or after {@code sinceMs}, oldest first. */
    public List<Point> since(String id, long sinceMs) {
        ArrayDeque<Point> q = series.get(id);
        if (q == null) return List.of();
        synchronized (q) {
            List<Point> out = new ArrayList<>();
            for (Point p : q) if (p.timeMs() >= sinceMs) out.add(p);
            return out;
        }
    }
}
