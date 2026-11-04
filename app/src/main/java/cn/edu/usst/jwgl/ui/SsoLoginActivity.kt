package cn.edu.usst.jwgl.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.data.model.StudentProfile
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.databinding.ActivitySsoLoginBinding
import cn.edu.usst.jwgl.util.CookieSyncHelper
import cn.edu.usst.jwgl.util.InitialSyncHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

class SsoLoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SsoLoginActivity"
        const val EXTRA_STUDENT_ID = "extra_student_id"
        const val EXTRA_PASSWORD = "extra_password"
        private const val SSO_ENTRY_URL = "https://jwgl.usst.edu.cn/sso/jziotlogin"

        fun start(context: Context, studentId: String = "", password: String = "") {
            val intent = Intent(context, SsoLoginActivity::class.java).apply {
                putExtra(EXTRA_STUDENT_ID, studentId)
                putExtra(EXTRA_PASSWORD, password)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivitySsoLoginBinding
    private lateinit var authPrefs: AuthPreferences
    private lateinit var cacheManager: DataCacheManager

    private var passedStudentId: String = ""
    private var passedPassword: String = ""
    private var capturedUsername: String = ""
    private var capturedPassword: String = ""

    private var isLoginCompleted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivitySsoLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val density = resources.displayMetrics.density
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val fallbackStatusTop = if (resId > 0) maxOf(resources.getDimensionPixelSize(resId), (36 * density).toInt()) else (36 * density).toInt()
        binding.viewStatusBarSpacer.layoutParams.height = fallbackStatusTop

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val topPad = if (statusBar.top > 0) statusBar.top else fallbackStatusTop
            binding.viewStatusBarSpacer.layoutParams.height = topPad
            binding.viewStatusBarSpacer.requestLayout()
            binding.root.updatePadding(bottom = navBar.bottom)
            insets
        }

        authPrefs = AuthPreferences(this)
        cacheManager = DataCacheManager(this)

        passedStudentId = intent.getStringExtra(EXTRA_STUDENT_ID).orEmpty()
        passedPassword = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()

        setupToolbar()
        setupBackPress()
        setupWebView()
        startSsoFlow()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            handleBackAction()
        }
        binding.btnRefresh.setOnClickListener {
            binding.webView.reload()
        }
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackAction()
            }
        })
    }

    private fun handleBackAction() {
        if (!isLoginCompleted && binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            finish()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val ws = binding.webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.databaseEnabled = true
        ws.useWideViewPort = true
        ws.loadWithOverviewMode = true
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        ws.cacheMode = WebSettings.LOAD_DEFAULT

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        // 预设中文 Locale，防止 CAS 页面循环重载
        cookieManager.setCookie(
            "https://ids6.usst.edu.cn/",
            "org.springframework.web.servlet.i18n.CookieLocaleResolver.LOCALE=zh_CN; Path=/; Secure"
        )
        cookieManager.flush()

        // 注入凭据捕获桥接接口，以便记录用户在网页上输入或填写的账密
        binding.webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onCredentialsSubmitted(username: String?, password: String?) {
                if (!username.isNullOrBlank()) {
                    capturedUsername = username.trim()
                }
                if (!password.isNullOrBlank()) {
                    capturedPassword = password.trim()
                }
                Log.d(TAG, "Captured credentials from SSO web form: username=$capturedUsername, pwdLength=${capturedPassword.length}")
            }
        }, "AndroidSSO")

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = newProgress
                } else {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleUrlNavigation(view, url)
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return handleUrlNavigation(view, url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let { checkAndHandleLoginSuccess(it) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val curUrl = url ?: ""
                checkAndHandleLoginSuccess(curUrl)

                // 若处于 CAS 官方认证登录页面，注入辅助预填脚本与表单提交监听
                if (isCasAuthUrl(curUrl)) {
                    injectCasAssistScript(view)
                }
            }
        }
    }

    private fun handleUrlNavigation(view: WebView?, url: String): Boolean {
        // 自动将 USST 内网系统的 http 重定向升级为 https，避免明文流量策略拦截报错
        if (url.startsWith("http://") && Uri.parse(url).host?.endsWith("usst.edu.cn") == true) {
            val httpsUrl = url.replaceFirst("http://", "https://")
            Log.d(TAG, "Upgraded http to https: $httpsUrl")
            view?.loadUrl(httpsUrl)
            return true
        }

        if (checkAndHandleLoginSuccess(url)) {
            return true
        }

        return false
    }

    private fun isCasAuthUrl(url: String): Boolean {
        val host = Uri.parse(url).host ?: return false
        return (host == "ids6.usst.edu.cn" || host == "ids6-443.webvpn.usst.edu.cn") &&
                (url.contains("/authserver/") || url.contains("login"))
    }

    private fun injectCasAssistScript(view: WebView?) {
        val studentId = capturedUsername.ifEmpty { passedStudentId }
        val password = capturedPassword.ifEmpty { passedPassword }

        val quotedStudentId = JSONObject.quote(studentId)
        val quotedPassword = JSONObject.quote(password)

        val js = """
            (function() {
                try {
                    var u = document.querySelector("#username") || document.querySelector("input[name='username']");
                    var p = document.querySelector("#password") || document.querySelector("input[name='password']");
                    
                    var presetU = $quotedStudentId;
                    var presetP = $quotedPassword;
                    
                    if (u && !u.value && presetU) { u.value = presetU; }
                    if (p && !p.value && presetP) { p.value = presetP; }
                    
                    function reportCredentials() {
                        try {
                            var uEl = document.querySelector("#username") || document.querySelector("input[name='username']");
                            var pEl = document.querySelector("#password") || document.querySelector("input[name='password']");
                            var uVal = uEl ? uEl.value : "";
                            var pVal = pEl ? pEl.value : "";
                            if (window.AndroidSSO && (uVal || pVal)) {
                                window.AndroidSSO.onCredentialsSubmitted(uVal, pVal);
                            }
                        } catch (e) {}
                    }
                    
                    var form = document.querySelector("#casLoginForm") || document.querySelector("form");
                    if (form && !form.__ssoHooked) {
                        form.__ssoHooked = true;
                        form.addEventListener("submit", reportCredentials);
                    }
                    
                    var submitBtn = document.querySelector("button.auth_login_btn") || 
                                    document.querySelector("button[type='submit']") || 
                                    document.querySelector("input[type='submit']") || 
                                    document.getElementById("login_submit");
                    if (submitBtn && !submitBtn.__ssoHooked) {
                        submitBtn.__ssoHooked = true;
                        submitBtn.addEventListener("click", reportCredentials);
                    }
                } catch (e) {
                    console.error("SSO injection error", e);
                }
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private fun checkAndHandleLoginSuccess(url: String): Boolean {
        if (isLoginCompleted) return true

        val uri = Uri.parse(url)
        val host = uri.host ?: ""
        val path = uri.path ?: ""

        // 检测是否已完成 CAS SSO 授权并跳转入教务系统内部页面
        val isJwglHome = host == "jwgl.usst.edu.cn" && 
                (path.contains("jwglxt") || path.contains("index_initMenu") || path.contains("index_cxYhxxIndex"))

        if (isJwglHome) {
            isLoginCompleted = true
            finalizeSsoLogin()
            return true
        }

        return false
    }

    private fun startSsoFlow() {
        binding.layoutLoading.visibility = View.VISIBLE
        binding.tvLoadingMsg.text = "正在载入学校官方统一身份认证..."
        binding.tvLoadingSub.text = "请在稍后展示的页面中完成登录与验证"

        // 清理陈旧教务会话 Cookie，保证干净发起 SSO 会话
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeSessionCookies(null)
        cookieManager.flush()

        Log.d(TAG, "Loading SSO entry URL: $SSO_ENTRY_URL")
        binding.webView.loadUrl(SSO_ENTRY_URL)

        // 延迟后确保 WebView 呈现给用户
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed && !isLoginCompleted) {
                binding.layoutLoading.visibility = View.GONE
            }
        }, 1200)
    }

    private fun finalizeSsoLogin() {
        binding.layoutLoading.visibility = View.VISIBLE
        binding.bannerTip.visibility = View.GONE
        binding.webView.visibility = View.GONE
        binding.tvLoadingMsg.text = "SSO 认证成功，正在同步学籍信息..."
        binding.tvLoadingSub.text = "正在建立教务数据通信通道..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 1. 刷新并同步 WebView Cookie 至 JwglClient (OkHttp)
                CookieManager.getInstance().flush()
                CookieSyncHelper.syncWebViewCookiesToJwgl()

                val targetStudentId = capturedUsername.ifEmpty { passedStudentId }
                Log.d(TAG, "Fetching student profile with studentId=$targetStudentId...")

                var profile: StudentProfile? = null
                var lastErr: Exception? = null

                // 2. 带重试获取学籍信息（防止重定向尚未完全落地）
                for (attempt in 1..3) {
                    try {
                        profile = JwglClient.fetchStudentProfile(
                            fallbackStudentId = targetStudentId,
                            context = this@SsoLoginActivity
                        )
                        if (profile.studentId.isNotEmpty() || profile.name.isNotEmpty()) {
                            Log.d(TAG, "Fetched profile successfully on attempt $attempt: name=${profile.name}")
                            break
                        }
                    } catch (e: Exception) {
                        lastErr = e
                        Log.w(TAG, "Attempt $attempt fetchStudentProfile failed: ${e.message}")
                        delay(600)
                    }
                }

                if (profile == null) {
                    throw lastErr ?: IOException("未能解析到学生学籍信息")
                }

                val finalStudentId = profile.studentId.ifEmpty { targetStudentId }
                val finalPassword = capturedPassword.ifEmpty { passedPassword }

                // 3. 保存凭证与学籍信息
                if (finalStudentId.isNotEmpty() && finalPassword.isNotEmpty()) {
                    authPrefs.saveCredentials(finalStudentId, finalPassword, autoLogin = true)
                } else if (finalStudentId.isNotEmpty()) {
                    authPrefs.saveCredentials(finalStudentId, authPrefs.getPassword(), autoLogin = true)
                }
                cacheManager.saveProfile(profile)

                // 4. 若为初次登录，执行全校与个人课表初始化
                if (!authPrefs.hasInitialSyncCompleted()) {
                    withContext(Dispatchers.Main) {
                        binding.tvLoadingMsg.text = "正在初始化课表与成绩信息..."
                    }
                    InitialSyncHelper.performInitialSync(this@SsoLoginActivity, finalStudentId) { statusMsg ->
                        binding.tvLoadingSub.post {
                            binding.tvLoadingSub.text = statusMsg
                        }
                    }
                }

                // 5. 登录完成，直达主界面
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SsoLoginActivity, "欢迎回来: ${profile.name}", Toast.LENGTH_SHORT).show()
                    val intent = Intent(this@SsoLoginActivity, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_PROFILE, profile)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(intent)
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "finalizeSsoLogin failed", e)
                withContext(Dispatchers.Main) {
                    isLoginCompleted = false
                    binding.layoutLoading.visibility = View.GONE
                    binding.bannerTip.visibility = View.VISIBLE
                    binding.webView.visibility = View.VISIBLE
                    Toast.makeText(this@SsoLoginActivity, "同步学籍失败: ${e.message}，请重试", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }
}
