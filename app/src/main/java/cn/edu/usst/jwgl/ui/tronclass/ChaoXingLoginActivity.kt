package cn.edu.usst.jwgl.ui.tronclass

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.network.ChaoXingClient
import cn.edu.usst.jwgl.databinding.ActivityChaoxingLoginBinding
import cn.edu.usst.jwgl.util.CookieSyncHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 超星学习通 (ChaoXing / Fanya) 交互式登录与账号绑定 Activity
 *
 * 功能特色：
 * 1. 采用上海理工大学专属通道 (fid=207) 官方认证网关；
 * 2. 支持手机验证码登录、学号密码登录、学习通 APP 扫码登录与第三方免密授权；
 * 3. 登录成功自动捕获与同步多域名鉴权凭据 (UID, vc3, _d, fid 等) 至硬件加密保险箱；
 * 4. 自动预填充当前教务学号，并提供丝滑无缝的绑定完成自动关闭回调。
 */
class ChaoXingLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChaoxingLoginBinding
    private lateinit var client: ChaoXingClient
    private var isHandlingSuccess = false

    companion object {
        private const val TAG = "ChaoXingLoginActivity"
        const val LOGIN_URL = "https://passport2.chaoxing.com/login?fid=207&refer=https://i.chaoxing.com/base"

        fun start(context: Context) {
            val intent = Intent(context, ChaoXingLoginActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChaoxingLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = ChaoXingClient(this)

        setupToolbar()
        setupWebView()
        loadLoginPage()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun loadLoginPage() {
        binding.layoutLoading.visibility = View.VISIBLE
        binding.tvLoadingMsg.text = "正在载入超星学习通认证页面..."
        binding.webView.loadUrl(LOGIN_URL)

        // 6秒兜底：若网络较慢未自动触发隐藏加载框，确保 WebView 展现
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed && !isHandlingSuccess) {
                binding.layoutLoading.visibility = View.GONE
                binding.webView.visibility = View.VISIBLE
            }
        }, 6000)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val ws = binding.webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.useWideViewPort = true
        ws.loadWithOverviewMode = true
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        ws.cacheMode = WebSettings.LOAD_DEFAULT

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progressBar.visibility = View.VISIBLE
                super.onPageStarted(view, url, favicon)
                url?.let { checkAndHandleLoginSuccess(it) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.visibility = View.GONE
                if (!isHandlingSuccess) {
                    binding.layoutLoading.visibility = View.GONE
                    binding.webView.visibility = View.VISIBLE
                }
                super.onPageFinished(view, url)

                url?.let {
                    checkAndHandleLoginSuccess(it)
                    autoFillStudentNo(it)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val nextUrl = request?.url?.toString() ?: return false
                if (checkAndHandleLoginSuccess(nextUrl)) {
                    return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }
        }
    }

    private fun autoFillStudentNo(url: String) {
        if (!url.contains("passport2.chaoxing.com/login")) return
        val currentSid = AuthPreferences(this).getStudentId().trim()
        if (currentSid.isEmpty()) return

        val quotedSid = JSONObject.quote(currentSid)
        val js = """
            (function() {
                try {
                    var u = document.querySelector("#uname") || document.querySelector("#phone") || document.querySelector("input[name='uname']");
                    if (u && (!u.value || u.value.trim().length === 0)) {
                        u.value = $quotedSid;
                        u.dispatchEvent(new Event('input', { bubbles: true }));
                        u.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    /**
     * 实时检测是否已成功完成超星账号鉴权并捕获 Session
     */
    private fun checkAndHandleLoginSuccess(url: String): Boolean {
        if (isHandlingSuccess) return true

        val cookieManager = CookieManager.getInstance()
        val passportCookie = cookieManager.getCookie("https://passport2.chaoxing.com/") ?: ""
        val iCookie = cookieManager.getCookie("https://i.chaoxing.com/") ?: ""
        val rootCookie = cookieManager.getCookie("https://chaoxing.com/") ?: ""
        val allCookies = "$passportCookie; $iCookie; $rootCookie"

        val uid = extractUid(allCookies)
        val isTargetUrl = url.contains("i.chaoxing.com") ||
                url.contains("/base") ||
                url.contains("mooc1.chaoxing.com") ||
                (url.contains("passport2.chaoxing.com") && !url.contains("/login"))

        if (!uid.isNullOrBlank() && (isTargetUrl || passportCookie.contains("UID="))) {
            isHandlingSuccess = true
            binding.layoutLoading.visibility = View.VISIBLE
            binding.tvLoadingMsg.text = "超星学习通验证成功，正在同步课程与数据..."
            binding.webView.visibility = View.GONE

            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    CookieSyncHelper.syncWebViewCookiesToChaoXing(this@ChaoXingLoginActivity)
                    client.fetchCourses()
                }

                Toast.makeText(this@ChaoXingLoginActivity, "超星学习通绑定成功！🎉", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
            return true
        }

        return false
    }

    private fun extractUid(cookieStr: String): String? {
        val regex = Regex("""(?:^|;\s*)(?:UID|_uid)=([0-9a-zA-Z_-]+)""")
        val found = regex.find(cookieStr)?.groupValues?.get(1)?.trim()
        return if (!found.isNullOrBlank() && found != "0" && !found.equals("null", ignoreCase = true)) {
            found
        } else null
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.webView.canGoBack() && !isHandlingSuccess) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
