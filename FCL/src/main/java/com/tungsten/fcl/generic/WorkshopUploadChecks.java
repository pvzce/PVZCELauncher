package com.tungsten.fcl.generic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Bounded, non-executing preflight. Server validation and malware scanning remain authoritative.
 */
public final class WorkshopUploadChecks {
    private WorkshopUploadChecks() {}

    public static void check(
            File file, String kind, WorkshopApi.Version version, WorkshopApi.Transfer transfer)
            throws IOException {
        Set<String> names = new HashSet<>();
        HashMap<String, JsonObject> metadata = new HashMap<>();
        int count = 0, data = 0;
        long expanded = 0;
        try (ZipFile zip = new ZipFile(file)) {
            var entries = zip.getEntries();
            byte[] buffer = new byte[65536];
            while (entries.hasMoreElements()) {
                transfer.check();
                var entry = entries.nextElement();
                String name = entry.getName();
                String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                if (++count > 10000
                        || clean.isEmpty()
                        || name.length() > 500
                        || clean.startsWith("/")
                        || clean.contains("\\")
                        || clean.contains(":")
                        || clean.contains("\0")
                        || !names.add(clean.toLowerCase(Locale.ROOT))
                        || entry.isUnixSymlink()
                        || (entry.getUnixMode() & 0170000) != 0
                                && (entry.getUnixMode() & 0170000)
                                        != (entry.isDirectory() ? 0040000 : 0100000)
                        || !zip.canReadEntryData(entry)
                        || entry.getGeneralPurposeBit().usesEncryption())
                    throw new IOException("包内路径重复、不安全或包含不支持的条目");
                for (String part : clean.split("/", -1))
                    if (part.isEmpty() || part.equals(".") || part.equals(".."))
                        throw new IOException("包内路径无效");
                if (entry.isDirectory()) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                if (kind.equals("datapack")
                        && (lower.endsWith(".class")
                                || lower.endsWith(".jar")
                                || lower.endsWith(".zip")))
                    throw new IOException("数据包不能包含代码或嵌套压缩包");
                if (entry.getSize() < 0
                        || entry.getSize() > 64L * 1024 * 1024
                        || (expanded += entry.getSize()) > 512L * 1024 * 1024
                        || entry.getSize() > 1024 * 1024
                                && entry.getSize() > entry.getCompressedSize() * 200)
                    throw new IOException("包展开大小超过限制");
                boolean json =
                        name.equals("fabric.mod.json")
                                || name.equals("pack.mcmeta")
                                || name.startsWith("data/") && name.endsWith(".json");
                ByteArrayOutputStream bytes = json ? new ByteArrayOutputStream() : null;
                CRC32 crc = new CRC32();
                long read = 0;
                try (var input = zip.getInputStream(entry)) {
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        transfer.check();
                        read += n;
                        if (read > entry.getSize() || json && read > 2 * 1024 * 1024)
                            throw new IOException("包条目大小或元数据超过限制");
                        crc.update(buffer, 0, n);
                        if (json) bytes.write(buffer, 0, n);
                    }
                }
                if (read != entry.getSize() || crc.getValue() != entry.getCrc())
                    throw new IOException("压缩包校验失败");
                if (json) {
                    JsonObject value =
                            JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8.name()))
                                    .getAsJsonObject();
                    metadata.put(name, value);
                    if (name.startsWith("data/")) data++;
                }
            }
            if (count == 0) throw new IOException("压缩包不能为空");
            if (kind.equals("mod")) {
                JsonObject meta = metadata.get("fabric.mod.json");
                if (meta == null
                        || meta.get("schemaVersion").getAsInt() != 1
                        || !meta.get("id").getAsString().matches("[a-z][a-z0-9_-]{1,63}")
                        || !version.number.equals(meta.get("version").getAsString())
                        || !version.number.matches(
                                "[0-9]+\\.[0-9]+\\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?")
                        || !meta.has("depends")
                        || !meta.getAsJsonObject("depends").has("pvzce")
                        || meta.getAsJsonObject("depends").has("minecraft")
                        || meta.has("jars") && !meta.getAsJsonArray("jars").isEmpty())
                    throw new IOException("模组元数据、版本号或 PVZCE 依赖不符合要求");
                for (String game : version.game_versions)
                    if (!WorkshopVersions.matches(
                            game, meta.getAsJsonObject("depends").get("pvzce")))
                        throw new IOException("所声明游戏版本不满足 depends.pvzce");
            } else {
                JsonObject pack = metadata.get("pack.mcmeta");
                if (pack == null
                        || metadata.containsKey("fabric.mod.json")
                        || pack.getAsJsonObject("pack").get("pack_format").getAsInt() != 1
                        || pack.getAsJsonObject("pack").get("description").getAsString().isEmpty()
                        || data == 0)
                    throw new IOException("数据包须有根目录 pack.mcmeta（格式 1）和 data 下的 JSON");
            }
        } catch (RuntimeException error) {
            throw new IOException("包格式或元数据无效", error);
        }
    }
}
