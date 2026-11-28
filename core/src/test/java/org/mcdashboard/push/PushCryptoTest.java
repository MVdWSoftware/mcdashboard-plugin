/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.push;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mcdashboard.json.Json;

/** The end-to-end envelope: only the device that owns the key can read it, and only for the route and server it was made for. */
class PushCryptoTest {
    private static final byte[] PLAIN = "{\"title\":\"TPS\",\"body\":\"TPS is 12\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void theDeviceReadsWhatTheServerEncrypted() throws Exception {
        KeyPair device = PushCrypto.generateKeyPair();
        String pub = PushCrypto.encodePublicKey(device.getPublic());
        Map<String, Object> envelope = PushCrypto.encrypt(pub, "route-token-123456", "hash", PLAIN);
        assertEquals(1, envelope.get("v"));
        assertArrayEquals(PLAIN, PushCrypto.decrypt(device.getPrivate(), pub, "route-token-123456", "hash", envelope));
    }

    @Test
    void everyMessageUsesAFreshKeyAndNonce() {
        String pub = PushCrypto.encodePublicKey(PushCrypto.generateKeyPair().getPublic());
        Map<String, Object> a = PushCrypto.encrypt(pub, "route-token-123456", null, PLAIN);
        Map<String, Object> b = PushCrypto.encrypt(pub, "route-token-123456", null, PLAIN);
        assertNotEquals(a.get("epk"), b.get("epk"));
        assertNotEquals(a.get("ct"), b.get("ct"));
    }

    @Test
    void anotherRouteOrServerCannotOpenIt() {
        KeyPair device = PushCrypto.generateKeyPair();
        String pub = PushCrypto.encodePublicKey(device.getPublic());
        Map<String, Object> envelope = PushCrypto.encrypt(pub, "route-token-123456", "hash", PLAIN);
        assertThrows(GeneralSecurityException.class, () -> PushCrypto.decrypt(device.getPrivate(), pub, "other-route-token-1", "hash", envelope));
        assertThrows(GeneralSecurityException.class, () -> PushCrypto.decrypt(device.getPrivate(), pub, "route-token-123456", "other", envelope));
    }

    @Test
    void anotherDeviceCannotOpenIt() {
        String pub = PushCrypto.encodePublicKey(PushCrypto.generateKeyPair().getPublic());
        KeyPair other = PushCrypto.generateKeyPair();
        Map<String, Object> envelope = PushCrypto.encrypt(pub, "route-token-123456", null, PLAIN);
        assertThrows(GeneralSecurityException.class, () -> PushCrypto.decrypt(other.getPrivate(), pub, "route-token-123456", null, envelope));
    }

    @Test
    void aTamperedOrUnknownEnvelopeIsRefused() {
        KeyPair device = PushCrypto.generateKeyPair();
        String pub = PushCrypto.encodePublicKey(device.getPublic());
        Map<String, Object> envelope = new HashMap<>(PushCrypto.encrypt(pub, "route-token-123456", null, PLAIN));
        byte[] ct = Base64.getUrlDecoder().decode((String) envelope.get("ct"));
        ct[0] ^= 1;
        envelope.put("ct", Base64.getUrlEncoder().withoutPadding().encodeToString(ct));
        assertThrows(GeneralSecurityException.class, () -> PushCrypto.decrypt(device.getPrivate(), pub, "route-token-123456", null, envelope));
        envelope.put("v", 2);
        assertThrows(GeneralSecurityException.class, () -> PushCrypto.decrypt(device.getPrivate(), pub, "route-token-123456", null, envelope));
    }

    @Test
    void invalidPublicKeysAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> PushCrypto.decodePublicKey("***"));
        assertThrows(IllegalArgumentException.class, () -> PushCrypto.decodePublicKey(null));
        assertThrows(IllegalArgumentException.class, () -> PushCrypto.decodePublicKey("AAAA"));
        // 65 bytes with the right prefix that is not on the curve
        byte[] off = new byte[65];
        off[0] = 4;
        off[64] = 1;
        assertThrows(IllegalArgumentException.class, () -> PushCrypto.decodePublicKey(Base64.getUrlEncoder().withoutPadding().encodeToString(off)));
        assertThrows(IllegalArgumentException.class, () -> PushCrypto.encrypt("AAAA", "route-token-123456", null, PLAIN));
        assertThrows(IllegalArgumentException.class, () -> PushCrypto.decodePrivateKey(null));
    }

    @Test
    void thePublicKeyRoundTripsThroughItsWireForm() {
        KeyPair device = PushCrypto.generateKeyPair();
        String pub = PushCrypto.encodePublicKey(device.getPublic());
        assertEquals(pub, PushCrypto.encodePublicKey(PushCrypto.decodePublicKey(pub)));
        assertEquals(87, pub.length()); // 65 bytes, base64url without padding
    }

    @Test
    @SuppressWarnings("unchecked")
    void theSharedVectorsOpen() throws Exception {
        // push-vectors.json is the contract the app (TypeScript, Kotlin, Swift) must open too.
        try (var in = PushCryptoTest.class.getResourceAsStream("/push-vectors.json")) {
            Map<String, Object> vectors = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            for (Object o : (java.util.List<Object>) vectors.get("vectors")) {
                Map<String, Object> v = (Map<String, Object>) o;
                byte[] plain = PushCrypto.decrypt(PushCrypto.decodePrivateKey((String) v.get("privateKey")), (String) v.get("publicKey"),
                        (String) v.get("routeToken"), (String) v.get("licenseHash"), (Map<String, Object>) v.get("envelope"));
                assertEquals(v.get("plaintext"), new String(plain, StandardCharsets.UTF_8));
            }
        }
    }
}
