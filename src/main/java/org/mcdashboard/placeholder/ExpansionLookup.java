package org.mcdashboard.placeholder;

import java.util.List;

/** Lists the placeholders an installed PlaceholderAPI expansion declares; the Bukkit implementation is {@link PapiExpansionLookup}. */
@FunctionalInterface
public interface ExpansionLookup {
    /** Declared placeholders such as {@code %server_online%}, or null when the expansion is not installed. */
    List<String> placeholders(String identifier);

    /**
     * This lookup decides whether the expansion exists; when it is installed but declares nothing, the {@code fallback}
     * (for example PlaceholderAPI's cloud data) supplies the list. A fallback that has nothing yields an empty list.
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
