/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.nukkit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mcdashboard.placeholder.PlaceholderDefinition;

class NukkitPermissionsTest {
    @Test
    void collectsThePermissionsOfThePlaceholdersAndTheWatchedNodes() {
        NukkitPermissions permissions = new NukkitPermissions(null, null);
        var defs = PlaceholderDefinition.parseAll(List.of(
                Map.of("id", "with", "value", "%with%", "permission", "mcd.with"),
                Map.of("id", "without", "value", "%without%")), m -> fail(m));
        permissions.addPlaceholders(defs);
        permissions.addPermissions(List.of("mcd.watched"));
        assertNotNull(permissions.cache(), "the API reads this cache, which the snapshots fill");
    }
}
