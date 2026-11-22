/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.mcdashboard.util.Text;

/**
 * One entry of {@code network.backends}: a backend server that runs MCDashboard. The URL is the only address the proxy
 * ever contacts for it. {@code key} is sent as a bearer token when set; {@code pin} is the SHA-256 fingerprint of its
 * self-signed certificate (hex, colons optional) that replaces the normal certificate check.
 *
 * @param name The backend's name, used in placeholder ids.
 * @param url The base URL the proxy contacts.
 * @param key The bearer token to send, or empty when none.
 * @param pin The SHA-256 certificate fingerprint to trust, or empty when none.
 */
public record BackendConfig(String name, URI url, String key, String pin) {
    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9.-]{1,32}");
    private static final Pattern PIN_PATTERN = Pattern.compile("[0-9a-fA-F]{64}");

    /**
     * Parses the entries; a bad or duplicate one is reported through {@code warn} and skipped.
     *
     * @param entries The parsed map list from the YAML file.
     * @param warn Where to report warnings about invalid or duplicate entries.
     * @return A list of valid backend configurations in order.
     */
    public static List<BackendConfig> parseAll(List<? extends Map<?, ?>> entries, Consumer<String> warn) {
        List<BackendConfig> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Map<?, ?> e : entries) {
            try {
                BackendConfig b = fromMap(e);
                if (!names.add(b.name().toLowerCase(java.util.Locale.ROOT))) throw new IllegalArgumentException("duplicate name " + b.name());
                out.add(b);
            } catch (RuntimeException ex) {
                warn.accept("network.backends: " + ex.getMessage());
            }
        }
        return out;
    }

    static BackendConfig fromMap(Map<?, ?> m) {
        String name = text(m.get("name"));
        if (!NAME_PATTERN.matcher(name).matches()) throw new IllegalArgumentException("name '" + name + "' must be 1-32 letters, digits, '.' or '-'");
        URI url;
        try {
            url = URI.create(text(m.get("url")));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + ": invalid url");
        }
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) throw new IllegalArgumentException(name + ": url must be http or https");
        if (url.getHost() == null || url.getUserInfo() != null || url.getRawQuery() != null || url.getRawFragment() != null) {
            throw new IllegalArgumentException(name + ": url must be scheme://host[:port] without credentials, query or fragment");
        }
        String path = url.getRawPath() == null ? "" : Text.trimTrailing(url.getRawPath(), '/');
        String pin = text(m.get("pin")).replace(":", "");
        if (!pin.isEmpty() && !PIN_PATTERN.matcher(pin).matches()) throw new IllegalArgumentException(name + ": pin must be a SHA-256 fingerprint");
        String base = scheme + "://" + url.getRawAuthority() + path;
        return new BackendConfig(name, URI.create(base), text(m.get("key")), pin.toLowerCase(java.util.Locale.ROOT));
    }

    private static String text(Object o) {
        return o == null ? "" : o.toString().trim();
    }
}
