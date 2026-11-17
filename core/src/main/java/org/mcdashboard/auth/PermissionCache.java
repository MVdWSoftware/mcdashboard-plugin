/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.auth;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The permission nodes each player held at their last snapshot. A server only answers for online players on the main
 * thread, while the API serves offline players from its own threads; an offline player keeps what they had when they left.
 */
public class PermissionCache {
    private final Map<String, Set<String>> granted = new ConcurrentHashMap<>();

    /** Records the nodes the player holds. */
    public void update(UUID player, Set<String> nodes) {
        granted.put(player.toString(), Set.copyOf(nodes));
    }

    /**
     * Whether the paired player holds the node. A null node means "no permission needed" and is always true; a
     * node without a known player (anonymous caller, or never seen) is false.
     */
    public boolean has(String playerUuid, String node) {
        if (node == null || node.isBlank()) return true;
        if (playerUuid == null) return false;
        Set<String> nodes = granted.get(playerUuid);
        return nodes != null && nodes.contains(node);
    }
}
