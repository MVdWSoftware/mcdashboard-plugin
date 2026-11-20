/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.auth.OtpService;
import org.mcdashboard.auth.PermissionCache;
import org.mcdashboard.auth.RateLimiter;
import org.mcdashboard.auth.TokenService;
import org.mcdashboard.dashboard.Dashboard;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.placeholder.HistoryService;
import org.mcdashboard.placeholder.PlaceholderService;
import org.mcdashboard.web.ApiContext;
import org.mcdashboard.web.ServerStatus;

import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Shorter ways to build an {@link ApiContext} for tests, with the defaults the plugin would use. */
public final class TestContexts {
    private TestContexts() {
    }

    /** Fixed dashboards, developer mode off, no endpoints, polling on. */
    public static ApiContext context(String serverName, String pluginVersion, Supplier<ServerStatus> status,
                                     boolean requireAuthentication, int maxDevices, long accessTokenSeconds,
                                     long refreshTokenSeconds, int websocketPort, PlaceholderService placeholders,
                                     TokenService tokens, DeviceStore devices, OtpService otps, RateLimiter authLimiter,
                                     List<Dashboard> dashboards, Set<String> trustedProxies, HistoryService history,
                                     String licenseId, Consumer<DeviceStore.Device> onLinked, PermissionCache permissions,
                                     String defaultLanguage) {
        return new ApiContext(serverName, pluginVersion, status, requireAuthentication, maxDevices, accessTokenSeconds,
                refreshTokenSeconds, websocketPort, placeholders, tokens, devices, otps, authLimiter,
                DashboardStore.inMemory(dashboards), trustedProxies, history, licenseId, onLinked, permissions,
                defaultLanguage, () -> false, () -> {
                }, List.of(), false, true, null);
    }

    /** No endpoints, polling on. */
    public static ApiContext context(String serverName, String pluginVersion, Supplier<ServerStatus> status,
                                     boolean requireAuthentication, int maxDevices, long accessTokenSeconds,
                                     long refreshTokenSeconds, int websocketPort, PlaceholderService placeholders,
                                     TokenService tokens, DeviceStore devices, OtpService otps, RateLimiter authLimiter,
                                     DashboardStore dashboards, Set<String> trustedProxies, HistoryService history,
                                     String licenseId, Consumer<DeviceStore.Device> onLinked, PermissionCache permissions,
                                     String defaultLanguage, BooleanSupplier developerMode, Runnable onDashboardsChanged) {
        return new ApiContext(serverName, pluginVersion, status, requireAuthentication, maxDevices, accessTokenSeconds,
                refreshTokenSeconds, websocketPort, placeholders, tokens, devices, otps, authLimiter, dashboards,
                trustedProxies, history, licenseId, onLinked, permissions, defaultLanguage, developerMode,
                onDashboardsChanged, List.of(), false, true, null);
    }

    /** The same context counting into {@code stats}. */
    public static ApiContext withStats(ApiContext c, org.mcdashboard.stats.PluginStats stats) {
        return new ApiContext(c.serverName(), c.pluginVersion(), c.status(), c.requireAuthentication(), c.maxDevices(),
                c.accessTokenSeconds(), c.refreshTokenSeconds(), c.websocketPort(), c.placeholders(), c.tokens(), c.devices(),
                c.otps(), c.authLimiter(), c.dashboards(), c.trustedProxies(), c.history(), c.licenseId(), c.onLinked(),
                c.permissions(), c.defaultLanguage(), c.developerMode(), c.onDashboardsChanged(), c.endpoints(),
                c.discoveryOnly(), c.polling(), stats);
    }

    /** The same context with the owner's caps ({@code limits.max-dashboards}, {@code limits.max-tiles}). */
    public static ApiContext withLimits(ApiContext c, int maxDashboards, int maxTiles) {
        return new ApiContext(c.serverName(), c.pluginVersion(), c.status(), c.requireAuthentication(), c.maxDevices(),
                c.accessTokenSeconds(), c.refreshTokenSeconds(), c.websocketPort(), c.placeholders(), c.tokens(), c.devices(),
                c.otps(), c.authLimiter(), c.dashboards(), c.trustedProxies(), c.history(), c.licenseId(), c.onLinked(),
                c.permissions(), c.defaultLanguage(), c.developerMode(), c.onDashboardsChanged(), c.endpoints(),
                c.discoveryOnly(), c.polling(), c.stats(), maxDashboards, maxTiles);
    }

    /** The same context, with unauthenticated typed player names and the lookup that turns a name into a UUID. */
    public static ApiContext withPlayerNames(ApiContext c, java.util.function.Function<String, String> playerUuid) {
        return new ApiContext(c.serverName(), c.pluginVersion(), c.status(), c.requireAuthentication(), c.maxDevices(),
                c.accessTokenSeconds(), c.refreshTokenSeconds(), c.websocketPort(), c.placeholders(), c.tokens(), c.devices(),
                c.otps(), c.authLimiter(), c.dashboards(), c.trustedProxies(), c.history(), c.licenseId(), c.onLinked(),
                c.permissions(), c.defaultLanguage(), c.developerMode(), c.onDashboardsChanged(), c.endpoints(),
                c.discoveryOnly(), c.polling(), c.stats(), c.maxDashboards(), c.maxTiles(), true, playerUuid);
    }

    /** A permission cache in which everybody holds every node. */
    public static PermissionCache allowAll() {
        return new PermissionCache() {
            @Override
            public boolean has(String playerUuid, String node) {
                return true;
            }
        };
    }
}
