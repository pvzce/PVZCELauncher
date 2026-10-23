package com.tungsten.fcl.fragment

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.lifecycleScope
import com.tungsten.fcl.R
import com.tungsten.fcl.activity.SplashActivity
import com.tungsten.fcl.databinding.FragmentRuntimeBinding
import com.tungsten.fcl.util.RuntimeUtils
import com.tungsten.fclauncher.utils.Architecture
import com.tungsten.fclauncher.utils.FCLPath
import com.tungsten.fcllibrary.component.FCLFragment
import com.tungsten.fcllibrary.component.dialog.FCLAlertDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import com.tungsten.fcllibrary.component.view.FCLImageView
import com.tungsten.fcllibrary.component.view.FCLProgressBar
import com.tungsten.fcllibrary.component.view.FCLTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RuntimeFragment : FCLFragment(), View.OnClickListener {
    private lateinit var bind: FragmentRuntimeBinding
    var lwjgl = false
    var cacio17 = false
    var java25 = false
    var jna = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_runtime, container, false)
        bind = FragmentRuntimeBinding.bind(view)
        bind.install.setOnClickListener(this)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { initState() }
            refreshDrawables()
            check()
        }
        return view
    }

    private fun initState() {
        lwjgl = (activity as SplashActivity).lwjgl
        cacio17 = (activity as SplashActivity).cacio17
        java25 = (activity as SplashActivity).java25
        jna = (activity as SplashActivity).jna
    }

    private fun refreshDrawables() {
        if (context != null) {
            val stateUpdate =
                AppCompatResources.getDrawable(requireContext(), R.drawable.ic_baseline_update_24)
            val stateDone =
                AppCompatResources.getDrawable(requireContext(), R.drawable.ic_baseline_done_24)

            stateUpdate?.setTint(ThemeEngine.getTheme().getColor2())
            stateDone?.setTint(ThemeEngine.getTheme().getColor2())

            bind.apply {
                lwjglState.setBackgroundDrawable(if (lwjgl) stateDone else stateUpdate)
                cacio17State.setBackgroundDrawable(if (cacio17) stateDone else stateUpdate)
                java25State.setBackgroundDrawable(if (java25) stateDone else stateUpdate)
                jnaState.setBackgroundDrawable(if (jna) stateDone else stateUpdate)
            }
        }
    }

    private val isLatest: Boolean
        get() = lwjgl && cacio17 && java25 && jna

    private fun check() {
        if (isLatest) {
            view?.visibility = View.GONE
            (activity as SplashActivity).enterLauncher()
        }
    }

    private var installing = false

    private fun install() {
        if (installing) return

        installing = true
        bind.apply {
            if (!lwjgl) {
                launchInstall(lwjglState, lwjglProgress, lwjglDetail, { lwjgl = true }) {
                    RuntimeUtils.install(context, FCLPath.LWJGL_DIR, "app_runtime/lwjgl", it)
                }
            }
            if (!cacio17) {
                launchInstall(cacio17State, cacio17Progress, cacio17Detail, { cacio17 = true }) {
                    RuntimeUtils.install(
                        context,
                        FCLPath.CACIOCAVALLO_17_DIR,
                        "app_runtime/caciocavallo17",
                        it
                    )
                }
            }
            if (!java25) {
                launchInstall(java25State, java25Progress, java25Detail, { java25 = true }) {
                    RuntimeUtils.installJava(
                        context,
                        FCLPath.JAVA_25_PATH,
                        "app_runtime/java/jre25",
                        it
                    )
                }
            }
            if (!jna) {
                launchInstall(jnaState, jnaProgress, jnaDetail, { jna = true }) {
                    RuntimeUtils.installJna(context, FCLPath.JNA_PATH, "app_runtime/jna", it)
                }
            }
        }
    }

    private fun launchInstall(
        state: FCLImageView,
        progress: FCLProgressBar,
        detail: FCLTextView,
        markDone: () -> Unit,
        block: (RuntimeUtils.InstallListener) -> Unit
    ) {
        val listener = createListener(detail)
        state.visibility = View.GONE
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { block(listener) }.exceptionOrNull()
            }
            state.visibility = View.VISIBLE
            progress.visibility = View.GONE
            detail.visibility = View.GONE
            if (error != null) {
                showErrorDialog(error.toString())
            } else {
                markDone()
            }
            refreshDrawables()
            check()
        }
    }

    private fun createListener(detail: FCLTextView): RuntimeUtils.InstallListener {
        val mainHandler = Handler(Looper.getMainLooper())
        var lastUpdateTime = 0L
        fun post(text: String, force: Boolean = false) {
            val now = SystemClock.elapsedRealtime()
            // 节流，避免解压大量小文件时刷爆主线程消息队列；阶段文案不节流
            if (!force && now - lastUpdateTime < DETAIL_UPDATE_INTERVAL_MS) return
            lastUpdateTime = now
            mainHandler.post {
                if (isAdded) {
                    detail.text = text
                    detail.visibility = View.VISIBLE
                }
            }
        }
        return object : RuntimeUtils.InstallListener {
            override fun onUpdate(detailText: String) {
                post(detailText)
            }

            override fun onStage(resId: Int) {
                post(getString(resId), force = true)
            }
        }
    }

    override fun onClick(view: View) {
        if (view === bind.install) {
            val deviceArch = Architecture.archAsString(Architecture.getDeviceArchitecture())
            if (!isJavaArchSupported(deviceArch)) {
                showErrorDialog(
                    getString(
                        R.string.missing_runtime_arch_files,
                        deviceArch,
                        "FCL-release-x.x.x.x-$deviceArch.apk",
                        "FCL-release-x.x.x.x-all.apk"
                    )
                )
                return
            }
            install()
        }
    }

    private fun isJavaArchSupported(arch: String): Boolean {
        try {
            val javaDirs = listOf("jre25")
            val assetManager = requireContext().assets
            var supportedCount = 0
            for (javaDir in javaDirs) {
                val dirPath = "app_runtime/java/$javaDir"
                val files = assetManager.list(dirPath)
                if (files != null) {
                    val expectedFile = "bin-$arch.tar.xz"
                    if (files.contains(expectedFile)) {
                        supportedCount++
                    }
                }
            }
            return supportedCount > 0
        } catch (e: Exception) {
            showErrorDialog(e.toString())
            return false
        }
    }

    private fun showErrorDialog(message: String) {
        installing = false
        lifecycleScope.launch(Dispatchers.Main) {
            FCLAlertDialog.Builder(requireContext())
                .setMessage(message)
                .setPositiveButton {
                }
                .create()
                .show()
        }
    }

    companion object {
        private const val DETAIL_UPDATE_INTERVAL_MS = 50L
    }
}
