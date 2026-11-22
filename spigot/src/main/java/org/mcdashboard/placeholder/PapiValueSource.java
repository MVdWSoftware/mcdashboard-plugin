/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Resolves placeholders on the server main thread through PlaceholderAPI. */
public final class PapiValueSource implements ValueSource {
    private final Plugin plugin;

    /**
     * Creates a new value source that resolves through PlaceholderAPI.
     *
     * @param plugin The Spigot plugin instance used for scheduling.
     */
    public PapiValueSource(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String resolve(String template, String playerUuid) {
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                OfflinePlayer p = playerUuid == null ? null : Bukkit.getOfflinePlayer(UUID.fromString(playerUuid));
                return PlaceholderAPI.setPlaceholders(p, template);
            }).get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
