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
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.webkit.PermissionRequest
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import android.content.res.Configuration
import android.util.Base64
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import cn.edu.usst.jwgl.BuildConfig
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.databinding.ActivityWebViewBinding
import cn.edu.usst.jwgl.util.CookieSyncHelper
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

class JwglWebActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "JwglWebActivity"
        const val DEFAULT_TARGET_URL = "https://jwgl.usst.edu.cn/sso/jziotlogin"
        const val XG_TARGET_URL = "https://new2017.webvpn.usst.edu.cn/default.aspx"
        const val LIVE_TARGET_URL = "https://live.usst.edu.cn/jy-application-resourcemanage/oauth2/authorize?json=0&returnUri=https%3A%2F%2Flive.usst.edu.cn%2Fjy-mobile-resourcemanage-ui%2F%23%2FliveList%3Ftype%3Dcas"
        const val LIVE_DESKTOP_TARGET_URL = "https://live.usst.edu.cn/jy-application-resourcemanage/oauth2/authorize?json=0&returnUri=https%3A%2F%2Flive.usst.edu.cn%2Fjy-application-resourcemanage-ui%2F%3Ftype%3Dcas"
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

    private var pendingPermissionRequest: PermissionRequest? = null



    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingPermissionRequest?.grant(pendingPermissionRequest?.resources)
            pendingPermissionRequest = null
        } else {
            pendingPermissionRequest?.deny()
            pendingPermissionRequest = null
            Toast.makeText(this, "需要相机权限以进行扫码", Toast.LENGTH_SHORT).show()
        }
    }

    private var pendingGeolocationCallback: android.webkit.GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val granted = fineGranted || coarseGranted
        if (granted) {
            pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, true, false)
        } else {
            pendingGeolocationCallback?.invoke(pendingGeolocationOrigin, false, false)
            Toast.makeText(this, "需要定位权限以完成课堂签到", Toast.LENGTH_SHORT).show()
        }
        pendingGeolocationCallback = null
        pendingGeolocationOrigin = null
    }

    inner class LiveNavInterface {
        @android.webkit.JavascriptInterface
        fun onEpisodeFound(episodeInfo: String) {
            runOnUiThread {
                Toast.makeText(this@JwglWebActivity, "已自动跳转至：$episodeInfo", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 🧪 视频帧抓取测试桥 —— 验证能否通过 JS Canvas 从直播 <video> 元素截取帧画面
     *
     * 测试目标：
     * 1. 检测 <video> 元素是否存在且正在播放
     * 2. 验证 Canvas.drawImage() 是否被 CORS/DRM 阻止（tainted canvas）
     * 3. 对截取的帧进行 ML Kit 二维码扫描
     * 4. 汇报完整诊断结果
     */
    inner class LiveQrTestBridge {
        @android.webkit.JavascriptInterface
        fun onTestResult(jsonResult: String) {
            runOnUiThread {
                try {
                    val json = JSONObject(jsonResult)
                    val status = json.optString("status", "unknown")
                    val message = json.optString("message", "")
                    val videoWidth = json.optInt("videoWidth", 0)
                    val videoHeight = json.optInt("videoHeight", 0)
                    val canvasWidth = json.optInt("canvasWidth", 0)
                    val canvasHeight = json.optInt("canvasHeight", 0)
                    val base64Data = json.optString("frameData", "")
                    val error = json.optString("error", "")

                    val report = StringBuilder()
                    report.appendLine("━━━ 视频帧抓取测试报告 ━━━")
                    report.appendLine()
                    report.appendLine("状态: ${if (status == "success") "✅ 成功" else "❌ 失败"}")
                    report.appendLine("消息: $message")
                    if (error.isNotEmpty()) {
                        report.appendLine("错误: $error")
                    }
                    report.appendLine()
                    report.appendLine("📺 视频原始分辨率: ${videoWidth}×${videoHeight}")
                    report.appendLine("🖼️ 截帧画布分辨率: ${canvasWidth}×${canvasHeight}")

                    if (status == "success" && base64Data.isNotEmpty()) {
                        report.appendLine()
                        report.appendLine("⏳ 正在用 ML Kit 扫描二维码...")

                        // 解码 Base64 → Bitmap → ML Kit 二维码扫描
                        try {
                            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                report.appendLine("✅ 帧数据解码成功: ${bitmap.width}×${bitmap.height}, ${bytes.size / 1024}KB")

                                val image = InputImage.fromBitmap(bitmap, 0)
                                val options = BarcodeScannerOptions.Builder()
                                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
                                    .build()
                                val scanner = BarcodeScanning.getClient(options)
                                scanner.process(image)
                                    .addOnSuccessListener { barcodes ->
                                        val finalReport = StringBuilder(report)
                                        finalReport.appendLine()
                                        if (barcodes.isNotEmpty()) {
                                            finalReport.appendLine("🎉 发现 ${barcodes.size} 个二维码！")
                                            barcodes.forEachIndexed { idx, barcode ->
                                                finalReport.appendLine("  [${idx + 1}] 格式: ${barcode.format}")
                                                val rawValue = barcode.rawValue ?: "(null)"
                                                finalReport.appendLine("  [${idx + 1}] 内容: ${rawValue.take(200)}")
                                            }
                                            finalReport.appendLine()
                                            finalReport.appendLine("✅ 方案可行！Canvas 帧抓取 + ML Kit 识别链路完全打通。")
                                        } else {
                                            finalReport.appendLine("📭 未检测到二维码（当前帧中可能没有 QR 码）")
                                            finalReport.appendLine()
                                            finalReport.appendLine("✅ Canvas 帧抓取通道正常，可尝试在有签到二维码的直播画面中重新测试。")
                                        }
                                        showTestResultDialog(finalReport.toString())
                                        scanner.close()
                                    }
                                    .addOnFailureListener { e ->
                                        report.appendLine("❌ ML Kit 扫描失败: ${e.message}")
                                        showTestResultDialog(report.toString())
                                        scanner.close()
                                    }
                                return@runOnUiThread // ML Kit 异步处理，结果在回调中显示
                            } else {
                                report.appendLine("❌ Base64 → Bitmap 解码失败（空 Bitmap）")
                            }
                        } catch (e: Exception) {
                            report.appendLine("❌ 帧数据解码异常: ${e.message}")
                        }
                    } else if (status == "cors_blocked") {
                        report.appendLine()
                        report.appendLine("⚠️ Canvas 被 CORS 污染保护阻止 (SecurityError)")
                        report.appendLine("直播流与页面不同源，浏览器禁止 Canvas 读取视频像素。")
                        report.appendLine()
                        report.appendLine("💡 需要升级到 Level 2 方案：提取 video.src 交给 ExoPlayer 解码。")
                    } else if (status == "no_video") {
                        report.appendLine()
                        report.appendLine("⚠️ 页面中未找到 <video> 元素或视频未在播放。")
                        report.appendLine("请确保已打开直播/点播视频后再测试。")
                    }

                    showTestResultDialog(report.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse test result", e)
                    Toast.makeText(this@JwglWebActivity, "测试结果解析失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showTestResultDialog(report: String) {
        val textView = TextView(this).apply {
            text = report
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        AlertDialog.Builder(this)
            .setTitle("🧪 视频帧抓取测试结果")
            .setView(textView)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun injectFrameGrabTestJs() {
        val js = """
            (function() {
                var result = {
                    status: 'unknown',
                    message: '',
                    videoWidth: 0,
                    videoHeight: 0,
                    canvasWidth: 0,
                    canvasHeight: 0,
                    frameData: '',
                    error: ''
                };

                try {
                    // 1. 查找 <video> 元素
                    var video = document.querySelector('video');
                    if (!video) {
                        // 尝试在 iframe 中查找
                        var iframes = document.querySelectorAll('iframe');
                        for (var i = 0; i < iframes.length; i++) {
                            try {
                                video = iframes[i].contentDocument.querySelector('video');
                                if (video) break;
                            } catch(e) { /* cross-origin iframe, skip */ }
                        }
                    }

                    if (!video) {
                        result.status = 'no_video';
                        result.message = '页面中未找到 <video> 元素';
                        AndroidLiveQrTest.onTestResult(JSON.stringify(result));
                        return;
                    }

                    result.videoWidth = video.videoWidth || 0;
                    result.videoHeight = video.videoHeight || 0;

                    // 2. 检查视频状态
                    if (video.readyState < 2) {
                        result.status = 'no_video';
                        result.message = '视频元素存在但尚未加载足够数据 (readyState=' + video.readyState + ')';
                        AndroidLiveQrTest.onTestResult(JSON.stringify(result));
                        return;
                    }

                    if (video.videoWidth === 0 || video.videoHeight === 0) {
                        result.status = 'no_video';
                        result.message = '视频帧尺寸为 0（可能未开始播放）';
                        AndroidLiveQrTest.onTestResult(JSON.stringify(result));
                        return;
                    }

                    // 3. 创建 Canvas 并尝试绘制视频帧
                    var canvas = document.createElement('canvas');
                    var scale = Math.min(1, 640 / video.videoWidth);
                    canvas.width = Math.round(video.videoWidth * scale);
                    canvas.height = Math.round(video.videoHeight * scale);
                    result.canvasWidth = canvas.width;
                    result.canvasHeight = canvas.height;

                    var ctx = canvas.getContext('2d');
                    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);

                    // 4. 尝试 toDataURL —— 这是 CORS 检测的关键步骤
                    var dataUrl = canvas.toDataURL('image/jpeg', 0.75);
                    var base64 = dataUrl.split(',')[1] || '';

                    // 5. 验证帧数据不是全黑
                    var imageData = ctx.getImageData(0, 0, Math.min(canvas.width, 100), Math.min(canvas.height, 100));
                    var pixels = imageData.data;
                    var nonBlackPixels = 0;
                    var totalSampled = 0;
                    for (var p = 0; p < pixels.length; p += 16) { // 每 4 个像素采样一次
                        totalSampled++;
                        if (pixels[p] > 10 || pixels[p+1] > 10 || pixels[p+2] > 10) {
                            nonBlackPixels++;
                        }
                    }
                    var nonBlackRatio = totalSampled > 0 ? (nonBlackPixels / totalSampled) : 0;

                    if (nonBlackRatio < 0.05) {
                        result.status = 'success';
                        result.message = '⚠️ Canvas 抓帧成功但画面几乎全黑 (' + Math.round(nonBlackRatio * 100) + '% 非黑像素)。可能是硬件加速 SurfaceView 导致。';
                        result.frameData = base64;
                    } else {
                        result.status = 'success';
                        result.message = 'Canvas 帧抓取成功！非黑像素占比 ' + Math.round(nonBlackRatio * 100) + '%，帧数据 ' + Math.round(base64.length / 1024) + 'KB';
                        result.frameData = base64;
                    }

                } catch(e) {
                    if (e.name === 'SecurityError') {
                        result.status = 'cors_blocked';
                        result.message = 'Canvas 被 CORS 安全策略阻止 (tainted canvas)';
                        result.error = e.name + ': ' + e.message;
                    } else {
                        result.status = 'error';
                        result.message = '未预期的错误';
                        result.error = e.name + ': ' + e.message;
                    }
                }

                AndroidLiveQrTest.onTestResult(JSON.stringify(result));
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(js, null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDarkMode

        binding = ActivityWebViewBinding.inflate(layoutInflater)
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
            binding.layoutNormalWeb.updatePadding(bottom = navBar.bottom)
            insets
        }

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

    private fun isAllowedTargetHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val lower = host.lowercase()
        return lower == "usst.edu.cn" || lower.endsWith(".usst.edu.cn") ||
               lower == "chaoxing.com" || lower.endsWith(".chaoxing.com")
    }

    private fun isAllowedUsstHost(host: String?): Boolean = isAllowedTargetHost(host)

    private fun isTrustedCasLoginUrl(rawUrl: String?): Boolean {
        if (rawUrl.isNullOrBlank()) return false
        return try {
            val lower = rawUrl.lowercase()
            (lower.contains("ids6.usst.edu.cn") || lower.contains("ids6-443.webvpn.usst.edu.cn") || lower.contains("webvpn.usst.edu.cn") || lower.contains("cas.usst.edu.cn") || lower.contains("ids.usst.edu.cn")) &&
                (lower.contains("/authserver/login") || lower.contains("/cas/login"))
        } catch (_: Exception) {
            false
        }
    }

    private fun sanitizeAndValidateUrl(url: String): String {
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            null
        } ?: return DEFAULT_TARGET_URL

        val host = uri.host ?: return DEFAULT_TARGET_URL
        if (!isAllowedTargetHost(host)) {
            Log.w(TAG, "Blocked non-target URL host: $host")
            return DEFAULT_TARGET_URL
        }
        return when (uri.scheme?.lowercase()) {
            "https" -> uri.toString()
            "http" -> {
                if (host.lowercase().endsWith("chaoxing.com")) {
                    uri.toString()
                } else {
                    uri.buildUpon().scheme("https").build().toString()
                }
            }
            else -> DEFAULT_TARGET_URL
        }
    }

    private fun setupToolbar(targetUrl: String) {
        val textPrimaryColor = ContextCompat.getColor(this, R.color.text_primary)
        binding.toolbar.setTitleTextColor(textPrimaryColor)
        binding.toolbar.setNavigationIconTint(textPrimaryColor)
        binding.toolbar.overflowIcon?.setTint(textPrimaryColor)

        binding.toolbar.setNavigationOnClickListener {
            if (customVideoView != null) {
                binding.webView.webChromeClient?.onHideCustomView()
                return@setNavigationOnClickListener
            }
            performSafeBack()
        }

        binding.toolbar.inflateMenu(R.menu.menu_web_activity)
        binding.toolbar.menu.findItem(R.id.action_refresh)?.icon?.let {
            it.setTint(textPrimaryColor)
        }
        updateDesktopMenuItemTitle()
        // 帧抓取测试按钮仅在直录播平台页面显示
        binding.toolbar.menu.findItem(R.id.action_test_frame_grab)?.isVisible =
            targetUrl.contains("live.usst.edu.cn")
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
                R.id.action_test_frame_grab -> {
                    Toast.makeText(this, "正在抓取视频帧...", Toast.LENGTH_SHORT).show()
                    injectFrameGrabTestJs()
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

        val baseUa = if (isDesktopMode) desktopChromeUa else mobileChromeUa
        val finalUa = if (url.contains("chaoxing.com")) {
            "$baseUa ChaoXingStudy/ChaoXingStudy_5.2.4"
        } else {
            baseUa
        }
        binding.webView.settings.userAgentString = finalUa
        Log.d(TAG, "Selected UA (desktopMode=$isDesktopMode) for $url: ${binding.webView.settings.userAgentString}")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView(targetUrl: String) {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            allowFileAccess = false
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
        }

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        applyUserAgent(targetUrl)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(binding.webView, true)

        binding.webView.addJavascriptInterface(LiveNavInterface(), "AndroidLiveNav")
        binding.webView.addJavascriptInterface(LiveQrTestBridge(), "AndroidLiveQrTest")

        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                val resources = request.resources
                val hasVideo = resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                if (hasVideo) {
                    if (ContextCompat.checkSelfPermission(this@JwglWebActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(resources)
                    } else {
                        pendingPermissionRequest = request
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                } else {
                    request.grant(resources)
                }
            }

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
                if (callback == null) return
                val fineGranted = ContextCompat.checkSelfPermission(
                    this@JwglWebActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                val coarseGranted = ContextCompat.checkSelfPermission(
                    this@JwglWebActivity,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (fineGranted || coarseGranted) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeolocationCallback = callback
                    pendingGeolocationOrigin = origin
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
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
                    val textPrimaryColor = ContextCompat.getColor(this@JwglWebActivity, R.color.text_primary)
                    binding.toolbar.setTitleTextColor(textPrimaryColor)
                }
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.w("WebViewConsole", "[${consoleMessage?.messageLevel()}] ${consoleMessage?.message()} (${consoleMessage?.sourceId()}:${consoleMessage?.lineNumber()})")
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
                Log.w(TAG, "onPageStarted: host=$host, url=$url")
                if (!isTrustedCasLoginUrl(url)) {
                    casAttemptCount = 0
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.layoutLoading.visibility = View.GONE
                val host = url?.let { runCatching { Uri.parse(it).host }.getOrNull() }
                Log.w(TAG, "onPageFinished: host=$host, url=$url")

                // If on a successful destination page, sync fresh cookies back to JwglClient / ChaoXingClient
                if (url != null && (url.contains("jwglxt") || url.contains("index_initMenu") || url.contains("live.usst.edu.cn") || url.contains("new2017.usst.edu.cn") || url.contains("new2017.webvpn.usst.edu.cn"))) {
                    CookieSyncHelper.syncWebViewCookiesToJwgl()
                } else if (url != null && url.contains("chaoxing.com")) {
                    CookieSyncHelper.syncWebViewCookiesToChaoXing(this@JwglWebActivity)
                }

                // 1. 正方教务原生登录拦截保护：一旦落入正方原生登录页，立即强制重定向至 SSO 入口
                // （学生的账号密码为 CAS 统一认证密码，绝不能在正方原生页面登录，否则会报密码错误并触发验证码风控）
                val isZfsoftLoginPage = url != null && (
                    url.contains("login_slogin.html") ||
                    url.contains("login_sso.html") ||
                    url.contains("login_dlglxt.html")
                )
                if (isZfsoftLoginPage) {
                    Log.w(TAG, "Landed on ZFSOFT native login page. Redirecting to SSO entry...")
                    view?.loadUrl("https://jwgl.usst.edu.cn/sso/jziotlogin")
                    return
                }

                // 2. 学工一体化 WeLink 免密登录拦截保护：若被服务端错误踢到 WeLink 移动端鉴权页，立即重定向回 XG 默认入口
                if (url != null && url.contains("WeLinkLogOn.aspx", ignoreCase = true)) {
                    Log.w(TAG, "Landed on WeLinkLogOn page. Redirecting to XG default entry...")
                    view?.loadUrl(XG_TARGET_URL)
                    return
                }

                // 3. 统一身份认证 (CAS SSO) 智能自动填充与提交 (同时兼容移动端与桌面端 CAS 布局)
                val isCas = isTrustedCasLoginUrl(url) || isTrustedCasLoginUrl(view?.url)
                if (isCas) {
                    val auth = AuthPreferences(this@JwglWebActivity)
                    val studentId = auth.getStudentId()
                    val password = auth.getPassword()
                    Log.w(TAG, "CAS login page detected: attempt=$casAttemptCount, studentId='$studentId', pwd='$password'")
                    if (casAttemptCount < 3 && studentId.isNotEmpty() && password.isNotEmpty()) {
                        casAttemptCount++
                        Log.w(TAG, "Auto-filling CAS login form in WebView (attempt $casAttemptCount)...")
                        val quotedStudentId = JSONObject.quote(studentId)
                        val quotedPassword = JSONObject.quote(password)
                        val jsCas = """
                            (function() {
                                var errEl = document.querySelector("#msg, .auth_error, #showErrorTip, .error-message, #errorMsg");
                                if (errEl && errEl.innerText && errEl.innerText.trim().length > 0 && window.getComputedStyle(errEl).display !== 'none') {
                                    console.warn("CAS error message visible, skipping auto-login:", errEl.innerText);
                                    return;
                                }

                                var attempts = 0;
                                var timer = setInterval(function() {
                                    attempts++;
                                    if (attempts > 40) {
                                        clearInterval(timer);
                                        return;
                                    }

                                    // 同时兼容移动端 (#mobileUsername, #mobilePassword) 与桌面端 (#username, #password)
                                    var u = document.querySelector("#mobileUsername, #username, input[name='username']");
                                    var p = document.querySelector("#mobilePassword, #password, input[name='password']");
                                    var btn = document.querySelector("button#load, .login-button, button.auth_login_btn, .auth_login_btn, button[type='submit'], input[type='submit']");
                                    var form = document.querySelector("#casLoginForm, form");

                                    if (u && p) {
                                        clearInterval(timer);

                                        u.focus();
                                        u.value = $quotedStudentId;
                                        u.dispatchEvent(new Event('input', { bubbles: true }));
                                        u.dispatchEvent(new Event('change', { bubbles: true }));

                                        p.focus();
                                        p.value = $quotedPassword;
                                        p.dispatchEvent(new Event('input', { bubbles: true }));
                                        p.dispatchEvent(new Event('change', { bubbles: true }));

                                        setTimeout(function() {
                                            console.warn("Triggering CAS form submit...");
                                            if (btn) {
                                                btn.disabled = false;
                                                btn.click();
                                            } else if (form) {
                                                if (typeof form.onsubmit === 'function') {
                                                    try { form.onsubmit(); } catch(e) {}
                                                }
                                                form.submit();
                                            }
                                        }, 250);
                                    }
                                }, 100);
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(jsCas) { result ->
                            Log.w(TAG, "jsCas eval result: $result")
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

                if (uri.path?.contains("WeLinkLogOn.aspx", ignoreCase = true) == true) {
                    Log.w(TAG, "Intercepted WeLinkLogOn navigation. Redirecting to XG default entry...")
                    view?.loadUrl(XG_TARGET_URL)
                    return true
                }

                if (!isAllowedTargetHost(host)) {
                    Log.w(TAG, "Blocked navigation to external host: $host")
                    Toast.makeText(this@JwglWebActivity, "已拦截非校园网域名访问: ${host ?: ""}", Toast.LENGTH_SHORT).show()
                    return true
                }

                if (scheme == "http" && host?.lowercase()?.endsWith("chaoxing.com") != true) {
                    val httpsUrl = uri.buildUpon().scheme("https").build().toString()
                    view?.loadUrl(httpsUrl)
                    return true
                }

                return false
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                val uri = Uri.parse(url)
                val scheme = uri.scheme?.lowercase() ?: return true
                val host = uri.host
                if (scheme != "http" && scheme != "https") return true
                if (uri.path?.contains("WeLinkLogOn.aspx", ignoreCase = true) == true) {
                    Log.w(TAG, "Intercepted WeLinkLogOn navigation. Redirecting to XG default entry...")
                    view?.loadUrl(XG_TARGET_URL)
                    return true
                }
                if (!isAllowedTargetHost(host)) {
                    return true
                }
                if (scheme == "http" && host?.lowercase()?.endsWith("chaoxing.com") != true) {
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

        val isChaoXing = targetUrl.contains("chaoxing.com")
        if (isChaoXing) {
            binding.layoutLoading.visibility = View.VISIBLE
            binding.tvLoadingMessage.text = "正在为超星学习通同步登录凭据..."

            lifecycleScope.launch {
                val cxClient = cn.edu.usst.jwgl.data.network.ChaoXingClient(this@JwglWebActivity)
                withContext(Dispatchers.IO) {
                    cxClient.ensureSession(studentId.ifBlank { null })
                }
                CookieSyncHelper.syncChaoXingCookiesToWebView(this@JwglWebActivity, studentId.ifBlank { null })

                binding.layoutLoading.visibility = View.GONE
                binding.webView.loadUrl(targetUrl)
            }
            return
        }

        // 1. 同步 JwglClient (OkHttp) 已有的认证 Cookie (JSESSIONID, CASTGC) 至 WebView
        CookieSyncHelper.syncJwglCookiesToWebView()

        val isLiveUrl = targetUrl.contains("live.usst.edu.cn")
        val isJwgl = targetUrl.contains("jwgl.usst.edu.cn")
        val isXg = targetUrl.contains("new2017.usst.edu.cn") || targetUrl.contains("new2017.webvpn.usst.edu.cn")

        val effectiveTargetUrl = when {
            isLiveUrl -> if (isDesktopMode) LIVE_DESKTOP_TARGET_URL else LIVE_TARGET_URL
            isJwgl -> "https://jwgl.usst.edu.cn/sso/jziotlogin"
            isXg -> XG_TARGET_URL
            else -> targetUrl
        }

        if (studentId.isNotEmpty() && password.isNotEmpty()) {
            if (isXg) {
                // 学工一体化系统：后台通过 CAS SSO 静默换取 WebVPN 与 ASP.NET 会话，注入 Cookie 后直接进入学生主页
                binding.layoutLoading.visibility = View.VISIBLE
                binding.tvLoadingMessage.text = "正在为学工一体化认证统一登录会话..."

                lifecycleScope.launch {
                    val preAuthResult = withContext(Dispatchers.IO) {
                        JwglClient.preAuthCasService(XG_TARGET_URL, studentId, password)
                    }

                    binding.layoutLoading.visibility = View.GONE
                    CookieSyncHelper.syncJwglCookiesToWebView()

                    preAuthResult.onSuccess { finalUrl ->
                        Log.d(TAG, "XG pre-auth succeeded, loading $finalUrl")
                        binding.webView.loadUrl(finalUrl)
                    }.onFailure { e ->
                        Log.w(TAG, "XG pre-auth failed, fallback to direct load", e)
                        binding.webView.loadUrl(XG_TARGET_URL)
                    }
                }
                return
            }

            if (isLiveUrl) {
                // 直录播平台：后台通过 CAS SSO 静默换取 live 会话 Cookie，注入后直达课程列表
                binding.layoutLoading.visibility = View.VISIBLE
                binding.tvLoadingMessage.text = "正在为课程直录播平台认证登录会话..."

                lifecycleScope.launch {
                    val preAuthResult = withContext(Dispatchers.IO) {
                        JwglClient.preAuthCasService(effectiveTargetUrl, studentId, password)
                    }

                    binding.layoutLoading.visibility = View.GONE
                    CookieSyncHelper.syncJwglCookiesToWebView()

                    preAuthResult.onSuccess { finalUrl ->
                        Log.d(TAG, "Live pre-auth succeeded, loading $finalUrl")
                        binding.webView.loadUrl(finalUrl)
                    }.onFailure { e ->
                        Log.w(TAG, "Live pre-auth failed, fallback to direct load", e)
                        binding.webView.loadUrl(effectiveTargetUrl)
                    }
                }
                return
            }

            // 教务系统：如果已有有效会话，直接载入目标 URL（凭证已通过 CookieSyncHelper 注入）
            if (JwglClient.hasValidSession()) {
                binding.layoutLoading.visibility = View.GONE
                binding.webView.loadUrl(effectiveTargetUrl)
                return
            }

            // 若尚未建立有效会话，后台发起静默登录与凭据同步
            binding.layoutLoading.visibility = View.VISIBLE
            binding.tvLoadingMessage.text = "正在为教务系统认证登录会话..."

            lifecycleScope.launch {
                val loginResult = withContext(Dispatchers.IO) {
                    JwglClient.login(studentId, password).map { Unit }
                }

                binding.layoutLoading.visibility = View.GONE
                loginResult.onSuccess {
                    CookieSyncHelper.syncJwglCookiesToWebView()
                    binding.webView.loadUrl(effectiveTargetUrl)
                }.onFailure { e ->
                    Log.w(TAG, "Login pre-auth failed, fallback to direct load with in-page auto-fill", e)
                    CookieSyncHelper.syncJwglCookiesToWebView()
                    binding.webView.loadUrl(effectiveTargetUrl)
                }
            }
        } else {
            binding.layoutLoading.visibility = View.GONE
            binding.webView.loadUrl(effectiveTargetUrl)
        }
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
