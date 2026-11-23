/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The labels the bStats custom charts publish. */
class SetupSummaryTest {
    @Test
    void mapsEveryStorageTypeToItsChartLabel() {
        assertEquals("memory", summary("memory").historyLabel());
        assertEquals("sqlite", summary("sqlite").historyLabel());
        assertEquals("mysql", summary("mysql").historyLabel());
        assertEquals("mariadb", summary("MARIADB").historyLabel());
        assertEquals("postgresql", summary("PostgreSQL").historyLabel());
        assertEquals("unknown", summary("other").historyLabel());
    }

    private static SetupSummary summary(String historyBackend) {
        return new SetupSummary(true, true, false, historyBackend);
    }
}
