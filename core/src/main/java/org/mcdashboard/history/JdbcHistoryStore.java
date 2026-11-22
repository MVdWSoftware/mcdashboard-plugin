/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
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
        /** Opens a new database connection.
         *
         * @return An open JDBC connection that can be used for queries.
         * @throws java.sql.SQLException When the connection cannot be established.
         */
        Connection open() throws SQLException;
    }

    private final SqlDialect dialect;
    private final ConnectionSource source;
    private final SensorRegistrar registrar;
    private final Clock clock;
    private final Map<String, Long> sensors = new HashMap<>();
    private Connection connection;
    private boolean ready;

    /** Creates a new history store backed by a SQL database. {@code baseUri} is the IRI the platform, sensors, properties and procedures are minted under.
     *
     * @param dialect       The SQL dialect to use for generating queries.
     * @param source        Factory for obtaining JDBC connections.
     * @param baseUri       Base URI used to construct sensor URIs in the SOSA schema.
     * @param platformLabel Human-readable label identifying this server/platform instance.
     * @param clock         Clock used for timestamps; null uses system default.
     */
    public JdbcHistoryStore(SqlDialect dialect, ConnectionSource source, String baseUri, String platformLabel, Clock clock) {
        this.dialect = dialect;
        this.source = source;
        String base = baseUri.endsWith("/") || baseUri.endsWith(":") || baseUri.endsWith("#") ? baseUri : baseUri + ":";
        this.registrar = new SensorRegistrar(dialect, base, platformLabel);
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
        Map<String, Long> ids = registrar.register(connection(), series, clock.millis());
        sensors.clear();
        sensors.putAll(ids);
        ready = true;
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

    static void rollbackQuietly(Connection c) {
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
