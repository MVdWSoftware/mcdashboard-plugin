/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.history;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mcdashboard.placeholder.HistoryService;
import org.mcdashboard.placeholder.PlaceholderDefinition;

class HistoryGapsTest {
    @TempDir
    Path dir;

    static final class Tick extends Clock {
        long now = 1_700_000_000_000L;

        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        public Clock withZone(ZoneId z) {
            return this;
        }

        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }

        public long millis() {
            return now;
        }
    }

    /** A store that can be told to fail. */
    static final class FakeStore implements HistoryStore {
        boolean ready;
        boolean failRegister;
        boolean failSince;
        List<HistoryService.Point> stored = List.of();
        final List<Collection<Series>> registered = new ArrayList<>();
        final List<Samples.Sample> appended = new ArrayList<>();
        final List<Map<String, Long>> pruned = new ArrayList<>();
        boolean closed;

        public void register(Collection<Series> series) throws Exception {
            if (failRegister) throw new SQLException("register down");
            registered.add(series);
            ready = true;
        }

        public boolean ready() {
            return ready;
        }

        public void append(List<Samples.Sample> samples) {
            appended.addAll(samples);
        }

        public List<HistoryService.Point> since(String id, long sinceMs, int points) throws Exception {
            if (failSince) throw new SQLException("read down");
            return stored;
        }

        public void prune(Map<String, Long> cutoffMs) {
            pruned.add(cutoffMs);
        }

        public void close() {
            closed = true;
        }
    }

    private static List<PlaceholderDefinition> defs() {
        return PlaceholderDefinition.parseAll(List.of(
                Map.of("id", "a", "value", "%a%", "history", 10, "unit", "u"),
                Map.of("id", "b", "value", "%b%", "history", 60)), m -> fail(m));
    }

    @Test
    void memorySeriesAreTrimmedReducedAndLimitedToWhatIsTracked() {
        Tick c = new Tick();
        HistoryService h = new HistoryService(c, defs());
        h.record("unknown", "1", 1000);
        h.record("a", null, 1000);
        h.track(defs()); // tracking again changes nothing
        for (int i = 0; i < 100; i++) {
            h.record("a", String.valueOf(i), 1000);
            c.now += 11_000;
        }
        assertTrue(h.since("a", 0).size() < 100, "older than the window dropped");
        assertTrue(h.since("a", 0, 5).size() <= 5);
        assertTrue(h.since("a", Long.MAX_VALUE).isEmpty());
        assertTrue(h.since("nope", 0).isEmpty());
        assertTrue(h.trackedIds().contains("a"));
        assertEquals(0, h.samplesWritten());
        h.flush(); // no store: nothing to do
        h.start();
        h.close();
    }

    @Test
    void theBacklogDropsTheOldestWhenTheDatabaseStaysDown() {
        Tick c = new Tick();
        FakeStore store = new FakeStore();
        HistoryService h = new HistoryService(c, defs(), HistorySettings.defaults(), store, m -> {
        });
        for (int i = 0; i < 50_100; i++) {
            h.record("a", "1", 1000);
            c.now += 11_000;
        }
        h.flush();
        assertTrue(store.appended.size() <= 50_000);
        assertEquals(store.appended.size(), h.samplesWritten());
    }

    @Test
    void flushRegistersWritesAndPrunesAndRetriesAfterFailures() {
        Tick c = new Tick();
        FakeStore store = new FakeStore();
        List<String> warnings = new ArrayList<>();
        HistoryService h = new HistoryService(c, defs(), HistorySettings.defaults(), store, warnings::add);
        store.failRegister = true;
        h.flush();
        h.flush(); // the warning is throttled
        assertEquals(1, warnings.size());
        c.now += 400_000;
        h.flush();
        assertEquals(2, warnings.size());
        store.failRegister = false;
        h.record("a", "5", 1000);
        h.flush();
        assertEquals(1, store.registered.size());
        assertEquals(1, store.appended.size());
        assertEquals(1, store.pruned.size());
        c.now += 3_700_000;
        store.ready = false; // forces a new registration
        h.flush();
        assertEquals(2, store.registered.size());
        assertEquals(2, store.pruned.size());
        assertFalse(store.pruned.get(1).isEmpty());
        h.start();
        h.start();
        h.close();
        assertTrue(store.closed);
    }

    @Test
    void readsPreferTheStoreAndFallBackToMemory() {
        Tick c = new Tick();
        FakeStore store = new FakeStore();
        List<String> warnings = new ArrayList<>();
        HistoryService h = new HistoryService(c, defs(), HistorySettings.defaults(), store, warnings::add);
        h.record("a", "7", 1000);
        assertEquals(1, h.since("a", 0).size(), "store not ready: memory");
        store.ready = true;
        store.stored = List.of(new HistoryService.Point(1, "9"), new HistoryService.Point(2, "10"));
        assertEquals(2, h.since("a", 0, 10).size());
        store.stored = List.of();
        assertEquals(1, h.since("a", 0).size(), "an empty answer from the store: memory");
        store.failSince = true;
        assertEquals(1, h.since("a", 0).size());
        assertEquals(1, warnings.size());
    }

    @Test
    void settingsHandleIncludeExcludeAndBadValues() {
        List<String> warnings = new ArrayList<>();
        HistorySettings s = HistorySettings.parse(Map.of("sampling", Map.of("interval", 5000, "flush-interval", "x"), "retention-days", -1,
                "include", java.util.Arrays.asList("a", null, " ", "b*"), "exclude", "not a list"), "srv", warnings::add);
        assertFalse(warnings.isEmpty());
        var all = PlaceholderDefinition.parseAll(List.of(
                Map.of("id", "a", "value", "%a%"), Map.of("id", "bb", "value", "%b%", "history", 5),
                Map.of("id", "c", "value", "%c%")), m -> fail(m));
        var applied = s.apply(all);
        assertTrue(applied.get(0).historyMinutes() > 0);
        assertEquals(5, applied.get(1).historyMinutes());
        assertEquals(0, applied.get(2).historyMinutes());
        assertEquals(5 * 60_000L, s.retentionMs(applied.get(1)));
        assertTrue(s.retentionMs(applied.get(0)) >= 0);
        HistorySettings forever = HistorySettings.parse(Map.of("storage", Map.of("type", "sqlite"), "retention-days", 0, "include", List.of("*")), "srv", m -> {
        });
        assertTrue(forever.apply(all).get(0).historyMinutes() > 0);
        assertEquals(0, forever.retentionMs(forever.apply(all).get(0)));
        assertEquals(30_000, forever.stepMs(all.get(0), 0));
    }

    @Test
    void imagesAndListsAreNeverSampledAndANumberOutsideItsRangeFallsBack() {
        List<String> warnings = new ArrayList<>();
        HistorySettings s = HistorySettings.parse(Map.of("include", List.of("*"),
                "storage", Map.of("type", "sqlite", "port", 70_000)), "srv", warnings::add);
        assertEquals(3306, s.storage().port(), "a port out of range falls back to the default");
        assertTrue(warnings.stream().anyMatch(m -> m.contains("history.storage.port must be a number from 1 to 65535")), warnings.toString());

        Map<String, Object> list = new java.util.LinkedHashMap<>();
        list.put("id", "top");
        list.put("type", "LIST");
        list.put("rows", 5);
        list.put("columns", Map.of("name", "%lb_{n}_name%"));
        list.put("history", 60);
        List<PlaceholderDefinition> defs = PlaceholderDefinition.parseAll(List.of(
                Map.of("id", "pic", "type", "IMAGE", "source", "url:https://example.org/a.png", "history", 60), list), m -> fail(m));
        for (PlaceholderDefinition d : s.apply(defs)) {
            assertEquals(0, d.historyMinutes(), d.id() + " carries a picture or a table: nothing to sample");
            assertEquals(s.retentionDays() * 86_400_000L, s.retentionMs(d), "so its range is the server's retention");
        }
    }

    @Test
    void dialectsDifferWhereTheyShould() {
        assertNull(SqlDialect.of(null));
        assertNull(SqlDialect.of("oracle"));
        assertEquals(SqlDialect.MYSQL, SqlDialect.of("MariaDB"));
        assertEquals(SqlDialect.POSTGRESQL, SqlDialect.of("postgres"));
        for (SqlDialect d : SqlDialect.values()) {
            assertFalse(d.identity().isEmpty());
            assertTrue(d.insertIfAbsent("t", List.of("a", "b")).startsWith("INSERT INTO t (a, b) VALUES (?, ?)"));
            assertTrue(d.intDiv("x", 3).contains(d == SqlDialect.MYSQL ? "DIV" : "/"));
            assertEquals(d == SqlDialect.MYSQL, d.inlineComments());
            assertEquals(d == SqlDialect.POSTGRESQL, d.commentOn());
            assertEquals(d == SqlDialect.MYSQL, d.tableOptions().contains("InnoDB"));
        }
        assertTrue(SqlDialect.MYSQL.insertIfAbsent("t", List.of("a")).contains("ON DUPLICATE KEY"));
    }

    @Test
    void samplesKeepNumbersAndText() {
        assertEquals("1.5", Samples.encode("&a1,5").asString());
        assertEquals("007", Samples.encode("007").asString());
        assertEquals("x".repeat(255), Samples.encode("x".repeat(400)).asString());
        assertNotNull(Samples.encode("99999999999999999999").asString());
        assertEquals("1e400", Samples.encode("1e400").asString());
        List<HistoryService.Point> pts = new ArrayList<>();
        for (int i = 0; i < 50; i++) pts.add(new HistoryService.Point(i * 1000L, i % 7 == 0 ? "text" + i : String.valueOf(i)));
        assertTrue(Samples.reduce(pts, 5).size() <= 6);
        assertSame(pts, Samples.reduce(pts, 0));
        assertSame(pts, Samples.reduce(pts, 500));
    }

    private JdbcHistoryStore.ConnectionSource sqlite(AtomicBoolean failCommit, int[] opened) {
        return () -> {
            opened[0]++;
            Connection real = new JdbcConnector("jdbc:sqlite:" + dir.resolve("h.db"), null, null, dir).open();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
                if (m.getName().equals("commit") && failCommit.get()) throw new SQLException("commit failed");
                if (m.getName().equals("isValid") && failCommit.get()) return false;
                try {
                    return m.invoke(real, a);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
        };
    }

    @Test
    void theJdbcStoreRollsBackReopensAndHandlesEveryShape() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        int[] opened = {0};
        Tick c = new Tick();
        JdbcHistoryStore store = new JdbcHistoryStore(SqlDialect.SQLITE, sqlite(fail, opened), "https://example.org/mc", "T", c);
        assertFalse(store.ready());
        store.register(List.of(new HistoryStore.Series("a", "A", null, 10_000), new HistoryStore.Series("b", "B", "u", 10_000)));
        store.register(List.of(new HistoryStore.Series("a", "A2", "u", 10_000)));
        assertTrue(store.ready());
        store.append(List.of());
        store.append(List.of(new Samples.Sample("a", 1000, "5"), new Samples.Sample("a", 2000, "text"), new Samples.Sample("ghost", 1, "1"),
                new Samples.Sample("a", 1000, "5")));
        assertEquals(2, store.since("a", 0, 10).size());
        assertTrue(store.since("ghost", 0, 10).isEmpty());
        assertTrue(store.since("a", 10_000, 10).isEmpty());
        for (int i = 0; i < 40; i++) store.append(List.of(new Samples.Sample("a", 10_000L + i * 1000L, String.valueOf(i))));
        assertTrue(store.since("a", 0, 5).size() <= 6, "averaged into buckets");
        store.prune(Map.of("a", 5000L, "ghost", 1L));

        fail.set(true);
        assertThrows(SQLException.class, () -> store.append(List.of(new Samples.Sample("a", 99_000, "1"))));
        assertThrows(SQLException.class, () -> store.prune(Map.of("a", 5000L)));
        assertThrows(SQLException.class, () -> store.register(List.of(new HistoryStore.Series("a", "A", null, 10_000))));
        assertTrue(opened[0] >= 2, "a connection that is not valid is replaced");
        fail.set(false);
        store.close();
        assertFalse(store.ready());
        store.close();
        // a baseUri that already ends in a separator is kept as it is
        for (String base : new String[]{"https://x/", "urn:x:", "https://x#"}) {
            assertNotNull(new JdbcHistoryStore(SqlDialect.SQLITE, sqlite(fail, opened), base, "T", c));
        }
    }
}
