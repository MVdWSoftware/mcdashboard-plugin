/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.history;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates or updates the rows that describe what is recorded: the platform and feature of interest (the server), and
 * per series its observable property, procedure and sensor. Rows are found by their URI, so a restart reuses them.
 */
final class SensorRegistrar {
    private final SqlDialect dialect;
    private final String baseUri;
    private final String platformLabel;

    /** {@code baseUri} ends with {@code :}, {@code /} or {@code #}. */
    SensorRegistrar(SqlDialect dialect, String baseUri, String platformLabel) {
        this.dialect = dialect;
        this.baseUri = baseUri;
        this.platformLabel = platformLabel;
    }

    /** Creates the schema when needed and registers the series in one transaction; the sensor id per series id. */
    Map<String, Long> register(Connection c, Collection<HistoryStore.Series> series, long nowMs) throws SQLException {
        c.setAutoCommit(true);
        try (Statement s = c.createStatement()) {
            for (String ddl : SosaSchema.create(dialect)) s.execute(ddl);
            for (String comment : SosaSchema.comments(dialect)) s.execute(comment);
        }
        c.setAutoCommit(false);
        try {
            if (count(c, SosaSchema.Statement.COUNT_SCHEMA_VERSION) == 0) {
                try (PreparedStatement p = c.prepareStatement(SosaSchema.Statement.INSERT_SCHEMA_VERSION.text())) {
                    p.setInt(1, SosaSchema.VERSION);
                    p.setLong(2, nowMs);
                    p.executeUpdate();
                }
            }
            String server = baseUri.replaceAll("[:/#]$", "");
            long platform = ensure(c, SosaSchema.TableRef.PLATFORM, server, platformLabel, Map.of());
            long feature = ensure(c, SosaSchema.TableRef.FEATURE, server, platformLabel, Map.of());
            Map<String, Long> ids = new HashMap<>();
            for (HistoryStore.Series s : series) ids.put(s.id(), sensor(c, s, platform, feature));
            c.commit();
            return ids;
        } catch (SQLException | RuntimeException e) {
            JdbcHistoryStore.rollbackQuietly(c);
            throw e;
        }
    }

    private long sensor(Connection c, HistoryStore.Series s, long platform, long feature) throws SQLException {
        long property = ensure(c, SosaSchema.TableRef.PROPERTY, baseUri + "property:" + s.id(), s.label(), Map.of("feature_of_interest_id", feature));
        update(c, SosaSchema.Statement.UPDATE_PROPERTY, s.unit(), feature, property);
        long procedure = ensure(c, SosaSchema.TableRef.PROCEDURE, baseUri + "procedure:" + s.id(), s.label(), Map.of());
        update(c, SosaSchema.Statement.UPDATE_PROCEDURE, (int) (s.intervalMs() / 1000), procedure);
        long system = ensure(c, SosaSchema.TableRef.SYSTEM, baseUri + "sensor:" + s.id(), s.label(), Map.of());
        try (PreparedStatement p = c.prepareStatement(SosaSchema.TableRef.SENSOR.insertIfAbsent(dialect,
                List.of("system_id", "platform_id", "observed_property_id", "procedure_id")).text())) {
            p.setLong(1, system);
            p.setLong(2, platform);
            p.setLong(3, property);
            p.setLong(4, procedure);
            p.executeUpdate();
        }
        update(c, SosaSchema.Statement.UPDATE_SENSOR, platform, property, procedure, system);
        return system;
    }

    /** The id of the row with this URI, created (with the extra columns) when missing; the label follows the configuration. */
    private long ensure(Connection c, SosaSchema.TableRef table, String uri, String label, Map<String, Object> extra) throws SQLException {
        Long id = find(c, table, uri);
        if (id != null) {
            update(c, table.updateLabelById(), label, id);
            return id;
        }
        List<String> columns = new ArrayList<>(List.of("uri", "label"));
        columns.addAll(extra.keySet());
        try (PreparedStatement p = c.prepareStatement(table.insertIfAbsent(dialect, columns).text())) {
            p.setString(1, uri);
            p.setString(2, label);
            int i = 3;
            for (Object v : extra.values()) p.setObject(i++, v);
            p.executeUpdate();
        }
        id = find(c, table, uri);
        if (id == null) throw new SQLException("could not create " + uri + " in " + table);
        return id;
    }

    private static Long find(Connection c, SosaSchema.TableRef table, String uri) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(table.selectIdByUri().text())) {
            p.setString(1, uri);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? r.getLong(1) : null;
            }
        }
    }

    private static void update(Connection c, SosaSchema.Query query, Object... args) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(query.text())) {
            for (int i = 0; i < args.length; i++) {
                if (args[i] == null) p.setNull(i + 1, Types.VARCHAR);
                else p.setObject(i + 1, args[i]);
            }
            p.executeUpdate();
        }
    }

    private static long count(Connection c, SosaSchema.Query query) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(query.text())) {
            r.next();
            return r.getLong(1);
        }
    }
}
