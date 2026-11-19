/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mcdashboard.auth.RateLimiter;

class ClientPolicyGapsTest {
    @Test
    void clientPolicyParsesVersionsAndLimitsBackgroundRequests() {
        Clock c = Clock.systemUTC();
        RateLimiter l = new RateLimiter(c, 100, 1000);
        assertThrows(IllegalArgumentException.class, () -> new ClientPolicy(List.of(), "banana", l, l));
        assertThrows(IllegalArgumentException.class, () -> new ClientPolicy(List.of(), "1.2", l, l));
        assertThrows(IllegalArgumentException.class, () -> new ClientPolicy(List.of(), "1.2.99999999999", l, l));
        ClientPolicy p = new ClientPolicy(List.of(Pattern.compile("MyTool/.*")), " 1.2.3 ", l, l);
        assertEquals("1.2.3", p.minVersion());
        assertTrue(p.tooOld(new ClientPolicy.Client("a", "1.2.2", "ios")));
        assertTrue(p.tooOld(new ClientPolicy.Client("a", "1.1.9", "ios")));
        assertTrue(p.tooOld(new ClientPolicy.Client("a", "0.9.9", "ios")));
        assertFalse(p.tooOld(new ClientPolicy.Client("a", "1.2.3", "ios")));
        assertFalse(p.tooOld(new ClientPolicy.Client("a", "2.0.0", "ios")));
        assertFalse(p.tooOld(new ClientPolicy.Client("a", null, null)));
        assertFalse(p.tooOld(new ClientPolicy.Client("a", "weird", null)));
        assertTrue(p.identify("MyTool/9").isPresent());
        assertTrue(p.identify(null).isEmpty());
        assertTrue(p.identify("x".repeat(200)).isEmpty());
        assertTrue(p.allowBackground("k"), "no background limit set");
        assertTrue(p.background(new RateLimiter(c, 1, 60000)).allowBackground("k"));
        assertFalse(p.allowBackground("k"));
        assertNull(new ClientPolicy(List.of(), null, l, l).minVersion());
        assertNull(new ClientPolicy(List.of(), "  ", l, l).minVersion());
        assertEquals("1.2.3", ClientPolicy.higher("1.2.3", null));
        assertEquals("2.0.0", ClientPolicy.higher("1.2.3", "2.0.0"));
        assertEquals("2.0.0", ClientPolicy.higher(null, "2.0.0"));
        assertEquals("2.0.0", ClientPolicy.higher(" ", "2.0.0"));
        assertEquals("3.0.0", ClientPolicy.higher("3.0.0", "2.0.0"));
        assertEquals("2.0.0", ClientPolicy.higher("2.0.0", "2.0.0"));
        assertEquals("junk", ClientPolicy.higher("junk", "2.0.0"));
        assertEquals("1.2.3", ClientPolicy.higher("1.2.3", "bad"));
    }
}
