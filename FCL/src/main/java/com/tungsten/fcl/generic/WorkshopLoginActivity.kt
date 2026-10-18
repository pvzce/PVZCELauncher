package com.tungsten.fcl.generic

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import com.tungsten.fcl.R
import com.tungsten.fcllibrary.component.FCLActivity
import com.tungsten.fcllibrary.component.view.FCLButton
import com.tungsten.fcllibrary.component.view.FCLTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Dedicated WebView process: Casdoor owns credentials; only the backend session is handed to native. */
class WorkshopLoginActivity : FCLActivity() {
    private lateinit var web: WebView
    private lateinit var status: FCLTextView
    private lateinit var api: String
    private var verifying = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT >= 28 && !webDirectorySet) {
            WebView.setDataDirectorySuffix("pvzce-login")
            webDirectorySet = true
        }
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        api = runCatching { WorkshopApi.normalize(intent.getStringExtra("api") ?: "") }.getOrElse { finish(); return }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = FCLTextView(this).apply { setUseThemeColor(true); text = "登录 $api（由 Casdoor 验证账号）" }
        layout.addView(status)
        val cancel = layoutInflater.inflate(R.layout.pvzce_action_button, layout, false) as FCLButton
        cancel.text = "取消登录"
        cancel.setOnClickListener { finish() }
        layout.addView(cancel)
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            saveFormData = false
        }
        val cookies = CookieManager.getInstance()
        cookies.setAcceptThirdPartyCookies(web, false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                return !(url.scheme == "https" || (url.scheme == "http" && runCatching {
                    WorkshopApi.normalize("http://" + url.authority)
                }.isSuccess))
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // Do not display or log OIDC callback query strings.
                status.text = "正在登录 $api…"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                val uri = Uri.parse(url ?: return)
                if (uri.path == "/account" && uri.buildUpon().path(null).query(null).fragment(null).build().toString().trimEnd('/') == api) {
                    complete(cookies.getCookie(api + "/api/v1/account/me") ?: "")
                }
            }
        }
        layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        // Browser cookie jars do not distinguish ports. Start with a clean dedicated jar every time.
        cookies.removeAllCookies {
            web.clearCache(true)
            android.webkit.WebStorage.getInstance().deleteAllData()
            web.loadUrl(api + "/auth/login")
        }
    }

    private fun complete(header: String) {
        if (verifying) return
        val cookie = header.split(';').map { it.trim() }.firstOrNull { it.startsWith("pvzce_session=") } ?: return
        verifying = true
        status.text = "正在核验网站会话…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val me = WorkshopApi(api, cookie).request("GET", "/api/v1/account/me", null, "").asJsonObject
                WorkshopSessionStore(applicationContext).save(api, cookie, me)
            } }
            result.onSuccess { setResult(RESULT_OK); finish() }.onFailure {
                verifying = false
                status.text = "会话核验失败，请取消后重试（${if (it is WorkshopApi.ApiException) it.message else "连接或本机会话保存失败"}）"
            }
        }
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.stopLoading()
            web.destroy()
            CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
        super.onDestroy()
    }

    companion object { private var webDirectorySet = false }
}
