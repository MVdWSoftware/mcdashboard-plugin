/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import org.mcdashboard.dashboard.Dashboard;
import org.mcdashboard.dashboard.LocalizedText;
import org.mcdashboard.dashboard.TileLook;
import org.mcdashboard.placeholder.PlaceholderService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validates the body of {@code POST /v1/dashboards}. Server-owned fields (id, order, permission) are never taken from it. */
final class DashboardUpload {
    /** The widgets and backgrounds the app knows. */
    private static final Set<String> WIDGETS = Set.of("text", "image", "number", "progress", "list", "status",
            "gauge", "ring", "line", "area", "bar", "pie", "stat", "stacked", "heatmap");
    private static final Set<String> BACKGROUNDS = Set.of("grid", "dirt", "stone", "planks", "checker", "cobble", "creeper");
    private static final int MAX_ITEMS = 40;
    private static final int MAX_ITEM_PLACEHOLDERS = 12;

    private DashboardUpload() {
    }

    /** The draft dashboard; every placeholder must exist. Throws {@link BadRequest} with the first problem. */
    static Dashboard parse(Map<String, Object> body, PlaceholderService placeholders) {
        String name = text(body.get("name"), 64, true);
        String description = text(body.get("description"), 240, false);
        String background = text(body.get("background"), 32, false);
        if (background != null) background = background.toLowerCase(Locale.ROOT);
        if (background != null && !BACKGROUNDS.contains(background)) throw new BadRequest("unknown background");
        TileLook.BackgroundImage backgroundImage = null;
        if (body.get("backgroundImage") != null) {
            backgroundImage = TileLook.backgroundImage(body.get("backgroundImage"));
            if (backgroundImage == null) throw new BadRequest("backgroundImage needs an https:// url");
        }
        Object raw = body.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) throw new BadRequest("items must be a non-empty list");
        if (list.size() > MAX_ITEMS) throw new BadRequest("at most " + MAX_ITEMS + " items");
        List<Dashboard.Item> items = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> item)) throw new BadRequest("each item must be an object");
            String widget = text(item.get("widget"), 32, true);
            if (!WIDGETS.contains(widget)) throw new BadRequest("unknown widget: " + widget);
            List<String> ids = placeholders(item);
            for (String id : ids) {
                if (placeholders.definition(id).isEmpty()) throw new BadRequest("unknown placeholder: " + id);
            }
            String title = text(item.get("title"), 80, false);
            int column = integer(item.get("column"), 0);
            int row = integer(item.get("row"), items.size());
            int width = integer(item.get("width"), 1);
            int height = integer(item.get("height"), 1);
            if (column < 0 || column > 3) throw new BadRequest("column must be 0-3");
            if (row < 0 || row > 999) throw new BadRequest("row must be 0-999");
            if (width < 1 || width > 4) throw new BadRequest("width must be 1-4");
            if (height < 1 || height > 64) throw new BadRequest("height must be 1-64");
            String icon = null;
            if (item.get("icon") != null) {
                icon = TileLook.icon(item.get("icon"));
                if (icon == null) throw new BadRequest("icon must be pack:name");
            }
            if (item.get("options") != null && !(item.get("options") instanceof Map)) throw new BadRequest("options must be an object");
            items.add(new Dashboard.Item(ids, widget, LocalizedText.of(title), column, row, width, height, icon, TileLook.options(item.get("options"))));
        }
        return new Dashboard("dev", LocalizedText.of(name), LocalizedText.of(description), 900, background, backgroundImage, items);
    }

    /** {@code placeholders: [a, b]}, else the single {@code placeholder}; duplicates dropped. */
    private static List<String> placeholders(Map<?, ?> item) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (item.get("placeholders") instanceof List<?> list) {
            for (Object value : list) {
                String id = text(value, 128, false);
                if (id != null) ids.add(id);
            }
        } else {
            String single = text(item.get("placeholder"), 128, false);
            if (single != null) ids.add(single);
        }
        if (ids.isEmpty()) throw new BadRequest("an item needs a placeholder");
        if (ids.size() > MAX_ITEM_PLACEHOLDERS) throw new BadRequest("at most " + MAX_ITEM_PLACEHOLDERS + " placeholders per item");
        return new ArrayList<>(ids);
    }

    /** A trimmed string of at most {@code max} characters; empty becomes null unless it is required. */
    private static String text(Object value, int max, boolean required) {
        String text = value == null ? "" : value.toString().trim();
        if (text.isEmpty()) {
            if (required) throw new BadRequest("missing text");
            return null;
        }
        if (text.length() > max) throw new BadRequest("text is longer than " + max + " characters");
        return text;
    }

    private static int integer(Object value, int fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Number n)) throw new BadRequest("grid values must be numbers");
        return n.intValue();
    }
}
