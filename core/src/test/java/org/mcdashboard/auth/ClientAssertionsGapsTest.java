/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mcdashboard.json.Json;

/** Assertions signed in the test with a throwaway key, so every refusal can be provoked. */
class ClientAssertionsGapsTest {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final long NOW = 1_800_000_000L;
    private final Clock clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private final KeyPair pair = generate();

    private static KeyPair generate() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private Map<String, Object> jwk() {
        ECPublicKey k = (ECPublicKey) pair.getPublic();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kty", "EC");
        m.put("crv", "P-256");
        m.put("x", B64.encodeToString(fixed(k.getW().getAffineX().toByteArray())));
        m.put("y", B64.encodeToString(fixed(k.getW().getAffineY().toByteArray())));
        return m;
    }

    private static byte[] fixed(byte[] in) {
        byte[] out = new byte[32];
        int n = Math.min(32, in.length);
        System.arraycopy(in, in.length - n, out, 32 - n, n);
        return out;
    }

    private Map<String, Object> claims() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("iss", ClientAssertions.ISSUER);
        c.put("typ", "client-attestation");
        c.put("exp", NOW + 600);
        c.put("iat", NOW - 10);
        c.put("plan", "device");
        c.put("kind", "app");
        c.put("sub", "inst");
        c.put("cver", "1.0.0");
        c.put("aud", "mc.example.com");
        return c;
    }

    private String token(String alg, Map<String, Object> claims, boolean shortSignature) throws Exception {
        String body = B64.encodeToString(("{\"alg\":\"" + alg + "\"}").getBytes()) + "." + B64.encodeToString(Json.write(claims).getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withECDSAinP1363Format");
        s.initSign(pair.getPrivate());
        s.update(body.getBytes(StandardCharsets.US_ASCII));
        byte[] sig = s.sign();
        return body + "." + B64.encodeToString(shortSignature ? new byte[10] : sig);
    }

    private ClientAssertions verifier(Set<String> kinds, Set<String> hosts, boolean deviceOnly) {
        return new ClientAssertions(List.of(jwk()), clock, kinds, hosts, deviceOnly);
    }

    @Test
    void acceptsAGoodAssertionForItsHostAndCachesIt() throws Exception {
        ClientAssertions v = verifier(Set.of(), Set.of(), false);
        assertTrue(v.hasKeys());
        String t = token("ES256", claims(), false);
        var first = v.verify(t, "MC.example.com:1553");
        assertTrue(first.isPresent());
        assertEquals("device", first.get().level());
        assertTrue(v.verify(t, "mc.example.com").isPresent(), "cached");
        assertTrue(v.verify(t, null).isEmpty(), "no host: cannot be the audience");
        assertTrue(v.verify(t, "other.example.com").isEmpty());
    }

    @Test
    void anExplicitHostListReplacesTheConnectedHost() throws Exception {
        ClientAssertions v = verifier(Set.of(), Set.of("mc.example.com"), false);
        assertTrue(v.verify(token("ES256", claims(), false), "whatever").isPresent());
    }

    @Test
    void refusesEveryKindOfBadAssertion() throws Exception {
        ClientAssertions v = verifier(Set.of("app"), Set.of(), true);
        assertTrue(v.verify(null, "mc.example.com").isEmpty());
        assertTrue(v.verify("", "mc.example.com").isEmpty());
        assertTrue(v.verify("x".repeat(5000), "mc.example.com").isEmpty());
        assertTrue(v.verify("a.b", "mc.example.com").isEmpty());
        assertTrue(v.verify("%%.%%.%%", "mc.example.com").isEmpty());
        assertTrue(v.verify(token("none", claims(), false), "mc.example.com").isEmpty());
        assertTrue(v.verify(token("ES256", claims(), true), "mc.example.com").isEmpty());
        Map<String, Object> c = claims();
        c.put("iss", "other");
        assertTrue(v.verify(token("ES256", c, false), "mc.example.com").isEmpty());
        c = claims();
        c.put("typ", "license");
        assertTrue(v.verify(token("ES256", c, false), "mc.example.com").isEmpty());
        c = claims();
        c.put("exp", NOW - 1);
        assertTrue(v.verify(token("ES256", c, false), "mc.example.com").isEmpty());
        c = claims();
        c.put("iat", NOW + 3600);
        assertTrue(v.verify(token("ES256", c, false), "mc.example.com").isEmpty());
        c = claims();
        c.put("plan", "registered");
        assertTrue(v.verify(token("ES256", c, false), "mc.example.com").isEmpty(), "device-level only");
        c = claims();
        c.put("kind", "server");
        assertTrue(v.verify(token("ES256", c, false), "mc.example.com").isEmpty(), "kind not accepted");
        c = claims();
        c.remove("exp");
        assertTrue(v.verify(token("ES256", c, false), "mc.example.com").isEmpty());
        c = claims();
        c.put("lid", 5);
        assertTrue(v.verify(token("ES256", c, false), "mc.example.com").isEmpty());
        assertTrue(verifier(Set.of(), Set.of(), false).verify(token("ES256", claims(), false), "mc.example.com").isPresent());
        assertFalse(new ClientAssertions(List.of(), clock, Set.of(), Set.of(), false).hasKeys());
        assertTrue(new ClientAssertions(List.of(), clock, Set.of(), Set.of(), false).verify(token("ES256", claims(), false), "mc.example.com").isEmpty());
    }

    @Test
    void theCacheStaysBounded() throws Exception {
        ClientAssertions v = verifier(Set.of(), Set.of(), false);
        for (int i = 0; i < 2100; i++) {
            Map<String, Object> c = claims();
            c.put("aud", "h" + i + ".example.com");
            assertTrue(v.verify(token("ES256", c, false), "h" + i + ".example.com").isPresent());
        }
    }

    @Test
    void normalisesHostHeaders() {
        assertEquals("mc.example.com", ClientAssertions.normalise(" MC.Example.com:1553 "));
        assertEquals("::1", ClientAssertions.normalise("[::1]:80"));
        assertEquals("[bad", ClientAssertions.normalise("[bad"));
        assertEquals("host", ClientAssertions.normalise("host"));
        assertEquals("a:b:c", ClientAssertions.normalise("a:b:c"));
        assertEquals(":80", ClientAssertions.normalise(":80"));
    }

    @Test
    void onlyEcP256KeysAreTrusted() {
        Map<String, Object> rsa = Map.of("kty", "RSA");
        assertThrows(IllegalArgumentException.class, () -> new ClientAssertions(List.of(rsa), clock, Set.of(), Set.of(), false));
        Map<String, Object> bad = new LinkedHashMap<>(jwk());
        bad.put("x", "!!!");
        assertThrows(IllegalArgumentException.class, () -> new ClientAssertions(List.of(bad), clock, Set.of(), Set.of(), false));
    }
}
