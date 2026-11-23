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
    private final Plugin plugin;

    /**
     * Creates a new guard and starts the periodic expiry checker.
     *
     * @param plugin The main plugin instance for scheduling.
     */
    public QrMapGuard(Plugin plugin) {
        this.plugin = plugin;
        // The code is short-lived even if the item is never touched (for example while the player is AFK).
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) QrMap.destroyExpired(player);
        }, 20L, 40L);
    }

    /**
     * Cancels dropping a QR map and destroys it instead. The server takes the stack out of the inventory before firing
     * this event and only puts it back when the event is cancelled, so the map is cleared on the next tick.
     *
     * @param e The drop event.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!QrMap.isQrMap(e.getItemDrop().getItemStack())) return;
        e.setCancelled(true);
        e.getItemDrop().remove();
        Player player = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            // The event is cancelled, so the stack has been restored by now; if the server already discarded it there is
            // nothing left to clear, but the player still gets told that the map is gone.
            if (!QrMap.destroyFor(player, false)) player.sendMessage("§7The MCDashboard QR map was destroyed.");
        });
    }

    /**
     * Cancels picking up a QR map and removes the dropped item.
     *
     * @param e The pickup event.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (!QrMap.isQrMap(e.getItem().getItemStack())) return;
        e.setCancelled(true);
        e.getItem().remove();
    }

    /**
     * Removes a QR map that spawned as a dropped item.
     *
     * @param e The item spawn event.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (QrMap.isQrMap(e.getEntity().getItemStack())) e.getEntity().remove();
    }

    /**
     * Cancels moving a QR map into a container or crafting grid and destroys it.
     *
     * @param e The inventory click event.
     */
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

    /**
     * Cancels dragging a QR map into a container and destroys it.
     *
     * @param e The inventory drag event.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player) || !QrMap.isQrMap(e.getOldCursor())) return;
        InventoryType top = e.getView().getTopInventory().getType();
        if (top != InventoryType.CRAFTING && top != InventoryType.PLAYER) {
            e.setCancelled(true);
            QrMap.destroyFor(player, false);
        }
    }

    /**
     * Cancels hopper or plugin moves of a QR map and clears it from the source.
     *
     * @param e The item move event.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        if (!QrMap.isQrMap(e.getItem())) return;
        e.setCancelled(true);
        // A hopper or another plugin moved it: remove every tagged map from the source.
        for (int i = 0; i < e.getSource().getSize(); i++) {
            if (QrMap.isQrMap(e.getSource().getItem(i))) e.getSource().setItem(i, null);
        }
    }

    /**
     * Cancels placing a QR map in an item frame and destroys it.
     *
     * @param e The entity interact event.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemFrame(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof ItemFrame)) return;
        ItemStack hand = e.getPlayer().getInventory().getItem(e.getHand());
        if (!QrMap.isQrMap(hand)) return;
        e.setCancelled(true);
        QrMap.destroyFor(e.getPlayer(), false);
    }

    /**
     * Clears a crafting result that would involve a QR map.
     *
     * @param e The prepare craft event.
     */
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

    /**
     * Removes QR maps from a player's drops and destroys any that remain.
     *
     * @param e The player death event.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(QrMap::isQrMap);
        QrMap.destroyFor(e.getEntity(), true);
    }

    /**
     * Destroys any QR maps when a player quits.
     *
     * @param e The quit event.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        QrMap.destroyFor(e.getPlayer(), true);
    }

    /**
     * Destroys any leftover QR maps when a player joins.
     *
     * @param e The join event.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // A leftover from a crash or a server stop must not survive; the player can generate a new map.
        QrMap.destroyFor(e.getPlayer(), true);
    }

    /**
     * Destroys a QR map left on the player's cursor when an inventory closes.
     *
     * @param e The inventory close event.
     */
    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player player && QrMap.isQrMap(player.getItemOnCursor())) {
            QrMap.destroyFor(player, true);
        }
    }
}
