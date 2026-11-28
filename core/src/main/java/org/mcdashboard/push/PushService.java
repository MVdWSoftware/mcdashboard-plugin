/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.push;

import org.mcdashboard.json.Json;
import org.mcdashboard.platform.Log;
import org.mcdashboard.util.Threads;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Push notifications for the paired devices: keeps their bindings, watches their alert rules and hands the notifications that
 * fire to the portal, encrypted for each device. Everything is optional: with push off, without a licence or while the
 * portal refuses it, {@link #available()} is false, the server does not advertise {@code push} and nothing is sent.
 */
public final class PushService implements AutoCloseable {
    /** Where a rule's placeholder is read from. */
    @FunctionalInterface
    public interface Values {
        /**
         * Reads a numeric placeholder for a player, honouring the placeholder's permission.
         *
         * @param placeholder The placeholder id.
         * @param playerUuid  The device's player.
         * @return The value, or empty when it is unknown, not readable by the player or not a number.
         */
        OptionalDouble read(String placeholder, String playerUuid);
    }

    /**
     * The placeholder values the rules watch: numeric, resolved for the device's player and only when that player may read
     * the placeholder, exactly as {@code /v1/values} decides.
     *
     * @param placeholders The placeholder service.
     * @param permissions  The permission snapshots.
     * @return A source for {@link PushService}.
     */
    public static Values values(org.mcdashboard.placeholder.PlaceholderService placeholders, org.mcdashboard.auth.PermissionCache permissions) {
        return (id, player) -> placeholders.definition(id).filter(d -> d.readableBy(player, permissions))
                .flatMap(d -> placeholders.read(id, player)).map(v -> number(v.value())).orElse(OptionalDouble.empty());
    }

    /**
     * A placeholder's text as a number.
     *
     * @param text The resolved text, for example {@code 19.8} or {@code 19,8}.
     * @return The number, or empty when the text is not a finite number.
     */
    public static OptionalDouble number(String text) {
        try {
            double v = Double.parseDouble(text.trim().replace(',', '.'));
            return Double.isFinite(v) ? OptionalDouble.of(v) : OptionalDouble.empty();
        } catch (NumberFormatException | NullPointerException e) {
            return OptionalDouble.empty();
        }
    }

    private static final int TTL_SECONDS = 3600;
    private static final int BATCH = 50;
    private static final long REFUSAL_MS = 10 * 60_000L;

    private final PushStore store;
    private final PushClient client;
    private final BooleanSupplier licensed;
    private final boolean enabled;
    private final String serverName;
    private final String licenseHash;
    private final Function<String, Optional<String>> playerOf;
    private final Values values;
    private final Clock clock;
    private final Log log;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(Threads.daemon("MCDashboard-push"));
    /** Per device and rule: when the condition started to hold (0 when it does not), when the rule last fired and whether it fired in this episode. */
    private final Map<String, long[]> state = new java.util.concurrent.ConcurrentHashMap<>();
    /** Until when a route is left alone because the portal asked to slow down. */
    private final Map<String, Long> backoff = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long refusedUntil;
    private volatile String lastProblem;

    /**
     * Creates the service.
     *
     * @param store       The bindings.
     * @param client      The portal client.
     * @param licensed    Whether the last licence heartbeat said this server is licensed.
     * @param enabled     {@code push.enabled}.
     * @param serverName  The name for the {@code {server}} token.
     * @param licenseHash The licence hash, bound into every message, or null.
     * @param playerOf    Resolves a device id to its player's UUID (empty when the device is gone).
     * @param values      Reads the placeholders the rules watch.
     * @param clock       The clock.
     * @param log         Where problems are logged.
     */
    public PushService(PushStore store, PushClient client, BooleanSupplier licensed, boolean enabled, String serverName,
                       String licenseHash, Function<String, Optional<String>> playerOf, Values values, Clock clock, Log log) {
        this.store = store;
        this.client = client;
        this.licensed = licensed;
        this.enabled = enabled;
        this.serverName = serverName;
        this.licenseHash = licenseHash;
        this.playerOf = playerOf;
        this.values = values;
        this.clock = clock;
        this.log = log;
    }

    /**
     * Whether push can be used right now: enabled, licensed, configured and not refused by the portal.
     *
     * @return True when the server may advertise {@code push}.
     */
    public boolean available() {
        return enabled && client.configured() && licensed.getAsBoolean() && clock.millis() >= refusedUntil;
    }

    /**
     * The binding of a device.
     *
     * @param deviceId The device.
     * @return Its binding, or empty.
     */
    public Optional<PushBinding> binding(String deviceId) {
        return store.get(deviceId);
    }

    /**
     * Saves a device's binding. A route the device replaced is revoked at the portal.
     *
     * @param deviceId The device.
     * @param binding  Its new binding.
     */
    public void bind(String deviceId, PushBinding binding) {
        Optional<PushBinding> old = store.put(deviceId, binding);
        old.filter(o -> !o.routeToken().equals(binding.routeToken())).ifPresent(o -> revoke(o.routeToken()));
        forget(deviceId);
    }

    /**
     * Turns push off for a device and revokes its route.
     *
     * @param deviceId The device.
     * @return True when it had a binding.
     */
    public boolean unbind(String deviceId) {
        Optional<PushBinding> old = store.remove(deviceId);
        old.ifPresent(o -> revoke(o.routeToken()));
        forget(deviceId);
        return old.isPresent();
    }

    /**
     * Looks at every rule once. Call it from a background thread every few seconds; the network work happens on the
     * service's own thread, never on the caller's.
     */
    public void evaluate() {
        if (!available()) return;
        long now = clock.millis();
        Map<String, PushBinding> bindings = store.all();
        List<Pending> due = new ArrayList<>();
        for (var entry : bindings.entrySet()) {
            Optional<String> player = playerOf.apply(entry.getKey());
            if (player.isEmpty() || entry.getValue().rules().isEmpty()) continue;
            for (PushRule rule : entry.getValue().rules()) {
                OptionalDouble value = values.read(rule.placeholder(), player.get());
                long[] s = state.computeIfAbsent(entry.getKey() + '|' + rule.id(), k -> new long[3]);
                if (value.isEmpty() || !rule.matches(value.getAsDouble())) {
                    s[0] = 0;
                    s[2] = 0;
                    continue;
                }
                if (s[0] == 0) s[0] = now;
                boolean held = now - s[0] >= rule.forSeconds() * 1000L;
                boolean quiet = s[1] != 0 && now - s[1] < rule.cooldownSeconds() * 1000L;
                // Fires once per episode: the condition has to stop holding before the rule can fire again.
                if (held && !quiet && s[2] == 0) {
                    s[1] = now;
                    s[2] = 1;
                    due.add(new Pending(entry.getKey(), entry.getValue(), rule, format(value.getAsDouble())));
                }
            }
        }
        if (!due.isEmpty()) dispatch(due);
    }

    private record Pending(String deviceId, PushBinding binding, PushRule rule, String value) {
    }

    private void dispatch(List<Pending> due) {
        try {
            sender.execute(() -> deliver(due));
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    private void deliver(List<Pending> due) {
        long now = clock.millis();
        List<PushClient.Message> messages = new ArrayList<>();
        for (Pending p : due) {
            Long until = backoff.get(p.binding().routeToken());
            if (until != null && until > now) continue;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", UUID.randomUUID().toString());
            payload.put("ts", now / 1000);
            payload.put("title", p.rule().render(p.rule().title(), p.value(), serverName));
            payload.put("body", p.rule().render(p.rule().body(), p.value(), serverName));
            payload.put("data", Map.of("server", serverName == null ? "" : serverName, "rule", p.rule().id()));
            try {
                Map<String, Object> envelope = PushCrypto.encrypt(p.binding().publicKey(), p.binding().routeToken(), licenseHash,
                        Json.write(payload).getBytes(StandardCharsets.UTF_8));
                messages.add(new PushClient.Message(List.of(p.binding().routeToken()), "alert", envelope,
                        p.deviceId() + ':' + p.rule().id(), TTL_SECONDS));
            } catch (IllegalArgumentException e) {
                // a stored key that no longer parses is useless: drop the binding so the app re-enables push
                store.remove(p.deviceId());
            }
        }
        for (int i = 0; i < messages.size(); i += BATCH) {
            handle(client.send(messages.subList(i, Math.min(messages.size(), i + BATCH))));
        }
    }

    private void handle(PushClient.Outcome outcome) {
        if (outcome.unavailable()) {
            refusedUntil = clock.millis() + REFUSAL_MS;
            problem(outcome.failure());
            return;
        }
        if (outcome.failure() != null) {
            problem(outcome.failure());
            return;
        }
        lastProblem = null;
        outcome.results().forEach((route, result) -> {
            switch (result.status()) {
                case "gone", "unknown_route" -> store.removeRoute(route);
                case "throttled", "muted" -> backoff.put(route, clock.millis() + Math.max(60, result.retryAfter()) * 1000L);
                case "suspended" -> refusedUntil = clock.millis() + REFUSAL_MS;
                default -> backoff.remove(route);
            }
        });
    }

    private void problem(String message) {
        if (message == null || message.equals(lastProblem)) return;
        lastProblem = message;
        log.warning("Push notifications: " + message + ".");
    }

    private void revoke(String routeToken) {
        try {
            sender.execute(() -> client.revokeRoute(routeToken));
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    private void forget(String deviceId) {
        state.keySet().removeIf(k -> k.startsWith(deviceId + '|'));
    }

    private static String format(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e12 ? Long.toString((long) value) : String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    /** Stops the sender thread. */
    @Override
    public void close() {
        sender.shutdownNow();
    }
}
