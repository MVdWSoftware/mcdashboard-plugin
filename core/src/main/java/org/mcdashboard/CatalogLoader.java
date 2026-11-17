/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import org.mcdashboard.history.HistorySettings;
import org.mcdashboard.placeholder.ExpansionCatalog;
import org.mcdashboard.placeholder.HistoryService;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.placeholder.PlaceholderService;
import org.mcdashboard.platform.Log;
import org.mcdashboard.platform.Permissions;
import org.mcdashboard.platform.Platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds the {@code catalog:} entries of {@code placeholders.yml} behind the explicit ones. PlaceholderAPI registers
 * expansions a moment after the plugins enable, so a missing or empty expansion is retried every 5 seconds, 6 times.
 */
final class CatalogLoader {
    private static final int MAX_ATTEMPTS = 6;

    private final Platform platform;
    private final Log log;
    private final PlaceholderService placeholders;
    private final HistoryService history;
    private final HistorySettings historySettings;
    private final Permissions permissions;
    private final List<PlaceholderDefinition> explicit;
    private final List<ExpansionCatalog.Entry> catalog;

    CatalogLoader(Platform platform, PlaceholderService placeholders, HistoryService history, HistorySettings historySettings,
                  Permissions permissions, List<PlaceholderDefinition> explicit, List<ExpansionCatalog.Entry> catalog) {
        this.platform = platform;
        this.log = platform.log();
        this.placeholders = placeholders;
        this.history = history;
        this.historySettings = historySettings;
        this.permissions = permissions;
        this.explicit = explicit;
        this.catalog = catalog;
    }

    void start() {
        if (catalog.isEmpty()) return;
        if (!platform.supportsCatalog()) {
            log.warning("placeholders.yml: catalog is not supported here (the placeholder engine cannot list its placeholders); list them under placeholders instead.");
            return;
        }
        platform.runSync(() -> load(0));
    }

    /** Main thread: the placeholder engine's expansion manager is not thread safe. */
    private void load(int attempt) {
        List<String> problems = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        try {
            List<PlaceholderDefinition> built = ExpansionCatalog.build(catalog,
                    platform.expansions(), problems::add, counts);
            List<PlaceholderDefinition> all = historySettings.apply(ExpansionCatalog.merge(explicit, built));
            placeholders.setDefinitions(all);
            history.track(all);
            permissions.addPlaceholders(built);
            log.info(built.size() + " catalog placeholder(s) loaded, " + all.size() + " in total.");
        } catch (RuntimeException e) {
            log.warning("Could not load the placeholder catalog: " + e.getMessage());
        }
        boolean notYet = problems.stream().anyMatch(m -> m.contains("not installed"))
                || ExpansionCatalog.worthRetrying(catalog, counts);
        if (notYet && attempt < MAX_ATTEMPTS) {
            platform.runSyncLater(() -> load(attempt + 1), 100L);
            return;
        }
        problems.forEach(log::warning);
        for (String line : ExpansionCatalog.report(catalog, counts)) {
            if (line.startsWith("WARN ")) log.warning(line.substring(5));
            else log.info(line);
        }
    }
}
