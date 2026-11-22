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

    /** Creates a view over the given parsed YAML root map; null is treated as empty.
     *
     * @param root The parsed YAML root map, or null for an empty view.
     * @return A view over the given root.
     */
    public static ConfigView of(Map<String, Object> root) {
        return new ConfigView(root == null ? Map.of() : root);
    }

    /** {@code overlay} on top of {@code base}: nested maps are merged key by key, every other value is replaced.
     *
     * @param base The base configuration map.
     * @param overlay Values to merge in on top.
     * @return A new map with the overlay applied.
     */
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

    /** Resolves a dotted path in the YAML tree and returns the raw object at that node.
     *
     * @param path The dotted YAML path.
     * @return The raw value at the node, or null when absent.
     */
    public Object get(String path) {
        Object node = root;
        for (String part : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) return null;
            node = map.get(part);
        }
        return node;
    }

    /** The string at {@code path}, or null when absent.
     *
     * @param path The dotted YAML path.
     * @return The value as a string, or null.
     */
    public String getString(String path) {
        return getString(path, null);
    }

    /** The string at {@code path}, or {@code fallback} when absent or not a string.
     *
     * @param path The dotted YAML path.
     * @param fallback The value to return when absent.
     * @return The value as a string, or the fallback.
     */
    public String getString(String path, String fallback) {
        Object v = get(path);
        return v == null ? fallback : v.toString();
    }

    /** The boolean at {@code path}, or {@code fallback} when absent or not a boolean.
     *
     * @param path The dotted YAML path.
     * @param fallback The value to return when absent.
     * @return The boolean value, or the fallback.
     */
    public boolean getBoolean(String path, boolean fallback) {
        return get(path) instanceof Boolean b ? b : fallback;
    }

    /** The integer at {@code path}, or {@code fallback} when absent or not an integer.
     *
     * @param path The dotted YAML path.
     * @param fallback The value to return when absent.
     * @return The integer value, or the fallback.
     */
    public int getInt(String path, int fallback) {
        return (int) getLong(path, fallback);
    }

    /** The number at {@code path} as a long, or {@code fallback} when absent or not parseable.
     *
     * @param path The dotted YAML path.
     * @param fallback The value to return when absent.
     * @return The long value, or the fallback.
     */
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

    /** The list's scalar items as strings; empty when the value is not a list.
     *
     * @param path The dotted YAML path to read.
     * @return A new list of string representations for each item.
     */
    public List<String> getStringList(String path) {
        List<String> out = new ArrayList<>();
        if (get(path) instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String || o instanceof Number || o instanceof Boolean || o instanceof Character) out.add(o.toString());
            }
        }
        return out;
    }

    /** The list's map items; empty when the value is not a list.
     *
     * @param path The dotted YAML path to read.
     * @return A new list containing only the map entries from the original list.
     */
    public List<Map<?, ?>> getMapList(String path) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (get(path) instanceof List<?> list) {
            for (Object o : list) if (o instanceof Map<?, ?> m) out.add(m);
        }
        return out;
    }

    /** The nested map at the path, or null when it is absent or not a map.
     *
     * @param path The dotted YAML path to resolve.
     * @return A new map with all keys converted to strings, or null.
     */
    public Map<String, Object> getSection(String path) {
        return get(path) instanceof Map<?, ?> m ? strings(m) : null;
    }

    private static Map<String, Object> strings(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }
}
