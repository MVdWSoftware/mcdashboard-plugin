/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import org.mcdashboard.json.Json;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads a backend's PUBLIC placeholders over its own {@code /v1} API. Safe by construction: it only calls the URL the
 * owner configured (never one taken from a response), never follows a redirect, times out, reads at most 1 MiB a
 * response, ignores PLAYER placeholders and sends a fixed User-Agent the backend must allow in
 * {@code security.allowed-clients}.
 */
public final class HttpBackendFetcher implements BackendFetcher {
    static final int MAX_PLACEHOLDERS = 500;
    private static final int PAGE = 100;
    private static final long MAX_BODY = 1024 * 1024;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private final String userAgent;
    private final Map<String, HttpClient> clients = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The User-Agent is {@code MCDashboard-Proxy/<version> (federation)}.
     *
     * @param pluginVersion The plugin version to embed in the User-Agent header.
     */
    public HttpBackendFetcher(String pluginVersion) {
        this.userAgent = "MCDashboard-Proxy/" + pluginVersion + " (federation)";
    }

    @Override
    public Map<String, String> fetch(BackendConfig b) throws IOException {
        List<String> ids = new ArrayList<>();
        for (int offset = 0; offset < MAX_PLACEHOLDERS; offset += PAGE) {
            Map<String, Object> page = getJson(b, "/v1/placeholders?limit=" + PAGE + "&offset=" + offset);
            if (!(page.get("placeholders") instanceof List<?> list) || list.isEmpty()) break;
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && "PUBLIC".equals(m.get("access")) && m.get("id") instanceof String id && ID.matcher(id).matches()) ids.add(id);
            }
            if (list.size() < PAGE) break;
        }
        Set<String> asked = new HashSet<>(ids);
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i += PAGE) {
            String csv = String.join(",", ids.subList(i, Math.min(ids.size(), i + PAGE)));
            Object pack = Json.parse(get(b, "/v1/values?ids=" + URLEncoder.encode(csv, StandardCharsets.UTF_8)));
            if (!(pack instanceof List<?> records)) throw new IOException("unexpected values response");
            for (Object r : records) {
                if (r instanceof Map<?, ?> m && m.get("n") instanceof String n && asked.contains(n)) {
                    String v = text(m);
                    if (v != null) out.put(n, v);
                }
            }
        }
        return out;
    }

    /** The SenML value as text: {@code v} (a whole number without a fraction), {@code vb} or {@code vs}; null for anything else. */
    static String text(Map<?, ?> record) {
        if (record.get("v") instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
        }
        if (record.get("vb") instanceof Boolean b) return b.toString();
        if (record.get("vs") instanceof String s) return s;
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getJson(BackendConfig b, String path) throws IOException {
        Object parsed = Json.parse(get(b, path));
        if (!(parsed instanceof Map)) throw new IOException("unexpected response");
        return (Map<String, Object>) parsed;
    }

    private String get(BackendConfig b, String path) throws IOException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(b.url() + path)).timeout(Duration.ofSeconds(5))
                .header("User-Agent", userAgent).header("Accept", "application/json, application/senml+json");
        if (!b.key().isEmpty()) req.header("Authorization", "Bearer " + b.key());
        try {
            HttpResponse<InputStream> res = client(b).send(req.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = res.body()) {
                if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " from " + b.url().getHost() + path.replaceAll("\\?.*", ""));
                byte[] body = in.readNBytes((int) MAX_BODY + 1);
                if (body.length > MAX_BODY) throw new IOException("response larger than 1 MiB");
                return new String(body, StandardCharsets.UTF_8);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private HttpClient client(BackendConfig b) {
        return clients.computeIfAbsent(b.name(), n -> {
            HttpClient.Builder c = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(3));
            if (!b.pin().isEmpty()) c.sslContext(pinned(b.pin()));
            return c.build();
        });
    }

    /** Trusts exactly the certificate whose SHA-256 fingerprint was configured (what the backend logs for a self-signed certificate). */
    static SSLContext pinned(String fingerprintHex) {
        byte[] expected = HexFormat.of().parseHex(fingerprintHex);
        X509TrustManager trust = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("not a server");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try {
                    byte[] actual = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                    if (!MessageDigest.isEqual(actual, expected)) throw new CertificateException("certificate does not match the configured pin");
                } catch (NoSuchAlgorithmException e) {
                    throw new CertificateException(e);
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {trust}, null);
            return ctx;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
