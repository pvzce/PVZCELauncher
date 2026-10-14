import com.tungsten.fcl.generic.JavaLaunchSpec;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/** Standalone behavioral checks; creates a real, harmless fixture, not a game. */
public class GenericLaunchChecks {
    private static int checks;
    private static Path root;
    private static Path runtime;
    private static Path jar;

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
        checks++;
    }

    private static JavaLaunchSpec spec(String jarPath, List<String> cp, String main,
                                       List<String> jvm, List<String> args) {
        return new JavaLaunchSpec(jarPath, cp, main, jvm, args, root.toString(), runtime.toString());
    }

    private static void rejects(JavaLaunchSpec spec, String message) throws Exception {
        try {
            spec.validate();
            throw new AssertionError("Accepted " + message);
        } catch (IOException expected) {
            checks++;
        }
    }

    private static void execute(JavaLaunchSpec spec) throws Exception {
        execute(spec, Collections.emptyList());
    }

    private static void execute(JavaLaunchSpec spec, List<String> bundled) throws Exception {
        List<String> command = new java.util.ArrayList<>();
        command.add(new File(System.getProperty("java.home"), "bin/java").toString());
        command.addAll(spec.command(bundled));
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        check(process.waitFor() == 0, "Fixture Java launch failed: " + output);
        check(output.contains("ARG=argument with spaces") && output.contains("ARG=$(literal)"), "Argument boundaries changed: " + output);
        check(output.contains("PROP=option with spaces"), "JVM option changed: " + output);
        check(output.contains("DIR=" + root), "Working directory changed: " + output);
    }

    public static void main(String[] ignored) throws Exception {
        root = Files.createTempDirectory("pvzce launch checks ").toRealPath();
        runtime = Files.createDirectories(root.resolve("runtime/bin")).getParent();
        // Validation fixture: no claim that this synthetic runtime is an Android JRE.
        Files.writeString(runtime.resolve("release"), "JAVA_VERSION=\"17\"\n");
        Files.writeString(runtime.resolve("bin/java"), "validation fixture only");
        Path source = root.resolve("Echo.java");
        Files.writeString(source, "public class Echo { public static void main(String[] a) { for (String s:a) System.out.println(\"ARG=\"+s); System.out.println(\"PROP=\"+System.getProperty(\"fixture\")); System.out.println(\"DIR=\"+System.getProperty(\"user.dir\")); } }");
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", root.toString(), source.toString()) == 0, "Fixture compilation failed");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "Echo");
        jar = root.resolve("fixture with spaces.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.putNextEntry(new JarEntry("Echo.class"));
            Files.copy(root.resolve("Echo.class"), output);
            output.closeEntry();
        }
        List<String> args = Arrays.asList("argument with spaces", "$(literal)");
        List<String> jvm = Collections.singletonList("-Dfixture=option with spaces");
        execute(spec(jar.toString(), Collections.emptyList(), "", jvm, args));
        execute(spec("", Collections.singletonList(root.toString()), "Echo", jvm, args));
        execute(spec(jar.getFileName().toString(), Collections.emptyList(), "Echo", jvm, args));
        execute(spec(jar.toString(), Collections.emptyList(), "", jvm, args), Collections.singletonList(root.toString()));
        List<String> bundled = spec(jar.toString(), Collections.emptyList(), "", jvm, args).command(Collections.singletonList(root.toString()));
        check(bundled.contains("Echo") && bundled.contains("-cp") && !bundled.contains("-jar"), "Executable JAR must use its real manifest entry with bundled LWJGL");
        check(spec(jar.toString(), Collections.emptyList(), "Echo", jvm, args).command(Collections.singletonList("ANDROID-LWJGL")).get(2).startsWith("ANDROID-LWJGL"), "Android runtime classpath must precede desktop libraries");
        check(JavaLaunchSpec.lines("a value\nb").equals(Arrays.asList("a value", "b")), "Line tokenizer split spaces");
        rejects(spec("", Collections.emptyList(), "", jvm, args), "empty target");
        rejects(spec("missing.jar", Collections.emptyList(), "", jvm, args), "missing jar");
        Path invalid = root.resolve("invalid.jar");
        Files.writeString(invalid, "not a jar");
        rejects(spec(invalid.toString(), Collections.emptyList(), "Echo", jvm, args), "invalid jar");
        rejects(spec("", Collections.singletonList(invalid.toString()), "Echo", jvm, args), "invalid classpath archive");
        Manifest invalidEntry = new Manifest();
        invalidEntry.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        invalidEntry.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "bad/main");
        Path invalidMain = root.resolve("invalid-main.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(invalidMain), invalidEntry)) {
            output.putNextEntry(new JarEntry("Echo.class"));
            Files.copy(root.resolve("Echo.class"), output);
            output.closeEntry();
        }
        rejects(spec(invalidMain.toString(), Collections.emptyList(), "", jvm, args), "invalid manifest entry");
        rejects(spec("", Collections.singletonList(root.toString()), "", jvm, args), "classpath without main");
        rejects(spec(jar.toString(), Collections.singletonList(root.toString()), "", jvm, args), "jar plus ignored classpath");
        rejects(spec(jar.toString(), Collections.emptyList(), "bad/main", jvm, args), "invalid main");
        for (String option : Arrays.asList("-jar", "-cp", "--class-path=x", "@options", "Echo", "-version", "--module=x", "-Xbootclasspath/a:x", "--", "--dry-run", "--list-modules")) {
            rejects(spec(jar.toString(), Collections.emptyList(), "Echo", Collections.singletonList(option), args), "injected entry/argfile: " + option);
        }
        rejects(spec(jar.toString(), Collections.emptyList(), "Echo", JavaLaunchSpec.lines("-Xmx512m\n"), args), "blank JVM line");
        rejects(spec(jar.toString(), Collections.emptyList(), "Echo", jvm, Collections.singletonList("nul\0value")), "NUL argument");
        rejects(new JavaLaunchSpec(jar.toString(), Collections.emptyList(), "Echo", jvm, args, "relative", runtime.toString()), "relative workdir");
        rejects(new JavaLaunchSpec(jar.toString(), Collections.emptyList(), "Echo", jvm, args, root.toString(), root.resolve("missing-runtime").toString()), "missing runtime");
        System.out.println("PASS " + checks + " checks; executable JAR, classpath and relative JAR fixture launches passed.");
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
