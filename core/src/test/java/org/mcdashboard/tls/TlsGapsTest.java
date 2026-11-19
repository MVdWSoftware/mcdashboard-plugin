/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.tls;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsGapsTest {
    @TempDir
    Path dir;

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der) + "\n-----END " + type + "-----\n";
    }

    private static TlsSupport.Settings custom(String cert, String key, String keystore, String password) {
        return new TlsSupport.Settings("custom", cert, key, keystore, password, List.of());
    }

    private void load(TlsSupport.Settings s) throws IOException {
        TlsSupport.load(dir, s, List.of(), "T", m -> {
        });
    }

    private void expectError(TlsSupport.Settings s, String part) {
        var e = assertThrows(IOException.class, () -> load(s));
        assertTrue(e.getMessage().contains(part), e.getMessage());
    }

    @Test
    void customPemFilesAreServedAndBadOnesExplained() throws Exception {
        SelfSignedCertificate c = SelfSignedCertificate.create("x", List.of("localhost"), 90);
        Path cert = dir.resolve("c.pem");
        Path key = dir.resolve("k.pem");
        Files.writeString(cert, pem("CERTIFICATE", c.certificate().getEncoded()));
        Files.writeString(key, pem("PRIVATE KEY", c.keys().getPrivate().getEncoded()));
        var id = TlsSupport.load(dir, custom("c.pem", "k.pem", "", ""), List.of(), "T", m -> {
        });
        assertFalse(id.selfSigned());
        assertEquals(TlsSupport.fingerprint(c.certificate()), id.fingerprint());
        expectError(custom("", "k.pem", "", ""), "set server.tls.certificate");
        expectError(custom("c.pem", "", "", ""), "set server.tls.certificate");
        Files.writeString(dir.resolve("empty.pem"), "");
        expectError(custom("empty.pem", "k.pem", "", ""), "holds no certificate");
        Files.writeString(dir.resolve("enc.pem"), "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n");
        expectError(custom("c.pem", "enc.pem", "", ""), "unencrypted PKCS#8");
        Files.writeString(dir.resolve("rsa.pem"), "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");
        expectError(custom("c.pem", "rsa.pem", "", ""), "unencrypted PKCS#8");
        Files.writeString(dir.resolve("ec.pem"), "-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----\n");
        expectError(custom("c.pem", "ec.pem", "", ""), "unencrypted PKCS#8");
        Files.writeString(dir.resolve("blank.pem"), "-----BEGIN PRIVATE KEY-----\n-----END PRIVATE KEY-----\n");
        expectError(custom("c.pem", "blank.pem", "", ""), "holds no private key");
        Files.writeString(dir.resolve("junk.pem"), pem("PRIVATE KEY", new byte[]{1, 2, 3, 4}));
        expectError(custom("c.pem", "junk.pem", "", ""), "not a supported private key");
        expectError(custom("c.pem", Files.createDirectory(dir.resolve("adir")).toString(), "", ""), "directory");
        Path absolute = key.toAbsolutePath();
        TlsSupport.load(dir, custom(cert.toAbsolutePath().toString(), absolute.toString(), "", ""), List.of(), "T", m -> {
        });
    }

    @Test
    void keystoresInBothFormats() throws Exception {
        SelfSignedCertificate c = SelfSignedCertificate.create("x", List.of("localhost"), 90);
        for (String type : new String[]{"PKCS12", "JKS"}) {
            KeyStore ks = KeyStore.getInstance(type);
            ks.load(null, null);
            ks.setCertificateEntry("trusted", c.certificate());
            ks.setKeyEntry("k", c.keys().getPrivate(), "pw".toCharArray(), new Certificate[]{c.certificate()});
            Path file = dir.resolve(type.equals("JKS") ? "store.jks" : "store.p12");
            try (OutputStream out = Files.newOutputStream(file)) {
                ks.store(out, "pw".toCharArray());
            }
            var id = TlsSupport.load(dir, custom("", "", file.getFileName().toString(), "pw"), List.of(), "T", m -> {
            });
            assertEquals(TlsSupport.fingerprint(c.certificate()), id.fingerprint());
            expectError(custom("", "", file.getFileName().toString(), "wrong"), "");
        }
        KeyStore none = KeyStore.getInstance("PKCS12");
        none.load(null, null);
        none.setCertificateEntry("only", c.certificate());
        try (OutputStream out = Files.newOutputStream(dir.resolve("none.p12"))) {
            none.store(out, "pw".toCharArray());
        }
        expectError(custom("", "", "none.pfx", "pw"), "none.pfx");
        expectError(custom("", "", "none.p12", "pw"), "holds no private key");
    }

    @Test
    void modeAndNamesAreValidated() throws Exception {
        expectError(new TlsSupport.Settings("weird", "", "", "", "", List.of()), "must be self-signed, custom or off, not 'weird'");
        assertFalse(new TlsSupport.Settings("off", "", "", "", "", List.of()).enabled());
        assertTrue(new TlsSupport.Settings("custom", "", "", "", "", List.of()).enabled());
        List<String> wanted = new ArrayList<>();
        wanted.add(null);
        wanted.add(" ");
        wanted.add(" Mc.Example.com ");
        var s = new TlsSupport.Settings("self-signed", "", "", "", "", java.util.Arrays.asList(null, "", "Other.example.com"));
        TlsSupport.load(dir, s, wanted, "T", m -> {
        });
        TlsSupport.load(dir, s, wanted, "T", m -> {
        }); // second load reuses it, IPv6 ::1 matched in its long form
    }

    @Test
    void aCertificateThatExpiresSoonIsReplaced() throws Exception {
        SelfSignedCertificate old = SelfSignedCertificate.create("x", List.of("localhost", "127.0.0.1", "::1"), 5);
        Files.createDirectories(dir.resolve("tls"));
        Files.writeString(dir.resolve("tls/cert.pem"), pem("CERTIFICATE", old.certificate().getEncoded()));
        Files.writeString(dir.resolve("tls/key.pem"), pem("PRIVATE KEY", old.keys().getPrivate().getEncoded()));
        List<String> info = new ArrayList<>();
        var id = TlsSupport.load(dir, new TlsSupport.Settings("self-signed", "", "", "", "", List.of()), List.of(), "T", info::add);
        assertNotEquals(TlsSupport.fingerprint(old.certificate()), id.fingerprint());
        assertTrue(info.get(0).contains("expires soon"), info.toString());
    }

    @Test
    void certificatesEncodeLongNamesLongValidityAndOddAddresses() throws Exception {
        List<String> names = new ArrayList<>(List.of("localhost", "10.0.0.5", "999.999.999.999", "2001:db8::1", "fe80::1%eth0", "1:2"));
        for (int i = 0; i < 30; i++) names.add("host" + i + ".example.com");
        SelfSignedCertificate c = SelfSignedCertificate.create("x".repeat(40), names, 9500);
        c.certificate().verify(c.keys().getPublic());
        assertTrue(c.certificate().getSubjectAlternativeNames().size() >= 30);
        assertTrue(c.certificate().getNotAfter().toInstant().isAfter(java.time.Instant.parse("2050-01-01T00:00:00Z")));
        SelfSignedCertificate many = SelfSignedCertificate.create("x", java.util.stream.IntStream.range(0, 400).mapToObj(i -> "h" + i + ".example.org").toList(), 30);
        many.certificate().verify(many.keys().getPublic());
    }
}
