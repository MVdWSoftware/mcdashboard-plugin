/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.mcdashboard.stats.PluginStats;
import org.mcdashboard.stats.StatsPlaceholders;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * The plugin's own PlaceholderAPI expansion: {@code %mcdashboard_<param>%}, see docs/placeholders-api.md. PlaceholderAPI calls it on
 * whatever thread asks (often the main thread), so it only reads {@link PluginStats}: in-memory counters and snapshots, no I/O.
 * The values are counts and settings, never about the player who is asking, so the player argument is ignored.
 */
public final class McDashboardExpansion extends PlaceholderExpansion {
    private final Supplier<PluginStats> stats;
    private final String author;

    /** {@code stats} is read on every request, so it may start returning a live object after the expansion registered. */
    public McDashboardExpansion(Supplier<PluginStats> stats, String author) {
        this.stats = stats;
        this.author = author;
    }

    @Override
    public @NotNull String getIdentifier() {
        return StatsPlaceholders.IDENTIFIER;
    }

    @Override
    public @NotNull String getAuthor() {
        return author;
    }

    @Override
    public @NotNull String getVersion() {
        PluginStats s = stats.get();
        return s == null || s.version().isEmpty() ? "1.0.0" : s.version();
    }

    /** Stays registered over {@code /papi reload}; the plugin unregisters it when it is disabled. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        return StatsPlaceholders.resolve(stats.get(), params);
    }
}
