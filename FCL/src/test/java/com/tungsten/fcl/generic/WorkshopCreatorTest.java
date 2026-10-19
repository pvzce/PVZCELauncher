package com.tungsten.fcl.generic;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class WorkshopCreatorTest {
    private interface Checked {
        void run() throws Exception;
    }

    private void rejects(Checked action) throws Exception {
        try {
            action.run();
            fail();
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void writeDtosExcludeServerOwnedFieldsAndCountUtf8Bytes() throws Exception {
        JsonObject body =
                WorkshopDrafts.project(
                        "my-mod", "中文", "mod", "说明", "MIT", "plants,other", "general");
        assertEquals(7, body.size());
        assertFalse(body.has("owner_id"));
        assertFalse(body.has("state"));
        assertEquals(2, body.getAsJsonArray("tags").size());
        rejects(
                () ->
                        WorkshopDrafts.project(
                                "my-mod", "中".repeat(67), "mod", "desc", "MIT", "", "general"));
        rejects(() -> WorkshopDrafts.project("../evil", "a", "mod", "desc", "MIT", "", "general"));
        rejects(() -> WorkshopDrafts.project("my-mod", "a", "mod", "desc", "MIT", "", "invalid"));
    }

    @Test
    public void immutableVersionAndAuthorOnlyTransitionsAreValidated() throws Exception {
        JsonObject body =
                WorkshopDrafts.version(
                        "1.0.0",
                        "notes",
                        "stable",
                        "1.0.0,1.1.0",
                        "8 required\n9 conflict\n10 optional");
        assertEquals(5, body.size());
        assertEquals(3, body.getAsJsonArray("dependencies").size());
        rejects(() -> WorkshopDrafts.version("1", "", "stable", "1.0.0", "8 required\n8 optional"));
        rejects(() -> WorkshopDrafts.version("1", "", "stable", "1.0.0", "8 approve"));
        rejects(() -> WorkshopDrafts.version("1", "", "stable", "", ""));
        for (String action : new String[] {"submit", "withdraw", "resume"})
            assertEquals(
                    action,
                    WorkshopDrafts.transition(action, "because").get("action").getAsString());
        rejects(() -> WorkshopDrafts.transition("approve", "because"));
        rejects(() -> WorkshopDrafts.transition("submit", "  "));
    }

    private File zip(String... entries) throws IOException {
        File file = Files.createTempFile("creator", ".zip").toFile();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (int i = 0; i < entries.length; i += 2) {
                zip.putNextEntry(new ZipEntry(entries[i]));
                zip.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return file;
    }

    private void check(File file, String kind) throws IOException {
        try {
            WorkshopApi.Version version = new WorkshopApi.Version();
            version.number = "1.0.0";
            version.game_versions = List.of("1.0.0");
            WorkshopUploadChecks.check(file, kind, version, new WorkshopApi.Transfer());
        } finally {
            file.delete();
        }
    }

    @Test
    public void localPreflightRejectsUnsafeArchivesCodePacksAndWrongModIdentity() throws Exception {
        check(
                zip(
                        "pack.mcmeta",
                        "{\"pack\":{\"pack_format\":1,\"description\":\"demo\"}}",
                        "data/demo/plants/demo.json",
                        "{}"),
                "datapack");
        rejects(() -> check(zip("../outside", "{}"), "datapack"));
        rejects(() -> check(zip("pack.mcmeta", "{}", "payload.class", "code"), "datapack"));
        rejects(
                () ->
                        check(
                                zip(
                                        "fabric.mod.json",
                                        "{\"schemaVersion\":1,\"id\":\"demo\",\"version\":\"2.0.0\",\"depends\":{\"pvzce\":\"*\"}}"),
                                "mod"));
        check(
                zip(
                        "fabric.mod.json",
                        "{\"schemaVersion\":1,\"id\":\"demo\",\"version\":\"1.0.0\",\"depends\":{\"pvzce\":\"*\"}}"),
                "mod");
    }
}
