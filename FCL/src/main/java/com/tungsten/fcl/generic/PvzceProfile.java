package com.tungsten.fcl.generic;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;

/** Contract verified against pvzce/pvzce, not Minecraft's Fabric installation pipeline. */
public final class PvzceProfile {
    public static final String MAIN_CLASS = "net.fabricmc.loader.impl.launch.knot.KnotClient";
    public static final String LWJGL = "3.4.1";
    public static final String NATIVE_ACCESS = "--enable-native-access=ALL-UNNAMED";
    private static final String[] DIRECTORIES = {"mods", "resourcepacks", "datapacks", "config", "saves"};

    private PvzceProfile() {
    }

    public static File prepareDirectory(File directory) throws IOException {
        File root = directory.getCanonicalFile();
        if (!directory.isAbsolute()) throw new IOException("PVZCE game directory must be absolute");
        Files.createDirectories(root.toPath());
        for (String name : DIRECTORIES) Files.createDirectories(new File(root, name).toPath());
        return root;
    }

    public static JavaLaunchSpec configure(JavaLaunchSpec spec, String lwjgl) throws IOException {
        spec.validate();
        if (!LWJGL.equals(lwjgl)) throw new IOException("PVZCE needs patched LWJGL " + LWJGL + " (GLFW platform API)");
        Properties release = new Properties();
        try (Reader reader = Files.newBufferedReader(new File(spec.runtimeDir, "release").toPath())) {
            release.load(reader);
        }
        String version = release.getProperty("JAVA_VERSION", "").replace("\"", "");
        int major;
        try {
            major = Integer.parseInt(version.split("[.+\\-]", 2)[0]);
        } catch (NumberFormatException error) {
            throw new IOException("Unrecognized runtime JAVA_VERSION: " + version, error);
        }
        if (major < 25) {
            throw new IOException("PVZCE requires Android JRE 25 or newer; selected: " + version);
        }
        if (spec.jar.isEmpty() || !spec.classpath.isEmpty() || !MAIN_CLASS.equals(spec.mainClass)) {
            throw new IOException("PVZCE preset needs its shadow/fat JAR, no extra classpath, and KnotClient main class");
        }
        File jar = new File(spec.jar);
        if (!jar.isAbsolute()) jar = new File(spec.workingDir, spec.jar);
        try (JarFile archive = new JarFile(jar)) {
            for (String entry : new String[] {MAIN_CLASS.replace('.', '/') + ".class",
                    "com/pvzce/launcher/PvzceGame.class", "com/pvzce/launcher/PvzceGameProvider.class",
                    "META-INF/services/net.fabricmc.loader.impl.game.GameProvider"}) {
                if (archive.getJarEntry(entry) == null) throw new IOException("Not a complete PVZCE shadow JAR: missing " + entry);
            }
        }
        List<String> jvm = new ArrayList<>(spec.jvmArgs);
        for (String arg : jvm) {
            if (arg.startsWith("--enable-native-access=") && !NATIVE_ACCESS.equals(arg)) {
                throw new IOException("PVZCE needs " + NATIVE_ACCESS);
            }
            if (arg.equals("-Dfabric.development") || arg.startsWith("-Dfabric.development=")
                    || arg.startsWith("-Dpvzce.platform=")) {
                throw new IOException("Remove desktop/development override from PVZCE JVM options: " + arg);
            }
        }
        if (!jvm.contains(NATIVE_ACCESS)) jvm.add(NATIVE_ACCESS);
        jvm.add("-Dfabric.development=false");
        File work = new File(spec.workingDir).getCanonicalFile();
        List<String> args = new ArrayList<>(spec.gameArgs);
        for (String arg : args) {
            if (arg.equals("--gameDir") || arg.startsWith("--gameDir=")) {
                throw new IOException("PVZCE preset supplies --gameDir from Working directory; remove the duplicate argument");
            }
        }
        args.add("--gameDir");
        args.add(work.getPath());
        prepareDirectory(work);
        return new JavaLaunchSpec(spec.jar, spec.classpath, MAIN_CLASS, jvm, args, work.getPath(), spec.runtimeDir);
    }
}
