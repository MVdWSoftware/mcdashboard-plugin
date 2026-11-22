/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProxyLinkTest {
    private static final List<String> ALL = List.of("presets", "history-points", "upload", "tls", "background", "client-verification", "player-names");

    @Test
    void keepsEverythingWhenTheProxyCarriesIt() {
        var advertised = ProxyLink.advertised(ALL, ProxyLink.CARRIED, true);
        assertEquals(ALL, advertised.features());
        assertTrue(advertised.allowPlayerNames());
    }

    @Test
    void dropsCarriedFeaturesTheProxyDoesNotCarryButNeverPluginLocalOnes() {
        var advertised = ProxyLink.advertised(ALL, Set.of("presets"), true);
        assertEquals(List.of("presets", "tls", "background"), advertised.features());
        assertFalse(advertised.allowPlayerNames(), "the proxy cannot forward the player header");
    }

    @Test
    void anOlderProxyThatReportsNothingHidesEveryCarriedFeature() {
        var advertised = ProxyLink.advertised(ALL, Set.of(), true);
        assertEquals(List.of("tls", "background"), advertised.features());
        assertFalse(advertised.allowPlayerNames());
    }

    @Test
    void ignoresWhatTheProxyClaimsBeyondTheContract() {
        var advertised = ProxyLink.advertised(ALL, Set.of("presets", "made-up"), true);
        assertFalse(advertised.features().contains("made-up"));
        assertTrue(advertised.features().contains("presets"));
        assertEquals(List.of("presets", "tls", "background"), advertised.features());
    }

    @Test
    void anOwnerWhoDisabledPlayerNamesKeepsThemOffEvenWhenTheProxyCarriesThem() {
        assertFalse(ProxyLink.advertised(ALL, ProxyLink.CARRIED, false).allowPlayerNames());
    }

    @Test
    void readsTheFeatureHeaderOfAProxyThatForwardsOverPlainHttp() {
        assertEquals(Set.of("presets", "history-points"), ProxyLink.parse(" presets , history-points , "));
        assertEquals(Set.of(), ProxyLink.parse(null));
        assertEquals(Set.of(), ProxyLink.parse(""));
        assertEquals(Set.of("presets"), ProxyLink.parse("presets," + "x".repeat(70)));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 70; i++) many.append("f").append(i).append(',');
        assertEquals(64, ProxyLink.parse(many.toString()).size());
    }
}
