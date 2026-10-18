package com.tungsten.fcl.generic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/** Non-secret provenance travels with local rename, trash, restore and launcher ZIP backups. */
final class WorkshopOrigins {
    private final File file;
    final JsonObject entries;

    WorkshopOrigins(File game) throws IOException {
        File raw = new File(game, ".pvzce-workshop.json").getAbsoluteFile();
        file = GameContentPaths.child(game, ".pvzce-workshop.json");
        if (Files.isSymbolicLink(raw.toPath()) || !file.equals(raw)) {
            throw new IOException("工坊记录不能是符号链接");
        }
        if (file.exists()) {
            try (InputStream input = Files.newInputStream(file.toPath())) {
                entries =
                        JsonParser.parseString(
                                        new String(
                                                WorkshopApi.read(input, 4 * 1024 * 1024),
                                                StandardCharsets.UTF_8))
                                .getAsJsonObject();
            } catch (RuntimeException error) {
                throw new IOException("工坊来源记录损坏，原文件保留", error);
            }
        } else entries = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
            try {
                validate(entry.getValue().getAsJsonObject());
            } catch (RuntimeException error) {
                throw new IOException("工坊来源记录格式无效", error);
            }
        }
    }

    JsonObject get(PvzceContentStore.Kind kind, String name) {
        JsonElement value = entries.get(kind.name() + "/" + name);
        return value == null ? null : value.getAsJsonObject().deepCopy();
    }

    void put(PvzceContentStore.Kind kind, String name, JsonObject value) {
        String key = kind.name() + "/" + name;
        if (value == null) entries.remove(key);
        else entries.add(key, value.deepCopy());
    }

    void save() throws IOException {
        InstanceFiles.write(file, entries.toString().getBytes(StandardCharsets.UTF_8));
    }

    void moved(PvzceContentStore.Kind kind, String from, String to) throws IOException {
        JsonObject value = get(kind, from);
        put(kind, from, null);
        put(kind, to, value);
        save();
    }

    static void validate(JsonObject value) throws IOException {
        try {
            if (!WorkshopApi.normalize(value.get("api").getAsString())
                            .equals(value.get("api").getAsString())
                    || value.get("project").getAsLong() < 1
                    || value.get("version").getAsLong() < 1
                    || value.get("file").getAsLong() < 1
                    || !value.get("sha256").getAsString().matches("[a-f0-9]{64}")
                    || !value.get("fingerprint").getAsString().matches("[a-f0-9]{64}")
                    || value.get("number").getAsString().isEmpty())
                throw new IOException("工坊来源字段无效");
            WorkshopApi.Version manifest =
                    WorkshopApi.JSON.fromJson(value.get("manifest"), WorkshopApi.Version.class);
            if (manifest == null
                    || manifest.id != value.get("version").getAsLong()
                    || manifest.project_id != value.get("project").getAsLong()
                    || manifest.dependencies == null
                    || manifest.game_versions == null) throw new IOException("工坊版本来源不匹配");
        } catch (RuntimeException error) {
            throw new IOException("工坊来源记录不完整", error);
        }
    }
}
