package com.tungsten.fcl.generic;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class GameInstanceStoreTest {
    private Path root;
    private GameInstanceStore store;
    private GameInstanceStore.Instance first;

    private static void write(Path file, String text) throws IOException {
        Files.write(file, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("pvzce instances 中文 ").toRealPath();
        Path legacy = Files.createDirectories(root.resolve("games/legacy"));
        write(legacy.resolve("original.dat"), "do not change");
        store = new GameInstanceStore(root.toFile());
        Map<String, String> config = new HashMap<>();
        config.put("work", legacy.toString());
        config.put("jar", "/shared/read-only-game.jar");
        config.put("args", "中文参数\n--literal");
        first = store.initialize(config);
    }

    @After public void cleanup() throws Exception {
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(file);
        }
    }

    @Test public void migrationIsIdempotentAndNeverMovesLegacyData() throws Exception {
        assertEquals(first.id, store.initialize(Map.of("work", root.resolve("ignored").toString())).id);
        assertEquals("do not change", read(first.directory().toPath().resolve("original.dat")));
        assertEquals("中文参数\n--literal", new GameInstanceStore(root.toFile()).selected().config.get("args"));
        assertEquals(1, store.list(false).size());
    }

    @Test public void configurationCopiesHaveIndependentMutableGameDirectories() throws Exception {
        var second = store.create("第二实例", first.config);
        assertNotEquals(first.directory(), second.directory());
        assertEquals(first.config.get("jar"), second.config.get("jar"));
        assertFalse(Files.exists(second.directory().toPath().resolve("original.dat")));
        Map<String, String> changed = new HashMap<>(second.config);
        changed.put("args", "second only");
        store.update(second.id, changed);
        assertEquals("中文参数\n--literal", store.get(first.id).config.get("args"));
        assertEquals(second.id, new GameInstanceStore(root.toFile()).selected().id);
        for (String kind : new String[] {"mods", "resourcepacks", "datapacks", "saves", "config"}) {
            assertTrue(Files.isDirectory(second.directory().toPath().resolve(kind)));
        }
    }

    @Test public void sharedAndNestedPathsAreRefusedIncludingAliasesAndArchivedInstances() throws Exception {
        var second = store.create("independent", first.config);
        Path alias = root.resolve("alias");
        Files.createSymbolicLink(alias, first.directory().toPath());
        for (String path : new String[] {first.directory().getPath(), first.directory() + "/nested", root.toString(), alias.toString()}) {
            Map<String, String> bad = new HashMap<>(second.config);
            bad.put("work", path);
            try {
                store.update(second.id, bad);
                fail("Accepted overlapping directory " + path);
            } catch (IOException expected) {
                // Rejected without overwriting user data.
            }
        }
        store.archive(first.id, true);
        Map<String, String> bad = new HashMap<>(second.config);
        bad.put("work", first.directory().getPath());
        try {
            store.update(second.id, bad);
            fail("Archived data directory was reused");
        } catch (IOException expected) {
            // Rejected without overwriting user data.
        }
        assertEquals(second.directory(), store.get(second.id).directory());
    }

    @Test public void archiveAndRestorePreserveDataAndAlwaysKeepOneActiveInstance() throws Exception {
        try {
            store.archive(first.id, true);
            fail("Archived the last instance");
        } catch (IOException expected) {
            // Rejected without overwriting user data.
        }
        var second = store.create("second", first.config);
        store.archive(second.id, true);
        assertEquals(first.id, store.selected().id);
        assertEquals(1, store.list(true).size());
        assertTrue(second.directory().exists());
        store.archive(second.id, false);
        store.select(second.id);
        assertEquals(second.id, store.selected().id);
        store.rename(second.id, "改名不移动目录");
        assertEquals(second.directory(), store.get(second.id).directory());
    }

    @Test public void separateStoreHandlesDoNotOverwriteEachOthersChanges() throws Exception {
        var other = new GameInstanceStore(root.toFile());
        var second = other.create("second", first.config);
        store.rename(first.id, "renamed");
        assertEquals(2, other.list(false).size());
        assertEquals("renamed", other.get(first.id).name);
        assertEquals(second.id, store.selected().id);
    }

    @Test public void corruptedRegistryIsNotSilentlyReset() throws Exception {
        Path file = root.resolve("launcher_instances/registry.properties");
        write(file, "corrupt content");
        try {
            store.initialize(first.config);
            fail("Reset corrupt registry");
        } catch (IOException expected) {
            // Rejected without overwriting user data.
        }
        assertEquals("corrupt content", read(file));
    }
}
