/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.nukkit;

import org.mcdashboard.config.YamlCodec;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.LinkedHashMap;
import java.util.Map;

/** The YAML library PowerNukkitX ships, as the plain maps the core works with. */
final class NukkitYaml implements YamlCodec {
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> parse(String yaml) {
        try {
            Object root = new Yaml().load(yaml);
            if (root == null) return new LinkedHashMap<>();
            if (root instanceof Map<?, ?> map) return (Map<String, Object>) map;
            throw new IllegalArgumentException("the document is not a mapping");
        } catch (YAMLException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    @Override
    public String dump(Map<String, Object> map) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(map);
    }
}
