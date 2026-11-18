/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.auth;

import org.junit.jupiter.api.Test;
import org.mcdashboard.json.Json;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code lid} claim: an assertion issued for a licensed server is good for that server only. */
class ClientAssertionLidTest {
    private final Map<String, Object> fixture = load();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load() {
        try (var in = ClientAssertionLidTest.class.getResourceAsStream("/client-assertion-lid-fixture.json")) {
            return Json.parseObject(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private ClientAssertions verifier(String licenseId) {
        Clock clock = Clock.fixed(Instant.ofEpochSecond(1_800_000_010L), ZoneOffset.UTC);
        return new ClientAssertions(List.of((Map<String, Object>) fixture.get("jwk")), clock, Set.of(), Set.of("mc.example.com"), false, licenseId);
    }

    private String token(String name) {
        return (String) fixture.get(name);
    }

    @Test
    void anAssertionWithLidIsAcceptedOnlyByTheServerWithThatLicenseId() {
        var verified = verifier((String) fixture.get("licenseId")).verify(token("withLid"), "mc.example.com");
        assertTrue(verified.isPresent());
        assertEquals(fixture.get("licenseId"), verified.get().licenseId());
        assertTrue(verifier("mcl_BBBBBBBBBBBBBBBBBBBBBB").verify(token("withLid"), "mc.example.com").isEmpty(), "another server's licence");
        assertTrue(verifier((String) fixture.get("licenseId")).verify(token("withOtherLid"), "mc.example.com").isEmpty());
    }

    @Test
    void aServerWithoutLicenseIdAcceptsNoAssertionThatCarriesLid() {
        assertTrue(verifier(null).verify(token("withLid"), "mc.example.com").isEmpty());
        assertTrue(verifier("").verify(token("withLid"), "mc.example.com").isEmpty());
    }

    @Test
    void anAssertionWithoutLidStaysValidEverywhere() {
        assertTrue(verifier(null).verify(token("withoutLid"), "mc.example.com").isPresent());
        var verified = verifier("mcl_BBBBBBBBBBBBBBBBBBBBBB").verify(token("withoutLid"), "mc.example.com");
        assertTrue(verified.isPresent());
        assertEquals(null, verified.get().licenseId());
    }

    @Test
    void theRefusalIsNotCachedAcrossServers() {
        var ok = verifier((String) fixture.get("licenseId"));
        assertTrue(ok.verify(token("withLid"), "mc.example.com").isPresent());
        assertTrue(ok.verify(token("withLid"), "mc.example.com").isPresent(), "from the cache");
    }
}
