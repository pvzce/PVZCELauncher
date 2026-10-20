package com.tungsten.fcl.generic

import android.content.Context
import java.io.File

/** Runs on IO immediately before preparing the native bridge. Website cookies never reach the game. */
object GameLoginCoordinator {
    data class Result(val ticket: GameLoginProtocol.Ticket? = null, val notice: String = "")
    fun prepare(context: Context, spec: JavaLaunchSpec): Result {
        val prefs = context.getSharedPreferences("pvzce_workshop", 0)
        if (!prefs.getBoolean("launch_login", false)) return Result()
        fun offline(reason: String) = Result(notice = "$reason；本次离线启动")
        val origin = prefs.getString("api", "") ?: ""
        if (origin.isBlank()) return offline("尚未设置 API 地址")
        val sessions = WorkshopSessionStore(context)
        return try {
            val jar = File(spec.jar).let { if (it.isAbsolute) it else File(spec.workingDir, spec.jar) }
            if (!jar.isFile || !GameLoginProtocol.supportedGame(jar)) return offline("该游戏未声明支持安全登录交接")
            val session = sessions.read(origin) ?: return offline("尚未登录此 API")
            if (session.expired()) { sessions.clear(origin); return offline("网站会话已过期") }
            val client = WorkshopApi(origin, session.cookie)
            Result(GameLoginProtocol.issue(client, session.csrf, WorkshopVersions.gameVersion(jar)))
        } catch (error: WorkshopApi.ApiException) {
            if (error.status == 401) sessions.clear(origin)
            offline(if (error.status == 404) "服务器尚未支持游戏登录交接" else "无法取得游戏登录凭证（HTTP ${error.status}）")
        } catch (_: Exception) { offline("无法验证游戏登录交接能力或取得有效凭证") }
    }
}
