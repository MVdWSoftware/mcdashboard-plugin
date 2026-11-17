/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.cloud.CloudExpansion;

import java.util.List;
import java.util.Map;

/** The placeholders listed in PlaceholderAPI's cloud data; yields nothing while that data is not loaded or the server is offline. */
public final class PapiCloudLookup implements ExpansionLookup {
    @Override
    public List<String> placeholders(String identifier) {
        try {
            Map<String, CloudExpansion> all = PlaceholderAPIPlugin.getInstance().getCloudExpansionManager().getCloudExpansions();
            if (all == null) return List.of();
            for (Map.Entry<String, CloudExpansion> e : all.entrySet()) {
                CloudExpansion c = e.getValue();
                if (e.getKey().equalsIgnoreCase(identifier) || c != null && identifier.equalsIgnoreCase(c.getName())) {
                    List<String> p = c == null ? null : c.getPlaceholders();
                    return p == null ? List.of() : p;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // cloud data unavailable
        }
        return List.of();
    }
}
