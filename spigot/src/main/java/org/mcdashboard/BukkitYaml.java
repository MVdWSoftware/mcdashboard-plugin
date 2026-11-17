/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.mcdashboard.config.YamlCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bukkit's own YAML reader and writer, turned into the plain maps and lists the core works with. */
final class BukkitYaml implements YamlCodec {
    @Override
    public Map<String, Object> parse(String yaml) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
        return toMap(config);
    }

    @Override
    public String dump(Map<String, Object> map) {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<String, Object> e : map.entrySet()) config.set(e.getKey(), e.getValue());
        return config.saveToString();
    }

    /** The section as nested maps, or null when it is absent. */
    static Map<String, Object> toMap(ConfigurationSection section) {
        if (section == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) out.put(key, plain(section.get(key)));
        return out;
    }

    /** A YAML value with every nested section or map turned into a {@code Map<String, Object>}. */
    private static Object plain(Object value) {
        if (value instanceof ConfigurationSection section) return toMap(section);
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
}
