package com.tungsten.fcl.generic;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class WorkshopUploadHttpTest {
    private static final class Server implements AutoCloseable {
        final ServerSocket socket =
                new ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"));
        final CompletableFuture<String> headers = new CompletableFuture<>();
        final CompletableFuture<byte[]> body = new CompletableFuture<>();

        Server(int status, String response) throws IOException {
            Thread thread =
                    new Thread(
                            () -> {
                                try (Socket client = socket.accept()) {
                                    client.setSoTimeout(5000);
                                    ByteArrayOutputStream head = new ByteArrayOutputStream();
                                    int c;
                                    while ((c = client.getInputStream().read()) != -1) {
                                        head.write(c);
                                        if (head.toString(StandardCharsets.ISO_8859_1)
                                                .endsWith("\r\n\r\n")) break;
                                    }
                                    String value = head.toString(StandardCharsets.ISO_8859_1);
                                    headers.complete(value);
                                    int length = 0;
                                    for (String line : value.split("\r\n"))
                                        if (line.toLowerCase(Locale.ROOT)
                                                .startsWith("content-length:"))
                                            length = Integer.parseInt(line.substring(15).trim());
                                    byte[] received = client.getInputStream().readNBytes(length);
                                    body.complete(received);
                                    byte[] data = response.getBytes(StandardCharsets.UTF_8);
                                    client.getOutputStream()
                                            .write(
                                                    ("HTTP/1.1 "
                                                                    + status
                                                                    + " Test\r\nContent-Length: "
                                                                    + data.length
                                                                    + "\r\n"
                                                                    + "Connection: close\r\n\r\n")
                                                            .getBytes(StandardCharsets.US_ASCII));
                                    client.getOutputStream().write(data);
                                    client.getOutputStream().flush();
                                } catch (Exception error) {
                                    headers.completeExceptionally(error);
                                    body.completeExceptionally(error);
                                }
                            });
            thread.setDaemon(true);
            thread.start();
        }

        String base() {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }

        public void close() throws IOException {
            socket.close();
        }
    }

    private String response(byte[] bytes, String validation, String scan) {
        return "{\"data\":{\"id\":12,\"project_id\":7,\"version_id\":9,\"purpose\":\"package\",\"size\":"
                + bytes.length
                + ",\"sha256\":\""
                + WorkshopApi.hex(WorkshopApi.digest().digest(bytes))
                + "\",\"validation_state\":\""
                + validation
                + "\",\"scan_state\":\""
                + scan
                + "\"}}";
    }

    @Test
    public void rawPutUsesCookieCsrfOriginHashKeyAndNeverTreatsInvalidUploadAsPublished()
            throws Exception {
        byte[] bytes = "jar fixture body".getBytes(StandardCharsets.UTF_8);
        File file = Files.createTempFile("raw-upload", ".jar").toFile();
        Files.write(file.toPath(), bytes);
        String hash = WorkshopApi.hex(WorkshopApi.digest().digest(bytes)),
                key = UUID.randomUUID().toString();
        try (Server server = new Server(200, response(bytes, "invalid", "clean"))) {
            var result =
                    new WorkshopApi(server.base(), "pvzce_session=fixture")
                            .upload(
                                    7,
                                    9,
                                    "package",
                                    file,
                                    "safe.jar",
                                    hash,
                                    key,
                                    "csrf-fixture",
                                    new WorkshopApi.Transfer(),
                                    (n, total) -> assertTrue(n <= total));
            assertEquals("invalid", result.validation_state);
            assertEquals("clean", result.scan_state);
            assertArrayEquals(bytes, server.body.get(5, TimeUnit.SECONDS));
            String headers = server.headers.get(5, TimeUnit.SECONDS).toLowerCase(Locale.ROOT);
            assertTrue(headers.startsWith("put /api/v1/projects/7/uploads/package?version_id=9 "));
            for (String header :
                    new String[] {
                        "cookie: pvzce_session=fixture",
                        "x-csrf-token: csrf-fixture",
                        "origin: " + server.base(),
                        "upload-sha256: " + hash,
                        "idempotency-key: " + key,
                        "upload-filename: safe.jar",
                        "content-length: " + bytes.length
                    }) assertTrue(header, headers.contains(header));
        } finally {
            file.delete();
        }
    }

    @Test
    public void conflictsAndMismatchedAcknowledgementsDoNotBecomeSuccess() throws Exception {
        byte[] bytes = {1, 2, 3};
        File file = Files.createTempFile("raw-upload", ".jar").toFile();
        Files.write(file.toPath(), bytes);
        try {
            for (int status : new int[] {409, 200})
                try (Server server =
                        new Server(
                                status,
                                status == 409
                                        ? "{}"
                                        : response(bytes, "valid", "clean")
                                                .replace(
                                                        "\"project_id\":7", "\"project_id\":88"))) {
                    try {
                        new WorkshopApi(server.base(), "pvzce_session=fixture")
                                .upload(
                                        7,
                                        9,
                                        "package",
                                        file,
                                        "safe.jar",
                                        WorkshopApi.hex(WorkshopApi.digest().digest(bytes)),
                                        UUID.randomUUID().toString(),
                                        "csrf",
                                        new WorkshopApi.Transfer(),
                                        (n, total) -> {});
                        fail();
                    } catch (IOException error) {
                        if (status == 409)
                            assertEquals(409, ((WorkshopApi.ApiException) error).status);
                    }
                }
            WorkshopApi.Transfer cancelled = new WorkshopApi.Transfer();
            cancelled.cancel();
            try {
                new WorkshopApi("https://example.com", "")
                        .upload(
                                7,
                                9,
                                "package",
                                file,
                                "safe.jar",
                                WorkshopApi.hex(WorkshopApi.digest().digest(bytes)),
                                UUID.randomUUID().toString(),
                                "csrf",
                                cancelled,
                                (n, total) -> {});
                fail();
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("取消"));
            }
        } finally {
            file.delete();
        }
    }

    @Test
    public void missingBackendCapabilityStopsBeforeCredentialIssuance() throws Exception {
        try (Server server = new Server(404, "{}")) {
            try {
                GameLoginProtocol.issue(
                        new WorkshopApi(server.base(), "pvzce_session=fixture"), "csrf", "1.0.0");
                fail();
            } catch (WorkshopApi.ApiException error) {
                assertEquals(404, error.status);
            }
            assertTrue(
                    server.headers
                            .get(5, TimeUnit.SECONDS)
                            .startsWith("GET /api/v1/launcher/capabilities "));
            assertEquals(0, server.body.get(5, TimeUnit.SECONDS).length);
        }
    }
}
