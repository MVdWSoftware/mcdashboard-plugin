/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.history;

import java.util.ArrayList;
import java.util.List;

/**
 * The history tables, modelled on the W3C Semantic Sensor Network ontology (SOSA/SSN). A placeholder is a
 * {@code sosa:Sensor} that observes one {@code sosa:ObservableProperty} of the server and is hosted by it
 * ({@code sosa:Platform}); each sample is a {@code sosa:Observation}. Sensors are {@code ssn:System}s, so they share
 * a supertype table that carries the identity, and a later actuator or sampler can join it the same way.
 *
 * <p>Every table, column and foreign key documents the ontology term it stands for ({@code COMMENT ON} on PostgreSQL,
 * inline {@code COMMENT} on MySQL). An observation's observed property, feature of interest and procedure are not
 * repeated on each row: they follow from its sensor ({@code sosa:observes}, {@code ssn:isPropertyOf},
 * {@code ssn:implements}). Its {@code sosa:phenomenonTime} equals its {@code sosa:resultTime}: a sample is instantaneous.
 */
public final class SosaSchema {
    public static final int VERSION = 1;
    public static final String SOSA = "http://www.w3.org/ns/sosa/";
    public static final String SSN = "http://www.w3.org/ns/ssn/";
    public static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    public static final String MCD = "https://mcdashboard.org/ns#";

    public static final String PLATFORM = "mcd_platform";
    public static final String FEATURE = "mcd_feature_of_interest";
    public static final String PROPERTY = "mcd_observable_property";
    public static final String PROCEDURE = "mcd_procedure";
    public static final String SYSTEM = "mcd_system";
    public static final String SENSOR = "mcd_sensor";
    public static final String OBSERVATION = "mcd_observation";
    public static final String VERSION_TABLE = "mcd_schema";

    private SosaSchema() {
    }

    /** SQL text built by this schema; a prepared statement takes one of these rather than a free-form string. */
    @FunctionalInterface
    interface Query {
        String text();
    }

    /**
     * A table that a statement may name. The set is closed, so a caller selects one of these constants and a free-form
     * string never becomes part of the SQL that reaches the database.
     */
    enum TableRef {
        PLATFORM(SosaSchema.PLATFORM),
        FEATURE(SosaSchema.FEATURE),
        PROPERTY(SosaSchema.PROPERTY),
        PROCEDURE(SosaSchema.PROCEDURE),
        SYSTEM(SosaSchema.SYSTEM),
        SENSOR(SosaSchema.SENSOR);

        private final String name;

        TableRef(String name) {
            this.name = name;
        }

        /** {@code SELECT id FROM <table> WHERE uri = ?}. */
        Query selectIdByUri() {
            return () -> "SELECT id FROM " + name + " WHERE uri = ?";
        }

        /** {@code UPDATE <table> SET label = ? WHERE id = ?}. */
        Query updateLabelById() {
            return () -> "UPDATE " + name + " SET label = ? WHERE id = ?";
        }

        /** An insert of exactly these columns that leaves an existing row alone. */
        Query insertIfAbsent(SqlDialect dialect, List<String> columns) {
            return () -> dialect.insertIfAbsent(name, columns);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** The statements that are not per-table; a caller passes one of these instead of a free-form string. */
    enum Statement implements Query {
        COUNT_SCHEMA_VERSION("SELECT COUNT(*) FROM " + VERSION_TABLE),
        INSERT_SCHEMA_VERSION("INSERT INTO " + VERSION_TABLE + " (version, applied_at) VALUES (?, ?)"),
        UPDATE_PROPERTY("UPDATE " + PROPERTY + " SET unit = ?, feature_of_interest_id = ? WHERE id = ?"),
        UPDATE_PROCEDURE("UPDATE " + PROCEDURE + " SET sampling_interval_s = ? WHERE id = ?"),
        UPDATE_SENSOR("UPDATE " + SENSOR + " SET platform_id = ?, observed_property_id = ?, procedure_id = ? WHERE system_id = ?");

        private final String text;

        Statement(String text) {
            this.text = text;
        }

        @Override
        public String text() {
            return text;
        }
    }

    /** A column. {@code type} is {@code ID} for the generated surrogate key; {@code term} is the ontology term it maps to. */
    record Column(String name, String type, boolean notNull, String term) {
    }

    /** A foreign key; {@code term} is the relation it embodies. */
    record ForeignKey(String name, String column, String table, String target, String term) {
    }

    record Table(String name, String term, List<Column> columns, List<String> primaryKey, List<String> unique,
                 List<ForeignKey> foreignKeys) {
    }

    private static Column id() {
        return new Column("id", "ID", true, MCD + "identifier");
    }

    private static Column uri(String term) {
        return new Column("uri", "VARCHAR(255)", true, term);
    }

    private static Column label() {
        return new Column("label", "VARCHAR(255)", false, RDFS + "label");
    }

    static List<Table> tables() {
        List<Table> t = new ArrayList<>();
        t.add(new Table(VERSION_TABLE, MCD + "schemaVersion", List.of(
                new Column("version", "INTEGER", true, MCD + "schemaVersion"),
                new Column("applied_at", "BIGINT", true, "http://purl.org/dc/terms/issued")),
                List.of("version"), List.of(), List.of()));
        t.add(new Table(PLATFORM, SOSA + "Platform", List.of(id(), uri("@id of the sosa:Platform: the Minecraft server"), label()),
                null, List.of("uri"), List.of()));
        t.add(new Table(FEATURE, SOSA + "FeatureOfInterest", List.of(id(), uri("@id of the sosa:FeatureOfInterest: the server being observed"), label()),
                null, List.of("uri"), List.of()));
        t.add(new Table(PROPERTY, SOSA + "ObservableProperty", List.of(id(), uri("@id of the sosa:ObservableProperty"), label(),
                new Column("unit", "VARCHAR(32)", false, "http://qudt.org/schema/qudt/unit"),
                new Column("feature_of_interest_id", "BIGINT", true, SSN + "isPropertyOf")),
                null, List.of("uri"), List.of(new ForeignKey("fk_mcd_property_feature", "feature_of_interest_id", FEATURE, "id", SSN + "isPropertyOf"))));
        t.add(new Table(PROCEDURE, SOSA + "Procedure", List.of(id(), uri("@id of the sosa:Procedure"), label(),
                new Column("sampling_interval_s", "INTEGER", false, MCD + "samplingInterval")),
                null, List.of("uri"), List.of()));
        t.add(new Table(SYSTEM, SSN + "System", List.of(id(), uri("@id of the ssn:System"), label()),
                null, List.of("uri"), List.of()));
        t.add(new Table(SENSOR, SOSA + "Sensor", List.of(
                new Column("system_id", "BIGINT", true, "@id of the ssn:System this sosa:Sensor is"),
                new Column("platform_id", "BIGINT", true, SOSA + "isHostedBy"),
                new Column("observed_property_id", "BIGINT", true, SOSA + "observes"),
                new Column("procedure_id", "BIGINT", true, SSN + "implements")),
                List.of("system_id"), List.of(), List.of(
                new ForeignKey("fk_mcd_sensor_system", "system_id", SYSTEM, "id", RDFS + "subClassOf " + SSN + "System"),
                new ForeignKey("fk_mcd_sensor_platform", "platform_id", PLATFORM, "id", SOSA + "isHostedBy"),
                new ForeignKey("fk_mcd_sensor_property", "observed_property_id", PROPERTY, "id", SOSA + "observes"),
                new ForeignKey("fk_mcd_sensor_procedure", "procedure_id", PROCEDURE, "id", SSN + "implements"))));
        t.add(new Table(OBSERVATION, SOSA + "Observation", List.of(
                new Column("sensor_id", "BIGINT", true, SOSA + "madeBySensor"),
                new Column("result_time", "BIGINT", true, SOSA + "resultTime (unix milliseconds; also the sosa:phenomenonTime)"),
                new Column("result_numeric", "DOUBLE PRECISION", false, SOSA + "hasSimpleResult (a number)"),
                new Column("result_text", "VARCHAR(255)", false, SOSA + "hasSimpleResult (anything that is not a number)")),
                List.of("sensor_id", "result_time"), List.of(), List.of(
                new ForeignKey("fk_mcd_observation_sensor", "sensor_id", SENSOR, "system_id", SOSA + "madeBySensor"))));
        return t;
    }

    /** CREATE TABLE statements, in dependency order, for the dialect. */
    static List<String> create(SqlDialect d) {
        List<String> out = new ArrayList<>();
        for (Table t : tables()) {
            List<String> parts = new ArrayList<>();
            for (Column c : t.columns()) {
                String type = c.type().equals("ID") ? d.identity() : c.type() + (c.notNull() ? " NOT NULL" : "");
                parts.add(c.name() + " " + type + (d.inlineComments() ? " COMMENT '" + c.term() + "'" : ""));
            }
            if (t.primaryKey() != null) parts.add("PRIMARY KEY (" + String.join(", ", t.primaryKey()) + ")");
            for (String u : t.unique()) parts.add("CONSTRAINT uq_" + t.name() + "_" + u + " UNIQUE (" + u + ")");
            for (ForeignKey f : t.foreignKeys()) {
                parts.add("CONSTRAINT " + f.name() + " FOREIGN KEY (" + f.column() + ") REFERENCES " + f.table() + " (" + f.target() + ")");
            }
            out.add("CREATE TABLE IF NOT EXISTS " + t.name() + " (\n  " + String.join(",\n  ", parts) + "\n)"
                    + d.tableOptions() + (d.inlineComments() ? " COMMENT='" + t.term() + "'" : ""));
        }
        return out;
    }

    /** PostgreSQL's {@code COMMENT ON} statements for every table, column and foreign key; empty for other dialects. */
    static List<String> comments(SqlDialect d) {
        List<String> out = new ArrayList<>();
        if (!d.commentOn()) return out;
        for (Table t : tables()) {
            out.add("COMMENT ON TABLE " + t.name() + " IS '" + t.term() + "'");
            for (Column c : t.columns()) out.add("COMMENT ON COLUMN " + t.name() + "." + c.name() + " IS '" + c.term() + "'");
            for (ForeignKey f : t.foreignKeys()) {
                out.add("COMMENT ON CONSTRAINT " + f.name() + " ON " + t.name() + " IS '" + f.term() + "'");
            }
        }
        return out;
    }
}
