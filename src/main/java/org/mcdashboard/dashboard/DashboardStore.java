package org.mcdashboard.dashboard;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The dashboards a server publishes: one YAML file per dashboard in {@code plugins/MCDashboard/dashboards/}. The file
 * name is the dashboard id, and it also decides the permission ({@code mcdashboard.dashboard.<id>}).
 */
public final class DashboardStore {
    private final Path directory;
    private List<Dashboard> memory;

    /** A store backed by files. */
    public DashboardStore(Path directory) {
        this.directory = directory;
        this.memory = null;
    }

    private DashboardStore(List<Dashboard> dashboards) {
        this.directory = null;
        this.memory = List.copyOf(dashboards);
    }

    /** A store that keeps dashboards in memory (tests). */
    public static DashboardStore inMemory(List<Dashboard> dashboards) {
        return new DashboardStore(dashboards);
    }

    /** All dashboards, ordered by id with {@code default} first. */
    public List<Dashboard> all() {
        if (memory != null) return memory;
        List<Dashboard> out = new ArrayList<>();
        java.io.File[] files = directory.toFile().listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return out;
        java.util.Arrays.sort(files);
        for (java.io.File f : files) {
            String id = f.getName().substring(0, f.getName().length() - 4);
            out.add(Dashboard.fromSection(id, YamlConfiguration.loadConfiguration(f)));
        }
        out.sort(Comparator.comparing((Dashboard d) -> !d.id().equals("default")).thenComparing(Dashboard::id));
        return out;
    }

    /**
     * Writes a dashboard as YAML, replacing the file with the same id. A dashboard uploaded from the app is written
     * with the player who made it, for the owner to see where it came from.
     * @param dashboard - The dashboard; its id is the file name.
     * @param uploader - The player UUID for an app upload, or null for a server-owned dashboard.
     * @return The dashboard, unchanged.
     * @throws IOException when the file cannot be written.
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
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", dashboard.name().resolve(List.of(), "en"));
        if (dashboard.description() != null) yaml.set("description", dashboard.description().resolve(List.of(), "en"));
        if (dashboard.background() != null && !dashboard.background().isBlank()) yaml.set("background", dashboard.background());
        yaml.set("order", dashboard.order());
        if (uploader != null) yaml.set("owner", uploader);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Dashboard.Item item : dashboard.items()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("placeholder", item.placeholder());
            if (item.placeholders().size() > 1) map.put("placeholders", item.placeholders());
            map.put("widget", item.widget());
            if (item.title() != null) map.put("title", item.title().resolve(List.of(), "en"));
            map.put("column", item.column());
            map.put("row", item.row());
            map.put("width", item.width());
            map.put("height", item.height());
            items.add(map);
        }
        yaml.set("items", items);
        String safeUploader = uploader == null ? null : uploader.replaceAll("[^A-Za-z0-9-]", "");
        String header = (safeUploader == null ? "# Saved by the MCDashboard plugin." : "# Uploaded from the MinecraftDashboard app by " + safeUploader + ".")
                + "\n# Permission: " + dashboard.permission() + " (derived from this file name).\n";
        Path target = directory.resolve(dashboard.id() + ".yml");
        Path temporary = directory.resolve(dashboard.id() + ".yml.tmp");
        Files.writeString(temporary, header + yaml.saveToString(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return dashboard;
    }
}
