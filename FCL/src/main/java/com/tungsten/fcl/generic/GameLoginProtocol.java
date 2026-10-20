package com.tungsten.fcl.generic;

import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.jar.JarFile;

/** Proposed protocol v1. Neither current site nor current game advertises this capability. */
public final class GameLoginProtocol {
    private GameLoginProtocol() {}

    public static boolean supportedGame(File file) throws IOException {
        try (JarFile jar = new JarFile(file)) {
            var entry = jar.getJarEntry("META-INF/pvzce-launcher.json");
            if (entry == null) return false;
            try (var input = jar.getInputStream(entry)) {
                JsonObject value =
                        WorkshopApi.JSON.fromJson(
                                new String(WorkshopApi.read(input, 16384), StandardCharsets.UTF_8),
                                JsonObject.class);
                return value != null
                        && value.has("game_login_protocol")
                        && value.get("game_login_protocol").getAsInt() == 1
                        && value.has("sanitized_launch_arguments")
                        && value.get("sanitized_launch_arguments").getAsBoolean();
            } catch (RuntimeException error) {
                throw new IOException("游戏登录能力声明无效");
            }
        }
    }

    public static Ticket issue(WorkshopApi client, String csrf, String gameVersion)
            throws IOException {
        JsonObject capabilities =
                client.request("GET", "/api/v1/launcher/capabilities", null, "").getAsJsonObject();
        if (!capabilities.has("game_login_protocol")
                || capabilities.get("game_login_protocol").getAsInt() != 1)
            throw new IOException("服务器尚未支持游戏登录交接");
        JsonObject body = new JsonObject();
        body.addProperty("protocol", 1);
        body.addProperty("game_id", "pvzce");
        body.addProperty("game_version", gameVersion);
        JsonObject response =
                client.request("POST", "/api/v1/launcher/login-codes", body, csrf)
                        .getAsJsonObject();
        return parse(response, client.base, Instant.now());
    }

    public static Ticket parse(JsonObject response, String origin, Instant now) throws IOException {
        try {
            String code = response.get("login_code").getAsString();
            Instant expiry = Instant.parse(response.get("expires_at").getAsString());
            if (response.get("protocol").getAsInt() != 1
                    || !origin.equals(WorkshopApi.normalize(response.get("api_url").getAsString()))
                    || !code.matches("[A-Za-z0-9_-]{32,128}")
                    || !expiry.isAfter(now)
                    || expiry.isAfter(now.plusSeconds(90))) throw new IllegalArgumentException();
            return new Ticket(origin, code, expiry.toEpochMilli());
        } catch (Exception error) {
            throw new IOException("服务器返回的游戏登录凭证无效，本次离线启动");
        }
    }

    /** Deliberately not Serializable; never part of an instance or its backups. */
    public static final class Ticket {
        public final String api;
        private final String code;
        public final long expiresAt;

        private Ticket(String api, String code, long expiresAt) {
            this.api = api;
            this.code = code;
            this.expiresAt = expiresAt;
        }

        public List<String> arguments() throws IOException {
            if (System.currentTimeMillis() >= expiresAt)
                throw new IOException("游戏登录凭证已过期，请重新启动或关闭账号交接后离线启动");
            return List.of("--pvzceApiUrl", api, "--pvzceLoginCode", code);
        }

        public String secretForLogFilter() {
            return code;
        }

        @Override
        public String toString() {
            return "GameLoginTicket[redacted]";
        }
    }
}
