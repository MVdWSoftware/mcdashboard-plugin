/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Typed reads from a parsed YAML document by dotted path ({@code server.websocket.port}); a missing or mistyped value yields the default. */
public final class ConfigView {
    private final Map<String, Object> root;

    private ConfigView(Map<String, Object> root) {
        this.root = root;
    }

    public static ConfigView of(Map<String, Object> root) {
        return new ConfigView(root == null ? Map.of() : root);
    }

    /** {@code overlay} on top of {@code base}: nested maps are merged key by key, every other value is replaced. */
    public static Map<String, Object> merge(Map<String, Object> base, Map<String, Object> overlay) {
        Map<String, Object> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> e : overlay.entrySet()) {
            Object mine = out.get(e.getKey());
            if (mine instanceof Map<?, ?> a && e.getValue() instanceof Map<?, ?> b) {
                out.put(e.getKey(), merge(strings(a), strings(b)));
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    public Object get(String path) {
        Object node = root;
        for (String part : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) return null;
            node = map.get(part);
        }
        return node;
    }

    public String getString(String path) {
        return getString(path, null);
    }

    public String getString(String path, String fallback) {
        Object v = get(path);
        return v == null ? fallback : v.toString();
    }

    public boolean getBoolean(String path, boolean fallback) {
        return get(path) instanceof Boolean b ? b : fallback;
    }

    public int getInt(String path, int fallback) {
        return (int) getLong(path, fallback);
    }

    public long getLong(String path, long fallback) {
        Object v = get(path);
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                // falls through to the default
            }
        }
        return fallback;
    }

    /** The list's scalar items as strings; empty when the value is not a list. */
    public List<String> getStringList(String path) {
        List<String> out = new ArrayList<>();
        if (get(path) instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String || o instanceof Number || o instanceof Boolean || o instanceof Character) out.add(o.toString());
            }
        }
        return out;
    }

    /** The list's map items; empty when the value is not a list. */
    public List<Map<?, ?>> getMapList(String path) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (get(path) instanceof List<?> list) {
            for (Object o : list) if (o instanceof Map<?, ?> m) out.add(m);
        }
        return out;
    }

    /** The nested map at the path, or null when it is absent or not a map. */
    public Map<String, Object> getSection(String path) {
        return get(path) instanceof Map<?, ?> m ? strings(m) : null;
    }

    private static Map<String, Object> strings(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }
}
