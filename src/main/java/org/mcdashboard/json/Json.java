package org.mcdashboard.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer. Objects are {@link Map}, arrays {@link List}, numbers {@link Long}
 * or {@link Double}. Exists so the plugin needs no JSON library.
 */
public final class Json {
    private static final int MAX_DEPTH = 32;

    private Json() {
    }

    /** Serializes maps, lists, strings, numbers, booleans and null. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            quote(sb, s);
        } else if (v instanceof Double d) {
            sb.append(d.isNaN() || d.isInfinite() ? "null" : d.toString());
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /** Parses a JSON document; throws {@link IllegalArgumentException} on malformed input. */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skip();
        Object v = p.value(0);
        p.skip();
        if (p.i != text.length()) throw p.error("trailing data");
        return v;
    }

    /** Parses a JSON object. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected a JSON object");
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("Invalid JSON at " + i + ": " + msg);
        }

        void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        Object value(int depth) {
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

        Object literal(String word, Object result) {
            if (!s.startsWith(word, i)) throw error("unexpected token");
            i += word.length();
            return result;
        }

        Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw error("unexpected character");
            String n = s.substring(start, i);
            try {
                if (n.matches("-?\\d+")) return Long.parseLong(n);
                return Double.parseDouble(n);
            } catch (NumberFormatException e) {
                throw error("bad number");
            }
        }

        String string() {
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
                            if (i + 4 > s.length()) throw error("bad escape");
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            } catch (NumberFormatException ex) {
                                throw error("bad escape");
                            }
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

        List<Object> array(int depth) {
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

        Map<String, Object> object(int depth) {
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
}
