package org.mcdashboard.auth;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Sliding-window limiter keyed by caller (normally the remote address). */
public final class RateLimiter {
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int max;
    private final long windowMs;

    public RateLimiter(Clock clock, int max, long windowMs) {
        this.clock = clock;
        this.max = max;
        this.windowMs = windowMs;
    }

    /** Records an attempt; false when the key has exceeded the limit. */
    public boolean allow(String key) {
        long now = clock.millis();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() < now - windowMs) q.pollFirst();
            if (q.size() >= max) return false;
            q.addLast(now);
            return true;
        }
    }
}
