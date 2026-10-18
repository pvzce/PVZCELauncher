package com.tungsten.fcl.generic;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.CRC32;

/** Stage and validate first; a known, unmodified previous version moves to recoverable trash. */
public final class WorkshopInstaller {
    private final File game;
    private final PvzceContentStore store;

    public WorkshopInstaller(File game) throws IOException {
        this.game = game.getCanonicalFile();
        store = new PvzceContentStore(this.game);
    }

    public List<String> check(
            String api, WorkshopApi.Detail detail, WorkshopApi.Version version, String gameVersion)
            throws IOException {
        synchronized (PvzceContentStore.LOCK) {
            return checkLocked(api, detail, version, gameVersion);
        }
    }

    private List<String> checkLocked(
            String api, WorkshopApi.Detail detail, WorkshopApi.Version version, String gameVersion)
            throws IOException {
        detail.file(version);
        if (!version.game_versions.contains(gameVersion))
            throw new IOException("该作品版本不支持所选游戏版本 " + gameVersion);
        kind(detail.project.kind);
        Map<Long, JsonObject> active = new java.util.HashMap<>();
        WorkshopOrigins origins = new WorkshopOrigins(game);
        for (PvzceContentStore.Kind type :
                new PvzceContentStore.Kind[] {
                    PvzceContentStore.Kind.MODS, PvzceContentStore.Kind.DATA
                }) {
            for (PvzceContentStore.Entry item : store.list(type)) {
                JsonObject origin = origins.get(type, item.filename);
                if (item.enabled
                        && origin != null
                        && api.equals(origin.get("api").getAsString())
                        && fingerprint(GameContentPaths.child(store.directory(type), item.filename))
                                .equals(origin.get("fingerprint").getAsString())) {
                    active.put(origin.get("version").getAsLong(), origin);
                }
            }
        }
        List<String> notes = new ArrayList<>();
        for (WorkshopApi.Dependency dep : version.dependencies) {
            boolean installed = active.containsKey(dep.version_id);
            if ("required".equals(dep.kind) && !installed)
                throw new IOException("缺少已启用且未修改的必需版本 #" + dep.version_id + "，请先安装依赖作品");
            if ("conflict".equals(dep.kind) && installed)
                throw new IOException("与已启用的版本 #" + dep.version_id + " 冲突，请先禁用");
            if ("required".equals(dep.kind)
                    && installed
                    && active.get(dep.version_id).get("project").getAsLong() == detail.project.id
                    && dep.version_id != version.id) throw new IOException("该版本依赖同一作品的旧版本，不能同时安装");
            notes.add(dep.kind + " · 版本 #" + dep.version_id + (installed ? "（已安装）" : "（未安装）"));
        }
        for (JsonObject origin : active.values()) {
            if (origin.get("project").getAsLong() == detail.project.id) continue;
            WorkshopApi.Version previous =
                    WorkshopApi.JSON.fromJson(origin.get("manifest"), WorkshopApi.Version.class);
            for (WorkshopApi.Dependency dep : previous.dependencies) {
                if ("conflict".equals(dep.kind) && dep.version_id == version.id)
                    throw new IOException("已安装作品声明与该版本冲突");
                JsonObject old = active.get(dep.version_id);
                if ("required".equals(dep.kind)
                        && old != null
                        && old.get("project").getAsLong() == detail.project.id
                        && dep.version_id != version.id)
                    throw new IOException("其他已启用作品依赖当前旧版本，不能直接更新");
            }
        }
        return notes;
    }

    public String install(
            String api,
            WorkshopApi.Detail detail,
            WorkshopApi.Version version,
            String gameVersion,
            File archive,
            WorkshopApi.Transfer transfer)
            throws IOException {
        WorkshopApi.PackageFile file = detail.file(version);
        if (!hash(archive).equals(file.sha256)) throw new IOException("安装前 SHA-256 校验失败");
        PvzceContentStore.Kind type = kind(detail.project.kind);
        Path stage = Files.createTempDirectory(game.toPath(), ".workshop-install-");
        try {
            File payload =
                    new File(
                            stage.toFile(),
                            type == PvzceContentStore.Kind.MODS ? "payload.jar" : "payload");
            String modId = "";
            if (type == PvzceContentStore.Kind.MODS) {
                Files.copy(archive.toPath(), payload.toPath());
                JsonObject meta = modMetadata(payload);
                modId = meta.get("id").getAsString();
                if (!modId.matches("[a-z][a-z0-9_-]{1,63}")
                        || !version.number.equals(meta.get("version").getAsString())
                        || !meta.has("depends")
                        || !meta.getAsJsonObject("depends").has("pvzce")
                        || meta.getAsJsonObject("depends").has("minecraft"))
                    throw new IOException("模组身份、版本或 PVZCE 依赖无效");
            } else {
                Files.createDirectory(payload.toPath());
                unpack(archive, payload, transfer);
                JsonObject pack;
                try (InputStream in =
                        Files.newInputStream(new File(payload, "pack.mcmeta").toPath())) {
                    pack =
                            JsonParser.parseString(
                                            new String(
                                                    WorkshopApi.read(in, 2 * 1024 * 1024),
                                                    StandardCharsets.UTF_8))
                                    .getAsJsonObject()
                                    .getAsJsonObject("pack");
                }
                if (pack.get("pack_format").getAsInt() != 1
                        || !pack.get("description").getAsJsonPrimitive().isString()
                        || !new File(payload, "data").isDirectory())
                    throw new IOException("数据包须包含格式 1 的 pack.mcmeta 和 data 目录");
            }
            transfer.check();
            String fingerprint = fingerprint(payload);
            synchronized (PvzceContentStore.LOCK) {
                checkLocked(api, detail, version, gameVersion);
                WorkshopOrigins origins = new WorkshopOrigins(game);
                PvzceContentStore.Entry previous = null;
                for (PvzceContentStore.Entry item : store.list(type)) {
                    JsonObject origin = origins.get(type, item.filename);
                    if (origin != null
                            && api.equals(origin.get("api").getAsString())
                            && origin.get("project").getAsLong() == detail.project.id) {
                        if (previous != null) throw new IOException("存在多个该作品副本，请先整理后更新");
                        previous = item;
                    }
                }
                String name =
                        previous != null
                                ? previous.filename
                                : "workshop-"
                                        + WorkshopApi.key(api).substring(0, 12)
                                        + "-"
                                        + detail.project.id
                                        + (type == PvzceContentStore.Kind.MODS ? ".jar" : "");
                File destination = GameContentPaths.child(store.directory(type), name);
                if (previous != null
                        && !fingerprint(destination)
                                .equals(origins.get(type, name).get("fingerprint").getAsString())) {
                    throw new IOException("本地内容已修改，原文件保留；请先备份并移至回收站再安装");
                }
                if (previous == null && destination.exists())
                    throw new IOException("同名本地内容已存在，未覆盖");
                if (type == PvzceContentStore.Kind.DATA && store.hasSharedPackName(type, name))
                    throw new IOException("资源包已使用该目录名，请先重命名");
                if (type == PvzceContentStore.Kind.MODS) {
                    for (PvzceContentStore.Entry item : store.list(type)) {
                        if ((previous == null || !item.filename.equals(previous.filename))
                                && item.enabled
                                && modId.equals(
                                        modMetadata(
                                                        GameContentPaths.child(
                                                                store.directory(type),
                                                                item.filename))
                                                .get("id")
                                                .getAsString())) {
                            throw new IOException("实例已有相同模组 ID：" + modId + "，请先禁用或移除");
                        }
                    }
                    checkModDependencies(payload, previous, gameVersion);
                }
                JsonObject origin = new JsonObject();
                origin.addProperty("api", api);
                origin.addProperty("project", detail.project.id);
                origin.addProperty("version", version.id);
                origin.addProperty("file", file.id);
                origin.addProperty("sha256", file.sha256);
                origin.addProperty("fingerprint", fingerprint);
                origin.addProperty("number", version.number);
                origin.addProperty("game_version", gameVersion);
                origin.add("manifest", WorkshopApi.JSON.toJsonTree(version));
                // No cancellation once committing: the complete payload and metadata move together
                // or roll back.
                transfer.check();
                String trash = previous == null ? null : store.trash(type, name);
                boolean moved = false;
                try {
                    Files.move(payload.toPath(), destination.toPath());
                    moved = true;
                    WorkshopOrigins current = new WorkshopOrigins(game);
                    current.put(type, name, origin);
                    current.save();
                } catch (IOException error) {
                    if (moved) Files.move(destination.toPath(), payload.toPath());
                    if (trash != null) store.restore(trash);
                    throw error;
                }
                return name + (trash == null ? " 已安装" : " 已更新，旧版本保存在回收站") + "；重新启动游戏后生效";
            }
        } catch (RuntimeException error) {
            throw new IOException("包或来源元数据无效，原内容保留", error);
        } finally {
            removeTree(stage);
        }
    }

    private void checkModDependencies(
            File payload, PvzceContentStore.Entry previous, String gameVersion) throws IOException {
        JsonObject incoming = modMetadata(payload);
        Map<String, String> available = new java.util.HashMap<>();
        List<JsonObject> metadata = new ArrayList<>();
        for (PvzceContentStore.Entry item : store.list(PvzceContentStore.Kind.MODS)) {
            if (!item.enabled || (previous != null && item.filename.equals(previous.filename)))
                continue;
            JsonObject meta =
                    modMetadata(
                            GameContentPaths.child(
                                    store.directory(PvzceContentStore.Kind.MODS), item.filename));
            available.put(meta.get("id").getAsString(), meta.get("version").getAsString());
            metadata.add(meta);
        }
        available.put("pvzce", gameVersion);
        available.put(incoming.get("id").getAsString(), incoming.get("version").getAsString());
        metadata.add(incoming);
        for (JsonObject meta : metadata) {
            for (String field : new String[] {"depends", "breaks"}) {
                if (!meta.has(field)) continue;
                for (Map.Entry<String, JsonElement> dep : meta.getAsJsonObject(field).entrySet()) {
                    if (dep.getKey().equals("java") || dep.getKey().equals("fabricloader"))
                        continue;
                    String installed = available.get(dep.getKey());
                    boolean match =
                            installed != null
                                    && WorkshopVersions.matches(installed, dep.getValue());
                    if ((field.equals("depends") && !match) || (field.equals("breaks") && match)) {
                        throw new IOException(
                                "模组 "
                                        + meta.get("id").getAsString()
                                        + " 的 "
                                        + field
                                        + "."
                                        + dep.getKey()
                                        + " 不满足，请先处理依赖");
                    }
                }
            }
        }
    }

    static JsonObject modMetadata(File file) throws IOException {
        try (JarFile jar = new JarFile(file)) {
            java.util.jar.JarEntry entry = jar.getJarEntry("fabric.mod.json");
            if (entry == null) throw new IOException("模组缺少 fabric.mod.json");
            try (InputStream in = jar.getInputStream(entry)) {
                return JsonParser.parseString(
                                new String(
                                        WorkshopApi.read(in, 1024 * 1024), StandardCharsets.UTF_8))
                        .getAsJsonObject();
            }
        } catch (RuntimeException error) {
            throw new IOException("模组元数据损坏", error);
        }
    }

    private static PvzceContentStore.Kind kind(String kind) throws IOException {
        if (kind.equals("mod")) return PvzceContentStore.Kind.MODS;
        if (kind.equals("datapack")) return PvzceContentStore.Kind.DATA;
        throw new IOException("暂不支持该作品类型");
    }

    private static void unpack(File archive, File root, WorkshopApi.Transfer transfer)
            throws IOException {
        Set<String> paths = new HashSet<>();
        Map<String, String> components = new java.util.HashMap<>();
        int count = 0;
        long total = 0;
        try (ZipFile zip = new ZipFile(archive)) {
            var entries = zip.getEntries();
            byte[] buffer = new byte[65536];
            while (entries.hasMoreElements()) {
                transfer.check();
                ZipArchiveEntry entry = entries.nextElement();
                String name = entry.getName();
                String[] parts = name.split("/", -1);
                int mode = entry.getUnixMode() & 0170000;
                if (++count > 10000
                        || parts.length > 33
                        || entry.isUnixSymlink()
                        || (mode != 0 && mode != 0100000 && mode != 0040000)
                        || !zip.canReadEntryData(entry)
                        || name.startsWith("/")
                        || name.contains("\\")
                        || name.contains(":")) throw new IOException("数据包含不安全路径、链接或超过条目限制");
                File target = root;
                for (int i = 0; i < parts.length; i++) {
                    if (i == parts.length - 1 && parts[i].isEmpty() && entry.isDirectory()) break;
                    if (parts[i].isEmpty() || parts[i].equals(".") || parts[i].equals(".."))
                        throw new IOException("非法数据包路径");
                    target = GameContentPaths.child(target, parts[i]);
                    String relative = root.toPath().relativize(target.toPath()).toString();
                    String existing =
                            components.putIfAbsent(relative.toLowerCase(Locale.ROOT), relative);
                    if (existing != null && !existing.equals(relative))
                        throw new IOException("数据包目录存在大小写冲突");
                }
                if (!paths.add(name.replaceAll("/$", "").toLowerCase(Locale.ROOT)))
                    throw new IOException("数据包路径重复或大小写冲突");
                if (entry.isDirectory()) {
                    Files.createDirectories(target.toPath());
                    continue;
                }
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".class") || lower.endsWith(".jar") || lower.endsWith(".zip"))
                    throw new IOException("数据包不能包含代码或嵌套压缩包");
                Files.createDirectories(target.toPath().getParent());
                long size = 0;
                CRC32 crc = new CRC32();
                try (InputStream in = zip.getInputStream(entry);
                        OutputStream out =
                                Files.newOutputStream(
                                        target.toPath(),
                                        java.nio.file.StandardOpenOption.CREATE_NEW)) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        transfer.check();
                        size += n;
                        total += n;
                        if (size > 64L * 1024 * 1024
                                || total > 512L * 1024 * 1024
                                || (size > 1024 * 1024
                                        && size > Math.max(1, entry.getCompressedSize()) * 200))
                            throw new IOException("数据包展开超过安全限制");
                        crc.update(buffer, 0, n);
                        out.write(buffer, 0, n);
                    }
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc())
                    throw new IOException("数据包 CRC 或长度校验失败");
            }
        }
    }

    static String hash(File file) throws IOException {
        MessageDigest hash = WorkshopApi.digest();
        try (InputStream in = Files.newInputStream(file.toPath())) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = in.read(buffer)) != -1) hash.update(buffer, 0, n);
        }
        return WorkshopApi.hex(hash.digest());
    }

    static String fingerprint(File file) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("内容包含符号链接");
        if (file.isFile()) return hash(file);
        if (!file.isDirectory()) throw new IOException("内容已不存在");
        MessageDigest result = WorkshopApi.digest();
        try (var walk = Files.walk(file.toPath())) {
            Path[] paths = walk.sorted().limit(20001).toArray(Path[]::new);
            if (paths.length > 20000) throw new IOException("内容条目过多");
            for (Path path : paths) {
                if (Files.isSymbolicLink(path)) throw new IOException("内容包含符号链接");
                String value =
                        file.toPath().relativize(path).toString().replace(File.separatorChar, '/')
                                + "\u0000"
                                + (Files.isDirectory(path) ? "directory" : hash(path.toFile()))
                                + "\n";
                result.update(value.getBytes(StandardCharsets.UTF_8));
            }
        }
        return WorkshopApi.hex(result.digest());
    }

    static void removeTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toArray(Path[]::new))
                Files.delete(path);
        }
    }
}
