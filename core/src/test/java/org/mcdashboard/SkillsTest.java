/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The configuration skills in `.agents/skills` are shared with the agents of this repository and shipped in the jar. */
class SkillsTest {
    @TempDir
    Path data;

    private DataFolder folder() {
        return new DataFolder(data, new SnakeYamlCodec(), "", List.of("player", "server"), new FakePlatform().log());
    }

    /** The skill folders under the bundled {@code skills/} resource, in the order the plugin exports them. */
    private static List<String> bundledFiles() throws Exception {
        List<String> files = DataFolder.tree(DataFolder.class.getResource("/skills"));
        assertFalse(files.isEmpty(), "core/pom.xml bundles ../.agents/skills as skills/");
        return files;
    }

    @Test
    void writesTheBundledSkillsIntoTheDataFolderOnEveryStart() throws Exception {
        folder().exportDefaults();

        for (String name : bundledFiles()) {
            Path written = data.resolve(".agents/skills/" + name);
            assertTrue(Files.isRegularFile(written), name + " is written");
            try (InputStream in = DataFolder.class.getResourceAsStream("/skills/" + name)) {
                assertEquals(new String(in.readAllBytes(), StandardCharsets.UTF_8), Files.readString(written), name + " is copied as is");
            }
        }
    }

    @Test
    void replacesAnEditedSkillButKeepsASkillItDoesNotShip() throws Exception {
        folder().exportDefaults();
        Path skill = data.resolve(".agents/skills/mcdashboard-setup/SKILL.md");
        String shipped = Files.readString(skill);
        Files.writeString(skill, "edited by the owner\n");
        Path own = data.resolve(".agents/skills/my-own/SKILL.md");
        Files.createDirectories(own.getParent());
        Files.writeString(own, "---\nname: Mine\ndescription: my own skill\n---\n");

        folder().exportDefaults();

        assertEquals(shipped, Files.readString(skill), "reference material belongs to the plugin version, so it is refreshed");
        assertEquals("---\nname: Mine\ndescription: my own skill\n---\n", Files.readString(own), "a skill the plugin does not ship is left alone");
    }

    @Test
    void everyShippedSkillCanBeFoundAndFollowedByAnAgent() throws Exception {
        List<String> ids = new ArrayList<>();
        for (String name : bundledFiles()) {
            assertTrue(name.endsWith("/SKILL.md"), name + " lives in a skill folder that an agent discovers by its SKILL.md only");
            String id = name.substring(0, name.indexOf('/'));
            assertTrue(id.matches("[a-z0-9]+(-[a-z0-9]+)*"), id + " is a portable skill id");
            String body = new String(DataFolder.class.getResourceAsStream("/skills/" + name).readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.startsWith("---\n"), id + " starts with frontmatter");
            int end = body.indexOf("\n---\n", 4);
            assertTrue(end > 0, id + " closes its frontmatter");
            String front = body.substring(4, end);
            assertTrue(front.contains("name: ") && front.contains("description: "), id + " names and describes itself for the model");
            assertTrue(body.contains("https://mcdashboard.org/"), id + " points at the documentation site");
            ids.add(id);
        }
        assertTrue(ids.size() >= 6, "the six configuration skills ship: " + ids);
    }

    @Test
    void listsTheFilesOfABundledFolderInsideAJar() throws Exception {
        Path jar = data.resolve("skills.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (String entry : List.of("skills/", "skills/one/SKILL.md", "skills/two/references/notes.md", "other/ignored.md")) {
                out.putNextEntry(new JarEntry(entry));
                if (!entry.endsWith("/")) out.write(entry.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        URL url = URI.create("jar:" + jar.toUri() + "!/skills").toURL();

        assertEquals(List.of("one/SKILL.md", "two/references/notes.md"), DataFolder.tree(url));
        assertEquals(List.of(), DataFolder.tree(null), "a jar without the folder lists nothing");
    }
}
