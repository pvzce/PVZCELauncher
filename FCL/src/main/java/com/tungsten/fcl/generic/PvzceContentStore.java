package com.tungsten.fcl.generic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** PVZCE file formats only: Fabric metadata, directory packs, and complete save directories. */
public final class PvzceContentStore {
    static final Object LOCK = new Object();
    private static final int MAX_ENTRIES = 20000;
    private static final long MAX_BYTES = 1024L * 1024 * 1024;
    private static final String BACKUP_MANIFEST = ".pvzce-backup.json";
    private final File game;

    public enum Kind {
        MODS("mods"), RESOURCES("resourcepacks"), DATA("datapacks"), SAVES("saves");
        public final String directory;
        Kind(String directory) {
            this.directory = directory;
        }
    }

    public static final class Entry {
        public final String filename;
        public final String title;
        public final String description;
        public final boolean enabled;
        public final long modified;
        public final String workshopApi;
        public final long workshopProject;

        private Entry(File file, String title, String description, boolean enabled, JsonObject origin) {
            filename = file.getName();
            this.title = title;
            this.description = description;
            this.enabled = enabled;
            modified = file.lastModified();
            workshopApi = origin == null ? "" : origin.get("api").getAsString();
            workshopProject = origin == null ? 0 : origin.get("project").getAsLong();
        }
    }

    public PvzceContentStore(File game) throws IOException {
        if (!game.isAbsolute()) throw new IOException("游戏目录必须为绝对路径");
        this.game = game.getCanonicalFile();
        Files.createDirectories(this.game.toPath());
    }

    public File directory(Kind kind) throws IOException {
        return managedDirectory(kind.directory);
    }

    private File managedDirectory(String name) throws IOException {
        File directory = checkedChild(game, name);
        Files.createDirectories(directory.toPath());
        return directory;
    }

    private static File checkedChild(File parent, String name) throws IOException {
        File raw = new File(parent, name).getAbsoluteFile();
        if (Files.isSymbolicLink(raw.toPath())) throw new IOException("不支持符号链接：" + name);
        File safe = GameContentPaths.child(parent, name);
        if (!safe.equals(raw)) throw new IOException("路径不能通过符号链接跳转：" + name);
        return safe;
    }

    private File item(Kind kind, String name) throws IOException {
        File result = checkedChild(directory(kind), name);
        if (!result.exists()) throw new IOException("文件已不存在：" + name);
        return result;
    }

    public List<Entry> list(Kind kind) throws IOException {
        synchronized (LOCK) {
            File[] files = directory(kind).listFiles();
            if (files == null) throw new IOException("无法读取内容目录");
            JsonObject selection = kind == Kind.RESOURCES || kind == Kind.DATA ? packSelection() : null;
            List<Entry> result = new ArrayList<>();
            WorkshopOrigins origins = new WorkshopOrigins(game);
            for (File file : files) {
                if (Files.isSymbolicLink(file.toPath())) continue;
                boolean mod = kind == Kind.MODS;
                if (mod ? !file.isFile() || !modName(file.getName()) : !file.isDirectory()) continue;
                String title = file.getName();
                String description = mod ? "Fabric / PVZCE 模组" : kind == Kind.SAVES ? "PVZCE 存档目录" : "目录包";
                try {
                    JsonObject meta;
                    if (mod) {
                        try (JarFile jar = new JarFile(file)) {
                            java.util.jar.JarEntry entry = jar.getJarEntry("fabric.mod.json");
                            if (entry == null) throw new IOException("Missing fabric.mod.json");
                            try (InputStream input = jar.getInputStream(entry)) {
                                meta = json(input);
                            }
                        }
                        title = string(meta, "name", string(meta, "id", title));
                        description = string(meta, "version", "") + "  " + string(meta, "description", "");
                    } else if (kind != Kind.SAVES && new File(file, "pack.mcmeta").isFile()) {
                        try (InputStream input = Files.newInputStream(checkedChild(file, "pack.mcmeta").toPath())) {
                            meta = json(input);
                        }
                        if (meta.has("pack") && meta.get("pack").isJsonObject()) {
                            description = string(meta.getAsJsonObject("pack"), "description", description);
                        }
                    }
                } catch (Exception error) {
                    description = "元数据不可读，原文件保留：" + file.getName();
                }
                JsonObject origin = origins.get(kind, file.getName());
                if (origin != null) description += "\n工坊版本 " + origin.get("number").getAsString()
                        + " · 作品 #" + origin.get("project").getAsLong() + " · " + origin.get("api").getAsString();
                result.add(new Entry(file, title, description, mod ? file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")
                        : selection == null || !disabled(selection, file.getName()), origin));
            }
            result.sort(Comparator.comparing(entry -> entry.filename.toLowerCase(Locale.ROOT)));
            return result;
        }
    }

    public void setEnabled(Kind kind, String name, boolean enabled) throws IOException {
        synchronized (LOCK) {
            File source = item(kind, name);
            if (kind == Kind.MODS) {
                if (!modName(name)) throw new IOException("不是模组 JAR");
                boolean current = name.toLowerCase(Locale.ROOT).endsWith(".jar");
                if (current == enabled) return;
                String target = enabled ? name.substring(0, name.length() - ".disabled".length()) : name + ".disabled";
                moveWithOrigin(kind, source, checkedChild(directory(kind), target));
            } else if (kind == Kind.RESOURCES || kind == Kind.DATA) {
                JsonObject selection = packSelection();
                changeDisabled(selection, name, !enabled);
                saveSelection(selection);
            } else throw new IOException("存档没有启用开关");
        }
    }

    public boolean hasSharedPackName(Kind kind, String name) throws IOException {
        if (kind != Kind.RESOURCES && kind != Kind.DATA) return false;
        return checkedChild(directory(kind == Kind.DATA ? Kind.RESOURCES : Kind.DATA), name).exists();
    }

    public void rename(Kind kind, String name, String nextName) throws IOException {
        synchronized (LOCK) {
            File source = item(kind, name);
            File target = checkedChild(directory(kind), nextName);
            if (name.equals(nextName)) return;
            if (target.exists()) throw new IOException("目标名称已存在");
            if (kind == Kind.MODS && (!modName(nextName)
                    || name.toLowerCase(Locale.ROOT).endsWith(".disabled") != nextName.toLowerCase(Locale.ROOT).endsWith(".disabled"))) {
                throw new IOException("重命名须保留 .jar 或 .jar.disabled 后缀；启用状态使用勾选开关修改");
            }
            if (kind == Kind.RESOURCES || kind == Kind.DATA) {
                if (hasSharedPackName(kind, nextName)) throw new IOException("另一类包已使用该名称，请换一个名称");
                JsonObject selection = packSelection();
                changeDisabled(selection, nextName, disabled(selection, name));
                // Keep the old key: another pack or a recoverable trash entry may still use it.
                saveSelection(selection);
            }
            moveWithOrigin(kind, source, target);
        }
    }

    /** Move to a recoverable directory outside all game-enumerated content roots. */
    public String trash(Kind kind, String name) throws IOException {
        synchronized (LOCK) {
            File source = item(kind, name);
            File box = checkedChild(managedDirectory(".pvzce-launcher-trash"), UUID.randomUUID().toString());
            Files.createDirectory(box.toPath());
            Properties meta = new Properties();
            meta.setProperty("kind", kind.name());
            meta.setProperty("name", name);
            WorkshopOrigins origins = new WorkshopOrigins(game);
            JsonObject origin = origins.get(kind, name);
            if (origin != null) meta.setProperty("origin", origin.toString());
            try (OutputStream output = Files.newOutputStream(new File(box, "entry.properties").toPath())) {
                meta.store(output, "Recoverable PVZCE content");
            }
            move(source, new File(box, "content"));
            try {
                origins.put(kind, name, null);
                origins.save();
            } catch (IOException error) {
                move(new File(box, "content"), source);
                Files.delete(new File(box, "entry.properties").toPath());
                Files.delete(box.toPath());
                throw error;
            }
            return box.getName();
        }
    }

    public List<String> trashIds() throws IOException {
        synchronized (LOCK) {
            List<String> ids = new ArrayList<>();
            File[] boxes = managedDirectory(".pvzce-launcher-trash").listFiles();
            if (boxes == null) throw new IOException("无法读取回收站");
            for (File box : boxes) {
                if (!Files.isSymbolicLink(box.toPath()) && box.getName().matches("[a-f0-9-]{36}")
                        && new File(box, "content").exists()) ids.add(box.getName());
            }
            ids.sort(Comparator.comparingLong((String id) -> new File(new File(game, ".pvzce-launcher-trash"), id).lastModified()).reversed());
            return ids;
        }
    }

    public String trashLabel(String id) throws IOException {
        Properties meta = trashMetadata(id);
        return meta.getProperty("kind") + " / " + meta.getProperty("name");
    }

    public void restore(String id) throws IOException {
        synchronized (LOCK) {
            Properties meta = trashMetadata(id);
            Kind kind;
            try {
                kind = Kind.valueOf(meta.getProperty("kind", ""));
            } catch (IllegalArgumentException error) {
                throw new IOException("回收站类型无效", error);
            }
            File box = checkedChild(managedDirectory(".pvzce-launcher-trash"), id);
            File content = checkedChild(box, "content");
            File destination = checkedChild(directory(kind), meta.getProperty("name"));
            WorkshopOrigins origins = new WorkshopOrigins(game);
            move(content, destination);
            try {
                origins.put(kind, destination.getName(), meta.containsKey("origin")
                        ? JsonParser.parseString(meta.getProperty("origin")).getAsJsonObject() : null);
                origins.save();
            } catch (IOException | RuntimeException error) {
                move(destination, content);
                throw new IOException("恢复来源记录失败，内容保留在回收站", error);
            }
            Files.delete(new File(box, "entry.properties").toPath());
            Files.delete(box.toPath());
        }
    }

    private Properties trashMetadata(String id) throws IOException {
        if (!id.matches("[a-f0-9-]{36}")) throw new IOException("Invalid recovery entry");
        File box = checkedChild(managedDirectory(".pvzce-launcher-trash"), id);
        File metadata = checkedChild(box, "entry.properties");
        if (metadata.length() > 65536) throw new IOException("Invalid recovery metadata");
        Properties result = new Properties();
        try (InputStream input = Files.newInputStream(metadata.toPath())) {
            result.load(input);
        }
        return result;
    }

    public void exportZip(Kind kind, List<String> names, OutputStream output) throws IOException {
        synchronized (LOCK) {
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                JsonObject manifest = new JsonObject();
                manifest.addProperty("format", 1);
                manifest.addProperty("kind", kind.name());
                JsonArray switchedOff = new JsonArray();
                if (kind == Kind.RESOURCES || kind == Kind.DATA) {
                    JsonObject selection = packSelection();
                    for (String name : names) if (disabled(selection, name)) switchedOff.add(name);
                }
                manifest.add("disabled", switchedOff);
                JsonObject provenance = new JsonObject();
                WorkshopOrigins origins = new WorkshopOrigins(game);
                for (String name : names) {
                    JsonObject origin = origins.get(kind, name);
                    if (origin != null) provenance.add(name, origin);
                }
                manifest.add("origins", provenance);
                zip.putNextEntry(new ZipEntry(BACKUP_MANIFEST));
                zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                long[] budget = {0, 0};
                for (String name : names) {
                    if (BACKUP_MANIFEST.equals(name)) throw new IOException("该名称为备份元数据保留名称");
                    export(item(kind, name), name, zip, 0, budget);
                }
            }
        }
    }

    private static void export(File file, String name, ZipOutputStream zip, int depth, long[] budget) throws IOException {
        budget(file, depth, budget);
        zip.putNextEntry(new ZipEntry(name + (file.isDirectory() ? "/" : "")));
        if (file.isDirectory()) {
            zip.closeEntry();
            File[] children = file.listFiles();
            if (children == null) throw new IOException("无法读取目录：" + file);
            for (File child : children) export(child, name + "/" + child.getName(), zip, depth + 1, budget);
        } else {
            Files.copy(file.toPath(), zip);
            zip.closeEntry();
        }
    }

    /** Restore our exported ZIP (top-level item directories/files); no existing item is overwritten. */
    public void importZip(Kind kind, InputStream input) throws IOException {
        synchronized (LOCK) {
            File stage = Files.createTempDirectory(game.toPath(), ".restore-").toFile();
            try {
                long bytes = 0;
                int count = 0;
                try (ZipInputStream zip = new ZipInputStream(input)) {
                    ZipEntry entry;
                    byte[] buffer = new byte[8192];
                    while ((entry = zip.getNextEntry()) != null) {
                        if (++count > MAX_ENTRIES) throw new IOException("备份条目过多");
                        String path = entry.getName();
                        if (path.startsWith("/") || path.contains("\\") || path.contains(":")) throw new IOException("非法备份路径");
                        String[] parts = path.split("/");
                        if (parts.length == 0 || parts.length > 33) throw new IOException("备份目录过深");
                        File target = stage;
                        for (String part : parts) target = checkedChild(target, part);
                        if (entry.isDirectory()) {
                            Files.createDirectories(target.toPath());
                        } else {
                            Files.createDirectories(target.toPath().getParent());
                            try (OutputStream out = Files.newOutputStream(target.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW)) {
                                int read;
                                while ((read = zip.read(buffer)) != -1) {
                                    bytes += read;
                                    if (bytes > MAX_BYTES) throw new IOException("备份解压超过 1 GiB");
                                    out.write(buffer, 0, read);
                                }
                            }
                        }
                    }
                }
                JsonObject manifest = null;
                File metadata = new File(stage, BACKUP_MANIFEST);
                if (metadata.exists()) {
                    try (InputStream meta = Files.newInputStream(metadata.toPath())) {
                        manifest = json(meta);
                        if (manifest.get("format").getAsInt() != 1 || !kind.name().equals(manifest.get("kind").getAsString())
                                || !manifest.get("disabled").isJsonArray()) throw new IOException("备份版本或内容类型不匹配");
                        for (JsonElement name : manifest.getAsJsonArray("disabled")) {
                            if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) throw new IOException("备份开关说明无效");
                        }
                    } catch (RuntimeException error) {
                        throw new IOException("备份说明损坏", error);
                    }
                    Files.delete(metadata.toPath());
                }
                if (manifest != null && manifest.has("origins")) {
                    try {
                        for (var entry : manifest.getAsJsonObject("origins").entrySet()) {
                            checkedChild(stage, entry.getKey());
                            WorkshopOrigins.validate(entry.getValue().getAsJsonObject());
                        }
                    } catch (RuntimeException error) {
                        throw new IOException("备份工坊来源说明无效", error);
                    }
                }
                WorkshopOrigins importedOrigins = new WorkshopOrigins(game);
                File[] items = stage.listFiles();
                if (items == null || items.length == 0) throw new IOException("备份为空");
                for (File item : items) {
                    validateImported(kind, item);
                    if (checkedChild(directory(kind), item.getName()).exists()) throw new IOException("已存在同名内容：" + item.getName());
                }
                JsonObject selection = null;
                if (manifest != null && (kind == Kind.DATA || kind == Kind.RESOURCES)) {
                    selection = packSelection();
                    for (File item : items) {
                        boolean off = disabled(manifest, item.getName());
                        if (hasSharedPackName(kind, item.getName()) && off != disabled(selection, item.getName())) {
                            throw new IOException("另一类同名包的开关与备份不同，请先重命名该包");
                        }
                        changeDisabled(selection, item.getName(), off);
                    }
                }
                List<File> moved = new ArrayList<>();
                try {
                    for (File item : items) {
                        File target = checkedChild(directory(kind), item.getName());
                        move(item, target);
                        moved.add(target);
                    }
                    if (selection != null) saveSelection(selection);
                    WorkshopOrigins origins = importedOrigins;
                    for (File imported : items) {
                        JsonObject origin = manifest != null && manifest.has("origins")
                                ? manifest.getAsJsonObject("origins").getAsJsonObject(imported.getName()) : null;
                        origins.put(kind, imported.getName(), origin);
                    }
                    origins.save();
                } catch (IOException error) {
                    for (File file : moved) {
                        try {
                            move(file, new File(stage, file.getName()));
                        } catch (IOException rollback) {
                            error.addSuppressed(rollback);
                        }
                    }
                    throw error;
                }
            } finally {
                deleteStage(stage.toPath());
            }
        }
    }

    public static void validateImported(Kind kind, File item) throws IOException {
        if (kind == Kind.MODS) {
            if (!item.isFile() || !modName(item.getName())) throw new IOException("备份中包含非模组文件");
            try (JarFile jar = new JarFile(item)) {
                if (jar.getJarEntry("fabric.mod.json") == null) throw new IOException("缺少 fabric.mod.json");
            }
        } else if (!item.isDirectory()) throw new IOException("请选择实际目录");
        else if (kind == Kind.SAVES && !new File(item, "profile.dat").isFile()
                && !new File(item, "levels").isDirectory() && !new File(item, "level_status").isDirectory()) {
            throw new IOException("请选择含 profile.dat、levels/ 或 level_status/ 的 PVZCE 世界目录");
        } else if (kind != Kind.SAVES && !new File(item, "assets").isDirectory() && !new File(item, "data").isDirectory()) {
            throw new IOException("包目录中没有 assets/ 或 data/");
        }
    }

    private static void budget(File file, int depth, long[] budget) throws IOException {
        if (Files.isSymbolicLink(file.toPath()) || depth > 32 || ++budget[0] > MAX_ENTRIES) throw new IOException("目录包含链接或超过导出限制");
        if (file.isFile()) budget[1] += file.length();
        if (budget[1] > MAX_BYTES) throw new IOException("导出超过 1 GiB");
    }

    private static void deleteStage(Path root) throws IOException {
        try (var files = Files.walk(root)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.deleteIfExists(path);
        }
    }

    private void moveWithOrigin(Kind kind, File source, File target) throws IOException {
        WorkshopOrigins origins = new WorkshopOrigins(game);
        move(source, target);
        try {
            origins.moved(kind, source.getName(), target.getName());
        } catch (IOException error) {
            move(target, source);
            throw error;
        }
    }

    private static void move(File source, File target) throws IOException {
        if (target.exists()) throw new IOException("目标已存在，未覆盖：" + target.getName());
        Files.move(source.toPath(), target.toPath());
    }

    private static boolean modName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".jar.disabled");
    }

    private JsonObject packSelection() throws IOException {
        File file = checkedChild(managedDirectory("config"), "pvzce-packs.json");
        if (!file.exists()) return new JsonObject();
        try (InputStream input = Files.newInputStream(file.toPath())) {
            JsonObject root = json(input);
            if (root.has("disabled")) {
                if (!root.get("disabled").isJsonArray()) throw new IOException("disabled 必须为数组");
                for (JsonElement name : root.getAsJsonArray("disabled")) {
                    if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) throw new IOException("disabled 必须只含包名称");
                }
            }
            return root;
        } catch (RuntimeException error) {
            throw new IOException("包开关配置损坏，保留原文件；请先备份并修复 config/pvzce-packs.json", error);
        }
    }

    private void saveSelection(JsonObject selection) throws IOException {
        InstanceFiles.write(checkedChild(managedDirectory("config"), "pvzce-packs.json"),
                selection.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static boolean disabled(JsonObject root, String name) {
        JsonArray array = root.getAsJsonArray("disabled");
        if (array == null) return false;
        for (JsonElement entry : array) if (name.equals(entry.getAsString())) return true;
        return false;
    }

    private static void changeDisabled(JsonObject root, String name, boolean disabled) {
        JsonArray result = new JsonArray();
        JsonArray old = root.getAsJsonArray("disabled");
        if (old != null) for (JsonElement entry : old) if (!name.equals(entry.getAsString())) result.add(entry);
        if (disabled) result.add(name);
        root.add("disabled", result);
    }

    private static JsonObject json(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (bytes.size() + read > 1024 * 1024) throw new IOException("元数据超过 1 MiB");
            bytes.write(buffer, 0, read);
        }
        return JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8.name())).getAsJsonObject();
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : fallback;
    }
}
