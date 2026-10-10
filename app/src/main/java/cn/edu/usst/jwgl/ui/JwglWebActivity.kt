package cn.edu.usst.jwgl.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.databinding.ActivityWebViewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JwglWebActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "JwglWebActivity"
        private const val DEFAULT_TARGET_URL = "https://jwgl.usst.edu.cn/jwglxt/xtgl/index_initMenu.html"
        const val EXTRA_URL = "extra_target_url"
        const val EXTRA_TITLE = "extra_title"

        fun start(context: Context, url: String = DEFAULT_TARGET_URL, title: String? = null) {
            val intent = Intent(context, JwglWebActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                if (title != null) {
                    putExtra(EXTRA_TITLE, title)
                }
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityWebViewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val targetUrl = intent.getStringExtra(EXTRA_URL) ?: DEFAULT_TARGET_URL
        val customTitle = intent.getStringExtra(EXTRA_TITLE)
        if (!customTitle.isNullOrBlank()) {
            binding.toolbar.title = customTitle
        }

        setupToolbar()
        initWebView(targetUrl)
        handleBackPress()
        prepareAndLoad(targetUrl)
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                finish()
            }
        }
    }

    private fun handleBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    finish()
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(targetUrl: String) {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            // Use mobile WeChat UA for new2017.usst.edu.cn to render mobile mini-app layout, desktop UA for jwgl
            userAgentString = if (targetUrl.contains("new2017.usst.edu.cn")) {
                "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.40.2420"
            } else {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            }
        }

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
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "onPageStarted: $url")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.layoutLoading.visibility = View.GONE
                Log.d(TAG, "onPageFinished: $url")

                // If redirected to login page despite cookie injection, perform JS autofill fallback
                if (url != null && (url.contains("authserver/login") || url.contains("login_slogin.html") || url.contains("sso/jziotlogin"))) {
                    autoFillCredentialsIfPresent()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val scheme = uri.scheme ?: return false
                if (scheme != "http" && scheme != "https") {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Cannot launch intent for $uri", e)
                    }
                    return true
                }
                return false
            }
        }
    }

    private fun autoFillCredentialsIfPresent() {
        val authPrefs = AuthPreferences(this)
        val studentId = authPrefs.getStudentId()
        val password = authPrefs.getPassword()
        if (studentId.isNotEmpty() && password.isNotEmpty()) {
            val safeId = studentId.replace("'", "\\'")
            val safePwd = password.replace("'", "\\'")
            val js = """
                (function() {
                    try {
                        var u = document.getElementById('mobileUsername') || document.getElementById('username') || document.querySelector('input[name=username]') || document.getElementById('yhm');
                        var p = document.getElementById('mobilePassword') || document.getElementById('password') || document.querySelector('input[name=password]') || document.getElementById('mm');
                        if (u && p) {
                            u.value = '$safeId';
                            p.value = '$safePwd';
                            u.dispatchEvent(new Event('input', { bubbles: true }));
                            p.dispatchEvent(new Event('input', { bubbles: true }));
                            u.dispatchEvent(new Event('change', { bubbles: true }));
                            p.dispatchEvent(new Event('change', { bubbles: true }));
                            var btn = document.getElementById('load') || document.querySelector('.login-button') || document.getElementById('login_sub') || document.querySelector('button[type=submit]') || document.querySelector('.auth_login_btn') || document.getElementById('dl');
                            if (btn && !window.__auto_submitted) {
                                window.__auto_submitted = true;
                                setTimeout(function() { btn.click(); }, 300);
                            }
                        }
                    } catch(e) {}
                })();
            """.trimIndent()
            binding.webView.evaluateJavascript(js, null)
        }
    }

    private fun prepareAndLoad(targetUrl: String) {
        val authPrefs = AuthPreferences(this)
        val studentId = authPrefs.getStudentId()
        val password = authPrefs.getPassword()

        if (studentId.isNotEmpty() && password.isNotEmpty()) {
            binding.layoutLoading.visibility = View.VISIBLE
            binding.tvLoadingMessage.text = if (targetUrl.contains("new2017")) {
                "正在为学工系统认证统一登录会话..."
            } else {
                "正在为教务系统认证网页会话..."
            }

            lifecycleScope.launch {
                // Always refresh CAS SSO session via OkHttp first to guarantee 100% fresh ticket
                val loginResult = withContext(Dispatchers.IO) {
                    JwglClient.login(studentId, password)
                }

                loginResult.onSuccess {
                    syncCookiesToWebView()
                    binding.webView.loadUrl(targetUrl)
                }.onFailure { e ->
                    Log.w(TAG, "CAS login refresh failed", e)
                    // If login failed, sync existing cookies and try direct load; JS fallback handles page
                    syncCookiesToWebView()
                    binding.webView.loadUrl(targetUrl)
                }
            }
        } else {
            binding.webView.loadUrl(targetUrl)
        }
    }

    private fun syncCookiesToWebView() {
        val cookieManager = CookieManager.getInstance()
        val cookies = JwglClient.getCookies()
        val targetDomains = listOf(
            "https://jwgl.usst.edu.cn",
            "https://ids6.usst.edu.cn",
            "https://ehall.usst.edu.cn",
            "https://new2017.usst.edu.cn",
            "http://new2017.usst.edu.cn",
            "https://usst.edu.cn"
        )

        for (cookie in cookies) {
            val domain = cookie.domain
            val cookieString = "${cookie.name}=${cookie.value}; Domain=${domain}; Path=${cookie.path}; Secure"
            val rawString = "${cookie.name}=${cookie.value}; Path=${cookie.path}"

            val url = if (domain.startsWith(".")) "https://${domain.substring(1)}" else "https://${domain}"
            cookieManager.setCookie(url, cookieString)
            cookieManager.setCookie(url, rawString)

            for (target in targetDomains) {
                if (target.contains(domain.trimStart('.'))) {
                    cookieManager.setCookie(target, cookieString)
                    cookieManager.setCookie(target, rawString)
                }
            }
        }
        cookieManager.flush()
        Log.d(TAG, "Synced ${cookies.size} cookies to Android WebView")
    }

    override fun onDestroy() {
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }
}
