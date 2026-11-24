/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.dashboard;

import org.mcdashboard.config.YamlCodec;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The dashboards a server publishes: one YAML file per dashboard in {@code plugins/MCDashboard/dashboards/}. The file
 * name is the dashboard id, and it also decides the permission ({@code mcdashboard.dashboard.<id>}).
 */
public final class DashboardStore {
    private final Path directory;
    private final YamlCodec yaml;
    private final Consumer<String> warn;
    private final Map<String, Set<String>> reportedLayoutProblems = new ConcurrentHashMap<>();
    private List<Dashboard> memory;

    /** A store backed by files; a file that is not valid YAML is reported through {@code warn} and read as an empty dashboard.
     *
     * @param directory Path to the dashboards directory containing one YAML file per dashboard.
     * @param yaml      The YAML codec used to parse and dump dashboard files.
     * @param warn      Consumer for warnings about unreadable or malformed dashboard files.
     */
    public DashboardStore(Path directory, YamlCodec yaml, Consumer<String> warn) {
        this.directory = directory;
        this.yaml = yaml;
        this.warn = warn;
        this.memory = null;
    }

    private DashboardStore(List<Dashboard> dashboards) {
        this.directory = null;
        this.yaml = null;
        this.warn = null;
        this.memory = List.copyOf(dashboards);
    }

    /** A store that keeps dashboards in memory (tests).
     *
     * @param dashboards The initial set of dashboards.
     * @return A new in-memory store holding the given dashboards.
     */
    public static DashboardStore inMemory(List<Dashboard> dashboards) {
        return new DashboardStore(dashboards);
    }

    /** All dashboards, ordered by id with {@code default} first.
     *
     * @return The list of dashboards sorted with default first, then alphabetically by id.
     */
    public List<Dashboard> all() {
        if (memory != null) return memory;
        List<Dashboard> out = new ArrayList<>();
        File[] files = directory.toFile().listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 4);
            Dashboard dashboard = Dashboard.fromMap(id, read(f));
            reportLayoutProblems(f.getName(), dashboard);
            out.add(dashboard);
        }
        out.sort(Comparator.comparing((Dashboard d) -> !d.id().equals("default")).thenComparing(Dashboard::id));
        return out;
    }

    /** Writes the dashboard to {@code <id>.yml}, replacing it; {@code uploader} is the player UUID of an app upload, or null.
     *
     * @param dashboard The dashboard to persist.
     * @param uploader  The uploading player's UUID, or null for server-saved files.
     * @return The same dashboard instance that was written.
     * @throws java.io.IOException      When the file system rejects the write operation.
     * @throws IllegalArgumentException If the dashboard id does not match the required pattern.
     */
    public Dashboard save(Dashboard dashboard, String uploader) throws IOException {
        if (!dashboard.id().matches("[a-z0-9][a-z0-9-]{0,63}")) throw new IllegalArgumentException("invalid dashboard id");
        if (memory != null) {
            List<Dashboard> next = new ArrayList<>(memory);
            next.removeIf(d -> d.id().equals(dashboard.id()));
            next.add(dashboard);
            next.sort(Comparator.comparing((Dashboard d) -> !d.id().equals("default")).thenComparing(Dashboard::id));
            memory = List.copyOf(next);
            return dashboard;
        }
        Files.createDirectories(directory);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("name", dashboard.name().resolve(List.of(), "en"));
        if (dashboard.description() != null) document.put("description", dashboard.description().resolve(List.of(), "en"));
        if (dashboard.background() != null && !dashboard.background().isBlank()) document.put("background", dashboard.background());
        if (dashboard.backgroundImage() != null) document.put("background-image", dashboard.backgroundImage().toJson());
        document.put("order", dashboard.order());
        if (uploader != null) document.put("owner", uploader);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Dashboard.Item item : dashboard.items()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("placeholder", item.placeholder());
            if (item.placeholders().size() > 1) map.put("placeholders", item.placeholders());
            map.put("widget", item.widget());
            if (item.title() != null) map.put("title", item.title().resolve(List.of(), "en"));
            if (item.icon() != null) map.put("icon", item.icon());
            if (item.options() != null) map.put("options", item.options());
            map.put("column", item.column());
            map.put("row", item.row());
            map.put("width", item.width());
            map.put("height", item.height());
            items.add(map);
        }
        document.put("items", items);
        String safeUploader = uploader == null ? null : uploader.replaceAll("[^A-Za-z0-9-]", "");
        String header = (safeUploader == null ? "# Saved by the MCDashboard plugin." : "# Uploaded from the MCDashboard app by " + safeUploader + ".")
                + "\n# Permission: " + dashboard.permission() + " (derived from this file name).\n";
        Path target = directory.resolve(dashboard.id() + ".yml");
        Path temporary = directory.resolve(dashboard.id() + ".yml.tmp");
        Files.writeString(temporary, header + yaml.dump(document), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return dashboard;
    }

    private Map<String, Object> read(File file) {
        try {
            return yaml.parse(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            warn.accept("Could not read dashboards/" + file.getName() + ": " + e.getMessage());
            return Map.of();
        }
    }

    /**
     * Reports a dashboard whose tiles overlap or leave the grid. The folder is read on every request, so only a problem
     * that is new since the last read is printed; fixing and reintroducing the same mistake warns again.
     */
    private void reportLayoutProblems(String file, Dashboard dashboard) {
        List<String> problems = dashboard.layoutProblems();
        Set<String> previous = reportedLayoutProblems.getOrDefault(dashboard.id(), Set.of());
        if (problems.isEmpty()) {
            reportedLayoutProblems.remove(dashboard.id());
            return;
        }
        List<String> added = problems.stream().filter(problem -> !previous.contains(problem)).toList();
        if (added.isEmpty()) return;
        reportedLayoutProblems.put(dashboard.id(), Set.copyOf(problems));
        for (String problem : added) warn.accept("dashboards/" + file + ": " + problem + ".");
    }
}
