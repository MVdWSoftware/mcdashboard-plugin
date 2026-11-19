/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeviceStoreGapsTest {
    @TempDir
    Path dir;

    @Test
    void devicesAreLimitedTouchedEntitledAndRotated() {
        DeviceStore s = new DeviceStore(dir.resolve("d.json"));
        var a = s.add("u", "n", null, 1).orElseThrow();
        assertEquals("Device", a.label());
        assertTrue(s.add("u", "n", "x", 1).isEmpty());
        assertTrue(s.add("u", "n", "x", -1).isPresent(), "negative means no limit");
        s.touch(null);
        s.touch(a.id());
        assertEquals(1, s.countSeenSince(0));
        assertEquals(0, s.countSeenSince(Long.MAX_VALUE));
        assertTrue(s.lastSeen(a.id()).isPresent());
        s.entitle(null, "app");
        s.entitle(a.id(), "bogus");
        s.entitle(a.id(), null);
        s.entitle("unknown", "app");
        s.entitle(a.id(), "app");
        s.entitle(a.id(), "app");
        assertEquals(1, s.countEntitled("app"));
        assertEquals(0, s.countEntitled("free"));
        assertTrue(s.rotate("unknown", 1).isEmpty());
        var r = s.rotate(a.id(), 1).orElseThrow();
        assertEquals(2, r.serial());
        assertTrue(s.rotate(a.id(), 1).isPresent(), "a retry of the same rotation within the grace period");
        assertTrue(s.rotate(a.id(), 7).isEmpty(), "a serial that never existed");
        assertTrue(s.rotate(a.id(), 0).isEmpty());
        s.onRemoved(d -> {
            throw new IllegalStateException("listener");
        });
        assertFalse(s.remove("unknown"));
        assertTrue(s.remove(a.id()));
        assertEquals(1, s.count());
        assertNull(DeviceStore.parseEntitlement(null));
        assertEquals("app", DeviceStore.parseEntitlement(" APP "));
        // a retry after another device rotated and the first was never rotated
        var b = s.devicesOf("u").get(0);
        assertTrue(s.rotate(b.id(), 1).isPresent());
    }

    @Test
    void loadsWhatItSavedAndRefusesAnUnreadableFile() throws Exception {
        Path file = dir.resolve("d.json");
        DeviceStore s = new DeviceStore(file);
        var a = s.add("u", "n", "L", 5, "client").orElseThrow();
        s.touch(a.id());
        s.entitle(a.id(), "server");
        DeviceStore again = new DeviceStore(file);
        assertEquals(1, again.count());
        assertEquals("server", again.get(a.id()).orElseThrow().entitlement());
        assertTrue(again.lastSeen(a.id()).isPresent());
        assertEquals(1, again.countEntitled("server"));
        Files.writeString(file, "");
        assertEquals(0, new DeviceStore(file).count());
        Files.writeString(file, "{\"devices\":[{\"id\":1}]}");
        assertThrows(IllegalStateException.class, () -> new DeviceStore(file));
        Files.writeString(file, "{\"devices\":[]}");
        Path blocked = dir.resolve("blocked");
        Files.createDirectories(blocked.resolve("d.json.tmp"));
        DeviceStore unwritable = new DeviceStore(blocked.resolve("d.json"));
        assertThrows(IllegalStateException.class, () -> unwritable.add("u", "n", "L", 5));
    }
}
