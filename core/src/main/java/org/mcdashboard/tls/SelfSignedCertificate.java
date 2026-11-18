/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.tls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Creates a self-signed X.509 certificate (ECDSA P-256, SHA-256) with the JDK alone: the JDK has no public API for it and
 * the plugin takes no dependencies, so the few DER structures that are needed are written here.
 */
public final class SelfSignedCertificate {
    private static final DateTimeFormatter UTC_TIME = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter GENERALIZED = DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);

    private final KeyPair keys;
    private final X509Certificate certificate;

    private SelfSignedCertificate(KeyPair keys, X509Certificate certificate) {
        this.keys = keys;
        this.certificate = certificate;
    }

    public KeyPair keys() {
        return keys;
    }

    public X509Certificate certificate() {
        return certificate;
    }

    /**
     * A new key pair and certificate valid from one day ago for {@code validDays}.
     *
     * @param commonName the subject
     * @param names      DNS names and IP literals for the subjectAltName extension
     */
    public static SelfSignedCertificate create(String commonName, List<String> names, int validDays) throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        KeyPair keys = gen.generateKeyPair();

        Instant from = Instant.now().minusSeconds(86_400);
        Instant to = from.plusSeconds(86_400L * validDays);
        byte[] serial = new byte[16];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x7f;
        serial[0] |= 0x40; // positive and never shorter than 16 bytes

        byte[] sigAlg = seq(oid(new int[]{1, 2, 840, 10045, 4, 3, 2}));
        byte[] name = seq(set(seq(oid(new int[]{2, 5, 4, 3}), tlv(0x0c, commonName.getBytes(StandardCharsets.UTF_8)))));
        byte[] tbs = seq(
                tlv(0xa0, tlv(0x02, new byte[]{2})),
                tlv(0x02, serial),
                sigAlg,
                name,
                seq(time(from), time(to)),
                name,
                keys.getPublic().getEncoded(),
                tlv(0xa3, seq(
                        // basicConstraints: not a CA
                        seq(oid(new int[]{2, 5, 29, 19}), tlv(0x01, new byte[]{(byte) 0xff}), tlv(0x04, seq())),
                        // keyUsage: digitalSignature
                        seq(oid(new int[]{2, 5, 29, 15}), tlv(0x01, new byte[]{(byte) 0xff}), tlv(0x04, tlv(0x03, new byte[]{7, (byte) 0x80}))),
                        // extendedKeyUsage: serverAuth
                        seq(oid(new int[]{2, 5, 29, 37}), tlv(0x04, seq(oid(new int[]{1, 3, 6, 1, 5, 5, 7, 3, 1})))),
                        // subjectAltName
                        seq(oid(new int[]{2, 5, 29, 17}), tlv(0x04, altNames(names)))
                )));

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keys.getPrivate());
        signer.update(tbs);
        byte[] signature = signer.sign();
        byte[] bits = new byte[signature.length + 1];
        System.arraycopy(signature, 0, bits, 1, signature.length);
        byte[] der = seq(tbs, sigAlg, tlv(0x03, bits));

        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
        return new SelfSignedCertificate(keys, cert);
    }

    private static byte[] altNames(List<String> names) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String n : names) {
            byte[] ip = ipBytes(n);
            byte[] entry = ip != null ? tlv(0x87, ip) : tlv(0x82, n.getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(entry);
        }
        return tlv(0x30, out.toByteArray());
    }

    /** The bytes of an IPv4 or IPv6 literal, or null for anything else (a name is never resolved here). */
    private static byte[] ipBytes(String s) {
        boolean v4 = s.matches("\\d{1,3}(\\.\\d{1,3}){3}");
        boolean v6 = s.contains(":") && s.matches("[0-9A-Fa-f:.]+");
        if (!v4 && !v6) return null;
        try {
            return InetAddress.getByName(s).getAddress();
        } catch (java.net.UnknownHostException e) {
            return null;
        }
    }

    private static byte[] time(Instant t) {
        boolean generalized = t.atZone(ZoneOffset.UTC).getYear() >= 2050;
        return tlv(generalized ? 0x18 : 0x17, (generalized ? GENERALIZED : UTC_TIME).format(t).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] oid(int[] parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(parts[0] * 40 + parts[1]);
        for (int i = 2; i < parts.length; i++) {
            int v = parts[i];
            int len = 1;
            for (int t = v >> 7; t > 0; t >>= 7) len++;
            for (int j = len - 1; j >= 0; j--) out.write(((v >> (7 * j)) & 0x7f) | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, out.toByteArray());
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int n = value.length;
        if (n < 128) {
            out.write(n);
        } else if (n < 256) {
            out.write(0x81);
            out.write(n);
        } else {
            out.write(0x82);
            out.write(n >> 8);
            out.write(n & 0xff);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }
}
