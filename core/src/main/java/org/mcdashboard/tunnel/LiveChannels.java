/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.tunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Live-feed streams through the tunnel: each channel is a socket to the plugin's own live port, piped as raw bytes
 * ({@code open}, {@code d}, {@code x} frames), so nothing is parsed or rewritten.
 */
final class LiveChannels {
    private static final int MAX_CHANNELS = 256;
    private static final long SEND_TIMEOUT_S = 30;

    private final String host;
    private final int port;
    private final ExecutorService workers;
    private final Function<Map<String, Object>, CompletableFuture<?>> send;
    private final Map<Long, Socket> channels = new ConcurrentHashMap<>();

    /** {@code send} queues a frame on the tunnel and returns when it is sent, or null when the tunnel is down. */
    LiveChannels(String host, int port, ExecutorService workers, Function<Map<String, Object>, CompletableFuture<?>> send) {
        this.host = host;
        this.port = port;
        this.workers = workers;
        this.send = send;
    }

    int size() {
        return channels.size();
    }

    void open(long c) {
        if (port <= 0 || channels.size() >= MAX_CHANNELS || channels.containsKey(c)) {
            send.apply(Map.of("t", "x", "c", c));
            return;
        }
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), 3000);
            if (channels.putIfAbsent(c, s) != null) {
                s.close();
                return;
            }
            workers.execute(() -> pump(c, s));
        } catch (IOException e) {
            send.apply(Map.of("t", "x", "c", c));
        }
    }

    /** Bytes from the proxy to the live feed. */
    void write(long c, byte[] data) {
        Socket s = channels.get(c);
        if (s == null) return;
        try {
            OutputStream out = s.getOutputStream();
            synchronized (s) {
                out.write(data);
                out.flush();
            }
        } catch (IOException e) {
            close(c, true);
        }
    }

    void close(long c, boolean tell) {
        Socket s = channels.remove(c);
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // already closed
        }
        if (tell) send.apply(Map.of("t", "x", "c", c));
    }

    void closeAll() {
        for (Long c : channels.keySet()) close(c, false);
    }

    /** Bytes from the live feed to the proxy; each frame is sent before the next read, so a slow proxy cannot fill memory. */
    private void pump(long c, Socket s) {
        byte[] buf = new byte[16 * 1024];
        try (InputStream in = s.getInputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("t", "d");
                d.put("c", c);
                d.put("b", Base64.getEncoder().encodeToString(Arrays.copyOf(buf, n)));
                CompletableFuture<?> sent = send.apply(d);
                if (sent == null) break;
                sent.get(SEND_TIMEOUT_S, TimeUnit.SECONDS);
            }
        } catch (IOException | ExecutionException | TimeoutException ignored) {
            // the live feed or the tunnel went away
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close(c, true);
        }
    }
}
