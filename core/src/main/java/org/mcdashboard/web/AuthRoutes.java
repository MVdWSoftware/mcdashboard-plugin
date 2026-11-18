/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import com.sun.net.httpserver.HttpExchange;
import org.mcdashboard.auth.DeviceStore;
import org.mcdashboard.auth.OtpService;
import org.mcdashboard.auth.TokenService;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.mcdashboard.web.Exchanges.body;
import static org.mcdashboard.web.Exchanges.error;
import static org.mcdashboard.web.Exchanges.remote;
import static org.mcdashboard.web.Exchanges.respond;

/** {@code POST /v1/auth} (pairing with a one-time code) and {@code POST /v1/auth/refresh}. */
final class AuthRoutes {
    private static final int MAX_LABEL = 40;

    private final ApiContext ctx;

    AuthRoutes(ApiContext ctx) {
        this.ctx = ctx;
    }

    void pair(HttpExchange ex, ClientPolicy.Client client) throws IOException {
        if (!ctx.authLimiter().allow(remote(ex, ctx.trustedProxies()))) {
            ctx.rateLimited();
            error(ex, 429, "too many attempts");
            return;
        }
        Map<String, Object> body = body(ex);
        Optional<OtpService.Pending> pending = ctx.otps().redeem(String.valueOf(body.get("otp")));
        if (pending.isEmpty()) {
            error(ex, 401, "invalid or expired code");
            return;
        }
        Optional<DeviceStore.Device> device = ctx.devices().add(pending.get().playerUuid(), pending.get().playerName(),
                label(body.get("deviceLabel")), ctx.maxDevices(), client.agent());
        if (device.isEmpty()) {
            error(ex, 403, "device limit reached; remove a device with /mcdashboard devices");
            return;
        }
        ctx.onLinked().accept(device.get());
        respond(ex, 200, tokens(device.get()));
    }

    void refresh(HttpExchange ex) throws IOException {
        if (!ctx.authLimiter().allow(remote(ex, ctx.trustedProxies()))) {
            ctx.rateLimited();
            error(ex, 429, "too many attempts");
            return;
        }
        Map<String, Object> body = body(ex);
        Optional<TokenService.Claims> claims = ctx.tokens().verify(String.valueOf(body.get("refreshToken")), TokenService.Type.REFRESH);
        Optional<DeviceStore.Device> rotated = claims.flatMap(c -> ctx.devices().rotate(c.deviceId(), c.serial()));
        if (rotated.isEmpty()) {
            error(ex, 401, "invalid refresh token");
            return;
        }
        respond(ex, 200, tokens(rotated.get()));
    }

    private Map<String, Object> tokens(DeviceStore.Device d) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("accessToken", ctx.tokens().issue(TokenService.Type.ACCESS, d.id(), d.playerUuid(), d.serial(), ctx.accessTokenSeconds()));
        o.put("refreshToken", ctx.tokens().issue(TokenService.Type.REFRESH, d.id(), d.playerUuid(), d.serial(), ctx.refreshTokenSeconds()));
        o.put("expiresIn", ctx.accessTokenSeconds());
        o.put("player", Map.of("uuid", d.playerUuid(), "name", d.playerName()));
        return o;
    }

    /** The label is shown in game chat, so colour codes and control characters are dropped. Null when nothing is left. */
    static String label(Object raw) {
        if (raw == null) return null;
        String clean = raw.toString().replaceAll("[\\p{Cntrl}§]", "").trim();
        if (clean.codePointCount(0, clean.length()) > MAX_LABEL) clean = clean.substring(0, clean.offsetByCodePoints(0, MAX_LABEL));
        return clean.isEmpty() ? null : clean;
    }
}
