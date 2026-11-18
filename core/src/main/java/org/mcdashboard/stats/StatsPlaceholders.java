/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.stats;

import java.util.List;
import java.util.Locale;

/**
 * Answers the {@code %mcdashboard_<param>%} placeholders from a {@link PluginStats}. Platform independent, so every platform
 * (PlaceholderAPI on Spigot, the built-in source of a proxy) gives the same names and values. Reads memory only.
 */
public final class StatsPlaceholders {
    /** The identifier of the PlaceholderAPI expansion. */
    public static final String IDENTIFIER = "mcdashboard";

    /** Every parameter that is answered. */
    public static final List<String> PARAMS = List.of("devices", "devices_online", "devices_server", "devices_app", "devices_free", "licensed", "license_state", "api_requests",
            "api_requests_per_minute", "rate_limited", "live_clients", "history_samples", "history_storage", "placeholders",
            "dashboards", "uptime", "uptime_formatted", "version");

    private StatsPlaceholders() {
    }

    /** The value for the parameter (the part after {@code mcdashboard_}), or null when it is not one of {@link #PARAMS}. */
    public static String resolve(PluginStats stats, String param) {
        if (stats == null || param == null) return null;
        return switch (param.toLowerCase(Locale.ROOT)) {
            case "devices" -> String.valueOf(stats.devices());
            case "devices_online" -> String.valueOf(stats.devicesOnline());
            case "devices_server" -> String.valueOf(stats.devicesEntitled("server"));
            case "devices_app" -> String.valueOf(stats.devicesEntitled("app"));
            case "devices_free" -> String.valueOf(stats.devicesEntitled("free"));
            case "licensed" -> String.valueOf(stats.licensed());
            case "license_state" -> stats.licenseState();
            case "api_requests" -> String.valueOf(stats.apiRequests());
            case "api_requests_per_minute" -> String.valueOf(stats.apiRequestsPerMinute());
            case "rate_limited" -> String.valueOf(stats.rateLimitedRequests());
            case "live_clients" -> String.valueOf(stats.liveClients());
            case "history_samples" -> String.valueOf(stats.historySamples());
            case "history_storage" -> stats.historyStorage();
            case "placeholders" -> String.valueOf(stats.placeholders());
            case "dashboards" -> String.valueOf(stats.dashboards());
            case "uptime" -> String.valueOf(stats.uptimeSeconds());
            case "uptime_formatted" -> format(stats.uptimeSeconds());
            case "version" -> stats.version();
            default -> null;
        };
    }

    /** {@code 3d 4h 5m 6s}: leading zero units are left out, {@code 0s} for none. */
    public static String format(long seconds) {
        long d = seconds / 86_400, h = seconds % 86_400 / 3600, m = seconds % 3600 / 60, s = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("d ");
        if (d > 0 || h > 0) sb.append(h).append("h ");
        if (d > 0 || h > 0 || m > 0) sb.append(m).append("m ");
        return sb.append(s).append('s').toString();
    }
}
