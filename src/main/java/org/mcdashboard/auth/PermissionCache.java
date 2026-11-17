package org.mcdashboard.auth;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which of the configured permission nodes each player holds. Bukkit permissions can only be asked on the main
 * thread and for online players, while the API runs on its own threads and serves players who are offline. So the
 * plugin takes snapshots on the main thread (on join, on quit and every few seconds for online players) and the API
 * reads the last snapshot. An offline player keeps the permissions they had when they last left; a permission that
 * changes while a player is online reaches the app within one refresh interval.
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

    /** For tests: nobody is restricted. */
    public static PermissionCache allowAll() {
        return new PermissionCache() {
            @Override
            public boolean has(String playerUuid, String node) {
                return true;
            }
        };
    }
}
