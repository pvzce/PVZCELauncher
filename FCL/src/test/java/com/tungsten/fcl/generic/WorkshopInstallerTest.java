package com.tungsten.fcl.generic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.tungsten.fcl.generic.PvzceContentStore.Kind.*;
import static org.junit.Assert.*;

public class WorkshopInstallerTest {
    private Path root;
    private File game;
    private WorkshopInstaller installer;
    private PvzceContentStore content;
    private static final String API = "https://workshop.example";

    @Before public void setup() throws Exception {
        root = Files.createTempDirectory("workshop 中文 ");
        game = root.resolve("game").toFile();
        content = new PvzceContentStore(game);
        installer = new WorkshopInstaller(game);
    }

    @After public void cleanup() throws Exception { WorkshopInstaller.removeTree(root); }

    private File mod(String version) throws Exception {
        File file = root.resolve("mod-" + version + ".jar").toFile();
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(file.toPath()))) {
            jar.putNextEntry(new JarEntry("fabric.mod.json"));
            jar.write(("{\"schemaVersion\":1,\"id\":\"example_mod\",\"version\":\"" + version
                    + "\",\"depends\":{\"pvzce\":\"*\"}}").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return file;
    }

    private File data(Map<String, String> entries) throws Exception {
        File file = root.resolve("data-" + java.util.UUID.randomUUID() + ".zip").toFile();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private WorkshopApi.Detail detail(String kind, long project, long version, String number, File archive) throws Exception {
        WorkshopApi.Detail detail = new WorkshopApi.Detail();
        detail.project = new WorkshopApi.Project();
        detail.project.id = project;
        detail.project.kind = kind;
        detail.project.state = "published";
        WorkshopApi.Version v = new WorkshopApi.Version();
        v.id = version; v.project_id = project; v.number = number; v.state = "published";
        v.game_versions.add("1.0.0");
        detail.versions.add(v);
        WorkshopApi.PackageFile f = new WorkshopApi.PackageFile();
        f.id = version; f.project_id = project; f.version_id = version; f.purpose = "package";
        f.scan_state = "clean"; f.validation_state = "valid"; f.size = archive.length(); f.sha256 = WorkshopInstaller.hash(archive);
        detail.files.add(f);
        return detail;
    }

    private void install(WorkshopApi.Detail detail, File file) throws Exception {
        installer.install(API, detail, detail.versions.get(0), "1.0.0", file, new WorkshopApi.Transfer());
    }

    private static void rejected(IoAction action) throws Exception {
        try { action.run(); fail("Unsafe operation accepted"); } catch (IOException expected) { /* Expected boundary rejection. */ }
    }

    private interface IoAction { void run() throws Exception; }

    @Test public void updatesPreserveDisabledStateAndRecoverableOrigin() throws Exception {
        File first = mod("1.0.0");
        install(detail("mod", 1, 11, "1.0.0", first), first);
        String name = content.list(MODS).get(0).filename;
        content.setEnabled(MODS, name, false);
        name += ".disabled";
        File second = mod("2.0.0");
        install(detail("mod", 1, 12, "2.0.0", second), second);
        assertFalse(content.list(MODS).get(0).enabled);
        assertEquals(WorkshopInstaller.hash(second), WorkshopInstaller.hash(new File(content.directory(MODS), name)));
        assertEquals(12, new WorkshopOrigins(game).get(MODS, name).get("version").getAsLong());
        String old = content.trashIds().get(0);
        content.trash(MODS, name);
        content.restore(old);
        assertEquals(11, new WorkshopOrigins(game).get(MODS, name).get("version").getAsLong());
        assertEquals(WorkshopInstaller.hash(first), WorkshopInstaller.hash(new File(content.directory(MODS), name)));
    }

    @Test public void renamedOriginsSurviveBackupTrashAndRestore() throws Exception {
        File file = mod("1.0.0");
        install(detail("mod", 1, 11, "1.0.0", file), file);
        content.rename(MODS, content.list(MODS).get(0).filename, "renamed.jar");
        ByteArrayOutputStream backup = new ByteArrayOutputStream();
        content.exportZip(MODS, List.of("renamed.jar"), backup);
        String trash = content.trash(MODS, "renamed.jar");
        assertNull(new WorkshopOrigins(game).get(MODS, "renamed.jar"));
        content.restore(trash);
        assertEquals(11, new WorkshopOrigins(game).get(MODS, "renamed.jar").get("version").getAsLong());
        content.trash(MODS, "renamed.jar");
        content.importZip(MODS, new ByteArrayInputStream(backup.toByteArray()));
        assertEquals(11, new WorkshopOrigins(game).get(MODS, "renamed.jar").get("version").getAsLong());
    }

    @Test public void manualEditsAreNeverReplacedByUpdate() throws Exception {
        File first = mod("1.0.0");
        install(detail("mod", 1, 11, "1.0.0", first), first);
        File installed = new File(content.directory(MODS), content.list(MODS).get(0).filename);
        Files.write(installed.toPath(), new byte[] {1, 2, 3});
        File second = mod("2.0.0");
        rejected(() -> install(detail("mod", 1, 12, "2.0.0", second), second));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(installed.toPath()));
        assertTrue(content.trashIds().isEmpty());
    }

    @Test public void linkedOriginsNeverRewriteAnotherLocalFile() throws Exception {
        Path other = game.toPath().resolve("keep.json");
        Files.write(other, "{}".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(game.toPath().resolve(".pvzce-workshop.json"), other);
        rejected(() -> new WorkshopOrigins(game));
        assertEquals("{}", new String(Files.readAllBytes(other), StandardCharsets.UTF_8));
    }

    @Test public void dataZipIsInstalledAsDirectoryAndChecksPathTraversal() throws Exception {
        File file = data(Map.of("pack.mcmeta", "{\"pack\":{\"pack_format\":1,\"description\":\"demo\"}}", "data/demo/levels/test.json", "{}"));
        install(detail("datapack", 2, 21, "1.0.0", file), file);
        File directory = new File(content.directory(DATA), content.list(DATA).get(0).filename);
        assertTrue(new File(directory, "data/demo/levels/test.json").isFile());
        File bad = data(Map.of("../escaped.json", "{}"));
        rejected(() -> install(detail("datapack", 3, 31, "1.0.0", bad), bad));
        assertFalse(root.resolve("escaped.json").toFile().exists());
    }

    @Test public void dataSymlinksAndCaseCollisionsAreRejected() throws Exception {
        File file = root.resolve("symlink.zip").toFile();
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(file)) {
            ZipArchiveEntry entry = new ZipArchiveEntry("link"); entry.setUnixMode(0120777);
            zip.putArchiveEntry(entry); zip.write("../outside".getBytes(StandardCharsets.UTF_8)); zip.closeArchiveEntry();
        }
        rejected(() -> install(detail("datapack", 1, 11, "1.0.0", file), file));
        File collision = data(Map.of("data/a.json", "{}", "DATA/a.json", "{}"));
        rejected(() -> install(detail("datapack", 1, 11, "1.0.0", collision), collision));
        assertTrue(content.list(DATA).isEmpty());
    }

    @Test public void requiredConflictAndReverseDependenciesUseActiveUnmodifiedOrigins() throws Exception {
        File first = data(Map.of("pack.mcmeta", "{\"pack\":{\"pack_format\":1,\"description\":\"demo\"}}", "data/demo/levels/a.json", "{}"));
        WorkshopApi.Detail dependency = detail("datapack", 2, 21, "1.0.0", first);
        File dependentFile = mod("1.0.0");
        WorkshopApi.Detail dependent = detail("mod", 1, 11, "1.0.0", dependentFile);
        WorkshopApi.Dependency dep = new WorkshopApi.Dependency(); dep.kind = "required"; dep.version_id = 21;
        dependent.versions.get(0).dependencies.add(dep);
        rejected(() -> installer.check(API, dependent, dependent.versions.get(0), "1.0.0"));
        install(dependency, first);
        installer.check(API, dependent, dependent.versions.get(0), "1.0.0");
        rejected(() -> installer.check("https://other.example", dependent, dependent.versions.get(0), "1.0.0"));
        install(dependent, dependentFile);
        WorkshopApi.Detail update = detail("datapack", 2, 22, "2.0.0", first);
        rejected(() -> installer.check(API, update, update.versions.get(0), "1.0.0"));
        dep.kind = "conflict";
        rejected(() -> installer.check(API, dependent, dependent.versions.get(0), "1.0.0"));
        content.setEnabled(DATA, content.list(DATA).get(0).filename, false);
        installer.check(API, dependent, dependent.versions.get(0), "1.0.0");
    }

    @Test public void badHashCancellationCompatibilityAndDuplicateModIdNeverInstall() throws Exception {
        File first = mod("1.0.0");
        WorkshopApi.Detail detail = detail("mod", 1, 11, "1.0.0", first);
        rejected(() -> installer.install(API, detail, detail.versions.get(0), "2.0.0", first, new WorkshopApi.Transfer()));
        WorkshopApi.Transfer cancel = new WorkshopApi.Transfer(); cancel.cancel();
        rejected(() -> installer.install(API, detail, detail.versions.get(0), "1.0.0", first, cancel));
        String hash = detail.files.get(0).sha256; detail.files.get(0).sha256 = "0".repeat(64);
        rejected(() -> install(detail, first)); detail.files.get(0).sha256 = hash;
        install(detail, first);
        File second = mod("2.0.0");
        rejected(() -> install(detail("mod", 9, 91, "2.0.0", second), second));
        assertEquals(1, content.list(MODS).size());
        try (var paths = Files.list(game.toPath())) { assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith(".workshop-install-"))); }
    }

    public static final class VersionFixture {
        public static final String GAME_VERSION = "1.2.3";
    }

    @Test public void gameVersionReadsConstantWithoutLoadingGameCode() throws Exception {
        File file = root.resolve("game.jar").toFile();
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(file.toPath()));
             var bytes = getClass().getResourceAsStream("WorkshopInstallerTest$VersionFixture.class")) {
            jar.putNextEntry(new JarEntry("com/pvzce/launcher/PvzceVersions.class"));
            byte[] buffer = new byte[4096];
            int n;
            while ((n = bytes.read(buffer)) != -1) jar.write(buffer, 0, n);
            jar.closeEntry();
        }
        assertEquals("1.2.3", WorkshopVersions.gameVersion(file));
        rejected(() -> WorkshopVersions.gameVersion(mod("1.0.0")));
    }

    @Test public void instanceSwitchDoesNotRetargetAndChangedTargetsAreRejected() throws Exception {
        GameInstanceStore registry = new GameInstanceStore(root.resolve("app").toFile());
        Map<String, String> config = new java.util.HashMap<>();
        config.put("work", root.resolve("instance-a").toString()); config.put("jar", "game.jar"); config.put("pvzce", "true");
        GameInstanceStore.Instance first = registry.initialize(config);
        GameInstanceStore.Instance second = registry.create("other", config);
        WorkshopTarget target = new WorkshopTarget(registry, first.id);
        registry.select(second.id);
        target.verify(registry);
        assertEquals(first.directory().getCanonicalFile(), target.directory);
        assertNotEquals(second.directory().getCanonicalFile(), target.directory);
        Map<String, String> changed = new java.util.HashMap<>(first.config);
        changed.put("jar", "changed.jar"); registry.update(first.id, changed);
        rejected(() -> target.verify(registry));
    }

    @Test public void supportedDependencyRangesAndUnknownExpressionsFailClosed() throws Exception {
        assertTrue(WorkshopVersions.matches("1.2.3", JsonParser.parseString("\">=1.0.0 <2.0.0\"")));
        assertTrue(WorkshopVersions.matches("1.2.3", JsonParser.parseString("[\"2.x\",\"^1.1.0\"]")));
        assertFalse(WorkshopVersions.matches("1.3.0", JsonParser.parseString("\"~1.2.0\"")));
        rejected(() -> WorkshopVersions.matches("1.0.0-alpha", JsonParser.parseString("\">=1.0.0\"")));
    }
}
