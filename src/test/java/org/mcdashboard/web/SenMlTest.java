package org.mcdashboard.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.placeholder.PlaceholderService;

class SenMlTest {
    private static PlaceholderDefinition def(String type) {
        return PlaceholderDefinition.fromMap(Map.of("id", "x", "value", "%x%", "type", type));
    }

    private static Map<String, Object> rec(String type, String raw) {
        return SenMl.record(def(type), new PlaceholderService.Value("x", raw, 1_700_000_000_000L));
    }

    @Test
    void decoratedNumbersStillBecomeNumbers() {
        assertEquals(20.0, rec("DECIMAL", "*20.0").get("v"));
        assertEquals(19.5, rec("DECIMAL", "§a19.5").get("v"));
        assertEquals(19.5, rec("DECIMAL", "19,5").get("v"));
        assertEquals(68.0, rec("PERCENTAGE", "68%").get("v"));
        assertEquals(37L, rec("INTEGER", "37").get("v"));
        assertEquals(12L, rec("INTEGER", "12.0").get("v"));
    }

    @Test
    void unresolvablePlaceholdersStayStrings() {
        assertEquals("n/a", rec("DECIMAL", "n/a").get("vs"));
        assertNull(rec("DECIMAL", "n/a").get("v"));
        assertEquals("true", rec("BOOLEAN", "true").get("vb").toString());
        assertEquals("yes", rec("BOOLEAN", "yes").get("vs"));
        assertEquals(1_700_000_000.0, rec("STRING", "Steve").get("t"));
    }

    @Test
    void autoInfersTheTypeOfEachValue() {
        assertEquals(37L, rec("AUTO", "37").get("v"));
        assertEquals(19.5, rec("AUTO", "§a19,5").get("v"));
        assertEquals(68L, rec("AUTO", "68%").get("v"));
        assertEquals(true, rec("AUTO", "True").get("vb"));
        assertEquals("Steve", rec("AUTO", "Steve").get("vs"));
        assertEquals("007", rec("AUTO", "007").get("vs"));
        assertEquals("1 2", rec("AUTO", "1 2").get("vs"));
        assertEquals("99999999999999999999", rec("AUTO", "99999999999999999999").get("vs"));
        assertEquals("", rec("AUTO", "").get("vs"));
    }

    @Test
    void imageValuesGoOutAsDataValues() {
        var d = PlaceholderDefinition.fromMap(Map.of("id", "logo", "type", "IMAGE", "source", "file:logo.png"));
        var r = SenMl.record(d, new PlaceholderService.Value("logo", "iVBORw0", 1_700_000_000_000L));
        assertEquals("iVBORw0", r.get("vd"));
        assertNull(r.get("vs"));
        assertNull(r.get("v"));
    }
}
