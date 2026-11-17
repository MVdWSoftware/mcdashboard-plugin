package org.mcdashboard.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class DeviceSeenTest {
    @Test
    void lastSeenSurvivesARestartAndIsForgottenOnRevoke() throws Exception {
        var file = Files.createTempFile("devices", ".json");
        var store = new DeviceStore(file);
        var d = store.add("uuid", "Steve", "phone", 5).orElseThrow();
        assertTrue(store.lastSeen(d.id()).isEmpty());
        store.touch(d.id());
        store.rotate(d.id(), 1); // any save writes it out
        long seen = store.lastSeen(d.id()).orElseThrow();
        assertEquals(seen, new DeviceStore(file).lastSeen(d.id()).orElseThrow());
        store.remove(d.id());
        assertTrue(store.lastSeen(d.id()).isEmpty());
    }
}
