/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recursive-descent parser behind {@link Json#parse}; nesting is capped so a hostile document cannot overflow the stack. */
final class JsonParser {
    private static final int MAX_DEPTH = 32;
    private final String s;
    private int i;

    JsonParser(String s) {
        this.s = s;
    }

    Object document() {
        skip();
        Object v = value(0);
        skip();
        if (i != s.length()) throw error("trailing data");
        return v;
    }

    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("Invalid JSON at " + i + ": " + msg);
    }

    private void skip() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw error("too deep");
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                return number();
        }
    }

    private Object literal(String word, Object result) {
        if (!s.startsWith(word, i)) throw error("unexpected token");
        i += word.length();
        return result;
    }

    private Object number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw error("unexpected character");
        String n = s.substring(start, i);
        try {
            if (n.matches("-?\\d+")) {
                try {
                    return Long.parseLong(n);
                } catch (NumberFormatException tooLarge) {
                    return Double.parseDouble(n);
                }
            }
            return Double.parseDouble(n);
        } catch (NumberFormatException e) {
            throw error("bad number");
        }
    }

    private String string() {
        i++;
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length() || !s.substring(i, i + 4).matches("[0-9A-Fa-f]{4}")) throw error("bad escape");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    case '"', '\\', '/' -> sb.append(e);
                    default -> throw error("bad escape");
                }
            } else {
                sb.append(c);
            }
        }
        throw error("unterminated string");
    }

    private List<Object> array(int depth) {
        i++;
        List<Object> list = new ArrayList<>();
        skip();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return list;
        }
        while (true) {
            skip();
            list.add(value(depth + 1));
            skip();
            if (i >= s.length()) throw error("unterminated array");
            char c = s.charAt(i++);
            if (c == ']') return list;
            if (c != ',') throw error("expected , or ]");
        }
    }

    private Map<String, Object> object(int depth) {
        i++;
        Map<String, Object> map = new LinkedHashMap<>();
        skip();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return map;
        }
        while (true) {
            skip();
            if (i >= s.length() || s.charAt(i) != '"') throw error("expected key");
            String key = string();
            skip();
            if (i >= s.length() || s.charAt(i++) != ':') throw error("expected :");
            skip();
            map.put(key, value(depth + 1));
            skip();
            if (i >= s.length()) throw error("unterminated object");
            char c = s.charAt(i++);
            if (c == '}') return map;
            if (c != ',') throw error("expected , or }");
        }
    }
}
