/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import com.sun.net.httpserver.HttpExchange;
import org.mcdashboard.push.PushBinding;
import org.mcdashboard.push.PushService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mcdashboard.web.Exchanges.body;
import static org.mcdashboard.web.Exchanges.error;
import static org.mcdashboard.web.Exchanges.respond;

/** {@code /v1/devices/self/push}: a paired device enables, reads and disables its own push notifications. */
final class PushRoutes {
    /** The path of the route. */
    static final String PATH = "/v1/devices/self/push";

    private final PushService push;

    PushRoutes(PushService push) {
        this.push = push;
    }

    /** Whether push can be offered at all right now (it is not advertised, and the route answers 404, otherwise). */
    boolean available() {
        return push.available();
    }

    /** Handles one request of an authenticated device. */
    void handle(HttpExchange ex, String deviceId) throws IOException {
        if (!push.available()) {
            error(ex, 404, "not found");
            return;
        }
        switch (ex.getRequestMethod()) {
            case "GET" -> {
                Map<String, Object> o = new LinkedHashMap<>();
                var binding = push.binding(deviceId);
                if (binding.isPresent()) o.putAll(binding.get().toJson());
                else o.put("enabled", false);
                respond(ex, 200, o);
            }
            case "PUT" -> {
                PushBinding binding;
                try {
                    binding = PushBinding.parse(body(ex));
                } catch (IllegalArgumentException e) {
                    throw new BadRequest(e.getMessage());
                }
                push.bind(deviceId, binding);
                respond(ex, 200, binding.toJson());
            }
            case "DELETE" -> respond(ex, 200, Map.of("enabled", false, "removed", push.unbind(deviceId)));
            default -> error(ex, 405, "method not allowed");
        }
    }
}
