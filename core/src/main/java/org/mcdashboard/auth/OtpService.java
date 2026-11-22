/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** One-time passwords a player creates in game ({@code /mcdashboard link}) to pair a device. */
public final class OtpService {
    /**
     * A pending pairing.
     *
     * @param playerUuid  Player UUID being linked.
     * @param playerName  Display name of the linking player.
     * @param expiresAtMs Expiry time in epoch milliseconds.
     */
    public record Pending(String playerUuid, String playerName, long expiresAtMs) {
    }

    /** No 0/O/1/I, so a code survives being read off a chat window. */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 8;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Clock clock;
    private final long timeoutSeconds;

    /**
     * Creates a service with the given timeout for pending codes.
     *
     * @param clock          System clock for expiry checks.
     * @param timeoutSeconds How long each code remains valid; negative means forever.
     */
    public OtpService(Clock clock, long timeoutSeconds) {
        this.clock = clock;
        this.timeoutSeconds = timeoutSeconds;
    }

    /**
     * Creates a code for the player, replacing any earlier one of that player.
     *
     * @param playerUuid Player UUID being linked.
     * @param playerName Display name of the linking player.
     * @return The generated code.
     */
    public String create(String playerUuid, String playerName) {
        purge();
        pending.values().removeIf(p -> p.playerUuid().equals(playerUuid));
        StringBuilder code = new StringBuilder();
        do {
            code.setLength(0);
            for (int i = 0; i < LENGTH; i++) code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        } while (pending.containsKey(code.toString()));
        long exp = timeoutSeconds < 0 ? Long.MAX_VALUE : clock.millis() + timeoutSeconds * 1000;
        pending.put(code.toString(), new Pending(playerUuid, playerName, exp));
        return code.toString();
    }

    /**
     * The code as shown to the player: {@code ABCD-2345}, easier to read and type.
     *
     * @param code The raw code.
     * @return The code with a hyphen after four characters.
     */
    public static String display(String code) {
        return code.length() == 8 ? code.substring(0, 4) + "-" + code.substring(4) : code;
    }

    /**
     * Consumes a code. A code can only be used once.
     *
     * @param code The code the client entered.
     * @return The pairing it created, or empty when it is unknown or expired.
     */
    public Optional<Pending> redeem(String code) {
        if (code == null) return Optional.empty();
        Pending p = pending.remove(code.trim().toUpperCase(Locale.ROOT).replace("-", ""));
        if (p == null || p.expiresAtMs() < clock.millis()) return Optional.empty();
        return Optional.of(p);
    }

    private void purge() {
        long now = clock.millis();
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext(); ) {
            if (it.next().expiresAtMs() < now) it.remove();
        }
    }
}
