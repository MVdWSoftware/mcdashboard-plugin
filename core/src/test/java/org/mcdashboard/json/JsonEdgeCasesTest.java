/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.json;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonEdgeCasesTest {
    private static void bad(String text, String part) {
        var e = assertThrows(IllegalArgumentException.class, () -> Json.parse(text), text);
        assertTrue(e.getMessage().contains(part), e.getMessage());
    }

    @Test
    void rejectsMalformedDocuments() {
        bad("", "unexpected end");
        bad("[1,", "unexpected end");
        bad("[1 2]", "expected , or ]");
        bad("[1", "unterminated array");
        bad("{\"a\" 1}", "expected :");
        bad("{\"a\":1 \"b\":2}", "expected , or }");
        bad("{\"a\":1", "unterminated object");
        bad("{a:1}", "expected key");
        bad("{", "expected key");
        bad("{\"a\"", "expected :");
        bad("tru", "unexpected token");
        bad("@", "unexpected character");
        bad("1.2.3", "bad number");
        bad("\"abc", "unterminated string");
        bad("\"a\\", "unterminated string");
        bad("\"\\x\"", "bad escape");
        bad("\"\\u12\"", "bad escape");
        bad("\"\\u12G4\"", "bad escape");
        bad("[".repeat(200), "too deep");
        bad("1 2", "");
    }

    @Test
    void parsesEveryValueKind() {
        assertEquals(List.of(), Json.parse("[ ]"));
        assertEquals(Map.of(), Json.parse("{ }"));
        assertEquals(true, Json.parse("true"));
        assertEquals(false, Json.parse("false"));
        assertNull(Json.parse("null"));
        assertEquals(12345678901234567890.0, (Double) Json.parse("12345678901234567890"));
        assertEquals(-5L, Json.parse("-5"));
        assertEquals(1.5e3, Json.parse("1.5e3"));
        assertEquals("a\n\r\t\b\f\"\\/A", Json.parse("\"a\\n\\r\\t\\b\\f\\\"\\\\\\/\\u0041\""));
        assertEquals(Map.of("a", List.of(1L, 2L)), Json.parse(" {\"a\" : [1 , 2]} "));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[1]"));
        assertEquals(Map.of("k", 1L), Json.parseObject("{\"k\":1}"));
    }

    @Test
    void writesEveryValueKind() {
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put("n", null);
        m.put(1, "q\"\\\n\r\t\u0001");
        m.put("d", 1.5);
        m.put("nan", Double.NaN);
        m.put("inf", Double.POSITIVE_INFINITY);
        m.put("i", 3);
        m.put("b", true);
        m.put("l", new ArrayList<>(List.of(1, "x")));
        m.put("o", new StringBuilder("sb"));
        assertEquals("{\"n\":null,\"1\":\"q\\\"\\\\\\n\\r\\t\\u0001\",\"d\":1.5,\"nan\":null,\"inf\":null,\"i\":3,\"b\":true,\"l\":[1,\"x\"],\"o\":\"sb\"}", Json.write(m));
        assertEquals("[]", Json.write(List.of()));
        assertEquals("{}", Json.write(Map.of()));
    }
}
