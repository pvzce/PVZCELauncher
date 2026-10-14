package com.tungsten.fcl.generic;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Pure configuration tests; fixture files are not Android runtimes or real games. */
public class JavaLaunchSpecTest {
    private Path root;
    private Path runtime;
    private Path jar;

    @Before
    public void setup() throws IOException {
        root = Files.createTempDirectory("pvzce-config-").toRealPath();
        runtime = Files.createDirectories(root.resolve("runtime/bin")).getParent();
        Files.write(runtime.resolve("release"), "JAVA_VERSION=\"17\"\n".getBytes(StandardCharsets.UTF_8));
        Files.write(runtime.resolve("bin/java"), "validation fixture only".getBytes(StandardCharsets.UTF_8));
        jar = archive("fixture.jar", "example.Entry");
    }

    private Path archive(String filename, String main) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, main);
        Path path = root.resolve(filename);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            output.flush();
        }
        return path;
    }

    private JavaLaunchSpec spec(String target, List<String> cp, String main, List<String> jvm) {
        return new JavaLaunchSpec(target, cp, main, jvm,
                Arrays.asList("argument with spaces", "$(literal)"), root.toString(), runtime.toString());
    }

    private void rejected(JavaLaunchSpec spec) throws IOException {
        try {
            spec.validate();
            fail("Expected configuration to be rejected");
        } catch (IOException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    @Test
    public void literalArgumentsAndRuntimePrecedence() throws IOException {
        List<String> command = spec(jar.toString(), Collections.emptyList(), "example.Entry",
                Collections.singletonList("-Dfixture=a value")).command(Collections.singletonList("android-lwjgl.jar"));
        assertEquals(Arrays.asList("-Dfixture=a value", "-cp",
                "android-lwjgl.jar:" + jar, "example.Entry", "argument with spaces", "$(literal)"), command);
    }

    @Test
    public void realManifestEntryIsUsedWithBundledClasspath() throws IOException {
        List<String> command = spec(jar.toString(), Collections.emptyList(), "", Collections.emptyList())
                .command(Collections.singletonList(root.toString()));
        assertTrue(command.contains("example.Entry"));
        assertFalse(command.contains("-jar"));
    }

    @Test
    public void malformedClasspathArchiveIsRejected() throws IOException {
        Path corrupt = root.resolve("corrupt.jar");
        Files.write(corrupt, "not an archive".getBytes(StandardCharsets.UTF_8));
        rejected(spec("", Collections.singletonList(corrupt.toString()), "example.Entry", Collections.emptyList()));
    }

    @Test
    public void malformedManifestMainIsRejected() throws IOException {
        rejected(spec(archive("invalid-main.jar", "bad/main").toString(), Collections.emptyList(), "", Collections.emptyList()));
    }

    @Test
    public void launcherExitAndEntrypointOptionsAreRejected() throws IOException {
        for (String option : Arrays.asList("--", "--dry-run", "--list-modules", "--validate-modules", "--source=17", "-jar", "-cp", "@args")) {
            rejected(spec(jar.toString(), Collections.emptyList(), "example.Entry", Collections.singletonList(option)));
        }
    }

    @Test
    public void relativeTargetsResolveAgainstWorkdir() throws IOException {
        List<String> command = spec("fixture.jar", Collections.emptyList(), "example.Entry", Collections.emptyList())
                .command(Collections.emptyList());
        assertEquals(jar.toString(), command.get(1));
    }

    @Test
    public void lineTokenizerPreservesSpacesAndRejectsBlankJvmLine() throws IOException {
        assertEquals(Arrays.asList("one value", "two"), JavaLaunchSpec.lines("one value\ntwo"));
        rejected(spec(jar.toString(), Collections.emptyList(), "example.Entry", JavaLaunchSpec.lines("-Xmx512m\n")));
    }

    @After
    public void cleanup() throws IOException {
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) {
                Files.delete(path);
            }
        }
    }
}
