/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.dashboard;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A text a server may translate: plain ({@code name: Server}) or per language
 * ({@code name: { en: Server, nl: Server }}). Resolution uses the caller's language first, then the
 * server default, then the text itself or the first available translation.
 *
 * @param translations Per-language translations; empty when the text is plain.
 * @param plain        The plain-text value when no per-language variants exist.
 */
public record LocalizedText(Map<String, String> translations, String plain) {
    /** A text that is the same in every language.
     *
     * @param text The plain-text value; null returns null.
     * @return A new LocalizedText instance, or null when text is blank.
     */
    public static LocalizedText of(String text) {
        return text == null || text.isBlank() ? null : new LocalizedText(Map.of(), text.trim());
    }

    /** A YAML string, or a map of language codes to strings; null when there is nothing to show.
     *
     * @param raw The parsed YAML value: a plain string or a locale-to-text map.
     * @return A LocalizedText for the given input, or null when there is nothing to display.
     */
    public static LocalizedText parse(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Map<?, ?> map) {
            Map<String, String> translations = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String language = Languages.normalise(String.valueOf(entry.getKey()));
                if (language == null || entry.getValue() == null) continue;
                String text = String.valueOf(entry.getValue()).trim();
                if (!text.isEmpty()) translations.put(language, text);
            }
            return translations.isEmpty() ? null : new LocalizedText(Collections.unmodifiableMap(translations), null);
        }
        return of(String.valueOf(raw));
    }

    /** Whether the server provided this text in more than one language.
     *
     * @return True when per-language translations exist.
     */
    public boolean isLocalized() {
        return !translations.isEmpty();
    }

    /** The languages this text is available in.
     *
     * @return The set of language codes for which a translation exists.
     */
    public Set<String> languages() {
        return translations.keySet();
    }

    /** The text for a caller's {@code preferred} languages (best first); empty when there is none.
     *
     * @param preferred       The caller's preferred languages in priority order.
     * @param defaultLanguage The server's fallback language when nothing matches.
     * @return The best matching text, or an empty string as the last resort.
     */
    public String resolve(List<String> preferred, String defaultLanguage) {
        for (String language : preferred) {
            String text = translations.get(language);
            if (text != null) return text;
        }
        if (plain != null) return plain;
        String fallback = translations.get(Languages.normaliseOrDefault(defaultLanguage));
        if (fallback != null) return fallback;
        for (String language : Languages.SUPPORTED) {
            String text = translations.get(language);
            if (text != null) return text;
        }
        return "";
    }
}
