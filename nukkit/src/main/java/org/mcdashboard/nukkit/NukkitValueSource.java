/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.nukkit;

import org.mcdashboard.placeholder.ValueSource;
import org.powernukkitx.Player;
import org.powernukkitx.plugin.PluginBase;
import org.powernukkitx.placeholderapi.PlaceholderAPI;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Resolves a {@code %placeholder%} template through the PowerNukkitX PlaceholderAPI on the server main thread. A
 * template it does not know comes back unchanged, which the core treats as unresolved.
 */
final class NukkitValueSource implements ValueSource {
    /** What the PlaceholderAPI writes in place of a placeholder whose handler threw. */
    private static final String HANDLER_ERROR = "§cerror";

    private final PluginBase plugin;

    NukkitValueSource(PluginBase plugin) {
        this.plugin = plugin;
    }

    @Override
    public String resolve(String template, String playerUuid) {
        CompletableFuture<String> result = new CompletableFuture<>();
        plugin.getServer().getScheduler().scheduleTask(plugin, () -> {
            try {
                Player player = null;
                if (playerUuid != null) {
                    // The player placeholders read live state, so an offline player has no value.
                    player = plugin.getServer().getPlayer(UUID.fromString(playerUuid)).orElse(null);
                    if (player == null) {
                        result.complete(null);
                        return;
                    }
                }
                String text = PlaceholderAPI.get().processPlaceholders(player, template);
                result.complete(text == null || text.contains(HANDLER_ERROR) ? null : text);
            } catch (RuntimeException e) {
                result.complete(null);
            }
        });
        try {
            return result.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
