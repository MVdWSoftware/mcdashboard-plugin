/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.auth.OtpService;
import org.mcdashboard.auth.PermissionCache;
import org.mcdashboard.auth.RateLimiter;
import org.mcdashboard.auth.TokenService;
import org.mcdashboard.dashboard.Dashboard;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.dashboard.Languages;
import org.mcdashboard.placeholder.HistoryService;
import org.mcdashboard.placeholder.PlaceholderService;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Everything the REST API and the live feed share. */
public record ApiContext(String serverName, String pluginVersion, Supplier<ServerStatus> status,
                         boolean requireAuthentication, int maxDevices,
                         long accessTokenSeconds, long refreshTokenSeconds, int websocketPort,
                         PlaceholderService placeholders, TokenService tokens, DeviceStore devices,
                         OtpService otps, RateLimiter authLimiter, DashboardStore dashboards,
                         Set<String> trustedProxies, HistoryService history, String licenseId,
                         Consumer<DeviceStore.Device> onLinked, PermissionCache permissions, String defaultLanguage,
                         BooleanSupplier developerMode, Runnable onDashboardsChanged,
                         List<String> endpoints, boolean discoveryOnly, boolean polling) {
    /**
     * Major version of the API reported by {@code /v1/info}. Bump it only for a change that would break an older app
     * (it then asks its player to update); additions are announced in {@link #FEATURES} instead and must keep working
     * for apps and plugins that do not know them.
     */
    public static final int PROTOCOL = 1;

    /**
     * Optional capabilities every plugin of this version has, reported as {@code features} in {@code /v1/info}. The app
     * gates what needs the plugin on this list, so a newer app still works with an older plugin that lacks an entry.
     */
    public static final List<String> FEATURES = List.of("presets", "history-points", "upload");

    /** Every language a dashboard on this server uses, plus the default, sorted. */
    public List<String> languages() {
        TreeSet<String> out = new TreeSet<>();
        out.add(Languages.normaliseOrDefault(defaultLanguage));
        for (Dashboard d : dashboards.all()) out.addAll(d.languages());
        return List.copyOf(out);
    }
}
