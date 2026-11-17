/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;

import java.util.List;

/** Reads the declared placeholders of a loaded expansion; call it on the server main thread. */
public final class PapiExpansionLookup implements ExpansionLookup {
    @Override
    public List<String> placeholders(String identifier) {
        PlaceholderExpansion e = PlaceholderAPIPlugin.getInstance().getLocalExpansionManager().getExpansion(identifier);
        if (e == null) return null;
        List<String> declared = e.getPlaceholders();
        return declared == null ? List.of() : declared;
    }
}
