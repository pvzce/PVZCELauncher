import com.tungsten.fcl.generic.GameSessionLog;
import com.tungsten.fcl.generic.LwjglRuntimeOptions;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** JVM/IO regression fixtures, not an Android device or a PVZCE game run. */
public final class AudioCompatibilityChecks {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) {
            System.out.println(System.getProperty("org.lwjgl.openal.libname"));
            System.out.println(System.getProperty("org.lwjgl.librarypath"));
            System.out.println(System.getProperty("org.lwjgl.system.allocator"));
            return;
        }
        Path root = Files.createTempDirectory("audio compatibility 中文 ");
        try {
            Path app = Files.createDirectories(root.resolve("apk lib/arm64-v8a"));
            Path lwjgl = Files.createDirectories(root.resolve("lwjgl natives/arm64-v8a"));
            List<String> options = LwjglRuntimeOptions.arguments(lwjgl.toFile(), app.toFile());
            String actual = launch(options);
            check(actual.equals(app.resolve("libopenal.so") + "\n" + lwjgl + "\nsystem\n"),
                    "Android library defaults or literal paths changed: " + actual);
            List<String> override = new ArrayList<>(options);
            override.add("-Dorg.lwjgl.openal.libname=/custom/backend.so");
            check(launch(override).startsWith("/custom/backend.so\n"), "Explicit user option must retain precedence");

            Path logFile = root.resolve("session.log");
            Files.writeString(logFile, "old session must be replaced");
            GameSessionLog log = new GameSessionLog(logFile.toFile());
            StringBuilder expected = new StringBuilder();
            int requests = 0;
            for (int i = 0; i < 10000; i++) {
                String line = "音频 warning " + i + "\n";
                expected.append(line);
                if (log.append(line)) requests++;
            }
            check(requests == 1, "Log burst enqueued " + requests + " UI callbacks instead of one");
            check(log.snapshot().equals(expected.substring(expected.length() - GameSessionLog.DISPLAY_LIMIT)),
                    "Display must retain the latest bounded tail in order");
            check(Files.readString(logFile).equals(expected.toString()), "Full UTF-8 file was truncated or not flushed");
            check(log.append("after refresh\n"), "New data after snapshot needs a new callback");
            log.close();
            String finalFile = Files.readString(logFile);
            check(!log.append("late callback"), "Closed session must not schedule updates");
            check(Files.readString(logFile).equals(finalFile), "Late callback reopened/truncated the file");

            try (GameSessionLog unavailable = new GameSessionLog(root.resolve("missing/session.log").toFile())) {
                check(unavailable.append("file unavailable"), "Disk failure lost the on-screen log");
                check(unavailable.snapshot().equals("file unavailable"), "Disk failure lost the on-screen text");
                String oversized = "😀" + "x".repeat(GameSessionLog.DISPLAY_LIMIT - 1);
                unavailable.append(oversized);
                String tail = unavailable.snapshot();
                check(tail.length() < GameSessionLog.DISPLAY_LIMIT && tail.startsWith("x"),
                        "Tail truncation split a UTF-16 surrogate pair");
            }

            try (GameSessionLog concurrent = new GameSessionLog(root.resolve("concurrent.log").toFile())) {
                CountDownLatch ready = new CountDownLatch(1);
                AtomicInteger scheduled = new AtomicInteger();
                List<Thread> writers = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    Thread writer = new Thread(() -> {
                        try {
                            ready.await();
                            for (int j = 0; j < 250; j++) {
                                if (concurrent.append("concurrent log\n")) scheduled.incrementAndGet();
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(error);
                        }
                    });
                    writers.add(writer);
                    writer.start();
                }
                ready.countDown();
                for (Thread writer : writers) writer.join();
                check(scheduled.get() == 1, "Concurrent log producers scheduled duplicate callbacks");
                check(Files.readString(root.resolve("concurrent.log")).equals("concurrent log\n".repeat(1000)),
                        "Concurrent file output lost data");
            }
            System.out.println("PASS Android JVM library arguments, user override, log burst/concurrency, full file, bounded UI tail, IO failure and lifecycle checks.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static String launch(List<String> options) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/java").getPath());
        command.addAll(options);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(AudioCompatibilityChecks.class.getName());
        command.add("properties");
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(process.waitFor() == 0, "JVM configuration fixture failed: " + output);
        return output;
    }
}
