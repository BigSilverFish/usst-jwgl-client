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

        fun start(context: Context, url: String = DEFAULT_TARGET_URL) {
            val intent = Intent(context, JwglWebActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var binding: ActivityWebViewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        initWebView()
        handleBackPress()

        val targetUrl = intent.getStringExtra(EXTRA_URL) ?: DEFAULT_TARGET_URL
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
    private fun initWebView() {
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
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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
                if (!title.isNullOrBlank() && !title.startsWith("http")) {
                    binding.toolbar.title = title
                }
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.d(TAG, "onPageStarted: ")
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.layoutLoading.visibility = View.GONE
                Log.d(TAG, "onPageFinished: ")

                // If redirected to login page despite cookie injection, perform JS autofill fallback
                if (url != null && (url.contains("authserver/login") || url.contains("login_slogin.html") || url.contains("sso/jziotlogin"))) {
                    autoFillCredentialsIfPresent()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
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
                        var u = document.getElementById('username') || document.querySelector('input[name=username]') || document.getElementById('yhm');
                        var p = document.getElementById('password') || document.querySelector('input[name=password]') || document.getElementById('mm');
                        if (u && p) {
                            u.value = '$safeId';
                            p.value = '$safePwd';
                            u.dispatchEvent(new Event('input', { bubbles: true }));
                            p.dispatchEvent(new Event('input', { bubbles: true }));
                            var btn = document.getElementById('login_sub') || document.querySelector('button[type=submit]') || document.querySelector('.auth_login_btn') || document.getElementById('dl');
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
            binding.tvLoadingMessage.text = "正在为网页版认证教务系统会话..."

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
            binding.webView.loadUrl("https://jwgl.usst.edu.cn/sso/jziotlogin")
        }
    }

    private fun syncCookiesToWebView() {
        val cookieManager = CookieManager.getInstance()
        val cookies = JwglClient.getCookies()
        val targetDomains = listOf(
            "https://jwgl.usst.edu.cn",
            "https://ids6.usst.edu.cn",
            "https://ehall.usst.edu.cn",
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
