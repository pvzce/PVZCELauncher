package com.tungsten.fcl.generic;

import com.tungsten.fclauncher.MobileGluesRuntime;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Verify the native launch contract without loading an Android graphics driver. */
public class MobileGluesRuntimeTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void createsPrivateDirectoryAndUsesMobileGluesForBothGlAndEgl() throws Exception {
        File directory = new File(temporary.getRoot(), "private path/mobileglues");
        Map<String, String> env = MobileGluesRuntime.environment(directory);
        assertTrue(directory.isDirectory());
        assertEquals(directory.getAbsolutePath(), env.get("MG_DIR_PATH"));
        assertEquals("opengles3", env.get("POJAV_RENDERER"));
        assertEquals("libmobileglues.so", env.get("POJAVEXEC_EGL"));
        assertEquals("libmobileglues.so", env.get("LIBGL_EGL"));
        assertEquals("3", env.get("LIBGL_ES"));
        assertEquals(env, MobileGluesRuntime.environment(directory));
        assertFalse(env.containsKey("MESA_GL_VERSION_OVERRIDE"));
        assertFalse(env.containsKey("LIBGL_GL"));
    }

    @Test
    public void failsIfSettingsDirectoryCannotBeCreated() throws Exception {
        File file = temporary.newFile("occupied");
        try {
            MobileGluesRuntime.environment(new File(file, "mobileglues"));
            fail("Do not continue with the /sdcard/MG fallback");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("MobileGlues directory"));
        }
    }
}
