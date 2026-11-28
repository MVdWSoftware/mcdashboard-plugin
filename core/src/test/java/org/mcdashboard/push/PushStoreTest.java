/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The bindings survive a restart, in a file of their own. */
class PushStoreTest {
    private static PushBinding binding(String route) {
        return new PushBinding(route, PushCrypto.encodePublicKey(PushCrypto.generateKeyPair().getPublic()),
                List.of(PushRule.parse(PushRuleTest.rule())));
    }

    @Test
    void bindingsAreStoredReplacedRemovedAndReloaded() throws Exception {
        Path file = Files.createTempFile("push", ".json");
        Files.delete(file);
        PushStore store = new PushStore(file);
        assertTrue(store.get("d1").isEmpty());
        assertTrue(store.put("d1", binding("route-token-0000001")).isEmpty());
        assertEquals("route-token-0000001", store.put("d1", binding("route-token-0000002")).orElseThrow().routeToken());
        store.put("d2", binding("route-token-0000003"));

        PushStore reloaded = new PushStore(file);
        assertEquals(2, reloaded.all().size());
        assertEquals("route-token-0000002", reloaded.get("d1").orElseThrow().routeToken());
        assertEquals(1, reloaded.get("d1").orElseThrow().rules().size());

        assertTrue(reloaded.remove("d1").isPresent());
        assertTrue(reloaded.remove("d1").isEmpty());
        assertTrue(reloaded.removeRoute("route-token-0000003"));
        assertFalse(reloaded.removeRoute("route-token-0000003"));
        assertTrue(new PushStore(file).all().isEmpty());
    }

    @Test
    void anEmptyFileIsNoBindingsAndADamagedEntryIsSkipped() throws Exception {
        Path file = Files.createTempFile("push", ".json");
        assertTrue(new PushStore(file).all().isEmpty());
        Files.writeString(file, "{\"bindings\":[{\"device\":\"d1\",\"routeToken\":\"x\"},{\"device\":7}]}");
        assertTrue(new PushStore(file).all().isEmpty());
        Files.writeString(file, "not json");
        assertThrows(IllegalStateException.class, () -> new PushStore(file));
    }

    @Test
    void aFileThatCannotBeWrittenIsAnError() {
        PushStore store = new PushStore(Path.of("/nonexistent-mcdashboard-dir/push.json"));
        assertThrows(IllegalStateException.class, () -> store.put("d1", binding("route-token-0000001")));
    }
}
