/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.config;

import java.util.Map;

/** Reads and writes YAML as plain maps and lists; every server platform ships a YAML library, so the core brings none. */
public interface YamlCodec {
    /** Parses a YAML document string into nested maps and lists.
     *
     * @param yaml The YAML text to parse.
     * @return A {@code Map<String, Object>} tree representing the document.
     * @throws IllegalArgumentException When the input is not valid YAML.
     */
    Map<String, Object> parse(String yaml) throws IllegalArgumentException;

    /** Serializes a parsed configuration map back to a YAML block.
     *
     * @param map The configuration tree to serialize.
     * @return A UTF-8 YAML document string using block style.
     */
    String dump(Map<String, Object> map);
}
