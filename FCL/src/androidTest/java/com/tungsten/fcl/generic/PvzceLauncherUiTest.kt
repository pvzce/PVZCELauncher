package com.tungsten.fcl.generic

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.widget.*
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tungsten.fcl.BuildConfig
import com.tungsten.fcl.R
import com.tungsten.fclauncher.utils.FCLPath
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import com.tungsten.fcllibrary.component.view.FCLEditText
import com.tungsten.fcllibrary.component.view.FCLMenuView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/** Real Android Activity tests. No game JAR, GPU/game compatibility claim or network service. */
@RunWith(AndroidJUnit4::class)
class PvzceLauncherUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before fun resetLaunchPreferences() {
        check(context.packageName.endsWith(".debug")) { "Instrumentation must use the isolated fordebug package" }
        File(context.filesDir, "launcher_instances").deleteRecursively()
        context.getSharedPreferences("pvzce_pending_transfer", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("generic_launch", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("launcher", Context.MODE_PRIVATE).edit().putInt("themeMode", 1).commit()
    }

    private fun idle() = instrumentation.waitForIdleSync()

    private fun screenshot(name: String) {
        idle()
        // Let FCL's title/selection animation finish before taking a device screenshot.
        SystemClock.sleep(600)
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Device screenshot unavailable" }
        // AGP collects this directory before uninstalling APKs; app external files can disappear.
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val root = output?.let { File(it) } ?: context.getExternalFilesDir(null)
        val directory = File(root, "ui-smoke").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }

    @Test fun fclNavigationDefaultsAndBothThemes() {
        ActivityScenario.launch(GenericLauncherActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(BuildConfig.MINECRAFT_FEATURES)
                assertTrue(activity.findViewById<View>(R.id.pvzce_nav_home) is FCLMenuView)
                assertTrue(activity.findViewById<View>(R.id.pvzce_jar) is FCLEditText)
                assertTrue(activity.findViewById<CheckBox>(R.id.pvzce_profile).isChecked)
                assertEquals(PvzceProfile.MAIN_CLASS, activity.findViewById<EditText>(R.id.pvzce_main).text.toString())
                assertEquals("jre25", activity.findViewById<Spinner>(R.id.pvzce_runtime).selectedItem)
                assertEquals("3.4.1", activity.findViewById<Spinner>(R.id.pvzce_lwjgl).selectedItem)
                assertNull(activity.findViewById<View>(R.id.account))
                assertNull(activity.findViewById<View>(R.id.download_panel))
                activity.findViewById<View>(R.id.pvzce_green).performClick()
            }
            screenshot("home-light")
            val navigation = listOf(R.id.pvzce_nav_content to R.id.pvzce_page_content,
                R.id.pvzce_nav_settings to R.id.pvzce_page_settings,
                R.id.pvzce_nav_advanced to R.id.pvzce_page_advanced,
                R.id.pvzce_nav_logs to R.id.pvzce_page_logs)
            navigation.forEach { (button, page) ->
                scenario.onActivity { activity ->
                    activity.findViewById<View>(button).performClick()
                    assertEquals(View.VISIBLE, activity.findViewById<View>(page).visibility)
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.pvzce_page_home).visibility)
                }
                if (page == R.id.pvzce_page_content) screenshot("content-light")
                if (page == R.id.pvzce_page_advanced) screenshot("advanced-light")
            }
            scenario.onActivity { activity -> activity.findViewById<View>(R.id.pvzce_render_card).performClick() }
            screenshot("settings-light")
            scenario.onActivity { activity ->
                assertTrue(ColorUtils.calculateContrast(Color.WHITE, ThemeEngine.getTheme().color) >= 4.5)
                activity.findViewById<Spinner>(R.id.pvzce_theme_mode).setSelection(2)
            }
            idle()
            scenario.onActivity { activity ->
                assertTrue(ThemeEngine.isNightMode(activity))
                assertTrue(ColorUtils.calculateContrast(Color.BLACK, ThemeEngine.getTheme().colorDark) >= 4.5)
                activity.findViewById<View>(R.id.pvzce_nav_home).performClick()
            }
            screenshot("home-dark")
        }
    }

    @Test fun advancedConfigurationAndNavigationSurviveRecreation() {
        ActivityScenario.launch(GenericLauncherActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.pvzce_nav_advanced).performClick()
                activity.findViewById<CheckBox>(R.id.pvzce_profile).isChecked = false
                activity.findViewById<EditText>(R.id.pvzce_main).setText("example.UserProvidedMain")
                activity.findViewById<EditText>(R.id.pvzce_args).setText("中文参数\n--example")
                activity.findViewById<EditText>(R.id.pvzce_classpath).setText("/example/user.jar")
                activity.findViewById<Spinner>(R.id.pvzce_runtime).setSelection(1)
                activity.findViewById<View>(R.id.pvzce_save).performClick()
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.pvzce_page_advanced).visibility)
                assertFalse(activity.findViewById<CheckBox>(R.id.pvzce_profile).isChecked)
                assertEquals("example.UserProvidedMain", activity.findViewById<EditText>(R.id.pvzce_main).text.toString())
                assertEquals("中文参数\n--example", activity.findViewById<EditText>(R.id.pvzce_args).text.toString())
                assertEquals("/example/user.jar", activity.findViewById<EditText>(R.id.pvzce_classpath).text.toString())
                assertEquals("jre17", activity.findViewById<Spinner>(R.id.pvzce_runtime).selectedItem)
                assertEquals("中文参数\n--example", GameInstanceStore(activity.filesDir).selected().config["args"])
            }
        }
    }

    @Test fun contentButtonsOpenRealPickersAndFclBrowser() {
        val intents = Collections.synchronizedList(mutableListOf<Intent>())
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                intents.add(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        ActivityScenario.launch(GenericLauncherActivity::class.java).use { scenario ->
            instrumentation.addMonitor(monitor)
            try {
                scenario.onActivity { it.findViewById<View>(R.id.pvzce_jar_card).performClick() }
                idle()
                scenario.onActivity { it.findViewById<View>(R.id.pvzce_nav_content).performClick() }
                for (index in 0..2) {
                    scenario.onActivity { activity ->
                        val tabs = activity.findViewById<com.google.android.material.tabs.TabLayout>(R.id.pvzce_content_tabs)
                        tabs.selectTab(tabs.getTabAt(index))
                    }
                    waitReady(scenario)
                    scenario.onActivity { activity ->
                        val content = activity.findViewById<View>(R.id.pvzce_page_content)
                        assertTrue(content.findViewById<View>(R.id.add).isShown)
                        content.findViewById<View>(R.id.add).performClick()
                    }
                    idle()
                }
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.pvzce_nav_advanced).performClick()
                    activity.findViewById<View>(R.id.pvzce_browse).performClick()
                }
                idle()
                assertEquals(5, intents.size)
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, intents[0].action)
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, intents[1].action)
                assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intents[2].action)
                assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, intents[3].action)
                assertEquals("com.tungsten.fcllibrary.browser.FileBrowserActivity", intents[4].component?.className)
                assertNotNull(intents[4].getSerializableExtra("config"))
            } finally { instrumentation.removeMonitor(monitor) }
            // Open the retained FCL component for real as well as checking its launch intent.
            val browserMonitor = instrumentation.addMonitor("com.tungsten.fcllibrary.browser.FileBrowserActivity", null, false)
            try {
                scenario.onActivity { activity -> activity.findViewById<View>(R.id.pvzce_browse).performClick() }
                val browser = requireNotNull(instrumentation.waitForMonitorWithTimeout(browserMonitor, 10000)) {
                    "FCL file browser must actually open"
                }
                idle()
                instrumentation.runOnMainSync {
                    assertTrue(browser.findViewById<TextView>(R.id.current_folder).text.toString().contains("games/pvzce"))
                    assertEquals(View.GONE, browser.findViewById<View>(R.id.shared_dir).visibility)
                    assertEquals(View.GONE, browser.findViewById<View>(R.id.confirm).visibility)
                    assertEquals(browser.getString(R.string.pvzce_game_root), browser.findViewById<TextView>(R.id.private_dir).text.toString())
                    // Back goes to games/, and the repurposed shortcut returns to the configured game root.
                    browser.findViewById<View>(R.id.back).performClick()
                    assertFalse(browser.findViewById<TextView>(R.id.current_folder).text.toString().endsWith("games/pvzce"))
                    browser.findViewById<View>(R.id.private_dir).performClick()
                    assertTrue(browser.findViewById<TextView>(R.id.current_folder).text.toString().endsWith("games/pvzce"))
                }
                screenshot("game-data-browser")
                instrumentation.runOnMainSync { browser.finish() }
            } finally { instrumentation.removeMonitor(browserMonitor) }
        }
    }

    private fun waitReady(scenario: ActivityScenario<GenericLauncherActivity>) {
        var ready = false
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50)
            scenario.onActivity { ready = it.findViewById<View>(R.id.pvzce_start).isEnabled }
        }
        assertTrue("Instance content operation must finish", ready)
        idle()
        SystemClock.sleep(100)
    }

    @Test fun independentInstancesAndVisualModManagementSurviveRecreation() {
        val firstRoot = File(context.cacheDir, "instance-ui-first").apply { mkdirs() }
        val store = GameInstanceStore(context.filesDir)
        val first = store.initialize(mapOf("work" to firstRoot.absolutePath, "jar" to "first.jar",
            "pvzce" to "true", "runtime" to "jre25", "lwjgl" to "3.4.1", "main" to PvzceProfile.MAIN_CLASS,
            "jvm" to "-Xmx512m", "args" to "", "classpath" to ""))
        val mods = File(firstRoot, "mods").apply { mkdirs() }
        java.util.jar.JarOutputStream(File(mods, "fixture.jar").outputStream()).use {
            it.putNextEntry(java.util.jar.JarEntry("fabric.mod.json"))
            it.write("{\"schemaVersion\":1,\"id\":\"fixture\",\"name\":\"界面测试模组\",\"version\":\"1.0\"}".toByteArray())
            it.closeEntry()
        }
        val second = store.create("第二实例", first.config)
        store.update(second.id, second.config.toMutableMap().apply { put("jar", "second.jar") })
        store.select(first.id)
        try {
            ActivityScenario.launch(GenericLauncherActivity::class.java).use { scenario ->
                scenario.onActivity { it.findViewById<Spinner>(R.id.pvzce_instance).setSelection(1) }
                idle()
                scenario.onActivity {
                    assertEquals("second.jar", it.findViewById<EditText>(R.id.pvzce_jar).text.toString())
                    assertEquals(second.directory().path, it.findViewById<EditText>(R.id.pvzce_work).text.toString())
                    it.findViewById<View>(R.id.pvzce_nav_content).performClick()
                }
                waitReady(scenario)
                scenario.onActivity {
                    assertEquals(0, it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list).adapter!!.itemCount)
                    it.findViewById<View>(R.id.pvzce_nav_home).performClick()
                    it.findViewById<Spinner>(R.id.pvzce_instance).setSelection(0)
                }
                idle()
                scenario.onActivity { it.findViewById<View>(R.id.pvzce_nav_content).performClick() }
                waitReady(scenario)
                scenario.recreate()
                waitReady(scenario)
                scenario.onActivity {
                    val list = it.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.list)
                    assertEquals(1, list.adapter!!.itemCount)
                    assertEquals(first.id, GameInstanceStore(it.filesDir).selected().id)
                    val row = requireNotNull(list.findViewHolderForAdapterPosition(0))
                    row.itemView.findViewById<CheckBox>(R.id.check).isChecked = false
                }
                waitReady(scenario)
                assertTrue(File(mods, "fixture.jar.disabled").isFile)
                assertFalse(File(second.directory(), "mods/fixture.jar.disabled").exists())
                screenshot("instance-mod-manager")
                scenario.onActivity { it.findViewById<View>(R.id.pvzce_nav_home).performClick() }
                screenshot("instance-selection")
            }
        } finally {
            firstRoot.deleteRecursively()
            second.directory().deleteRecursively()
        }
    }

    @Test fun operationLockSurvivesActivityRecreation() {
        ActivityScenario.launch(GenericLauncherActivity::class.java).use { scenario ->
            val token = Any()
            assertTrue(GameOperationGate.begin(token))
            try {
                idle()
                val store = GameInstanceStore(context.filesDir)
                val instance = store.selected()
                store.update(instance.id, instance.config.toMutableMap().apply { put("jar", "io-completed.jar") })
                scenario.recreate()
                scenario.onActivity {
                    assertFalse(it.findViewById<View>(R.id.pvzce_start).isEnabled)
                    assertFalse(it.findViewById<View>(R.id.pvzce_instance).isEnabled)
                }
            } finally { GameOperationGate.end(token) }
            waitReady(scenario)
            scenario.onActivity {
                assertTrue(it.findViewById<View>(R.id.pvzce_instance).isEnabled)
                assertEquals("io-completed.jar", it.findViewById<EditText>(R.id.pvzce_jar).text.toString())
            }
        }
    }

    @Test fun runtimeInstallValidationAndRealLocalLog() {
        ActivityScenario.launch(GenericLauncherActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<View>(R.id.pvzce_render_card).performClick()
                activity.findViewById<View>(R.id.pvzce_install).performClick()
                assertTrue(activity.findViewById<View>(R.id.pvzce_status).isShown)
                assertFalse(activity.findViewById<View>(R.id.pvzce_start).isEnabled)
                assertFalse(activity.findViewById<View>(R.id.pvzce_jar_card).isEnabled)
            }
            var finished = false
            val deadline = SystemClock.elapsedRealtime() + 600000
            while (!finished && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(250)
                scenario.onActivity { activity -> finished = activity.findViewById<View>(R.id.pvzce_start).isEnabled }
            }
            assertTrue("Bundled JRE install must finish", finished)
            assertTrue(File(FCLPath.JAVA_PATH, "jre25/release").isFile)
            assertTrue(File(FCLPath.JAVA_PATH, "jre25/bin/java").isFile)
            scenario.onActivity { activity ->
                assertTrue("All bundled components must install successfully",
                    activity.findViewById<TextView>(R.id.pvzce_status).text.startsWith("已安装 jre25"))
                activity.findViewById<View>(R.id.pvzce_start).performClick()
            }
            var failedCorrectly = false
            val errorDeadline = SystemClock.elapsedRealtime() + 15000
            while (!failedCorrectly && SystemClock.elapsedRealtime() < errorDeadline) {
                SystemClock.sleep(100)
                scenario.onActivity { activity ->
                    failedCorrectly = activity.findViewById<TextView>(R.id.pvzce_status).text.startsWith("操作失败：")
                }
            }
            assertTrue("Missing game JAR must produce an error", failedCorrectly)
            scenario.onActivity { activity ->
                assertTrue(activity.findViewById<TextView>(R.id.pvzce_status).text.contains("Supply a JAR or classpath"))
            }
            screenshot("missing-jar-error")
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            idle()
        }
        val log = File(FCLPath.LOG_DIR, "latest_jar_executor.log")
        log.parentFile!!.mkdirs()
        log.writeText("UI test log fixture / 中文日志\n")
        try {
            ActivityScenario.launch(GenericLauncherActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.findViewById<View>(R.id.pvzce_nav_logs).performClick() }
                var loaded = false
                val deadline = SystemClock.elapsedRealtime() + 10000
                while (!loaded && SystemClock.elapsedRealtime() < deadline) {
                    SystemClock.sleep(100)
                    scenario.onActivity { activity -> loaded = activity.findViewById<TextView>(R.id.pvzce_log_text).text.contains("中文日志") }
                }
                assertTrue("Log page must read actual file", loaded)
                screenshot("logs-light")
                val exports = mutableListOf<Intent>()
                val exportMonitor = object : Instrumentation.ActivityMonitor() {
                    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                        exports.add(Intent(intent))
                        return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                    }
                }
                instrumentation.addMonitor(exportMonitor)
                try {
                    scenario.onActivity { activity -> activity.findViewById<View>(R.id.pvzce_export_logs).performClick() }
                    idle()
                    assertEquals(Intent.ACTION_CREATE_DOCUMENT, exports.single().action)
                    assertEquals("text/plain", exports.single().type)
                } finally { instrumentation.removeMonitor(exportMonitor) }
            }
        } finally { log.delete() }
    }
}
