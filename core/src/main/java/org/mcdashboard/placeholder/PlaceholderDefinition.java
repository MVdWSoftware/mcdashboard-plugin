/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

import org.mcdashboard.auth.PermissionCache;
import org.mcdashboard.image.ImageSource;
import org.mcdashboard.image.map.MapSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A placeholder the server owner allows the dashboard to read. Only definitions listed in
 * {@code placeholders.yml} are ever resolved; clients refer to them by {@code id}. {@code map} is the parsed block of a
 * {@code map:} IMAGE source (null otherwise); {@code encode} false makes a {@code url:} IMAGE send its expanded https URL
 * in {@code vs} for the app to load itself instead of the fetched bytes in {@code vd} (always true for other types).
 */
public record PlaceholderDefinition(String id, String name, String value, Type type, Access access,
                                    long minIntervalMs, IntervalOverrides minIntervalOverrides,
                                    Double min, Double max, String unit, int historyMinutes,
                                    long historyIntervalMs, String permission, String maxFrom, String group, String description,
                                    String source, ListSpec list, MapSpec map, boolean encode) {
    /** AUTO infers number, boolean or string from each value; IMAGE serves a picture from {@code source}; LIST is a table of {@code rows} x {@code columns}. */
    public enum Type { STRING, INTEGER, DECIMAL, BOOLEAN, PERCENTAGE, AUTO, IMAGE, LIST }

    public static final String DEFAULT_GROUP = "General";

    /** PUBLIC placeholders are readable by anyone who can reach the server; PLAYER needs a paired device. */
    public enum Access { PUBLIC, PLAYER }

    /** Builds a definition from a parsed {@code placeholders.yml} entry. */
    public static PlaceholderDefinition fromMap(Map<?, ?> m) {
        String id = str(m.get("id"));
        String value = str(m.get("value"));
        if (id == null || !id.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("invalid id: " + id);
        Type type = Type.valueOf(String.valueOf(or(m.get("type"), "STRING")).toUpperCase(Locale.ROOT));
        String source = null;
        ListSpec list = null;
        MapSpec map = null;
        boolean encode = true;
        if (type == Type.IMAGE) {
            source = str(m.get("source"));
            try {
                ImageSource.validate(source);
                if (source.startsWith("map:")) map = MapSpec.parse(source.substring(4), m.get("map"));
                Object enc = m.get("encode");
                if (enc != null && !(enc instanceof Boolean)) throw new IllegalArgumentException("encode must be true or false");
                encode = !Boolean.FALSE.equals(enc);
                if (!encode && !source.startsWith("url:")) {
                    throw new IllegalArgumentException("encode: false only applies to url: sources (" + source.substring(0, source.indexOf(':') + 1)
                            + " images are always sent as bytes); remove it");
                }
                if (!encode && !source.regionMatches(true, 4, "https://", 0, 8)) {
                    throw new IllegalArgumentException("encode: false needs an https:// url, because the app loads it directly");
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("placeholder '" + id + "': " + e.getMessage());
            }
            value = null;
        } else if (type == Type.LIST) {
            list = ListSpec.parse(id, m);
            value = null;
        } else if (value == null || !value.matches("%[^%\\s]+%")) {
            throw new IllegalArgumentException("placeholder '" + id + "' needs a single %placeholder% value");
        }
        String name = str(m.get("name"));
        Object interval = m.get("min-interval");
        long ms = interval instanceof Number n && n.longValue() > 0 ? n.longValue() * 1000 : 0;
        IntervalOverrides intervalOverrides = IntervalOverrides.parse(m.get("min-interval-permissions"),
                msg -> { throw new IllegalArgumentException("placeholder '" + id + "': " + msg); });
        String group = blankToNull(str(m.get("group")));
        return new PlaceholderDefinition(id, name == null ? id : name, value, type,
                Access.valueOf(String.valueOf(or(m.get("access"), "PUBLIC")).toUpperCase(Locale.ROOT)),
                ms, intervalOverrides, num(m.get("min-value")), num(m.get("max-value")), str(m.get("unit")),
                type != Type.IMAGE && type != Type.LIST && m.get("history") instanceof Number h ? Math.max(0, Math.min(h.intValue(), 525_600)) : 0,
                m.get("history-interval") instanceof Number hi && hi.longValue() > 0 ? hi.longValue() * 1000 : 0,
                blankToNull(str(m.get("permission"))), blankToNull(str(m.get("max-from"))),
                group == null ? DEFAULT_GROUP : group, blankToNull(str(m.get("description"))), source, list, map, encode);
    }

    /** A copy with another history window (minutes); used when {@code history.include} / {@code exclude} decide what is recorded. */
    public PlaceholderDefinition withHistoryMinutes(int minutes) {
        return new PlaceholderDefinition(id, name, value, type, access, minIntervalMs, minIntervalOverrides, min, max, unit, minutes,
                historyIntervalMs, permission, maxFrom, group, description, source, list, map, encode);
    }

    /**
     * Whether the caller may read this placeholder: PLAYER placeholders need a paired player, and a configured
     * {@code permission} needs that player to hold it. Applied on every route that returns a value or history.
     */
    public boolean readableBy(String playerUuid, PermissionCache permissions) {
        if (access == Access.PLAYER && playerUuid == null) return false;
        return permission == null || permissions.has(playerUuid, permission);
    }

    /** JSON view sent to clients. The raw {@code %placeholder%} template is never exposed. */
    public Map<String, Object> toJson() {
        return toJson(minIntervalMs);
    }

    /** JSON view with the interval that applies to this caller instead of the placeholder's configured one. */
    public Map<String, Object> toJson(long effectiveMinIntervalMs) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", id);
        o.put("name", name);
        o.put("type", type.name());
        o.put("access", access.name());
        o.put("group", group);
        if (description != null) o.put("description", description);
        o.put("minInterval", Math.max(0, effectiveMinIntervalMs) / 1000);
        if (min != null) o.put("min", min);
        if (max != null) o.put("max", max);
        if (unit != null) o.put("unit", unit);
        if (historyMinutes > 0) o.put("historyMinutes", historyMinutes);
        if (maxFrom != null) o.put("maxFrom", maxFrom);
        if (list != null) {
            o.put("columns", new ArrayList<>(list.columns().keySet()));
            o.put("rows", list.rows());
        }
        return o;
    }

    /** Parses all entries; invalid ones are reported through {@code onError} and skipped. */
    public static List<PlaceholderDefinition> parseAll(List<? extends Map<?, ?>> entries,
                                                       Consumer<String> onError) {
        List<PlaceholderDefinition> out = new ArrayList<>();
        for (Map<?, ?> e : entries) {
            try {
                out.add(fromMap(e));
            } catch (RuntimeException ex) {
                onError.accept(ex.getMessage());
            }
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }

    private static Object or(Object value, Object fallback) {
        return value == null ? fallback : value;
    }
}
