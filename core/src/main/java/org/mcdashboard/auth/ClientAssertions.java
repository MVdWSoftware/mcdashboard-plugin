/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.auth;

import org.mcdashboard.json.Json;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verifies client assertions: short-lived ES256 tokens that the MVdW attestation service issues to a client after it
 * proved what it is (an unmodified app on a genuine device, a registered server-side client, ...). The plugin checks them
 * offline with the service's public keys, so one rule covers every kind of client and nothing is sent to the service.
 *
 * <p>What this proves is that the service vouched for this client for this server within the last hour; it does not make
 * a client trustworthy by itself, and a token can be replayed by whoever steals it until it expires (it is bound to the host
 * it was issued for, not to a key). See {@code docs/client-verification.md}.
 */
public final class ClientAssertions {
    /** Issuer of assertions; a license token (another issuer) can never pass as one. */
    public static final String ISSUER = "mcdashboard-client";
    /** Header the assertion travels in; the live feed sends it in the {@code auth} message instead (a WebView cannot set headers on a WebSocket). */
    public static final String HEADER = "X-MCDashboard-Attestation";
    private static final int MAX_TOKEN = 4096;
    private static final int MAX_CACHE = 2048;
    private static final long SKEW_SECONDS = 120;

    /**
     * A verified assertion. {@code level} is {@code device} or {@code registered}; {@code licenseId} is the {@code lid} claim
     * (the licensed server the assertion was issued for, only for server-side clients) or null.
     *
     * @param instance    The client that produced it ({@code sub}).
     * @param kind        What produced it.
     * @param version     The client version ({@code cver}).
     * @param level       {@code device} or {@code registered}.
     * @param expiresAtMs Expiry in epoch milliseconds.
     * @param licenseId   The {@code lid} claim, or null.
     */
    public record Verified(String instance, String kind, String version, String level, long expiresAtMs, String licenseId) {
    }

    private final List<PublicKey> keys;
    private final Clock clock;
    private final Set<String> kinds;
    private final Set<String> hosts;
    private final boolean deviceOnly;
    private final String licenseId;
    private final Base64.Decoder b64 = Base64.getUrlDecoder();
    private final Map<String, Verified> cache = new ConcurrentHashMap<>();

    /**
     * Creates a verifier for the service's published keys.
     *
     * @param keys       The service's public keys (JWK maps: {@code kty EC, crv P-256, x, y}); several while it rotates.
     * @param clock      The clock used for expiry and cache decisions.
     * @param kinds      Accepted client kinds, empty for all.
     * @param hosts      Host names an assertion may be for; empty means the host the client connected to.
     * @param deviceOnly Accept only {@code device}-level assertions (platform-attested apps), not {@code registered} clients.
     */
    public ClientAssertions(List<Map<String, Object>> keys, Clock clock, Set<String> kinds, Set<String> hosts, boolean deviceOnly) {
        this(keys, clock, kinds, hosts, deviceOnly, null);
    }

    /**
     * Creates a verifier that also matches a {@code lid} claim against this server's licence id.
     *
     * @param keys       The service's public keys (JWK maps: {@code kty EC, crv P-256, x, y}); several while it rotates.
     * @param clock      The clock used for expiry and cache decisions.
     * @param kinds      Accepted client kinds, empty for all.
     * @param hosts      Host names an assertion may be for; empty means the host the client connected to.
     * @param deviceOnly Accept only {@code device}-level assertions (platform-attested apps), not {@code registered} clients.
     * @param licenseId  This server's {@code license.id}, or null. An assertion that carries a {@code lid} claim is accepted only
     *                   when it equals this id, so a server without a licence id accepts none of them.
     */
    public ClientAssertions(List<Map<String, Object>> keys, Clock clock, Set<String> kinds, Set<String> hosts, boolean deviceOnly, String licenseId) {
        List<PublicKey> parsed = new ArrayList<>();
        for (Map<String, Object> jwk : keys) parsed.add(key(jwk));
        this.keys = List.copyOf(parsed);
        this.clock = clock;
        this.kinds = Set.copyOf(kinds);
        this.hosts = Set.copyOf(hosts);
        this.deviceOnly = deviceOnly;
        this.licenseId = licenseId == null || licenseId.isBlank() ? null : licenseId;
    }

    /**
     * Whether any key is trusted: without one nothing verifies, which is what an owner who forgot the key must see.
     *
     * @return True when at least one public key is configured.
     */
    public boolean hasKeys() {
        return !keys.isEmpty();
    }

    /**
     * Verifies a token for the connection it arrived on.
     *
     * @param token The compact JWT, or null.
     * @param host  The host the client connected to ({@code Host} header, port stripped).
     * @return A verified assertion, or empty when the token is not acceptable.
     */
    public Optional<Verified> verify(String token, String host) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN || keys.isEmpty()) return Optional.empty();
        long now = clock.millis();
        String cacheKey = (host == null ? "" : normalise(host)) + "\n" + token; // an assertion is good for the host it was issued for only
        Verified cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAtMs() > now) return Optional.of(cached);
        try {
            String[] p = token.split("\\.", -1);
            if (p.length != 3) return Optional.empty();
            Map<String, Object> header = Json.parseObject(new String(b64.decode(p[0]), StandardCharsets.UTF_8));
            if (!"ES256".equals(header.get("alg"))) return Optional.empty(); // rejects none and algorithm confusion
            byte[] signature = b64.decode(p[2]);
            byte[] signed = (p[0] + "." + p[1]).getBytes(StandardCharsets.US_ASCII);
            if (signature.length != 64 || keys.stream().noneMatch(k -> signed(k, signed, signature))) return Optional.empty();
            Map<String, Object> c = Json.parseObject(new String(b64.decode(p[1]), StandardCharsets.UTF_8));
            if (!ISSUER.equals(c.get("iss")) || !"client-attestation".equals(c.get("typ"))) return Optional.empty();
            long exp = ((Number) c.get("exp")).longValue() * 1000;
            long iat = ((Number) c.get("iat")).longValue() * 1000;
            if (exp <= now || iat > now + SKEW_SECONDS * 1000) return Optional.empty();
            String level = String.valueOf(c.get("plan"));
            if (deviceOnly && !"device".equals(level)) return Optional.empty();
            String kind = String.valueOf(c.get("kind"));
            if (!kinds.isEmpty() && !kinds.contains(kind)) return Optional.empty();
            String lid = null;
            if (c.containsKey("lid")) {
                // Issued for one licensed server: valid here only when that is this server (offline comparison, nothing is sent).
                if (!(c.get("lid") instanceof String s) || s.isEmpty() || licenseId == null || !MessageDigest.isEqual(
                        s.getBytes(StandardCharsets.UTF_8), licenseId.getBytes(StandardCharsets.UTF_8))) return Optional.empty();
                lid = s;
            }
            Verified v = new Verified(String.valueOf(c.get("sub")), kind, String.valueOf(c.get("cver")), level, exp, lid);
            String aud = String.valueOf(c.get("aud")).toLowerCase(Locale.ROOT);
            if (!audienceOk(aud, host)) return Optional.empty();
            if (cache.size() >= MAX_CACHE) cache.values().removeIf(x -> x.expiresAtMs() <= now);
            if (cache.size() < MAX_CACHE) cache.put(cacheKey, v);
            return Optional.of(v);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private boolean audienceOk(String aud, String host) {
        if (!hosts.isEmpty()) return hosts.contains(aud);
        return host != null && aud.equals(normalise(host));
    }

    /**
     * Lower-case host without port or brackets: {@code MC.example.com:1553} becomes {@code mc.example.com}.
     *
     * @param hostHeader The raw Host header.
     * @return The normalised host name.
     */
    public static String normalise(String hostHeader) {
        String h = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            return end > 0 ? h.substring(1, end) : h;
        }
        int colon = h.lastIndexOf(':');
        return colon > 0 && h.indexOf(':') == colon ? h.substring(0, colon) : h;
    }

    private static boolean signed(PublicKey key, byte[] data, byte[] sig) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
            s.initVerify(key);
            s.update(data);
            return s.verify(sig);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static PublicKey key(Map<String, Object> jwk) {
        try {
            if (!"EC".equals(jwk.get("kty")) || !"P-256".equals(jwk.get("crv"))) throw new IllegalArgumentException("only EC P-256 keys are supported");
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
            Base64.Decoder d = Base64.getUrlDecoder();
            ECPoint point = new ECPoint(new BigInteger(1, d.decode(String.valueOf(jwk.get("x")))), new BigInteger(1, d.decode(String.valueOf(jwk.get("y")))));
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException("not a usable public key: " + e.getMessage(), e);
        }
    }
}
