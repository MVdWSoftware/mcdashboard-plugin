/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class DeviceEntitlementTest {
    @Test
    void onlyTheThreeKnownValuesAreStoredAndTheyPersist() throws Exception {
        var file = Files.createTempFile("devices", ".json");
        var store = new DeviceStore(file);
        var a = store.add("u1", "Steve", "phone", 5).orElseThrow();
        var b = store.add("u1", "Steve", "tablet", 5).orElseThrow();
        var c = store.add("u2", "Alex", "phone", 5).orElseThrow();
        assertNull(a.entitlement());
        store.entitle(a.id(), "server");
        store.entitle(b.id(), " App ");
        store.entitle(c.id(), "premium"); // not one of the three
        store.entitle("unknown", "free");
        assertEquals("server", store.get(a.id()).orElseThrow().entitlement());
        assertEquals("app", store.get(b.id()).orElseThrow().entitlement());
        assertNull(store.get(c.id()).orElseThrow().entitlement());
        assertEquals(1, store.countEntitled("server"));
        assertEquals(1, store.countEntitled("app"));
        assertEquals(0, store.countEntitled("free"));
        store.entitle(a.id(), "bogus"); // an invalid value does not clear a valid one
        assertEquals("server", store.get(a.id()).orElseThrow().entitlement());

        var reloaded = new DeviceStore(file);
        assertEquals("server", reloaded.get(a.id()).orElseThrow().entitlement());
        assertEquals(1, reloaded.countEntitled("app"));
        reloaded.rotate(a.id(), 1);
        assertEquals("server", reloaded.get(a.id()).orElseThrow().entitlement(), "survives a token rotation");
        reloaded.remove(a.id());
        assertEquals(0, reloaded.countEntitled("server"));
    }
}
