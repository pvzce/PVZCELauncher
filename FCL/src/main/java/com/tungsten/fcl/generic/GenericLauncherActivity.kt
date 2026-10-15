package com.tungsten.fcl.generic

import android.content.Intent
import android.os.Bundle
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.doAfterTextChanged
import com.tungsten.fcl.R
import com.tungsten.fcllibrary.browser.FileBrowser
import com.tungsten.fcllibrary.browser.options.LibMode
import com.tungsten.fcllibrary.browser.options.SelectionMode
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import com.tungsten.fcllibrary.component.view.*
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
    private val controls = mutableListOf<View>()
    private val pages = mutableListOf<View>()
    private val navIds = listOf(R.id.pvzce_nav_home, R.id.pvzce_nav_content, R.id.pvzce_nav_settings,
        R.id.pvzce_nav_advanced, R.id.pvzce_nav_logs)
    private val labelIds = listOf(R.id.pvzce_label_home, R.id.pvzce_label_content, R.id.pvzce_label_settings,
        R.id.pvzce_label_advanced, R.id.pvzce_label_logs)
    private val titles = listOf(R.string.pvzce_home, R.string.pvzce_content, R.string.pvzce_settings,
        R.string.pvzce_advanced, R.string.pvzce_logs)
    private var selectedPage = 0
    private lateinit var logText: TextView
    private var initialized = false
    private val themeRefresh = Runnable { refreshShell() }
    private val logFile get() = File(FCLPath.LOG_DIR, "latest_jar_executor.log")
    private var busy = false
        set(value) {
            field = value
            controls.forEach { it.isEnabled = !value; it.alpha = if (value) 0.5f else 1f }
        }
    private val logExporter = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null && !busy) {
            busy = true
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    require(logFile.isFile) { getString(R.string.pvzce_no_logs) }
                    val output = contentResolver.openOutputStream(uri, "wt") ?: error("无法写入目标文件")
                    output.use { out -> logFile.inputStream().use { it.copyTo(out) } }
                } }
                result.onSuccess { status.text = "日志已导出" }.onFailure { showError(it) }
                busy = false
            }
        }
    }
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
        status.text = "正在导入所选文件…"
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
                    status.text = "模组已导入，重新启动游戏后加载：\n" + paths.joinToString("\n")
                } else if (pvzceImport) {
                    fields.getValue("jar").setText(paths.single())
                    fields.getValue("classpath").setText("")
                } else if (paths.size == 1 && fields.getValue("jar").text.isEmpty()) fields.getValue("jar").setText(paths[0])
                else fields.getValue("classpath").setText((JavaLaunchSpec.lines(fields.getValue("classpath").text.toString()) + paths).joinToString("\n"))
                if (!importMods) status.text = "已导入应用存储：\n" + paths.joinToString("\n")
                save()
            }.onFailure { showError(it) }
            busy = false
        }
    }

    private val packImporter = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (busy || uri == null) return@registerForActivityResult
        val destination = File(fields.getValue("work").text.toString(), packType)
        busy = true
        status.text = "正在复制包目录…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { GameContentImporter.tree(contentResolver, uri, destination) }
            }
            result.onSuccess { status.text = "包目录已导入：$it\n请在游戏的包管理界面启用或重载。" }
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
        setContentView(R.layout.activity_pvzce_launcher)
        val prefs = getSharedPreferences("generic_launch", MODE_PRIVATE)
        val fresh = !prefs.contains("jar") && !prefs.contains("pvzce")
        val container = findViewById<FrameLayout>(R.id.pvzce_pages)
        fun page(id: Int): LinearLayout {
            val body = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(16))
            }
            val scroll = ScrollView(this).apply {
                this.id = id
                isFillViewport = true
                addView(body)
            }
            pages.add(scroll)
            container.addView(scroll, FrameLayout.LayoutParams(-1, -1))
            return body
        }
        fun text(parent: LinearLayout, label: Int): TextView = FCLTextView(this).apply {
            setUseThemeColor(true)
            setText(label)
            setPadding(0, dp(12), 0, dp(6))
            parent.addView(this)
        }
        fun field(parent: LinearLayout, key: String, id: Int, label: Int,
                  default: String = "", multiline: Boolean = false) {
            text(parent, label).labelFor = id
            fields[key] = FCLEditText(this).apply {
                this.id = id
                setSingleLine(!multiline)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    (if (multiline) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
                setText(prefs.getString(key, default))
                parent.addView(this, LinearLayout.LayoutParams(-1, -2))
                controls.add(this)
            }
        }
        fun action(parent: LinearLayout, id: Int, label: Int, block: () -> Unit): Button =
            (layoutInflater.inflate(R.layout.pvzce_action_button, parent, false) as FCLButton).apply {
                this.id = id
                minimumHeight = dp(48)
                isSingleLine = false
                setText(label)
                setOnClickListener { if (!busy) block() }
                parent.addView(this)
                controls.add(this)
            }
        fun spinner(parent: LinearLayout, id: Int, label: Int, entries: List<String>,
                    key: String, values: List<String> = entries, default: String = values.first()): Spinner {
            text(parent, label).labelFor = id
            return Spinner(this).apply {
                this.id = id
                adapter = ArrayAdapter(this@GenericLauncherActivity, android.R.layout.simple_spinner_dropdown_item, entries)
                setSelection(values.indexOf(prefs.getString(key, default)).coerceAtLeast(0))
                minimumHeight = dp(48)
                parent.addView(this, LinearLayout.LayoutParams(-1, -2))
                controls.add(this)
            }
        }
        val home = page(R.id.pvzce_page_home)
        text(home, R.string.pvzce_intro)
        field(home, "jar", R.id.pvzce_jar, R.string.pvzce_jar)
        action(home, R.id.pvzce_preset, R.string.pvzce_preset) { applyPvzcePreset() }
        status = findViewById<TextView>(R.id.pvzce_status).apply { setTextIsSelectable(true) }

        val content = page(R.id.pvzce_page_content)
        text(content, R.string.pvzce_content_hint)
        action(content, R.id.pvzce_import_mods, R.string.pvzce_import_mods) {
            if (validateContentDirectory()) { importMods = true; save(); importer.launch(arrayOf("*/*")) }
        }
        for ((kind, id, label) in listOf(
            Triple("resourcepacks", R.id.pvzce_import_resources, R.string.pvzce_import_resources),
            Triple("datapacks", R.id.pvzce_import_data, R.string.pvzce_import_data))) {
            action(content, id, label) {
                if (validateContentDirectory()) { packType = kind; save(); packImporter.launch(null) }
            }
        }
        action(content, R.id.pvzce_browse, R.string.pvzce_browse) { browseGameData() }

        val settings = page(R.id.pvzce_page_settings)
        text(settings, R.string.pvzce_runtime_hint)
        runtimes = spinner(settings, R.id.pvzce_runtime, R.string.pvzce_runtime, runtimeNames, "runtime",
            default = if (fresh) "jre25" else "jre8")
        val rendererValues = RendererManager.rendererList.map { it.id }
        renderers = spinner(settings, R.id.pvzce_renderer, R.string.pvzce_renderer,
            RendererManager.rendererList.map { it.name }, "renderer", rendererValues,
            if (fresh) RendererManager.RENDERER_VIRGL.id else rendererValues.first())
        lwjgl = spinner(settings, R.id.pvzce_lwjgl, R.string.pvzce_lwjgl, lwjglNames, "lwjgl",
            default = if (fresh) PvzceProfile.LWJGL else lwjglNames.first())
        installButton = action(settings, R.id.pvzce_install, R.string.pvzce_install) { install() }
        text(settings, R.string.pvzce_theme)
        val themeMode = Spinner(this).apply {
            id = R.id.pvzce_theme_mode
            minimumHeight = dp(48)
            adapter = ArrayAdapter(this@GenericLauncherActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.pvzce_theme_system), getString(R.string.pvzce_theme_light), getString(R.string.pvzce_theme_dark)))
            setSelection(getSharedPreferences("launcher", MODE_PRIVATE).getInt("themeMode", 0).coerceIn(0, 2))
        }
        settings.addView(themeMode)
        themeMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val themePrefs = getSharedPreferences("launcher", MODE_PRIVATE)
                if (themePrefs.getInt("themeMode", 0) == position) return
                save()
                themePrefs.edit { putInt("themeMode", position) }
                AppCompatDelegate.setDefaultNightMode(when (position) {
                    1 -> AppCompatDelegate.MODE_NIGHT_NO
                    2 -> AppCompatDelegate.MODE_NIGHT_YES
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                })
                ThemeEngine.refreshTheme()
            }
        }
        controls.add(themeMode)
        action(settings, R.id.pvzce_green, R.string.pvzce_green) {
            ThemeEngine.applyAndSave(this, 0xFF2E7D32.toInt())
            ThemeEngine.applyAndSaveDark(this, 0xFFA5D6A7.toInt())
        }

        val advanced = page(R.id.pvzce_page_advanced)
        text(advanced, R.string.pvzce_advanced_hint)
        pvzce = FCLCheckBox(this).apply {
            id = R.id.pvzce_profile
            setText(R.string.pvzce_mode)
            isChecked = prefs.getBoolean("pvzce", fresh)
            advanced.addView(this)
            controls.add(this)
        }
        field(advanced, "classpath", R.id.pvzce_classpath, R.string.pvzce_classpath, multiline = true)
        field(advanced, "main", R.id.pvzce_main, R.string.pvzce_main_class, if (fresh) PvzceProfile.MAIN_CLASS else "")
        field(advanced, "jvm", R.id.pvzce_jvm, R.string.pvzce_jvm, if (fresh) "-Xmx1024m" else "-Xmx512m", true)
        field(advanced, "args", R.id.pvzce_args, R.string.pvzce_args, multiline = true)
        field(advanced, "work", R.id.pvzce_work, R.string.pvzce_work, File(filesDir, if (fresh) "games/pvzce" else "games").absolutePath)
        action(advanced, R.id.pvzce_save, R.string.pvzce_save) { save(); status.setText(R.string.pvzce_saved) }

        val logs = page(R.id.pvzce_page_logs)
        text(logs, R.string.pvzce_log_hint)
        action(logs, R.id.pvzce_refresh_logs, R.string.pvzce_refresh_logs) { refreshLogs() }
        action(logs, R.id.pvzce_export_logs, R.string.pvzce_export_logs) {
            if (logFile.isFile) logExporter.launch("pvzce-latest.log")
            else showError(IllegalStateException(getString(R.string.pvzce_no_logs)))
        }
        logText = text(logs, R.string.pvzce_no_logs).apply {
            id = R.id.pvzce_log_text
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        launchButton = findViewById(R.id.pvzce_start)
        listOf(R.id.pvzce_start, R.id.pvzce_jar_card, R.id.pvzce_render_card).forEach { id ->
            findViewById<Button>(id).apply { minimumHeight = dp(if (id == R.id.pvzce_start) 56 else 48); isSingleLine = false }
        }
        launchButton.setOnClickListener { startJava() }
        controls.add(launchButton)
        findViewById<Button>(R.id.pvzce_jar_card).also { card ->
            card.setOnClickListener { if (!busy) { importMods = false; save(); importer.launch(arrayOf("*/*")) } }
            controls.add(card)
        }
        findViewById<Button>(R.id.pvzce_render_card).setOnClickListener { selectPage(2) }
        navIds.forEachIndexed { index, id -> findViewById<View>(id).setOnClickListener { selectPage(index) } }
        if (savedInstanceState != null) {
            pvzce.isChecked = savedInstanceState.getBoolean("pvzce")
            for (key in fields.keys) savedInstanceState.getString("field_$key")?.let { fields.getValue(key).setText(it) }
        }
        fields.getValue("jar").doAfterTextChanged { refreshSummary() }
        pvzce.setOnCheckedChangeListener { _, _ -> refreshSummary() }
        initialized = true
        if (fresh) { runCatching { PvzceProfile.prepareDirectory(File(fields.getValue("work").text.toString())) }.onFailure { showError(it) }; save() }
        selectPage(savedInstanceState?.getInt("page") ?: prefs.getInt("page", 0))
        ThemeEngine.addRefreshListener(themeRefresh)
        refreshShell()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (busy) return
                if (selectedPage != 0) selectPage(0) else { save(); finish() }
            }
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun selectPage(index: Int) {
        selectedPage = index.coerceIn(pages.indices)
        pages.forEachIndexed { i, page -> page.visibility = if (i == selectedPage) View.VISIBLE else View.GONE }
        navIds.forEachIndexed { i, id -> findViewById<FCLMenuView>(id).isSelected = i == selectedPage }
        refreshNavigation()
        findViewById<FCLDynamicIsland>(R.id.pvzce_title).refresh(getString(titles[selectedPage]))
        if (selectedPage == 4) refreshLogs()
    }

    private fun refreshShell() {
        if (!initialized) return
        val theme = ThemeEngine.getTheme()
        findViewById<View>(R.id.pvzce_background).background = theme.getBackground(this)
        findViewById<View>(R.id.pvzce_left_menu).background = GradientDrawable().apply {
            setColor(theme.getColor()); cornerRadius = dp(8).toFloat()
        }
        val surface = getColor(R.color.pvzce_surface)
        findViewById<View>(R.id.pvzce_right_menu).background = GradientDrawable().apply {
            setColor(surface); cornerRadius = dp(8).toFloat()
        }
        pages.forEach { it.background = GradientDrawable().apply { setColor(surface); cornerRadius = dp(8).toFloat() } }
        refreshNavigation()
        fields.values.forEach { it.setTextColor(theme.getColor2()); it.setHintTextColor(theme.getColor2()) }
        pvzce.setTextColor(theme.getColor2())
        refreshSummary()
    }

    private fun refreshNavigation() {
        val theme = ThemeEngine.getTheme()
        labelIds.forEachIndexed { index, id ->
            findViewById<TextView>(id).apply {
                val selected = index == selectedPage
                setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (selected) theme.getColor() else theme.autoTint)
                background = GradientDrawable().apply {
                    setColor(if (selected) theme.autoTint else android.graphics.Color.TRANSPARENT)
                    cornerRadius = dp(8).toFloat()
                }
            }
        }
    }

    private fun refreshSummary() {
        if (!initialized) return
        val jar = fields.getValue("jar").text.toString()
        findViewById<TextView>(R.id.pvzce_summary).text =
            (if (pvzce.isChecked) "PVZCE" else "通用 Java") + "\n" +
                (if (jar.isBlank()) "尚未选择游戏 JAR" else File(jar).name)
        launchButton.setText(if (pvzce.isChecked) R.string.pvzce_launch else R.string.pvzce_generic_launch)
    }

    private fun browseGameData() {
        runCatching {
            val work = File(fields.getValue("work").text.toString())
            require(work.isAbsolute && work.isDirectory) { "工作目录不存在，请先应用预设或设置有效目录" }
            save()
            fileLauncher.launch(FileBrowser.Builder(this)
                .setLibMode(LibMode.FILE_BROWSER).setSelectionMode(SelectionMode.SINGLE_SELECTION)
                .setInitDir(work.absolutePath).setExternalSelection(false).create())
        }.onFailure { showError(it) }
    }

    private fun refreshLogs() {
        if (busy) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                if (!logFile.isFile) getString(R.string.pvzce_no_logs) else {
                    java.io.RandomAccessFile(logFile, "r").use { log ->
                        log.seek((log.length() - 65536).coerceAtLeast(0))
                        val bytes = ByteArray((log.length() - log.filePointer).toInt().coerceAtMost(65536))
                        log.readFully(bytes)
                        String(bytes, Charsets.UTF_8)
                    }
                }
            } }
            result.onSuccess { logText.text = it }.onFailure { logText.text = "读取失败：${it.message}" }
        }
    }

    override fun onResume() {
        super.onResume()
        // FCLApp assigns its current Activity after onCreate; refresh dynamic dark colors now.
        ThemeEngine.refreshTheme()
    }

    override fun onPause() {
        if (initialized) save()
        super.onPause()
    }

    override fun onDestroy() {
        ThemeEngine.removeRefreshListener(themeRefresh)
        super.onDestroy()
    }

    private fun validateContentDirectory(): Boolean = runCatching {
        require(pvzce.isChecked) { "Apply the PVZCE preset before importing game content" }
        PvzceProfile.prepareDirectory(File(fields.getValue("work").text.toString()))
    }.onFailure { showError(it) }.isSuccess

    private fun applyPvzcePreset() {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.pvzce_preset)
            .setMessage(R.string.pvzce_preset_message)
            .setNegativeButton(R.string.pvzce_cancel, null).setPositiveButton(R.string.pvzce_apply) { _, _ ->
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
                    status.text = getString(R.string.pvzce_ready)
                }.onFailure { showError(it) }
            }.show()
    }

    private fun save() {
        getSharedPreferences("generic_launch", MODE_PRIVATE).edit {
            fields.forEach { (key, value) -> putString(key, value.text.toString()) }
            putBoolean("pvzce", pvzce.isChecked)
            putInt("page", selectedPage)
            putString("runtime", runtimes.selectedItem.toString())
            putString("renderer", RendererManager.rendererList[renderers.selectedItemPosition].id)
            putString("lwjgl", lwjgl.selectedItem.toString())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pvzce", pvzce.isChecked)
        outState.putBoolean("import_mods", importMods)
        outState.putString("pack_type", packType)
        outState.putInt("page", selectedPage)
        fields.forEach { (key, value) -> outState.putString("field_$key", value.text.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun showError(error: Throwable) {
        status.text = "操作失败：${error.message ?: error}"
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.pvzce_error).setMessage(status.text).setPositiveButton(R.string.pvzce_ok, null).show()
    }

    private fun install() {
        if (busy) return
        busy = true
        installButton.isEnabled = false
        launchButton.isEnabled = false
        val runtime = runtimes.selectedItem.toString()
        status.text = "正在安装 $runtime 与随附图形 / AWT 组件…"
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
            result.onSuccess { status.text = "已安装 $runtime 及随附组件，可选择游戏 JAR 后启动。" }.onFailure { showError(it) }
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
