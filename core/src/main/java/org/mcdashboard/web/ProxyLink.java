/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How a request that reaches the API through a proxy is marked, and which {@code features} depend on the proxy carrying
 * them end to end. A proxy is a separate release that only routes what it knows: a feature the plugin can serve is not
 * usable by the app when the proxy in front of it drops the header, query parameter or route that carries it. The
 * reverse tunnel's handshake (or {@link #FEATURES_HEADER} when it forwards over plain HTTP) reports the proxy's
 * capabilities, and {@code /v1/info} only publishes the intersection.
 *
 * <p>Plugin-local features ({@code tls}, {@code background}) are never in {@link #CARRIED}: the proxy does not change
 * what they mean.
 */
public final class ProxyLink {
    /** Header that says a proxy routed this request; its value says which kind of link. */
    public static final String HEADER = "X-MCDashboard-Via";
    /** Value of {@link #HEADER} for requests that arrived over the reverse tunnel. */
    public static final String TUNNEL = "tunnel";
    /** Value of {@link #HEADER} for requests a proxy forwarded over plain HTTP. */
    public static final String PROXY = "proxy";
    /** Header with the comma separated features the proxy carries, for a proxy that is not the reverse tunnel. */
    public static final String FEATURES_HEADER = "X-MCDashboard-Proxy-Features";

    /**
     * Features that only work when the proxy forwards the matching header or accepts the matching route/parameter:
     * {@code presets} and {@code upload} need the dashboard routes, {@code history-points} the {@code points} parameter,
     * {@code player-names} the player header and {@code client-verification} the attestation header.
     */
    public static final Set<String> CARRIED = Set.of("presets", "history-points", "upload", "client-verification", "player-names");

    private ProxyLink() {
    }

    /**
     * Reads the feature list a proxy over plain HTTP sent, ignoring anything malformed rather than trusting it.
     *
     * @param header The {@link #FEATURES_HEADER} value, or null.
     * @return The features, empty when the header is absent.
     */
    public static Set<String> parse(String header) {
        if (header == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String part : header.split(",")) {
            String feature = part.trim();
            if (!feature.isEmpty() && feature.length() <= 64 && out.size() < 64) out.add(feature);
        }
        return Set.copyOf(out);
    }

    /**
     * What a connection through a proxy may offer.
     *
     * @param features         Every feature the plugin can serve.
     * @param proxyFeatures    The features the proxy reported it can carry; empty when it reported nothing (an older proxy).
     * @param allowPlayerNames The owner's setting.
     * @return The features to publish and whether a typed player name can be used over this connection.
     */
    public static Advertised advertised(List<String> features, Set<String> proxyFeatures, boolean allowPlayerNames) {
        List<String> out = new ArrayList<>(features.size());
        for (String feature : features) {
            if (!CARRIED.contains(feature) || proxyFeatures.contains(feature)) out.add(feature);
        }
        return new Advertised(List.copyOf(out), allowPlayerNames && proxyFeatures.contains("player-names"));
    }

    /**
     * The features of {@code /v1/info} for one connection.
     *
     * @param features         The features to publish.
     * @param allowPlayerNames Whether a typed player name can be used over this connection.
     */
    public record Advertised(List<String> features, boolean allowPlayerNames) {
    }
}
