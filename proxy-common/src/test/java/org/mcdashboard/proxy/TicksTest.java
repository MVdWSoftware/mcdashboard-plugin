/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mcdashboard.platform.Log;

class TicksTest {
    @TempDir
    Path dir;

    @Test
    void aTickIs50Milliseconds() {
        assertEquals(50, Ticks.toMillis(1));
        assertEquals(5000, Ticks.toMillis(100));
        assertEquals(0, Ticks.toMillis(-3));
    }

    @Test
    void thePlatformMapsItsSchedulersToMilliseconds() {
        FakeAdapter adapter = new FakeAdapter();
        Log quiet = Log.of(java.util.logging.Logger.getAnonymousLogger());
        ProxyPlatform platform = new ProxyPlatform(adapter, quiet, "1", dir, new ProxyYaml(), b -> java.util.Map.of(), (h, p) -> true, Clock.systemUTC(), 0);
        platform.runSync(() -> { });
        platform.runSyncLater(() -> { }, 100);
        platform.runSyncRepeating(() -> { }, 100);
        platform.runSyncRepeating(() -> { }, 0);
        assertEquals(0, adapter.scheduled.get(0).delayMs());
        assertEquals(0, adapter.scheduled.get(0).periodMs());
        assertEquals(5000, adapter.scheduled.get(1).delayMs());
        assertEquals(0, adapter.scheduled.get(1).periodMs());
        assertEquals(5000, adapter.scheduled.get(2).periodMs());
        assertEquals(50, adapter.scheduled.get(3).periodMs(), "a repeat is never faster than one tick");
        var repeating = platform.runSyncRepeating(() -> { }, 100);
        assertEquals(5, adapter.scheduled.size());
        repeating.cancel();
        assertEquals(4, adapter.scheduled.size(), "a reload must be able to cancel the repeating task");
        platform.close();
    }
}
