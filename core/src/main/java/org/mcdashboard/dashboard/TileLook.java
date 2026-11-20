/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.dashboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * How a dashboard and its tiles look: a tile's icon and display options and a dashboard's background picture. The
 * server only passes these on; it checks types and ranges so a YAML typo or an upload can never send the app anything
 * but the documented shape (see {@code TileOptions} and {@code BackgroundImage} in openapi.yaml).
 */
public final class TileLook {
    /** {@code pack:name}, the Iconify naming the app uses ({@code fa6-solid:memory}). */
    private static final Pattern ICON = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*:[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern BIOME = Pattern.compile("[a-zA-Z0-9_:. -]{1,64}");
    private static final Pattern STYLE = Pattern.compile("[a-z0-9-]{1,24}");
    private static final Pattern HTTPS = Pattern.compile("https://[^\\s\"'()<>\\\\]+", Pattern.CASE_INSENSITIVE);
    /** Named colours; the app maps each to a theme token. */
    public static final Set<String> COLORS = Set.of("accent", "green", "yellow", "orange", "red", "purple", "cyan", "muted");
    private static final int MAX_THRESHOLDS = 6;

    private TileLook() {
    }

    /** A dashboard's background picture. */
    public record BackgroundImage(String url, double dim, double blur, String fit) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("url", url);
            m.put("dim", dim);
            m.put("blur", blur);
            m.put("fit", fit);
            return m;
        }
    }

    /** The icon when it is a well-formed {@code pack:name} of at most 64 characters, else null. */
    public static String icon(Object raw) {
        if (raw == null) return null;
        String s = raw.toString().trim().toLowerCase(Locale.ROOT);
        return s.length() <= 64 && ICON.matcher(s).matches() ? s : null;
    }

    /** The known, well-typed tile options; unknown keys and bad values are dropped. Null when nothing is left. */
    public static Map<String, Object> options(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        if (m.get("style") instanceof String s && STYLE.matcher(s).matches()) out.put("style", s);
        if (m.get("color") instanceof String c && COLORS.contains(c)) out.put("color", c);
        if (m.get("thresholds") instanceof List<?> list) {
            List<Map<String, Object>> rules = new ArrayList<>();
            for (Object o : list) {
                if (rules.size() >= MAX_THRESHOLDS) break;
                if (!(o instanceof Map<?, ?> r)) continue;
                Object when = r.get("when");
                if (!("above".equals(when) || "below".equals(when)) || !(r.get("value") instanceof Number v)
                        || !Double.isFinite(v.doubleValue()) || !(r.get("color") instanceof String c) || !COLORS.contains(c)) continue;
                Map<String, Object> rule = new LinkedHashMap<>();
                rule.put("when", when);
                rule.put("value", v.doubleValue());
                rule.put("color", c);
                rules.add(rule);
            }
            if (!rules.isEmpty()) out.put("thresholds", rules);
        }
        if (m.get("decimals") instanceof Number n) out.put("decimals", (int) Math.max(0, Math.min(4, Math.round(n.doubleValue()))));
        if (m.get("compact") instanceof Boolean b) out.put("compact", b);
        if (m.get("unit") instanceof String u) out.put("unit", cut(u, 16));
        oneOf(m.get("unitSize"), Set.of("s", "m", "l"), "unitSize", out);
        if (m.get("unitColor") instanceof String uc && COLORS.contains(uc)) out.put("unitColor", uc);
        if (m.get("prefix") instanceof String p && !p.isEmpty()) out.put("prefix", cut(p, 8));
        if (Boolean.TRUE.equals(m.get("hideTitle"))) out.put("hideTitle", true);
        oneOf(m.get("align"), Set.of("start", "center", "end"), "align", out);
        oneOf(m.get("size"), Set.of("s", "m", "l"), "size", out);
        oneOf(m.get("card"), Set.of("accent", "plain"), "card", out);
        if (m.get("target") instanceof Number t && Double.isFinite(t.doubleValue())) out.put("target", t.doubleValue());
        if (Boolean.TRUE.equals(m.get("invertTrend"))) out.put("invertTrend", true);
        if (m.get("count") instanceof Number n) out.put("count", (int) Math.max(1, Math.min(20, Math.round(n.doubleValue()))));
        oneOf(m.get("fit"), Set.of("contain", "cover", "fill", "none"), "fit", out);
        oneOf(m.get("position"), Set.of("center", "top", "bottom"), "position", out);
        oneOf(m.get("padding"), Set.of("none", "s", "m"), "padding", out);
        oneOf(m.get("smoothing"), Set.of("auto", "pixelated", "smooth"), "smoothing", out);
        String markerIcon = icon(m.get("markerIcon"));
        if (markerIcon != null) out.put("markerIcon", markerIcon);
        if (Boolean.TRUE.equals(m.get("hideValue"))) out.put("hideValue", true);
        if (m.get("biome") instanceof String b && BIOME.matcher(b.trim()).matches()) out.put("biome", b.trim());
        oneOf(m.get("speed"), Set.of("slow", "normal", "fast"), "speed", out);
        Map<String, Object> background = tileBackground(m.get("background"));
        if (background != null) out.put("background", background);
        return out.isEmpty() ? null : out;
    }

    /**
     * A tile's own background: a named {@code color}, a second {@code gradient} colour and/or a picture (an
     * {@code https://} {@code url} with dim, blur and fit as in {@link #backgroundImage}). Null when nothing valid is left.
     */
    static Map<String, Object> tileBackground(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        if (m.get("color") instanceof String c && COLORS.contains(c)) out.put("color", c);
        if (m.get("gradient") instanceof String g && COLORS.contains(g)) out.put("gradient", g);
        BackgroundImage image = backgroundImage(m);
        if (image != null) out.putAll(image.toJson());
        return out.isEmpty() ? null : out;
    }

    /** A background picture with an {@code https://} URL, dim 0-0.9, blur 0-20 and a known fit; null otherwise. */
    public static BackgroundImage backgroundImage(Object raw) {
        if (!(raw instanceof Map<?, ?> m) || m.get("url") == null) return null;
        String url = m.get("url").toString().trim();
        if (url.length() > 2048 || !HTTPS.matcher(url).matches()) return null;
        double dim = m.get("dim") instanceof Number d && Double.isFinite(d.doubleValue()) ? Math.max(0, Math.min(0.9, d.doubleValue())) : 0.4;
        double blur = m.get("blur") instanceof Number b && Double.isFinite(b.doubleValue()) ? Math.max(0, Math.min(20, b.doubleValue())) : 0;
        String fit = m.get("fit") instanceof String f && Set.of("cover", "contain", "tile").contains(f) ? f : "cover";
        return new BackgroundImage(url, dim, blur, fit);
    }

    private static void oneOf(Object value, Set<String> allowed, String key, Map<String, Object> out) {
        if (value instanceof String s && allowed.contains(s)) out.put(key, s);
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }
}
