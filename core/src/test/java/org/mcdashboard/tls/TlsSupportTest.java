/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.tls;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mcdashboard.web.ApiContext;
import org.mcdashboard.web.LiveServer;

import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TlsSupportTest {
    private static TlsSupport.Settings selfSigned() {
        return new TlsSupport.Settings("self-signed", "", "", "", "", List.of());
    }

    @Test
    void generatesOnceAndKeepsTheSameCertificate(@TempDir Path dir) throws Exception {
        TlsSupport.Identity a = TlsSupport.load(dir, selfSigned(), List.of("mc.example.com", "192.168.1.6"), "Test", m -> { });
        TlsSupport.Identity b = TlsSupport.load(dir, selfSigned(), List.of("mc.example.com", "192.168.1.6"), "Test", m -> { });
        assertEquals(a.fingerprint(), b.fingerprint(), "a restart keeps the fingerprint the devices pinned");
        assertEquals(64, a.fingerprint().length());
        assertTrue(Files.isRegularFile(dir.resolve("tls/cert.pem")));
    }

    @Test
    void certificateNamesTheServerAndVerifies(@TempDir Path dir) throws Exception {
        SelfSignedCertificate c = SelfSignedCertificate.create("MCDashboard x", List.of("localhost", "10.0.0.5", "mc.example.com", "::1"), 30);
        X509Certificate cert = c.certificate();
        cert.verify(c.keys().getPublic());
        cert.checkValidity();
        var names = cert.getSubjectAlternativeNames().stream().map(e -> String.valueOf(e.get(1))).toList();
        assertTrue(names.containsAll(List.of("localhost", "10.0.0.5", "mc.example.com")), names.toString());
    }

    @Test
    void aNewConfiguredNameRegeneratesTheCertificate(@TempDir Path dir) throws Exception {
        String first = TlsSupport.load(dir, selfSigned(), List.of(), "T", m -> { }).fingerprint();
        String second = TlsSupport.load(dir, selfSigned(), List.of("new.example.com"), "T", m -> { }).fingerprint();
        assertTrue(!first.equals(second));
    }

    @Test
    void customModeNeedsFilesAndExplainsWhat(@TempDir Path dir) {
        var s = new TlsSupport.Settings("custom", "", "", "", "", List.of());
        var e = assertThrows(java.io.IOException.class, () -> TlsSupport.load(dir, s, List.of(), "T", m -> { }));
        assertTrue(e.getMessage().contains("server.tls.certificate"));
    }

    @Test
    void customPemFilesAreServed(@TempDir Path dir) throws Exception {
        SelfSignedCertificate c = SelfSignedCertificate.create("custom", List.of("localhost"), 30);
        Files.writeString(dir.resolve("c.pem"), "-----BEGIN CERTIFICATE-----\n"
                + java.util.Base64.getMimeEncoder().encodeToString(c.certificate().getEncoded()) + "\n-----END CERTIFICATE-----\n");
        Files.writeString(dir.resolve("k.pem"), "-----BEGIN PRIVATE KEY-----\n"
                + java.util.Base64.getMimeEncoder().encodeToString(c.keys().getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        var s = new TlsSupport.Settings("custom", "c.pem", "k.pem", "", "", List.of());
        TlsSupport.Identity id = TlsSupport.load(dir, s, List.of(), "T", m -> { });
        assertEquals(TlsSupport.fingerprint(c.certificate()), id.fingerprint());
    }

    @Test
    void theLiveFeedSpeaksTlsWithThePinnedCertificate(@TempDir Path dir) throws Exception {
        TlsSupport.Identity id = TlsSupport.load(dir, selfSigned(), List.of(), "T", m -> { });
        LiveServer live = new LiveServer(ctx(), 5);
        live.start("127.0.0.1", 0, id.context());
        try {
            SSLContext client = SSLContext.getInstance("TLS");
            client.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String t) { }
                public void checkServerTrusted(X509Certificate[] c, String t) throws java.security.cert.CertificateException {
                    try {
                        if (!TlsSupport.fingerprint(c[0]).equals(id.fingerprint())) throw new java.security.cert.CertificateException("pin mismatch");
                    } catch (java.security.GeneralSecurityException e) {
                        throw new java.security.cert.CertificateException(e);
                    }
                }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            try (Socket raw = client.getSocketFactory().createSocket("127.0.0.1", live.port())) {
                ((SSLSocket) raw).startHandshake();
                assertTrue(((SSLSocket) raw).getSession().isValid());
            }
        } finally {
            live.stop();
        }
    }

    private static ApiContext ctx() throws Exception {
        java.time.Clock clock = java.time.Clock.systemUTC();
        var defs = org.mcdashboard.placeholder.PlaceholderDefinition.parseAll(List.of(), m -> { });
        return org.mcdashboard.TestContexts.context("T", "1", () -> org.mcdashboard.web.ServerStatus.EMPTY, false, 5, 900, 900, 0,
                new org.mcdashboard.placeholder.PlaceholderService((t, u) -> "1", clock, 0, defs),
                new org.mcdashboard.auth.TokenService(new byte[48], clock),
                new org.mcdashboard.auth.DeviceStore(Files.createTempFile("d", ".json")),
                new org.mcdashboard.auth.OtpService(clock, 60), new org.mcdashboard.auth.RateLimiter(clock, 5, 1000), List.of(),
                java.util.Set.of(), new org.mcdashboard.placeholder.HistoryService(clock, defs), null, d -> { },
                org.mcdashboard.TestContexts.allowAll(), "en");
    }
}
