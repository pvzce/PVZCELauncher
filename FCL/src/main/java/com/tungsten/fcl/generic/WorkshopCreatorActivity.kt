package com.tungsten.fcl.generic

import android.os.Bundle
import android.net.Uri
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tungsten.fcl.R
import com.tungsten.fcllibrary.component.FCLActivity
import com.tungsten.fcllibrary.component.view.*
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** FCL controls over authenticated author endpoints; private author responses are never cached. */
class WorkshopCreatorActivity : FCLActivity() {
    private lateinit var model: WorkshopCreatorViewModel
    private lateinit var root: LinearLayout
    private lateinit var message: TextView
    private lateinit var usage: TextView
    private lateinit var progress: ProgressBar
    private lateinit var cancel: Button
    private lateinit var retry: Button
    private val pages = mutableListOf<LinearLayout>()
    private val controls = mutableListOf<View>()
    private val inputs = mutableMapOf<String, EditText>()
    private lateinit var kind: Spinner
    private lateinit var label: Spinner
    private lateinit var channel: Spinner
    private lateinit var version: Spinner
    private lateinit var versionsText: TextView
    private lateinit var mine: LinearLayout
    private lateinit var status: LinearLayout
    private var mode = 0
    private var restoredDraft: Bundle? = null
    private var renderedPage: WorkshopApi.ProjectPage? = null
    private var renderedDetail: WorkshopApi.Detail? = null
    private var renderedVersion = -1L
    private var renderedUser = -1L
    private val refreshTheme = Runnable { if (::root.isInitialized) root.background = ThemeEngine.getTheme().getBackground(this) }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val selection = model.choosing
        model.picked(uri, selection)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[WorkshopCreatorViewModel::class.java]
        mode = savedInstanceState?.getInt("mode") ?: 0
        restoredDraft = savedInstanceState?.getBundle("draft")
        if (model.choosing == null && savedInstanceState?.containsKey("choose_project") == true) {
            model.choosing = WorkshopCreatorViewModel.Selection(savedInstanceState.getString("choose_api") ?: "",
                savedInstanceState.getLong("choose_user"), savedInstanceState.getLong("choose_project"),
                savedInstanceState.getLong("choose_version"), savedInstanceState.getString("choose_purpose") ?: "",
                savedInstanceState.getString("choose_kind") ?: "")
        }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 8, 16, 8) }
        val nav = LinearLayout(this)
        button(nav, "返回工坊", false) { finish() }
        listOf("我的作品", "作品资料", "新版本", "上传 / 审核").forEachIndexed { index, name ->
            button(nav, name, false) { mode = index; showPage() }
        }
        root.addView(nav)
        text(root, "创作者服务：${model.api.ifBlank { "尚未设置 API" }}")
        message = text(root, "").apply { id = R.id.pvzce_creator_message }
        usage = text(root, "账号额度尚未读取（来源 IP 的额度由服务器另行检查）")
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        root.addView(progress)
        val transferActions = LinearLayout(this)
        cancel = button(transferActions, "取消上传", false) { model.cancel() }
        retry = button(transferActions, "核对并重试上次上传") { model.retry() }
        root.addView(transferActions)
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        repeat(4) { pages.add(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; body.addView(this) }) }
        buildMine(pages[0]); buildProject(pages[1]); buildVersion(pages[2]); buildStatus(pages[3])
        setContentView(root); showPage(); refreshTheme.run(); ThemeEngine.addRefreshListener(refreshTheme)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } } }
        if (savedInstanceState == null) model.list()
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized && model.state.value.user > 0 && !model.state.value.busy) model.verifyAccount()
    }

    private fun text(parent: LinearLayout, value: String): TextView = FCLTextView(this).apply {
        text = value; setUseThemeColor(true); setTextIsSelectable(true); setPadding(0, 8, 0, 6); parent.addView(this)
    }
    private fun field(parent: LinearLayout, key: String, title: String, multiline: Boolean = false) {
        val id = resources.getIdentifier("pvzce_creator_$key", "id", packageName)
        text(parent, title).labelFor = id
        inputs[key] = FCLEditText(this).apply {
            this.id = id; setSingleLine(!multiline)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or if (multiline) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            parent.addView(this, LinearLayout.LayoutParams(-1, -2)); controls.add(this)
        }
    }
    private fun value(key: String) = inputs.getValue(key).text.toString()
    private fun button(parent: LinearLayout, title: String, lock: Boolean = true, action: () -> Unit): FCLButton {
        val button = layoutInflater.inflate(R.layout.pvzce_action_button, parent, false) as FCLButton
        button.text = title; button.isSingleLine = false
        button.setOnClickListener { if (!lock || !model.state.value.busy) runCatching(action).onFailure { message.text = it.message } }
        parent.addView(button, if (parent.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-1, -2))
        if (lock) controls.add(button)
        return button
    }
    private fun spinner(parent: LinearLayout, title: String, options: List<String>): Spinner {
        text(parent, title)
        return Spinner(this).apply {
            minimumHeight = 48
            adapter = ArrayAdapter(this@WorkshopCreatorActivity, android.R.layout.simple_spinner_dropdown_item, options)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2)); controls.add(this)
        }
    }
    private fun buildMine(parent: LinearLayout) {
        button(parent, "刷新我的作品与额度") { model.list(model.state.value.page?.page ?: 1) }.id = R.id.pvzce_creator_refresh
        button(parent, "上一页") { model.list(((model.state.value.page?.page ?: 1) - 1).coerceAtLeast(1)) }
        button(parent, "下一页") {
            model.state.value.page?.let { if (it.page.toLong() * it.size < it.total) model.list(it.page + 1) }
        }
        button(parent, "创建新作品") { model.newProject(); mode = 1; showPage() }
        mine = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; parent.addView(this) }
    }
    private fun buildProject(parent: LinearLayout) {
        text(parent, "作品和版本分别审核。编辑已公开作品的资料或图标会使其恢复草稿并退出公开，需重新提交审核。")
        field(parent, "slug", "作品标识（2–80 位小写字母、数字或连字符）")
        field(parent, "title", "标题（最多 200 字节）")
        kind = spinner(parent, "作品类型（创建后固定）", listOf("模组", "数据包"))
        field(parent, "description", "作品介绍（最多 60000 字节）", true)
        field(parent, "license", "许可证，例如 GPL-3.0-only")
        field(parent, "tags", "标签，逗号或空格分隔（最多 20 个）")
        label = spinner(parent, "内容标注", listOf("普通内容 general", "成人暗示 suggestive（默认公开列表隐藏）"))
        button(parent, "保存作品资料（草稿 / 重新审核）") {
            val body = WorkshopDrafts.project(value("slug"), value("title"), if (kind.selectedItemPosition == 0) "mod" else "datapack",
                value("description"), value("license"), value("tags"), if (label.selectedItemPosition == 0) "general" else "suggestive")
            model.saveProject(body, model.state.value.detail?.project?.id ?: 0)
        }.id = R.id.pvzce_creator_save
    }
    private fun buildVersion(parent: LinearLayout) {
        versionsText = text(parent, "请刷新以加载服务器游戏版本列表")
        text(parent, "版本元数据创建后不可编辑，版本号不可重复使用。修改版本声明或替换失败包需要新建版本。")
        field(parent, "number", "新版本号（与模组 fabric.mod.json 的 version 一致）")
        field(parent, "changelog", "更新日志", true)
        channel = spinner(parent, "发布通道", listOf("stable", "beta"))
        field(parent, "games", "兼容游戏版本，逗号或空格分隔")
        field(parent, "dependencies", "依赖，每行：版本ID required / optional / conflict；仅引用已公开版本", true)
        button(parent, "创建版本（随后上传包文件）") {
            val project = requireNotNull(model.state.value.detail?.project) { "请先创建或打开作品" }
            val body = WorkshopDrafts.version(value("number"), value("changelog"), channel.selectedItem.toString(), value("games"), value("dependencies"))
            val games = body.getAsJsonArray("game_versions").map { it.asString }
            check(games.all { it in model.state.value.games }) { "请填写服务器已登记的游戏版本，或先刷新列表" }
            model.createVersion(project.id, body)
        }.id = R.id.pvzce_creator_version_create
    }
    private fun buildStatus(parent: LinearLayout) {
        text(parent, "上传成功仅表示文件已接收。格式 valid、扫描 clean、作品与版本都 published 后才可公开下载。")
        version = spinner(parent, "选择版本（包含草稿 / 待审 / 驳回）", emptyList())
        version.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                model.state.value.detail?.versions?.getOrNull(position)?.let { if (it.id != model.state.value.version) model.selectVersion(it.id) }
            }
        }
        button(parent, "选择模组 JAR / 数据包 ZIP 并上传") { pick("package") }.id = R.id.pvzce_creator_upload
        button(parent, "选择 PNG / JPEG 图标并上传") { pick("icon") }
        button(parent, "刷新文件、审核结果与驳回原因") {
            model.state.value.detail?.project?.id?.let { model.open(it) }
        }
        field(parent, "reason", "提交 / 撤回 / 恢复原因（必填，最多 4000 字节）", true)
        for ((name, action) in listOf("提交审核" to "submit", "撤回" to "withdraw", "恢复草稿" to "resume")) {
            val row = LinearLayout(this)
            button(row, "$name：作品") { transition(false, action) }
            button(row, "$name：所选版本") { transition(true, action) }
            parent.addView(row)
        }
        status = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; parent.addView(this) }
    }
    private fun pick(purpose: String) {
        model.choose(purpose)
        picker.launch(if (purpose == "icon") arrayOf("image/png", "image/jpeg") else arrayOf("*/*"))
    }
    private fun transition(forVersion: Boolean, action: String) {
        val state = model.state.value
        val id = requireNotNull(state.detail?.project?.id) { "请先打开作品" }
        if (forVersion) check(state.version > 0) { "请先选择版本" }
        model.transition(id, if (forVersion) state.version else 0, action, value("reason"))
    }
    private fun showPage() { pages.forEachIndexed { i, page -> page.visibility = if (i == mode) View.VISIBLE else View.GONE } }

    private fun render(state: WorkshopCreatorViewModel.State) {
        message.text = state.message
        usage.text = state.usage?.let {
            "账号上传额度：${it.get("used").asLong} 已用 + ${it.get("reserved").asLong} 预留 / ${it.get("daily_quota_bytes").asLong} 字节\n" +
                "单文件上限 ${it.get("max_file_bytes").asLong} 字节；${it.get("day").asString}，时区 ${it.get("timezone").asString}（该时区午夜重置）"
        } ?: "账号额度尚未读取"
        progress.visibility = if (state.uploading) View.VISIBLE else View.GONE
        progress.isIndeterminate = state.size == 0L
        if (state.size > 0) progress.progress = (state.sent * 1000 / state.size).toInt()
        cancel.visibility = if (state.uploading) View.VISIBLE else View.GONE
        retry.visibility = if (state.canRetry && !state.busy) View.VISIBLE else View.GONE
        versionsText.text = "服务器已登记游戏版本：${state.games.joinToString().ifEmpty { "请刷新我的作品" }}"
        if (renderedPage !== state.page) {
            renderedPage = state.page
            controls.removeAll { it.parent === mine }; mine.removeAllViews()
            state.page?.let { page ->
                text(mine, "第 ${page.page} 页，共 ${page.total} 个作品")
                page.items.forEach { project ->
                    text(mine, "${project.title} · #${project.id} · ${project.state}")
                    button(mine, "打开 / 编辑 / 上传") { model.open(project.id); mode = 3; showPage() }
                }
            }
        }
        if (renderedDetail !== state.detail || renderedVersion != state.version || renderedUser != state.user) {
            val userChanged = renderedUser > 0 && renderedUser != state.user
            if (userChanged) { inputs.values.forEach { it.setText("") }; restoredDraft = null }
            val projectChanged = renderedDetail?.project?.id != state.detail?.project?.id || renderedDetail !== state.detail || renderedUser != state.user
            renderedDetail = state.detail; renderedVersion = state.version; renderedUser = state.user
            if (projectChanged) {
                val project = state.detail?.project
                inputs.getValue("slug").setText(project?.slug ?: ""); inputs.getValue("title").setText(project?.title ?: "")
                inputs.getValue("description").setText(project?.description ?: ""); inputs.getValue("license").setText(project?.license ?: "")
                inputs.getValue("tags").setText(project?.tags.orEmpty().joinToString(", "))
                kind.setSelection(if (project?.kind == "datapack") 1 else 0)
                label.setSelection(if (project?.content_label == "suggestive") 1 else 0)
                val versions = state.detail?.versions.orEmpty()
                version.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, versions.map { "${it.number} · #${it.id} · ${it.state}" })
                version.setSelection(versions.indexOfFirst { it.id == state.version }.coerceAtLeast(0))
            }
            controls.removeAll { it.parent === status }; status.removeAllViews()
            state.detail?.let { detail ->
                text(status, "作品 #${detail.project.id}：${detail.project.title} · ${detail.project.state}\n所选版本 #${state.version}：${detail.versions.firstOrNull { it.id == state.version }?.state ?: "未选择"}")
                detail.versions.firstOrNull { it.id == state.version }?.let { selected ->
                    text(status, "版本 ${selected.number} · ${selected.channel}\n游戏版本：${selected.game_versions.joinToString()}\n更新日志：${selected.changelog}\n依赖：${selected.dependencies.joinToString { "#${it.version_id} ${it.kind}" }}")
                }
                detail.files.filter { it.purpose == "icon" || it.version_id == state.version }.forEach { file ->
                    text(status, "文件 #${file.id} · ${file.filename} · ${file.size} 字节\n格式：${file.validation_state}；扫描：${file.scan_state}\n" +
                        (file.metadata?.toString()?.take(12000) ?: ""))
                    if (file.purpose == "icon" && file.scan_state == "clean" && file.validation_state == "valid")
                        button(status, "采用此图标 #${file.id}（重新审核）") { model.icon(file.id, detail.project.id) }
                    if (file.scan_state == "failed") button(status, "重试扫描 #${file.id}") { model.rescan(file.id, detail.project.id) }
                }
                text(status, "审核与状态历史：")
                detail.reviews?.forEach { review ->
                    val row = review.asJsonObject
                    text(status, "${row.get("created_at")?.asString ?: ""} · ${row.get("action")?.asString ?: ""} · ${row.get("version_id")?.takeUnless { it.isJsonNull }?.let { "版本 #${it.asLong}" } ?: "作品"}\n原因：${row.get("reason")?.asString ?: ""}")
                }
            }
        }
        restoredDraft?.let { draft ->
            if ((state.user > 0 || model.api.isBlank()) && draft.getLong("user") == state.user && draft.getLong("project") == (state.detail?.project?.id ?: 0)) {
                inputs.forEach { (key, field) -> draft.getString(key)?.let { field.setText(it) } }
                restoredDraft = null
            } else if (state.user > 0) restoredDraft = null
        }
        controls.forEach { it.isEnabled = !state.busy }
        kind.isEnabled = !state.busy && state.detail == null
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("mode", mode)
        outState.putBundle("draft", Bundle().apply {
            putLong("user", model.state.value.user); putLong("project", model.state.value.detail?.project?.id ?: 0)
            inputs.forEach { (key, field) -> putString(key, field.text.toString()) }
        })
        model.choosing?.let { choice ->
            outState.putString("choose_api", choice.api); outState.putLong("choose_user", choice.user)
            outState.putLong("choose_project", choice.project); outState.putLong("choose_version", choice.version)
            outState.putString("choose_purpose", choice.purpose); outState.putString("choose_kind", choice.kind)
        }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { ThemeEngine.removeRefreshListener(refreshTheme); super.onDestroy() }
}
