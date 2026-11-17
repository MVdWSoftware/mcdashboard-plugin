/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PrivateNetworkTest {
    @Test
    void acceptsLoopbackAndPrivateLiterals() {
        for (String host : new String[]{"localhost", "127.0.0.1", "10.0.0.5", "172.16.1.1", "172.31.255.1", "192.168.1.10",
                "169.254.1.1", "::1", "[::1]", "fd12:3456::1", "[fe80::1]"}) {
            assertTrue(PrivateNetwork.isPrivate(host), host);
        }
    }

    @Test
    void refusesPublicAddressesAndEveryHostName() {
        for (String host : new String[]{"8.8.8.8", "172.32.0.1", "11.0.0.1", "2001:db8::1", "10.evil.example",
                "192.168.attacker.net", "127.0.0.1.nip.io", "example.com", "999.1.1.1", "", null}) {
            assertFalse(PrivateNetwork.isPrivate(host), String.valueOf(host));
        }
    }
}
