package com.tungsten.fcl.generic;

import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.tungsten.fcl.generic.PvzceContentStore.Kind.*;
import static org.junit.Assert.*;

public class PvzceContentStoreTest {
    private Path root;
    private PvzceContentStore store;

    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("pvzce content 中文 ").toRealPath();
        store = new PvzceContentStore(root.resolve("game").toFile());
    }

    @After public void cleanup() throws Exception {
        try (var files = Files.walk(root)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(file);
        }
    }

    private Path pack(PvzceContentStore.Kind kind, String name) throws Exception {
        return Files.createDirectories(store.directory(kind).toPath().resolve(name).resolve("data")).getParent();
    }

    private void mod(String name) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(store.directory(MODS).toPath().resolve(name)))) {
            jar.putNextEntry(new JarEntry("fabric.mod.json"));
            jar.write("{\"schemaVersion\":1,\"id\":\"demo\",\"name\":\"示例模组\",\"version\":\"1.0\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private byte[] backup(PvzceContentStore.Kind kind, String name) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.exportZip(kind, List.of(name), out);
        return out.toByteArray();
    }

    @Test public void modMetadataAndEnableToggleUseFabricJarNames() throws Exception {
        mod("demo.jar");
        assertEquals("示例模组", store.list(MODS).get(0).title);
        store.setEnabled(MODS, "demo.jar", false);
        assertFalse(store.list(MODS).get(0).enabled);
        assertEquals("demo.jar.disabled", store.list(MODS).get(0).filename);
        store.setEnabled(MODS, "demo.jar.disabled", true);
        assertTrue(store.list(MODS).get(0).enabled);
    }

    @Test public void packStateMatchesPvzceAndPreservesUnknownConfiguration() throws Exception {
        pack(RESOURCES, "shared"); pack(DATA, "shared");
        Path config = root.resolve("game/config/pvzce-packs.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"future\":{\"keep\":true},\"disabled\":[\"unavailable-pack\"]}");
        store.setEnabled(DATA, "shared", false);
        assertTrue(store.hasSharedPackName(DATA, "shared"));
        assertFalse(store.list(DATA).get(0).enabled);
        assertFalse(store.list(RESOURCES).get(0).enabled);
        var json = JsonParser.parseString(Files.readString(config)).getAsJsonObject();
        assertTrue(json.getAsJsonObject("future").get("keep").getAsBoolean());
        assertTrue(json.getAsJsonArray("disabled").toString().contains("unavailable-pack"));
        store.rename(DATA, "shared", "renamed");
        assertFalse(store.list(DATA).get(0).enabled);
        assertFalse(store.list(RESOURCES).get(0).enabled);
    }

    @Test public void malformedPackSettingsAreNotOverwritten() throws Exception {
        pack(DATA, "example");
        Path config = Files.createDirectories(root.resolve("game/config")).resolve("pvzce-packs.json");
        for (String content : List.of("{broken", "{\"disabled\":42}", "{\"disabled\":[{}]}")) {
            Files.writeString(config, content);
            try {
                store.setEnabled(DATA, "example", false);
                fail("Overwrote malformed settings");
            } catch (IOException expected) {
                // Rejected without overwriting user data.
            }
            assertEquals(content, Files.readString(config));
        }
    }

    @Test public void removedSaveCanBeRestoredWithoutOverwritingAnotherSave() throws Exception {
        Path world = Files.createDirectories(store.directory(SAVES).toPath().resolve("world"));
        Files.write(world.resolve("profile.dat"), new byte[] {1, 2, 3});
        String id = store.trash(SAVES, "world");
        assertTrue(store.list(SAVES).isEmpty());
        assertEquals(List.of(id), store.trashIds());
        Files.createDirectory(world);
        Files.writeString(world.resolve("keep"), "new world");
        try {
            store.restore(id);
            fail("Overwrote an existing save");
        } catch (IOException expected) {
            // Rejected without overwriting user data.
        }
        assertEquals("new world", Files.readString(world.resolve("keep")));
        Files.delete(world.resolve("keep")); Files.delete(world);
        store.restore(id);
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(world.resolve("profile.dat")));
    }

    @Test public void saveBackupRoundTripsAllBinaryFilesToADifferentInstance() throws Exception {
        Path world = Files.createDirectories(store.directory(SAVES).toPath().resolve("世界/levels/a"));
        Files.write(world.resolve("level.dat"), new byte[] {0, -1, 17, 42});
        Files.write(world.getParent().getParent().resolve("profile.dat"), new byte[] {9, 8});
        byte[] zip = backup(SAVES, "世界");
        var other = new PvzceContentStore(root.resolve("other").toFile());
        other.importZip(SAVES, new ByteArrayInputStream(zip));
        assertArrayEquals(Files.readAllBytes(world.resolve("level.dat")),
                Files.readAllBytes(other.directory(SAVES).toPath().resolve("世界/levels/a/level.dat")));
        try {
            store.importZip(SAVES, new ByteArrayInputStream(zip));
            fail("Overwrote own save");
        } catch (IOException expected) {
            // Rejected without overwriting user data.
        }
    }

    @Test public void packBackupRestoresDisabledStateAndRefusesTheWrongKind() throws Exception {
        pack(DATA, "pack");
        store.setEnabled(DATA, "pack", false);
        byte[] zip = backup(DATA, "pack");
        var other = new PvzceContentStore(root.resolve("other").toFile());
        other.importZip(DATA, new ByteArrayInputStream(zip));
        assertFalse(other.list(DATA).get(0).enabled);
        try {
            other.importZip(RESOURCES, new ByteArrayInputStream(zip));
            fail("Imported mismatched backup");
        } catch (IOException expected) {
            // Rejected without overwriting user data.
        }
        assertTrue(other.list(RESOURCES).isEmpty());
    }

    @Test public void zipSlipAndDuplicateEntriesDoNotCommitPartialContent() throws Exception {
        for (String bad : List.of("../escape", "/absolute", "x/../../escape", "C:/windows", "x\\escape")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("safe/profile.dat")); zip.write(1); zip.closeEntry();
                zip.putNextEntry(new ZipEntry(bad)); zip.write(2); zip.closeEntry();
            }
            try {
                store.importZip(SAVES, new ByteArrayInputStream(out.toByteArray()));
                fail("Accepted " + bad);
            } catch (IOException expected) {
                // Rejected without overwriting user data.
            }
            assertTrue(store.list(SAVES).isEmpty());
            assertFalse(Files.exists(root.resolve("escape")));
        }
    }

    @Test public void traversalAndSymlinksCannotModifyOutsideData() throws Exception {
        Path external = Files.createDirectories(root.resolve("external"));
        Files.writeString(external.resolve("keep"), "untouched");
        Files.createSymbolicLink(store.directory(SAVES).toPath().resolve("link"), external);
        assertTrue(store.list(SAVES).isEmpty());
        for (String name : List.of("../external", "link")) {
            try {
                store.trash(SAVES, name);
                fail("Moved external data");
            } catch (IOException expected) {
                // Rejected without overwriting user data.
            }
        }
        Path world = Files.createDirectories(store.directory(SAVES).toPath().resolve("world"));
        Files.createSymbolicLink(world.resolve("link"), external);
        try {
            backup(SAVES, "world");
            fail("Exported symlink target");
        } catch (IOException expected) {
            // Rejected without overwriting user data.
        }
        assertEquals("untouched", Files.readString(external.resolve("keep")));
    }

    @Test public void duplicateZipFilesAndExcessiveNestingAreRejected() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : List.of("a/profile.dat", "b/profile.dat")) {
                zip.putNextEntry(new ZipEntry(name)); zip.write(1); zip.closeEntry();
            }
        }
        byte[] archive = out.toByteArray();
        byte[] needle = "b/profile.dat".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i <= archive.length - needle.length; i++) {
            boolean matches = true;
            for (int j = 0; j < needle.length; j++) matches &= archive[i + j] == needle[j];
            if (matches) archive[i] = 'a';
        }
        try {
            store.importZip(SAVES, new ByteArrayInputStream(archive));
            fail("Accepted duplicate file paths");
        } catch (IOException expected) {
            assertTrue(store.list(SAVES).isEmpty());
        }
        out.reset();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("d/".repeat(34) + "profile.dat")); zip.write(1); zip.closeEntry();
        }
        try {
            store.importZip(SAVES, new ByteArrayInputStream(out.toByteArray()));
            fail("Accepted excessive nesting");
        } catch (IOException expected) {
            assertTrue(store.list(SAVES).isEmpty());
        }
    }
}
