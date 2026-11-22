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
    /** The snapshots the API threads read.
     *
     * @return The permission cache shared with the API.
     */
    PermissionCache cache();

    /** Main thread: starts watching the nodes of these placeholders and of every dashboard.
     *
     * @param placeholders The placeholder definitions whose permission nodes to watch.
     */
    void start(Collection<PlaceholderDefinition> placeholders);

    /** Main thread: also watches the nodes of these (catalog) placeholders.
     *
     * @param placeholders Additional placeholder definitions whose nodes to watch.
     */
    void addPlaceholders(Collection<PlaceholderDefinition> placeholders);

    /** Main thread: also watches these nodes, such as the ones in {@code security.min-interval-permissions}.
     *
     * @param nodes The permission nodes to watch.
     */
    void addPermissions(Collection<String> nodes);

    /** A dashboard was written by the app: register its permission and refresh the online players' snapshots. */
    void dashboardsChanged();
}
