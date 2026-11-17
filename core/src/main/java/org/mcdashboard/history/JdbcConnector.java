/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.history;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.ServiceLoader;

/**
 * Opens JDBC connections without shipping a driver. The server already carries SQLite and MySQL drivers; any other
 * (PostgreSQL, MariaDB's own) is a jar the owner drops into {@code plugins/MCDashboard/drivers/}, loaded here.
 */
public final class JdbcConnector implements JdbcHistoryStore.ConnectionSource {
    private final String url;
    private final String user;
    private final String password;
    private final Path driversDir;
    private Driver external;

    public JdbcConnector(String url, String user, String password, Path driversDir) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.driversDir = driversDir;
    }

    @Override
    public Connection open() throws SQLException {
        Properties props = new Properties();
        if (user != null && !user.isEmpty()) props.setProperty("user", user);
        if (password != null && !password.isEmpty()) props.setProperty("password", password);
        try {
            DriverManager.getDriver(url);
            return DriverManager.getConnection(url, props);
        } catch (SQLException noBuiltIn) {
            Driver d = externalDriver();
            if (d == null) {
                throw new SQLException("no JDBC driver for " + scheme() + ": put the driver jar in " + driversDir, noBuiltIn);
            }
            Connection c = d.connect(url, props);
            if (c == null) throw new SQLException("the driver in " + driversDir + " does not accept " + scheme());
            return c;
        }
    }

    private String scheme() {
        int i = url.indexOf("://");
        return i > 0 ? url.substring(0, i) : url.substring(0, Math.min(url.length(), 20));
    }

    private synchronized Driver externalDriver() {
        if (external != null) return external;
        File[] jars = driversDir == null ? null : driversDir.toFile().listFiles((dir, n) -> n.endsWith(".jar"));
        if (jars == null || jars.length == 0) return null;
        try {
            List<URL> urls = new ArrayList<>();
            for (File j : jars) urls.add(j.toURI().toURL());
            URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), JdbcConnector.class.getClassLoader());
            for (Driver d : ServiceLoader.load(Driver.class, loader)) {
                if (d.acceptsURL(url)) {
                    external = d;
                    return d;
                }
            }
        } catch (Exception | LinkageError e) {
            return null;
        }
        return null;
    }
}
