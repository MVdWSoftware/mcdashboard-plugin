/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.platform;

import org.mcdashboard.auth.PermissionCache;
import org.mcdashboard.placeholder.PlaceholderDefinition;

import java.util.Collection;

/**
 * Registers the dashboard permissions and fills the {@link PermissionCache} the API reads. Servers only answer
 * permission questions on their main thread, so an implementation takes its snapshots there.
 */
public interface Permissions {
    /** The snapshots the API threads read. */
    PermissionCache cache();

    /** Main thread: starts watching the nodes of these placeholders and of every dashboard. */
    void start(Collection<PlaceholderDefinition> placeholders);

    /** Main thread: also watches the nodes of these (catalog) placeholders. */
    void addPlaceholders(Collection<PlaceholderDefinition> placeholders);

    /** A dashboard was written by the app: register its permission and refresh the online players' snapshots. */
    void dashboardsChanged();
}
