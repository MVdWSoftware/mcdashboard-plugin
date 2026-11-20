/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Turns the {@code catalog:} entries of {@code placeholders.yml} into AUTO definitions, one per placeholder an
 * expansion declares. Parameterised placeholders ({@code <}, {@code {} or {@code [}) are skipped, a deny list keeps
 * sensitive parameters out (always including {@link #DEFAULT_DENY}) and {@code max} caps the amount per expansion.
 */
public final class ExpansionCatalog {
    /** Applied to every catalog entry, even when not configured. Matched case-insensitively against the parameter. */
    public static final List<String> DEFAULT_DENY = List.of("*ip*", "*password*", "*token*", "*secret*", "*address*");
    public static final int DEFAULT_MAX = 2000;

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    /** Characters a parameter may not contain: markup, a placeholder opener, a percent sign or whitespace. */
    private static final Pattern FORBIDDEN = Pattern.compile("[<{\\[%\\s]");

    /** One {@code catalog:} entry. */
    public record Entry(String expansion, String group, PlaceholderDefinition.Access access, String permission,
                        List<String> deny, int max, List<String> params) {
        public static Entry fromMap(Map<?, ?> m) {
            Object ex = m.get("expansion");
            if (ex == null || !ex.toString().matches("[A-Za-z0-9_]{1,40}")) {
                throw new IllegalArgumentException("catalog entry needs an 'expansion' identifier");
            }
            String id = ex.toString();
            Object g = m.get("group");
            String group = g == null || g.toString().isBlank() ? id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1) : g.toString().trim();
            PlaceholderDefinition.Access access = PlaceholderDefinition.Access.valueOf(
                    String.valueOf(m.get("access") == null ? "PUBLIC" : m.get("access")).toUpperCase(Locale.ROOT));
            Object p = m.get("permission");
            String permission = p == null || p.toString().isBlank() ? null : p.toString().trim();
            List<String> deny = new ArrayList<>();
            if (m.get("deny") instanceof List<?> l) for (Object o : l) if (o != null) deny.add(o.toString());
            int max = m.get("max") instanceof Number n && n.intValue() > 0 ? n.intValue() : DEFAULT_MAX;
            List<String> params = new ArrayList<>();
            if (m.get("params") instanceof List<?> l) {
                for (Object o : l) {
                    String param = o == null ? "" : o.toString().trim();
                    if (!ID.matcher(param).matches()) throw new IllegalArgumentException("invalid param '" + param + "' for " + id);
                    params.add(param);
                }
            }
            return new Entry(id, group, access, permission, deny, max, params);
        }
    }

    private ExpansionCatalog() {
    }

    /** Parses the entries; invalid ones are reported through {@code onError} and skipped. */
    public static List<Entry> parseAll(List<? extends Map<?, ?>> entries, Consumer<String> onError) {
        List<Entry> out = new ArrayList<>();
        for (Map<?, ?> e : entries) {
            try {
                out.add(Entry.fromMap(e));
            } catch (RuntimeException ex) {
                onError.accept("catalog: " + ex.getMessage());
            }
        }
        return out;
    }

    /** Builds the AUTO definitions; an expansion that is not installed is reported through {@code warn} and skipped. */
    public static List<PlaceholderDefinition> build(List<Entry> entries, ExpansionLookup lookup, Consumer<String> warn) {
        return build(entries, lookup, warn, new LinkedHashMap<>());
    }

    /**
     * Like {@link #build(List, ExpansionLookup, Consumer)}; {@code counts} receives the number of definitions each
     * expansion yielded. Discovered placeholders and the entry's {@code params} are combined.
     */
    public static List<PlaceholderDefinition> build(List<Entry> entries, ExpansionLookup lookup, Consumer<String> warn,
                                                    Map<String, Integer> counts) {
        Map<String, PlaceholderDefinition> out = new LinkedHashMap<>();
        for (Entry e : entries) {
            List<String> found = lookup.placeholders(e.expansion());
            if (found == null) {
                warn.accept("catalog: expansion '" + e.expansion() + "' is not installed, skipping");
                continue;
            }
            List<String> declared = new ArrayList<>(found);
            for (String p : e.params()) declared.add("%" + e.expansion() + "_" + p + "%");
            List<Pattern> deny = new ArrayList<>();
            for (String g : DEFAULT_DENY) deny.add(glob(g));
            for (String g : e.deny()) deny.add(glob(g));
            String prefix = "%" + e.expansion() + "_";
            int added = 0;
            for (String raw : declared) {
                if (added >= e.max()) {
                    warn.accept("catalog: expansion '" + e.expansion() + "' capped at " + e.max() + " placeholders");
                    break;
                }
                String param = param(raw, prefix);
                if (param == null || denied(deny, param)) continue;
                String id = e.expansion() + "_" + param;
                if (!ID.matcher(id).matches() || out.containsKey(id)) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("name", humanise(param));
                m.put("value", prefix + param + "%");
                m.put("type", "AUTO");
                m.put("access", e.access().name());
                m.put("group", e.group());
                m.put("min-interval", 5);
                if (e.permission() != null) m.put("permission", e.permission());
                out.put(id, PlaceholderDefinition.fromMap(m));
                added++;
            }
            counts.merge(e.expansion(), added, Integer::sum);
        }
        return new ArrayList<>(out.values());
    }

    /** True when an installed expansion yielded nothing and no {@code params} were configured: discovery may still succeed later. */
    public static boolean worthRetrying(List<Entry> entries, Map<String, Integer> counts) {
        for (Entry e : entries) if (e.params().isEmpty() && counts.getOrDefault(e.expansion(), 0) == 0) return true;
        return false;
    }

    /** The final log lines: how many placeholders each expansion yielded, and a warning for those that yielded none. */
    public static List<String> report(List<Entry> entries, Map<String, Integer> counts) {
        List<String> out = new ArrayList<>();
        for (String id : new LinkedHashSet<>(entries.stream().map(Entry::expansion).toList())) {
            if (!counts.containsKey(id)) continue; // not installed: already warned about
            int n = counts.get(id);
            out.add(n > 0 ? "catalog: " + id + ": " + n + " placeholders"
                    : "WARN catalog: " + id + " yielded 0 placeholders (it declares none and PlaceholderAPI's cloud data has none); list them with 'params: [a, b]' in the catalog entry");
        }
        return out;
    }

    /** Explicit definitions first; a catalog definition with an id that already exists is dropped. */
    public static List<PlaceholderDefinition> merge(List<PlaceholderDefinition> explicit, List<PlaceholderDefinition> catalog) {
        Map<String, PlaceholderDefinition> all = new LinkedHashMap<>();
        for (PlaceholderDefinition d : explicit) all.put(d.id(), d);
        for (PlaceholderDefinition d : catalog) all.putIfAbsent(d.id(), d);
        return new ArrayList<>(all.values());
    }

    /** {@code "%server_online%"} with prefix {@code "%server_"} gives {@code "online"}; null when it cannot be used. */
    static String param(String raw, String prefix) {
        if (raw == null || !raw.startsWith(prefix) || !raw.endsWith("%") || raw.length() <= prefix.length() + 1) return null;
        String param = raw.substring(prefix.length(), raw.length() - 1);
        if (FORBIDDEN.matcher(param).find()) return null;
        return param;
    }

    static String humanise(String param) {
        StringBuilder sb = new StringBuilder();
        for (String w : param.split("[_.\\-]+")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.length() == 0 ? param : sb.toString();
    }

    private static boolean denied(List<Pattern> deny, String param) {
        for (Pattern p : deny) if (p.matcher(param).matches()) return true;
        return false;
    }

    /** Glob with {@code *} and {@code ?}, case-insensitive. */
    static Pattern glob(String g) {
        StringBuilder sb = new StringBuilder();
        for (char c : g.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if (c == '?') sb.append('.');
            else sb.append(Pattern.quote(String.valueOf(c)));
        }
        return Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }
}
