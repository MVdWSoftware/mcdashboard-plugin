/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

import org.mcdashboard.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The table behind a LIST placeholder (a leaderboard, for example): {@code rows} rows, each column a
 * {@code %placeholder%} template in which {@code {n}} is the 1-based row number. {@code empty} lists the cell values
 * (colour codes stripped, trimmed) that mean "no entry"; trailing rows made only of such cells are dropped.
 *
 * @param rows    The number of rows in the table.
 * @param columns The ordered column name to {@code %placeholder%} template pairs.
 * @param empty   The cell values that count as "no entry".
 */
public record ListSpec(int rows, Map<String, String> columns, List<String> empty) {
    public static final int DEFAULT_ROWS = 10;
    public static final int MAX_ROWS = 50;
    public static final int MAX_COLUMNS = 6;
    /** Each text cell is cut to this many characters. */
    public static final int MAX_CELL = 256;

    private static final Pattern COLUMN = Pattern.compile("[a-z][a-z0-9_]{0,23}");
    private static final Pattern TEMPLATE = Pattern.compile("%[^%\\s]+%");
    private static final Pattern COLOUR = Pattern.compile("[§&][0-9a-fk-orA-FK-OR]");
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d{1,3}(,\\d{3})+(\\.\\d+)?|[-+]?\\d+(\\.\\d+)?|[-+]?\\.\\d+");

    /** Parses the {@code rows}, {@code columns} and {@code empty} keys of a placeholders.yml entry; throws on bad input. */
    static ListSpec parse(String id, Map<?, ?> m) {
        int rows = DEFAULT_ROWS;
        if (m.get("rows") != null) {
            if (!(m.get("rows") instanceof Number n)) throw new IllegalArgumentException("placeholder '" + id + "': rows must be a number");
            rows = Math.max(1, Math.min(MAX_ROWS, n.intValue()));
        }
        if (!(m.get("columns") instanceof Map<?, ?> cols) || cols.isEmpty()) {
            throw new IllegalArgumentException("placeholder '" + id + "': a LIST needs columns (name: '%placeholder_{n}%')");
        }
        if (cols.size() > MAX_COLUMNS) throw new IllegalArgumentException("placeholder '" + id + "': at most " + MAX_COLUMNS + " columns");
        Map<String, String> columns = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : cols.entrySet()) {
            String name = String.valueOf(e.getKey());
            String template = e.getValue() == null ? null : e.getValue().toString();
            if (!COLUMN.matcher(name).matches()) throw new IllegalArgumentException("placeholder '" + id + "': invalid column name: " + name);
            if (template == null || !TEMPLATE.matcher(template).matches()) {
                throw new IllegalArgumentException("placeholder '" + id + "': column '" + name + "' needs a single %placeholder% value");
            }
            columns.put(name, template);
        }
        List<String> empty = new ArrayList<>();
        if (m.get("empty") instanceof List<?> l) {
            for (Object o : l) if (o != null) empty.add(strip(o.toString()));
        } else if (m.get("empty") != null) {
            empty.add(strip(m.get("empty").toString()));
        }
        return new ListSpec(rows, columns, List.copyOf(empty));
    }

    private static String strip(String s) {
        return COLOUR.matcher(s).replaceAll("").trim();
    }

    /**
     * Resolves every cell through {@code resolver} (one {@code %placeholder%} at a time, {@code {n}} already replaced)
     * and encodes the table as the compact JSON sent in {@code vs}. Null when no cell could be resolved at all.
     */
    String resolve(Function<String, String> resolver) {
        List<List<Object>> table = new ArrayList<>();
        int last = 0;
        boolean any = false;
        for (int n = 1; n <= rows; n++) {
            List<Object> row = new ArrayList<>();
            boolean blank = true;
            for (String template : columns.values()) {
                String t = template.replace("{n}", Integer.toString(n));
                String raw = resolver.apply(t);
                if (raw == null || raw.equals(t)) raw = ""; // unresolved: PlaceholderAPI leaves the text as it is
                else any = true;
                if (!isEmpty(raw)) blank = false;
                row.add(cell(raw));
            }
            table.add(row);
            if (!blank) last = n;
        }
        if (!any) return null;
        List<List<Object>> out = table.subList(0, last);
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("columns", new ArrayList<>(columns.keySet()));
        json.put("rows", out);
        return Json.write(json);
    }

    private boolean isEmpty(String raw) {
        String s = strip(raw);
        return s.isEmpty() || empty.contains(s);
    }

    /** A finite number when the cell is one (colour codes and thousands separators ignored), else the text cut to {@link #MAX_CELL}. */
    static Object cell(String raw) {
        String s = strip(raw);
        if (NUMBER.matcher(s).matches()) {
            try {
                String plain = s.replace(",", "");
                if (plain.matches("[-+]?\\d+")) {
                    if (!plain.matches("[-+]?0\\d+")) return Long.parseLong(plain.startsWith("+") ? plain.substring(1) : plain);
                } else {
                    double d = Double.parseDouble(plain);
                    if (Double.isFinite(d)) return d;
                }
            } catch (NumberFormatException e) {
                // beyond a long: keep the text
            }
        }
        String text = raw.trim();
        return text.length() > MAX_CELL ? text.substring(0, MAX_CELL) : text;
    }
}
