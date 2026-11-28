/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Alert rules and the bindings they travel in: strict input, because a device writes them. */
class PushRuleTest {
    static Map<String, Object> rule() {
        Map<String, Object> m = new HashMap<>();
        m.put("id", "tps-low");
        m.put("placeholder", "server_tps");
        m.put("op", "lt");
        m.put("threshold", 15);
        m.put("forSeconds", 30);
        m.put("cooldownSeconds", 600);
        m.put("label", "TPS");
        m.put("title", "{server}: {label} is low");
        m.put("body", "{label} is {value}");
        return m;
    }

    @Test
    void aValidRuleParsesAndWritesBack() {
        PushRule r = PushRule.parse(rule());
        assertEquals("tps-low", r.id());
        assertEquals(600, r.cooldownSeconds());
        assertEquals(r, PushRule.parse(r.toJson()));
    }

    @Test
    void aCooldownBelowTheMinimumIsRaised() {
        Map<String, Object> m = rule();
        m.put("cooldownSeconds", 5);
        assertEquals(PushRule.MIN_COOLDOWN_SECONDS, PushRule.parse(m).cooldownSeconds());
        m.remove("cooldownSeconds");
        m.remove("forSeconds");
        m.remove("label");
        PushRule r = PushRule.parse(m);
        assertEquals(900, r.cooldownSeconds());
        assertEquals(0, r.forSeconds());
        assertNull(r.label());
        assertFalse(r.toJson().containsKey("label"));
    }

    @Test
    void invalidRulesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> PushRule.parse("x"));
        for (String[] bad : new String[][]{{"id", "Bad Id"}, {"id", ""}, {"id", null}, {"placeholder", "a b"}, {"op", "xx"},
                {"title", "x".repeat(81)}, {"body", " "}, {"label", "x".repeat(41)}}) {
            Map<String, Object> m = rule();
            m.put(bad[0], bad[1]);
            assertThrows(IllegalArgumentException.class, () -> PushRule.parse(m), bad[0]);
        }
        for (Object bad : new Object[]{"15", Double.NaN, null}) {
            Map<String, Object> m = rule();
            m.put("threshold", bad);
            assertThrows(IllegalArgumentException.class, () -> PushRule.parse(m));
        }
        for (Object bad : new Object[]{-1, 1.5, "5", 90_000}) {
            Map<String, Object> m = rule();
            m.put("forSeconds", bad);
            assertThrows(IllegalArgumentException.class, () -> PushRule.parse(m));
        }
        Map<String, Object> m = rule();
        m.put("label", 5);
        assertThrows(IllegalArgumentException.class, () -> PushRule.parse(m));
    }

    @Test
    void everyOperatorCompares() {
        assertTrue(rule("gt", 5).matches(6));
        assertFalse(rule("gt", 5).matches(5));
        assertTrue(rule("gte", 5).matches(5));
        assertTrue(rule("lt", 5).matches(4));
        assertFalse(rule("lt", 5).matches(5));
        assertTrue(rule("lte", 5).matches(5));
        assertTrue(rule("eq", 5).matches(5));
        assertFalse(rule("eq", 5).matches(6));
        assertTrue(rule("ne", 5).matches(6));
    }

    private static PushRule rule(String op, double threshold) {
        return new PushRule("r", "p", op, threshold, 0, 60, null, "t", "b");
    }

    @Test
    void templatesAreFilledAndCleaned() {
        PushRule r = PushRule.parse(rule());
        assertEquals("Aurora: TPS is low", r.render(r.title(), "12", "Aurora"));
        assertEquals("p is 3", new PushRule("r", "p", "gt", 1, 0, 60, null, "t", "b").render("{label} is {value} {server}§\n", "3", null).trim());
        assertEquals("a b", new PushRule("r", "p", "gt", 1, 0, 60, "l", "t", "b").render("a\u0007 b", "1", "s"));
    }

    @Test
    void aBindingNeedsARouteAKeyAndSaneRules() {
        String key = PushCrypto.encodePublicKey(PushCrypto.generateKeyPair().getPublic());
        Map<String, Object> body = new HashMap<>();
        body.put("routeToken", "route-token-123456");
        body.put("publicKey", key);
        body.put("rules", List.of(rule()));
        PushBinding b = PushBinding.parse(body);
        assertEquals(1, b.rules().size());
        assertEquals(true, b.toJson().get("enabled"));
        body.remove("rules");
        assertTrue(PushBinding.parse(body).rules().isEmpty());

        for (String[] bad : new String[][]{{"routeToken", "short"}, {"routeToken", null}, {"publicKey", null}, {"publicKey", "AAAA"}}) {
            Map<String, Object> m = new HashMap<>(body);
            m.put(bad[0], bad[1]);
            assertThrows(IllegalArgumentException.class, () -> PushBinding.parse(m), bad[0]);
        }
        Map<String, Object> m = new HashMap<>(body);
        m.put("rules", "nope");
        assertThrows(IllegalArgumentException.class, () -> PushBinding.parse(m));
        m.put("rules", List.of(rule(), rule()));
        assertThrows(IllegalArgumentException.class, () -> PushBinding.parse(m));
        m.put("rules", java.util.Collections.nCopies(PushRule.MAX_RULES + 1, rule()));
        assertThrows(IllegalArgumentException.class, () -> PushBinding.parse(m));
    }
}
