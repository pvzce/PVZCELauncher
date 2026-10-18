package com.tungsten.fcl.generic;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class WorkshopApiTest {
    /** Real HTTP bytes with deterministic responses; no production account or backend is simulated as verified. */
    private static final class Server implements AutoCloseable {
        final ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
        final CompletableFuture<String> request = new CompletableFuture<>();

        Server(int status, String headers, byte[] body) throws IOException {
            Thread thread = new Thread(() -> {
                try (Socket client = socket.accept()) {
                    client.setSoTimeout(5000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder received = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) received.append(line).append('\n');
                    request.complete(received.toString());
                    byte[] prefix = ("HTTP/1.1 " + status + " Test\r\nConnection: close\r\n" + headers + "\r\n").getBytes(StandardCharsets.UTF_8);
                    client.getOutputStream().write(prefix); client.getOutputStream().write(body); client.getOutputStream().flush();
                } catch (Exception error) { request.completeExceptionally(error); }
            });
            thread.setDaemon(true); thread.start();
        }

        String base() { return "http://127.0.0.1:" + socket.getLocalPort(); }
        @Override public void close() throws IOException { socket.close(); }
    }

    @Test public void apiOriginsAreCanonicalAndDoNotMergePortsOrSchemes() throws Exception {
        assertEquals("https://example.com", WorkshopApi.normalize("HTTPS://EXAMPLE.COM:443/"));
        assertEquals(WorkshopApi.key("https://example.com"), WorkshopApi.key("https://EXAMPLE.COM/"));
        assertNotEquals(WorkshopApi.key("https://example.com:8443"), WorkshopApi.key("https://example.com"));
        for (String invalid : new String[] {"http://example.com", "https://user:pass@example.com", "https://example.com/api", "https://example.com?x=1", "file:///a", "https://example.com/#token"}) {
            try { WorkshopApi.normalize(invalid); fail(invalid); } catch (IOException expected) { /* Invalid origin. */ }
        }
    }

    @Test public void catalogueEncodesFiltersAndUsesBackendEnvelope() throws Exception {
        byte[] body = "{\"data\":{\"items\":[{\"id\":4,\"kind\":\"mod\",\"title\":\"demo\"}],\"page\":2,\"size\":20,\"total\":21}}".getBytes(StandardCharsets.UTF_8);
        try (Server server = new Server(200, "Content-Length: " + body.length + "\r\n", body)) {
            WorkshopApi.ProjectPage result = new WorkshopApi(server.base(), "").projects("a&b", "mod", "中文", "1.0.0", "downloads", 2);
            assertEquals(4, result.items.get(0).id); assertEquals(21, result.total);
            String request = server.request.get(5, TimeUnit.SECONDS);
            assertTrue(request.contains("q=a%26b")); assertTrue(request.contains("page=2"));
            assertFalse(request.toLowerCase().contains("cookie:"));
        }
    }

    @Test public void redirectDoesNotForwardSessionAnd401IsExplicit() throws Exception {
        try (Server server = new Server(302, "Location: https://untrusted.example/\r\nContent-Length: 0\r\n", new byte[0])) {
            try { new WorkshopApi(server.base(), "pvzce_session=secret").request("GET", "/api/v1/account/me", null, ""); fail(); }
            catch (WorkshopApi.ApiException expected) { assertEquals(302, expected.status); }
            assertTrue(server.request.get(5, TimeUnit.SECONDS).contains("pvzce_session=secret"));
        }
        try (Server server = new Server(401, "Content-Length: 0\r\n", new byte[0])) {
            try { new WorkshopApi(server.base(), "").request("GET", "/api/v1/account/me", null, ""); fail(); }
            catch (WorkshopApi.ApiException expected) { assertEquals(401, expected.status); assertTrue(expected.getMessage().contains("过期")); }
        }
    }

    private WorkshopApi.PackageFile file(byte[] bytes) {
        WorkshopApi.PackageFile file = new WorkshopApi.PackageFile(); file.id = 5; file.size = bytes.length;
        file.sha256 = WorkshopApi.hex(WorkshopApi.digest().digest(bytes)); return file;
    }

    @Test public void checksumIsCheckedAndFailedFilesAreRemoved() throws Exception {
        byte[] bytes = "trusted package".getBytes(StandardCharsets.UTF_8);
        WorkshopApi.PackageFile file = file(bytes);
        File destination = Files.createTempFile("workshop-download", ".tmp").toFile();
        String headers = "Content-Length: " + bytes.length + "\r\nX-Checksum-SHA256: " + file.sha256 + "\r\n";
        try {
            try (Server server = new Server(200, headers, bytes)) {
                new WorkshopApi(server.base(), "").download(file, destination, new WorkshopApi.Transfer(), (n, total) -> assertTrue(n <= total));
                assertArrayEquals(bytes, Files.readAllBytes(destination.toPath()));
            }
            try (Server server = new Server(200, headers, "altered package".getBytes(StandardCharsets.UTF_8))) {
                try { new WorkshopApi(server.base(), "").download(file, destination, new WorkshopApi.Transfer(), (n, total) -> { }); fail(); }
                catch (IOException expected) { assertFalse(destination.exists()); }
            }
        } finally { Files.deleteIfExists(destination.toPath()); }
    }

    @Test public void cancellationDuringTransferDeletesPartialDownload() throws Exception {
        byte[] bytes = new byte[128 * 1024];
        WorkshopApi.PackageFile file = file(bytes);
        File destination = Files.createTempFile("workshop-download", ".tmp").toFile();
        String headers = "Content-Length: " + bytes.length + "\r\nX-Checksum-SHA256: " + file.sha256 + "\r\n";
        try (Server server = new Server(200, headers, bytes)) {
            WorkshopApi.Transfer transfer = new WorkshopApi.Transfer();
            try { new WorkshopApi(server.base(), "").download(file, destination, transfer, (n, total) -> transfer.cancel()); fail(); }
            catch (IOException expected) { assertFalse(destination.exists()); }
        } finally { Files.deleteIfExists(destination.toPath()); }
    }
}
