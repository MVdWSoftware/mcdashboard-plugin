/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** A proxy in memory: servers, players, permission answers, and a scheduler that records what it was asked. */
final class FakeAdapter implements ProxyAdapter {
    record Scheduled(long delayMs, long periodMs, Runnable task) {
    }

    final List<ServerInfo> servers = new CopyOnWriteArrayList<>();
    final List<PlayerView> players = new CopyOnWriteArrayList<>();
    final Map<String, Boolean> permissions = new HashMap<>(); // uuid:node
    final List<Scheduled> scheduled = new CopyOnWriteArrayList<>();
    final List<String> messages = new CopyOnWriteArrayList<>();
    int max = 100;
    /** Runs a task once when it is scheduled, standing in for the proxy's scheduler thread. */
    boolean inline;

    static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    FakeAdapter() {
        servers.add(new ServerInfo("lobby", "127.0.0.1", 25565, 2));
        servers.add(new ServerInfo("sky_block", "127.0.0.1", 25566, 1));
        players.add(new PlayerView(ALEX, "Alex", 42, "lobby"));
        players.add(new PlayerView(UUID.randomUUID(), "Sam", 80, "sky_block"));
        players.add(new PlayerView(UUID.randomUUID(), "Kim", 10, "lobby"));
    }

    @Override
    public String platformName() {
        return "FakeProxy";
    }

    @Override
    public String proxyVersion() {
        return "9.9";
    }

    @Override
    public String motd() {
        return "A network";
    }

    @Override
    public int port() {
        return 25577;
    }

    @Override
    public int onlinePlayers() {
        return players.size();
    }

    @Override
    public int maxPlayers() {
        return max;
    }

    @Override
    public List<ServerInfo> servers() {
        return new ArrayList<>(servers);
    }

    @Override
    public List<PlayerView> players() {
        return new ArrayList<>(players);
    }

    @Override
    public Optional<PlayerView> player(UUID uuid) {
        return players.stream().filter(p -> p.uuid().equals(uuid)).findFirst();
    }

    @Override
    public Optional<PlayerView> playerByName(String name) {
        return players.stream().filter(p -> p.name().equalsIgnoreCase(name)).findFirst();
    }

    @Override
    public Boolean permission(UUID player, String node) {
        return permissions.get(player + ":" + node);
    }

    @Override
    public void message(UUID player, String text) {
        messages.add(player + " " + text);
    }

    @Override
    public Cancellable schedule(Runnable task, long delayMs, long periodMs) {
        Scheduled s = new Scheduled(delayMs, periodMs, task);
        scheduled.add(s);
        if (inline) task.run();
        return () -> scheduled.remove(s);
    }
}
