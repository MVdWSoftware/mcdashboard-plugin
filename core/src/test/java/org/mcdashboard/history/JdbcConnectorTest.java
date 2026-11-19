/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.history;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JdbcConnectorTest {
    @TempDir
    Path dir;

    @Test
    void opensWithABuiltInDriverAndPassesCredentials() throws Exception {
        try (Connection c = new JdbcConnector("jdbc:sqlite:" + dir.resolve("a.db"), "u", "p", dir).open()) {
            assertFalse(c.isClosed());
        }
        try (Connection c = new JdbcConnector("jdbc:sqlite:" + dir.resolve("b.db"), "", "", null).open()) {
            assertFalse(c.isClosed());
        }
        try (Connection c = new JdbcConnector("jdbc:sqlite:" + dir.resolve("c.db"), null, null, dir).open()) {
            assertFalse(c.isClosed());
        }
    }

    @Test
    void explainsWhereToPutAMissingDriver() {
        var e = assertThrows(SQLException.class, () -> new JdbcConnector("jdbc:nosuch://h/db", null, null, dir).open());
        assertTrue(e.getMessage().contains("no JDBC driver for jdbc:nosuch"), e.getMessage());
        e = assertThrows(SQLException.class, () -> new JdbcConnector("jdbc:nosuch", null, null, null).open());
        assertTrue(e.getMessage().contains("jdbc:nosuch"));
        e = assertThrows(SQLException.class, () -> new JdbcConnector("jdbc:a-very-long-scheme-without-slashes", null, null, dir.resolve("missing")).open());
        assertTrue(e.getMessage().contains("jdbc:a-very-long-sch"));
    }

    @Test
    void ignoresDriverJarsThatAreBroken() throws Exception {
        Files.writeString(dir.resolve("broken.jar"), "not a jar");
        Files.writeString(dir.resolve("note.txt"), "x");
        assertThrows(SQLException.class, () -> new JdbcConnector("jdbc:nosuch://h/db", null, null, dir).open());
    }
}
