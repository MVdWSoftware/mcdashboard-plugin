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
    /** Protocol version reported by {@code /v1/info}; bump on breaking API changes. */
    public static final int PROTOCOL = 1;

    /** Every language a dashboard on this server uses, plus the default, sorted. */
    public List<String> languages() {
        TreeSet<String> out = new TreeSet<>();
        out.add(Languages.normaliseOrDefault(defaultLanguage));
        for (Dashboard d : dashboards.all()) out.addAll(d.languages());
        return List.copyOf(out);
    }
}
