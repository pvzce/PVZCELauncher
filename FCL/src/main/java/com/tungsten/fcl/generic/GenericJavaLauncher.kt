package com.tungsten.fcl.generic

import android.content.Context
import com.mio.manager.RendererManager
import com.tungsten.fclcore.launch.CacioJavaArgs
import com.tungsten.fclcore.util.platform.CommandBuilder
import com.tungsten.fclauncher.FCLConfig
import com.tungsten.fclauncher.FCLauncher
import com.tungsten.fclauncher.bridge.FCLBridge
import com.tungsten.fclauncher.utils.Architecture
import com.tungsten.fclauncher.utils.FCLPath
import java.io.File
import java.io.IOException
import java.util.Properties

object GenericJavaLauncher {
    fun prepare(context: Context, spec: JavaLaunchSpec, rendererId: String, lwjgl: String): FCLBridge {
        spec.validate()
        val release = Properties().apply {
            File(spec.runtimeDir, "release").reader().use { load(it) }
        }
        val arch = release.getProperty("OS_ARCH", "").trim('"')
        if (Architecture.archAsInt(arch) != Architecture.getDeviceArchitecture()) {
            throw IOException("Runtime architecture $arch does not match this device")
        }
        val lwjglDir = File(FCLPath.LWJGL_DIR, lwjgl)
        val jars = lwjglDir.listFiles()?.filter { it.extension == "jar" }?.sortedBy { it.name }
            ?: throw IOException("Install bundled LWJGL runtime first")
        if (jars.isEmpty()) throw IOException("No bundled LWJGL jars installed")
        val natives = File(lwjglDir, "natives/" + Architecture.archAsStringAndroid(Architecture.getDeviceArchitecture()))
        if (!natives.isDirectory) throw IOException("LWJGL natives for this architecture are missing")
        val command = CommandBuilder()
        CacioJavaArgs.addCacioArgs(command, "1280x720", "javax.swing.plaf.metal.MetalLookAndFeel",
            null, true, release.getProperty("JAVA_VERSION", "").trim('"').startsWith("1.8"))
        command.add("-Duser.home=" + context.filesDir.absolutePath)
        command.add("-Djava.io.tmpdir=" + context.cacheDir.absolutePath)
        command.add("-Djava.library.path=\${natives_directory}")
        command.add("-Dorg.lwjgl.librarypath=" + natives.absolutePath)
        command.add("-Dorg.lwjgl.opengl.libname=\${gl_lib_name}")
        // Prefer the Android patched LWJGL jars over desktop variants in a game's classpath.
        spec.command(jars.map { it.absolutePath }).forEach { command.add(it) }
        val renderer = RendererManager.getRenderer(rendererId)
        val config = FCLConfig(context, FCLPath.LOG_DIR, spec.runtimeDir, spec.workingDir,
            renderer, command.asList().toTypedArray()).apply { lwjglVersion = lwjgl }
        return FCLauncher.launchJarExecutor(config).apply {
            gameDir = spec.workingDir
            this.renderer = rendererId
            java = spec.runtimeDir
            scaleFactor = 1.0
        }
    }
}
