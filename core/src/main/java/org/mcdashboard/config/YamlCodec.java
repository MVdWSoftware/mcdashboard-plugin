/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.config;

import java.util.Map;

/** Reads and writes YAML as plain maps and lists; every server platform ships a YAML library, so the core brings none. */
public interface YamlCodec {
    /** The document as nested maps and lists (never sections or other library types), empty for an empty document. */
    Map<String, Object> parse(String yaml) throws IllegalArgumentException;

    /** The map as a block style YAML document. */
    String dump(Map<String, Object> map);
}
