package cn.edu.usst.jwgl.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.databinding.ActivityWebViewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

class JwglWebActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "JwglWebActivity"
        private const val DEFAULT_TARGET_URL = "https://jwgl.usst.edu.cn/jwglxt/xtgl/index_initMenu.html"
        const val LIVE_TARGET_URL = "https://live.usst.edu.cn/jy-application-resourcemanage/oauth2/authorize?json=0&returnUri=https%3A%2F%2Flive.usst.edu.cn%2Fjy-application-resourcemanage-ui%2F%3Ftype%3Dcas"
        const val EXTRA_URL = "extra_target_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SEARCH_QUERY = "extra_search_query"

        fun start(
            context: Context,
            url: String = DEFAULT_TARGET_URL,
            title: String? = null,
            searchQuery: String? = null
        ) {
            val intent = Intent(context, JwglWebActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                if (title != null) {
                    putExtra(EXTRA_TITLE, title)
                }
                if (searchQuery != null) {
                    putExtra(EXTRA_SEARCH_QUERY, searchQuery)
                }
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityWebViewBinding
    private var customVideoView: View? = null
    private var customVideoCallback: WebChromeClient.CustomViewCallback? = null
    private var casAttemptCount = 0
    private var searchQuery: String? = null
    private var isDesktopMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val rawTargetUrl = intent.getStringExtra(EXTRA_URL) ?: DEFAULT_TARGET_URL
        val targetUrl = sanitizeAndValidateUrl(rawTargetUrl)
        val customTitle = intent.getStringExtra(EXTRA_TITLE)
        searchQuery = intent.getStringExtra(EXTRA_SEARCH_QUERY)

        // Responsively select UA based on screen size:
        // Phones (< 600dp) default to Mobile UA for optimal touch and mobile layout
        // Tablets/Desktops (>= 600dp) default to Desktop UA
        val smallestWidthDp = resources.configuration.smallestScreenWidthDp
        isDesktopMode = smallestWidthDp >= 600

        if (!customTitle.isNullOrBlank()) {
            binding.toolbar.title = customTitle
        }

        setupToolbar(targetUrl)
        initWebView(targetUrl)
        handleBackPress()
        prepareAndLoad(targetUrl)
    }

    private fun isAllowedUsstHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val lower = host.lowercase()
        return lower == "usst.edu.cn" || lower.endsWith(".usst.edu.cn")
    }

    private fun sanitizeAndValidateUrl(url: String): String {
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            null
        } ?: return DEFAULT_TARGET_URL

        val host = uri.host ?: return DEFAULT_TARGET_URL
        if (!isAllowedUsstHost(host)) {
            Log.w(TAG, "Blocked non-USST target URL host: $host")
            return DEFAULT_TARGET_URL
        }
        return when (uri.scheme?.lowercase()) {
            "https" -> uri.toString()
            "http" -> uri.buildUpon().scheme("https").build().toString()
            else -> DEFAULT_TARGET_URL
        }
    }

    private fun setupToolbar(targetUrl: String) {
        binding.toolbar.setNavigationOnClickListener {
            if (customVideoView != null) {
                binding.webView.webChromeClient?.onHideCustomView()
                return@setNavigationOnClickListener
            }
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                finish()
            }
        }

        binding.toolbar.inflateMenu(R.menu.menu_web_activity)
        updateDesktopMenuItemTitle()
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_refresh -> {
                    binding.webView.reload()
                    true
                }
                R.id.action_toggle_desktop -> {
                    isDesktopMode = !isDesktopMode
                    applyUserAgent(binding.webView.url ?: targetUrl)
                    updateDesktopMenuItemTitle()
                    binding.webView.reload()
                    val toastText = if (isDesktopMode) "已切换至电脑版视图" else "已切换至手机版视图"
                    Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_open_external -> {
                    val currentUrl = binding.webView.url ?: targetUrl
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)))
                    } catch (e: Exception) {
                        Toast.makeText(this, "无法调用外部浏览器打开", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun updateDesktopMenuItemTitle() {
        val menuItem = binding.toolbar.menu.findItem(R.id.action_toggle_desktop) ?: return
        menuItem.title = if (isDesktopMode) "切换为手机版" else "切换为电脑版"
    }

    private fun handleBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customVideoView != null) {
                    binding.webView.webChromeClient?.onHideCustomView()
                    return
                }
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    private fun applyUserAgent(url: String) {
        val mobileChromeUa = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        val desktopChromeUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val weChatMobileUa = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.40.2420"

        binding.webView.settings.userAgentString = when {
            url.contains("new2017.webvpn.usst.edu.cn") -> mobileChromeUa
            url.contains("new2017") -> weChatMobileUa
            isDesktopMode -> desktopChromeUa
            else -> mobileChromeUa
        }
        Log.d(TAG, "Selected UA (desktopMode=$isDesktopMode) for $url: ${binding.webView.settings.userAgentString}")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(targetUrl: String) {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
        }

        applyUserAgent(targetUrl)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    binding.progressBar.visibility = View.VISIBLE
                    binding.progressBar.progress = newProgress
                } else {
                    binding.progressBar.visibility = View.GONE
                }
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrBlank() && !title.startsWith("http") && !title.contains("统一身份认证") && !title.contains("登录")) {
                    binding.toolbar.title = title
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("WebViewConsole", "[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()} (${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})")
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customVideoView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customVideoView = view
                customVideoCallback = callback

                binding.layoutNormalWeb.visibility = View.GONE
                binding.videoContainer.apply {
                    visibility = View.VISIBLE
                    removeAllViews()
                    addView(view)
                }

                // Hide status bar & navigation bar for immersive video playback
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }

            override fun onHideCustomView() {
                if (customVideoView == null) return

                binding.videoContainer.apply {
                    removeAllViews()
                    visibility = View.GONE
                }
                binding.layoutNormalWeb.visibility = View.VISIBLE

                customVideoCallback?.onCustomViewHidden()
                customVideoView = null
                customVideoCallback = null

                // Restore status bar and normal orientation
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    show(WindowInsetsCompat.Type.systemBars())
                }
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                val host = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                Log.d(TAG, "onPageStarted: host=$host, url=$url")
                if (url != null && !url.contains("authserver/login")) {
                    casAttemptCount = 0
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.layoutLoading.visibility = View.GONE
                val host = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                Log.d(TAG, "onPageFinished: host=$host, url=$url")

                // Auto-fill and submit unified auth (CAS) if landing on the login page
                if (url != null && (url.contains("ids6.usst.edu.cn/authserver/login") || url.contains("ids6-443.webvpn.usst.edu.cn/authserver/login"))) {
                    if (casAttemptCount < 3) {
                        val auth = AuthPreferences(this@JwglWebActivity)
                        val studentId = auth.getStudentId()
                        val password = auth.getPassword()
                        if (studentId.isNotEmpty() && password.isNotEmpty()) {
                            casAttemptCount++
                            Log.d(TAG, "Auto-filling CAS login form in WebView (attempt $casAttemptCount)...")
                            val quotedStudentId = JSONObject.quote(studentId)
                            val quotedPassword = JSONObject.quote(password)
                            val js = """
                                (function() {
                                    if (window._usstCasAutoLoginRunning) return;
                                    window._usstCasAutoLoginRunning = true;

                                    var errEl = document.querySelector("#msg, .auth_error");
                                    if (errEl && errEl.innerText && errEl.innerText.trim().length > 0 && window.getComputedStyle(errEl).display !== 'none') {
                                        console.log("CAS error message visible, skipping auto-login:", errEl.innerText);
                                        return;
                                    }

                                    var attempts = 0;
                                    var timer = setInterval(function() {
                                        attempts++;
                                        var u = document.querySelector("#username") || document.querySelector("input[name='username']");
                                        var p = document.querySelector("#password") || document.querySelector("input[name='password']");
                                        var btn = document.querySelector("button.auth_login_btn") || 
                                                  document.querySelector("button[type='submit']") || 
                                                  document.querySelector("input[type='submit']") || 
                                                  document.getElementById("login_submit");
                                        if (u && p && btn) {
                                            clearInterval(timer);
                                            u.value = $quotedStudentId;
                                            p.value = $quotedPassword;
                                            var evInput = new Event('input', { bubbles: true });
                                            var evChange = new Event('change', { bubbles: true });
                                            u.dispatchEvent(evInput);
                                            u.dispatchEvent(evChange);
                                            p.dispatchEvent(evInput);
                                            p.dispatchEvent(evChange);
                                            setTimeout(function() {
                                                console.log("Triggering CAS form submit...");
                                                btn.click();
                                            }, 250);
                                        } else if (attempts > 30) {
                                            clearInterval(timer);
                                            window._usstCasAutoLoginRunning = false;
                                        }
                                    }, 200);
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(js, null)
                        }
                    }
                }

                // If on the Live video platform and a course search query was specified, auto-search it
                if (url != null && url.contains("live.usst.edu.cn") && !searchQuery.isNullOrBlank()) {
                    val quotedQuery = JSONObject.quote(searchQuery ?: "")
                    val jsSearch = """
                        (function() {
                            if (window.__usst_search_injected) return;
                            var q = $quotedQuery;
                            if (!q) return;
                            var attempts = 0;
                            var timer = setInterval(function() {
                                attempts++;
                                if (attempts > 30) {
                                    clearInterval(timer);
                                    return;
                                }
                                var input = document.querySelector('input[placeholder*="课程名称"]');
                                if (input) {
                                    window.__usst_search_injected = true;
                                    clearInterval(timer);
                                    input.focus();
                                    input.value = q;
                                    input.dispatchEvent(new Event('input', { bubbles: true }));
                                    input.dispatchEvent(new Event('change', { bubbles: true }));
                                    var btn = document.querySelector('.search-icon') ? document.querySelector('.search-icon').closest('button') : null;
                                    if (!btn) {
                                        var container = input.closest('.el-input');
                                        if (container && container.parentElement) {
                                            btn = container.parentElement.querySelector('button');
                                        }
                                    }
                                    if (btn) {
                                        setTimeout(function() { btn.click(); }, 350);
                                    }
                                }
                            }, 500);
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(jsSearch, null)
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme?.lowercase() ?: return true
                val host = uri.host

                if (scheme != "http" && scheme != "https") {
                    return true
                }

                if (!isAllowedUsstHost(host)) {
                    Log.w(TAG, "Blocked navigation to external host: $host")
                    Toast.makeText(this@JwglWebActivity, "已拦截非校园网域名访问: ${host ?: ""}", Toast.LENGTH_SHORT).show()
                    return true
                }

                if (scheme == "http") {
                    val httpsUrl = uri.buildUpon().scheme("https").build().toString()
                    view?.loadUrl(httpsUrl)
                    return true
                }

                return false
            }
        }
    }

    private fun prepareAndLoad(targetUrl: String) {
        val authPrefs = AuthPreferences(this)
        val studentId = authPrefs.getStudentId()
        val password = authPrefs.getPassword()

        if (targetUrl.contains("live.usst.edu.cn")) {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setCookie("https://ids6.usst.edu.cn/", "org.springframework.web.servlet.i18n.CookieLocaleResolver.LOCALE=zh_CN; Path=/; Secure")
            cookieManager.flush()
            binding.layoutLoading.visibility = View.GONE
            binding.webView.loadUrl(LIVE_TARGET_URL)
            return
        }

        if (studentId.isNotEmpty() && password.isNotEmpty()) {
            binding.layoutLoading.visibility = View.VISIBLE
            binding.tvLoadingMessage.text = when {
                targetUrl.contains("new2017") -> "正在为学工一体化认证统一登录会话..."
                else -> "正在为教务系统认证网页会话..."
            }

            lifecycleScope.launch {
                val loginResult = withContext(Dispatchers.IO) {
                    if (targetUrl.contains("new2017.webvpn.usst.edu.cn")) {
                        JwglClient.loginWebVpn(studentId, password)
                    } else {
                        JwglClient.login(studentId, password).map { Unit }
                    }
                }

                loginResult.onSuccess {
                    val finalUrl = withContext(Dispatchers.IO) {
                        if (targetUrl.contains("new2017.webvpn.usst.edu.cn")) {
                            "https://ids6-443.webvpn.usst.edu.cn/authserver/login?service=" + java.net.URLEncoder.encode(targetUrl, "UTF-8")
                        } else {
                            targetUrl
                        }
                    }
                    syncCookiesToWebView()
                    binding.webView.loadUrl(finalUrl)
                }.onFailure { e ->
                    Log.w(TAG, "SSO login refresh failed", e)
                    syncCookiesToWebView()
                    val fallbackUrl = when {
                        targetUrl.contains("new2017.webvpn.usst.edu.cn") ->
                            "https://ids6-443.webvpn.usst.edu.cn/authserver/login?service=" + java.net.URLEncoder.encode(targetUrl, "UTF-8")
                        else -> targetUrl
                    }
                    binding.webView.loadUrl(fallbackUrl)
                }
            }
        } else {
            val urlToLoad = when {
                targetUrl.contains("new2017.webvpn.usst.edu.cn") ->
                    "https://ids6-443.webvpn.usst.edu.cn/authserver/login?service=" + java.net.URLEncoder.encode(targetUrl, "UTF-8")
                else -> targetUrl
            }
            binding.webView.loadUrl(urlToLoad)
        }
    }

    private fun syncCookiesToWebView() {
        val cookieManager = CookieManager.getInstance()
        val cookies = JwglClient.getCookies()

        for (cookie in cookies) {
            val cleanDomain = cookie.domain.trimStart('.')
            if (!isAllowedUsstHost(cleanDomain)) continue

            val secureAttr = if (cookie.secure) "; Secure" else ""
            val httpOnlyAttr = if (cookie.httpOnly) "; HttpOnly" else ""

            // 1. Origin URL with original path
            val originUrl = "https://$cleanDomain${cookie.path}"
            val exactCookieStr = "${cookie.name}=${cookie.value}; Path=${cookie.path}$secureAttr$httpOnlyAttr"
            cookieManager.setCookie(originUrl, exactCookieStr)

            // 2. Set with root Path=/ so all subpaths (e.g. /authserver/login) inherit it
            val rootCookieStr = "${cookie.name}=${cookie.value}; Path=/$secureAttr$httpOnlyAttr"
            cookieManager.setCookie("https://$cleanDomain/", rootCookieStr)
        }

        // Pre-configure LOCALE to avoid page reload loops in CAS login-language.js
        cookieManager.setCookie("https://ids6.usst.edu.cn/", "org.springframework.web.servlet.i18n.CookieLocaleResolver.LOCALE=zh_CN; Path=/; Secure")
        cookieManager.flush()
        Log.d(TAG, "Synced ${cookies.size} cookies to Android WebView")
    }

    override fun onDestroy() {
        if (customVideoView != null) {
            binding.webView.webChromeClient?.onHideCustomView()
        }
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }
}
