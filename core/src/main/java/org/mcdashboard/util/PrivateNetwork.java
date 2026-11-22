/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decides whether a URL host stays on a private network, where plain HTTP or ws:// is acceptable. */
public final class PrivateNetwork {
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:][0-9A-Fa-f:.]*(%[\\w.]+)?");

    private PrivateNetwork() {
    }

    /**
     * True for {@code localhost} and for IP literals that are loopback, private (RFC 1918, IPv6 ULA) or link local.
     * Host names are never resolved and never count as private: {@code 10.example.com} can point anywhere.
     *
     * @param host The host name or IP literal to test.
     * @return True when the host stays on a private network.
     */
    public static boolean isPrivate(String host) {
        if (host == null || host.isBlank()) return false;
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (h.equalsIgnoreCase("localhost")) return true;
        if (!isIpv4(h) && !(h.contains(":") && IPV6.matcher(h).matches())) return false;
        try {
            InetAddress address = InetAddress.getByName(h);
            if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()) return true;
            byte[] raw = address.getAddress();
            return raw.length == 16 && (raw[0] & 0xFE) == 0xFC;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /** A dotted quad with every part 0-255: only then does {@link InetAddress#getByName} parse it instead of looking it up. */
    private static boolean isIpv4(String host) {
        Matcher m = IPV4.matcher(host);
        if (!m.matches()) return false;
        for (int i = 1; i <= 4; i++) if (Integer.parseInt(m.group(i)) > 255) return false;
        return true;
    }
}
