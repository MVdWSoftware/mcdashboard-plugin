/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bstats.charts.CustomChart;
import org.junit.jupiter.api.Test;
import org.mcdashboard.stats.SetupSummary;

/** The shared bStats charts carry the configured feature flags and the history backend label. */
class ProxyMetricsTest {
    @Test
    void registersTheFourAnonymousCharts() {
        List<CustomChart> charts = new ArrayList<>();
        ProxyMetrics.register(charts::add, () -> new SetupSummary(true, true, false, "postgresql"));
        assertEquals(4, charts.size());

        String json = charts.get(0).getRequestJsonObject((message, error) -> {
        }, true).toString();
        assertTrue(json.contains("server_enabled"), json);
        assertTrue(json.contains("enabled"), json);
        assertTrue(charts.get(3).getRequestJsonObject((message, error) -> {
        }, true).toString().contains("postgresql"));
    }

    @Test
    void mapsTheOffStateAndUnknownBackend() {
        List<CustomChart> charts = new ArrayList<>();
        ProxyMetrics.register(charts::add, () -> new SetupSummary(false, false, false, "custom"));
        assertTrue(charts.get(0).getRequestJsonObject((message, error) -> {
        }, true).toString().contains("disabled"));
        assertTrue(charts.get(1).getRequestJsonObject((message, error) -> {
        }, true).toString().contains("free"));
        assertTrue(charts.get(3).getRequestJsonObject((message, error) -> {
        }, true).toString().contains("unknown"));
    }
}
