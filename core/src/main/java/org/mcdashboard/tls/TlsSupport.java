/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * The TLS identity of the API and the live feed: a configured certificate (PEM files or a keystore), or a self-signed
 * one the plugin generates once in {@code plugins/MCDashboard/tls/} and keeps. A self-signed certificate is not trusted
 * by anyone's CA list; clients pin its SHA-256 {@link #fingerprint()}, which the pairing link carries.
 */
public final class TlsSupport {
    /** What {@code server.tls} says. {@code mode} is {@code self-signed}, {@code custom} or {@code off}. */
    public record Settings(String mode, String certificate, String key, String keystore, String keystorePassword, List<String> names) {
        public boolean enabled() {
            return !"off".equals(mode);
        }
    }

    /** The context to serve with, and the SHA-256 fingerprint of the certificate it presents (lower-case hex). */
    public record Identity(SSLContext context, String fingerprint, boolean selfSigned) {
    }

    private static final int VALID_DAYS = 3650;
    private static final long RENEW_BEFORE_DAYS = 30;

    private TlsSupport() {
    }

    /**
     * Loads or creates the identity. Throws {@link IOException} with a reason the owner can act on; the caller then
     * keeps the API down rather than silently serving it in the clear.
     *
     * @param dir   {@code plugins/MCDashboard/}
     * @param names host names and addresses the generated certificate must name, besides localhost and loopback
     */
    public static Identity load(Path dir, Settings s, Collection<String> names, String commonName, Consumer<String> info) throws IOException {
        try {
            return switch (s.mode()) {
                case "custom" -> custom(dir, s);
                case "self-signed" -> selfSigned(dir, s, names, commonName, info);
                default -> throw new IOException("server.tls.mode must be self-signed, custom or off, not '" + s.mode() + "'");
            };
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS: " + e.getMessage(), e);
        }
    }

    private static Identity selfSigned(Path dir, Settings s, Collection<String> wanted, String commonName, Consumer<String> info)
            throws IOException, GeneralSecurityException {
        Path folder = dir.resolve("tls");
        Path certFile = folder.resolve("cert.pem");
        Path keyFile = folder.resolve("key.pem");
        Set<String> names = new LinkedHashSet<>(List.of("localhost", "127.0.0.1", "::1"));
        for (String n : wanted) if (n != null && !n.isBlank()) names.add(n.trim().toLowerCase(Locale.ROOT));
        for (String n : s.names()) if (n != null && !n.isBlank()) names.add(n.trim().toLowerCase(Locale.ROOT));

        if (Files.isRegularFile(certFile) && Files.isRegularFile(keyFile)) {
            X509Certificate cert = (X509Certificate) readCertificates(certFile).get(0);
            boolean fresh = cert.getNotAfter().toInstant().isAfter(Instant.now().plusSeconds(RENEW_BEFORE_DAYS * 86_400));
            if (fresh && covers(cert, names)) return identity(List.of(cert), readKey(keyFile), true);
            info.accept("Generating a new self-signed certificate (" + (fresh ? "its names changed" : "the old one expires soon")
                    + "). Paired devices that pinned the old one must pair again.");
        }
        SelfSignedCertificate created = SelfSignedCertificate.create("MCDashboard " + commonName, new ArrayList<>(names), VALID_DAYS);
        Files.createDirectories(folder);
        write(keyFile, pem("PRIVATE KEY", created.keys().getPrivate().getEncoded()));
        write(certFile, pem("CERTIFICATE", created.certificate().getEncoded()));
        info.accept("Created a self-signed certificate in " + folder + " for " + String.join(", ", names));
        return identity(List.of(created.certificate()), created.keys().getPrivate(), true);
    }

    private static Identity custom(Path dir, Settings s) throws IOException, GeneralSecurityException {
        if (!s.keystore().isBlank()) {
            Path file = resolve(dir, s.keystore());
            char[] password = s.keystorePassword().toCharArray();
            String type = file.toString().toLowerCase(Locale.ROOT).matches(".*\\.(p12|pfx)$") ? "PKCS12" : "JKS";
            KeyStore ks = KeyStore.getInstance(type);
            try (InputStream in = Files.newInputStream(file)) {
                ks.load(in, password);
            }
            for (String alias : java.util.Collections.list(ks.aliases())) {
                if (!ks.isKeyEntry(alias)) continue;
                Certificate[] chain = ks.getCertificateChain(alias);
                PrivateKey key = (PrivateKey) ks.getKey(alias, password);
                return identity(List.of(chain), key, false);
            }
            throw new IOException("server.tls.keystore " + file + " holds no private key");
        }
        if (s.certificate().isBlank() || s.key().isBlank()) {
            throw new IOException("server.tls.mode is custom: set server.tls.certificate and server.tls.key (PEM files), or server.tls.keystore");
        }
        List<Certificate> chain = readCertificates(resolve(dir, s.certificate()));
        return identity(chain, readKey(resolve(dir, s.key())), false);
    }

    private static Identity identity(List<? extends Certificate> chain, PrivateKey key, boolean selfSigned) throws GeneralSecurityException, IOException {
        char[] pw = new char[24];
        byte[] rnd = new byte[pw.length];
        new SecureRandom().nextBytes(rnd);
        for (int i = 0; i < pw.length; i++) pw[i] = (char) ('a' + (rnd[i] & 0x0f));
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("mcdashboard", key, pw, chain.toArray(new Certificate[0]));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return new Identity(ctx, fingerprint(chain.get(0)), selfSigned);
    }

    /** SHA-256 of the DER certificate, lower-case hex: what a client pins. */
    public static String fingerprint(Certificate cert) throws GeneralSecurityException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
    }

    /** Whether every wanted name is in the certificate's subjectAltName. */
    private static boolean covers(X509Certificate cert, Set<String> names) throws GeneralSecurityException {
        Set<String> have = new LinkedHashSet<>();
        Collection<List<?>> alt = cert.getSubjectAlternativeNames();
        if (alt != null) for (List<?> e : alt) have.add(String.valueOf(e.get(1)).toLowerCase(Locale.ROOT));
        for (String n : names) {
            if (!have.contains(n) && !have.contains(normalise(n))) return false;
        }
        return true;
    }

    /** {@code ::1} is reported by the JDK in its long form. */
    private static String normalise(String ip) {
        try {
            return ip.contains(":") ? java.net.InetAddress.getByName(ip).getHostAddress().toLowerCase(Locale.ROOT) : ip;
        } catch (java.net.UnknownHostException e) {
            return ip;
        }
    }

    private static Path resolve(Path dir, String file) {
        Path p = Path.of(file);
        return p.isAbsolute() ? p : dir.resolve(p);
    }

    private static List<Certificate> readCertificates(Path file) throws IOException, GeneralSecurityException {
        try (InputStream in = Files.newInputStream(file)) {
            List<Certificate> out = new ArrayList<>(CertificateFactory.getInstance("X.509").generateCertificates(in));
            if (out.isEmpty()) throw new IOException(file + " holds no certificate");
            return out;
        }
    }

    /** An unencrypted PKCS#8 PEM key ({@code BEGIN PRIVATE KEY}); convert others with {@code openssl pkcs8 -topk8 -nocrypt}. */
    private static PrivateKey readKey(Path file) throws IOException, GeneralSecurityException {
        String text = Files.readString(file, StandardCharsets.US_ASCII);
        if (text.contains("BEGIN ENCRYPTED PRIVATE KEY") || text.contains("BEGIN RSA PRIVATE KEY") || text.contains("BEGIN EC PRIVATE KEY")) {
            throw new IOException(file + " must be an unencrypted PKCS#8 key (-----BEGIN PRIVATE KEY-----); convert it with: openssl pkcs8 -topk8 -nocrypt -in old.pem -out key.pem");
        }
        String body = text.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        if (body.isEmpty()) throw new IOException(file + " holds no private key");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body));
        for (String alg : new String[]{"EC", "RSA", "Ed25519"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (GeneralSecurityException ignored) {
                // try the next algorithm
            }
        }
        throw new IOException(file + " is not a supported private key (RSA, EC or Ed25519)");
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.US_ASCII, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system
        }
    }
}
