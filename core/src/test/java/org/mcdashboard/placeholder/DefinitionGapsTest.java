/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.placeholder;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DefinitionGapsTest {
    private static void bad(Map<String, Object> m, String part) {
        var e = assertThrows(IllegalArgumentException.class, () -> PlaceholderDefinition.fromMap(m));
        assertTrue(e.getMessage().contains(part), e.getMessage());
    }

    @Test
    void definitionsAreValidatedAndDefaulted() {
        bad(Map.of("value", "%x%"), "invalid id");
        bad(Map.of("id", "a b", "value", "%x%"), "invalid id");
        bad(Map.of("id", "a"), "needs a single %placeholder%");
        bad(Map.of("id", "a", "value", "no placeholder"), "needs a single %placeholder%");
        bad(Map.of("id", "a", "value", "%x%", "min-interval-permissions", "not a map"), "placeholder 'a'");
        bad(Map.of("id", "a", "type", "IMAGE", "source", "url:https://x.example/a.png", "encode", "no"), "true or false");
        bad(Map.of("id", "a", "type", "IMAGE", "source", "file:a.png", "encode", false), "only applies to url:");
        bad(Map.of("id", "a", "type", "IMAGE", "source", "url:http://x.example/a.png", "encode", false), "https");
        bad(Map.of("id", "a", "type", "IMAGE"), "placeholder 'a'");
        var d = PlaceholderDefinition.fromMap(Map.of("id", "a", "value", "%x%", "min-interval", -5, "history-interval", -1, "history", 999_999, "group", " ", "name", "Name", "description", " "));
        assertEquals(0, d.minIntervalMs());
        assertEquals(0, d.historyIntervalMs());
        assertEquals(525_600, d.historyMinutes());
        assertEquals("Name", d.name());
        assertNull(d.description());
        var list = PlaceholderDefinition.fromMap(Map.of("id", "l", "type", "list", "columns", Map.of("a", "%p_{n}%")));
        assertEquals(List.of("a"), list.toJson().get("columns"));
        assertFalse(list.toJson(0).containsKey("min"));
        var img = PlaceholderDefinition.fromMap(Map.of("id", "i", "type", "IMAGE", "source", "file:a.png"));
        assertEquals(0, img.historyMinutes());
        assertNotNull(img.withHistoryMinutes(5));
        assertEquals(2, PlaceholderDefinition.parseAll(List.of(Map.of("id", "a", "value", "%x%"), Map.of("id", "!"), Map.of("id", "b", "value", "%x%")), m -> {
        }).size());
    }

    @Test
    void intervalOverridesParseSecondsAsNumbersOrText() {
        List<String> warnings = new ArrayList<>();
        java.util.Map<Object, Object> raw = new java.util.LinkedHashMap<>();
        raw.put("a.fast", "15");
        raw.put("b", "x");
        raw.put("c", List.of());
        raw.put("d", -1);
        raw.put("e", Long.MAX_VALUE);
        raw.put(null, 5);
        raw.put("  ", 5);
        raw.put("f", 20);
        IntervalOverrides o = IntervalOverrides.parse(raw, warnings::add);
        assertEquals(java.util.Set.of("a.fast", "f"), o.permissions());
        assertEquals(6, warnings.size());
        assertFalse(o.isEmpty());
        assertTrue(IntervalOverrides.parse(null, warnings::add).isEmpty());
        assertTrue(IntervalOverrides.parse(Map.of(), warnings::add).isEmpty());
        assertTrue(IntervalOverrides.parse(Map.of("x", "bad"), warnings::add).isEmpty());
        assertTrue(IntervalOverrides.parse("string", warnings::add).isEmpty());
    }

    @Test
    void catalogEntriesAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> ExpansionCatalog.Entry.fromMap(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> ExpansionCatalog.Entry.fromMap(Map.of("expansion", "bad id")));
        assertThrows(IllegalArgumentException.class, () -> ExpansionCatalog.Entry.fromMap(Map.of("expansion", "x", "params", List.of(" "))));
        var e = ExpansionCatalog.Entry.fromMap(Map.of("expansion", "x", "group", " ", "permission", " ", "deny", java.util.Arrays.asList("a", null),
                "max", 0, "params", List.of("a")));
        assertEquals("X", e.group());
        assertNull(e.permission());
        assertEquals(ExpansionCatalog.DEFAULT_MAX, e.max());
        assertEquals(List.of("a"), e.deny());
        assertNull(ExpansionCatalog.param(null, "%x_"));
        assertNull(ExpansionCatalog.param("%y_a%", "%x_"));
        assertNull(ExpansionCatalog.param("%x_a", "%x_"));
        assertNull(ExpansionCatalog.param("%x_%", "%x_"));
        assertNull(ExpansionCatalog.param("%x_a b%", "%x_"));
        assertEquals("a", ExpansionCatalog.param("%x_a%", "%x_"));
        assertEquals("A B", ExpansionCatalog.humanise("a__b"));
        assertEquals("_", ExpansionCatalog.humanise("_"));
        assertTrue(ExpansionCatalog.glob("a?c*").matcher("ABCdef").matches());
        var entries = ExpansionCatalog.parseAll(List.of(Map.of("expansion", "x", "max", 5)), m -> fail(m));
        var defs = ExpansionCatalog.build(entries, id -> List.of("%x_a%", "%x_a%", "%x_" + "b".repeat(70) + "%"), m -> {
        });
        assertEquals(1, defs.size(), "duplicates and over-long ids are skipped");
        Map<String, Integer> counts = new java.util.HashMap<>();
        ExpansionCatalog.build(entries, id -> List.of(), m -> {
        }, counts);
        assertTrue(ExpansionCatalog.worthRetrying(entries, counts));
        assertTrue(ExpansionCatalog.report(entries, counts).get(0).startsWith("WARN"));
        assertTrue(ExpansionCatalog.report(entries, new java.util.HashMap<>()).isEmpty());
        assertEquals(2, ExpansionCatalog.merge(defs, ExpansionCatalog.build(
                ExpansionCatalog.parseAll(List.of(Map.of("expansion", "x")), m -> fail(m)), id -> List.of("%x_a%", "%x_c%"), m -> {
                })).size());
    }

    @Test
    void aSlowSourceMakesOtherCallersGiveUpWithoutBreakingTheService() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "slow", "value", "%slow%"), Map.of("id", "slow", "value", "%dup%")), m -> fail(m));
        PlaceholderService svc = new PlaceholderService((t, u) -> {
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "v";
        }, Clock.systemUTC(), 0, null, null, defs, null, null);
        assertEquals(1, svc.all().size(), "the first of two equal ids wins");
        Thread first = new Thread(() -> svc.read("slow", null));
        first.start();
        started.await();
        AtomicReference<Object> second = new AtomicReference<>();
        long t0 = System.currentTimeMillis();
        Thread waiter = new Thread(() -> second.set(svc.read("slow", null)));
        waiter.start();
        waiter.join(5000);
        assertTrue(System.currentTimeMillis() - t0 >= 2500, "waited for the other resolution");
        assertEquals(java.util.Optional.empty(), second.get());
        Thread interrupted = new Thread(() -> second.set(svc.read("slow", null)));
        interrupted.start();
        Thread.sleep(200);
        interrupted.interrupt();
        interrupted.join(5000);
        release.countDown();
        first.join(5000);
        assertTrue(svc.read("missing", null).isEmpty());
        assertTrue(svc.read("slow", null).isPresent());
    }
}
