package org.mcdashboard.web;

import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.auth.OtpService;
import org.mcdashboard.auth.RateLimiter;
import org.mcdashboard.auth.TokenService;
import org.mcdashboard.dashboard.Dashboard;
import org.mcdashboard.dashboard.DashboardStore;
import org.mcdashboard.dashboard.Languages;
import org.mcdashboard.placeholder.PlaceholderService;

import java.util.List;

/** Everything the REST API and the live feed share. */
public record ApiContext(String serverName, String pluginVersion, java.util.function.Supplier<ServerStatus> status,
                         boolean requireAuthentication, int maxDevices,
                         long accessTokenSeconds, long refreshTokenSeconds, int websocketPort,
                         PlaceholderService placeholders, TokenService tokens, DeviceStore devices,
                         OtpService otps, RateLimiter authLimiter, DashboardStore dashboards,
                         java.util.Set<String> trustedProxies,
                         org.mcdashboard.placeholder.HistoryService history,
                         String licenseId,
                         java.util.function.Consumer<org.mcdashboard.auth.DeviceStore.Device> onLinked,
                         org.mcdashboard.auth.PermissionCache permissions,
                         String defaultLanguage,
                         java.util.function.BooleanSupplier developerMode,
                         Runnable onDashboardsChanged,
                         java.util.List<String> endpoints, boolean discoveryOnly, boolean polling) {
    /** Protocol version reported by {@code /v1/info}; bump on breaking API changes. */
    public static final int PROTOCOL = 1;

    /** Every language a dashboard on this server uses, plus the default, sorted. */
    public java.util.List<String> languages() {
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        out.add(Languages.normaliseOrDefault(defaultLanguage));
        for (Dashboard d : dashboards.all()) out.addAll(d.languages());
        return java.util.List.copyOf(out);
    }

    /** A context with the given endpoints and discovery mode, and polling on (tests and older callers). */
    public ApiContext(String serverName, String pluginVersion, java.util.function.Supplier<ServerStatus> status,
                      boolean requireAuthentication, int maxDevices, long accessTokenSeconds, long refreshTokenSeconds,
                      int websocketPort, PlaceholderService placeholders, TokenService tokens, DeviceStore devices,
                      OtpService otps, RateLimiter authLimiter, DashboardStore dashboards,
                      java.util.Set<String> trustedProxies, org.mcdashboard.placeholder.HistoryService history,
                      String licenseId, java.util.function.Consumer<org.mcdashboard.auth.DeviceStore.Device> onLinked,
                      org.mcdashboard.auth.PermissionCache permissions, String defaultLanguage,
                      java.util.function.BooleanSupplier developerMode, Runnable onDashboardsChanged,
                      java.util.List<String> endpoints, boolean discoveryOnly) {
        this(serverName, pluginVersion, status, requireAuthentication, maxDevices, accessTokenSeconds, refreshTokenSeconds,
                websocketPort, placeholders, tokens, devices, otps, authLimiter, dashboards, trustedProxies, history,
                licenseId, onLinked, permissions, defaultLanguage, developerMode, onDashboardsChanged, endpoints,
                discoveryOnly, true);
    }

    /** A context with read-only dashboards and developer mode off (tests and older callers). */
    public ApiContext(String serverName, String pluginVersion, java.util.function.Supplier<ServerStatus> status,
                      boolean requireAuthentication, int maxDevices, long accessTokenSeconds, long refreshTokenSeconds,
                      int websocketPort, PlaceholderService placeholders, TokenService tokens, DeviceStore devices,
                      OtpService otps, RateLimiter authLimiter, List<Dashboard> dashboards,
                      java.util.Set<String> trustedProxies, org.mcdashboard.placeholder.HistoryService history,
                      String licenseId, java.util.function.Consumer<org.mcdashboard.auth.DeviceStore.Device> onLinked,
                      org.mcdashboard.auth.PermissionCache permissions, String defaultLanguage) {
        this(serverName, pluginVersion, status, requireAuthentication, maxDevices, accessTokenSeconds,
                refreshTokenSeconds, websocketPort, placeholders, tokens, devices, otps, authLimiter,
                DashboardStore.inMemory(dashboards), trustedProxies, history, licenseId, onLinked, permissions,
                defaultLanguage, () -> false, () -> {
                }, List.of(), false, true);
    }

    /** A context without endpoints and with the full API on this port (tests and older callers). */
    public ApiContext(String serverName, String pluginVersion, java.util.function.Supplier<ServerStatus> status,
                      boolean requireAuthentication, int maxDevices, long accessTokenSeconds, long refreshTokenSeconds,
                      int websocketPort, PlaceholderService placeholders, TokenService tokens, DeviceStore devices,
                      OtpService otps, RateLimiter authLimiter, DashboardStore dashboards,
                      java.util.Set<String> trustedProxies, org.mcdashboard.placeholder.HistoryService history,
                      String licenseId, java.util.function.Consumer<org.mcdashboard.auth.DeviceStore.Device> onLinked,
                      org.mcdashboard.auth.PermissionCache permissions, String defaultLanguage,
                      java.util.function.BooleanSupplier developerMode, Runnable onDashboardsChanged) {
        this(serverName, pluginVersion, status, requireAuthentication, maxDevices, accessTokenSeconds, refreshTokenSeconds,
                websocketPort, placeholders, tokens, devices, otps, authLimiter, dashboards, trustedProxies, history,
                licenseId, onLinked, permissions, defaultLanguage, developerMode, onDashboardsChanged, List.of(), false, true);
    }
}
