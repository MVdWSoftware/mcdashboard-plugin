/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.mcdashboard.util.Threads;

/**
 * Whether each backend server accepts connections, checked in the background so the answer is a cached lookup: a
 * placeholder read never waits on the network and never runs on the proxy's own threads.
 */
public final class ServerPinger implements AutoCloseable {
    /** Decides whether one server is up; the default opens a TCP connection. */
    @FunctionalInterface
    public interface Probe {
        /**
         * Checks if the given host and port are reachable.
         *
         * @param host The hostname or IP address to check.
         * @param port The port number to check.
         * @return True if the server is reachable, false otherwise.
         */
        boolean up(String host, int port);
    }

    /** Timeout in milliseconds for each individual TCP connection attempt. */
    public static final int CONNECT_TIMEOUT_MS = 2000;

    private final ProxyAdapter adapter;
    private final Probe probe;
    private final Map<String, Boolean> state = new ConcurrentHashMap<>();
    private ScheduledExecutorService timer;
    private ExecutorService workers;

    /**
     * Creates an instance with the default TCP probe.
     *
     * @param adapter The proxy adapter providing server information.
     */
    public ServerPinger(ProxyAdapter adapter) {
        this(adapter, ServerPinger::connect);
    }

    /**
     * Creates an instance with a custom probe for server reachability checks.
     *
     * @param adapter The proxy adapter providing server information.
     * @param probe The probe function to test server reachability.
     */
    public ServerPinger(ProxyAdapter adapter, Probe probe) {
        this.adapter = adapter;
        this.probe = probe;
    }

    /** True when a TCP connection to the server opens within {@link #CONNECT_TIMEOUT_MS}. */
    static boolean connect(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Checks now and then every {@code intervalSeconds}, off every proxy thread.
     *
     * @param intervalSeconds How often to check servers, in seconds (minimum 5).
     */
    public synchronized void start(int intervalSeconds) {
        if (timer != null) return;
        timer = Executors.newSingleThreadScheduledExecutor(Threads.daemon("MCDashboard-ping"));
        workers = Executors.newFixedThreadPool(4, Threads.daemon("MCDashboard-ping-worker"));
        timer.scheduleWithFixedDelay(this::refresh, 0, Math.max(5, intervalSeconds), TimeUnit.SECONDS);
    }

    /** Checks every server now and waits for the answers (a few seconds at most). Runs on the caller's thread. */
    public void refresh() {
        try {
            List<ProxyAdapter.ServerInfo> servers = adapter.servers();
            Set<String> names = new HashSet<>();
            ExecutorService pool = workers;
            List<CompletableFuture<Void>> work = new java.util.ArrayList<>();
            for (ProxyAdapter.ServerInfo s : servers) {
                names.add(s.name().toLowerCase(java.util.Locale.ROOT));
                Runnable check = () -> state.put(s.name().toLowerCase(java.util.Locale.ROOT), probe.up(s.host(), s.port()));
                work.add(pool == null ? CompletableFuture.runAsync(check) : CompletableFuture.runAsync(check, pool));
            }
            for (CompletableFuture<Void> f : work) {
                try {
                    f.get(CONNECT_TIMEOUT_MS * 2L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    // that server stays unknown until the next round
                }
            }
            state.keySet().retainAll(names);
        } catch (RuntimeException e) {
            // keep the last answers; the next round tries again
        }
    }

    /**
     * True or false from the last check; null before the first one finished or for an unknown server.
     *
     * @param server The lowercase server name to look up.
     * @return True if reachable, false otherwise, or null when no check has completed yet.
     */
    public Boolean isUp(String server) {
        return server == null ? null : state.get(server.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public synchronized void close() {
        if (timer != null) timer.shutdownNow();
        if (workers != null) workers.shutdownNow();
        timer = null;
        workers = null;
    }
}
