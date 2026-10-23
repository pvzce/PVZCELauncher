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
import com.tungsten.fcllibrary.component.dialog.EditDialog
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** PVZCE launcher entry. Retained Minecraft activities are disabled in the manifest. */
class GenericLauncherActivity : FCLActivity() {
    private val fields = linkedMapOf<String, EditText>()
    private lateinit var runtimes: Spinner
    private lateinit var renderers: Spinner
    private lateinit var lwjgl: Spinner
    private lateinit var status: TextView
    private lateinit var installButton: Button
    private lateinit var launchButton: Button
    private val runtimeNames = listOf("jre25")
    private val lwjglNames = listOf("3.3.3", "3.4.1")
    private lateinit var pvzce: CheckBox
    private lateinit var instanceStore: GameInstanceStore
    private var instanceId = ""
    private var instanceName = ""
    private lateinit var instances: Spinner
    private var instanceIds = emptyList<String>()
    private var contentPanel: PvzceContentPanel? = null
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
    private val operationOwner = Any()
    private var refreshAfterOperation = false
    private val operationListener = Runnable { runOnUiThread {
        if (!isDestroyed && initialized) {
            if (GameOperationGate.isBusy() && !GameOperationGate.isOwnedBy(operationOwner)) refreshAfterOperation = true
            renderBusy(GameOperationGate.isBusy())
            if (!GameOperationGate.isBusy() && refreshAfterOperation) {
                refreshAfterOperation = false
                runCatching { loadInstance(instanceStore.selected().id, false); resumeContent() }
                    .onFailure { showError(it) }
            }
        }
    } }
    private var busy: Boolean
        get() = GameOperationGate.isBusy()
        set(value) {
            if (value) check(GameOperationGate.begin(operationOwner)) { "上次实例操作尚未完成" }
            else GameOperationGate.end(operationOwner)
            renderBusy(GameOperationGate.isBusy())
        }

    private fun renderBusy(value: Boolean) {
        controls.forEach { it.isEnabled = !value; it.alpha = if (value) 0.5f else 1f }
        contentPanel?.setBusy(value)
    }
    private val logExporter = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null && !busy) {
            io({
                    require(logFile.isFile) { getString(R.string.pvzce_no_logs) }
                    val output = contentResolver.openOutputStream(uri, "wt") ?: error("无法写入目标文件")
                    output.use { out -> logFile.inputStream().use { it.copyTo(out) } }
            }) { status.text = "日志已导出" }
        }
    }
    private data class Transfer(val instance: String, val root: File, val kind: String,
                                val names: List<String>, val pvzce: Boolean)
    private val transfers get() = getSharedPreferences("pvzce_pending_transfer", MODE_PRIVATE)

    private fun request(mode: String, kind: String, names: List<String> = emptyList()) {
        check(transfers.edit().clear().putString("mode", mode).putString("instance", instanceId)
            .putString("root", instanceStore.get(instanceId).directory().absolutePath).putString("kind", kind)
            .putStringSet("names", names.toSet()).putBoolean("pvzce", pvzce.isChecked).commit()) { "无法保存导入目标" }
    }

    private fun consume(mode: String): Transfer? {
        if (transfers.getString("mode", "") != mode) return null
        val result = Transfer(transfers.getString("instance", "")!!, File(transfers.getString("root", "")!!),
            transfers.getString("kind", "")!!, transfers.getStringSet("names", emptySet())!!.toList(),
            transfers.getBoolean("pvzce", true))
        transfers.edit().clear().commit()
        return result
    }

    private fun target(request: Transfer): PvzceContentStore {
        val registered = instanceStore.get(request.instance)
        check(!registered.archived && registered.directory().canonicalFile == request.root.absoluteFile) {
            "导入目标实例或目录已改变，请重新选择文件"
        }
        return PvzceContentStore(request.root)
    }

    private fun <T> io(action: () -> T, success: (T) -> Unit) {
        if (busy) { showError(IllegalStateException("操作尚未完成，请重试")); return }
        busy = true
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { runCatching(action) }
                result.fold(onSuccess = { runCatching { success(it) }.onFailure { error -> showError(error) } },
                    onFailure = { showError(it) })
            } finally {
                busy = false
                if (!isDestroyed && !isFinishing && selectedPage == 1) contentPanel?.reload()
            }
        }
    }

    private val importer = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val transfer = consume("files") ?: return@registerForActivityResult
        if (uris.isEmpty()) { resumeContent(); return@registerForActivityResult }
        if (transfer.kind == "JAR" && transfer.pvzce && uris.size != 1) {
            showError(IllegalArgumentException("PVZCE 每次选择一个游戏 JAR")); return@registerForActivityResult
        }
        io({
            val root = target(transfer)
            val mods = transfer.kind == "MODS"
            val destination = if (mods) root.directory(PvzceContentStore.Kind.MODS)
                else File(filesDir, "instance_jars/" + transfer.instance)
            val paths = uris.map { uri ->
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: error("无法读取文件名")
                GameContentImporter.file(contentResolver, uri, destination, name, mods).absolutePath
            }
            if (transfer.kind == "JAR") {
                val config = instanceStore.get(transfer.instance).config.toMutableMap()
                if (transfer.pvzce) { config["jar"] = paths.single(); config["classpath"] = "" }
                else if (paths.size == 1 && config["jar"].isNullOrEmpty()) config["jar"] = paths[0]
                else config["classpath"] = (JavaLaunchSpec.lines(config["classpath"] ?: "") + paths).joinToString("\n")
                instanceStore.update(transfer.instance, config)
            }
            paths
        }) { paths ->
            if (transfer.kind == "JAR" && transfer.instance == instanceId) loadInstance(instanceId, false)
            status.text = "已导入目标实例：\n" + paths.joinToString("\n")
        }
    }

    private val packImporter = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val transfer = consume("tree") ?: return@registerForActivityResult
        if (uri == null) { resumeContent(); return@registerForActivityResult }
        io({
            val kind = PvzceContentStore.Kind.valueOf(transfer.kind)
            GameContentImporter.tree(contentResolver, uri, target(transfer).directory(kind), kind)
        }) { status.text = "目录已导入：$it\n重新启动游戏后生效。" }
    }

    private val archiveImporter = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val transfer = consume("zip") ?: return@registerForActivityResult
        if (uri == null) { resumeContent(); return@registerForActivityResult }
        io({
            contentResolver.openInputStream(uri)?.use {
                target(transfer).importZip(PvzceContentStore.Kind.valueOf(transfer.kind), it)
            } ?: error("无法读取备份")
        }) { status.text = "备份已导入目标实例，原有同名内容未覆盖。" }
    }

    private val contentExporter = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val transfer = consume("export") ?: return@registerForActivityResult
        if (uri == null) { resumeContent(); return@registerForActivityResult }
        io({
            val temporary = File.createTempFile("pvzce-backup-", ".zip", cacheDir)
            try {
                temporary.outputStream().use { target(transfer).exportZip(PvzceContentStore.Kind.valueOf(transfer.kind), transfer.names, it) }
                contentResolver.openOutputStream(uri, "wt")?.use { output -> temporary.inputStream().use { it.copyTo(output) } }
                    ?: error("无法写入备份")
            } finally { temporary.delete() }
        }) { status.text = "备份已导出，包含所选目录的完整文件。" }
    }

    private fun importJar() {
        if (busy || !save()) return
        runCatching { request("files", "JAR"); importer.launch(arrayOf("*/*")) }
            .onFailure { transfers.edit().clear().commit(); showError(it) }
    }

    private fun resumeContent() {
        if (initialized && selectedPage == 1) contentPanel?.reload()
    }

    private fun importContent(kind: PvzceContentStore.Kind, archive: Boolean) {
        if (busy || !validateContentDirectory()) return
        runCatching {
            val mode = if (archive) "zip" else if (kind == PvzceContentStore.Kind.MODS) "files" else "tree"
            request(mode, kind.name)
            when (mode) {
                "zip" -> archiveImporter.launch(arrayOf("application/zip", "application/octet-stream"))
                "files" -> importer.launch(arrayOf("*/*"))
                else -> packImporter.launch(null)
            }
        }.onFailure { transfers.edit().clear().commit(); showError(it) }
    }

    private fun exportContent(kind: PvzceContentStore.Kind, names: List<String>) {
        if (busy || !save()) return
        runCatching {
            request("export", kind.name, names)
            contentExporter.launch("pvzce-${kind.directory}-backup.zip")
        }.onFailure { transfers.edit().clear().commit(); showError(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        RendererManager.init(this)
        setContentView(R.layout.activity_pvzce_launcher)
        val legacy = getSharedPreferences("generic_launch", MODE_PRIVATE)
        val wasFresh = !legacy.contains("jar") && !legacy.contains("pvzce")
        val first = runCatching {
            instanceStore = GameInstanceStore(filesDir)
            val config = defaultConfig(wasFresh)
            legacy.all.forEach { (key, value) -> if (key in config) config[key] = value.toString() }
            instanceStore.initialize(config)
        }.getOrElse {
            FCLAlertDialog.Builder(this).setMessage("实例配置读取失败，原数据保留：${it.message}")
                .setPositiveButton("关闭") { finish() }.create().show()
            return
        }
        instanceId = first.id
        instanceName = first.name
        val prefs = first.config
        val fresh = prefs["pvzce"]?.toBoolean() ?: false
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
                setText(prefs[key] ?: default)
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
                setSelection(values.indexOf(prefs[key] ?: default).coerceAtLeast(0))
                minimumHeight = dp(48)
                parent.addView(this, LinearLayout.LayoutParams(-1, -2))
                controls.add(this)
            }
        }
        val home = page(R.id.pvzce_page_home)
        action(home, R.id.pvzce_open_workshop, R.string.pvzce_workshop) {
            if (save()) startActivity(Intent(this, PvzceWorkshopActivity::class.java).putExtra("instance", instanceId))
        }
        action(home, R.id.pvzce_open_account, R.string.pvzce_account) {
            if (save()) startActivity(Intent(this, PvzceWorkshopActivity::class.java).putExtra("account", true).putExtra("instance", instanceId))
        }
        text(home, R.string.pvzce_instance)
        instances = Spinner(this).apply {
            id = R.id.pvzce_instance
            minimumHeight = dp(48)
        }
        home.addView(instances)
        controls.add(instances)
        action(home, R.id.pvzce_instance_create, R.string.pvzce_instance_create) { editInstanceName(false) }
        action(home, R.id.pvzce_instance_copy, R.string.pvzce_instance_copy) { editInstanceName(true) }
        action(home, R.id.pvzce_instance_rename, R.string.pvzce_instance_rename) {
            EditDialog(this, instanceStore.get(instanceId).name) { name ->
                runCatching { instanceStore.rename(instanceId, name); refreshInstances(); refreshSummary() }.onFailure { showError(it) }
            }.apply { setTitle("重命名实例") }.show()
        }
        action(home, R.id.pvzce_instance_archive, R.string.pvzce_instance_archive) {
            FCLAlertDialog.Builder(this).setTitle("归档当前实例")
                .setMessage("归档后可恢复，游戏 JAR、模组、包和存档都保留原处。")
                .setPositiveButton("归档") {
                    if (save()) runCatching {
                        instanceStore.archive(instanceId, true)
                        loadInstance(instanceStore.selected().id)
                    }.onFailure { showError(it) }
                }.setNegativeButton("取消", null).create().show()
        }
        action(home, R.id.pvzce_instance_restore, R.string.pvzce_instance_restore) { restoreInstances() }
        text(home, R.string.pvzce_intro)
        field(home, "jar", R.id.pvzce_jar, R.string.pvzce_jar)
        action(home, R.id.pvzce_preset, R.string.pvzce_preset) { applyPvzcePreset() }
        status = findViewById<TextView>(R.id.pvzce_status).apply { setTextIsSelectable(true) }

        contentPanel = PvzceContentPanel(this, {
            check(pvzce.isChecked) { "请启用 PVZCE 预设后管理游戏内容" }
            check(save(false)) { "请先修正实例配置：${status.text}" }
            PvzceContentStore(instanceStore.get(instanceId).directory())
        }, { busy = it }, { status.text = it }, { showError(it) }, ::importContent, ::exportContent)
        val content = contentPanel!!.view.apply { id = R.id.pvzce_page_content }
        pages.add(content)
        container.addView(content, FrameLayout.LayoutParams(-1, -1))

        val settings = page(R.id.pvzce_page_settings)
        text(settings, R.string.pvzce_runtime_hint)
        runtimes = spinner(settings, R.id.pvzce_runtime, R.string.pvzce_runtime, runtimeNames, "runtime",
            default = "jre25")
        val rendererValues = RendererManager.rendererList.map { it.id }
        renderers = spinner(settings, R.id.pvzce_renderer, R.string.pvzce_renderer,
            RendererManager.rendererList.map { it.name }, "renderer", rendererValues,
            RendererManager.RENDERER_MOBILEGLUES.id)
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
                if (!save()) return
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
            isChecked = prefs["pvzce"]?.toBoolean() ?: fresh
            advanced.addView(this)
            controls.add(this)
        }
        field(advanced, "classpath", R.id.pvzce_classpath, R.string.pvzce_classpath, multiline = true)
        field(advanced, "main", R.id.pvzce_main, R.string.pvzce_main_class, if (fresh) PvzceProfile.MAIN_CLASS else "")
        field(advanced, "jvm", R.id.pvzce_jvm, R.string.pvzce_jvm, if (fresh) "-Xmx1024m" else "-Xmx512m", true)
        field(advanced, "args", R.id.pvzce_args, R.string.pvzce_args, multiline = true)
        field(advanced, "work", R.id.pvzce_work, R.string.pvzce_work, File(filesDir, if (fresh) "games/pvzce" else "games").absolutePath)
        action(advanced, R.id.pvzce_browse, R.string.pvzce_browse) { if (save()) browseGameData() }
        action(advanced, R.id.pvzce_save, R.string.pvzce_save) { if (save()) status.setText(R.string.pvzce_saved) }

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
            card.setOnClickListener { importJar() }
            controls.add(card)
        }
        findViewById<Button>(R.id.pvzce_render_card).setOnClickListener { selectPage(2) }
        navIds.forEachIndexed { index, id -> findViewById<View>(id).setOnClickListener { selectPage(index) } }
        if (savedInstanceState != null && savedInstanceState.getString("instance") == instanceId) {
            pvzce.isChecked = savedInstanceState.getBoolean("pvzce")
            for (key in fields.keys) savedInstanceState.getString("field_$key")?.let { fields.getValue(key).setText(it) }
        }
        fields.getValue("jar").doAfterTextChanged { refreshSummary() }
        pvzce.setOnCheckedChangeListener { _, _ -> refreshSummary() }
        initialized = true
        GameOperationGate.addListener(operationListener)
        operationListener.run()
        if (wasFresh && fresh && !first.directory().exists()) {
            runCatching { PvzceProfile.prepareDirectory(first.directory()) }.onFailure { showError(it) }
        }
        refreshInstances()
        if (savedInstanceState != null && savedInstanceState.getString("instance") == instanceId) {
            contentPanel?.restoreState(savedInstanceState.getInt("content_kind", 0), savedInstanceState.getString("content_query", ""))
        }
        instances.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val next = instanceIds.getOrNull(position) ?: return
                if (next == instanceId || busy) return
                if (save()) runCatching { loadInstance(next) }.onFailure { showError(it); refreshInstances() }
                else refreshInstances()
            }
        }
        selectPage(savedInstanceState?.getInt("page") ?: legacy.getInt("page", 0))
        ThemeEngine.addRefreshListener(themeRefresh)
        refreshShell()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (busy) return
                if (selectedPage != 0) selectPage(0) else if (save()) finish()
            }
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun defaultConfig(pvzceMode: Boolean = true) = linkedMapOf(
        "jar" to "", "classpath" to "", "main" to if (pvzceMode) PvzceProfile.MAIN_CLASS else "",
        "jvm" to if (pvzceMode) "-Xmx1024m" else "-Xmx512m", "args" to "",
        "work" to File(filesDir, if (pvzceMode) "games/pvzce" else "games").absolutePath,
        "pvzce" to pvzceMode.toString(), "runtime" to "jre25",
        "lwjgl" to if (pvzceMode) PvzceProfile.LWJGL else lwjglNames.first(),
        "renderer" to if (pvzceMode) RendererManager.RENDERER_MOBILEGLUES.id else RendererManager.rendererList.first().id)

    fun workshopInstanceId(): String = instanceId

    private fun refreshInstances() {
        val items = instanceStore.list(false)
        instanceName = items.firstOrNull { it.id == instanceId }?.name ?: instanceName
        instanceIds = items.map { it.id }
        instances.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items.map { it.name })
        instances.setSelection(instanceIds.indexOf(instanceId).coerceAtLeast(0))
    }

    private fun loadInstance(id: String, reloadContent: Boolean = true) {
        instanceStore.select(id)
        val config = instanceStore.get(id).config
        instanceId = id
        fields.forEach { (key, field) -> field.setText(config[key] ?: "") }
        pvzce.isChecked = config["pvzce"]?.toBoolean() ?: true
        runtimes.setSelection(runtimeNames.indexOf(config["runtime"]).coerceAtLeast(0))
        lwjgl.setSelection(lwjglNames.indexOf(config["lwjgl"]).coerceAtLeast(0))
        renderers.setSelection(RendererManager.rendererList.indexOfFirst { it.id == config["renderer"] }.coerceAtLeast(0))
        refreshInstances()
        refreshSummary()
        if (reloadContent && selectedPage == 1) contentPanel?.reload(true)
        status.text = "当前实例：${instanceStore.get(id).name}"
    }

    private fun editInstanceName(copy: Boolean) {
        EditDialog(this) { name ->
            if (save()) runCatching {
                val template = if (copy) instanceStore.get(instanceId).config else defaultConfig()
                loadInstance(instanceStore.create(name, template).id)
                status.text = if (copy) "启动配置已复制；新实例的模组、包和存档为空。" else "已创建独立实例。"
            }.onFailure { showError(it) }
        }.apply { setTitle(if (copy) "复制配置到空实例" else "新建实例") }.show()
    }

    private fun restoreInstances() {
        runCatching {
            val items = instanceStore.list(true)
            if (items.isEmpty()) { status.text = "没有已归档实例"; return }
            val dialog = com.tungsten.fcllibrary.component.dialog.FCLDialog(this)
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            items.forEach { item ->
                val button = layoutInflater.inflate(R.layout.pvzce_action_button, body, false) as FCLButton
                button.text = item.name
                button.setOnClickListener {
                    if (save()) runCatching {
                        instanceStore.archive(item.id, false)
                        loadInstance(item.id)
                        dialog.dismiss()
                    }.onFailure { showError(it) }
                }
                body.addView(button)
            }
            dialog.setContentView(ScrollView(this).apply { addView(body) })
            dialog.show()
            dialog.window?.setLayout((resources.displayMetrics.widthPixels * .7f).toInt(),
                (resources.displayMetrics.heightPixels * .7f).toInt())
        }.onFailure { showError(it) }
    }

    private fun selectPage(index: Int) {
        selectedPage = index.coerceIn(pages.indices)
        pages.forEachIndexed { i, page -> page.visibility = if (i == selectedPage) View.VISIBLE else View.GONE }
        navIds.forEachIndexed { i, id -> findViewById<FCLMenuView>(id).isSelected = i == selectedPage }
        refreshNavigation()
        findViewById<FCLDynamicIsland>(R.id.pvzce_title).apply {
            contentDescription = getString(titles[selectedPage])
            refresh(getString(titles[selectedPage]))
        }
        if (selectedPage == 4) refreshLogs()
        if (selectedPage == 1 && !transfers.contains("mode")) contentPanel?.reload(true)
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
        contentPanel?.refreshTheme()
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
            instanceName + "\n" +
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
                .setInitDir(work.absolutePath).setExternalSelection(false).setGameDirectoryMode(true).create())
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
        if (initialized && !busy) save(false)
        super.onPause()
    }

    override fun onDestroy() {
        GameOperationGate.removeListener(operationListener)
        ThemeEngine.removeRefreshListener(themeRefresh)
        super.onDestroy()
    }

    private fun validateContentDirectory(): Boolean = runCatching {
        require(pvzce.isChecked) { "Apply the PVZCE preset before importing game content" }
        check(save()) { "实例配置未保存" }
        PvzceContentStore(instanceStore.get(instanceId).directory())
    }.onFailure { showError(it) }.isSuccess

    private fun applyPvzcePreset() {
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.pvzce_preset)
            .setMessage(R.string.pvzce_preset_message)
            .setNegativeButton(R.string.pvzce_cancel, null).setPositiveButton(R.string.pvzce_apply) { _, _ ->
                runCatching {
                    check(save()) { "请先修正实例目录" }
                    val work = PvzceProfile.prepareDirectory(instanceStore.get(instanceId).directory())
                    pvzce.isChecked = true
                    fields.getValue("classpath").setText("")
                    fields.getValue("main").setText(PvzceProfile.MAIN_CLASS)
                    fields.getValue("jvm").setText("-Xmx1024m")
                    fields.getValue("args").setText("")
                    fields.getValue("work").setText(work.absolutePath)
                    runtimes.setSelection(runtimeNames.indexOf("jre25"))
                    lwjgl.setSelection(lwjglNames.indexOf(PvzceProfile.LWJGL))
                    renderers.setSelection(RendererManager.rendererList.indexOfFirst {
                        it.id == RendererManager.RENDERER_MOBILEGLUES.id
                    }.coerceAtLeast(0))
                    if (save()) status.text = getString(R.string.pvzce_ready)
                }.onFailure { showError(it) }
            }.show()
    }

    private fun save(showDialog: Boolean = true): Boolean {
        if (!initialized) return true
        return runCatching {
            val config = fields.mapValues { it.value.text.toString() }.toMutableMap()
            config["pvzce"] = pvzce.isChecked.toString()
            config["runtime"] = runtimes.selectedItem.toString()
            config["renderer"] = RendererManager.rendererList[renderers.selectedItemPosition].id
            config["lwjgl"] = lwjgl.selectedItem.toString()
            instanceStore.update(instanceId, config)
            getSharedPreferences("generic_launch", MODE_PRIVATE).edit { putInt("page", selectedPage) }
        }.onFailure { if (showDialog) showError(it) else status.text = "配置未保存：${it.message}" }.isSuccess
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (!initialized) { super.onSaveInstanceState(outState); return }
        outState.putBoolean("pvzce", pvzce.isChecked)
        outState.putString("instance", instanceId)
        outState.putInt("content_kind", contentPanel?.kind?.ordinal ?: 0)
        outState.putString("content_query", contentPanel?.query ?: "")
        outState.putInt("page", selectedPage)
        fields.forEach { (key, value) -> outState.putString("field_$key", value.text.toString()) }
        super.onSaveInstanceState(outState)
    }

    private fun showError(error: Throwable) {
        val message = "操作失败：${error.message ?: error}"
        if (::status.isInitialized) status.text = message
        FCLAlertDialog.Builder(this).setTitle(getString(R.string.pvzce_error)).setMessage(message)
            .setPositiveButton(getString(R.string.pvzce_ok), null).create().show()
    }

    private fun install() {
        if (busy || !save()) return
        val runtime = runtimes.selectedItem.toString()
        status.text = "正在安装 $runtime 与随附图形 / AWT 组件…"
        io({
            RuntimeUtils.installJava(this, File(FCLPath.JAVA_PATH, runtime).absolutePath, "app_runtime/java/$runtime")
            RuntimeUtils.install(this, FCLPath.LWJGL_DIR, "app_runtime/lwjgl")
            RuntimeUtils.install(this, FCLPath.CACIOCAVALLO_17_DIR, "app_runtime/caciocavallo17")
            RuntimeUtils.installJna(this, FCLPath.JNA_PATH, "app_runtime/jna")
        }) { status.text = "已安装 $runtime 及随附组件，可选择游戏 JAR 后启动。" }
    }

    private fun startJava() {
        if (busy) return
        if (!save()) return
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
            try {
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
            } finally {
                busy = false
            }
        }
    }
}
