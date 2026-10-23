package com.mio.data

data class Renderer(
    val name: String,
    val des: String,
    val glName: String,
    val eglName: String,
    val path: String,
    val boatEnv: List<String>?,
    val pojavEnv: List<String>?,
    val id: String,
    val minMCver: String = "",
    val maxMCver: String = "",
    /** v2 插件渲染器的 POJAV_RENDERER 值；内置与 v1 插件渲染器为空串（不设置该环境变量） */
    val pojavRendererId: String = "",
    /** 来源显示名（插件应用名）；内置渲染器为空串 */
    val source: String = "",
    /** 展示用最大兼容版本，空串回落 maxMCver；兼容性判断始终用 maxMCver */
    val displayMaxMCver: String = ""
) {
    companion object {
        const val ID_MOBILEGLUES = "c3f6a502-c6b3-4e48-9f2e-03e8a928e7bf"
        const val DEFAULT_ID = ID_MOBILEGLUES
        const val ID_VIRGL = "417a7a93-d9b4-98b9-ec6e-1ea400259c1f"
        const val ID_ZINK = "18d93f17-ff53-a319-fa61-58709a77bf87"
        const val ID_FREEDRENO = "8d427e6c-9d22-2d19-db0c-3b9ac2c1543f"
    }

    fun getGLPath(): String {
        if (path.isEmpty()) return glName
        return "$path/$glName"
    }

    fun getEGLPath(): String {
        if (path.isEmpty()) return eglName
        return "$path/$eglName"
    }

    fun isEqual(id: String): Boolean {
        return this.id == id
    }

    override fun equals(other: Any?): Boolean {
        return if (other !is Renderer) false
        else id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
