/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.dashboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A predefined dashboard a server shows to the players who may see it. Its permission is derived from the file name:
 * {@code admin.yml} is shown only to players with {@code mcdashboard.dashboard.admin}.
 *
 * @param id              The dashboard identifier, matching its YAML file name without extension.
 * @param name            Display name, translatable.
 * @param description     Description, translatable; null when there is none.
 * @param order           Sort order among dashboards (lower first).
 * @param background      Background colour key or gradient specification; null when unset.
 * @param backgroundImage Optional background picture configuration; null when unset.
 * @param items           The dashboard's tiles.
 */
public record Dashboard(String id, LocalizedText name, LocalizedText description, int order, String background,
                        TileLook.BackgroundImage backgroundImage, List<Item> items) {
    /** Prefix of every dashboard permission. */
    public static final String PERMISSION_PREFIX = "mcdashboard.dashboard.";

    /**
     * The permission a player needs to see this dashboard, derived from its id (the YAML file name).
     *
     * @return The full permission node, e.g. {@code mcdashboard.dashboard.admin}.
     */
    public String permission() {
        return PERMISSION_PREFIX + id;
    }

    /**
     * One tile: one or more placeholders rendered by a widget at a grid position. Widgets that combine values (list,
     * line, bar, pie) take several. {@code icon} ({@code pack:name}) and {@code options} (see {@link TileLook}) are
     * optional and only passed on to the app.
     *
     * @param placeholders The placeholder ids this tile renders.
     * @param widget       The widget type to display the values with.
     * @param title        The tile's display label; null when there is no title.
     * @param column       Grid column (zero-based).
     * @param row          Grid row (zero-based).
     * @param width        Number of columns the tile spans.
     * @param height       Number of rows the tile spans.
     * @param icon         Icon in {@code pack:name} format; null when unset.
     * @param options      Widget-specific configuration options; null when unset.
     */
    public record Item(List<String> placeholders, String widget, LocalizedText title, int column, int row, int width, int height,
                       String icon, Map<String, Object> options) {
        /** An item without icon or options.
         *
         * @param placeholders The placeholder ids this tile renders.
         * @param widget       The widget type to display the values with.
         * @param title        The tile's display label; null when there is no title.
         * @param column       Grid column (zero-based).
         * @param row          Grid row (zero-based).
         * @param width        Number of columns the tile spans.
         * @param height       Number of rows the tile spans.
         */
        public Item(List<String> placeholders, String widget, LocalizedText title, int column, int row, int width, int height) {
            this(placeholders, widget, title, column, row, width, height, null, null);
        }

        /** The same item showing other placeholders.
         *
         * @param ids The placeholder ids to display instead.
         * @return A new Item with these placeholders and the same widget, position, and options.
         */
        public Item withPlaceholders(List<String> ids) {
            return new Item(ids, widget, title, column, row, width, height, icon, options);
        }

        /** The first placeholder, which single-value widgets use.
         *
         * @return The id of the primary placeholder for this tile.
         */
        public String placeholder() {
            return placeholders.get(0);
        }
    }

    /** Builds a dashboard from a parsed dashboards/*.yml file.
     *
     * @param id The dashboard identifier derived from the YAML file name.
     * @param m  The parsed YAML map containing items, name, description and optional settings.
     * @return A new Dashboard populated from the provided data.
     */
    public static Dashboard fromMap(String id, Map<?, ?> m) {
        List<Item> items = new ArrayList<>();
        Object raw = m.get("items");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> i)) continue;
                List<String> ids = placeholdersOf(i);
                if (ids.isEmpty()) continue;
                items.add(new Item(ids,
                        String.valueOf(or(i.get("widget"), "text")),
                        LocalizedText.parse(i.get("title")),
                        intOf(i.get("column"), 0), intOf(i.get("row"), items.size()),
                        Math.max(1, intOf(i.get("width"), 1)), Math.max(1, intOf(i.get("height"), 1)),
                        TileLook.icon(i.get("icon")), TileLook.options(i.get("options"))));
            }
        }
        LocalizedText name = LocalizedText.parse(m.get("name"));
        if (name == null) name = LocalizedText.of(id);
        return new Dashboard(id, name, LocalizedText.parse(m.get("description")),
                intOf(m.get("order"), 100),
                m.get("background") == null ? null : m.get("background").toString().trim().toLowerCase(Locale.ROOT),
                TileLook.backgroundImage(m.get("background-image")), items);
    }

    /** JSON view sent to clients, translated for the caller's {@code preferred} languages (best first).
     *
     * @param preferred       The caller's preferred languages in priority order.
     * @param defaultLanguage The server's fallback language when nothing matches.
     * @return A map representing this dashboard as JSON for the API response.
     */
    public Map<String, Object> toJson(List<String> preferred, String defaultLanguage) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", id);
        o.put("name", name.resolve(preferred, defaultLanguage));
        if (description != null) o.put("description", description.resolve(preferred, defaultLanguage));
        o.put("permission", permission());
        if (background != null && !background.isEmpty()) o.put("background", background);
        if (backgroundImage != null) o.put("backgroundImage", backgroundImage.toJson());
        List<Object> list = new ArrayList<>();
        for (Item i : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("placeholder", i.placeholder());
            if (i.placeholders().size() > 1) m.put("placeholders", i.placeholders());
            m.put("widget", i.widget());
            if (i.title() != null) m.put("title", i.title().resolve(preferred, defaultLanguage));
            if (i.icon() != null) m.put("icon", i.icon());
            if (i.options() != null) m.put("options", i.options());
            m.put("column", i.column());
            m.put("row", i.row());
            m.put("width", i.width());
            m.put("height", i.height());
            list.add(m);
        }
        o.put("items", list);
        return o;
    }

    /** The languages this dashboard uses, for {@code /v1/info}.
     *
     * @return All unique language tags used across names, descriptions and titles.
     */
    public Set<String> languages() {
        Set<String> out = new LinkedHashSet<>();
        out.addAll(name.languages());
        if (description != null) out.addAll(description.languages());
        for (Item i : items) if (i.title() != null) out.addAll(i.title().languages());
        return out;
    }

    /**
     * Problems that stop the dashboard from looking the way it is drawn: a tile outside the four-column grid (including a
     * negative position) or two tiles on the same cells. They do not stop the dashboard from being served, so the plugin
     * reports them in the console and the app still shows the dashboard.
     *
     * @return One readable problem per offending tile or pair of tiles, in tile order; empty when the layout is valid.
     */
    public List<String> layoutProblems() {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Item a = items.get(i);
            if (a.column() < 0 || a.row() < 0) {
                problems.add("tile " + (i + 1) + " starts before the grid (column and row must be 0 or more)");
            }
            if (a.column() + a.width() > 4) {
                problems.add("tile " + (i + 1) + " spans columns " + (a.column() + 1) + "-" + (a.column() + a.width())
                        + ", past the 4-column grid");
            }
            for (int j = i + 1; j < items.size(); j++) {
                Item b = items.get(j);
                if (a.column() < b.column() + b.width() && b.column() < a.column() + a.width()
                        && a.row() < b.row() + b.height() && b.row() < a.row() + a.height()) {
                    problems.add("tiles " + (i + 1) + " and " + (j + 1) + " overlap at column "
                            + (Math.max(a.column(), b.column()) + 1) + ", row " + (Math.max(a.row(), b.row()) + 1));
                }
            }
        }
        return problems;
    }

    /** The ids of an item: {@code placeholders: [a, b]}, else the single {@code placeholder}. */
    private static List<String> placeholdersOf(Map<?, ?> item) {
        List<String> ids = new ArrayList<>();
        if (item.get("placeholders") instanceof List<?> list) {
            for (Object o : list) if (o != null && !o.toString().isBlank()) ids.add(o.toString().trim());
        } else if (item.get("placeholder") != null) {
            ids.add(item.get("placeholder").toString().trim());
        }
        return ids;
    }

    private static int intOf(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    private static Object or(Object value, Object fallback) {
        return value == null ? fallback : value;
    }
}
