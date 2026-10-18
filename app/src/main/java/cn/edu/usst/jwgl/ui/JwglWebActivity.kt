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
        const val YWCX_TARGET_URL = "http://10.1.15.15:28080/"
        const val EXTRA_URL = "extra_target_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_SEARCH_QUERY = "extra_search_query"
        const val EXTRA_TARGET_DATE = "extra_target_date"
        const val EXTRA_TARGET_NODE = "extra_target_node"

        fun start(
            context: Context,
            url: String = DEFAULT_TARGET_URL,
            title: String? = null,
            searchQuery: String? = null,
            targetDate: String? = null,
            targetNode: Int = -1
        ) {
            val intent = Intent(context, JwglWebActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                if (title != null) {
                    putExtra(EXTRA_TITLE, title)
                }
                if (searchQuery != null) {
                    putExtra(EXTRA_SEARCH_QUERY, searchQuery)
                }
                if (targetDate != null) {
                    putExtra(EXTRA_TARGET_DATE, targetDate)
                }
                if (targetNode > 0) {
                    putExtra(EXTRA_TARGET_NODE, targetNode)
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
    private var targetDate: String? = null
    private var targetNode: Int = -1
    private var isDesktopMode = false
    private var filePathCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val intentData = result.data
            val results = when {
                intentData?.data != null -> arrayOf(intentData.data!!)
                intentData?.clipData != null -> {
                    val clip = intentData.clipData!!
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                }
                else -> null
            }
            filePathCallback?.onReceiveValue(results)
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    inner class LiveNavInterface {
        @android.webkit.JavascriptInterface
        fun onEpisodeFound(episodeInfo: String) {
            runOnUiThread {
                Toast.makeText(this@JwglWebActivity, "已自动跳转至：$episodeInfo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val rawTargetUrl = intent.getStringExtra(EXTRA_URL) ?: DEFAULT_TARGET_URL
        val targetUrl = sanitizeAndValidateUrl(rawTargetUrl)
        val customTitle = intent.getStringExtra(EXTRA_TITLE)
        searchQuery = intent.getStringExtra(EXTRA_SEARCH_QUERY)
        targetDate = intent.getStringExtra(EXTRA_TARGET_DATE)
        targetNode = intent.getIntExtra(EXTRA_TARGET_NODE, -1)

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
        return lower == "usst.edu.cn" || lower.endsWith(".usst.edu.cn") || lower == "10.1.15.15"
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
            "http" -> {
                if (host == "10.1.15.15") uri.toString()
                else uri.buildUpon().scheme("https").build().toString()
            }
            else -> DEFAULT_TARGET_URL
        }
    }

    private fun setupToolbar(targetUrl: String) {
        binding.toolbar.setNavigationOnClickListener {
            if (customVideoView != null) {
                binding.webView.webChromeClient?.onHideCustomView()
                return@setNavigationOnClickListener
            }
            performSafeBack()
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
                performSafeBack()
            }
        })
    }

    private fun performSafeBack() {
        if (!binding.webView.canGoBack()) {
            finish()
            return
        }
        val list = binding.webView.copyBackForwardList()
        var targetSteps = 0
        for (i in list.currentIndex - 1 downTo 0) {
            val itemUrl = list.getItemAtIndex(i)?.url?.lowercase() ?: ""
            val isAuthOrSplash = itemUrl.contains("/authserver/") ||
                                 itemUrl.contains("login") ||
                                 itemUrl.contains("ticket=") ||
                                 itemUrl.endsWith(":28080/") ||
                                 itemUrl.endsWith(":28080") ||
                                 (itemUrl.contains("cas") && itemUrl.contains("service="))
            if (!isAuthOrSplash) {
                targetSteps = i - list.currentIndex
                break
            }
        }
        if (targetSteps < 0) {
            binding.webView.goBackOrForward(targetSteps)
        } else {
            finish()
        }
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
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
        }

        applyUserAgent(targetUrl)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.addJavascriptInterface(LiveNavInterface(), "AndroidLiveNav")

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@JwglWebActivity.filePathCallback?.onReceiveValue(null)
                this@JwglWebActivity.filePathCallback = filePathCallback
                try {
                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                    fileChooserLauncher.launch(intent)
                    return true
                } catch (e: Exception) {
                    this@JwglWebActivity.filePathCallback = null
                    return false
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: android.webkit.GeolocationPermissions.Callback?
            ) {
                callback?.invoke(origin, true, false)
            }

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

                // If on the Live video platform and course/date was specified, auto-navigate to playback
                if (url != null && url.contains("live.usst.edu.cn") && (!searchQuery.isNullOrBlank() || !targetDate.isNullOrBlank())) {
                    val quotedQuery = JSONObject.quote(searchQuery ?: "")
                    val quotedTargetDate = JSONObject.quote(targetDate ?: "")
                    val targetNodeVal = targetNode
                    val jsSearch = """
                        (function() {
                            if (window.__usst_live_nav_running) return;
                            window.__usst_live_nav_running = true;

                            var courseQuery = $quotedQuery;
                            var targetDate = $quotedTargetDate;
                            var targetNode = $targetNodeVal;

                            console.log("Starting USST Live auto-navigation:", courseQuery, targetDate, targetNode);

                            var attempts = 0;
                            var maxAttempts = 75; // ~37.5s max duration
                            var searchedCourse = false;
                            var openedCourse = false;
                            var episodeSelected = false;

                            var timer = setInterval(function() {
                                attempts++;
                                if (attempts > maxAttempts || episodeSelected) {
                                    clearInterval(timer);
                                    window.__usst_live_nav_running = false;
                                    return;
                                }

                                var hash = window.location.hash || "";

                                // ==========================================
                                // 1. Mobile H5 Flow (jy-mobile-resourcemanage-ui)
                                // ==========================================

                                // Step A: Switch to "点播" tab if on liveList
                                if (hash.indexOf('/liveList') !== -1 || (!hash.indexOf('/videoList') !== -1 && !hash.indexOf('/play-video') !== -1)) {
                                    var tabs = Array.from(document.querySelectorAll('.van-tab'));
                                    var vodTab = tabs.find(function(t) { return t.innerText && t.innerText.trim() === '点播'; });
                                    if (vodTab && vodTab.getAttribute('aria-selected') !== 'true') {
                                        vodTab.click();
                                        return;
                                    }
                                }

                                // Step B & C: On videoList, search course and click it
                                if (hash.indexOf('/videoList') !== -1) {
                                    var input = document.querySelector('input[placeholder*="课程名称"]');
                                    if (input && courseQuery) {
                                        if (!searchedCourse && input.value !== courseQuery) {
                                            searchedCourse = true;
                                            input.focus();
                                            input.value = courseQuery;
                                            input.dispatchEvent(new Event('input', { bubbles: true }));
                                            input.dispatchEvent(new Event('change', { bubbles: true }));
                                            input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, bubbles: true }));
                                            input.dispatchEvent(new KeyboardEvent('keypress', { key: 'Enter', keyCode: 13, bubbles: true }));
                                            input.dispatchEvent(new KeyboardEvent('keyup', { key: 'Enter', keyCode: 13, bubbles: true }));
                                            return;
                                        }

                                        if (!openedCourse) {
                                            var titleEls = Array.from(document.querySelectorAll('.vod-item-title'));
                                            if (titleEls.length > 0) {
                                                var targetCourse = titleEls.find(function(el) { return el.innerText && el.innerText.trim() === courseQuery; }) ||
                                                                     titleEls.find(function(el) { return el.innerText && el.innerText.indexOf(courseQuery) !== -1; }) ||
                                                                     titleEls.find(function(el) { return el.innerText && courseQuery.indexOf(el.innerText.trim()) !== -1; });
                                                if (targetCourse) {
                                                    openedCourse = true;
                                                    var item = targetCourse.closest('.vod-item') || targetCourse;
                                                    item.click();
                                                    return;
                                                }
                                            }
                                        }
                                    }
                                }

                                // Step D: On play-video, locate the episode matching targetDate & targetNode
                                if (hash.indexOf('/play-video') !== -1) {
                                    var cards = Array.from(document.querySelectorAll('.selection-card'));
                                    if (cards.length === 0) {
                                        return; // still loading episodes
                                    }

                                    function matchEpisode(cardList) {
                                        if (!targetDate) return cardList[0];
                                        
                                        // 1. Both targetDate AND node
                                        if (targetNode > 0) {
                                            var n1 = '第' + targetNode + '节';
                                            var n2 = '第 ' + targetNode + ' 节';
                                            var m1 = cardList.find(function(c) {
                                                var text = c.innerText;
                                                return text.indexOf(targetDate) !== -1 && (text.indexOf(n1) !== -1 || text.indexOf(n2) !== -1);
                                            });
                                            if (m1) return m1;
                                        }

                                        // 2. Exact targetDate (e.g. 2026-09-30)
                                        var m2 = cardList.find(function(c) { return c.innerText.indexOf(targetDate) !== -1; });
                                        if (m2) return m2;

                                        // 3. Short date (MM-dd, e.g. 09-30)
                                        if (targetDate.indexOf('-') !== -1) {
                                            var shortDate = targetDate.substring(5);
                                            var m3 = cardList.find(function(c) { return c.innerText.indexOf(shortDate) !== -1; });
                                            if (m3) return m3;
                                        }
                                        return null;
                                    }

                                    var matchedCard = matchEpisode(cards);

                                    // If not found in initial horizontal list, try opening the full "共计 X 节" sheet
                                    if (!matchedCard) {
                                        var moreBtn = document.querySelector('.title-box-right');
                                        if (moreBtn && !document.querySelector('.selection-more')) {
                                            moreBtn.click();
                                            return;
                                        }
                                        var popup = document.querySelector('.selection-more');
                                        if (popup) {
                                            var popupCards = Array.from(popup.querySelectorAll('.selection-card'));
                                            matchedCard = matchEpisode(popupCards);
                                        }
                                    }

                                    if (matchedCard) {
                                        episodeSelected = true;
                                        clearInterval(timer);
                                        window.__usst_live_nav_running = false;
                                        
                                        var matchedText = matchedCard.innerText.replace(/\s+/g, ' ');
                                        matchedCard.click();

                                        if (window.AndroidLiveNav && window.AndroidLiveNav.onEpisodeFound) {
                                            window.AndroidLiveNav.onEpisodeFound(matchedText);
                                        }

                                        // Auto-start video playback
                                        setTimeout(function() {
                                            var playBtn = document.querySelector('.kmedia-mobile-play-btn, .kmd-m-flex-center, .icon-play');
                                            if (playBtn) playBtn.click();
                                            var video = document.querySelector('video');
                                            if (video) {
                                                video.play().catch(function(e) { console.log("video play error:", e); });
                                            }
                                        }, 500);
                                        return;
                                    }
                                }

                                // ==========================================
                                // 2. Desktop Web Flow (jy-application-resourcemanage-ui)
                                // ==========================================
                                if (hash.indexOf('/home') !== -1 || hash.indexOf('/liveCourse') !== -1 || hash.indexOf('/vodCourse') !== -1) {
                                    var desktopCards = Array.from(document.querySelectorAll('*')).filter(function(el) {
                                        return el.children.length === 0 && el.innerText && (el.innerText.trim() === courseQuery || el.innerText.indexOf(courseQuery) !== -1);
                                    });
                                    if (desktopCards.length > 0 && !openedCourse) {
                                        openedCourse = true;
                                        desktopCards[0].click();
                                        return;
                                    }
                                }

                                if (hash.indexOf('/play-center') !== -1 && targetDate) {
                                    var desktopEpisodes = Array.from(document.querySelectorAll('.bottom-left, .list-item, .time')).filter(function(el) {
                                        return el.innerText && el.innerText.indexOf(targetDate) !== -1;
                                    });
                                    if (desktopEpisodes.length > 0 && !episodeSelected) {
                                        episodeSelected = true;
                                        clearInterval(timer);
                                        window.__usst_live_nav_running = false;
                                        var dtItem = desktopEpisodes[0].closest('.list-item') || desktopEpisodes[0];
                                        dtItem.click();
                                        if (window.AndroidLiveNav && window.AndroidLiveNav.onEpisodeFound) {
                                            window.AndroidLiveNav.onEpisodeFound(dtItem.innerText.replace(/\s+/g, ' '));
                                        }
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

                if (scheme == "http" && host != "10.1.15.15") {
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

        if (targetUrl.contains("10.1.15.15")) {
            val cookieManager = CookieManager.getInstance()
            cookieManager.setCookie("https://ids6.usst.edu.cn/", "org.springframework.web.servlet.i18n.CookieLocaleResolver.LOCALE=zh_CN; Path=/; Secure")
            cookieManager.flush()
            binding.layoutLoading.visibility = View.GONE
            binding.webView.loadUrl(targetUrl)
            return
        }

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
