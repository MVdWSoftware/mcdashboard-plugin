package org.mcdashboard.dashboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A predefined dashboard a server shows to the players who may see it. Its permission is derived from the file name:
 * {@code admin.yml} is shown only to players with {@code mcdashboard.dashboard.admin}.
 */
public record Dashboard(String id, LocalizedText name, LocalizedText description, int order, String background, List<Item> items) {
    /** Prefix of every dashboard permission. */
    public static final String PERMISSION_PREFIX = "mcdashboard.dashboard.";

    /** The permission a player needs to see this dashboard, derived from its id (the YAML file name). */
    public String permission() {
        return PERMISSION_PREFIX + id;
    }

    /**
     * One tile: one or more placeholders rendered by a widget at a grid position. Widgets that combine values (list,
     * line, bar, pie) take several.
     */
    public record Item(List<String> placeholders, String widget, LocalizedText title, int column, int row, int width, int height) {
        /** The first placeholder, which single-value widgets and older apps use. */
        public String placeholder() {
            return placeholders.get(0);
        }
    }

    /** Builds a dashboard from a loaded dashboards/*.yml file (nested YAML sections are flattened to maps first). */
    public static Dashboard fromSection(String id, org.bukkit.configuration.ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) out.put(key, plain(section.get(key)));
        return fromMap(id, out);
    }

    /** Builds a dashboard from a parsed dashboards/*.yml file. */
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
                        Math.max(1, intOf(i.get("width"), 1)), Math.max(1, intOf(i.get("height"), 1))));
            }
        }
        LocalizedText name = LocalizedText.parse(m.get("name"));
        if (name == null) name = LocalizedText.of(id);
        return new Dashboard(id, name, LocalizedText.parse(m.get("description")),
                intOf(m.get("order"), 100),
                m.get("background") == null ? null : m.get("background").toString().trim().toLowerCase(java.util.Locale.ROOT), items);
    }

    /**
     * JSON view sent to clients, translated for one caller.
     * @param preferred - The caller's languages, best match first.
     * @param defaultLanguage - The server's default language.
     * @return The JSON object.
     */
    public Map<String, Object> toJson(List<String> preferred, String defaultLanguage) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", id);
        o.put("name", name.resolve(preferred, defaultLanguage));
        if (description != null) o.put("description", description.resolve(preferred, defaultLanguage));
        o.put("permission", permission());
        if (background != null && !background.isEmpty()) o.put("background", background);
        List<Object> list = new ArrayList<>();
        for (Item i : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("placeholder", i.placeholder());
            if (i.placeholders().size() > 1) m.put("placeholders", i.placeholders());
            m.put("widget", i.widget());
            if (i.title() != null) m.put("title", i.title().resolve(preferred, defaultLanguage));
            m.put("column", i.column());
            m.put("row", i.row());
            m.put("width", i.width());
            m.put("height", i.height());
            list.add(m);
        }
        o.put("items", list);
        return o;
    }

    /** The languages this dashboard uses, for {@code /v1/info}. */
    public Set<String> languages() {
        Set<String> out = new LinkedHashSet<>();
        out.addAll(name.languages());
        if (description != null) out.addAll(description.languages());
        for (Item i : items) if (i.title() != null) out.addAll(i.title().languages());
        return out;
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

    /** Bukkit hands nested YAML maps back as sections; turn them into plain maps for the model. */
    private static Object plain(Object value) {
        if (value instanceof org.bukkit.configuration.ConfigurationSection section) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : section.getKeys(false)) out.put(key, plain(section.get(key)));
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) out.put(String.valueOf(e.getKey()), plain(e.getValue()));
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(plain(item));
            return out;
        }
        return value;
    }

    private static int intOf(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    private static Object or(Object value, Object fallback) {
        return value == null ? fallback : value;
    }
}
