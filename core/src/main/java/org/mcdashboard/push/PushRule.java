/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.push;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A threshold alert a device asked the server to watch: when {@code placeholder op threshold} holds for
 * {@code forSeconds}, push {@code title}/{@code body} (with {@code {label}}, {@code {value}} and {@code {server}} filled in),
 * and stay quiet for {@code cooldownSeconds}. The app writes the texts in its own language, so the server never translates.
 *
 * @param id              The rule's id, unique per device.
 * @param placeholder     The placeholder id to watch.
 * @param op              The comparison: {@code gt}, {@code gte}, {@code lt}, {@code lte}, {@code eq} or {@code ne}.
 * @param threshold       The number to compare with.
 * @param forSeconds      How long the condition must hold before it fires; 0 for at once.
 * @param cooldownSeconds The shortest time between two notifications of this rule.
 * @param label           The name of the value, for the {@code {label}} token.
 * @param title           The notification title template.
 * @param body            The notification body template.
 */
public record PushRule(String id, String placeholder, String op, double threshold, int forSeconds, int cooldownSeconds,
                       String label, String title, String body) {
    /** The comparisons a rule may use. */
    public static final List<String> OPERATORS = List.of("gt", "gte", "lt", "lte", "eq", "ne");
    /** The most rules one device may have. */
    public static final int MAX_RULES = 20;
    /** The shortest cooldown, so a flapping value cannot flood a phone. */
    public static final int MIN_COOLDOWN_SECONDS = 60;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");
    private static final Pattern PLACEHOLDER = Pattern.compile("[A-Za-z0-9_.:%-]{1,120}");

    /**
     * Reads and validates a rule from its JSON form.
     *
     * @param raw The JSON object.
     * @return The rule, with the cooldown raised to {@link #MIN_COOLDOWN_SECONDS} when it was lower.
     * @throws IllegalArgumentException with a message for the client when a field is missing or invalid.
     */
    public static PushRule parse(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) throw new IllegalArgumentException("a rule must be an object");
        String id = text(m, "id", 40);
        if (!ID.matcher(id).matches()) throw new IllegalArgumentException("rule id must be lower case letters, digits, - or _");
        String placeholder = text(m, "placeholder", 120);
        if (!PLACEHOLDER.matcher(placeholder).matches()) throw new IllegalArgumentException("rule placeholder is not a placeholder id");
        String op = text(m, "op", 4);
        if (!OPERATORS.contains(op)) throw new IllegalArgumentException("rule op must be one of " + OPERATORS);
        if (!(m.get("threshold") instanceof Number threshold) || !Double.isFinite(threshold.doubleValue())) {
            throw new IllegalArgumentException("rule threshold must be a number");
        }
        int forSeconds = bounded(m, "forSeconds", 0, 0, 86_400);
        int cooldown = Math.max(MIN_COOLDOWN_SECONDS, bounded(m, "cooldownSeconds", 900, 0, 604_800));
        return new PushRule(id, placeholder, op, threshold.doubleValue(), forSeconds, cooldown,
                optional(m, "label", 40), text(m, "title", 80), text(m, "body", 200));
    }

    /**
     * The JSON form of the rule.
     *
     * @return An ordered map for {@code Json.write}.
     */
    public Map<String, Object> toJson() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", id);
        o.put("placeholder", placeholder);
        o.put("op", op);
        o.put("threshold", threshold);
        o.put("forSeconds", forSeconds);
        o.put("cooldownSeconds", cooldownSeconds);
        if (label != null) o.put("label", label);
        o.put("title", title);
        o.put("body", body);
        return o;
    }

    /**
     * Whether the value satisfies the rule.
     *
     * @param value The current value of the placeholder.
     * @return True when {@code value op threshold} holds.
     */
    public boolean matches(double value) {
        return switch (op) {
            case "gt" -> value > threshold;
            case "gte" -> value >= threshold;
            case "lt" -> value < threshold;
            case "lte" -> value <= threshold;
            case "eq" -> value == threshold;
            default -> value != threshold;
        };
    }

    /**
     * Fills the tokens of a template.
     *
     * @param template The title or body template.
     * @param value    The value that fired the rule.
     * @param server   The server's name.
     * @return The text with {@code {label}}, {@code {value}} and {@code {server}} replaced and control characters removed.
     */
    public String render(String template, String value, String server) {
        String text = template.replace("{label}", label == null ? placeholder : label)
                .replace("{value}", value).replace("{server}", server == null ? "" : server);
        return text.replaceAll("[\\p{Cntrl}§]", "");
    }

    private static String text(Map<?, ?> m, String key, int max) {
        Object v = m.get(key);
        if (!(v instanceof String s) || s.isBlank()) throw new IllegalArgumentException("rule " + key + " is required");
        if (s.length() > max) throw new IllegalArgumentException("rule " + key + " is longer than " + max + " characters");
        return s.trim();
    }

    private static String optional(Map<?, ?> m, String key, int max) {
        Object v = m.get(key);
        if (v == null) return null;
        if (!(v instanceof String s) || s.length() > max) throw new IllegalArgumentException("rule " + key + " is invalid");
        return s.isBlank() ? null : s.trim();
    }

    private static int bounded(Map<?, ?> m, String key, int fallback, int min, int max) {
        Object v = m.get(key);
        if (v == null) return fallback;
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.longValue() < min || n.longValue() > max) {
            throw new IllegalArgumentException("rule " + key + " must be a whole number from " + min + " to " + max);
        }
        return n.intValue();
    }
}
