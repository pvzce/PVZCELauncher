package com.mio.manager

import android.content.Context
import com.mio.data.Renderer
import com.mio.plugin.DriverPlugin
import com.mio.plugin.RendererPlugin
import com.tungsten.fcl.FCLApp
import com.tungsten.fcl.R

object RendererManager {
    lateinit var RENDERER_VIRGL: Renderer
    lateinit var RENDERER_ZINK: Renderer
    lateinit var RENDERER_FREEDRENO: Renderer
    private var isInit = false

    @JvmStatic
    val rendererList: MutableList<Renderer> = mutableListOf()
        get() {
            if (!isInit) {
                init(FCLApp.getAppContext())
            }
            return field
        }

    fun init(context: Context) {
        if (isInit) return
        isInit = true
        rendererList.clear()

        RENDERER_VIRGL = Renderer(
            "VirGLRenderer",
            context.getString(R.string.settings_fcl_renderer_virgl),
            "libOSMesa_81.so",
            "libEGL.so",
            "",
            null,
            null,
            Renderer.ID_VIRGL,
            "",
            "26.3-snapshot-3",
            displayMaxMCver = "26.2"
        )

        RENDERER_ZINK = Renderer(
            "Zink",
            context.getString(R.string.settings_fcl_renderer_zink),
            "libglxshim.so",
            "libEGL_mesa.so",
            "",
            null,
            null,
            Renderer.ID_ZINK,
            "",
            "26.3-snapshot-3",
            displayMaxMCver = "26.2"
        )

        RENDERER_FREEDRENO = Renderer(
            "Freedreno",
            context.getString(R.string.settings_fcl_renderer_freedreno),
            "libOSMesa_8.so",
            "libEGL.so",
            "",
            null,
            null,
            Renderer.ID_FREEDRENO,
            "",
            "26.3-snapshot-3",
            displayMaxMCver = "26.2"
        )

        RendererPlugin.init(context)
        addRenderer()
        DriverPlugin.init(context)
    }

    private fun addRenderer() {
        rendererList.add(RENDERER_VIRGL)
        rendererList.add(RENDERER_ZINK)
        rendererList.add(RENDERER_FREEDRENO)
        rendererList.addAll(RendererPlugin.rendererList)
    }

    fun refresh(context: Context) {
        RendererPlugin.refresh(context)
        rendererList.clear()
        addRenderer()
    }

    /** 原位替换同 id 的插件渲染器实例（v2 环境变量配置变化后调用），已初始化时才生效 */
    fun replaceRenderer(renderer: Renderer) {
        if (!isInit) return
        rendererList.removeIf { it.id == renderer.id }
        rendererList.add(renderer)
    }

    @JvmStatic
    fun getRenderer(id: String): Renderer {
        return rendererList.find { it.id == id } ?: RENDERER_VIRGL
    }

}