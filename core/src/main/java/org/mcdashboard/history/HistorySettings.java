/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.history;

import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.util.Text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** The {@code history:} section of {@code config.yml}: where samples go, how often they are taken and for which placeholders. */
public record HistorySettings(Storage storage, long intervalMs, int retentionDays, long flushMs,
                              List<String> include, List<String> exclude) {
    /**
     * Where samples are kept. {@code dialect} is null for memory. {@code url} overrides the address built from
     * {@code host}, {@code port} and {@code database} (or the SQLite file); it must not contain the password.
     */
    public record Storage(String type, SqlDialect dialect, String file, String host, int port, String database,
                          String user, String password, String url, String baseUri) {
        /** The JDBC URL; never contains the password. */
        public String jdbcUrl(Path dataFolder) {
            if (!url.isBlank()) return url;
            return switch (dialect) {
                case SQLITE -> "jdbc:sqlite:" + dataFolder.resolve(file);
                case MYSQL -> "jdbc:mysql://" + host + ":" + port + "/" + database;
                case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + port + "/" + database;
            };
        }
    }

    /** Samples are never taken more often than this, whatever is configured. */
    public static final long MIN_INTERVAL_MS = 10_000;
    /** How long a tracked placeholder is kept in memory when nothing says otherwise. */
    public static final int MEMORY_MINUTES = 120;
    private static final int YEAR_MINUTES = 525_600;

    /** Memory only, 30 s samples, nothing tracked beyond what {@code placeholders.yml} asks for. */
    public static HistorySettings defaults() {
        return new HistorySettings(new Storage("memory", null, "history.db", "localhost", 0, "mcdashboard", "", "", "", ""),
                30_000, 30, 10_000, List.of(), List.of());
    }

    /**
     * Reads the section (null gives the defaults). Problems are reported through {@code warn} and fall back to the
     * default; an unknown storage type means memory. {@code serverId} names this server in the stored identifiers.
     */
    public static HistorySettings parse(Map<?, ?> m, String serverId, Consumer<String> warn) {
        HistorySettings d = defaults();
        if (m == null) m = Map.of();
        Map<?, ?> st = map(m.get("storage"));
        Map<?, ?> sampling = map(m.get("sampling"));
        String type = str(st.get("type"), "memory").toLowerCase(Locale.ROOT);
        SqlDialect dialect = SqlDialect.of(type);
        if (dialect == null && !type.equals("memory")) {
            warn.accept("history.storage.type '" + type + "' is not memory, sqlite, mysql, mariadb or postgresql: keeping history in memory");
            type = "memory";
        }
        int port = dialect == SqlDialect.POSTGRESQL ? 5432 : 3306;
        String slug = str(st.get("server-id"), serverId).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "-");
        slug = Text.trim(slug, '-');
        String base = str(st.get("base-uri"), "").trim();
        Storage storage = new Storage(type, dialect, str(st.get("file"), "history.db"), str(st.get("host"), "localhost"),
                num(st.get("port"), port, 1, 65535, "history.storage.port", warn), str(st.get("database"), "mcdashboard"),
                str(st.get("user"), ""), str(st.get("password"), ""), str(st.get("url"), "").trim(),
                base.isEmpty() ? "urn:mcdashboard:" + (slug.isEmpty() ? "server" : slug) : base);
        long interval = num(sampling.get("interval"), 30, 10, 86_400, "history.sampling.interval", warn) * 1000L;
        int retention = num(m.get("retention-days"), d.retentionDays, 0, 3650, "history.retention-days", warn);
        long flush = num(sampling.get("flush-interval"), 10, 1, 3600, "history.sampling.flush-interval", warn) * 1000L;
        return new HistorySettings(storage, interval, retention, flush, strings(m.get("include")), strings(m.get("exclude")));
    }

    /** Whether samples go to a database. */
    public boolean persistent() {
        return storage.dialect() != null;
    }

    /**
     * Applies the include and exclude lists to the definitions: an included PUBLIC placeholder gets the default
     * retention when it has no {@code history} of its own, an excluded one loses its history. Everything else is untouched.
     */
    public List<PlaceholderDefinition> apply(List<PlaceholderDefinition> defs) {
        List<PlaceholderDefinition> out = new ArrayList<>(defs.size());
        for (PlaceholderDefinition p : defs) {
            boolean eligible = p.access() == PlaceholderDefinition.Access.PUBLIC && p.type() != PlaceholderDefinition.Type.IMAGE
                    && p.type() != PlaceholderDefinition.Type.LIST;
            if (!eligible || matches(exclude, p.id())) {
                out.add(p.historyMinutes() > 0 ? p.withHistoryMinutes(0) : p);
            } else if (p.historyMinutes() == 0 && matches(include, p.id())) {
                out.add(p.withHistoryMinutes(defaultMinutes()));
            } else {
                out.add(p);
            }
        }
        return out;
    }

    private int defaultMinutes() {
        if (!persistent()) return MEMORY_MINUTES;
        return retentionDays == 0 ? YEAR_MINUTES : Math.min(YEAR_MINUTES, retentionDays * 1440);
    }

    /** Time between two samples of this placeholder: its own {@code history-interval}, else the default; never below the source's refresh. */
    public long stepMs(PlaceholderDefinition d, long sourceIntervalMs) {
        long own = d.historyIntervalMs() > 0 ? d.historyIntervalMs() : intervalMs;
        return Math.max(MIN_INTERVAL_MS, Math.max(own, sourceIntervalMs));
    }

    /** How long samples of this placeholder are kept in the database (0 = forever). */
    public long retentionMs(PlaceholderDefinition d) {
        if (d.historyMinutes() > 0 && !(matches(include, d.id()) && d.historyMinutes() == defaultMinutes())) return d.historyMinutes() * 60_000L;
        return retentionDays * 86_400_000L;
    }

    /** {@code *} matches any run of characters; everything else is literal. */
    static boolean matches(List<String> globs, String id) {
        for (String glob : globs) {
            StringBuilder regex = new StringBuilder();
            for (String part : glob.split("\\*", -1)) {
                if (regex.length() > 0) regex.append(".*");
                regex.append(Pattern.quote(part));
            }
            if (id.matches(regex.toString())) return true;
        }
        return false;
    }

    private static Map<?, ?> map(Object o) {
        return o instanceof Map<?, ?> m ? m : Map.of();
    }

    private static String str(Object o, String def) {
        return o == null ? def : o.toString();
    }

    private static int num(Object o, int def, int min, int max, String key, Consumer<String> warn) {
        if (o == null) return def;
        if (o instanceof Number n && n.intValue() >= min && n.intValue() <= max) return n.intValue();
        warn.accept(key + " must be a number from " + min + " to " + max + ": using " + def);
        return def;
    }

    private static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) if (x != null && !x.toString().isBlank()) out.add(x.toString().trim());
        return List.copyOf(out);
    }
}
