/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mcdashboard.placeholder.HistoryService;
import org.mcdashboard.history.HistorySettings;
import org.mcdashboard.placeholder.ExpansionCatalog;
import org.mcdashboard.placeholder.ExpansionLookup;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.placeholder.PlaceholderService;

class CatalogLoaderTest {
    /** Runs delayed work at once, so the retries finish within the test. */
    private static class Platform2 extends FakePlatform {
        ExpansionLookup lookup = id -> null;
        boolean catalog = true;
        final AtomicInteger later = new AtomicInteger();

        @Override
        public ExpansionLookup expansions() {
            return lookup;
        }

        @Override
        public boolean supportsCatalog() {
            return catalog;
        }

        @Override
        public void runSyncLater(Runnable task, long ticks) {
            later.incrementAndGet();
            task.run();
        }
    }

    private static List<ExpansionCatalog.Entry> entries(String expansion) {
        return ExpansionCatalog.parseAll(List.of(Map.of("expansion", expansion)), m -> fail(m));
    }

    private static CatalogLoader loader(Platform2 p, PlaceholderService svc, List<ExpansionCatalog.Entry> catalog) {
        Clock clock = Clock.systemUTC();
        return new CatalogLoader(p, svc, new HistoryService(clock, List.of()), HistorySettings.defaults(),
                p.permissions(null), new ArrayList<PlaceholderDefinition>(), catalog);
    }

    private PlaceholderService service() {
        return new PlaceholderService((t, u) -> "1", Clock.systemUTC(), 0, List.of());
    }

    @Test
    void loadsTheCatalogOnTheMainThread() {
        Platform2 p = new Platform2();
        p.lookup = id -> id.equals("server") ? List.of("%server_online%") : null;
        PlaceholderService svc = service();
        loader(p, svc, entries("server")).start();
        assertEquals(1, svc.all().size());
        assertTrue(p.logged.stream().anyMatch(l -> l.contains("1 catalog placeholder(s) loaded")));
        assertEquals(0, p.later.get());
        assertTrue(p.permissionCalls.stream().anyMatch(c -> c.startsWith("add ")));
    }

    @Test
    void doesNothingWithoutCatalogEntries() {
        Platform2 p = new Platform2();
        loader(p, service(), List.of()).start();
        assertTrue(p.logged.isEmpty());
    }

    @Test
    void saysSoWhenThePlatformCannotListPlaceholders() {
        Platform2 p = new Platform2();
        p.catalog = false;
        loader(p, service(), entries("server")).start();
        assertTrue(p.logged.get(0).startsWith("WARN placeholders.yml: catalog is not supported"));
    }

    @Test
    void retriesAnExpansionThatIsNotThereYetThenReportsIt() {
        Platform2 p = new Platform2();
        loader(p, service(), entries("late")).start();
        assertEquals(6, p.later.get(), "retried the maximum number of times");
        assertTrue(p.logged.stream().anyMatch(l -> l.startsWith("WARN") && l.contains("late")));
    }

    @Test
    void anExpansionThatAppearsLaterIsPickedUp() {
        Platform2 p = new Platform2();
        AtomicInteger calls = new AtomicInteger();
        p.lookup = id -> calls.incrementAndGet() < 3 ? null : List.of("%late_a%");
        PlaceholderService svc = service();
        loader(p, svc, entries("late")).start();
        assertEquals(1, svc.all().size());
        assertEquals(2, p.later.get());
    }

    @Test
    void aFailingLookupIsLoggedNotThrown() {
        Platform2 p = new Platform2();
        p.lookup = id -> {
            throw new IllegalStateException("boom");
        };
        loader(p, service(), entries("x")).start();
        assertTrue(p.logged.stream().anyMatch(l -> l.contains("Could not load the placeholder catalog: boom")));
    }

    @Test
    void reportsInfoAndWarningLinesOfTheCatalog() {
        Platform2 p = new Platform2();
        p.lookup = id -> List.of("%x_a%");
        loader(p, service(), ExpansionCatalog.parseAll(List.of(Map.of("expansion", "x", "max", 1)), m -> fail(m))).start();
        assertFalse(p.logged.isEmpty());
    }
}
