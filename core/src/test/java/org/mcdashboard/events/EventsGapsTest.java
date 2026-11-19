/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.events;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EventsGapsTest {
    static void waitFor(java.util.function.BooleanSupplier c) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > end) fail("condition not met");
            Thread.sleep(10);
        }
    }

    /** Collects what is written; can be told to block or to fail. */
    static final class Sink extends OutputStream {
        final StringBuilder text = new StringBuilder();
        volatile CountDownLatch block;
        volatile boolean fail;

        @Override
        public synchronized void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            CountDownLatch l = block;
            if (l != null) {
                try {
                    l.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (fail) throw new IOException("gone");
            text.append(new String(b, off, len));
        }
    }

    @Test
    void aClientThatCannotBeWrittenToIsDropped() throws Exception {
        EventBus bus = new EventBus(Clock.systemUTC());
        EventStream stream = new EventStream(bus, 3600);
        Sink sink = new Sink();
        sink.fail = true;
        AtomicInteger closed = new AtomicInteger();
        assertTrue(stream.attach(sink, null, closed::incrementAndGet));
        waitFor(() -> closed.get() == 1);
        assertEquals(0, stream.clients());
        stream.close();
    }

    @Test
    void aSlowClientIsDroppedWhenItsQueueOverflowsAndAFullServerRefuses() throws Exception {
        EventBus bus = new EventBus(Clock.systemUTC());
        EventStream stream = new EventStream(bus, 3600);
        Sink slow = new Sink();
        slow.block = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger();
        assertTrue(stream.attach(slow, null, () -> {
            closed.incrementAndGet();
            throw new IllegalStateException("onClose failed");
        }));
        for (int i = 0; i < 600; i++) bus.publish("device.revoked", "d" + i, "p");
        waitFor(() -> closed.get() == 1);
        slow.block.countDown();
        for (int i = 0; i < EventStream.MAX_CLIENTS; i++) assertTrue(stream.attach(new Sink(), null, () -> {
        }));
        assertFalse(stream.attach(new Sink(), null, () -> {
        }));
        assertEquals(EventStream.MAX_CLIENTS, stream.clients());
        stream.close();
        assertEquals(0, stream.clients());
        stream.close();
    }

    @Test
    void eventsKeepFlowingWhileTheWriterIsBusyAndHeartbeatsAreSent() throws Exception {
        EventBus bus = new EventBus(Clock.systemUTC());
        EventStream stream = new EventStream(bus, 1);
        Sink sink = new Sink();
        sink.block = new CountDownLatch(1);
        stream.attach(sink, null, () -> {
        });
        for (int i = 0; i < 5; i++) bus.publish("device.revoked", "d" + i, "p");
        sink.block.countDown();
        for (int i = 0; i < 5; i++) bus.publish("device.revoked", "e" + i, "p");
        waitFor(() -> sink.text.toString().contains("\"e4\"") || sink.text.toString().contains("e4"));
        waitFor(() -> sink.text.toString().contains(": ping"));
        stream.close();
        // after the server is closed, attaching drops the client at once
        AtomicInteger closed = new AtomicInteger();
        stream.attach(new Sink(), null, closed::incrementAndGet);
        waitFor(() -> closed.get() == 1);
    }

    @Test
    void webhooksRetryFailuresAndDropWhatDoesNotFit() throws Exception {
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            int n = calls.incrementAndGet();
            ex.sendResponseHeaders(n == 1 ? 302 : n == 2 ? 500 : 200, -1);
            ex.close();
        });
        server.start();
        try {
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/h");
            WebhookDispatcher d = new WebhookDispatcher(List.of(new WebhookDispatcher.Hook(url, "0123456789abcdef")), Clock.systemUTC(), "1", warnings::add, new long[]{0, 0, 0});
            EventBus bus = new EventBus(Clock.systemUTC());
            d.start(bus);
            bus.publish("device.revoked", "dev", "p");
            waitFor(() -> calls.get() >= 3);
            d.stop();
        } finally {
            server.stop(0);
        }
        // an unreachable endpoint: every attempt fails, the queue fills up, the owner is told
        WebhookDispatcher dead = new WebhookDispatcher(List.of(new WebhookDispatcher.Hook(URI.create("http://127.0.0.1:1/h"), "0123456789abcdef")),
                Clock.systemUTC(), "1", warnings::add, new long[]{1000});
        EventBus bus = new EventBus(Clock.systemUTC());
        dead.start(bus);
        for (int i = 0; i < 1100; i++) bus.publish("device.revoked", "d" + i, "p");
        waitFor(() -> warnings.stream().anyMatch(w -> w.contains("queue is full")));
        dead.stop();
        WebhookDispatcher giving = new WebhookDispatcher(List.of(new WebhookDispatcher.Hook(URI.create("http://127.0.0.1:1/h"), "0123456789abcdef")),
                Clock.systemUTC(), "1", warnings::add, new long[]{0});
        giving.start(bus);
        bus.publish("device.revoked", "last", "p");
        waitFor(() -> warnings.stream().anyMatch(w -> w.contains("giving up after 2 attempts")));
        giving.stop();
    }

    @Test
    void stoppingInterruptsAHangingDelivery() throws Exception {
        java.net.ServerSocket hang = new java.net.ServerSocket(0);
        try {
            WebhookDispatcher d = new WebhookDispatcher(List.of(new WebhookDispatcher.Hook(URI.create("http://127.0.0.1:" + hang.getLocalPort() + "/h"), "0123456789abcdef")),
                    Clock.systemUTC(), "1", m -> {
            }, new long[]{});
            EventBus bus = new EventBus(Clock.systemUTC());
            d.start(bus);
            bus.publish("device.revoked", "x", "p");
            hang.accept(); // the delivery is now waiting for an answer that never comes
            Thread.sleep(200);
            d.stop();
            Thread.sleep(200);
        } finally {
            hang.close();
        }
    }
}
