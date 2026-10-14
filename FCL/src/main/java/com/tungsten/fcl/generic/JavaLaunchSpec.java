package com.tungsten.fcl.generic;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.jar.JarFile;

/** An Android-independent, token-based Java launch configuration. No version JSON or account. */
public final class JavaLaunchSpec implements Serializable {
    private static final long serialVersionUID = 1L;
    public final String jar;
    public final List<String> classpath;
    public final String mainClass;
    public final List<String> jvmArgs;
    public final List<String> gameArgs;
    public final String workingDir;
    public final String runtimeDir;

    public JavaLaunchSpec(String jar, List<String> classpath, String mainClass,
                          List<String> jvmArgs, List<String> gameArgs,
                          String workingDir, String runtimeDir) {
        this.jar = jar.trim();
        this.classpath = copy(classpath);
        this.mainClass = mainClass.trim();
        this.jvmArgs = copy(jvmArgs);
        this.gameArgs = copy(gameArgs);
        this.workingDir = workingDir.trim();
        this.runtimeDir = runtimeDir.trim();
    }

    private static List<String> copy(List<String> args) {
        return Collections.unmodifiableList(new ArrayList<>(args));
    }

    /** Each line is a single literal argument; whitespace inside the line is preserved. */
    public static List<String> lines(String value) {
        List<String> result = new ArrayList<>();
        if (!value.isEmpty()) {
            Collections.addAll(result, value.split("\\r?\\n", -1));
        }
        return result;
    }

    public void validate() throws IOException {
        File work = absolute(workingDir, "Working directory");
        if (!work.isDirectory() || !work.canRead() || !work.canWrite()) {
            throw new IOException("Working directory must exist and be readable/writable: " + work);
        }
        File runtime = absolute(runtimeDir, "Runtime");
        if (!new File(runtime, "release").isFile() || !new File(runtime, "bin/java").isFile()) {
            throw new IOException("Select an installed Android JRE (release and bin/java are required)");
        }
        if (jar.isEmpty() && classpath.isEmpty()) {
            throw new IOException("Supply a JAR or classpath");
        }
        if (!jar.isEmpty()) {
            File target = readable(jar, work);
            if (!target.isFile()) throw new IOException("JAR must be a file");
            try (JarFile archive = new JarFile(target)) {
                if (mainClass.isEmpty()) {
                    String entry = archive.getManifest() == null ? null
                            : archive.getManifest().getMainAttributes().getValue("Main-Class");
                    if (entry == null || entry.trim().isEmpty()) {
                        throw new IOException("Executable JAR has no Main-Class; specify a main class");
                    }
                }
            }
        }
        if (mainClass.isEmpty() && (jar.isEmpty() || !classpath.isEmpty())) {
            throw new IOException("Classpath needs a main class; JAR mode cannot add classpath entries");
        }
        if (!mainClass.isEmpty() && !mainClass.matches("[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*(\\.[\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)*")) {
            throw new IOException("Invalid fully qualified main class");
        }
        for (String path : classpath) readable(path, work);
        for (String arg : jvmArgs) {
            token(arg);
            // The entry point is assembled below, not smuggled through JVM options/argfiles.
            if (!arg.startsWith("-") || arg.equals("-jar") || arg.equals("-cp")
                    || arg.startsWith("-classpath") || arg.startsWith("--class-path")
                    || arg.equals("-m") || arg.startsWith("--module") || arg.startsWith("-p")
                    || arg.equals("-version") || arg.equals("--version")
                    || arg.equals("-help") || arg.equals("--help") || arg.equals("-h")
                    || arg.startsWith("-Xbootclasspath") || arg.equals("--disable-@files")) {
                throw new IOException("Unsupported JVM option: " + arg + "; put entry points in their fields");
            }
        }
        for (String arg : gameArgs) token(arg);
    }

    private static void token(String value) throws IOException {
        if (value == null || value.isEmpty() || value.indexOf('\0') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException("Arguments/paths cannot be empty or contain NUL/newlines");
        }
    }

    private static File absolute(String path, String label) throws IOException {
        token(path);
        File file = new File(path);
        if (!file.isAbsolute()) throw new IOException(label + " must be an absolute path");
        return file.getCanonicalFile();
    }

    private static File readable(String path, File work) throws IOException {
        token(path);
        if (path.indexOf(File.pathSeparatorChar) >= 0) throw new IOException("Classpath paths cannot contain ':'");
        File file = new File(path);
        if (!file.isAbsolute()) file = new File(work, path);
        file = file.getCanonicalFile();
        if (!file.exists() || !file.canRead()) throw new IOException("Unreadable path: " + file);
        return file;
    }

    /** Caller adds Android runtime/renderer options before these literal tokens. */
    public List<String> command(List<String> runtimeClasspath) throws IOException {
        validate();
        List<String> result = new ArrayList<>(jvmArgs);
        if (mainClass.isEmpty() && runtimeClasspath.isEmpty()) {
            result.add("-jar");
            result.add(readable(jar, new File(workingDir)).getPath());
        } else {
            List<String> cp = new ArrayList<>(runtimeClasspath);
            if (!jar.isEmpty()) cp.add(readable(jar, new File(workingDir)).getPath());
            for (String path : classpath) cp.add(readable(path, new File(workingDir)).getPath());
            result.add("-cp");
            result.add(String.join(File.pathSeparator, cp));
            String entry = mainClass;
            if (entry.isEmpty()) {
                try (JarFile archive = new JarFile(readable(jar, new File(workingDir)))) {
                    entry = archive.getManifest().getMainAttributes().getValue("Main-Class").trim();
                }
            }
            result.add(entry);
        }
        result.addAll(gameArgs);
        return result;
    }
}
