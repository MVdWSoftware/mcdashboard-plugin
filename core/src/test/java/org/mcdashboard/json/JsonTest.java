/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void roundTrip() {
        Map<String, Object> value = Map.of("s", "a\"b\\c\n\u0001é", "n", 42L, "d", 1.5, "b", true, "l", List.of(1L, "x"));
        assertEquals(value, Json.parse(Json.write(value)));
    }

    @Test
    void numbersBeyondLongBecomeDoubles() {
        assertEquals(Long.MAX_VALUE, Json.parse("9223372036854775807"));
        assertEquals(1e20, Json.parse("100000000000000000000"));
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad : new String[]{"\"\\u-001\"", "\"\\u12\"", "{\"a\":1,}", "[1 2]", "01x", "\"open", "{} x"}) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
        }
    }

    @Test
    void limitsNesting() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(100) + "]".repeat(100)));
    }
}
