package com.tungsten.fcl.generic

import android.content.Intent
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.mio.manager.RendererManager
import com.tungsten.fcl.util.RuntimeUtils
import com.tungsten.fclauncher.utils.FCLPath
import com.tungsten.fcllibrary.component.FCLActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Offline launcher entry. Retained Minecraft activities are disabled in the manifest. */
class GenericLauncherActivity : FCLActivity() {
    private val fields = linkedMapOf<String, EditText>()
    private lateinit var runtimes: Spinner
    private lateinit var renderers: Spinner
    private lateinit var lwjgl: Spinner
    private lateinit var status: TextView
    private lateinit var installButton: Button
    private lateinit var launchButton: Button
    private val runtimeNames = listOf("jre8", "jre17", "jre21", "jre25")
    private val lwjglNames = listOf("3.3.3", "3.4.1")
    private var busy = false
    private val importer = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (busy || uris.isEmpty()) return@registerForActivityResult
        busy = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dest = File(filesDir, "games/imported").apply { mkdirs() }
                    uris.map { uri ->
                        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: throw IllegalArgumentException("Cannot determine filename")
                        require(name == File(name).name && name != "." && name != "..") { "Invalid filename" }
                        val file = File(dest, name)
                        require(!file.exists()) { "File already exists: $name (move or rename it first)" }
                        val temporary = File.createTempFile("import-", ".tmp", dest)
                        try {
                            val input = contentResolver.openInputStream(uri) ?: error("Cannot open $uri")
                            input.use { stream -> temporary.outputStream().use { stream.copyTo(it) } }
                            check(temporary.renameTo(file)) { "Cannot save imported file" }
                        } finally {
                            temporary.delete()
                        }
                        file.absolutePath
                    }
                }
            }
            result.onSuccess { paths ->
                if (paths.size == 1 && fields.getValue("jar").text.isEmpty()) fields.getValue("jar").setText(paths[0])
                else fields.getValue("classpath").setText((JavaLaunchSpec.lines(fields.getValue("classpath").text.toString()) + paths).joinToString("\n"))
                status.text = "Imported into app storage:\n" + paths.joinToString("\n")
                save()
            }.onFailure { showError(it) }
            busy = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        RendererManager.init(this)
        val prefs = getSharedPreferences("generic_launch", MODE_PRIVATE)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        setContentView(ScrollView(this).apply { addView(form) })
        fun text(value: String) { form.addView(TextView(this).apply { text = value }) }
        text("PVZCE Launcher — Java / LWJGL\nNo Minecraft account, downloads or version JSON. Supply your own legally obtained files.")
        fun field(key: String, label: String, default: String = "", multiline: Boolean = false) {
            text(label)
            val editor = EditText(this).apply {
                id = android.view.View.generateViewId()
                setSingleLine(!multiline)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    (if (multiline) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                setText(prefs.getString(key, default))
            }
            fields[key] = editor
            form.addView(editor)
        }
        field("jar", "JAR path (optional with classpath)")
        field("classpath", "Classpath: one existing JAR/directory per line", multiline = true)
        field("main", "Main class (blank: executable JAR Main-Class)")
        field("jvm", "JVM options: one literal argument per line", "-Xmx512m", true)
        field("args", "Program arguments: one literal argument per line", multiline = true)
        field("work", "Working directory (absolute, existing, writable)", File(filesDir, "games").absolutePath)
        fun spinner(label: String, entries: List<String>, key: String): Spinner {
            text(label)
            return Spinner(this).apply {
                adapter = ArrayAdapter(this@GenericLauncherActivity, android.R.layout.simple_spinner_dropdown_item, entries)
                setSelection(entries.indexOf(prefs.getString(key, entries.first())).coerceAtLeast(0))
                form.addView(this)
            }
        }
        runtimes = spinner("Bundled Android JRE", runtimeNames, "runtime")
        renderers = spinner("Renderer", RendererManager.rendererList.map { it.id }, "renderer")
        lwjgl = spinner("Bundled Android LWJGL", lwjglNames, "lwjgl")
        form.addView(Button(this).apply { text = "Import JAR / dependency files"; setOnClickListener {
            if (!busy) importer.launch(arrayOf("*/*"))
        } })
        installButton = Button(this).apply { text = "Install / repair selected bundled runtime"; setOnClickListener { install() } }
        form.addView(installButton)
        form.addView(Button(this).apply { text = "Save configuration"; setOnClickListener { save(); status.text = "Saved" } })
        launchButton = Button(this).apply { text = "Validate and launch"; setOnClickListener { startJava() } }
        form.addView(launchButton)
        status = TextView(this).apply { setTextIsSelectable(true); text = "Game files: ${File(filesDir, "games")}\nLogs: ${FCLPath.LOG_DIR}\nUses bundled Android LWJGL. Blank main class reads the executable JAR manifest." }
        form.addView(status)
        savedInstanceState?.getStringArrayList("field_values")?.forEachIndexed { index, value -> fields.values.elementAtOrNull(index)?.setText(value) }
    }

    private fun save() {
        getSharedPreferences("generic_launch", MODE_PRIVATE).edit {
            fields.forEach { (key, value) -> putString(key, value.text.toString()) }
            putString("runtime", runtimes.selectedItem.toString())
            putString("renderer", renderers.selectedItem.toString())
            putString("lwjgl", lwjgl.selectedItem.toString())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("field_values", ArrayList(fields.values.map { it.text.toString() }))
        super.onSaveInstanceState(outState)
    }

    private fun showError(error: Throwable) {
        status.text = "Error: ${error.message ?: error}"
        android.app.AlertDialog.Builder(this).setTitle("PVZCE Launcher").setMessage(status.text).setPositiveButton("OK", null).show()
    }

    private fun install() {
        if (busy) return
        busy = true
        installButton.isEnabled = false
        launchButton.isEnabled = false
        val runtime = runtimes.selectedItem.toString()
        status.text = "Installing $runtime and bundled graphics/AWT support…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    RuntimeUtils.installJava(this@GenericLauncherActivity, File(FCLPath.JAVA_PATH, runtime).absolutePath, "app_runtime/java/$runtime")
                    RuntimeUtils.install(this@GenericLauncherActivity, FCLPath.LWJGL_DIR, "app_runtime/lwjgl")
                    RuntimeUtils.install(this@GenericLauncherActivity, FCLPath.CACIOCAVALLO_8_DIR, "app_runtime/caciocavallo")
                    RuntimeUtils.install(this@GenericLauncherActivity, FCLPath.CACIOCAVALLO_17_DIR, "app_runtime/caciocavallo17")
                    RuntimeUtils.installJna(this@GenericLauncherActivity, FCLPath.JNA_PATH, "app_runtime/jna")
                }
            }
            result.onSuccess { status.text = "Installed $runtime. Configure your JAR/classpath, then launch." }.onFailure { showError(it) }
            busy = false
            installButton.isEnabled = true
            launchButton.isEnabled = true
        }
    }

    private fun startJava() {
        if (busy) return
        save()
        val spec = JavaLaunchSpec(fields.getValue("jar").text.toString(), JavaLaunchSpec.lines(fields.getValue("classpath").text.toString()),
            fields.getValue("main").text.toString(), JavaLaunchSpec.lines(fields.getValue("jvm").text.toString()),
            JavaLaunchSpec.lines(fields.getValue("args").text.toString()), fields.getValue("work").text.toString(),
            File(FCLPath.JAVA_PATH, runtimes.selectedItem.toString()).absolutePath)
        busy = true
        launchButton.isEnabled = false
        val rendererId = renderers.selectedItem.toString()
        val lwjglVersion = lwjgl.selectedItem.toString()
        lifecycleScope.launch {
            // Native bridge class initialization is kept on the main thread (Choreographer).
            val error = withContext(Dispatchers.IO) { runCatching { spec.validate() }.exceptionOrNull() }
            if (error != null) showError(error)
            else runCatching {
                val bridge = GenericJavaLauncher.prepare(this@GenericLauncherActivity, spec, rendererId, lwjglVersion)
                GenericJvmActivity.pendingBridge = bridge
                startActivity(Intent(this@GenericLauncherActivity, GenericJvmActivity::class.java))
            }.onFailure { showError(it) }
            busy = false
            launchButton.isEnabled = true
        }
    }
}
