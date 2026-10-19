package com.tungsten.fcl.generic;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public class GameLoginProtocolTest {
    private final String secret = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789";

    private JsonObject response(Instant time) {
        JsonObject value = new JsonObject();
        value.addProperty("protocol", 1);
        value.addProperty("api_url", "https://example.com");
        value.addProperty("login_code", secret);
        value.addProperty("expires_at", time.plusSeconds(60).toString());
        return value;
    }

    @Test
    public void ticketIsEphemeralOriginBoundAndArgumentsAreLiteral() throws Exception {
        Instant now = Instant.now();
        var ticket = GameLoginProtocol.parse(response(now), "https://example.com", now);
        assertEquals(
                List.of("--pvzceApiUrl", "https://example.com", "--pvzceLoginCode", secret),
                ticket.arguments());
        assertFalse(ticket.toString().contains(secret));
        assertFalse(java.io.Serializable.class.isAssignableFrom(ticket.getClass()));
        for (String flag :
                new String[] {
                    "--pvzceLoginCode",
                    "--pvzceLoginCode=" + secret,
                    "--pvzceApiUrl=https://example.com"
                }) {
            try {
                JavaLaunchSpec.rejectLoginArguments(List.of(flag));
                fail();
            } catch (IOException error) {
                assertFalse(error.getMessage().contains(secret));
            }
        }
    }

    @Test
    public void malformedForeignExpiredAndOverlongTicketsFailWithoutEchoingSecrets()
            throws Exception {
        Instant now = Instant.now();
        for (String key : new String[] {"api_url", "expires_at", "login_code", "protocol"}) {
            JsonObject body = response(now);
            body.addProperty(key, key.equals("api_url") ? "https://foreign.example" : secret + "+");
            try {
                GameLoginProtocol.parse(body, "https://example.com", now);
                fail();
            } catch (IOException error) {
                assertFalse(error.toString().contains(secret));
                assertNull(error.getCause());
            }
        }
        for (long seconds : new long[] {-1, 0, 91, 3600}) {
            JsonObject body = response(now);
            body.addProperty("expires_at", now.plusSeconds(seconds).toString());
            try {
                GameLoginProtocol.parse(body, "https://example.com", now);
                fail();
            } catch (IOException expected) {
            }
        }
    }

    private boolean jar(String descriptor) throws Exception {
        File file = Files.createTempFile("login-game", ".jar").toFile();
        try {
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file.toPath()))) {
                if (descriptor != null) {
                    out.putNextEntry(new JarEntry("META-INF/pvzce-launcher.json"));
                    out.write(descriptor.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    out.closeEntry();
                }
            }
            return GameLoginProtocol.supportedGame(file);
        } finally {
            file.delete();
        }
    }

    @Test
    public void currentOrUnsafeGamesNeverAdvertiseLoginSupport() throws Exception {
        assertFalse(jar(null));
        assertFalse(jar("{\"game_login_protocol\":1}"));
        assertFalse(jar("{\"game_login_protocol\":1,\"sanitized_launch_arguments\":false}"));
        assertTrue(jar("{\"game_login_protocol\":1,\"sanitized_launch_arguments\":true}"));
    }

    @Test
    public void argumentAndChunkRedactionCoversEveryBoundary() {
        assertArrayEquals(
                new String[] {
                    "--pvzceLoginCode",
                    "[redacted]",
                    "--pvzceLoginCode=[redacted]",
                    "--accessToken=[redacted]",
                    "ok"
                },
                LaunchSecretFilter.arguments(
                        new String[] {
                            "--pvzceLoginCode",
                            secret,
                            "--pvzceLoginCode=" + secret,
                            "--accessToken=" + secret,
                            "ok"
                        }));
        for (int split = 0; split <= secret.length(); split++) {
            LaunchSecretFilter filter = new LaunchSecretFilter(secret);
            String result =
                    filter.accept("before " + secret.substring(0, split))
                            + filter.accept(secret.substring(split) + " after")
                            + filter.finish();
            assertEquals("before [redacted] after", result);
        }
        LaunchSecretFilter filter = new LaunchSecretFilter(secret);
        StringBuilder out = new StringBuilder();
        for (char c : ("a " + secret + " b " + secret).toCharArray())
            out.append(filter.accept(String.valueOf(c)));
        out.append(filter.finish());
        assertEquals("a [redacted] b [redacted]", out.toString());
        assertEquals("normal", new LaunchSecretFilter("").accept("normal"));
        LaunchSecretFilter partial = new LaunchSecretFilter(secret);
        assertEquals("", partial.accept(secret.substring(0, 10)));
        assertEquals("[redacted]", partial.finish());
    }
}
