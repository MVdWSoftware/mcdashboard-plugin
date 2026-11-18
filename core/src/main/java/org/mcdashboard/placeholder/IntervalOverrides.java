/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

import org.mcdashboard.auth.PermissionCache;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Minimum refresh intervals by permission node. A player who holds a node uses that interval instead of the
 * configured default; when they hold several, the fastest wins. Anonymous callers always get the default.
 */
public final class IntervalOverrides {
    private static final IntervalOverrides EMPTY = new IntervalOverrides(Map.of());

    private final Map<String, Long> byPermissionMs;

    private IntervalOverrides(Map<String, Long> byPermissionMs) {
        this.byPermissionMs = byPermissionMs;
    }

    /** No overrides at all. */
    public static IntervalOverrides empty() {
        return EMPTY;
    }

    /**
     * Parses a {@code permission: seconds} map ({@code permission: 15} or {@code permission: "15"}). Problems are
     * reported through {@code warn} and the entry is skipped; a non-map yields no overrides.
     */
    public static IntervalOverrides parse(Object raw, Consumer<String> warn) {
        if (raw == null) return EMPTY;
        if (!(raw instanceof Map<?, ?> map)) {
            warn.accept("must be a map of permission nodes to seconds");
            return EMPTY;
        }
        Map<String, Long> parsed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String node = e.getKey() == null ? "" : e.getKey().toString().trim();
            Long ms = millis(e.getValue());
            if (node.isEmpty() || ms == null) {
                warn.accept("ignoring '" + e.getKey() + "': the value must be a positive number of seconds");
                continue;
            }
            parsed.put(node, ms);
        }
        return parsed.isEmpty() ? EMPTY : new IntervalOverrides(Collections.unmodifiableMap(parsed));
    }

    public boolean isEmpty() {
        return byPermissionMs.isEmpty();
    }

    /** Every node mentioned here, so the permission snapshots can track them. */
    public Set<String> permissions() {
        return byPermissionMs.keySet();
    }

    /** The fastest interval the player holds, or {@code fallbackMs} when no node matches (or the player is anonymous). */
    public long select(long fallbackMs, String playerUuid, PermissionCache permissions) {
        long fastest = Long.MAX_VALUE;
        boolean matched = false;
        for (Map.Entry<String, Long> e : byPermissionMs.entrySet()) {
            if (e.getValue() < fastest && permissions.has(playerUuid, e.getKey())) {
                fastest = e.getValue();
                matched = true;
            }
        }
        return matched ? fastest : fallbackMs;
    }

    private static Long millis(Object value) {
        long seconds;
        if (value instanceof Number n) {
            seconds = n.longValue();
        } else if (value instanceof String s) {
            try {
                seconds = Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        if (seconds <= 0 || seconds > Long.MAX_VALUE / 1000) return null;
        return seconds * 1000;
    }
}
