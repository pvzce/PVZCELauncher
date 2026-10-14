import com.tungsten.fcl.generic.GameInputState;
import com.tungsten.fcl.generic.GameContentPaths;
import com.tungsten.fcl.generic.JavaLaunchSpec;
import com.tungsten.fcl.generic.PvzceProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Preflight fixtures only: does not pretend to contain or launch the real PVZCE game. */
public final class PvzceProfileChecks {
    private static int checks;
    private static Path root;
    private static Path runtime;
    private static Path jar;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static JavaLaunchSpec spec(List<String> jvm, List<String> args) {
        return new JavaLaunchSpec(jar.toString(), Collections.emptyList(), PvzceProfile.MAIN_CLASS,
                jvm, args, root.toString(), runtime.toString());
    }

    private static void rejects(JavaLaunchSpec spec, String lwjgl) throws Exception {
        try {
            PvzceProfile.configure(spec, lwjgl);
            throw new AssertionError("Accepted invalid PVZCE configuration");
        } catch (IOException expected) {
            checks++;
        }
    }

    public static void main(String[] ignored) throws Exception {
        root = Files.createTempDirectory("pvzce profile with spaces ").toRealPath();
        try {
            runtime = Files.createDirectories(root.resolve("runtime/bin")).getParent();
            Files.writeString(runtime.resolve("bin/java"), "validation fixture only");
            Files.writeString(runtime.resolve("release"), "JAVA_VERSION=\"25.0.1\"\n");
            jar = root.resolve("preflight-markers-only.jar");
            String[] entries = {PvzceProfile.MAIN_CLASS.replace('.', '/') + ".class",
                    "com/pvzce/launcher/PvzceGame.class", "com/pvzce/launcher/PvzceGameProvider.class",
                    "META-INF/services/net.fabricmc.loader.impl.game.GameProvider"};
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
                for (String entry : entries) {
                    output.putNextEntry(new JarEntry(entry));
                    output.write("preflight marker, not executable bytecode".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    output.closeEntry();
                }
            }
            JavaLaunchSpec configured = PvzceProfile.configure(spec(Collections.singletonList("-Xmx1024m"),
                    Collections.singletonList("--versionType=development")), PvzceProfile.LWJGL);
            check(configured.jvmArgs.contains(PvzceProfile.NATIVE_ACCESS), "Missing FFM native access");
            check(configured.jvmArgs.contains("-Dfabric.development=false"), "Missing loader release mode");
            check(configured.gameArgs.equals(Arrays.asList("--versionType=development", "--gameDir", root.toString())), "Game directory argument changed");
            check(configured.command(Collections.singletonList("ANDROID-PATCHED-LWJGL")).contains(PvzceProfile.MAIN_CLASS), "Wrong loader entry point");
            for (String name : new String[] {"mods", "resourcepacks", "datapacks", "config", "saves"}) {
                check(Files.isDirectory(root.resolve(name)), "Missing content directory " + name);
            }
            rejects(spec(Collections.emptyList(), Collections.emptyList()), "3.3.3");
            for (String arg : new String[] {"--gameDir", "--gameDir=/other"}) {
                rejects(spec(Collections.emptyList(), Collections.singletonList(arg)), PvzceProfile.LWJGL);
            }
            for (String arg : new String[] {"-Dfabric.development=true", "-Dfabric.development", "-Dpvzce.platform=wayland", "--enable-native-access=another.module"}) {
                rejects(spec(Collections.singletonList(arg), Collections.emptyList()), PvzceProfile.LWJGL);
            }
            for (String version : new String[] {"1.8.0", "17.0.20", "21.0.4", "invalid", "999999999999999"}) {
                Files.writeString(runtime.resolve("release"), "JAVA_VERSION=\"" + version + "\"\n");
                rejects(spec(Collections.emptyList(), Collections.emptyList()), PvzceProfile.LWJGL);
            }
            Files.writeString(runtime.resolve("release"), "JAVA_VERSION=\"25\"\n");
            for (int missing = 0; missing < entries.length; missing++) {
                try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
                    for (int i = 0; i < entries.length; i++) {
                        if (i == missing) continue;
                        output.putNextEntry(new JarEntry(entries[i]));
                        output.closeEntry();
                    }
                }
                rejects(spec(Collections.emptyList(), Collections.emptyList()), PvzceProfile.LWJGL);
            }
            check(GameContentPaths.child(root.toFile(), "中文 pack").getName().equals("中文 pack"), "Valid pack name changed");
            for (String name : new String[] {"..", ".", "../outside", "/absolute", "a/b", "a\\b", "", "   ", "nul\0"}) {
                try {
                    GameContentPaths.child(root.toFile(), name);
                    throw new AssertionError("Accepted unsafe content filename");
                } catch (IOException expected) {
                    checks++;
                }
            }
            Path outside = Files.createDirectory(root.resolve("outside"));
            Path imports = Files.createDirectory(root.resolve("imports"));
            Files.createSymbolicLink(imports.resolve("escape"), outside);
            try {
                GameContentPaths.child(imports.toFile(), "escape");
                throw new AssertionError("Accepted escaping destination symlink");
            } catch (IOException expected) {
                checks++;
            }
            List<String> events = new ArrayList<>();
            GameInputState input = new GameInputState((key, down) -> events.add(key + ":" + down));
            input.press(31);
            input.press(31);
            input.press(32);
            check(events.equals(Arrays.asList("31:true", "32:true")), "Multi-finger chord or duplicate press broken");
            input.release(31);
            input.release(31);
            check(Collections.frequency(events, "31:false") == 1, "Duplicate key release");
            input.reset();
            input.reset();
            check(Collections.frequency(events, "32:false") == 1, "Focus/cancel reset left a held key");
            check(GameInputState.checkedText("你好 / setName").equals("你好 / setName"), "Chinese text changed");
            for (String text : new String[] {"\ud83c\udf31", "line\nnext", "nul\0", "\ud800"}) {
                try {
                    GameInputState.checkedText(text);
                    throw new AssertionError("Accepted unsupported JNI character");
                } catch (IllegalArgumentException expected) {
                    checks++;
                }
            }
            System.out.println("PASS " + checks + " PVZCE preflight/input checks; no real game or Android runtime launched.");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).collect(java.util.stream.Collectors.toList())) {
                    Files.delete(path);
                }
            }
        }
    }
}
