package org.mcdashboard.dashboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** The languages the plugin can publish dashboards in, and HTTP language negotiation. */
public final class Languages {
    /** The languages the mobile app ships: English, Dutch, French, German and Spanish. */
    public static final List<String> SUPPORTED = List.of("en", "nl", "fr", "de", "es");

    private Languages() {
    }

    /**
     * Maps a tag such as {@code nl}, {@code de-AT} or {@code es_419} to a supported language.
     * @param tag - A language tag, possibly with a region.
     * @return The two-letter language, or {@code null} when the plugin does not support it.
     */
    public static String normalise(String tag) {
        if (tag == null) return null;
        String value = tag.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = value.indexOf('-');
        if (dash >= 0) value = value.substring(0, dash);
        return value.length() == 2 && SUPPORTED.contains(value) ? value : null;
    }

    /**
     * The supported languages in an {@code Accept-Language} header, best match first.
     * @param header - The header value, e.g. {@code fr-CA,fr;q=0.9,en;q=0.8}.
     * @return The languages; empty when the header is absent or names none the plugin supports.
     */
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

    /**
     * The language to use: the caller's best supported match, else the server's default.
     * @param header - The {@code Accept-Language} header.
     * @param defaultLanguage - The language from {@code server.default-language}.
     * @return A supported two-letter language, never {@code null}.
     */
    public static String resolve(String header, String defaultLanguage) {
        List<String> preferred = preferred(header);
        return preferred.isEmpty() ? normaliseOrDefault(defaultLanguage) : preferred.get(0);
    }

    /**
     * A supported language, falling back to English.
     * @param language - Any tag.
     * @return A supported two-letter language.
     */
    public static String normaliseOrDefault(String language) {
        String normalised = normalise(language);
        return normalised == null ? "en" : normalised;
    }
}
