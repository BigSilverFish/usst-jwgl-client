package cn.edu.usst.jwgl.ui.tronclass

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.ActivityTronclassLoginBinding
import cn.edu.usst.jwgl.util.CookieSyncHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class TronClassLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTronclassLoginBinding
    private lateinit var client: TronClassClient
    private var isTokenExchanging = false

    companion object {
        private const val TAG = "TronClassLoginActivity"

        fun start(context: Context) {
            val intent = Intent(context, TronClassLoginActivity::class.java)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTronclassLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = TronClassClient(this)

        setupToolbar()
        setupFastImport()
        setupWebView()
        startUnifiedAuthFlow()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupFastImport() {
        binding.btnImportFromApp.setOnClickListener {
            binding.layoutLoading.visibility = View.VISIBLE
            binding.tvLoadingMsg.text = "正在从本机畅课App读取登录凭据..."

            lifecycleScope.launch {
                val res = client.importFromLocalTronClassApp()
                binding.layoutLoading.visibility = View.GONE
                if (res.isSuccess) {
                    Toast.makeText(this@TronClassLoginActivity, "导入成功！欢迎 ${client.getUserName()}", Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    val err = res.exceptionOrNull()?.message ?: "导入失败"
                    Toast.makeText(this@TronClassLoginActivity, err, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startUnifiedAuthFlow() {
        val auth = AuthPreferences(this)
        val studentId = auth.getStudentId()
        val password = auth.getPassword()

        // 1. 同步教务系统现有 Cookie 至 WebView (尤其是 CASTGC)
        CookieSyncHelper.syncJwglCookiesToWebView()

        // 2. 若教务会话尚未建立或已过期，但已保存账号密码，则静默发起一次 SSO 会话同步
        if (!JwglClient.hasValidSession() && studentId.isNotEmpty() && password.isNotEmpty()) {
            binding.layoutLoading.visibility = View.VISIBLE
            binding.tvLoadingMsg.text = "正在同步学校统一身份认证 (SSO) 会话..."
            lifecycleScope.launch(Dispatchers.IO) {
                val loginRes = JwglClient.login(studentId, password)
                withContext(Dispatchers.Main) {
                    if (loginRes.isSuccess) {
                        CookieSyncHelper.syncJwglCookiesToWebView()
                    }
                    loadOAuthUrl()
                }
            }
        } else {
            loadOAuthUrl()
        }
    }

    private fun loadOAuthUrl() {
        val authUrl = TronClassClient.getOAuthAuthUrl()
        Log.d(TAG, "Loading OAuth Auth URL: $authUrl")
        binding.layoutLoading.visibility = View.VISIBLE
        binding.tvLoadingMsg.text = "正在进行一网畅学统一认证授权..."
        binding.webView.loadUrl(authUrl)

        // 8秒超时兜底：若网络缓慢或遇验证码未自动跳转，恢复显示 WebView 供用户手动交互
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed && binding.layoutLoading.visibility == View.VISIBLE && !isTokenExchanging) {
                binding.layoutLoading.visibility = View.GONE
                binding.webView.visibility = View.VISIBLE
            }
        }, 8000)
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

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                binding.progressBar.visibility = View.VISIBLE
                super.onPageStarted(view, url, favicon)
                url?.let { handleInterceptUrl(it) }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progressBar.visibility = View.GONE
                super.onPageFinished(view, url)

                // 若 CAS Cookie 失效重新跳转到 ids6 认证登录页，自动填入账密并提交
                if (url != null && (url.contains("ids6.usst.edu.cn/authserver/login") || url.contains("ids6-443.webvpn.usst.edu.cn/authserver/login"))) {
                    val auth = AuthPreferences(this@TronClassLoginActivity)
                    val studentId = auth.getStudentId()
                    val password = auth.getPassword()
                    if (studentId.isNotEmpty() && password.isNotEmpty()) {
                        Log.d(TAG, "Auto-filling CAS login form in TronClass WebView...")
                        val quotedStudentId = JSONObject.quote(studentId)
                        val quotedPassword = JSONObject.quote(password)
                        val js = """
                            (function() {
                                var errEl = document.querySelector("#msg, .auth_error");
                                if (errEl && errEl.innerText && errEl.innerText.trim().length > 0 && window.getComputedStyle(errEl).display !== 'none') {
                                    return;
                                }
                                var u = document.querySelector("#username") || document.querySelector("input[name='username']");
                                var p = document.querySelector("#password") || document.querySelector("input[name='password']");
                                var btn = document.querySelector("button.auth_login_btn") || 
                                          document.querySelector("button[type='submit']") || 
                                          document.querySelector("input[type='submit']") || 
                                          document.getElementById("login_submit");
                                if (u && p && btn) {
                                    u.value = $quotedStudentId;
                                    p.value = $quotedPassword;
                                    var evInput = new Event('input', { bubbles: true });
                                    var evChange = new Event('change', { bubbles: true });
                                    u.dispatchEvent(evInput);
                                    u.dispatchEvent(evChange);
                                    p.dispatchEvent(evInput);
                                    p.dispatchEvent(evChange);
                                    setTimeout(function() { btn.click(); }, 120);
                                }
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(js, null)
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleInterceptUrl(url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return handleInterceptUrl(url)
            }
        }
    }

    private fun handleInterceptUrl(url: String): Boolean {
        Log.d(TAG, "Navigating to URL: $url")

        // 拦截 OAuth 回调: https://mobile-download.tronclass.com.cn/callback.html?code=...
        if (url.startsWith("https://mobile-download.tronclass.com.cn/callback.html") ||
            url.contains("callback.html?code=")
        ) {
            val uri = Uri.parse(url)
            val code = uri.getQueryParameter("code")

            if (!code.isNullOrBlank()) {
                Log.d(TAG, "OAuth authorization code captured: $code")
                performTokenExchange(code)
                return true
            }
        }

        return false
    }

    private fun performTokenExchange(code: String) {
        if (isTokenExchanging) return
        isTokenExchanging = true

        binding.webView.visibility = View.INVISIBLE
        binding.layoutLoading.visibility = View.VISIBLE
        binding.tvLoadingMsg.text = "认证成功，正在换取畅课登录凭证..."

        lifecycleScope.launch {
            val result = client.loginWithAuthCode(code)
            if (result.isSuccess) {
                Toast.makeText(this@TronClassLoginActivity, "一网畅学授权成功！", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } else {
                isTokenExchanging = false
                binding.webView.visibility = View.VISIBLE
                binding.layoutLoading.visibility = View.GONE
                val err = result.exceptionOrNull()?.message ?: "换取登录凭证失败"
                Toast.makeText(this@TronClassLoginActivity, err, Toast.LENGTH_LONG).show()
            }
        }
    }
}
