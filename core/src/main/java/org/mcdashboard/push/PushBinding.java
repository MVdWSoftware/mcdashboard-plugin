/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.push;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What one paired device told the server about its push notifications: the portal route to address, the public key to
 * encrypt for and the alert rules to watch. The server never sees the device's push token, only this opaque route.
 *
 * @param routeToken The portal's opaque route for this device and this server.
 * @param publicKey  The device's P-256 public key for this server (base64url, uncompressed point).
 * @param rules      The rules to watch, at most {@link PushRule#MAX_RULES}.
 */
public record PushBinding(String routeToken, String publicKey, List<PushRule> rules) {
    private static final Pattern ROUTE = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    /**
     * Reads and validates a binding from its JSON form.
     *
     * @param body The JSON object a device sent.
     * @return The binding.
     * @throws IllegalArgumentException with a message for the client when something is missing or invalid.
     */
    public static PushBinding parse(Map<String, Object> body) {
        if (!(body.get("routeToken") instanceof String route) || !ROUTE.matcher(route).matches()) {
            throw new IllegalArgumentException("routeToken is missing or invalid");
        }
        if (!(body.get("publicKey") instanceof String key)) throw new IllegalArgumentException("publicKey is required");
        PushCrypto.decodePublicKey(key);
        List<PushRule> rules = new ArrayList<>();
        Object raw = body.get("rules");
        if (raw != null) {
            if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("rules must be a list");
            if (list.size() > PushRule.MAX_RULES) throw new IllegalArgumentException("at most " + PushRule.MAX_RULES + " rules");
            List<String> seen = new ArrayList<>();
            for (Object o : list) {
                PushRule rule = PushRule.parse(o);
                if (seen.contains(rule.id())) throw new IllegalArgumentException("duplicate rule id " + rule.id());
                seen.add(rule.id());
                rules.add(rule);
            }
        }
        return new PushBinding(route, key, List.copyOf(rules));
    }

    /**
     * The JSON form (the route token is not echoed back, only the rules and the key).
     *
     * @return An ordered map for {@code Json.write}.
     */
    public Map<String, Object> toJson() {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("enabled", true);
        o.put("publicKey", publicKey);
        o.put("rules", rules.stream().map(PushRule::toJson).toList());
        return o;
    }
}
