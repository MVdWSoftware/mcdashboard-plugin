/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.dashboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** The languages the plugin can publish dashboards in, and HTTP language negotiation. */
public final class Languages {
    /** The languages the app ships. */
    public static final List<String> SUPPORTED = List.of("en", "nl", "fr", "de", "es");

    private Languages() {
    }

    /** Maps a tag such as {@code nl}, {@code de-AT} or {@code es_419} to a supported language, or null. */
    public static String normalise(String tag) {
        if (tag == null) return null;
        String value = tag.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = value.indexOf('-');
        if (dash >= 0) value = value.substring(0, dash);
        return value.length() == 2 && SUPPORTED.contains(value) ? value : null;
    }

    /** The supported languages in an {@code Accept-Language} header such as {@code fr-CA,fr;q=0.9,en;q=0.8}, best first. */
    public static List<String> preferred(String header) {
        if (header == null || header.isBlank()) return List.of();
        record Candidate(String language, double quality, int order) {
        }
        List<Candidate> candidates = new ArrayList<>();
        int order = 0;
        for (String part : header.split(",")) {
            String[] bits = part.split(";", 2);
            String language = normalise(bits[0]);
            if (language == null) continue;
            double quality = 1;
            if (bits.length > 1) {
                String q = bits[1].trim();
                if (q.startsWith("q=")) {
                    try {
                        quality = Double.parseDouble(q.substring(2));
                    } catch (NumberFormatException ignored) {
                        quality = 0; // a malformed weight makes the entry unusable
                    }
                }
            }
            if (quality > 0) candidates.add(new Candidate(language, quality, order++));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::quality).reversed()
                .thenComparingInt(Candidate::order));
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Candidate c : candidates) out.add(c.language());
        return List.copyOf(out);
    }

    /** The caller's best supported language, else the server's default; never null. */
    public static String resolve(String header, String defaultLanguage) {
        List<String> preferred = preferred(header);
        return preferred.isEmpty() ? normaliseOrDefault(defaultLanguage) : preferred.get(0);
    }

    /** A supported language, falling back to English. */
    public static String normaliseOrDefault(String language) {
        String normalised = normalise(language);
        return normalised == null ? "en" : normalised;
    }
}
