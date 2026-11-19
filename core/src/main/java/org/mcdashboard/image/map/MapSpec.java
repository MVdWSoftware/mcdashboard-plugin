/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.image.map;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The {@code map:} block of a {@code source: map:<provider>} IMAGE placeholder: which web map to read, where to centre
 * and how large the picture is. {@code x} and {@code z} are a number or a PlaceholderAPI template (expanded when read);
 * {@code url} is the map's web root, fixed (no placeholders) and without a trailing slash.
 */
public record MapSpec(TileProvider tiles, String url, String x, String z, int zoom, int size) {
    public static final List<String> PROVIDERS = List.of("dynmap", "squaremap", "pl3xmap", "bluemap");
    public static final int MIN_SIZE = 64;
    public static final int MAX_SIZE = 512;
    /** Largest block coordinate accepted (the world border's limit). */
    static final double MAX_COORD = 30_000_000;

    private static final Set<String> KEYS = Set.of("url", "world", "map", "x", "y", "z", "zoom", "size", "max-zoom",
            "format", "perspective");
    private static final Pattern BASE_URL = Pattern.compile("https?://[^/%\\s?#@\\\\]+(/[^%\\s?#\\\\]*)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_.:+-]{1,64}");

    /** The provider id after {@code map:}; throws when it is not one of {@link #PROVIDERS}. */
    public static String provider(String id) {
        String p = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(p)) {
            throw new IllegalArgumentException("unknown map provider '" + id + "' (use map:" + String.join(", map:", PROVIDERS) + ")");
        }
        return p;
    }

    /** Parses and validates the block; the message of the {@link IllegalArgumentException} says what is wrong. */
    public static MapSpec parse(String providerId, Object block) {
        String provider = provider(providerId);
        if (!(block instanceof Map<?, ?> m)) throw new IllegalArgumentException("map:" + provider + " needs a map: block");
        for (Object k : m.keySet()) {
            if (!KEYS.contains(String.valueOf(k))) throw new IllegalArgumentException("unknown key in map: '" + k + "'");
        }
        String url = str(m.get("url"));
        if (url == null || !BASE_URL.matcher(url.trim()).matches()) {
            throw new IllegalArgumentException("map url must be http(s)://host[:port][/path] with no placeholders, query or user name");
        }
        url = url.trim().replaceAll("/+$", "");
        String x = coordinate(m, "x");
        String z = coordinate(m, "z");
        int size = integer(m, "size", 256, MIN_SIZE, MAX_SIZE);
        String world = segment(m, "world");
        String map = segment(m, "map");
        TileProvider tiles = switch (provider) {
            case "squaremap" -> new SquaremapProvider(required(world, "world", "the folder under tiles/, e.g. minecraft_overworld"),
                    integer(m, "max-zoom", SquaremapProvider.DEFAULT_MAX_ZOOM, 0, 8));
            case "pl3xmap" -> new Pl3xMapProvider(required(world, "world", "the folder under tiles/"),
                    required(map, "map", "the renderer, e.g. vintage_story or basic"),
                    format(m, Set.of("png", "jpg", "jpeg", "gif")), integer(m, "max-zoom", Pl3xMapProvider.DEFAULT_MAX_ZOOM, 0, 8));
            case "bluemap" -> new BlueMapProvider(required(map != null ? map : world, "map", "the map id, e.g. world"),
                    integer(m, "max-zoom", BlueMapProvider.DEFAULT_MAX_ZOOM, 0, 4));
            default -> new DynmapProvider(required(world, "world", "the world name"),
                    required(map, "map", "the map's tile prefix, e.g. flat, t (surface) or ct (cave)"),
                    perspective(m), integer(m, "y", 64, -2048, 4096), format(m, Set.of("png", "jpg")),
                    integer(m, "max-zoom", DynmapProvider.DEFAULT_MAX_ZOOM, 0, 10));
        };
        int zoom = integer(m, "zoom", 0, 0, tiles.maxZoom());
        return new MapSpec(tiles, url, x, z, zoom, size);
    }

    /** The provider id. */
    public String provider() {
        return tiles.id();
    }

    /** Whether {@code x} or {@code z} is expanded per read (and per player for PLAYER access). */
    public boolean dynamic() {
        return x.indexOf('%') >= 0 || z.indexOf('%') >= 0;
    }

    /** A resolved coordinate: colour codes, spaces and thousands separators dropped; null when it is not a usable number. */
    static Double number(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[§&][0-9a-fk-orA-FK-ORxX]", "").replace(",", "").replace("_", "").trim();
        try {
            double d = Double.parseDouble(t);
            return Double.isFinite(d) && Math.abs(d) <= MAX_COORD ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String coordinate(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) throw new IllegalArgumentException("map " + key + " is required (a block coordinate or a %placeholder%)");
        if (v instanceof Number n) {
            if (number(n.toString()) == null) throw new IllegalArgumentException("map " + key + " is out of range");
            return n.toString();
        }
        String s = v.toString().trim();
        if (s.indexOf('%') < 0 && number(s) == null) {
            throw new IllegalArgumentException("map " + key + " must be a number or contain a %placeholder%: " + s);
        }
        if (s.length() > 256) throw new IllegalArgumentException("map " + key + " is too long");
        return s;
    }

    private static int integer(Map<?, ?> m, String key, int fallback, int min, int max) {
        Object v = m.get(key);
        if (v == null) return fallback;
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.doubleValue() < min || n.doubleValue() > max) {
            throw new IllegalArgumentException("map " + key + " must be a whole number from " + min + " to " + max + ": " + v);
        }
        return n.intValue();
    }

    private static String segment(Map<?, ?> m, String key) {
        String s = str(m.get(key));
        if (s == null) return null;
        s = s.trim();
        if (!SEGMENT.matcher(s).matches() || s.matches("\\.+")) {
            throw new IllegalArgumentException("map " + key + " may only contain letters, digits and _ . : + - (max 64): " + s);
        }
        return s;
    }

    private static String required(String value, String key, String hint) {
        if (value == null) throw new IllegalArgumentException("map " + key + " is required: " + hint);
        return value;
    }

    private static String format(Map<?, ?> m, Set<String> allowed) {
        String f = str(m.get("format"));
        if (f == null) return "png";
        f = f.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(f)) throw new IllegalArgumentException("map format must be one of " + String.join(", ", allowed.stream().sorted().toList()));
        return f;
    }

    private static String perspective(Map<?, ?> m) {
        String p = str(m.get("perspective"));
        p = p == null ? DynmapProvider.DEFAULT_PERSPECTIVE : p.trim();
        DynmapProvider.worldToMap(p); // validates
        return p;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
