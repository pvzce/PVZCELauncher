package com.tungsten.fclauncher;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Native settings from the verified MobileGlues 2.0.0 plugin release. */
public final class MobileGluesRuntime {
    private MobileGluesRuntime() {
    }

    public static Map<String, String> environment(File directory) {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create MobileGlues directory: " + directory);
        }
        Map<String, String> env = new LinkedHashMap<>();
        env.put("LIBGL_ES", "3");
        env.put("POJAV_RENDERER", "opengles3");
        env.put("POJAVEXEC_EGL", "libmobileglues.so");
        env.put("LIBGL_EGL", "libmobileglues.so");
        env.put("MG_COUNT_LAUNCH", "1");
        // Set before dlopen: MobileGlues reads this in its static constructor.
        env.put("MG_DIR_PATH", directory.getAbsolutePath());
        return env;
    }
}
