/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.tunnel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LiveChannelsTest {
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final List<Map<String, Object>> sent = Collections.synchronizedList(new ArrayList<>());
    private ServerSocket live;
    private final List<Socket> accepted = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void stop() throws IOException {
        if (live != null) live.close();
        for (Socket s : accepted) s.close();
        workers.shutdownNow();
    }

    private int echoServer() throws IOException {
        live = new ServerSocket(0);
        workers.execute(() -> {
            try {
                while (true) {
                    Socket s = live.accept();
                    accepted.add(s);
                    workers.execute(() -> {
                        try {
                            byte[] b = new byte[100];
                            int n;
                            while ((n = s.getInputStream().read(b)) > 0) {
                                s.getOutputStream().write(b, 0, n);
                                s.getOutputStream().flush();
                            }
                        } catch (IOException ignored) {
                            // closed by the test
                        }
                    });
                }
            } catch (IOException ignored) {
                // closed by the test
            }
        });
        return live.getLocalPort();
    }

    private LiveChannels channels(int port, Function<Map<String, Object>, CompletableFuture<?>> send) {
        return new LiveChannels("127.0.0.1", port, workers, send);
    }

    private void waitFor(java.util.function.BooleanSupplier c) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("condition not met");
            Thread.sleep(10);
        }
    }

    private boolean has(String t) {
        synchronized (sent) {
            return sent.stream().anyMatch(m -> t.equals(m.get("t")));
        }
    }

    @Test
    void relaysBytesBothWaysAndTellsTheProxyWhenItCloses() throws Exception {
        LiveChannels c = channels(echoServer(), m -> {
            sent.add(m);
            return CompletableFuture.completedFuture(null);
        });
        c.open(1);
        assertEquals(1, c.size());
        c.open(1); // the same id twice is refused
        waitFor(() -> has("x"));
        c.write(1, "hello".getBytes());
        c.write(99, new byte[1]); // unknown channel: ignored
        waitFor(() -> has("d"));
        Map<String, Object> d;
        synchronized (sent) {
            d = sent.stream().filter(m -> "d".equals(m.get("t"))).findFirst().orElseThrow();
        }
        assertEquals("hello", new String(Base64.getDecoder().decode((String) d.get("b"))));
        sent.clear();
        c.close(1, true);
        c.close(1, true); // already gone
        assertTrue(has("x"));
        assertEquals(0, c.size());
    }

    @Test
    void refusesWithoutAPortAndWhenTheLiveFeedIsNotThere() throws Exception {
        channels(0, m -> {
            sent.add(m);
            return null;
        }).open(5);
        assertTrue(has("x"));
        sent.clear();
        int free;
        try (ServerSocket s = new ServerSocket(0)) {
            free = s.getLocalPort();
        }
        channels(free, m -> {
            sent.add(m);
            return null;
        }).open(6);
        assertTrue(has("x"));
    }

    @Test
    void closesWhenTheTunnelIsGoneOrTheLiveFeedHangsUp() throws Exception {
        // tunnel gone: send answers null for the data frame, then the channel is closed and the proxy told
        LiveChannels c = channels(echoServer(), m -> {
            sent.add(m);
            return "d".equals(m.get("t")) ? null : CompletableFuture.completedFuture(null);
        });
        c.open(1);
        c.write(1, "x".getBytes());
        waitFor(() -> c.size() == 0);
        assertTrue(has("x"));

        // the live feed hangs up
        sent.clear();
        LiveChannels d = channels(live.getLocalPort(), m -> {
            sent.add(m);
            return CompletableFuture.completedFuture(null);
        });
        d.open(2);
        waitFor(() -> accepted.size() >= 2);
        accepted.get(1).close();
        waitFor(() -> d.size() == 0);
        assertTrue(has("x"));

        // a failed send of a data frame ends the pump
        sent.clear();
        LiveChannels e = channels(live.getLocalPort(), m -> {
            sent.add(m);
            return "d".equals(m.get("t")) ? CompletableFuture.failedFuture(new IOException("down")) : CompletableFuture.completedFuture(null);
        });
        e.open(3);
        e.write(3, "y".getBytes());
        waitFor(() -> e.size() == 0);
    }

    @Test
    void aWriteToAClosedSocketClosesTheChannel() throws Exception {
        LiveChannels c = channels(echoServer(), m -> {
            sent.add(m);
            return CompletableFuture.completedFuture(null);
        });
        c.open(1);
        waitFor(() -> accepted.size() == 1);
        // close the plugin side of the socket without the channel knowing: the next write fails once the peer resets
        accepted.get(0).close();
        for (int i = 0; i < 50 && c.size() > 0; i++) {
            c.write(1, new byte[8192]);
            Thread.sleep(20);
        }
        assertEquals(0, c.size());
        c.closeAll();
    }

    @Test
    void closeAllClosesEveryChannelSilently() throws Exception {
        LiveChannels c = channels(echoServer(), m -> {
            sent.add(m);
            return CompletableFuture.completedFuture(null);
        });
        c.open(1);
        c.open(2);
        c.closeAll();
        assertEquals(0, c.size());
        assertFalse(sent.stream().anyMatch(m -> "x".equals(m.get("t")) && Long.valueOf(1).equals(m.get("c")) && false));
        workers.awaitTermination(50, TimeUnit.MILLISECONDS);
    }

    @Test
    void anInterruptedPumpStops() throws Exception {
        CompletableFuture<Object> never = new CompletableFuture<>();
        LiveChannels c = channels(echoServer(), m -> "d".equals(m.get("t")) ? never : CompletableFuture.completedFuture(null));
        c.open(1);
        c.write(1, "x".getBytes());
        Thread.sleep(200);
        workers.shutdownNow(); // interrupts the pump blocked on the send
        waitFor(() -> c.size() == 0);
    }
}
