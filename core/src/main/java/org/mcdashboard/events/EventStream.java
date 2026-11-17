/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.events;

import org.mcdashboard.json.Json;
import org.mcdashboard.util.Threads;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Server-sent events ({@code text/event-stream}) over {@link EventBus}. Each client has its own bounded queue,
 * written by a pool thread, so a slow or stalled consumer can never delay a revocation or other clients: it is
 * dropped instead.
 */
public final class EventStream {
    public static final int MAX_CLIENTS = 16;
    private static final int MAX_QUEUE = 500;

    private final EventBus bus;
    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private final ExecutorService writers = Executors.newCachedThreadPool(Threads.daemon("MCDashboard-events"));
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(Threads.daemon("MCDashboard-events-ping"));

    public EventStream(EventBus bus, long heartbeatSeconds) {
        this.bus = bus;
        heartbeat.scheduleAtFixedRate(() -> clients.forEach(c -> c.offer(": ping\n\n")), heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
    }

    /** Starts streaming to {@code out}; {@code onClose} runs once when the client goes away. False when the server is full. */
    public boolean attach(OutputStream out, String lastEventId, Runnable onClose) {
        if (clients.size() >= MAX_CLIENTS) return false;
        Client c = new Client(out, onClose);
        clients.add(c);
        c.offer("retry: 3000\n: connected\n\n");
        c.unsubscribe = bus.subscribe(lastEventId, c::event, () -> c.offer(frame("reset", null, Map.of("epoch", bus.epoch()))));
        return true;
    }

    public int clients() {
        return clients.size();
    }

    public void close() {
        heartbeat.shutdownNow();
        clients.forEach(Client::close);
        writers.shutdownNow();
    }

    static String frame(String event, String id, Map<String, Object> data) {
        return (id == null ? "" : "id: " + id + "\n") + "event: " + event + "\ndata: " + Json.write(data) + "\n\n";
    }

    private final class Client {
        private final OutputStream out;
        private final Runnable onClose;
        private final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
        private final AtomicBoolean draining = new AtomicBoolean();
        private volatile boolean closed;
        volatile Runnable unsubscribe = () -> { };

        Client(OutputStream out, Runnable onClose) {
            this.out = out;
            this.onClose = onClose;
        }

        void event(DeviceEvent e) {
            offer(frame(e.type(), e.id(), e.toJson()));
        }

        void offer(String frame) {
            if (closed) return;
            queue.add(frame);
            if (queue.size() > MAX_QUEUE) {
                close();
                return;
            }
            drain();
        }

        private void drain() {
            if (!draining.compareAndSet(false, true)) return;
            try {
                writers.execute(() -> {
                    try {
                        String f;
                        while (!closed && (f = queue.poll()) != null) {
                            out.write(f.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    } catch (IOException e) {
                        close();
                    } finally {
                        draining.set(false);
                        if (!closed && !queue.isEmpty()) drain();
                    }
                });
            } catch (RuntimeException rejected) {
                close(); // shutting down
            }
        }

        void close() {
            if (closed) return;
            closed = true;
            unsubscribe.run();
            clients.remove(this);
            try {
                onClose.run();
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
    }
}
