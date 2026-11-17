/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.platform;

import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.placeholder.ExpansionLookup;
import org.mcdashboard.placeholder.ValueSource;
import org.mcdashboard.web.ServerStatus;

/**
 * Everything the core needs from the server it runs on: the logger, the placeholder engine, permissions, the player
 * count and the main thread. A "tick" is 1/20 second, as on every Minecraft server.
 */
public interface Platform {
    Log log();

    /**
     * A classpath folder (such as {@code nukkit/}) whose files replace the bundled defaults of the same name, for the
     * defaults that name placeholders of this platform. The empty default uses the files at the root.
     */
    default String defaultsFolder() {
        return "";
    }

    /** Whether the placeholder engine can list what it offers; without that, {@code catalog:} entries cannot be expanded. */
    default boolean supportsCatalog() {
        return true;
    }

    /** The version of the plugin, shown in {@code /v1/info} and sent with the webhooks. */
    String pluginVersion();

    /** The name the server calls itself, used when {@code server.name} is not set. */
    String serverName();

    /** Whether the placeholder engine is installed; the plugin cannot start without it. */
    boolean placeholdersAvailable();

    /** Resolves placeholders; may be called from any thread. */
    ValueSource values();

    /** Reads the placeholders an expansion declares; call it on the main thread. */
    ExpansionLookup expansions();

    /** The permission bridge for the dashboards of this server. */
    Permissions permissions(DashboardStore dashboards);

    /** Main thread: the server list data, copied for the API threads. */
    ServerStatus status();

    /** Runs the task on the main thread as soon as possible. */
    void runSync(Runnable task);

    /** Runs the task on the main thread after the delay. */
    void runSyncLater(Runnable task, long ticks);

    /** Runs the task on the main thread now and then every {@code ticks}. */
    void runSyncRepeating(Runnable task, long ticks);

    /** Tells the player in game that a device was linked, with the way to undo it. */
    void notifyLinked(DeviceStore.Device device);
}
