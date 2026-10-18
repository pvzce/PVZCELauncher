package com.tungsten.fcl.generic;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The pvzce-site v1 contract. Credentials never follow redirects or enter disk caches. */
public final class WorkshopApi {
    public static final Gson JSON = new Gson();
    public static final long MAX_PACKAGE = 200L * 1024 * 1024;
    public final String base;
    private final String cookie;

    public static final class ApiException extends IOException {
        public final int status;

        ApiException(int status) {
            super(
                    status == 401
                            ? "登录已过期，请重新登录"
                            : status == 403
                                    ? "账号无权执行此操作"
                                    : status == 429
                                            ? "请求过于频繁或额度不足，请稍后重试"
                                            : status == 503
                                                    ? "服务器功能尚未配置或暂时不可用"
                                                    : "服务器返回 HTTP " + status);
            this.status = status;
        }
    }

    public static final class Project {
        public long id, owner_id, downloads;
        public String slug = "", title = "", kind = "", description = "", license = "", state = "";
        public List<String> tags = new ArrayList<>();
    }

    public static final class ProjectPage {
        public List<Project> items = new ArrayList<>();
        public int page, size;
        public long total;
    }

    public static final class Dependency {
        public long version_id;
        public String kind = "";
    }

    public static final class Version {
        public long id, project_id;
        public String number = "", changelog = "", channel = "", state = "";
        public List<String> game_versions = new ArrayList<>();
        public List<Dependency> dependencies = new ArrayList<>();
    }

    public static final class PackageFile {
        public long id, project_id, version_id, size;
        public String purpose = "",
                filename = "",
                sha256 = "",
                scan_state = "",
                validation_state = "";
        public JsonObject metadata = new JsonObject();
    }

    public static final class Detail {
        public Project project;
        public List<Version> versions = new ArrayList<>();
        public List<PackageFile> files = new ArrayList<>();

        public PackageFile file(Version version) throws IOException {
            List<PackageFile> matches = new ArrayList<>();
            for (PackageFile file : files) {
                if (file.version_id == version.id
                        && "package".equals(file.purpose)
                        && "clean".equals(file.scan_state)
                        && "valid".equals(file.validation_state)) matches.add(file);
            }
            if (!"published".equals(project.state)
                    || !"published".equals(version.state)
                    || matches.size() != 1) {
                throw new IOException("仅支持已发布且已通过扫描的唯一包文件");
            }
            return matches.get(0);
        }
    }

    public static final class Transfer {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;

        public void cancel() {
            cancelled = true;
            HttpURLConnection current = connection;
            if (current != null) current.disconnect();
        }

        public void check() throws IOException {
            if (cancelled || Thread.currentThread().isInterrupted())
                throw new IOException("下载已取消，重试将重新下载");
        }
    }

    public interface Progress {
        void update(long received, long total);
    }

    public WorkshopApi(String base, String cookie) throws IOException {
        this.base = normalize(base);
        if (cookie != null
                && !cookie.isEmpty()
                && !cookie.matches("pvzce_session=[A-Za-z0-9_\\-]+")) {
            throw new IOException("无效会话");
        }
        this.cookie = cookie == null ? "" : cookie;
    }

    /** A backend origin, not an arbitrary URL; preserves non-default ports in the isolation key. */
    public static String normalize(String raw) throws IOException {
        try {
            URI uri = new URI(raw.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean local =
                    host.equals("localhost")
                            || host.equals("127.0.0.1")
                            || host.equals("[::1]")
                            || host.equals("::1")
                            || host.equals("10.0.2.2");
            if ((!scheme.equals("https") && !(scheme.equals("http") && local))
                    || host.isEmpty()
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                    || uri.getPort() == 0
                    || uri.getPort() > 65535) {
                throw new IOException("请输入 HTTPS 服务根地址；HTTP 仅允许本机或模拟器开发地址");
            }
            int port = uri.getPort();
            if ((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80))
                port = -1;
            return new URI(scheme, null, host, port, null, null, null).toASCIIString();
        } catch (java.net.URISyntaxException | IllegalArgumentException error) {
            throw new IOException("API 地址无效", error);
        }
    }

    public static String key(String base) throws IOException {
        return hex(digest().digest(normalize(base).getBytes(StandardCharsets.UTF_8)));
    }

    public static String query(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException error) {
            throw new AssertionError(error);
        }
    }

    public ProjectPage projects(
            String search, String kind, String tag, String game, String sort, int page)
            throws IOException {
        return JSON.fromJson(
                request(
                        "GET",
                        "/api/v1/projects?q="
                                + query(search)
                                + "&kind="
                                + query(kind)
                                + "&tag="
                                + query(tag)
                                + "&game_version="
                                + query(game)
                                + "&sort="
                                + query(sort)
                                + "&page="
                                + page
                                + "&size=20",
                        null,
                        ""),
                ProjectPage.class);
    }

    public Detail detail(long id) throws IOException {
        if (id < 1) throw new IOException("作品 ID 无效");
        Detail detail =
                JSON.fromJson(request("GET", "/api/v1/projects/" + id, null, ""), Detail.class);
        if (detail.project == null || detail.project.id != id) throw new IOException("作品响应无效");
        return detail;
    }

    public JsonElement request(String method, String path, JsonObject body, String csrf)
            throws IOException {
        HttpURLConnection connection = open(path);
        try {
            connection.setRequestMethod(method);
            if (!csrf.isEmpty()) {
                connection.setRequestProperty("X-CSRF-Token", csrf);
                connection.setRequestProperty("Origin", base);
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(data);
                }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new ApiException(status);
            if (status == 204) return new JsonObject();
            try (InputStream in = connection.getInputStream()) {
                JsonObject root =
                        JsonParser.parseString(
                                        new String(
                                                read(in, 8 * 1024 * 1024), StandardCharsets.UTF_8))
                                .getAsJsonObject();
                if (!root.has("data")) throw new IOException("API 响应缺少 data");
                return root.get("data");
            } catch (RuntimeException error) {
                throw new IOException("API 响应格式无效", error);
            }
        } finally {
            connection.disconnect();
        }
    }

    public void download(PackageFile file, File destination, Transfer transfer, Progress progress)
            throws IOException {
        if (file.id < 1
                || file.size < 1
                || file.size > MAX_PACKAGE
                || !file.sha256.matches("[a-f0-9]{64}")) {
            throw new IOException("下载元数据无效或超过 200 MiB");
        }
        transfer.check();
        HttpURLConnection connection = open("/api/v1/files/" + file.id + "/download");
        transfer.connection = connection;
        MessageDigest hash = digest();
        boolean complete = false;
        try {
            transfer.check();
            if (connection.getResponseCode() != 200)
                throw new ApiException(connection.getResponseCode());
            if (connection.getContentLengthLong() != file.size
                    || !file.sha256.equals(connection.getHeaderField("X-Checksum-SHA256")))
                throw new IOException("下载响应的长度或 SHA-256 与版本记录不一致");
            try (InputStream in = connection.getInputStream();
                    OutputStream out = Files.newOutputStream(destination.toPath())) {
                byte[] buffer = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buffer)) != -1) {
                    transfer.check();
                    total += n;
                    if (total > file.size) throw new IOException("下载超过声明大小");
                    hash.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                    progress.update(total, file.size);
                }
                transfer.check();
                if (total != file.size || !hex(hash.digest()).equals(file.sha256))
                    throw new IOException("下载不完整或 SHA-256 校验失败");
                complete = true;
            }
        } finally {
            transfer.connection = null;
            connection.disconnect();
            if (!complete) Files.deleteIfExists(destination.toPath());
        }
    }

    private HttpURLConnection open(String path) throws IOException {
        if (!path.startsWith("/")
                || path.startsWith("//")
                || path.indexOf('\r') >= 0
                || path.indexOf('\n') >= 0) {
            throw new IOException("无效 API 路径");
        }
        HttpURLConnection result =
                (HttpURLConnection) URI.create(base + path).toURL().openConnection();
        result.setInstanceFollowRedirects(false);
        result.setConnectTimeout(15000);
        result.setReadTimeout(30000);
        result.setUseCaches(false);
        result.setRequestProperty("Accept", "application/json");
        result.setRequestProperty("Accept-Encoding", "identity");
        if (!cookie.isEmpty()) result.setRequestProperty("Cookie", cookie);
        return result;
    }

    static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (output.size() + n > limit) throw new IOException("响应或元数据过大");
            output.write(buffer, 0, n);
        }
        return output.toByteArray();
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError(error);
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
