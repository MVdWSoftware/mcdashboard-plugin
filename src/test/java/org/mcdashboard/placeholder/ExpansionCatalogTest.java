package org.mcdashboard.placeholder;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpansionCatalogTest {
    private static final ExpansionLookup LOOKUP = id -> id.equals("server")
            ? List.of("%server_online%", "%server_max_players%", "%server_tps_<1|5|15>%", "%server_ip%", "%server_Public_IP%",
            "%server_countdown_{format}%", "%server_x_[a]%", "%server_secret_key%", "%server%", "%other_thing%", "%server_bad id%",
            "%server_online%", "%server_my_token%")
            : null;

    private static List<ExpansionCatalog.Entry> entries(Map<String, Object> m) {
        return ExpansionCatalog.parseAll(List.of(m), msg -> fail(msg));
    }

    @Test
    void buildsAutoDefinitionsAndSkipsParametersAndDeniedNames() {
        List<String> warnings = new ArrayList<>();
        var defs = ExpansionCatalog.build(entries(Map.of("expansion", "server", "group", "Server", "access", "PLAYER",
                "permission", "x.staff", "deny", List.of("*bad*"))), LOOKUP, warnings::add);
        assertEquals(List.of("server_online", "server_max_players"), defs.stream().map(PlaceholderDefinition::id).toList());
        var d = defs.get(1);
        assertEquals("Max Players", d.name());
        assertEquals("%server_max_players%", d.value());
        assertEquals(PlaceholderDefinition.Type.AUTO, d.type());
        assertEquals(PlaceholderDefinition.Access.PLAYER, d.access());
        assertEquals("Server", d.group());
        assertEquals("x.staff", d.permission());
        assertEquals(5000, d.minIntervalMs());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void missingExpansionIsReportedAndSkipped() {
        List<String> warnings = new ArrayList<>();
        var defs = ExpansionCatalog.build(entries(Map.of("expansion", "nothere")), LOOKUP, warnings::add);
        assertTrue(defs.isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("nothere"));
    }

    @Test
    void maxCapsTheNumberOfPlaceholders() {
        List<String> warnings = new ArrayList<>();
        var defs = ExpansionCatalog.build(entries(Map.of("expansion", "server", "max", 1)), LOOKUP, warnings::add);
        assertEquals(1, defs.size());
        assertEquals(1, warnings.size());
    }

    @Test
    void explicitEntriesWinOverCatalogEntries() {
        var catalog = ExpansionCatalog.build(entries(Map.of("expansion", "server")), LOOKUP, m -> {});
        var explicit = PlaceholderDefinition.parseAll(List.of(
                Map.of("id", "server_online", "name", "Mine", "value", "%server_online%")), m -> fail(m));
        var merged = ExpansionCatalog.merge(explicit, catalog);
        assertEquals(2, merged.size());
        assertEquals("Mine", merged.get(0).name());
        assertEquals("Max Players", merged.get(1).name());
    }

    @Test
    void invalidEntriesAreReported() {
        List<String> errors = new ArrayList<>();
        assertTrue(ExpansionCatalog.parseAll(List.of(Map.of("group", "x"), Map.of("expansion", "a", "access", "NOPE")), errors::add).isEmpty());
        assertEquals(2, errors.size());
        assertEquals("Server", ExpansionCatalog.Entry.fromMap(Map.of("expansion", "server")).group());
    }

    @Test
    void searchIsCorrectWithThousandsOfDefinitions() {
        List<PlaceholderDefinition> defs = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            defs.add(PlaceholderDefinition.fromMap(Map.of("id", "e_p" + i, "name", "Param " + i, "value", "%e_p" + i + "%",
                    "group", "G" + (i % 3), "type", "AUTO")));
        }
        var service = new PlaceholderService((t, u) -> "1", java.time.Clock.systemUTC(), 0, defs);
        assertTrue(service.definition("e_p4999").isPresent());
        var page = service.search(d -> true, "PARAM 49", "G1", 10, 5);
        assertEquals(10, page.items().size());
        assertEquals(3, page.groups().size());
        assertEquals("G0", page.groups().get(0).name());
        assertEquals(111, page.groups().stream().mapToInt(PlaceholderService.Group::count).sum());
        assertEquals("G1", page.items().get(0).group());
        assertEquals(page.total(), page.groups().get(1).count());
        assertEquals(0, service.search(d -> false, "", null, 10, 0).total());
    }

    @Test
    void paramsAreUsedAndUnionedWithDiscovery() {
        ExpansionLookup empty = id -> List.of();
        var count = new java.util.LinkedHashMap<String, Integer>();
        var defs = ExpansionCatalog.build(entries(Map.of("expansion", "player", "params", List.of("health", "ping", "ip_address", "name"))),
                empty, m -> fail(m), count);
        assertEquals(List.of("player_health", "player_ping", "player_name"), defs.stream().map(PlaceholderDefinition::id).toList(),
                "deny rules still apply to params");
        assertEquals("%player_health%", defs.get(0).value());
        assertEquals(3, count.get("player"));
        var both = ExpansionCatalog.build(entries(Map.of("expansion", "server", "params", List.of("online", "extra"))), LOOKUP, m -> {});
        assertEquals(List.of("server_online", "server_max_players", "server_extra"), both.stream().map(PlaceholderDefinition::id).toList());
        var errors = new ArrayList<String>();
        assertTrue(ExpansionCatalog.parseAll(List.of(Map.of("expansion", "a", "params", List.of("bad param"))), errors::add).isEmpty());
        assertEquals(1, errors.size());
    }

    @Test
    void lookupChainFallsBackOnlyWhenInstalledButEmpty() {
        ExpansionLookup primary = id -> id.equals("a") ? List.of() : id.equals("b") ? List.of("%b_x%") : null;
        ExpansionLookup cloud = id -> List.of("%" + id + "_cloud%");
        var chain = primary.orElse(cloud);
        assertEquals(List.of("%a_cloud%"), chain.placeholders("a"));
        assertEquals(List.of("%b_x%"), chain.placeholders("b"));
        assertNull(chain.placeholders("missing"));
        assertEquals(List.of(), primary.orElse(id -> null).placeholders("a"));
    }

    @Test
    void zeroYieldIsRetriedThenReported() {
        var entries = entries(Map.of("expansion", "player"));
        var counts = new java.util.LinkedHashMap<String, Integer>();
        ExpansionCatalog.build(entries, id -> List.of(), m -> {}, counts);
        assertTrue(ExpansionCatalog.worthRetrying(entries, counts));
        var report = ExpansionCatalog.report(entries, counts);
        assertEquals(1, report.size());
        assertTrue(report.get(0).startsWith("WARN ") && report.get(0).contains("params:"));
        counts.clear();
        ExpansionCatalog.build(entries, LOOKUP.orElse(id -> List.of()), m -> {}, counts);
        counts.put("player", 3);
        assertFalse(ExpansionCatalog.worthRetrying(entries, counts));
        assertEquals(List.of("catalog: player: 3 placeholders"), ExpansionCatalog.report(entries, counts));
        var withParams = entries(Map.of("expansion", "player", "params", List.of("x")));
        assertFalse(ExpansionCatalog.worthRetrying(withParams, Map.of("player", 0)), "explicit params: nothing to wait for");
    }
}
