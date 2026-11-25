/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mcdashboard.config.ConfigView;

class SettingsGapsTest {
    private static Map<String, Object> nested(Object... kv) {
        Map<String, Object> root = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            String[] path = ((String) kv[i]).split("\\.");
            Map<String, Object> m = root;
            for (int j = 0; j < path.length - 1; j++) {
                @SuppressWarnings("unchecked")
                Map<String, Object> next = (Map<String, Object>) m.computeIfAbsent(path[j], k -> new LinkedHashMap<String, Object>());
                m = next;
            }
            m.put(path[path.length - 1], kv[i + 1]);
        }
        return root;
    }

    private static Settings load(List<String> warnings, Object... kv) {
        return Settings.load(ConfigView.of(nested(kv)), warnings::add);
    }

    @Test
    void polling_and_live_feed_both_off_keeps_polling_on() {
        List<String> w = new ArrayList<>();
        Settings s = load(w, "server.polling.enabled", false, "server.websocket.enabled", false);
        assertTrue(s.polling());
        assertTrue(w.get(0).contains("both off"));
        assertFalse(load(new ArrayList<>(), "server.polling.enabled", false).polling());
    }

    @Test
    void shortEventTokensAndBadWebhooksWarn() {
        List<String> w = new ArrayList<>();
        Settings s = load(w, "events.token", "short", "events.webhooks", List.of(
                Map.of("url", "http://example.org/hook", "secret", "0123456789abcdef"),
                Map.of("url", "http://10.0.0.5/hook", "secret", "0123456789abcdef"),
                Map.of("url", "https://example.org/hook", "secret", "short"),
                Map.of("url", "https://example.org/hook"),
                Map.of("url", "ftp://example.org/x", "secret", "0123456789abcdef"),
                Map.of("url", "not a url", "secret", "0123456789abcdef")));
        assertEquals(2, s.webhooks().size());
        assertTrue(w.stream().anyMatch(m -> m.contains("events.token is short")));
        assertTrue(w.stream().anyMatch(m -> m.contains("plain HTTP")));
        assertEquals(4, w.stream().filter(m -> m.contains("ignoring")).count(), w.toString());
    }

    @Test
    void tlsModeReadsBooleansTextAndBlank() {        assertEquals("self-signed", load(new ArrayList<>(), "server.tls.mode", true).tls().mode());
        assertEquals("off", load(new ArrayList<>(), "server.tls.mode", false).tls().mode());
        assertEquals("self-signed", load(new ArrayList<>(), "server.tls.mode", " ").tls().mode());
        assertEquals("custom", load(new ArrayList<>(), "server.tls.mode", " Custom ").tls().mode());
        assertEquals("self-signed", load(new ArrayList<>()).tls().mode());
    }

    @Test
    void tokenTimeoutsAreAtLeastOneSecond() {
        List<String> w = new ArrayList<>();
        Settings s = load(w, "security.access-token-timeout", -5, "security.refresh-token-timeout", 0);
        assertEquals(900, s.accessTokenSeconds(), "a negative lifetime would issue a token without exp");
        assertEquals(31_536_000, s.refreshTokenSeconds());
        assertEquals(2, w.size(), w.toString());
        assertTrue(w.stream().allMatch(m -> m.contains("at least 1 second")), w.toString());

        Settings ok = load(new ArrayList<>(), "security.access-token-timeout", 60, "security.refresh-token-timeout", 3600);
        assertEquals(60, ok.accessTokenSeconds());
        assertEquals(3600, ok.refreshTokenSeconds());
    }

    @Test
    void capsAreWholeNumbers() {
        List<String> w = new ArrayList<>();
        assertEquals(5, Settings.cap(ConfigView.of(nested("limits.max-tiles", 5.0)), "limits.max-tiles", w::add));
        assertEquals(7, Settings.cap(ConfigView.of(nested("limits.max-tiles", "7")), "limits.max-tiles", w::add));
        assertEquals(0, Settings.cap(ConfigView.of(nested("limits.max-tiles", 5.5)), "limits.max-tiles", w::add));
        assertEquals(0, Settings.cap(ConfigView.of(nested("limits.max-tiles", "x")), "limits.max-tiles", w::add));
        assertEquals(0, Settings.cap(ConfigView.of(nested("limits.max-tiles", -3)), "limits.max-tiles", w::add));
        assertEquals(0, Settings.cap(ConfigView.of(nested()), "limits.max-tiles", w::add));
        assertEquals(3, w.size());
    }

    @Test
    void theLocalHostFollowsTheBindAddressAndTheTunnelTrustsIt() {
        assertEquals("127.0.0.1", load(new ArrayList<>()).localHost());
        assertEquals("127.0.0.1", load(new ArrayList<>(), "server.host", "0.0.0.0").localHost());
        assertEquals("127.0.0.1", load(new ArrayList<>(), "server.host", "::").localHost());
        assertEquals("10.1.1.1", load(new ArrayList<>(), "server.host", "10.1.1.1").localHost());
        assertTrue(load(new ArrayList<>(), "server.host", "10.1.1.1", "tunnel.enabled", true).trustedProxies().contains("10.1.1.1"));
        assertEquals("fallback", load(new ArrayList<>()).nameOr("fallback"));
        assertEquals("Named", load(new ArrayList<>(), "server.name", "Named").nameOr("fallback"));
    }

    @Test
    void aWebhookOnlyNeedsAHostAndASchemeAndHttpsNeverWarns() {
        List<String> w = new ArrayList<>();
        Settings s = load(w, "events.webhooks", List.of(
                Map.of("url", "//example.org/hook", "secret", "0123456789abcdef"),
                Map.of("url", "example.org/hook", "secret", "0123456789abcdef"),
                Map.of("url", "https://example.org/hook", "secret", "0123456789abcdef")));
        assertEquals(1, s.webhooks().size(), w.toString());
        assertEquals("https://example.org/hook", s.webhooks().get(0).url().toString());
        assertEquals(2, w.stream().filter(m -> m.contains("ignoring")).count(), w.toString());
        assertTrue(w.stream().noneMatch(m -> m.contains("plain HTTP")), w.toString());
    }

    @Test
    void blankSettingsKeepTheirDefaultAndHistoryComesThroughAsConfig() {
        List<String> w = new ArrayList<>();
        Settings s = load(w, "server.host", "  ", "messages.prefix", "", "history.storage.port", 70000);
        assertEquals("127.0.0.1", s.localHost(), "a blank bind address is every address");
        assertEquals("", s.prefix(), "an empty prefix means the plugin does not decorate its chat lines");
        assertTrue(w.stream().anyMatch(m -> m.equals("config.yml: history.storage.port must be a number from 1 to 65535: using 3306")),
                w.toString());
        assertEquals(3306, s.history().storage().port());
    }
}
