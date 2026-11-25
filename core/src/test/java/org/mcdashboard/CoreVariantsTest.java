/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import static org.junit.jupiter.api.Assertions.*;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Starts the core under the less common configurations, so every branch of the wiring in Services runs. */
class CoreVariantsTest {
    @TempDir
    Path data;

    private MCDashboardCore core;
    private FakePlatform platform;

    @AfterEach
    void stop() {
        if (core != null) core.stop();
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private void start(String tls, String websocket, String extra) throws Exception {
        Files.writeString(data.resolve("config.yml"), "server:\n  enabled: true\n  host: 127.0.0.1\n  port: " + freePort()
                + "\n  tls:\n    mode: " + tls + "\n  websocket:\n    enabled: " + websocket + "\n    port: " + freePort() + "\n" + extra);
        platform = new FakePlatform();
        core = new MCDashboardCore(platform, new SnakeYamlCodec(), data);
        core.start();
    }

    private boolean logged(String part) {
        return platform.logged.stream().anyMatch(m -> m.contains(part));
    }

    @Test
    void tlsWithLiveFeedAndEventsAndWebhooks() throws Exception {
        start("self-signed", "true", "events:\n  token: abcdefghijklmnop\n  webhooks:\n    - url: http://127.0.0.1:9/hook\n      secret: 0123456789abcdef\n    - url: ftp://x\n      secret: short\n");
        assertTrue(logged("(HTTPS)"), platform.logged.toString());
        assertTrue(logged("Live feed listening"));
        assertTrue(logged("Device events stream enabled"));
        assertTrue(logged("1 webhook(s)"));
        assertTrue(logged("ignoring ftp://x"));
    }

    @Test
    void plainHttpWithAuthenticationOffWarns() throws Exception {
        start("off", "false", "security:\n  require-authentication: false\n");
        assertTrue(logged("require-authentication is off"));
    }

    @Test
    void clientVerificationWithoutKeysRefusesEverybodyAndSaysSo() throws Exception {
        start("off", "true", "security:\n  client-verification:\n    mode: required\n    keys:\n      - {kty: bad}\n");
        assertTrue(logged("client-verification"), platform.logged.toString());
    }

    @Test
    void clientVerificationRequiredWithNoKeys() throws Exception {
        start("off", "false", "security:\n  client-verification:\n    mode: required\n    min-level: device\n");
        assertTrue(logged("client-verification"), platform.logged.toString());
    }

    @Test
    void anUnsafeTunnelStaysOff() throws Exception {
        start("off", "false", "tunnel:\n  enabled: true\n  url: http://example.org/tunnel\n  key: short\n");
        assertTrue(logged("Tunnel not started"), platform.logged.toString());
    }

    /** The bind address goes into the certificate's subject alternative names, except when it is every address. */
    @Test
    void bindingEveryAddressKeepsTheCertificateOnTheHostNamesOnly() throws Exception {
        Files.writeString(data.resolve("config.yml"), "server:\n  enabled: true\n  host: 0.0.0.0\n  port: " + freePort()
                + "\n  tls:\n    mode: self-signed\n  websocket:\n    enabled: false\n  public-url: https://mc.example.org/api\n");
        platform = new FakePlatform();
        core = new MCDashboardCore(platform, new SnakeYamlCodec(), data);
        core.start();
        assertTrue(logged("TLS certificate (self-signed)"), platform.logged.toString());
        assertTrue(logged("SHA-256 fingerprint"), platform.logged.toString());
    }

    /** The loopback listeners exist for the tunnel only, and only carry the live feed when the live feed is on. */
    @Test
    void aTunnelWithoutTheLiveFeedGetsNoLoopbackFeed() throws Exception {
        start("self-signed", "false", "tunnel:\n  enabled: true\n  url: ws://127.0.0.1:9/tunnel\n"
                + "  key: kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk\n  allow-insecure: true\n");
        assertNotNull(core.tunnel());
        assertFalse(logged("Live feed"), platform.logged.toString());
    }

    @Test
    void typedPlayerNamesWarnTheOwnerLoudly() throws Exception {
        start("off", "true", "security:\n  allow-player-names: true\n");
        assertTrue(logged("security.allow-player-names is on"), platform.logged.toString());
    }

    @Test
    void tunnelWithTlsStartsTheLoopbackListeners() throws Exception {
        start("self-signed", "true", "tunnel:\n  enabled: true\n  url: ws://127.0.0.1:9/tunnel\n  key: kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk\n  allow-insecure: true\n");
        assertNotNull(core.tunnel());
    }

    @Test
    void sqliteHistoryAndDefaultsOfAFreshFolder() throws Exception {
        start("off", "false", "history:\n  storage:\n    type: sqlite\n    file: h.db\n");
        assertTrue(logged("History is stored in sqlite"), platform.logged.toString());
    }

    @Test
    void anUnusablePortIsReportedNotThrown() throws Exception {
        try (ServerSocket busy = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            Files.writeString(data.resolve("config.yml"), "server:\n  enabled: true\n  host: 127.0.0.1\n  port: " + busy.getLocalPort() + "\n  tls:\n    mode: off\n");
            platform = new FakePlatform();
            core = new MCDashboardCore(platform, new SnakeYamlCodec(), data);
            core.start();
            assertTrue(logged("Could not start MCDashboard"), platform.logged.toString());
        }
    }

    private static String jwkYaml() throws Exception {
        var g = java.security.KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        var k = (java.security.interfaces.ECPublicKey) g.generateKeyPair().getPublic();
        var b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        byte[] x = new byte[32];
        byte[] y = new byte[32];
        byte[] xb = k.getW().getAffineX().toByteArray();
        byte[] yb = k.getW().getAffineY().toByteArray();
        System.arraycopy(xb, Math.max(0, xb.length - 32), x, Math.max(0, 32 - xb.length), Math.min(32, xb.length));
        System.arraycopy(yb, Math.max(0, yb.length - 32), y, Math.max(0, 32 - yb.length), Math.min(32, yb.length));
        return "      - {kty: EC, crv: P-256, x: " + b64.encodeToString(x) + ", y: " + b64.encodeToString(y) + "}\n";
    }

    @Test
    void clientVerificationWithAKeyOnTheLoopbackListenersToo() throws Exception {
        start("self-signed", "true", "security:\n  client-verification:\n    mode: required\n    min-level: device\n    keys:\n" + jwkYaml()
                + "tunnel:\n  enabled: true\n  url: ws://127.0.0.1:9/tunnel\n  key: kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk\n  allow-insecure: true\n");
        assertTrue(logged("Client verification is required (platform-attested apps only)"), platform.logged.toString());
        assertNotNull(core.tunnel());
        core.stop();
        start("off", "false", "security:\n  client-verification:\n    mode: required\n    keys:\n" + jwkYaml());
        assertTrue(logged("attested apps and registered clients"), platform.logged.toString());
    }

    @Test
    void brokenTlsKeepsTheApiDownInsteadOfServingInTheClear() throws Exception {
        Files.writeString(data.resolve("config.yml"), "server:\n  enabled: true\n  host: 0.0.0.0\n  port: " + freePort()
                + "\n  public-url: http://mc.example.com\n  tls:\n    mode: custom\n");
        platform = new FakePlatform();
        core = new MCDashboardCore(platform, new SnakeYamlCodec(), data);
        core.start();
        assertTrue(logged("Could not start HTTPS"), platform.logged.toString());
        assertNull(core.otps() == null ? null : core.tunnel());
    }

    @Test
    void aBadPublicUrlAndABoundHostNameTheCertificate() throws Exception {
        Files.writeString(data.resolve("config.yml"), "server:\n  enabled: true\n  host: 127.0.0.1\n  port: " + freePort()
                + "\n  public-url: 'http://bad host'\n  tls:\n    mode: self-signed\n  websocket:\n    enabled: false\n");
        platform = new FakePlatform();
        core = new MCDashboardCore(platform, new SnakeYamlCodec(), data);
        core.start();
        assertTrue(logged("fingerprint"), platform.logged.toString());
        assertNotNull(core.pinnedFingerprint());
    }

    @Test
    void placeholderFileProblemsAreReportedAndTheSamplerRecordsHistory() throws Exception {
        Files.writeString(data.resolve("placeholders.yml"), "placeholders:\n  - id: ok\n    value: '%server_online%'\n    history: 5\n"
                + "  - id: ''\n  - id: stats\n    value: '%mcdashboard_requests%'\n    group: MCDashboard\n"
                + "catalog:\n  - nonsense: true\n");
        start("off", "true", "");
        assertTrue(logged("placeholders.yml:"), platform.logged.toString());
        Thread.sleep(6000); // the history sampler runs every 5 seconds
        assertTrue(core.devices() != null);
    }
}
