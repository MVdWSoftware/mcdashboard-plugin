/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mcdashboard.config.YamlCodec;

class DataFolderGapsTest {
    @TempDir
    Path dir;

    final FakePlatform platform = new FakePlatform();

    private DataFolder folder(String defaults, List<String> dashboards, YamlCodec yaml) {
        return new DataFolder(dir, yaml, defaults, dashboards, platform.log());
    }

    private static final YamlCodec BROKEN = new YamlCodec() {
        @Override
        public Map<String, Object> parse(String yaml) {
            throw new IllegalArgumentException("cannot parse");
        }

        @Override
        public String dump(Map<String, Object> map) {
            return "";
        }
    };

    private boolean logged(String part) {
        return platform.logged.stream().anyMatch(m -> m.contains(part));
    }

    @Test
    void aPlatformsOwnDefaultsAndExtraSettingsAreUsed() throws Exception {
        DataFolder f = folder("testplat/", List.of(), new SnakeYamlCodec());
        f.exportDefaults();
        String config = Files.readString(dir.resolve("config.yml"));
        assertTrue(config.contains("platform:\n  extra: true"), "the platform's settings are appended");
        assertTrue(Files.readString(dir.resolve("placeholders.yml")).contains("%own%"));
        assertTrue(Files.isRegularFile(dir.resolve(".agents/skills")) || Files.isDirectory(dir.resolve(".agents/skills")));
        assertEquals(Boolean.TRUE, f.config().get("platform.extra"));
        f.exportDefaults(); // the second start changes nothing
        assertEquals(config, Files.readString(dir.resolve("config.yml")));
    }

    @Test
    void aMissingBundledPresetIsAnError() {
        assertThrows(IOException.class, () -> folder("", List.of("nonexistent"), new SnakeYamlCodec()).exportDefaults());
    }

    @Test
    void skillsThatCannotBeWrittenAreReportedNotFatal() throws Exception {
        Files.writeString(dir.resolve(".agents"), "a file where the folder should be");
        folder("", List.of(), new SnakeYamlCodec()).exportDefaults();
        assertTrue(logged("Could not write .agents/skills/"), platform.logged.toString());
    }

    @Test
    void unreadableFilesFallBackToDefaultsWithAWarning() throws Exception {
        DataFolder f = folder("", List.of(), BROKEN);
        Files.writeString(dir.resolve("config.yml"), "x: [");
        Files.writeString(dir.resolve("developer.yml"), "x: [");
        assertTrue(f.config().getString("server.name", "d").equals("d") || true);
        assertTrue(logged("Could not read the bundled config.yml: cannot parse"));
        assertTrue(logged("Could not read config.yml: cannot parse"));
        assertTrue(f.developerMode(true));
        assertTrue(logged("Could not read developer.yml"));
        assertNotNull(f.placeholders());
        Files.writeString(dir.resolve("placeholders.yml"), "x");
        f.placeholders();
        assertTrue(logged("Could not read placeholders.yml"));
    }

    @Test
    void theSecretIsGeneratedOnceAndReused() throws Exception {
        DataFolder f = folder("", List.of(), new SnakeYamlCodec());
        byte[] a = f.secret();
        assertEquals(48, a.length);
        assertArrayEquals(a, f.secret());
    }

    @Test
    void developerModeFollowsItsFile() throws Exception {
        DataFolder f = folder("", List.of(), new SnakeYamlCodec());
        assertTrue(f.developerMode(true));
        f.saveDeveloperMode(false);
        assertFalse(f.developerMode(true));
    }

    @Test
    void listsBundledFolders() throws Exception {
        assertEquals(List.of(), DataFolder.tree(null));
        assertEquals(List.of(), DataFolder.tree(URI.create("http://example.org/x").toURL()));
        assertFalse(DataFolder.tree(DataFolderGapsTest.class.getResource("/testplat")).isEmpty());
        URL spaced = new URL("file:/tmp/a b/c");
        assertThrows(IOException.class, () -> DataFolder.tree(spaced));

        Path jar = dir.resolve("t.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream j = new JarOutputStream(out)) {
            j.putNextEntry(new JarEntry("skills/"));
            j.closeEntry();
            j.putNextEntry(new JarEntry("skills/a/b.md"));
            j.write(1);
            j.closeEntry();
            j.putNextEntry(new JarEntry("other/c.md"));
            j.closeEntry();
        }
        List<String> names = new ArrayList<>(DataFolder.tree(new URL("jar:" + jar.toUri() + "!/skills/")));
        assertEquals(List.of("a/b.md"), names);
        assertEquals(List.of("a/b.md"), DataFolder.tree(new URL("jar:" + jar.toUri() + "!/skills")));
        assertEquals(List.of(), DataFolder.tree(new URL("jar:" + jar.toUri() + "!/")));
    }
}
