/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.nukkit;

import org.mcdashboard.stats.PluginStats;
import org.mcdashboard.stats.StatsPlaceholders;
import org.powernukkitx.placeholderapi.placeholder.Placeholder;
import org.powernukkitx.placeholderapi.placeholder.StaticPlaceholder;

import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Registers the {@code %mcdashboard_*%} placeholders ({@link StatsPlaceholders}) with the PowerNukkitX PlaceholderAPI, whose
 * placeholders are plain lower-case names with a handler, so each parameter becomes {@code mcdashboard_<param>}. The handlers read
 * memory only (see {@link PluginStats}).
 */
final class NukkitStatsPlaceholders {
    private NukkitStatsPlaceholders() {
    }

    /**
     * @param registry where a name and its handler go ({@code PlaceholderAPI::register})
     * @param stats    the plugin's counters; may be null while the core is not running (the value is then empty)
     */
    static void register(BiConsumer<String, Placeholder> registry, Supplier<PluginStats> stats) {
        for (String param : StatsPlaceholders.PARAMS) {
            registry.accept(StatsPlaceholders.IDENTIFIER + "_" + param, new StaticPlaceholder(() -> {
                String value = StatsPlaceholders.resolve(stats.get(), param);
                return value == null ? "" : value;
            }));
        }
    }
}
