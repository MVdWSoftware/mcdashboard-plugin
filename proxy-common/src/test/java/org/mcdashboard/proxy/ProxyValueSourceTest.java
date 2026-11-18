/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProxyValueSourceTest {
    private final FakeAdapter adapter = new FakeAdapter();
    private final ServerPinger pinger = new ServerPinger(adapter, (host, port) -> port == 25565);
    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(100_000), ZoneOffset.UTC);
    private final ProxyValueSource source = new ProxyValueSource(adapter, pinger, null, clock, 40_000);

    @Test
    void networkWideValues() {
        assertEquals("3", source.resolve("%proxy_online%", null));
        assertEquals("100", source.resolve("%proxy_max%", null));
        assertEquals("2", source.resolve("%proxy_servers%", null));
        assertEquals("9.9", source.resolve("%proxy_version%", null));
        assertEquals("FakeProxy", source.resolve("%proxy_platform%", null));
        assertEquals("60", source.resolve("%proxy_uptime%", null));
    }

    @Test
    void perServerOnlineAndUpUseTheNameWithUnderscoresAndAnyCase() {
        assertEquals("2", source.resolve("%proxy_server_lobby_online%", null));
        assertEquals("1", source.resolve("%proxy_server_sky_block_online%", null));
        assertEquals("1", source.resolve("%proxy_server_SKY_BLOCK_online%", null));
        assertNull(source.resolve("%proxy_server_nope_online%", null));
        assertNull(source.resolve("%proxy_server_lobby_down%", null));
    }

    @Test
    void upIsUnknownUntilTheBackgroundCheckRanThenCached() {
        assertNull(source.resolve("%proxy_server_lobby_up%", null), "never a blocking ping on the reading thread");
        pinger.refresh();
        assertEquals("true", source.resolve("%proxy_server_lobby_up%", null));
        assertEquals("false", source.resolve("%proxy_server_sky_block_up%", null));
    }

    @Test
    void aRemovedServerIsForgotten() {
        pinger.refresh();
        adapter.servers.removeIf(s -> s.name().equals("sky_block"));
        pinger.refresh();
        assertNull(pinger.isUp("sky_block"));
    }

    @Test
    void playerValuesAreOnlyForThePairedPlayerAndOnlineOnes() {
        String alex = FakeAdapter.ALEX.toString();
        assertEquals("42", source.resolve("%proxy_player_ping%", alex));
        assertEquals("Alex", source.resolve("%proxy_player_name%", alex));
        assertEquals("lobby", source.resolve("%proxy_player_server%", alex));
        assertNull(source.resolve("%proxy_player_ping%", null), "a PUBLIC read has no player");
        assertNull(source.resolve("%proxy_player_ping%", "00000000-0000-0000-0000-00000000dead"));
        assertNull(source.resolve("%proxy_player_ping%", "not-a-uuid"));
    }

    @Test
    void unknownOrMalformedTemplatesAreUnresolved() {
        assertNull(source.resolve("%server_online%", null));
        assertNull(source.resolve("%proxy_nothing%", null));
        assertNull(source.resolve("proxy_online", null));
        assertNull(source.resolve("%proxy_online% %proxy_max%", null));
        assertNull(source.resolve(null, null));
    }

    @Test
    void theCatalogListsNetworkAndServerPlaceholdersButNoPlayerOnes() {
        List<String> list = source.lookup().placeholders("proxy");
        assertTrue(list.contains("%proxy_online%"));
        assertTrue(list.contains("%proxy_server_sky_block_up%"));
        assertTrue(list.stream().noneMatch(s -> s.contains("player")));
        assertNull(source.lookup().placeholders("player"), "other expansions are not ours");
        assertEquals(List.of(), source.lookup().placeholders("backend"), "federation is off");
    }

    @Test
    void federatedValuesAreServedWhenConfigured() {
        BackendConfig lobby = BackendConfig.fromMap(Map.of("name", "lobby", "url", "https://lobby.example:1553"));
        Federation fed = new Federation(List.of(lobby), b -> Map.of("tps", "19.8"), clock, m -> { });
        fed.refresh();
        ProxyValueSource withFed = new ProxyValueSource(adapter, pinger, fed, clock, 0);
        assertEquals("19.8", withFed.resolve("%backend_lobby_tps%", null));
        assertNull(withFed.resolve("%backend_other_tps%", null));
        assertEquals(List.of("%backend_lobby_tps%"), withFed.lookup().placeholders("backend"));
    }
}
