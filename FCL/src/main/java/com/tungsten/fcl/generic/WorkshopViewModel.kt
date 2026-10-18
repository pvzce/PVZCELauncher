package com.tungsten.fcl.generic

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.UUID

/** No Activity references. Network IO survives recreation; installs retain an immutable target snapshot. */
class WorkshopViewModel(application: Application) : AndroidViewModel(application) {
    data class Filter(val search: String = "", val kind: String = "mod", val tag: String = "",
                      val game: String = "", val sort: String = "updated", val page: Int = 1)
    data class State(val api: String = "", val filter: Filter = Filter(), val catalogue: WorkshopApi.ProjectPage? = null,
                     val detail: WorkshopApi.Detail? = null, val version: Long = 0, val account: String = "尚未登录",
                     val profile: JsonObject? = null, val message: String = "设置 API 地址后浏览工坊", val busy: Boolean = false,
                     val downloading: Boolean = false, val received: Long = 0, val total: Long = 0,
                     val gameVersions: List<String> = emptyList())
    private val app get() = getApplication<Application>()
    private val settings = app.getSharedPreferences("pvzce_workshop", 0)
    private val sessions = WorkshopSessionStore(app)
    private val mutable = MutableStateFlow(State(api = settings.getString("api", "") ?: "",
        message = if (settings.contains("pending_transfer") && !GameOperationGate.isBusy())
            "上次下载已中断，未完成任务不会自动续传；请重新选择版本重试" else "设置 API 地址后浏览工坊"))
    val state = mutable.asStateFlow()
    private val gate = Any()
    private var transfer: WorkshopApi.Transfer? = null
    var targetId = ""

    private fun change(block: (State) -> State) { mutable.value = block(mutable.value) }

    fun setApi(raw: String) {
        if (mutable.value.busy) return
        runCatching {
            val api = WorkshopApi.normalize(raw)
            check(settings.edit().putString("api", api).commit()) { "无法保存 API 地址" }
            mutable.value = State(api = api, message = "服务地址已保存；该服务的账号和缓存独立")
        }.onFailure { change { s -> s.copy(message = it.message ?: "地址无效") } }
    }

    private fun session(api: String): WorkshopSessionStore.Session {
        val result = sessions.read(api) ?: throw IOException("请先登录此 API 服务")
        if (result.expired()) {
            sessions.clear(api)
            throw WorkshopApi.ApiException(401)
        }
        return result
    }

    private fun operation(action: suspend (String) -> Unit) {
        if (mutable.value.busy) return
        val api = mutable.value.api
        if (api.isBlank()) { change { it.copy(message = "请先设置 API 地址") }; return }
        change { it.copy(busy = true, message = "正在连接 $api…") }
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { action(api) }
            catch (error: Exception) {
                if (error is WorkshopApi.ApiException && error.status == 401) {
                    withContext(Dispatchers.IO) { sessions.clear(api) }
                    change { it.copy(account = "登录已过期，请重新登录", profile = null) }
                }
                change { it.copy(message = error.message ?: "操作失败，请重试") }
            } finally { change { it.copy(busy = false, downloading = false) } }
        }
    }

    fun refreshAccount() = operation { api ->
        val confirmed = withContext(Dispatchers.IO) {
            val saved = sessions.read(api)
            if (saved == null) null else {
                if (saved.expired()) throw WorkshopApi.ApiException(401)
                val me = WorkshopApi(api, saved.cookie).request("GET", "/api/v1/account/me", null, "").asJsonObject
                sessions.save(api, saved.cookie, me)
            }
        }
        if (confirmed == null) {
            change { it.copy(account = "尚未登录此服务", profile = null, message = "可以匿名浏览和下载公开作品") }
        } else {
            change { it.copy(account = "${confirmed.account.get("name").asString} · 用户 #${confirmed.account.get("id").asLong}\n会话到期：${confirmed.expires}",
                profile = null, message = "账号已核验，正在读取本站资料…") }
            val profile = withContext(Dispatchers.IO) {
                WorkshopApi(api, confirmed.cookie).request("GET", "/api/v1/profile", null, "").asJsonObject
            }
            change { it.copy(profile = profile, account = "${profile.get("display_name").asString} · 用户 #${confirmed.account.get("id").asLong}\n会话到期：${confirmed.expires}",
                message = "账号与资料已核验") }
        }
    }

    fun saveProfile(name: String, bio: String) = operation { api ->
        val profile = withContext(Dispatchers.IO) {
            val saved = session(api)
            val client = WorkshopApi(api, saved.cookie)
            val body = JsonObject().apply { addProperty("display_name", name); addProperty("bio", bio) }
            client.request("PATCH", "/api/v1/profile", body, saved.csrf)
            client.request("GET", "/api/v1/profile", null, "").asJsonObject
        }
        change { it.copy(profile = profile, account = "${profile.get("display_name").asString}（本站资料）", message = "资料已保存") }
    }

    fun logout() = operation { api ->
        withContext(Dispatchers.IO) {
            val saved = session(api)
            WorkshopApi(api, saved.cookie).request("POST", "/auth/logout", JsonObject(), saved.csrf)
            sessions.clear(api)
        }
        change { it.copy(account = "已退出此服务", profile = null, message = "网站会话已撤销；Casdoor SSO 会话由其自身管理") }
    }

    fun clearLocalSession() = operation { api ->
        withContext(Dispatchers.IO) { sessions.clear(api) }
        change { it.copy(account = "本机登录已清除", profile = null, message = "本机凭据已删除；服务器不可达时无法撤销服务器会话") }
    }

    fun search(filter: Filter) = operation { api ->
        change { it.copy(filter = filter, detail = null, version = 0) }
        val result = withContext(Dispatchers.IO) {
            val cache = File(app.cacheDir, "pvzce_workshop/${WorkshopApi.key(api)}/catalogue.json")
            try {
                val client = WorkshopApi(api, "")
                val page = client.projects(filter.search, filter.kind, filter.tag, filter.game, filter.sort, filter.page)
                val versions = client.request("GET", "/api/v1/game-versions", null, "").asJsonArray.map { it.asJsonObject.get("version").asString }
                val record = JsonObject().apply {
                    add("filter", WorkshopApi.JSON.toJsonTree(filter)); add("page", WorkshopApi.JSON.toJsonTree(page))
                    addProperty("saved", java.time.Instant.now().toString())
                }
                InstanceFiles.write(cache, record.toString().toByteArray(Charsets.UTF_8))
                Triple(page, versions, "找到 ${page.total} 个作品")
            } catch (error: IOException) {
                if (!cache.isFile || cache.length() > 8 * 1024 * 1024) throw error
                val record = com.google.gson.JsonParser.parseString(cache.readText()).asJsonObject
                if (record.get("filter") != WorkshopApi.JSON.toJsonTree(filter)) throw error
                Triple(WorkshopApi.JSON.fromJson(record.get("page"), WorkshopApi.ProjectPage::class.java),
                    emptyList(), "连接失败，显示 ${record.get("saved").asString} 的缓存；安装前会重新验证服务器版本")
            }
        }
        change { it.copy(catalogue = result.first, gameVersions = result.second, message = result.third) }
    }

    fun openProject(id: Long) = operation { api ->
        val detail = withContext(Dispatchers.IO) { WorkshopApi(api, "").detail(id) }
        change { it.copy(detail = detail, version = detail.versions.firstOrNull { v -> v.state == "published" }?.id ?: 0,
            message = "已加载作品 #$id；可下载版本仅限已发布且通过扫描的文件") }
    }

    fun chooseVersion(id: Long) { if (!mutable.value.busy) change { it.copy(version = id) } }
    fun backToList() { if (!mutable.value.busy) change { it.copy(detail = null) } }
    fun cancel() { transfer?.cancel() }

    fun install(instanceId: String) {
        if (mutable.value.busy) return
        val state = mutable.value
        if (state.api.isBlank()) return
        val project = state.detail?.project?.id ?: return
        val version = state.version
        if (!GameOperationGate.begin(gate)) { change { it.copy(message = "实例操作尚未完成，请稍后再试") }; return }
        val task = WorkshopApi.Transfer()
        transfer = task
        operation { api ->
            change { it.copy(downloading = true, received = 0, total = 0, message = "正在检查目标实例…") }
            try {
                val result = withContext(Dispatchers.IO) {
                    val registry = GameInstanceStore(app.filesDir)
                    val target = WorkshopTarget(registry, instanceId)
                    val directory = target.directory
                    val gameVersion = WorkshopVersions.gameVersion(target.jar)
                    val client = WorkshopApi(api, "")
                    val detail = client.detail(project)
                    val selected = detail.versions.firstOrNull { it.id == version } ?: error("版本已不可见，请刷新详情")
                    val installer = WorkshopInstaller(directory)
                    installer.check(api, detail, selected, gameVersion)
                    val file = detail.file(selected)
                    val cache = File(app.cacheDir, "pvzce_workshop/${WorkshopApi.key(api)}").apply { mkdirs() }
                    // Only one installer owns the gate. Remove abandoned partial files from an earlier process.
                    cache.listFiles()?.filter { it.name.endsWith(".download") }?.forEach { it.delete() }
                    check(settings.edit().putString("pending_transfer", "$api|$instanceId|$version").commit()) { "无法记录下载任务" }
                    val temporary = File(cache, "${UUID.randomUUID()}.download")
                    try {
                        var last = 0L
                        client.download(file, temporary, task) { received, total ->
                            val now = System.currentTimeMillis()
                            if (now - last > 150 || received == total) {
                                last = now
                                mutable.value = mutable.value.copy(received = received, total = total,
                                    message = "下载到 ${target.name}：$received / $total 字节")
                            }
                        }
                        task.check()
                        target.verify(registry)
                        check(WorkshopVersions.gameVersion(target.jar) == gameVersion) { "目标游戏 JAR 版本已改变，取消安装" }
                        val latest = client.detail(project)
                        val confirmed = latest.versions.firstOrNull { it.id == version } ?: error("版本已下架，取消安装")
                        check(latest.file(confirmed).sha256 == file.sha256) { "服务器包记录已改变，取消安装" }
                        val message = installer.install(api, latest, confirmed, gameVersion, temporary, task)
                        "${target.name}：$message"
                    } finally {
                        temporary.delete()
                        settings.edit().remove("pending_transfer").commit()
                    }
                }
                change { it.copy(message = result) }
            } finally { transfer = null; GameOperationGate.end(gate) }
        }
    }

    override fun onCleared() {
        transfer?.cancel()
        super.onCleared()
    }
}
