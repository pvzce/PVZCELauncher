package com.tungsten.fcl.generic;

import java.io.File;
import java.util.Arrays;
import java.util.List;

/** Android library defaults shared by generic Java and PVZCE launches. */
public final class LwjglRuntimeOptions {
    private LwjglRuntimeOptions() {
    }

    public static List<String> arguments(File lwjglNatives, File appNatives) {
        return Arrays.asList(
                "-Djava.library.path=${natives_directory}",
                "-Dorg.lwjgl.librarypath=" + lwjglNatives.getAbsolutePath(),
                "-Dorg.lwjgl.opengl.libname=${gl_lib_name}",
                // OpenAL lives in the APK's jni directory, not in the LWJGL assets.
                // An absolute name also bypasses desktop natives embedded in a fat JAR.
                "-Dorg.lwjgl.openal.libname=" + new File(appNatives, "libopenal.so").getAbsolutePath(),
                "-Dorg.lwjgl.system.allocator=system");
    }
}
