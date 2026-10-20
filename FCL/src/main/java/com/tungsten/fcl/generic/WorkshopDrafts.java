package com.tungsten.fcl.generic;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/** The site's write DTOs only; never submit owner/creator/role or response-only fields. */
public final class WorkshopDrafts {
    private WorkshopDrafts() {}

    public static JsonObject project(
            String slug,
            String title,
            String kind,
            String description,
            String license,
            String tags,
            String label)
            throws IOException {
        if (!slug.matches("[a-z0-9][a-z0-9-]{1,79}")
                || title.trim().isEmpty()
                || bytes(title) > 200
                || description.isEmpty()
                || bytes(description) > 60000
                || license.isEmpty()
                || bytes(license) > 200
                || !(kind.equals("mod") || kind.equals("datapack"))
                || !(label.equals("general") || label.equals("suggestive")))
            throw new IOException("请检查作品标识、标题、说明、许可证和分类长度");
        JsonObject body = new JsonObject();
        body.addProperty("slug", slug);
        body.addProperty("title", title);
        body.addProperty("kind", kind);
        body.addProperty("description", description);
        body.addProperty("license", license);
        body.addProperty("content_label", label);
        JsonArray values = new JsonArray();
        for (String tag : parts(tags)) {
            if (!tag.matches("[a-z0-9][a-z0-9-]{1,79}"))
                throw new IOException("标签须为 2–80 位小写字母、数字或连字符");
            values.add(tag);
        }
        if (values.size() > 20) throw new IOException("最多 20 个标签");
        body.add("tags", values);
        return body;
    }

    public static JsonObject version(
            String number, String changelog, String channel, String games, String dependencies)
            throws IOException {
        if (number.isEmpty()
                || bytes(number) > 100
                || bytes(changelog) > 60000
                || !(channel.equals("stable") || channel.equals("beta")))
            throw new IOException("版本号、更新日志或通道无效");
        JsonObject body = new JsonObject();
        body.addProperty("number", number);
        body.addProperty("changelog", changelog);
        body.addProperty("channel", channel);
        JsonArray versions = new JsonArray();
        for (String game : parts(games)) versions.add(game);
        if (versions.size() < 1 || versions.size() > 20)
            throw new IOException("请选择 1–20 个服务器已登记的游戏版本");
        body.add("game_versions", versions);
        JsonArray deps = new JsonArray();
        Set<Long> seen = new HashSet<>();
        for (String entry : dependencies.split("\\r?\\n")) {
            if (entry.trim().isEmpty()) continue;
            String[] pair = entry.trim().split("\\s+");
            if (pair.length != 2
                    || !pair[0].matches("[1-9][0-9]*")
                    || !Set.of("required", "optional", "conflict").contains(pair[1])) {
                throw new IOException("依赖每行格式：版本ID required / optional / conflict");
            }
            long id;
            try {
                id = Long.parseLong(pair[0]);
            } catch (NumberFormatException error) {
                throw new IOException("依赖版本 ID 无效", error);
            }
            if (!seen.add(id) || deps.size() >= 100) throw new IOException("依赖重复或超过 100 项");
            JsonObject dep = new JsonObject();
            dep.addProperty("version_id", id);
            dep.addProperty("kind", pair[1]);
            deps.add(dep);
        }
        body.add("dependencies", deps);
        return body;
    }

    public static JsonObject transition(String action, String reason) throws IOException {
        if (!Set.of("submit", "withdraw", "resume").contains(action)
                || reason.trim().isEmpty()
                || bytes(reason) > 4000) throw new IOException("请填写 1–4000 字节的操作原因");
        JsonObject body = new JsonObject();
        body.addProperty("action", action);
        body.addProperty("reason", reason);
        return body;
    }

    private static String[] parts(String value) {
        return value.trim().isEmpty() ? new String[0] : value.trim().split("[,，\\s]+");
    }

    private static int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
