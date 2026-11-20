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
     * {@code entitlement} is what the app says it runs under ({@link #ENTITLEMENTS}), null while it has not said. It is
     * self-reported by the app, informational only and never used to decide anything.
     */
    public record Device(String id, String playerUuid, String playerName, String label, long serial, long createdMs,
                         String client, String entitlement) {
    }

    /** The values a device may report: the server's licence, the app unlock bought on the device, or neither. */
    public static final List<String> ENTITLEMENTS = List.of("server", "app", "free");

    /** The value if it is one of {@link #ENTITLEMENTS} (case and surrounding space ignored), else null. */
    public static String parseEntitlement(String raw) {
        if (raw == null) return null;
        String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
        return ENTITLEMENTS.contains(v) ? v : null;
    }

    private final Map<String, Device> devices = new LinkedHashMap<>();
    /** When each device last made an authenticated request. Kept in memory and written with the next save, not on every request. */
    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    /** The entitlement each device reported, mirrored here so counting needs no lock. */
    private final Map<String, String> reportedEntitlements = new ConcurrentHashMap<>();
    /** The refresh generation each device had just before its last rotation, and when it rotated. */
    private final Map<String, long[]> rotated = new ConcurrentHashMap<>();
    /** How long the previous refresh token stays valid after a rotation, so a response lost on the way does not unpair the device. */
    private static final long ROTATION_GRACE_MS = 30_000;
    private final List<Consumer<Device>> removalListeners = new CopyOnWriteArrayList<>();
    /** The number of paired devices, readable without taking the store's lock (which is held while it writes the file). */
    private volatile int count;
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
        Device d = new Device(id, playerUuid, playerName, label == null ? "Device" : label, 1, System.currentTimeMillis(), client, null);
        devices.put(id, d);
        count = devices.size();
        save();
        return Optional.of(d);
    }

    /** Records that the device just made an authenticated request. */
    public void touch(String id) {
        if (id != null) seen.put(id, System.currentTimeMillis());
    }

    /**
     * Records what the device says it runs under. Anything but {@link #ENTITLEMENTS} is ignored. The file is written only
     * when the value changed, which is rare, not on every request. Self-reported: informational, never a basis for a decision.
     */
    public void entitle(String id, String raw) {
        String value = parseEntitlement(raw);
        if (id == null || value == null || value.equals(reportedEntitlements.get(id))) return;
        synchronized (this) {
            Device d = devices.get(id);
            if (d == null) return;
            devices.put(id, new Device(d.id(), d.playerUuid(), d.playerName(), d.label(), d.serial(), d.createdMs(), d.client(), value));
            reportedEntitlements.put(id, value);
            save();
        }
    }

    /** How many devices reported {@code entitlement} ({@code server}, {@code app} or {@code free}). Lock free and in memory. */
    public int countEntitled(String entitlement) {
        int n = 0;
        for (String v : reportedEntitlements.values()) if (v.equals(entitlement)) n++;
        return n;
    }

    /** When the device was last seen (epoch ms), or empty when it has not been since it paired (or before the plugin recorded it). */
    public Optional<Long> lastSeen(String id) {
        return Optional.ofNullable(seen.get(id));
    }

    /** How many devices are paired. Lock free and in memory: safe on any thread, including the server's main thread. */
    public int count() {
        return count;
    }

    /** How many paired devices made a request at or after {@code sinceMs} (epoch ms). Lock free and in memory. */
    public long countSeenSince(long sinceMs) {
        long n = 0;
        for (long t : seen.values()) if (t >= sinceMs) n++;
        return n;
    }

    public synchronized Optional<Device> get(String id) {
        return Optional.ofNullable(devices.get(id));
    }

    /**
     * Rotates the refresh token generation; the previous refresh token stops working after a short grace period, so a
     * client whose answer got lost can retry with the token it still has.
     */
    public synchronized Optional<Device> rotate(String id, long expectedSerial) {
        Device d = devices.get(id);
        if (d == null) return Optional.empty();
        if (d.serial() != expectedSerial) {
            long[] prev = rotated.get(id);
            boolean retry = prev != null && prev[0] == expectedSerial && System.currentTimeMillis() - prev[1] <= ROTATION_GRACE_MS;
            return retry ? Optional.of(d) : Optional.empty();
        }
        rotated.put(id, new long[]{expectedSerial, System.currentTimeMillis()});
        Device n = new Device(d.id(), d.playerUuid(), d.playerName(), d.label(), d.serial() + 1, d.createdMs(), d.client(), d.entitlement());
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
        reportedEntitlements.remove(id);
        rotated.remove(id);
        count = devices.size();
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
                        ((Number) m.get("created")).longValue(), (String) m.get("client"),
                        parseEntitlement((String) m.get("entitlement")));
                devices.put(d.id(), d);
                if (d.entitlement() != null) reportedEntitlements.put(d.id(), d.entitlement());
                if (m.get("seen") instanceof Number n) seen.put(d.id(), n.longValue());
            }
            count = devices.size();
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
            if (d.entitlement() != null) m.put("entitlement", d.entitlement());
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
