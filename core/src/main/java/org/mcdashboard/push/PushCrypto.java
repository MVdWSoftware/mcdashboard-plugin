/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.push;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.ECPrivateKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The end-to-end encryption of a push notification, so the portal and Google or Apple only ever see ciphertext.
 *
 * <p>Envelope {@code v1}: an ephemeral P-256 key pair per message, ECDH with the device's public key, HKDF-SHA256 and
 * AES-256-GCM with {@code routeToken|licenseHash} as the additional authenticated data. P-256 is used because it needs no
 * library on the JDK, WebCrypto, Android (API 24+) or iOS (CryptoKit). Keys travel as base64url of the uncompressed
 * X9.62 point ({@code 0x04 || X || Y}, 65 bytes); the ciphertext is the AES-GCM output with its 16 byte tag appended.</p>
 *
 * <p>The key derivation is the contract every client implements and the shared test vectors pin down:
 * {@code prk = HMAC-SHA256(salt = "mcdashboard-push-v1", ecdhSecret)} and
 * {@code key = HMAC-SHA256(prk, epk || recipientPublicKey || 0x01)}.</p>
 */
public final class PushCrypto {
    /** The envelope version this class writes and reads. */
    public static final int VERSION = 1;

    private static final byte[] SALT = "mcdashboard-push-v1".getBytes(StandardCharsets.UTF_8);
    private static final int POINT_BYTES = 65;
    private static final int COORDINATE_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PushCrypto() {
    }

    /**
     * Creates a P-256 key pair (the app does this on its side; the plugin only needs it for tests and tooling).
     *
     * @return A new key pair.
     */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("P-256 is not available", e);
        }
    }

    /**
     * Encodes a public key the way the wire carries it.
     *
     * @param key The P-256 public key.
     * @return base64url of the uncompressed point.
     */
    public static String encodePublicKey(PublicKey key) {
        ECPoint w = ((ECPublicKey) key).getW();
        byte[] out = new byte[POINT_BYTES];
        out[0] = 4;
        System.arraycopy(fixed(w.getAffineX()), 0, out, 1, COORDINATE_BYTES);
        System.arraycopy(fixed(w.getAffineY()), 0, out, 1 + COORDINATE_BYTES, COORDINATE_BYTES);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }

    /**
     * Reads a public key from its wire form, checking that the point is on the curve.
     *
     * @param encoded base64url of the uncompressed point.
     * @return The public key.
     * @throws IllegalArgumentException when the value is not a valid P-256 point.
     */
    public static PublicKey decodePublicKey(String encoded) {
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(encoded);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("the public key is not base64url");
        }
        if (raw.length != POINT_BYTES || raw[0] != 4) throw new IllegalArgumentException("the public key is not an uncompressed P-256 point");
        try {
            ECPoint point = new ECPoint(new BigInteger(1, Arrays.copyOfRange(raw, 1, 1 + COORDINATE_BYTES)),
                    new BigInteger(1, Arrays.copyOfRange(raw, 1 + COORDINATE_BYTES, POINT_BYTES)));
            ECParameterSpec curve = params();
            java.security.spec.EllipticCurve c = curve.getCurve();
            BigInteger p = ((java.security.spec.ECFieldFp) c.getField()).getP();
            BigInteger left = point.getAffineY().pow(2).mod(p);
            BigInteger right = point.getAffineX().pow(3).add(c.getA().multiply(point.getAffineX())).add(c.getB()).mod(p);
            if (!left.equals(right) || point.getAffineX().compareTo(p) >= 0 || point.getAffineY().compareTo(p) >= 0) {
                throw new IllegalArgumentException("not on the curve");
            }
            return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, curve));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("the public key is not a point on P-256");
        }
    }

    /**
     * Encrypts a payload for one device.
     *
     * @param recipientPublicKey The device's public key for this server, as sent when it enabled push.
     * @param routeToken         The route the message is addressed to (bound into the authenticated data).
     * @param licenseHash        The server's licence hash (bound into the authenticated data), or null.
     * @param plaintext          The payload, normally JSON.
     * @return The envelope {@code {v, epk, iv, ct}}, ready to be serialised as JSON.
     * @throws IllegalArgumentException when the public key is invalid.
     */
    public static Map<String, Object> encrypt(String recipientPublicKey, String routeToken, String licenseHash, byte[] plaintext) {
        PublicKey recipient = decodePublicKey(recipientPublicKey);
        try {
            KeyPair ephemeral = generateKeyPair();
            byte[] key = deriveKey(ephemeral.getPrivate(), recipient, encodePublicKey(ephemeral.getPublic()), recipientPublicKey);
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(routeToken, licenseHash));
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("v", VERSION);
            envelope.put("epk", encodePublicKey(ephemeral.getPublic()));
            envelope.put("iv", Base64.getUrlEncoder().withoutPadding().encodeToString(iv));
            envelope.put("ct", Base64.getUrlEncoder().withoutPadding().encodeToString(cipher.doFinal(plaintext)));
            return envelope;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not encrypt the notification", e);
        }
    }

    /**
     * Decrypts an envelope; the app does this natively, the plugin only for tests and the shared vectors.
     *
     * @param recipientPrivateKey The device's private key.
     * @param recipientPublicKey  The device's public key in wire form.
     * @param routeToken          The route the message was addressed to.
     * @param licenseHash         The server's licence hash, or null.
     * @param envelope            The envelope as produced by {@link #encrypt}.
     * @return The payload.
     * @throws GeneralSecurityException when the envelope was tampered with or is for another key, route or server.
     */
    public static byte[] decrypt(PrivateKey recipientPrivateKey, String recipientPublicKey, String routeToken, String licenseHash,
                                 Map<String, Object> envelope) throws GeneralSecurityException {
        if (!(envelope.get("v") instanceof Number v) || v.intValue() != VERSION) throw new GeneralSecurityException("unsupported envelope version");
        String epk = String.valueOf(envelope.get("epk"));
        byte[] key = deriveKey(recipientPrivateKey, decodePublicKey(epk), epk, recipientPublicKey);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                new GCMParameterSpec(TAG_BITS, Base64.getUrlDecoder().decode(String.valueOf(envelope.get("iv")))));
        cipher.updateAAD(aad(routeToken, licenseHash));
        return cipher.doFinal(Base64.getUrlDecoder().decode(String.valueOf(envelope.get("ct"))));
    }

    /**
     * Rebuilds a private key from its raw scalar (for the shared test vectors).
     *
     * @param scalar The 32 byte private scalar, base64url.
     * @return The private key.
     */
    public static PrivateKey decodePrivateKey(String scalar) {
        try {
            return KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(new BigInteger(1, Base64.getUrlDecoder().decode(scalar)), params()));
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("the private key is not valid");
        }
    }

    private static byte[] deriveKey(PrivateKey own, PublicKey peer, String epk, String recipientPublicKey) throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(own);
        agreement.doPhase(peer, true);
        byte[] secret = agreement.generateSecret();
        byte[] prk = hmac(SALT, secret);
        byte[] a = Base64.getUrlDecoder().decode(epk);
        byte[] b = Base64.getUrlDecoder().decode(recipientPublicKey);
        byte[] info = new byte[a.length + b.length + 1];
        System.arraycopy(a, 0, info, 0, a.length);
        System.arraycopy(b, 0, info, a.length, b.length);
        info[info.length - 1] = 1;
        return hmac(prk, info);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] aad(String routeToken, String licenseHash) {
        return (routeToken + "|" + (licenseHash == null ? "" : licenseHash)).getBytes(StandardCharsets.UTF_8);
    }

    private static ECParameterSpec params() throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    private static byte[] fixed(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[COORDINATE_BYTES];
        int copy = Math.min(raw.length, COORDINATE_BYTES);
        System.arraycopy(raw, raw.length - copy, out, COORDINATE_BYTES - copy, copy);
        return out;
    }
}
