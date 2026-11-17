/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.auth;

import org.mcdashboard.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Paired devices, persisted as JSON in {@code devices.json}. */
public final class DeviceStore {
    /**
     * A paired device. {@code serial} is the current refresh-token generation, {@code client} the User-Agent it paired
     * with (e.g. {@code MCDashboard/1.2.3 (android)}), null for devices paired before the plugin recorded it.
     */
    public record Device(String id, String playerUuid, String playerName, String label, long serial, long createdMs,
                         String client) {
    }

    private final Map<String, Device> devices = new LinkedHashMap<>();
    /** When each device last made an authenticated request. Kept in memory and written with the next save, not on every request. */
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final List<Consumer<Device>> removalListeners = new CopyOnWriteArrayList<>();
    private final Path file;
    private final SecureRandom random = new SecureRandom();

    public DeviceStore(Path file) {
        this.file = file;
        load();
    }

    /** Registers a device for the player; fails when the player is at the device limit. */
    public Optional<Device> add(String playerUuid, String playerName, String label, int maxDevices) {
        return add(playerUuid, playerName, label, maxDevices, null);
    }

    /** Registers a device for the player and remembers which client it paired with. */
    public synchronized Optional<Device> add(String playerUuid, String playerName, String label, int maxDevices, String client) {
        if (maxDevices >= 0 && devicesOf(playerUuid).size() >= maxDevices) return Optional.empty();
        byte[] raw = new byte[12];
        random.nextBytes(raw);
        String id = HexFormat.of().formatHex(raw);
        Device d = new Device(id, playerUuid, playerName, label == null ? "Device" : label, 1, System.currentTimeMillis(), client);
        devices.put(id, d);
        save();
        return Optional.of(d);
    }

    /** Records that the device just made an authenticated request. */
    public void touch(String id) {
        if (id != null) seen.put(id, System.currentTimeMillis());
    }

    /** When the device was last seen (epoch ms), or empty when it has not been since it paired (or before the plugin recorded it). */
    public Optional<Long> lastSeen(String id) {
        return Optional.ofNullable(seen.get(id));
    }

    public synchronized Optional<Device> get(String id) {
        return Optional.ofNullable(devices.get(id));
    }

    /** Rotates the refresh token generation; the previous refresh token stops working. */
    public synchronized Optional<Device> rotate(String id, long expectedSerial) {
        Device d = devices.get(id);
        if (d == null || d.serial() != expectedSerial) return Optional.empty();
        Device n = new Device(d.id(), d.playerUuid(), d.playerName(), d.label(), d.serial() + 1, d.createdMs(), d.client());
        devices.put(id, n);
        save();
        return Optional.of(n);
    }

    /** Called after a device was revoked, whoever did it (command, the device itself); must not block. */
    public void onRemoved(Consumer<Device> listener) {
        removalListeners.add(listener);
    }

    public synchronized boolean remove(String id) {
        Device d = devices.remove(id);
        if (d == null) return false;
        seen.remove(id);
        save();
        for (var l : removalListeners) {
            try {
                l.accept(d);
            } catch (RuntimeException ignored) {
                // a failing listener must not undo the revocation
            }
        }
        return true;
    }

    public synchronized List<Device> devicesOf(String playerUuid) {
        List<Device> out = new ArrayList<>();
        for (Device d : devices.values()) if (d.playerUuid().equals(playerUuid)) out.add(d);
        return out;
    }

    @SuppressWarnings("unchecked")
    private void load() {
        try {
            if (!Files.exists(file) || Files.size(file) == 0) return;
            Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            for (Object o : (List<Object>) root.getOrDefault("devices", List.of())) {
                Map<String, Object> m = (Map<String, Object>) o;
                Device d = new Device((String) m.get("id"), (String) m.get("player"), (String) m.get("name"),
                        (String) m.get("label"), ((Number) m.get("serial")).longValue(),
                        ((Number) m.get("created")).longValue(), (String) m.get("client"));
                devices.put(d.id(), d);
                if (m.get("seen") instanceof Number n) seen.put(d.id(), n.longValue());
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not read " + file + ": " + e.getMessage(), e);
        }
    }

    private void save() {
        List<Object> list = new ArrayList<>();
        for (Device d : devices.values()) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", d.id());
            m.put("player", d.playerUuid());
            m.put("name", d.playerName());
            m.put("label", d.label());
            m.put("serial", d.serial());
            m.put("created", d.createdMs());
            if (d.client() != null) m.put("client", d.client());
            Long last = seen.get(d.id());
            if (last != null) m.put("seen", last);
            list.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("devices", list);
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write " + file, e);
        }
    }
}
