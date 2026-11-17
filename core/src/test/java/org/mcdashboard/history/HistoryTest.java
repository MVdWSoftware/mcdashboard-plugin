/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.history;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mcdashboard.placeholder.HistoryService;
import org.mcdashboard.placeholder.HistoryService.Point;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.SnakeYamlCodec;
import org.mcdashboard.config.ConfigView;

class HistoryTest {
    @TempDir
    Path temp;

    /** A clock the test moves. */
    static final class TestClock extends Clock {
        long now = 1_700_000_000_000L;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }

        @Override
        public long millis() {
            return now;
        }
    }

    private static PlaceholderDefinition def(String id, Object... more) {
        Map<String, Object> m = new HashMap<>(Map.of("id", id, "value", "%x_" + id + "%", "type", "AUTO"));
        for (int i = 0; i < more.length; i += 2) m.put((String) more[i], more[i + 1]);
        return PlaceholderDefinition.fromMap(m);
    }

    // ---- value encoding and reduction -------------------------------------------------------------------------

    @Test
    void numbersAreKeptAsNumbersAndTheRestAsText() {
        assertEquals(19.98, Samples.encode("19.98").number());
        assertEquals(20.0, Samples.encode("*20.0").number(), "a capped TPS keeps its number");
        assertEquals(85.0, Samples.encode("85%").number());
        assertEquals(12.0, Samples.encode("§a12").number(), "colour codes are dropped");
        assertEquals(1.5, Samples.encode("1,5").number());
        assertEquals("true", Samples.encode("true").text());
        assertEquals("007", Samples.encode("007").text(), "an identifier, not a number");
        assertEquals("NaN", Samples.encode("NaN").text());
        assertEquals(Samples.MAX_TEXT, Samples.encode("x".repeat(1000)).text().length());
        assertEquals("20", Samples.encode("20.0").asString());
        assertEquals("19.98", Samples.encode("19.98").asString());
    }

    @Test
    void longSeriesAreAveragedIntoBuckets() {
        List<Point> in = new ArrayList<>();
        for (int i = 0; i < 100; i++) in.add(new Point(1000L + i * 1000L, String.valueOf(i)));
        List<Point> out = Samples.reduce(in, 10);
        assertTrue(out.size() <= 10 && out.size() >= 9, "got " + out.size());
        for (int i = 1; i < out.size(); i++) assertTrue(out.get(i).timeMs() > out.get(i - 1).timeMs());
        assertEquals(in.get(99).timeMs(), out.get(out.size() - 1).timeMs(), "a bucket keeps the time of its last sample");
        assertEquals(4.5, Double.parseDouble(out.get(0).value()), 1.0);
        assertSame(in, Samples.reduce(in, 200), "short series pass through");
        List<Point> text = List.of(new Point(1, "a"), new Point(2, "b"), new Point(3, "c"), new Point(4, "d"));
        assertEquals(List.of("b", "d"), Samples.reduce(text, 2).stream().map(Point::value).toList());
    }

    // ---- settings ---------------------------------------------------------------------------------------------

    @Test
    void settingsHaveSafeDefaultsAndReportMistakes() {
        List<String> warnings = new ArrayList<>();
        HistorySettings d = HistorySettings.parse(null, "Survival 1", warnings::add);
        assertFalse(d.persistent());
        assertEquals(30_000, d.intervalMs());
        assertEquals("urn:mcdashboard:survival-1", d.storage().baseUri());

        HistorySettings bad = HistorySettings.parse(Map.of("storage", Map.of("type", "oracle"),
                "sampling", Map.of("interval", 1)), "s", warnings::add);
        assertFalse(bad.persistent(), "an unknown type falls back to memory");
        assertEquals(30_000, bad.intervalMs());
        assertEquals(2, warnings.size());

        HistorySettings pg = HistorySettings.parse(Map.of("storage", Map.of("type", "PostgreSQL", "host", "db", "database", "mc",
                "server-id", "Lobby", "base-uri", "https://example.org/mc/"), "retention-days", 90), "s", warnings::add);
        assertEquals(SqlDialect.POSTGRESQL, pg.storage().dialect());
        assertEquals(5432, pg.storage().port());
        assertEquals("jdbc:postgresql://db:5432/mc", pg.storage().jdbcUrl(temp));
        assertEquals("https://example.org/mc/", pg.storage().baseUri());
        assertEquals(90, pg.retentionDays());
        assertEquals("jdbc:mysql://h:3306/x", HistorySettings.parse(Map.of("storage", Map.of("type", "mariadb", "host", "h", "database", "x")),
                "s", warnings::add).storage().jdbcUrl(temp));
        assertTrue(HistorySettings.parse(Map.of("storage", Map.of("type", "sqlite")), "s", warnings::add)
                .storage().jdbcUrl(temp).endsWith("history.db"));
        assertEquals("jdbc:custom:x", HistorySettings.parse(Map.of("storage", Map.of("type", "postgresql", "url", "jdbc:custom:x")),
                "s", warnings::add).storage().jdbcUrl(temp));
    }

    @Test
    void theShippedConfigParsesToTheDocumentedDefaults() throws Exception {
        var codec = new SnakeYamlCodec();
        ConfigView yaml;
        try (var in = getClass().getResourceAsStream("/config.yml")) {
            yaml = ConfigView.of(codec.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
        List<String> warnings = new ArrayList<>();
        HistorySettings s = HistorySettings.parse(yaml.getSection("history"), "server", warnings::add);
        assertEquals(List.of(), warnings);
        assertFalse(s.persistent());
        assertEquals(30_000, s.intervalMs());
        assertEquals(10_000, s.flushMs());
        assertEquals(30, s.retentionDays());
        assertEquals(List.of(), s.include());
        assertEquals(List.of(), s.exclude());
        // a section a server owner would write
        yaml = ConfigView.of(codec.parse("history:\n  storage:\n    type: postgresql\n    host: db\n  include: [\"tps\", \"ram-*\"]\n  sampling:\n    interval: 60\n"));
        HistorySettings custom = HistorySettings.parse(yaml.getSection("history"), "server", warnings::add);
        assertEquals(SqlDialect.POSTGRESQL, custom.storage().dialect());
        assertEquals(List.of("tps", "ram-*"), custom.include());
        assertEquals(60_000, custom.intervalMs());
        assertEquals(List.of(), warnings);
    }

    @Test
    void includeAndExcludeListsDecideWhatIsRecorded() {
        HistorySettings s = HistorySettings.parse(Map.of("include", List.of("tps", "ram-*", "*_total"),
                "exclude", List.of("ram-secret", "motd")), "s", m -> fail(m));
        List<PlaceholderDefinition> out = s.apply(List.of(
                def("tps"), def("ram-used"), def("ram-secret"), def("disk_total"), def("other"),
                def("mine", "access", "PLAYER"), def("logo", "type", "IMAGE", "source", "file:logo.png"),
                def("motd", "history", 60), def("kept", "history", 45)));
        Function<String, Integer> minutes = id -> out.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow().historyMinutes();
        assertEquals(HistorySettings.MEMORY_MINUTES, minutes.apply("tps"));
        assertEquals(HistorySettings.MEMORY_MINUTES, minutes.apply("ram-used"));
        assertEquals(HistorySettings.MEMORY_MINUTES, minutes.apply("disk_total"));
        assertEquals(0, minutes.apply("ram-secret"), "exclude wins over include");
        assertEquals(0, minutes.apply("other"));
        assertEquals(0, minutes.apply("mine"), "per-player values are never recorded");
        assertEquals(0, minutes.apply("logo"));
        assertEquals(0, minutes.apply("motd"), "exclude also removes an explicit history");
        assertEquals(45, minutes.apply("kept"), "an explicit window is left alone");

        HistorySettings db = HistorySettings.parse(Map.of("storage", Map.of("type", "sqlite"), "include", List.of("*"),
                "retention-days", 7), "s", m -> fail(m));
        PlaceholderDefinition tps = db.apply(List.of(def("tps"))).get(0);
        assertEquals(7 * 1440, tps.historyMinutes());
        assertEquals(7 * 86_400_000L, db.retentionMs(tps));
        assertEquals(30 * 60_000L, db.retentionMs(def("x", "history", 30)));
        HistorySettings forever = HistorySettings.parse(Map.of("storage", Map.of("type", "sqlite"), "include", List.of("*"),
                "retention-days", 0), "s", m -> fail(m));
        assertEquals(0, forever.retentionMs(forever.apply(List.of(def("tps"))).get(0)), "0 days = never prune");
    }

    @Test
    void samplingStepFollowsThePlaceholderThenTheDefaultAndNeverBeatsTheSource() {
        HistorySettings s = HistorySettings.parse(Map.of("sampling", Map.of("interval", 60)), "s", m -> fail(m));
        assertEquals(60_000, s.stepMs(def("a"), 1_000));
        assertEquals(15_000, s.stepMs(def("b", "history-interval", 15), 1_000));
        assertEquals(10_000, HistorySettings.parse(null, "s", m -> fail(m)).stepMs(def("c", "history-interval", 1), 0) , "never under 10 s");
        assertEquals(120_000, s.stepMs(def("d", "history-interval", 15), 120_000), "not faster than the source refreshes");
    }

    // ---- schema -----------------------------------------------------------------------------------------------

    @Test
    void everyTableColumnAndForeignKeyNamesItsOntologyTerm() {
        int tables = SosaSchema.tables().size();
        int columns = SosaSchema.tables().stream().mapToInt(t -> t.columns().size()).sum();
        int keys = SosaSchema.tables().stream().mapToInt(t -> t.foreignKeys().size()).sum();
        assertEquals(tables + columns + keys, SosaSchema.comments(SqlDialect.POSTGRESQL).size());
        SosaSchema.tables().forEach(t -> {
            assertFalse(t.term().isBlank(), t.name());
            t.columns().forEach(c -> assertFalse(c.term().isBlank(), t.name() + "." + c.name()));
            t.foreignKeys().forEach(f -> assertFalse(f.term().isBlank(), f.name()));
        });
        String pg = String.join("\n", SosaSchema.comments(SqlDialect.POSTGRESQL));
        assertTrue(pg.contains("COMMENT ON TABLE mcd_observation IS 'http://www.w3.org/ns/sosa/Observation'"));
        assertTrue(pg.contains("COMMENT ON COLUMN mcd_sensor.platform_id IS 'http://www.w3.org/ns/sosa/isHostedBy'"));
        assertTrue(pg.contains("COMMENT ON CONSTRAINT fk_mcd_sensor_procedure ON mcd_sensor IS 'http://www.w3.org/ns/ssn/implements'"));
        assertFalse(pg.contains("'http://www.w3.org/ns/sosa/System"), "sensors are ssn:System, not sosa:System");
        String mysql = String.join("\n", SosaSchema.create(SqlDialect.MYSQL));
        assertEquals(columns, mysql.split(" COMMENT '", -1).length - 1, "every column carries an inline comment");
        assertTrue(mysql.contains("ENGINE=InnoDB"));
        assertFalse(String.join("\n", SosaSchema.create(SqlDialect.SQLITE)).contains("COMMENT"));
        assertTrue(SosaSchema.comments(SqlDialect.SQLITE).isEmpty());
        assertTrue(SosaSchema.comments(SqlDialect.MYSQL).isEmpty());
    }

    // ---- the store on real databases --------------------------------------------------------------------------

    private JdbcHistoryStore.ConnectionSource sqlite(String name) {
        return new JdbcConnector("jdbc:sqlite:" + temp.resolve(name), "", "", temp.resolve("drivers"));
    }

    @Test
    void sqliteStoresAndServesSamplesAcrossRestarts() throws Exception {
        exercise(SqlDialect.SQLITE, sqlite("history.db"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MCD_TEST_POSTGRES_URL", matches = ".+")
    void postgresqlStoresAndServesSamples() throws Exception {
        JdbcConnector c = new JdbcConnector(System.getenv("MCD_TEST_POSTGRES_URL"), "", "", temp.resolve("drivers"));
        dropAll(c);
        exercise(SqlDialect.POSTGRESQL, c);
        try (Connection db = c.open(); Statement s = db.createStatement();
             ResultSet r = s.executeQuery("SELECT count(*) FROM pg_description d JOIN pg_class c ON c.oid = d.objoid WHERE c.relname LIKE 'mcd\\_%'")) {
            r.next();
            assertTrue(r.getInt(1) >= SosaSchema.tables().stream().mapToInt(t -> 1 + t.columns().size()).sum(), "COMMENT ON is in place");
        }
        try (Connection db = c.open(); Statement s = db.createStatement();
             ResultSet r = s.executeQuery("SELECT obj_description(oid, 'pg_constraint') FROM pg_constraint WHERE conname = 'fk_mcd_sensor_platform'")) {
            r.next();
            assertEquals("http://www.w3.org/ns/sosa/isHostedBy", r.getString(1));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MCD_TEST_MYSQL_URL", matches = ".+")
    void mysqlStoresAndServesSamples() throws Exception {
        JdbcConnector c = new JdbcConnector(System.getenv("MCD_TEST_MYSQL_URL"), "", "", temp.resolve("drivers"));
        dropAll(c);
        exercise(SqlDialect.MYSQL, c);
        try (Connection db = c.open(); Statement s = db.createStatement();
             ResultSet r = s.executeQuery("SELECT count(*) FROM information_schema.COLUMNS WHERE table_schema = DATABASE() "
                     + "AND table_name LIKE 'mcd\\_%' AND (column_comment IS NULL OR column_comment = '')")) {
            r.next();
            assertEquals(0, r.getInt(1), "every column has its ontology comment");
        }
    }

    private static void dropAll(JdbcHistoryStore.ConnectionSource src) throws SQLException {
        List<SosaSchema.Table> tables = new ArrayList<>(SosaSchema.tables());
        Collections.reverse(tables);
        try (Connection c = src.open(); Statement s = c.createStatement()) {
            for (SosaSchema.Table t : tables) s.execute("DROP TABLE IF EXISTS " + t.name());
        }
    }

    private JdbcHistoryStore store(SqlDialect d, JdbcHistoryStore.ConnectionSource src, TestClock clock) {
        return new JdbcHistoryStore(d, src, "urn:mcdashboard:test", "Test server", clock);
    }

    /** The behaviour every database has to show. */
    private void exercise(SqlDialect d, JdbcHistoryStore.ConnectionSource src) throws Exception {
        TestClock clock = new TestClock();
        long t0 = clock.now;
        JdbcHistoryStore store = store(d, src, clock);
        assertFalse(store.ready());
        List<HistoryStore.Series> series = List.of(new HistoryStore.Series("tps", "Ticks per second", "tps", 30_000),
                new HistoryStore.Series("state", "State", null, 60_000));
        store.register(series);
        store.register(series); // idempotent
        assertTrue(store.ready());

        List<Samples.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 10; i++) samples.add(new Samples.Sample("tps", t0 + i * 30_000L, i % 2 == 0 ? "20.0" : "19.5"));
        samples.add(new Samples.Sample("state", t0, "open"));
        samples.add(new Samples.Sample("state", t0 + 60_000, "true"));
        samples.add(new Samples.Sample("tps", t0, "1"));             // same sensor and time again: skipped, not an error
        samples.add(new Samples.Sample("nobody", t0, "1"));          // not a registered sensor: ignored
        store.append(samples);

        List<Point> tps = store.since("tps", 0, 2000);
        assertEquals(10, tps.size());
        assertEquals(new Point(t0, "20"), tps.get(0), "the first write wins");
        assertEquals("19.5", tps.get(1).value());
        assertEquals(t0 + 9 * 30_000L, tps.get(9).timeMs());
        assertEquals(5, store.since("tps", t0 + 5 * 30_000L, 2000).size(), "since is inclusive");
        assertEquals(List.of(new Point(t0, "open"), new Point(t0 + 60_000, "true")), store.since("state", 0, 2000));
        assertEquals(List.of(), store.since("nobody", 0, 2000));

        // many samples: reduced by the database to at most the requested points
        List<Samples.Sample> many = new ArrayList<>();
        for (int i = 0; i < 1000; i++) many.add(new Samples.Sample("tps", t0 + 1_000_000L + i * 1000L, String.valueOf(i)));
        store.append(many);
        List<Point> reduced = store.since("tps", t0 + 1_000_000L, 50);
        assertTrue(reduced.size() <= 50 && reduced.size() >= 45, "got " + reduced.size());
        for (int i = 1; i < reduced.size(); i++) assertTrue(reduced.get(i).timeMs() > reduced.get(i - 1).timeMs());
        assertEquals(t0 + 1_000_000L + 999_000L, reduced.get(reduced.size() - 1).timeMs());
        double first = Double.parseDouble(reduced.get(0).value());
        double last = Double.parseDouble(reduced.get(reduced.size() - 1).value());
        assertTrue(first < 30 && last > 970, "buckets are averages: " + first + " .. " + last);

        // the model: a sensor is an ssn:System hosted by the platform, observing a property with a procedure
        try (Connection c = src.open(); Statement s = c.createStatement()) {
            try (ResultSet r = s.executeQuery("SELECT sy.uri, p.uri, pr.sampling_interval_s, op.unit, f.uri FROM mcd_sensor se "
                    + "JOIN mcd_system sy ON sy.id = se.system_id JOIN mcd_platform p ON p.id = se.platform_id "
                    + "JOIN mcd_procedure pr ON pr.id = se.procedure_id JOIN mcd_observable_property op ON op.id = se.observed_property_id "
                    + "JOIN mcd_feature_of_interest f ON f.id = op.feature_of_interest_id WHERE sy.uri = 'urn:mcdashboard:test:sensor:tps'")) {
                assertTrue(r.next());
                assertEquals("urn:mcdashboard:test", r.getString(2));
                assertEquals(30, r.getInt(3));
                assertEquals("tps", r.getString(4));
                assertEquals("urn:mcdashboard:test", r.getString(5));
                assertFalse(r.next());
            }
            try (ResultSet r = s.executeQuery("SELECT count(*) FROM mcd_sensor")) {
                r.next();
                assertEquals(2, r.getInt(1), "registering twice made no duplicates");
            }
            try (ResultSet r = s.executeQuery("SELECT result_numeric, result_text FROM mcd_observation o JOIN mcd_sensor se ON se.system_id = o.sensor_id "
                    + "JOIN mcd_system sy ON sy.id = se.system_id WHERE sy.uri = 'urn:mcdashboard:test:sensor:state' ORDER BY result_time")) {
                assertTrue(r.next());
                assertNull(r.getObject(1));
                assertEquals("open", r.getString(2));
            }
        }

        // a restart: a new store over the same database finds the sensors and the data
        store.close();
        JdbcHistoryStore again = store(d, src, clock);
        again.register(series);
        assertEquals(1010, again.since("tps", 0, 2000).size(), "all samples survived the restart");

        // pruning is per sensor
        again.prune(Map.of("tps", t0 + 3 * 30_000L));
        assertEquals(t0 + 3 * 30_000L, again.since("tps", 0, 2000).get(0).timeMs());
        assertEquals(2, again.since("state", 0, 2000).size(), "other sensors are untouched");
        again.close();
    }

    // ---- the service on top of a store ------------------------------------------------------------------------

    @Test
    void historySurvivesARestartAndAnOutageDoesNotBreakReads() throws Exception {
        TestClock clock = new TestClock();
        List<PlaceholderDefinition> defs = List.of(def("tps", "history", 120));
        HistorySettings settings = HistorySettings.defaults();
        List<String> warnings = new ArrayList<>();
        JdbcHistoryStore.ConnectionSource file = sqlite("service.db");

        HistoryService first = new HistoryService(clock, defs, settings, store(SqlDialect.SQLITE, file, clock), warnings::add);
        first.record("tps", "20.0", 0);
        clock.now += 30_000;
        first.record("tps", "19.0", 0);
        clock.now += 5_000;
        first.record("tps", "18.0", 0); // too soon: skipped
        first.flush();
        first.close();
        assertEquals(List.of(), warnings);

        HistoryService second = new HistoryService(clock, defs, settings, store(SqlDialect.SQLITE, file, clock), warnings::add);
        second.flush();
        List<Point> stored = second.since("tps", 0);
        assertEquals(List.of("20", "19"), stored.stream().map(Point::value).toList(), "read back from the database after a restart");
        second.close();

        // the database goes away: nothing throws, reads use memory, samples wait, one warning
        boolean[] down = {false};
        List<Connection> opened = new ArrayList<>();
        JdbcHistoryStore.ConnectionSource flaky = () -> {
            if (down[0]) throw new SQLException("connection refused");
            Connection c = file.open();
            opened.add(c);
            return c;
        };
        HistoryService third = new HistoryService(clock, defs, settings, store(SqlDialect.SQLITE, flaky, clock), warnings::add);
        third.flush();
        down[0] = true;
        for (Connection c : opened) c.close(); // the server drops the connection
        clock.now += 30_000;
        third.record("tps", "17.0", 0);
        third.flush();
        third.flush();
        assertEquals(1, warnings.size(), "warnings are rate limited");
        assertTrue(warnings.get(0).contains("connection refused"));
        assertEquals(List.of("17.0"), third.since("tps", 0).stream().map(Point::value).toList(), "memory serves while the database is away");
        down[0] = false;
        third.flush();
        assertEquals(List.of("20", "19", "17"), third.since("tps", 0).stream().map(Point::value).toList(), "the waiting sample was written");
        third.close();
    }

    @Test
    void placeholdersAddedLaterAreRecordedToo() throws Exception {
        TestClock clock = new TestClock();
        JdbcHistoryStore.ConnectionSource file = sqlite("late.db");
        HistoryService h = new HistoryService(clock, List.of(), HistorySettings.defaults(), store(SqlDialect.SQLITE, file, clock), m -> fail(m));
        assertFalse(h.tracked("ram"));
        h.record("ram", "1", 0);
        h.flush();
        h.track(List.of(def("ram", "history", 30)));
        assertTrue(h.tracked("ram"));
        h.record("ram", "512", 0);
        h.flush();
        assertEquals(List.of("512"), h.since("ram", 0).stream().map(Point::value).toList());
        h.close();
    }

    @Test
    void memoryOnlyKeepsWorkingLikeBefore() {
        TestClock clock = new TestClock();
        HistoryService h = new HistoryService(clock, List.of(def("tps", "history", 1), def("me", "history", 1, "access", "PLAYER")));
        assertTrue(h.tracked("tps"));
        assertFalse(h.tracked("me"));
        h.record("tps", "20", 0);
        clock.now += 5_000;
        h.record("tps", "19", 0); // under the 10 s minimum
        clock.now += 10_000;
        h.record("tps", "18", 0);
        assertEquals(2, h.since("tps", 0).size());
        clock.now += 120_000;
        h.record("tps", "17", 0);
        assertEquals(List.of("17"), h.since("tps", 0).stream().map(Point::value).toList(), "older than the window is dropped");
        clock.now += 20_000;
        h.record("tps", "16", 0);
        clock.now += 20_000;
        h.record("tps", "15", 0);
        assertTrue(h.since("tps", 0, 2).size() <= 2, "points is honoured");
    }
}
