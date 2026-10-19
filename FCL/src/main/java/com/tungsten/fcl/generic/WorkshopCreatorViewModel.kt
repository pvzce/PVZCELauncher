package com.tungsten.fcl.generic

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
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

/** Private author data stays in memory; uploads retain origin, account, project and version. */
class WorkshopCreatorViewModel(app: Application) : AndroidViewModel(app) {
    data class State(val page: WorkshopApi.ProjectPage? = null, val detail: WorkshopApi.Detail? = null,
                     val version: Long = 0, val usage: JsonObject? = null, val games: List<String> = emptyList(),
                     val message: String = "刷新我的作品以读取账号与上传额度", val busy: Boolean = false,
                     val uploading: Boolean = false, val sent: Long = 0, val size: Long = 0,
                     val lastFile: WorkshopApi.PackageFile? = null, val user: Long = 0, val canRetry: Boolean = false)
    data class Selection(val api: String, val user: Long, val project: Long, val version: Long, val purpose: String, val kind: String)
    private data class Pending(val selection: Selection, val file: File, val filename: String, val sha: String,
                               val key: String = UUID.randomUUID().toString())
    private val app get() = getApplication<Application>()
    val api: String = app.getSharedPreferences("pvzce_workshop", 0).getString("api", "") ?: ""
    private val sessions = WorkshopSessionStore(app)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    var choosing: Selection? = null
    private var pending: Pending? = null
    private var transfer: WorkshopApi.Transfer? = null
    private var cleared = false
    private val spoolRoot = File(app.cacheDir, "pvzce_uploads")
    private val spool = File(spoolRoot, UUID.randomUUID().toString())

    init {
        synchronized(activeSpools) {
            spoolRoot.listFiles()?.filter { it.name !in activeSpools }?.forEach { WorkshopInstaller.removeTree(it.toPath()) }
            activeSpools.add(spool.name)
        }
    }

    private fun change(block: (State) -> State) { mutable.value = block(mutable.value) }

    private fun identity(): WorkshopSessionStore.Session {
        check(app.getSharedPreferences("pvzce_workshop", 0).getString("api", "") == api) { "API 地址已改变，请关闭作者页面后重新打开" }
        val session = sessions.read(api) ?: throw WorkshopApi.ApiException(401)
        if (session.expired()) { sessions.clear(api); throw WorkshopApi.ApiException(401) }
        return session
    }

    private fun operation(action: suspend (WorkshopApi, WorkshopSessionStore.Session) -> Unit) {
        if (mutable.value.busy) return
        if (api.isBlank()) { change { it.copy(message = "请返回账号页设置 API 地址并登录") }; return }
        change { it.copy(busy = true) }
        viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                val session = withContext(Dispatchers.IO) { identity() }
                val user = session.account.get("id").asLong
                if (mutable.value.user != 0L && mutable.value.user != user) {
                    pending?.file?.delete(); pending = null; choosing = null
                    mutable.value = State(user = user, busy = true, message = "登录账号已改变，原作者页面已清空")
                    throw IOException("账号已改变，请重新打开作品后再操作")
                } else change { it.copy(user = user) }
                action(WorkshopApi(api, session.cookie), session)
            } catch (error: Exception) {
                if (error is WorkshopApi.ApiException && error.status == 401) {
                    withContext(Dispatchers.IO) { sessions.clear(api) }
                    pending?.file?.delete(); pending = null; choosing = null
                    mutable.value = State(message = "尚未登录或会话已过期，请返回账号页登录", busy = true)
                } else change { it.copy(message = error.message ?: "操作失败，请刷新作品与额度后重试") }
            } finally {
                transfer = null
                change { it.copy(busy = false, uploading = false, canRetry = pending != null) }
                if (cleared) cleanup()
            }
        }
    }

    fun verifyAccount() = operation { _, _ -> }

    fun list(page: Int = 1) = operation { client, _ ->
        val result = withContext(Dispatchers.IO) {
            val projects = WorkshopApi.JSON.fromJson(client.request("GET", "/api/v1/author/projects?page=$page", null, ""), WorkshopApi.ProjectPage::class.java)
            val usage = client.request("GET", "/api/v1/upload-usage", null, "").asJsonObject
            val games = client.request("GET", "/api/v1/game-versions", null, "").asJsonArray.map { it.asJsonObject.get("version").asString }
            Triple(projects, usage, games)
        }
        change { it.copy(page = result.first, usage = result.second, games = result.third, message = "我的作品第 ${result.first.page} 页（含协作作品）") }
    }

    fun open(id: Long) = operation { client, _ ->
        val detail = withContext(Dispatchers.IO) { client.detail(id) }
        change { it.copy(detail = detail, version = detail.versions.firstOrNull()?.id ?: 0, lastFile = null, message = "作品 #$id 的私有作者详情已刷新") }
    }

    fun newProject() { if (!mutable.value.busy) change { it.copy(detail = null, version = 0, lastFile = null, message = "填写新作品资料后创建") } }
    fun selectVersion(id: Long) { if (!mutable.value.busy) change { it.copy(version = id) } }

    fun saveProject(body: JsonObject, edit: Long) = operation { client, session ->
        val detail = withContext(Dispatchers.IO) {
            val id = if (edit == 0L) client.request("POST", "/api/v1/projects", body, session.csrf).asJsonObject.get("id").asLong
                else { client.request("PATCH", "/api/v1/projects/$edit", body, session.csrf); edit }
            client.detail(id)
        }
        change { it.copy(detail = detail, version = detail.versions.firstOrNull()?.id ?: 0, message = "作品资料已保存为草稿；编辑后须重新审核") }
    }

    fun createVersion(project: Long, body: JsonObject) = operation { client, session ->
        val result = withContext(Dispatchers.IO) {
            val id = client.request("POST", "/api/v1/projects/$project/versions", body, session.csrf).asJsonObject.get("id").asLong
            Pair(client.detail(project), id)
        }
        change { it.copy(detail = result.first, version = result.second, message = "版本已创建，请上传对应包文件；尚未发布") }
    }

    fun transition(project: Long, version: Long, action: String, reason: String) = operation { client, session ->
        val detail = withContext(Dispatchers.IO) {
            val path = "/api/v1/projects/$project" + if (version > 0) "/versions/$version/transitions" else "/transitions"
            client.request("POST", path, WorkshopDrafts.transition(action, reason), session.csrf)
            client.detail(project)
        }
        change { it.copy(detail = detail, message = "状态操作已提交，以服务器返回状态为准；待审不代表已发布") }
    }

    fun rescan(file: Long, project: Long) = operation { client, session ->
        val detail = withContext(Dispatchers.IO) {
            client.request("POST", "/api/v1/files/$file/rescan", JsonObject(), session.csrf)
            client.detail(project)
        }
        change { it.copy(detail = detail, message = "扫描重试已处理，请查看文件状态；不会自动发布") }
    }

    fun icon(file: Long, project: Long) = operation { client, session ->
        val detail = withContext(Dispatchers.IO) {
            client.request("POST", "/api/v1/projects/$project/icon", JsonObject().apply { addProperty("file_id", file) }, session.csrf)
            client.detail(project)
        }
        change { it.copy(detail = detail, message = "图标已选用，作品恢复草稿并需重新审核") }
    }

    fun choose(purpose: String): Selection {
        check(!mutable.value.busy) { "操作尚未结束" }
        val state = mutable.value
        val project = requireNotNull(state.detail?.project) { "请先创建或打开作品" }
        check(state.user > 0) { "请先刷新账号" }
        if (purpose == "package") check(state.version > 0) { "请先创建或选择版本" }
        return Selection(api, state.user, project.id, if (purpose == "package") state.version else 0, purpose, project.kind).also { choosing = it }
    }

    fun picked(uri: Uri?, selection: Selection?) {
        choosing = null
        if (uri == null || selection == null || mutable.value.busy) return
        operation { client, session ->
            check(selection.api == api && selection.user == session.account.get("id").asLong) { "选择文件期间账号或 API 已改变，请重新选择" }
            transfer = WorkshopApi.Transfer()
            val task = transfer!!
            change { it.copy(uploading = true, sent = 0, size = 0, message = "正在读取并校验所选文件…") }
            pending?.file?.delete(); pending = null
            val prepared = withContext(Dispatchers.IO) {
                val usage = client.request("GET", "/api/v1/upload-usage", null, "").asJsonObject
                mutable.value = mutable.value.copy(usage = usage)
                val remaining = usage.get("daily_quota_bytes").asLong - usage.get("used").asLong - usage.get("reserved").asLong - 1
                val limit = minOf(remaining, usage.get("max_file_bytes").asLong, if (selection.purpose == "icon") 8L * 1024 * 1024 else WorkshopApi.MAX_PACKAGE)
                check(limit > 0) { "账号上传额度不足，请稍后刷新" }
                val original = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: error("无法读取文件名")
                val extension = original.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
                check(if (selection.purpose == "icon") extension in listOf("png", "jpg", "jpeg")
                    else extension == if (selection.kind == "mod") "jar" else "zip") { "模组请选择 JAR，数据包请选择 ZIP，图标请选择 PNG/JPEG" }
                spool.mkdirs()
                val file = File(spool, "upload.bin")
                try {
                    val hash = WorkshopApi.digest()
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output ->
                            val bytes = ByteArray(65536); var total = 0L
                            while (true) {
                                task.check(); val n = input.read(bytes); if (n < 0) break
                                total += n; check(total <= limit) { "文件超过大小或账号剩余额度限制" }
                                output.write(bytes, 0, n); hash.update(bytes, 0, n)
                            }
                        }
                    } ?: error("无法读取所选文件")
                    check(file.length() > 0) { "不能上传空文件" }
                    if (selection.purpose == "package") {
                        val version = client.detail(selection.project).versions.firstOrNull { it.id == selection.version }
                            ?: error("目标版本已不存在，请重新打开作品")
                        WorkshopUploadChecks.check(file, selection.kind, version, task)
                    } else {
                        val image = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(file.absolutePath, image)
                        check(image.outMimeType in listOf("image/png", "image/jpeg") && image.outWidth in 1..2048 && image.outHeight in 1..2048) { "图标须为不超过 2048×2048 的 PNG/JPEG" }
                    }
                    Pending(selection, file, "project-${selection.project}-${UUID.randomUUID()}.$extension", WorkshopApi.hex(hash.digest()))
                } catch (error: Exception) { file.delete(); throw error }
            }
            pending = prepared
            send(client, session, prepared, task)
        }
    }

    fun retry() {
        val saved = pending ?: return
        operation { client, session ->
            check(saved.selection.api == api && saved.selection.user == session.account.get("id").asLong) { "上传所属账号已改变，请重新选择文件" }
            val task = WorkshopApi.Transfer(); transfer = task
            change { it.copy(uploading = true, sent = 0, size = saved.file.length(), message = "核对服务器是否已接收上一次文件…") }
            send(client, session, saved, task)
        }
    }

    private suspend fun send(client: WorkshopApi, session: WorkshopSessionStore.Session, saved: Pending, task: WorkshopApi.Transfer) {
        val selected = saved.selection
        val file = withContext(Dispatchers.IO) {
            val before = client.detail(selected.project)
            val existing = before.files.firstOrNull { it.purpose == selected.purpose && it.version_id == selected.version && it.sha256 == saved.sha }
            existing ?: client.upload(selected.project, selected.version, selected.purpose, saved.file, saved.filename,
                saved.sha, saved.key, session.csrf, task) { n, total ->
                mutable.value = mutable.value.copy(sent = n, size = total, message = if (n == total)
                    "文件发送完毕，等待服务器校验与扫描；尚未发布" else "上传 $n / $total 字节")
            }
        }
        pending = null; saved.file.delete()
        change { it.copy(lastFile = file, message = "服务器已接收文件 #${file.id}：校验 ${file.validation_state}，扫描 ${file.scan_state}；尚未发布", canRetry = false) }
        try {
            val after = withContext(Dispatchers.IO) { Pair(client.detail(selected.project), client.request("GET", "/api/v1/upload-usage", null, "").asJsonObject) }
            change { it.copy(detail = after.first, usage = after.second) }
        } catch (error: Exception) {
            if (error is WorkshopApi.ApiException && error.status == 401) throw error
            change { it.copy(message = it.message + "；后续详情刷新失败，请手动刷新，勿重复上传") }
        }
    }

    fun cancel() { transfer?.cancel() }
    private fun cleanup() { pending?.file?.delete(); File(spool, "upload.bin").delete(); spool.delete(); activeSpools.remove(spool.name) }
    override fun onCleared() {
        cleared = true; transfer?.cancel()
        if (!mutable.value.busy) cleanup()
        super.onCleared()
    }
    companion object { private val activeSpools = java.util.concurrent.ConcurrentHashMap.newKeySet<String>() }
}
