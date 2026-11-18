/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.bungee;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BungeeCord parses bungee.yml with SnakeYAML and skips the plugin on any error (an unquoted ": " in the description once did). */
class BungeeDescriptorTest {
    @Test
    void descriptorIsValidYamlAndNamesTheMainClass() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/bungee.yml")) {
            assertNotNull(in);
            Map<String, Object> d = new Yaml().load(in);
            assertEquals("MCDashboard", d.get("name"));
            assertEquals("org.mcdashboard.bungee.MCDashboardBungee", d.get("main"));
            assertTrue(d.get("description") instanceof String);
            assertTrue(Class.forName((String) d.get("main")) != null);
        }
    }
}
