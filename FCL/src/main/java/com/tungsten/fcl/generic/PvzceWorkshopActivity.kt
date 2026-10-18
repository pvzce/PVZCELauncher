package com.tungsten.fcl.generic

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tungsten.fcl.R
import com.tungsten.fcllibrary.component.FCLActivity
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import com.tungsten.fcllibrary.component.view.FCLButton
import com.tungsten.fcllibrary.component.view.FCLEditText
import com.tungsten.fcllibrary.component.view.FCLTextView
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** PVZCE-only online surface, independent of the disabled Minecraft download/account stack. */
class PvzceWorkshopActivity : FCLActivity() {
    private lateinit var model: WorkshopViewModel
    private lateinit var root: LinearLayout
    private lateinit var accountPage: ScrollView
    private lateinit var cataloguePage: ScrollView
    private lateinit var account: FCLTextView
    private lateinit var message: FCLTextView
    private lateinit var api: EditText
    private lateinit var displayName: EditText
    private lateinit var bio: EditText
    private lateinit var search: EditText
    private lateinit var tag: EditText
    private lateinit var game: EditText
    private lateinit var projectId: EditText
    private lateinit var kind: Spinner
    private lateinit var sort: Spinner
    private lateinit var target: Spinner
    private lateinit var resultList: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var cancel: FCLButton
    private var instances = emptyList<GameInstanceStore.Instance>()
    private val controls = mutableListOf<View>()
    private var showAccount = false
    private var renderedCatalogue: WorkshopApi.ProjectPage? = null
    private var renderedDetail: WorkshopApi.Detail? = null
    private var renderedVersion = -1L
    private var renderedProfile: com.google.gson.JsonObject? = null
    private var renderedApi = ""
    private val login = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.refreshAccount()
    }
    private val refreshTheme = Runnable { if (::root.isInitialized) root.background = ThemeEngine.getTheme().getBackground(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[WorkshopViewModel::class.java]
        if (savedInstanceState == null) intent.getStringExtra("api")?.let { origin ->
            if (origin != model.state.value.api) model.setApi(origin)
        }
        showAccount = savedInstanceState?.getBoolean("account") ?: intent.getBooleanExtra("account", false)
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val navigation = LinearLayout(this)
        button(navigation, "返回启动器", false) { finish() }
        button(navigation, "在线工坊", false) { showAccount = false; showPage() }
        button(navigation, "账号与 API", false) { showAccount = true; showPage() }
        root.addView(navigation)
        message = text(root, "")
        message.id = R.id.pvzce_workshop_message
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        root.addView(progress)
        cancel = button(root, "取消下载", false) { model.cancel() }
        accountPage = page().also { buildAccount(it.getChildAt(0) as LinearLayout) }
        cataloguePage = page().also { buildCatalogue(it.getChildAt(0) as LinearLayout) }
        setContentView(root)
        showPage()
        refreshTheme.run()
        ThemeEngine.addRefreshListener(refreshTheme)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect { render(it) } }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!showAccount && model.state.value.detail != null && !model.state.value.busy) model.backToList()
                else finish()
            }
        })
        if (savedInstanceState == null && model.state.value.api.isNotBlank()) {
            val project = intent.getLongExtra("project", 0)
            if (project > 0) model.openProject(project) else model.refreshAccount()
        }
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
    private fun page(): ScrollView {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        return ScrollView(this).apply {
            isFillViewport = true
            addView(body)
            root.addView(this, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun text(parent: LinearLayout, value: String): FCLTextView = FCLTextView(this).apply {
        text = value; textSize = 14f; setUseThemeColor(true); setPadding(0, dp(8), 0, dp(6))
        setTextIsSelectable(true)
        parent.addView(this)
    }

    private fun field(parent: LinearLayout, label: String, id: Int, value: String = "", multiline: Boolean = false): EditText {
        text(parent, label).labelFor = id
        return FCLEditText(this).apply {
            this.id = id; setText(value); setSingleLine(!multiline)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                if (multiline) android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            parent.addView(this, LinearLayout.LayoutParams(-1, -2)); controls.add(this)
        }
    }

    private fun button(parent: LinearLayout, label: String, lock: Boolean = true, action: () -> Unit): FCLButton {
        val button = layoutInflater.inflate(R.layout.pvzce_action_button, parent, false) as FCLButton
        button.text = label
        button.isSingleLine = false
        button.setOnClickListener { if (!lock || !model.state.value.busy) action() }
        if (parent.orientation == LinearLayout.HORIZONTAL) parent.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
        else parent.addView(button)
        if (lock) controls.add(button)
        return button
    }

    private fun spinner(parent: LinearLayout, label: String, entries: List<String>): Spinner {
        text(parent, label)
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@PvzceWorkshopActivity, android.R.layout.simple_spinner_dropdown_item, entries)
            minimumHeight = dp(48)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2)); controls.add(this)
        }
    }

    private fun buildAccount(body: LinearLayout) {
        api = field(body, "API 服务根地址（HTTPS；例如你部署的 pvzce-site 地址）", R.id.pvzce_api_address, model.state.value.api)
        button(body, "保存 API 地址") { model.setApi(api.text.toString()) }
        account = text(body, "")
        account.id = R.id.pvzce_account_summary
        text(body, "登录后可编辑本站资料。Casdoor 管理密码和身份；公开作品可匿名浏览下载。")
        button(body, "登录 / 注册") {
            runCatching {
                check(model.state.value.api.isNotBlank()) { "请先保存 API 地址" }
                login.launch(Intent(this, WorkshopLoginActivity::class.java).putExtra("api", model.state.value.api))
            }.onFailure { message.text = it.message }
        }.id = R.id.pvzce_account_login
        button(body, "刷新账号资料") { model.refreshAccount() }
        displayName = field(body, "本站显示名", R.id.pvzce_profile_name)
        bio = field(body, "简介", R.id.pvzce_profile_bio, multiline = true)
        button(body, "保存本站资料") { model.saveProfile(displayName.text.toString(), bio.text.toString()) }
        button(body, "退出此 API 的网站会话") { model.logout() }
        button(body, "仅清除本机登录") {
            FCLAlertDialog.Builder(this).setTitle("清除本机登录？")
                .setMessage("这不会撤销服务器会话；通常请使用退出。仅在无法连接或本机记录损坏时使用。")
                .setNegativeButton("取消", null).setPositiveButton("清除") { model.clearLocalSession() }.create().show()
        }
    }

    private fun buildCatalogue(body: LinearLayout) {
        val filter = model.state.value.filter
        search = field(body, "搜索作品", R.id.pvzce_workshop_search, filter.search)
        kind = spinner(body, "分类", listOf("模组", "数据包"))
        kind.setSelection(if (filter.kind == "datapack") 1 else 0)
        tag = field(body, "标签（可留空）", R.id.pvzce_workshop_tag, filter.tag)
        game = field(body, "游戏版本筛选（可留空；安装时读取目标游戏 JAR 的实际版本）", R.id.pvzce_workshop_game, filter.game)
        sort = spinner(body, "排序", listOf("最近更新", "下载次数", "评分"))
        sort.setSelection(listOf("updated", "downloads", "rating").indexOf(filter.sort).coerceAtLeast(0))
        button(body, "搜索 / 刷新") { search(1) }.id = R.id.pvzce_workshop_refresh
        button(body, "查看服务器支持的游戏版本") {
            val versions = model.state.value.gameVersions
            message.text = if (versions.isEmpty()) "请先搜索以加载服务器游戏版本列表" else "已验证游戏版本：${versions.joinToString()}"
        }
        val pagination = LinearLayout(this)
        button(pagination, "上一页") { search((model.state.value.filter.page - 1).coerceAtLeast(1)) }
        button(pagination, "下一页") {
            val state = model.state.value
            val page = state.catalogue
            if (page != null && page.page.toLong() * page.size < page.total) search(state.filter.page + 1)
        }
        body.addView(pagination)
        projectId = field(body, "或按作品 ID 打开详情", R.id.pvzce_workshop_project_id)
        button(body, "打开作品 ID") {
            val id = projectId.text.toString().toLongOrNull()
            if (id == null || id < 1) message.text = "请输入有效作品 ID" else model.openProject(id)
        }
        instances = runCatching { GameInstanceStore(filesDir).list(false) }.getOrElse { message.text = "实例读取失败"; emptyList() }
        target = spinner(body, "安装目标实例（任务开始后固定）", instances.map { it.name })
        if (model.targetId.isEmpty()) model.targetId = intent.getStringExtra("instance") ?: ""
        target.setSelection(instances.indexOfFirst { it.id == model.targetId }.coerceAtLeast(0))
        target.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                model.targetId = instances.getOrNull(position)?.id ?: ""
            }
        }
        resultList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; id = R.id.pvzce_workshop_results }
        body.addView(resultList)
    }

    private fun search(page: Int) {
        model.search(WorkshopViewModel.Filter(search.text.toString(), if (kind.selectedItemPosition == 0) "mod" else "datapack",
            tag.text.toString(), game.text.toString(), listOf("updated", "downloads", "rating")[sort.selectedItemPosition], page))
    }

    private fun showPage() {
        accountPage.visibility = if (showAccount) View.VISIBLE else View.GONE
        cataloguePage.visibility = if (showAccount) View.GONE else View.VISIBLE
    }

    private fun render(state: WorkshopViewModel.State) {
        message.text = state.message
        account.text = state.account
        progress.visibility = if (state.downloading) View.VISIBLE else View.GONE
        progress.isIndeterminate = state.total == 0L
        if (state.total > 0) progress.progress = (state.received * 1000 / state.total).toInt()
        cancel.visibility = if (state.downloading) View.VISIBLE else View.GONE
        if (renderedApi != state.api) {
            renderedApi = state.api; api.setText(state.api)
            displayName.setText(""); bio.setText("")
        }
        if (renderedProfile != state.profile) {
            renderedProfile = state.profile
            displayName.setText(state.profile?.get("display_name")?.asString ?: "")
            bio.setText(state.profile?.get("bio")?.asString ?: "")
        }
        if (renderedCatalogue !== state.catalogue || renderedDetail !== state.detail || renderedVersion != state.version) {
            renderedCatalogue = state.catalogue; renderedDetail = state.detail; renderedVersion = state.version
            controls.removeAll { it.parent === resultList }
            resultList.removeAllViews()
            if (state.detail != null) detail(state) else catalogue(state)
        }
        controls.forEach { it.isEnabled = !state.busy }
    }

    private fun catalogue(state: WorkshopViewModel.State) {
        val page = state.catalogue ?: return
        text(resultList, "第 ${page.page} 页 · 共 ${page.total} 个作品 · 每页 ${page.size} 项")
        if (page.items.isEmpty()) text(resultList, "没有匹配的公开作品")
        page.items.forEach { item ->
            text(resultList, "${item.title}\n作者 #${item.owner_id} · ${item.tags.orEmpty().joinToString()} · 下载 ${item.downloads}\n${item.description.take(240)}")
            button(resultList, "详情 / 版本 · #${item.id}") { model.openProject(item.id) }
        }
    }

    private fun detail(state: WorkshopViewModel.State) {
        val detail = state.detail ?: return
        val project = detail.project
        button(resultList, "返回搜索结果") { model.backToList() }
        text(resultList, "${project.title}\n作者 #${project.owner_id}\n${project.description}\n许可证：${project.license}\n标签：${project.tags.orEmpty().joinToString()}")
        val versions = detail.versions.filter { it.state == "published" }
        if (versions.isEmpty()) { text(resultList, "没有已发布版本"); return }
        val selected = versions.firstOrNull { it.id == state.version } ?: versions.first()
        val choices = spinner(resultList, "版本", versions.map { "${it.number} · ${it.channel} · #${it.id}" })
        choices.setSelection(versions.indexOf(selected))
        choices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (versions[position].id != model.state.value.version) model.chooseVersion(versions[position].id)
            }
        }
        text(resultList, "兼容游戏：${selected.game_versions.orEmpty().joinToString()}\n更新日志：${selected.changelog}")
        text(resultList, "依赖：\n" + selected.dependencies.orEmpty().joinToString("\n") { "${it.kind} · 版本 #${it.version_id}" }.ifEmpty { "无平台依赖声明" })
        text(resultList, "必需依赖须先安装并启用；缺失依赖或冲突会阻止安装。当前 API 没有版本 ID 到作品的直接解析接口，请按作者说明查找依赖作品。")
        val file = runCatching { detail.file(selected) }.getOrNull()
        if (file != null) {
            text(resultList, "${file.filename} · ${file.size} 字节\nSHA-256：${file.sha256}")
            button(resultList, "下载 / 更新到目标实例") {
                val instance = instances.getOrNull(target.selectedItemPosition) ?: return@button
                FCLAlertDialog.Builder(this).setTitle("安装到 ${instance.name}？")
                    .setMessage("${project.title} ${selected.number}\n会核对游戏版本、依赖、冲突和本地修改；更新的旧版本保存在回收站。失败重试会重新下载。")
                    .setNegativeButton("取消", null).setPositiveButton("安装") { model.install(instance.id) }.create().show()
            }.id = R.id.pvzce_workshop_install
        } else text(resultList, "该版本暂无可下载的已验证包文件")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("account", showAccount)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        ThemeEngine.removeRefreshListener(refreshTheme)
        super.onDestroy()
    }
}
