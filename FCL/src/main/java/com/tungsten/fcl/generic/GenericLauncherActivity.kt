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
    private lateinit var pvzce: CheckBox
    private var importMods = false
    private var packType = "resourcepacks"
    private var busy = false
    private val importer = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (busy || uris.isEmpty()) return@registerForActivityResult
        if (pvzce.isChecked && !importMods && uris.size != 1) {
            showError(IllegalArgumentException("PVZCE profile imports one shadow JAR at a time"))
            return@registerForActivityResult
        }
        val pvzceImport = pvzce.isChecked
        val dest = if (importMods) File(fields.getValue("work").text.toString(), "mods")
            else File(filesDir, "games/imported")
        busy = true
        status.text = "Importing selected files…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    uris.map { uri ->
                        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: throw IllegalArgumentException("Cannot determine filename")
                        GameContentImporter.file(contentResolver, uri, dest, name, importMods).absolutePath
                    }
                }
            }
            result.onSuccess { paths ->
                if (importMods) {
                    status.text = "Mods imported. Restart the game to load them:\n" + paths.joinToString("\n")
                } else if (pvzceImport) {
                    fields.getValue("jar").setText(paths.single())
                    fields.getValue("classpath").setText("")
                } else if (paths.size == 1 && fields.getValue("jar").text.isEmpty()) fields.getValue("jar").setText(paths[0])
                else fields.getValue("classpath").setText((JavaLaunchSpec.lines(fields.getValue("classpath").text.toString()) + paths).joinToString("\n"))
                if (!importMods) status.text = "Imported into app storage:\n" + paths.joinToString("\n")
                save()
            }.onFailure { showError(it) }
            busy = false
        }
    }

    private val packImporter = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (busy || uri == null) return@registerForActivityResult
        val destination = File(fields.getValue("work").text.toString(), packType)
        busy = true
        status.text = "Copying selected pack directory…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { GameContentImporter.tree(contentResolver, uri, destination) }
            }
            result.onSuccess { status.text = "Pack imported: $it\nEnable/reload it from the game's pack screen." }
                .onFailure { showError(it) }
            busy = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        importMods = savedInstanceState?.getBoolean("import_mods") ?: false
        packType = savedInstanceState?.getString("pack_type") ?: "resourcepacks"
        RendererManager.init(this)
        val prefs = getSharedPreferences("generic_launch", MODE_PRIVATE)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        setContentView(ScrollView(this).apply { addView(form) })
        fun text(value: String) { form.addView(TextView(this).apply { text = value }) }
        text("PVZCE Launcher\nFor PVZCE: apply the preset, import your game JAR/content, then install JRE 25 and launch. Advanced generic Java / LWJGL mode is also available.")
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
        pvzce = CheckBox(this).apply {
            text = "PVZCE profile (Java 25+, own mod loader and game directory)"
            isChecked = prefs.getBoolean("pvzce", false)
        }
        form.addView(pvzce)
        field("jar", "JAR path (optional with classpath)")
        field("classpath", "Classpath: one existing JAR/directory per line", multiline = true)
        field("main", "Main class (blank: executable JAR Main-Class)")
        field("jvm", "JVM options: one literal argument per line", "-Xmx512m", true)
        field("args", "Program arguments: one literal argument per line", multiline = true)
        field("work", "Working directory (absolute, existing, writable)", File(filesDir, "games").absolutePath)
        fun spinner(label: String, entries: List<String>, key: String, values: List<String> = entries): Spinner {
            text(label)
            return Spinner(this).apply {
                adapter = ArrayAdapter(this@GenericLauncherActivity, android.R.layout.simple_spinner_dropdown_item, entries)
                setSelection(values.indexOf(prefs.getString(key, values.first())).coerceAtLeast(0))
                form.addView(this)
            }
        }
        runtimes = spinner("Bundled Android JRE", runtimeNames, "runtime")
        renderers = spinner("Renderer (PVZCE needs OpenGL 3.2 core)", RendererManager.rendererList.map { it.name },
            "renderer", RendererManager.rendererList.map { it.id })
        lwjgl = spinner("Bundled Android LWJGL", lwjglNames, "lwjgl")
        form.addView(Button(this).apply { text = "Apply PVZCE preset"; setOnClickListener {
            if (!busy) applyPvzcePreset()
        } })
        form.addView(Button(this).apply { text = "Import JAR / dependency files"; setOnClickListener {
            if (!busy) { importMods = false; save(); importer.launch(arrayOf("*/*")) }
        } })
        form.addView(Button(this).apply { text = "Import PVZCE mods (JAR)"; setOnClickListener {
            if (!busy && validateContentDirectory()) { importMods = true; save(); importer.launch(arrayOf("*/*")) }
        } })
        for (kind in listOf("resourcepacks", "datapacks")) {
            form.addView(Button(this).apply { text = "Import PVZCE $kind directory"; setOnClickListener {
                if (!busy && validateContentDirectory()) { packType = kind; save(); packImporter.launch(null) }
            } })
        }
        installButton = Button(this).apply { text = "Install / repair selected bundled runtime"; setOnClickListener { install() } }
        form.addView(installButton)
        form.addView(Button(this).apply { text = "Save configuration"; setOnClickListener { save(); status.text = "Saved" } })
        launchButton = Button(this).apply { text = "Validate and launch"; setOnClickListener { startJava() } }
        form.addView(launchButton)
        status = TextView(this).apply { setTextIsSelectable(true); text = "Game files: ${File(filesDir, "games")}\nLogs: ${FCLPath.LOG_DIR}\nUses bundled Android LWJGL. Blank main class reads the executable JAR manifest." }
        form.addView(status)
        if (savedInstanceState != null) pvzce.isChecked = savedInstanceState.getBoolean("pvzce")
        savedInstanceState?.getStringArrayList("field_values")?.forEachIndexed { index, value -> fields.values.elementAtOrNull(index)?.setText(value) }
    }

    private fun validateContentDirectory(): Boolean = runCatching {
        require(pvzce.isChecked) { "Apply the PVZCE preset before importing game content" }
        PvzceProfile.prepareDirectory(File(fields.getValue("work").text.toString()))
    }.onFailure { showError(it) }.isSuccess

    private fun applyPvzcePreset() {
        android.app.AlertDialog.Builder(this).setTitle("Apply PVZCE preset?")
            .setMessage("Replace launch fields with PVZCE defaults? The selected JAR is kept. Existing game files are not moved or deleted.")
            .setNegativeButton("Cancel", null).setPositiveButton("Apply") { _, _ ->
                runCatching {
                    val work = PvzceProfile.prepareDirectory(File(filesDir, "games/pvzce"))
                    pvzce.isChecked = true
                    fields.getValue("classpath").setText("")
                    fields.getValue("main").setText(PvzceProfile.MAIN_CLASS)
                    fields.getValue("jvm").setText("-Xmx1024m")
                    fields.getValue("args").setText("")
                    fields.getValue("work").setText(work.absolutePath)
                    runtimes.setSelection(runtimeNames.indexOf("jre25"))
                    lwjgl.setSelection(lwjglNames.indexOf(PvzceProfile.LWJGL))
                    renderers.setSelection(RendererManager.rendererList.indexOfFirst {
                        it.id == RendererManager.RENDERER_VIRGL.id
                    }.coerceAtLeast(0))
                    save()
                    status.text = "PVZCE preset ready. Import your real shadow JAR and install jre25. Native access and --gameDir are supplied at launch. Requires OpenGL 3.2 core; VirGL is a starting choice, device compatibility is unverified."
                }.onFailure { showError(it) }
            }.show()
    }

    private fun save() {
        getSharedPreferences("generic_launch", MODE_PRIVATE).edit {
            fields.forEach { (key, value) -> putString(key, value.text.toString()) }
            putBoolean("pvzce", pvzce.isChecked)
            putString("runtime", runtimes.selectedItem.toString())
            putString("renderer", RendererManager.rendererList[renderers.selectedItemPosition].id)
            putString("lwjgl", lwjgl.selectedItem.toString())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pvzce", pvzce.isChecked)
        outState.putBoolean("import_mods", importMods)
        outState.putString("pack_type", packType)
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
        val rawSpec = JavaLaunchSpec(fields.getValue("jar").text.toString(), JavaLaunchSpec.lines(fields.getValue("classpath").text.toString()),
            fields.getValue("main").text.toString(), JavaLaunchSpec.lines(fields.getValue("jvm").text.toString()),
            JavaLaunchSpec.lines(fields.getValue("args").text.toString()), fields.getValue("work").text.toString(),
            File(FCLPath.JAVA_PATH, runtimes.selectedItem.toString()).absolutePath)
        busy = true
        launchButton.isEnabled = false
        val rendererId = RendererManager.rendererList[renderers.selectedItemPosition].id
        val lwjglVersion = lwjgl.selectedItem.toString()
        val usePvzce = pvzce.isChecked
        lifecycleScope.launch {
            // Native bridge class initialization is kept on the main thread (Choreographer).
            val validated = withContext(Dispatchers.IO) {
                runCatching {
                    if (usePvzce) PvzceProfile.configure(rawSpec, lwjglVersion)
                    else rawSpec.also { it.validate() }
                }
            }
            if (validated.isFailure) showError(validated.exceptionOrNull()!!)
            else runCatching {
                val bridge = GenericJavaLauncher.prepare(this@GenericLauncherActivity, validated.getOrThrow(), rendererId, lwjglVersion)
                GenericJvmActivity.pendingBridge = bridge
                startActivity(Intent(this@GenericLauncherActivity, GenericJvmActivity::class.java))
            }.onFailure { showError(it) }
            busy = false
            launchButton.isEnabled = true
        }
    }
}
