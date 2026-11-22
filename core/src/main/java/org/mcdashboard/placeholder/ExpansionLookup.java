/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

import java.util.List;

/** Lists the placeholders an installed placeholder expansion declares. */
@FunctionalInterface
public interface ExpansionLookup {
    /** Declared placeholders such as {@code %server_online%}, or null when the expansion is not installed.
     *
     * @param identifier The expansion identifier to look up.
     * @return The declared placeholders, or null when the expansion is not installed.
     */
    List<String> placeholders(String identifier);

    /**
     * This lookup decides whether the expansion exists; when it is installed but declares nothing, the {@code fallback}
     * (for example PlaceholderAPI's cloud data) supplies the list. A fallback that has nothing yields an empty list.
     *
     * @param fallback The lookup to consult when this one finds an installed expansion with no declarations.
     * @return A lookup that falls back to {@code fallback} for empty results.
     */
    default ExpansionLookup orElse(ExpansionLookup fallback) {
        return id -> {
            List<String> own = placeholders(id);
            if (own == null || !own.isEmpty()) return own;
            List<String> other = fallback.placeholders(id);
            return other == null ? own : other;
        };
    }
}
