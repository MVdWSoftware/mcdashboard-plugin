/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.push;

import org.mcdashboard.json.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The push bindings of the paired devices, persisted in {@code push.json}. It is a separate file on purpose: {@code devices.json}
 * keeps its 1.0 shape, so a server can move between plugin versions without losing or confusing either file.
 */
public final class PushStore {
    private final Map<String, PushBinding> bindings = new LinkedHashMap<>();
    private final Path file;

    /**
     * Creates the store and reads {@code file} when it exists.
     *
     * @param file The JSON file the bindings are persisted in.
     */
    public PushStore(Path file) {
        this.file = file;
        load();
    }

    /**
     * Stores (or replaces) the binding of a device.
     *
     * @param deviceId The paired device.
     * @param binding  Its binding.
     * @return The binding it replaced, or empty when the device had none.
     */
    public synchronized Optional<PushBinding> put(String deviceId, PushBinding binding) {
        PushBinding old = bindings.put(deviceId, binding);
        save();
        return Optional.ofNullable(old);
    }

    /**
     * The binding of a device.
     *
     * @param deviceId The paired device.
     * @return Its binding, or empty when it did not enable push.
     */
    public synchronized Optional<PushBinding> get(String deviceId) {
        return Optional.ofNullable(bindings.get(deviceId));
    }

    /**
     * Removes the binding of a device.
     *
     * @param deviceId The paired device.
     * @return The removed binding, or empty when there was none.
     */
    public synchronized Optional<PushBinding> remove(String deviceId) {
        PushBinding old = bindings.remove(deviceId);
        if (old != null) save();
        return Optional.ofNullable(old);
    }

    /**
     * Removes the binding that uses a route (the portal said the route is gone).
     *
     * @param routeToken The route.
     * @return True when a binding was removed.
     */
    public synchronized boolean removeRoute(String routeToken) {
        boolean removed = bindings.values().removeIf(b -> b.routeToken().equals(routeToken));
        if (removed) save();
        return removed;
    }

    /**
     * A copy of every binding, keyed by device id.
     *
     * @return The bindings.
     */
    public synchronized Map<String, PushBinding> all() {
        return new LinkedHashMap<>(bindings);
    }

    @SuppressWarnings("unchecked")
    private void load() {
        try {
            if (!Files.exists(file) || Files.size(file) == 0) return;
            Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            for (Object o : (List<Object>) root.getOrDefault("bindings", List.of())) {
                Map<String, Object> m = (Map<String, Object>) o;
                try {
                    bindings.put((String) m.get("device"), PushBinding.parse(m));
                } catch (IllegalArgumentException | ClassCastException ignored) {
                    // a damaged entry is dropped; the device re-enables push the next time it opens the app
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Could not read " + file + ": " + e.getMessage(), e);
        }
    }

    private void save() {
        List<Object> list = new ArrayList<>();
        bindings.forEach((device, b) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("device", device);
            m.put("routeToken", b.routeToken());
            m.put("publicKey", b.publicKey());
            m.put("rules", b.rules().stream().map(PushRule::toJson).toList());
            list.add(m);
        });
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(Map.of("bindings", list)), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write " + file, e);
        }
    }
}
