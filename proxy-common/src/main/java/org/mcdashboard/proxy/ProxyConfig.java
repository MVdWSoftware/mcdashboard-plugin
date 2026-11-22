/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import org.mcdashboard.config.ConfigView;
import org.mcdashboard.config.YamlCodec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The {@code network:} section of {@code config.yml}: how often servers are checked and which backends are federated.
 *
 * @param pingIntervalSeconds How often to check backend servers, in seconds.
 * @param federationIntervalSeconds How often to refresh federation data, in seconds.
 * @param backends The list of configured backend servers.
 */
public record ProxyConfig(int pingIntervalSeconds, int federationIntervalSeconds, List<BackendConfig> backends) {
    public static final ProxyConfig DEFAULTS = new ProxyConfig(15, 15, List.of());

    /**
     * Reads {@code dataDir/config.yml}; a missing or unreadable file gives the defaults (federation off).
     *
     * @param dataDir The plugin's data directory path.
     * @param yaml The YAML codec to parse with.
     * @param warn Where to report warnings about read failures.
     * @return The loaded configuration, or defaults if unavailable.
     */
    public static ProxyConfig load(Path dataDir, YamlCodec yaml, Consumer<String> warn) {
        Path file = dataDir.resolve("config.yml");
        if (!Files.isRegularFile(file)) return DEFAULTS;
        try {
            ConfigView cfg = ConfigView.of(yaml.parse(Files.readString(file, StandardCharsets.UTF_8)));
            List<Map<?, ?>> backends = cfg.getMapList("network.backends");
            return new ProxyConfig(clamp(cfg.getInt("network.ping-interval", 15)), clamp(cfg.getInt("network.federation-interval", 15)),
                    BackendConfig.parseAll(backends, warn));
        } catch (IOException | RuntimeException e) {
            warn.accept("Could not read the network section of config.yml: " + e.getMessage());
            return DEFAULTS;
        }
    }

    private static int clamp(int seconds) {
        return Math.max(5, Math.min(seconds, 3600));
    }
}
