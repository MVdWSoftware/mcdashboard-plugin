/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import org.bstats.charts.CustomChart;
import org.bstats.charts.SimplePie;
import org.mcdashboard.stats.SetupSummary;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The bStats custom charts the proxy plugins share. It only says whether the web server, a licence and the tunnel
 * are on and which history backend is configured; it never carries a server name, address, licence id or
 * placeholder value. The proxy's default bStats charts (players, managed servers, online mode, versions, OS)
 * are collected by bStats itself.
 */
public final class ProxyMetrics {
    private ProxyMetrics() {
    }

    /**
     * Registers the shared custom charts on a platform's bStats instance.
     *
     * @param add The platform metrics object's {@code addCustomChart} method.
     * @param summary Supplies the current setup summary; read on every submission.
     */
    public static void register(Consumer<CustomChart> add, Supplier<SetupSummary> summary) {
        add.accept(new SimplePie("server_enabled", () -> yesNo(summary.get().serverEnabled())));
        add.accept(new SimplePie("licensed", () -> summary.get().licensed() ? "licensed" : "free"));
        add.accept(new SimplePie("tunnel", () -> yesNo(summary.get().tunnelEnabled())));
        add.accept(new SimplePie("history_backend", () -> summary.get().historyLabel()));
    }

    /**
     * Maps a boolean to the two chart values.
     *
     * @param value The flag.
     * @return {@code enabled} or {@code disabled}.
     */
    private static String yesNo(boolean value) {
        return value ? "enabled" : "disabled";
    }
}
