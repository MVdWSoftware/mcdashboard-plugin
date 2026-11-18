/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import org.mcdashboard.config.ConfigView;
import org.mcdashboard.config.YamlCodec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** The files in {@code plugins/MCDashboard/}: the defaults it starts from, the signing key and the developer switch. */
final class DataFolder {
    private final Path dir;
    private final YamlCodec yaml;
    private final Consumer<String> warn;
    private final String defaults;

    private final java.util.List<String> dashboards;

    DataFolder(Path dir, YamlCodec yaml, String defaultsFolder, java.util.List<String> dashboards, Consumer<String> warn) {
        this.dir = dir;
        this.dashboards = dashboards;
        this.defaults = defaultsFolder;
        this.yaml = yaml;
        this.warn = warn;
    }

    Path dir() {
        return dir;
    }

    Path resolve(String name) {
        return dir.resolve(name);
    }

    /** Writes the default files that are missing. The preset dashboards only on the very first start, so a deleted preset stays deleted. */
    void exportDefaults() throws IOException {
        Files.createDirectories(dir);
        if (!Files.exists(resolve("config.yml"))) {
            export("config.yml");
            appendPlatformConfig();
        }
        exportIfMissing("placeholders.yml");
        if (!Files.exists(resolve("dashboards"))) {
            for (String name : dashboards) export("dashboards/" + name + ".yml");
        }
    }

    /** {@code config.yml} on top of the defaults bundled in the jar, so a key an older file lacks still has its documented default. */
    ConfigView config() {
        Map<String, Object> defaults = bundled("config.yml");
        Map<String, Object> mine = read(resolve("config.yml"));
        return ConfigView.of(ConfigView.merge(defaults, mine));
    }

    ConfigView placeholders() {
        return ConfigView.of(read(resolve("placeholders.yml")));
    }

    /** The token signing key, generated on the first start and readable by the server user only. */
    byte[] secret() throws IOException {
        Path file = resolve("secret.key");
        if (Files.exists(file)) return Base64.getDecoder().decode(Files.readString(file).trim());
        byte[] key = new byte[48];
        new SecureRandom().nextBytes(key);
        Files.createDirectories(file.getParent());
        Files.writeString(file, Base64.getEncoder().encodeToString(key), StandardOpenOption.CREATE_NEW);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system
        }
        return key;
    }

    /** The switch {@code /mcdashboard developer} stored in {@code developer.yml}; it wins over {@code config.yml}. */
    boolean developerMode(boolean configured) {
        Path state = resolve("developer.yml");
        if (!Files.isRegularFile(state)) return configured;
        try {
            return ConfigView.of(yaml.parse(Files.readString(state, StandardCharsets.UTF_8))).getBoolean("enabled", configured);
        } catch (IOException | RuntimeException e) {
            warn.accept("Could not read developer.yml: " + e.getMessage());
            return configured;
        }
    }

    /** Writes {@code developer.yml} rather than {@code config.yml}, so the owner's comments in the config survive. */
    void saveDeveloperMode(boolean enabled) throws IOException {
        Path file = resolve("developer.yml");
        Files.createDirectories(file.getParent());
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("enabled", enabled);
        Files.writeString(file, "# Managed by /mcdashboard developer. Overrides developer.enabled in config.yml.\n"
                + yaml.dump(state), StandardCharsets.UTF_8);
    }

    private Map<String, Object> read(Path file) {
        if (!Files.isRegularFile(file)) return Map.of();
        try {
            return yaml.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            warn.accept("Could not read " + dir.relativize(file) + ": " + e.getMessage());
            return Map.of();
        }
    }

    /** The bundled file: the platform's own version when it has one, else the shared default. */
    private InputStream bundledStream(String name) {
        InputStream own = defaults.isEmpty() ? null : DataFolder.class.getResourceAsStream("/" + defaults + name);
        return own != null ? own : DataFolder.class.getResourceAsStream("/" + name);
    }

    private Map<String, Object> bundled(String name) {
        try (InputStream in = bundledStream(name)) {
            return in == null ? Map.of() : yaml.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            warn.accept("Could not read the bundled " + name + ": " + e.getMessage());
            return Map.of();
        }
    }

    /** A platform's own settings ({@code config-append.yml} in its defaults folder) go below the shared ones in a new config.yml. */
    private void appendPlatformConfig() throws IOException {
        InputStream in = defaults.isEmpty() ? null : DataFolder.class.getResourceAsStream("/" + defaults + "config-append.yml");
        if (in == null) return;
        try (in) {
            Files.writeString(resolve("config.yml"), "\n" + new String(in.readAllBytes(), StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }
    }

    private void exportIfMissing(String name) throws IOException {
        if (!Files.exists(resolve(name))) export(name);
    }

    private void export(String name) throws IOException {
        try (InputStream in = bundledStream(name)) {
            if (in == null) throw new IOException("the bundled " + name + " is missing");
            Path target = resolve(name);
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
