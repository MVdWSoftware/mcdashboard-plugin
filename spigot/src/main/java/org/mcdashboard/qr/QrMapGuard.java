/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.qr;

import org.bukkit.Bukkit;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Keeps a QR map inside its owner's inventory and destroys it when it is dropped, stored, traded, duplicated,
 * placed in an item frame or lost on death. The map also self-destructs when its code expires (see {@link QrMap}).
 */
public final class QrMapGuard implements Listener {
    public QrMapGuard(Plugin plugin) {
        // The code is short-lived even if the item is never touched (for example while the player is AFK).
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) QrMap.destroyExpired(player);
        }, 20L, 40L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!QrMap.isQrMap(e.getItemDrop().getItemStack())) return;
        e.setCancelled(true);
        e.getItemDrop().remove();
        QrMap.destroyFor(e.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!QrMap.isQrMap(e.getItem().getItemStack())) return;
        e.setCancelled(true);
        e.getItem().remove();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (QrMap.isQrMap(e.getEntity().getItemStack())) e.getEntity().remove();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (!QrMap.isQrMap(e.getCurrentItem()) && !QrMap.isQrMap(e.getCursor())) return;
        Inventory clicked = e.getClickedInventory();
        if (clicked == null) { // dropping it outside the window
            e.setCancelled(true);
            QrMap.destroyFor(player, false);
            return;
        }
        boolean topIsPlayer = e.getView().getTopInventory().getType() == InventoryType.CRAFTING
                || e.getView().getTopInventory().getType() == InventoryType.PLAYER;
        boolean clickedContainer = clicked.getType() != InventoryType.PLAYER && clicked.getType() != InventoryType.CRAFTING;
        boolean shiftIntoContainer = e.isShiftClick() && !topIsPlayer;
        boolean crafting = e.getSlotType() == InventoryType.SlotType.CRAFTING
                || e.getSlotType() == InventoryType.SlotType.RESULT;
        if (clickedContainer || shiftIntoContainer || crafting) {
            e.setCancelled(true);
            QrMap.destroyFor(player, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player) || !QrMap.isQrMap(e.getOldCursor())) return;
        InventoryType top = e.getView().getTopInventory().getType();
        if (top != InventoryType.CRAFTING && top != InventoryType.PLAYER) {
            e.setCancelled(true);
            QrMap.destroyFor(player, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        if (!QrMap.isQrMap(e.getItem())) return;
        e.setCancelled(true);
        // A hopper or another plugin moved it: remove every tagged map from the source.
        for (int i = 0; i < e.getSource().getSize(); i++) {
            if (QrMap.isQrMap(e.getSource().getItem(i))) e.getSource().setItem(i, null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemFrame(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof ItemFrame)) return;
        ItemStack hand = e.getPlayer().getInventory().getItem(e.getHand());
        if (!QrMap.isQrMap(hand)) return;
        e.setCancelled(true);
        QrMap.destroyFor(e.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(PrepareItemCraftEvent e) {
        if (QrMap.isQrMap(e.getInventory().getResult())) {
            e.getInventory().setResult(null);
            return;
        }
        for (ItemStack item : e.getInventory().getMatrix()) {
            if (QrMap.isQrMap(item)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(QrMap::isQrMap);
        QrMap.destroyFor(e.getEntity(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        QrMap.destroyFor(e.getPlayer(), true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // A leftover from a crash or a server stop must not survive; the player can generate a new map.
        QrMap.destroyFor(e.getPlayer(), true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player player && QrMap.isQrMap(player.getItemOnCursor())) {
            QrMap.destroyFor(player, true);
        }
    }
}
