package org.mcdashboard.history;

import org.mcdashboard.placeholder.HistoryService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Keeps samples in a SQL database using the {@link SosaSchema}. One shared connection, reopened when it breaks. */
public final class JdbcHistoryStore implements HistoryStore {
    /** Opens a connection to the database. */
    public interface ConnectionSource {
        Connection open() throws SQLException;
    }

    private final SqlDialect dialect;
    private final ConnectionSource source;
    private final String baseUri;
    private final String platformLabel;
    private final Clock clock;
    private final Map<String, Long> sensors = new HashMap<>();
    private Connection connection;
    private boolean ready;

    /**
     * @param baseUri the IRI the resource identifiers are minted under (platform, sensors, properties, procedures)
     */
    public JdbcHistoryStore(SqlDialect dialect, ConnectionSource source, String baseUri, String platformLabel, Clock clock) {
        this.dialect = dialect;
        this.source = source;
        this.baseUri = baseUri.endsWith("/") || baseUri.endsWith(":") || baseUri.endsWith("#") ? baseUri : baseUri + ":";
        this.platformLabel = platformLabel;
        this.clock = clock;
    }

    private Connection connection() throws SQLException {
        if (connection != null && !connection.isClosed() && connection.isValid(2)) return connection;
        closeQuietly();
        connection = source.open();
        if (dialect == SqlDialect.SQLITE) {
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("PRAGMA foreign_keys=ON");
            }
        }
        return connection;
    }

    private void closeQuietly() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // already broken
        }
        connection = null;
    }

    @Override
    public synchronized boolean ready() {
        return ready;
    }

    @Override
    public synchronized void register(Collection<Series> series) throws SQLException {
        Connection c = connection();
        c.setAutoCommit(true);
        try (Statement s = c.createStatement()) {
            for (String ddl : SosaSchema.create(dialect)) s.execute(ddl);
            for (String comment : SosaSchema.comments(dialect)) s.execute(comment);
        }
        c.setAutoCommit(false);
        try {
            if (count(c, "SELECT COUNT(*) FROM " + SosaSchema.VERSION_TABLE) == 0) {
                try (PreparedStatement p = c.prepareStatement("INSERT INTO " + SosaSchema.VERSION_TABLE + " (version, applied_at) VALUES (?, ?)")) {
                    p.setInt(1, SosaSchema.VERSION);
                    p.setLong(2, clock.millis());
                    p.executeUpdate();
                }
            }
            long platform = ensure(c, SosaSchema.PLATFORM, baseUri.replaceAll("[:/#]$", ""), platformLabel);
            long feature = ensure(c, SosaSchema.FEATURE, baseUri.replaceAll("[:/#]$", ""), platformLabel);
            Map<String, Long> ids = new HashMap<>();
            for (Series s : series) {
                long property = ensure(c, SosaSchema.PROPERTY, baseUri + "property:" + s.id(), s.label(), Map.of("feature_of_interest_id", feature));
                update(c, "UPDATE " + SosaSchema.PROPERTY + " SET unit = ?, feature_of_interest_id = ? WHERE id = ?", s.unit(), feature, property);
                long procedure = ensure(c, SosaSchema.PROCEDURE, baseUri + "procedure:" + s.id(), s.label());
                update(c, "UPDATE " + SosaSchema.PROCEDURE + " SET sampling_interval_s = ? WHERE id = ?", (int) (s.intervalMs() / 1000), procedure);
                long system = ensure(c, SosaSchema.SYSTEM, baseUri + "sensor:" + s.id(), s.label());
                try (PreparedStatement p = c.prepareStatement(dialect.insertIfAbsent(SosaSchema.SENSOR,
                        List.of("system_id", "platform_id", "observed_property_id", "procedure_id")))) {
                    p.setLong(1, system);
                    p.setLong(2, platform);
                    p.setLong(3, property);
                    p.setLong(4, procedure);
                    p.executeUpdate();
                }
                update(c, "UPDATE " + SosaSchema.SENSOR + " SET platform_id = ?, observed_property_id = ?, procedure_id = ? WHERE system_id = ?",
                        platform, property, procedure, system);
                ids.put(s.id(), system);
            }
            c.commit();
            sensors.clear();
            sensors.putAll(ids);
            ready = true;
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(c);
            throw e;
        }
    }

    /** The id of the row with this URI, created (with the extra columns) when missing; the label follows the configuration. */
    private long ensure(Connection c, String table, String uri, String label, Map<String, Object> extra) throws SQLException {
        Long id = find(c, table, uri);
        if (id == null) {
            List<String> columns = new ArrayList<>(List.of("uri", "label"));
            columns.addAll(extra.keySet());
            try (PreparedStatement p = c.prepareStatement(dialect.insertIfAbsent(table, columns))) {
                p.setString(1, uri);
                p.setString(2, label);
                int i = 3;
                for (Object v : extra.values()) p.setObject(i++, v);
                p.executeUpdate();
            }
            id = find(c, table, uri);
            if (id == null) throw new SQLException("could not create " + uri + " in " + table);
        } else {
            update(c, "UPDATE " + table + " SET label = ? WHERE id = ?", label, id);
        }
        return id;
    }

    private long ensure(Connection c, String table, String uri, String label) throws SQLException {
        return ensure(c, table, uri, label, Map.of());
    }

    private static Long find(Connection c, String table, String uri) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT id FROM " + table + " WHERE uri = ?")) {
            p.setString(1, uri);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getLong(1) : null;
            }
        }
    }

    private static void update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) p.setNull(i + 1, Types.VARCHAR);
                else p.setObject(i + 1, args[i]);
            }
            p.executeUpdate();
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    @Override
    public synchronized void append(List<Samples.Sample> samples) throws SQLException {
        if (samples.isEmpty()) return;
        Connection c = connection();
        c.setAutoCommit(false);
        try (PreparedStatement p = c.prepareStatement(dialect.insertIfAbsent(SosaSchema.OBSERVATION,
                List.of("sensor_id", "result_time", "result_numeric", "result_text")))) {
            for (Samples.Sample s : samples) {
                Long sensor = sensors.get(s.id());
                if (sensor == null) continue; // not registered (yet): nothing to attach it to
                Samples.Result r = Samples.encode(s.value());
                p.setLong(1, sensor);
                p.setLong(2, s.timeMs());
                if (r.number() != null) p.setDouble(3, r.number());
                else p.setNull(3, Types.DOUBLE);
                if (r.text() != null) p.setString(4, r.text());
                else p.setNull(4, Types.VARCHAR);
                p.addBatch();
            }
            p.executeBatch();
            c.commit();
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(c);
            throw e;
        }
    }

    @Override
    public synchronized List<HistoryService.Point> since(String id, long sinceMs, int points) throws SQLException {
        Long sensor = sensors.get(id);
        if (sensor == null) return List.of();
        Connection c = connection();
        c.setAutoCommit(true);
        long min;
        long max;
        long total;
        try (PreparedStatement p = c.prepareStatement("SELECT COUNT(*), MIN(result_time), MAX(result_time) FROM "
                + SosaSchema.OBSERVATION + " WHERE sensor_id = ? AND result_time >= ?")) {
            p.setLong(1, sensor);
            p.setLong(2, sinceMs);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                total = r.getLong(1);
                min = r.getLong(2);
                max = r.getLong(3);
            }
        }
        if (total == 0) return List.of();
        boolean raw = total <= points;
        long bucket = Math.max(1, (max - min) / Math.max(1, points) + 1);
        String sql = raw
                ? "SELECT result_time, result_numeric, result_text FROM " + SosaSchema.OBSERVATION
                + " WHERE sensor_id = ? AND result_time >= ? ORDER BY result_time"
                : "SELECT MAX(result_time) AS t, AVG(result_numeric), MAX(result_text) FROM " + SosaSchema.OBSERVATION
                + " WHERE sensor_id = ? AND result_time >= ? GROUP BY " + dialect.intDiv("result_time - " + min, bucket) + " ORDER BY t";
        List<HistoryService.Point> out = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setLong(1, sensor);
            p.setLong(2, sinceMs);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    double n = r.getDouble(2);
                    String value = r.wasNull() ? r.getString(3) : new Samples.Result(n, null).asString();
                    if (value != null) out.add(new HistoryService.Point(r.getLong(1), value));
                }
            }
        }
        return out;
    }

    @Override
    public synchronized void prune(Map<String, Long> cutoffMs) throws SQLException {
        Connection c = connection();
        c.setAutoCommit(false);
        try (PreparedStatement p = c.prepareStatement("DELETE FROM " + SosaSchema.OBSERVATION + " WHERE sensor_id = ? AND result_time < ?")) {
            for (Map.Entry<String, Long> e : cutoffMs.entrySet()) {
                Long sensor = sensors.get(e.getKey());
                if (sensor == null) continue;
                p.setLong(1, sensor);
                p.setLong(2, e.getValue());
                p.addBatch();
            }
            p.executeBatch();
            c.commit();
        } catch (SQLException | RuntimeException e) {
            rollbackQuietly(c);
            throw e;
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // the connection is gone; the next call reopens it
        }
    }

    @Override
    public synchronized void close() {
        ready = false;
        closeQuietly();
    }
}
