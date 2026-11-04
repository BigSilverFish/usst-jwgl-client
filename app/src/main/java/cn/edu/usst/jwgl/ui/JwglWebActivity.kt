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
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import cn.edu.usst.jwgl.BuildConfig
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.ActivityWebViewBinding
import cn.edu.usst.jwgl.util.CookieSyncHelper
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    // ==========================================
    // 🎯 直录播大屏采集卡 (PPT) 视角自动签到联动状态
    // ==========================================
    private var isScreenAutoSignInEnabled = true
    private var isPptScanningActive = false
    private var pptScanJob: Job? = null
    private var liveRollcallWatcherJob: Job? = null
    private var lastHandledRollcallId: Long = -1L
    private var activeRollcallForCapture: TronClassRollcall? = null

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
     * 🧪 视频帧抓取测试桥 —— 验证能否通过 JS Canvas 从直播多机位 <video> 元素截取帧画面
     *
     * 支持全机位多视频探测与定向 PPT 采集卡抓取：
     * 1. 扫描页面上所有的 <video> 元素（主屏、副屏、画中画、iframe）
     * 2. 定向捕获 PPT 采集卡视角的高清帧（1280 宽）
     * 3. 驱动 ML Kit 识别二维码并自动完成 TronClass 签到闭环
     */
    inner class LiveQrTestBridge {
        @android.webkit.JavascriptInterface
        fun onTestResult(jsonResult: String) {
            runOnUiThread {
                processTestResult(jsonResult)
            }
        }

        @android.webkit.JavascriptInterface
        fun onPptFrameCaptured(jsonResult: String) {
            runOnUiThread {
                handlePptFrame(jsonResult)
            }
        }
    }

    // =========================================================================
    // 🎯 直录播大屏采集卡 (PPT) 视角事件驱动自动签到核心联动系统
    // =========================================================================

    /**
     * 启动直录播签到协同监听器（每 12 秒探测一次是否有正在进行的课堂签到）
     * 仅在检测到有签到活动时才唤醒高频抓帧，平时完全 0 额外功耗
     */
    private fun startLiveRollcallWatcher() {
        liveRollcallWatcherJob?.cancel()
        if (!isScreenAutoSignInEnabled) return

        val authPrefs = AuthPreferences(this)
        val studentId = authPrefs.getStudentId()
        if (studentId.isBlank()) return

        Log.d(TAG, "Starting Live Rollcall Watcher for student: $studentId")
        liveRollcallWatcherJob = lifecycleScope.launch(Dispatchers.IO) {
            val tcClient = TronClassClient(this@JwglWebActivity)
            while (isActive && isScreenAutoSignInEnabled) {
                try {
                    val result = tcClient.fetchOngoingRollcalls()
                    result.onSuccess { rollcalls ->
                        // 寻找尚未处理过的活跃签到
                        val active = rollcalls.firstOrNull { it.id != lastHandledRollcallId }
                        if (active != null) {
                            Log.i(TAG, "Active rollcall detected: id=${active.id}, course=${active.courseName}, type=${active.type}")
                            withContext(Dispatchers.Main) {
                                onRollcallTriggered(active)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error checking ongoing rollcalls", e)
                }
                delay(12_000L)
            }
        }
    }

    private fun stopLiveRollcallWatcher() {
        liveRollcallWatcherJob?.cancel()
        liveRollcallWatcherJob = null
        stopPptScanningLoop()
    }

    /**
     * 捕捉到了签到事件：展开悬浮感知横幅并唤醒定向抓帧引擎
     */
    private fun onRollcallTriggered(rollcall: TronClassRollcall) {
        if (isPptScanningActive && activeRollcallForCapture?.id == rollcall.id) return

        activeRollcallForCapture = rollcall
        binding.cardRollcallBanner.visibility = View.VISIBLE
        binding.pbRollcallScanning.visibility = View.VISIBLE
        binding.ivRollcallStatusIcon.visibility = View.GONE
        val courseTitle = rollcall.courseName?.takeIf { it.isNotBlank() } ?: "当前课堂"
        binding.tvRollcallBannerTitle.text = "🔔 检测到【$courseTitle】开启签到，正在从大屏采集卡识别二维码..."

        startPptScanningLoop(rollcall)
    }

    /**
     * 开启高频抓帧循环（每 1.2 秒截取一次 PPT 视角画面，最长 90 秒超时保护）
     */
    private fun startPptScanningLoop(rollcall: TronClassRollcall?) {
        pptScanJob?.cancel()
        isPptScanningActive = true
        activeRollcallForCapture = rollcall

        pptScanJob = lifecycleScope.launch {
            var attempts = 0
            val maxAttempts = 75 // ~90 秒
            while (isActive && isPptScanningActive && attempts < maxAttempts) {
                attempts++
                injectPptFrameCaptureJs(isManual = false)
                delay(1200L)
            }

            if (isPptScanningActive) {
                isPptScanningActive = false
                binding.cardRollcallBanner.visibility = View.GONE
                Toast.makeText(this@JwglWebActivity, "未能从大屏画面中识别到有效二维码，已恢复静默监控", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun stopPptScanningLoop() {
        isPptScanningActive = false
        pptScanJob?.cancel()
        pptScanJob = null
    }

    /**
     * 手动立刻捕捉大屏画面并识别签到（支持直播与点播回放）
     */
    private fun capturePptFrameNow() {
        Toast.makeText(this, "正在从大屏采集卡画面识别二维码...", Toast.LENGTH_SHORT).show()
        binding.cardRollcallBanner.visibility = View.VISIBLE
        binding.pbRollcallScanning.visibility = View.VISIBLE
        binding.ivRollcallStatusIcon.visibility = View.GONE
        binding.tvRollcallBannerTitle.text = "⚡ 正在从大屏采集卡捕获二维码..."
        injectPptFrameCaptureJs(isManual = true)
    }

    /**
     * 处理捕获到的大屏视频帧并调用 ML Kit 识别二维码
     */
    private fun handlePptFrame(jsonResult: String) {
        try {
            val json = JSONObject(jsonResult)
            val isManual = json.optBoolean("isManual", false)
            val status = json.optString("status", "unknown")
            val message = json.optString("message", "")
            val framesArray = json.optJSONArray("frames")

            if (status != "success" || framesArray == null || framesArray.length() == 0) {
                if (isManual) {
                    binding.cardRollcallBanner.visibility = View.GONE
                    Toast.makeText(this, "大屏抓帧未能就绪: $message", Toast.LENGTH_LONG).show()
                }
                return
            }

            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
                    .build()
            )

            var qrFound = false
            val totalFrames = framesArray.length()
            var processedCount = 0

            for (i in 0 until totalFrames) {
                val fObj = framesArray.getJSONObject(i)
                val base64 = fObj.optString("base64", "")
                val label = fObj.optString("label", "视窗")

                if (base64.isBlank()) {
                    processedCount++
                    continue
                }

                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap == null) {
                    processedCount++
                    continue
                }

                val image = InputImage.fromBitmap(bitmap, 0)
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        processedCount++
                        if (!qrFound && barcodes.isNotEmpty()) {
                            val rawQr = barcodes.firstOrNull()?.rawValue
                            if (!rawQr.isNullOrBlank()) {
                                qrFound = true
                                Log.i(TAG, "QR detected in $label: $rawQr")
                                processScannedQrCode(rawQr)
                            }
                        }

                        if (processedCount >= totalFrames) {
                            scanner.close()
                            if (!qrFound && isManual) {
                                binding.cardRollcallBanner.visibility = View.GONE
                                Toast.makeText(
                                    this,
                                    "已成功捕获 $totalFrames 个画面帧，但当前画面未出现签到二维码（请确认画面中有签到码）",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                    .addOnFailureListener {
                        processedCount++
                        if (processedCount >= totalFrames) {
                            scanner.close()
                            if (!qrFound && isManual) {
                                binding.cardRollcallBanner.visibility = View.GONE
                                Toast.makeText(this, "二维码识别扫描失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling PPT frame JSON: $jsonResult", e)
        }
    }

    /**
     * 解析二维码数据并自动提交 TronClass 签到
     */
    private fun processScannedQrCode(rawQr: String) {
        val tcClient = TronClassClient(this)
        val parsed = tcClient.parseRollcallQr(rawQr)
        val rollcallId = parsed?.rollcallId ?: activeRollcallForCapture?.id

        if (rollcallId == null || parsed == null) {
            Log.w(TAG, "Unrecognized QR format from live screen: $rawQr")
            return
        }

        // 已经处理过同一个签到，直接忽略防止重复提交
        if (rollcallId == lastHandledRollcallId) {
            stopPptScanningLoop()
            binding.cardRollcallBanner.visibility = View.GONE
            return
        }

        // 成功捕获有效二维码，立即停止抓帧！
        stopPptScanningLoop()
        lastHandledRollcallId = rollcallId

        // 触觉反馈
        vibratePhone()

        // 界面更新为提交中
        binding.pbRollcallScanning.visibility = View.VISIBLE
        binding.ivRollcallStatusIcon.visibility = View.GONE
        binding.tvRollcallBannerTitle.text = "🎯 已捕获大屏二维码，正在自动提交签到..."

        lifecycleScope.launch(Dispatchers.IO) {
            val results = tcClient.batchAnswerQRRollcall(rollcallId, parsed.qrData)
            val anySuccess = results.any { it.success }
            val firstMsg = results.firstOrNull()?.message ?: ""
            withContext(Dispatchers.Main) {
                if (anySuccess) {
                    binding.pbRollcallScanning.visibility = View.GONE
                    binding.ivRollcallStatusIcon.visibility = View.VISIBLE
                    binding.tvRollcallBannerTitle.text = "🎉 签到成功！已通过大屏采集卡完成签到"
                    Toast.makeText(this@JwglWebActivity, "🎉 大屏扫码签到成功！", Toast.LENGTH_LONG).show()

                    // 5 秒后优雅隐藏横幅
                    binding.cardRollcallBanner.postDelayed({
                        binding.cardRollcallBanner.visibility = View.GONE
                    }, 5000L)
                } else {
                    binding.pbRollcallScanning.visibility = View.GONE
                    val errDetail = if (firstMsg.isNotBlank()) ": $firstMsg" else ""
                    binding.tvRollcallBannerTitle.text = "⚠️ 签到提交未通过$errDetail"
                    Toast.makeText(this@JwglWebActivity, "签到提交未通过$errDetail", Toast.LENGTH_LONG).show()

                    binding.cardRollcallBanner.postDelayed({
                        binding.cardRollcallBanner.visibility = View.GONE
                    }, 4000L)
                }
            }
        }
    }

    private fun vibratePhone() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(200)
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * 定向抓取 PPT 采集卡与活动视窗的高清视频帧（1280 宽超清画质，支持直播与点播回放）
     */
    private fun injectPptFrameCaptureJs(isManual: Boolean) {
        val js = """
            (function() {
                var payload = {
                    isManual: $isManual,
                    status: 'unknown',
                    message: '',
                    frames: []
                };

                try {
                    var videos = Array.from(document.querySelectorAll('video'));
                    var iframes = document.querySelectorAll('iframe');
                    for (var f = 0; f < iframes.length; f++) {
                        try {
                            var fVideos = iframes[f].contentDocument.querySelectorAll('video');
                            for (var fv = 0; fv < fVideos.length; fv++) {
                                videos.push(fVideos[fv]);
                            }
                        } catch(e) {}
                    }

                    if (videos.length === 0) {
                        payload.status = 'no_video';
                        payload.message = '页面中未找到任何 <video> 标签 (视频可能尚未初始化)';
                        AndroidLiveQrTest.onPptFrameCaptured(JSON.stringify(payload));
                        return;
                    }

                    // 收集所有具备实际像素宽度的视频（不管在 playing 还是 paused 状态）
                    var validVideos = videos.filter(function(v) { return (v.videoWidth || 0) > 0; });
                    if (validVideos.length === 0) {
                        payload.status = 'not_ready';
                        payload.message = '检测到 ' + videos.length + ' 个视频元素，但尚未解码出画面 (videoWidth=0，可能正在缓冲或未开始播放)';
                        AndroidLiveQrTest.onPptFrameCaptured(JSON.stringify(payload));
                        return;
                    }

                    // 检查页面 Tab 是否处于 PPT 状态
                    var tabs = Array.from(document.querySelectorAll('.van-tab, .tab, button, span, div, a'));
                    var pptTab = tabs.find(function(t) { return t.innerText && t.innerText.trim() === 'PPT'; });
                    var isPptTabActive = pptTab && (
                        pptTab.classList.contains('van-tab--active') ||
                        pptTab.classList.contains('active') ||
                        pptTab.getAttribute('aria-selected') === 'true'
                    );

                    for (var i = 0; i < validVideos.length; i++) {
                        var v = validVideos[i];
                        var vWidth = v.videoWidth;
                        var vHeight = v.videoHeight;
                        var rect = v.getBoundingClientRect();

                        var isLikelyPpt = false;
                        var label = '机位 ' + (i + 1);

                        if (isPptTabActive && rect.width >= 250) {
                            isLikelyPpt = true;
                            label += ' (PPT主视窗)';
                        } else if (rect.width > 0 && rect.width < 320) {
                            isLikelyPpt = true;
                            label += ' (画中画采集卡)';
                        } else if (validVideos.length === 1) {
                            isLikelyPpt = true;
                            label += ' (回放主视窗)';
                        }

                        try {
                            // 以 1280 宽高质量导出
                            var scale = Math.min(1, 1280 / vWidth);
                            var canvas = document.createElement('canvas');
                            canvas.width = Math.round(vWidth * scale);
                            canvas.height = Math.round(vHeight * scale);
                            var ctx = canvas.getContext('2d');
                            ctx.drawImage(v, 0, 0, canvas.width, canvas.height);

                            var dataUrl = canvas.toDataURL('image/jpeg', 0.90);
                            var base64 = dataUrl.split(',')[1] || '';

                            if (base64.length > 500) {
                                payload.frames.push({
                                    label: label,
                                    isLikelyPpt: isLikelyPpt,
                                    width: canvas.width,
                                    height: canvas.height,
                                    base64: base64
                                });
                            }
                        } catch(drawErr) {
                            // 单个视窗受限不阻断其他视窗
                        }
                    }

                    if (payload.frames.length > 0) {
                        // 优先扫描最可能是 PPT/采集卡的画面
                        payload.frames.sort(function(a, b) {
                            return (b.isLikelyPpt ? 1 : 0) - (a.isLikelyPpt ? 1 : 0);
                        });
                        payload.status = 'success';
                        payload.message = '成功抓取 ' + payload.frames.length + ' 个视窗画面';
                    } else {
                        payload.status = 'canvas_error';
                        payload.message = 'Canvas 绘制视频画面失败';
                    }

                } catch(globalErr) {
                    payload.status = 'error';
                    payload.message = globalErr.name + ': ' + globalErr.message;
                }

                AndroidLiveQrTest.onPptFrameCaptured(JSON.stringify(payload));
            })();
        """.trimIndent()

        binding.webView.evaluateJavascript(js, null)
    }

    private fun processTestResult(jsonResult: String) {
        try {
            val json = JSONObject(jsonResult)
            val pageViewsInfo = json.optString("pageViewsInfo", "")
            val targetsArray = json.optJSONArray("targets")

            val report = StringBuilder()
            report.appendLine("━━━ 直录播多机位帧抓取诊断报告 ━━━")
            report.appendLine()
            if (pageViewsInfo.isNotEmpty()) {
                report.appendLine("📌 页面机位导航标识: $pageViewsInfo")
                report.appendLine()
            }

            if (targetsArray == null || targetsArray.length() == 0) {
                val errorMsg = json.optString("error", "页面中未找到任何正在播放的 <video> 元素")
                report.appendLine("❌ 未检测到视频流: $errorMsg")
                report.appendLine("请确认直录播视频已经开始播放，并选择清晰的课程画面后再试。")
                showTestResultDialog(report.toString())
                return
            }

            val totalVideos = targetsArray.length()
            report.appendLine("🎬 检测到 $totalVideos 个视频流画面：")
            report.appendLine()

            // 存储待异步 ML Kit 扫描的任务
            data class ScanTask(
                val index: Int,
                val label: String,
                val isPptGuess: Boolean,
                val videoRes: String,
                val canvasRes: String,
                val nonBlackPercent: Int,
                val status: String,
                val message: String,
                val bitmap: Bitmap?
            )

            val tasks = mutableListOf<ScanTask>()

            for (i in 0 until totalVideos) {
                val item = targetsArray.getJSONObject(i)
                val label = item.optString("label", "机位 ${i + 1}")
                val isPptGuess = item.optBoolean("isPptGuess", false)
                val status = item.optString("status", "unknown")
                val message = item.optString("message", "")
                val vWidth = item.optInt("videoWidth", 0)
                val vHeight = item.optInt("videoHeight", 0)
                val cWidth = item.optInt("canvasWidth", 0)
                val cHeight = item.optInt("canvasHeight", 0)
                val nonBlack = item.optInt("nonBlackPercent", 0)
                val base64Data = item.optString("frameData", "")

                var decodedBitmap: Bitmap? = null
                if (status == "success" && base64Data.isNotEmpty()) {
                    try {
                        val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                        decodedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (e: Exception) {
                        Log.e(TAG, "Decode bitmap error for $label", e)
                    }
                }

                tasks.add(
                    ScanTask(
                        index = i + 1,
                        label = label,
                        isPptGuess = isPptGuess,
                        videoRes = "${vWidth}×${vHeight}",
                        canvasRes = "${cWidth}×${cHeight}",
                        nonBlackPercent = nonBlack,
                        status = status,
                        message = message,
                        bitmap = decodedBitmap
                    )
                )
            }

            // 收集所有扫描结果
            val scanResults = mutableMapOf<Int, List<String>>()
            var pendingScans = tasks.count { it.bitmap != null }

            fun finishAndShowDialog() {
                val finalReport = StringBuilder(report)
                var hasQrFoundTotal = false

                tasks.forEach { task ->
                    val pptTag = if (task.isPptGuess) " [🌟可能是PPT/电脑画面]" else ""
                    finalReport.appendLine("▶ 【${task.label}$pptTag】")
                    finalReport.appendLine("   • 原始分辨率: ${task.videoRes} | 画布: ${task.canvasRes}")
                    finalReport.appendLine("   • 非黑像素率: ${task.nonBlackPercent}%")

                    if (task.status == "success") {
                        finalReport.appendLine("   • 抓帧结果: ✅ 成功 (${task.message})")
                        val qrList = scanResults[task.index]
                        if (!qrList.isNullOrEmpty()) {
                            hasQrFoundTotal = true
                            finalReport.appendLine("   • 二维码识别: 🎉 成功检测到 ${qrList.size} 个二维码！")
                            qrList.forEachIndexed { qIdx, content ->
                                finalReport.appendLine("     (${qIdx + 1}) $content")
                            }
                        } else {
                            finalReport.appendLine("   • 二维码识别: 📭 当前帧未识别到二维码")
                        }
                    } else if (task.status == "cors_blocked") {
                        finalReport.appendLine("   • 抓帧结果: ❌ CORS 跨域阻止 (Tainted Canvas)")
                        finalReport.appendLine("     说明: 直播流不同源，浏览器阻止 Canvas 像素读取")
                    } else {
                        finalReport.appendLine("   • 抓帧结果: ❌ 失败 (${task.message})")
                    }
                    finalReport.appendLine()
                }

                finalReport.appendLine("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                if (hasQrFoundTotal) {
                    finalReport.appendLine("💡 结论: 完美！已从视频流中直接解析到签到二维码！无需脱壳原生化即可自动签到！")
                } else {
                    val anySuccess = tasks.any { it.status == "success" && it.nonBlackPercent > 20 }
                    if (anySuccess) {
                        finalReport.appendLine("💡 结论: 视频帧抓取通道完全畅通！当前画面若无签到码，可切到老师展示签到二维码的片段再测。")
                    } else {
                        finalReport.appendLine("💡 建议: 若画面全黑或受 CORS 限制，需采用提取视频流原生解码方案。")
                    }
                }

                showTestResultDialog(finalReport.toString())
            }

            if (pendingScans == 0) {
                finishAndShowDialog()
                return
            }

            // 逐个扫描各机位
            val scanner = BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
                    .build()
            )

            tasks.forEach { task ->
                val bmp = task.bitmap
                if (bmp != null) {
                    val inputImage = InputImage.fromBitmap(bmp, 0)
                    scanner.process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            if (barcodes.isNotEmpty()) {
                                scanResults[task.index] = barcodes.map { b ->
                                    val raw = b.rawValue ?: "(null)"
                                    if (raw.length > 120) raw.take(120) + "..." else raw
                                }
                            }
                            pendingScans--
                            if (pendingScans <= 0) {
                                scanner.close()
                                finishAndShowDialog()
                            }
                        }
                        .addOnFailureListener {
                            pendingScans--
                            if (pendingScans <= 0) {
                                scanner.close()
                                finishAndShowDialog()
                            }
                        }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse multi test result", e)
            Toast.makeText(this, "多机位测试结果解析失败: ${e.message}", Toast.LENGTH_LONG).show()
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
                var payload = {
                    pageViewsInfo: '',
                    targets: [],
                    error: ''
                };

                try {
                    // 1. 扫描页面中的机位切换按钮或标识文字（如 "PPT", "课件", "教师", "教师1", "大屏"）
                    var viewKeywords = ['PPT', 'ppt', '课件', '电脑', '大屏', '教师', '全景', '机位', '板书', '电影'];
                    var foundViewLabels = [];
                    var allElements = document.querySelectorAll('button, .van-tab, .tab, .btn, span, div, a');
                    for (var e = 0; e < allElements.length; e++) {
                        var el = allElements[e];
                        if (el.children.length === 0 && el.innerText) {
                            var t = el.innerText.trim();
                            for (var k = 0; k < viewKeywords.length; k++) {
                                if (t.indexOf(viewKeywords[k]) !== -1 && t.length <= 15) {
                                    if (foundViewLabels.indexOf(t) === -1) {
                                        var isCurrentActive = el.classList.contains('active') ||
                                                              el.classList.contains('van-tab--active') ||
                                                              el.getAttribute('aria-selected') === 'true';
                                        foundViewLabels.push(t + (isCurrentActive ? '(当前选中)' : ''));
                                    }
                                    break;
                                }
                            }
                        }
                    }
                    if (foundViewLabels.length > 0) {
                        payload.pageViewsInfo = foundViewLabels.join(' | ');
                    }

                    // 2. 收集所有的 <video> 元素（包括顶级页面与同源 iframe）
                    var videoList = Array.from(document.querySelectorAll('video'));
                    var iframes = document.querySelectorAll('iframe');
                    for (var f = 0; f < iframes.length; f++) {
                        try {
                            var fVideos = iframes[f].contentDocument.querySelectorAll('video');
                            for (var fv = 0; fv < fVideos.length; fv++) {
                                videoList.push(fVideos[fv]);
                            }
                        } catch(err) { /* 跨域 iframe 忽略 */ }
                    }

                    if (videoList.length === 0) {
                        payload.error = '页面中未找到任何 <video> 标签（可能尚未加载播放器或处于纯音频状态）';
                        AndroidLiveQrTest.onTestResult(JSON.stringify(payload));
                        return;
                    }

                    // 3. 针对每个 video 进行特征分析与帧抓取
                    for (var i = 0; i < videoList.length; i++) {
                        var v = videoList[i];
                        var rect = v.getBoundingClientRect();
                        var vWidth = v.videoWidth || 0;
                        var vHeight = v.videoHeight || 0;
                        var isPaused = v.paused;
                        var readyState = v.readyState;

                        // 智能推测机位标签
                        var label = '机位 ' + (i + 1);
                        var isPptGuess = false;

                        // 根据类名、父容器、附近文本推测
                        var vAttrStr = (v.id + ' ' + v.className + ' ' + (v.src || '')).toLowerCase();
                        var parentText = (v.parentElement ? v.parentElement.innerText || '' : '').toLowerCase();

                        if (vAttrStr.indexOf('ppt') !== -1 || vAttrStr.indexOf('screen') !== -1 ||
                            vAttrStr.indexOf('vga') !== -1 || vAttrStr.indexOf('hdmi') !== -1 ||
                            parentText.indexOf('ppt') !== -1 || parentText.indexOf('电脑') !== -1) {
                            label += ' (PPT/电脑采集卡)';
                            isPptGuess = true;
                        } else if (vAttrStr.indexOf('teacher') !== -1 || parentText.indexOf('教师') !== -1) {
                            label += ' (教师摄像机)';
                        } else if (rect.width > 0 && rect.width < 250 && rect.height < 200) {
                            label += ' (小画中画/副视窗)';
                        } else if (rect.width >= 250) {
                            label += ' (主视窗)';
                        }

                        // 如果页面选中的机位包含 PPT，且当前 video 为主窗口
                        if (!isPptGuess && payload.pageViewsInfo.indexOf('PPT(当前选中)') !== -1 && rect.width >= 250) {
                            label += ' [当前已切至PPT]';
                            isPptGuess = true;
                        }

                        var item = {
                            id: i + 1,
                            label: label,
                            isPptGuess: isPptGuess,
                            videoWidth: vWidth,
                            videoHeight: vHeight,
                            canvasWidth: 0,
                            canvasHeight: 0,
                            nonBlackPercent: 0,
                            status: 'unknown',
                            message: '',
                            frameData: ''
                        };

                        if (readyState < 2) {
                            item.status = 'no_data';
                            item.message = '视频流正在缓冲或未就绪 (readyState=' + readyState + ')';
                            payload.targets.push(item);
                            continue;
                        }

                        if (vWidth === 0 || vHeight === 0) {
                            item.status = 'zero_dimension';
                            item.message = '视频分辨率为 0 (尚未解码出首帧或暂停中)';
                            payload.targets.push(item);
                            continue;
                        }

                        try {
                            // 创建 Canvas 并缩放截取（最大宽 720，保证二维码清晰度且数据量适中）
                            var canvas = document.createElement('canvas');
                            var scale = Math.min(1, 720 / vWidth);
                            canvas.width = Math.round(vWidth * scale);
                            canvas.height = Math.round(vHeight * scale);
                            item.canvasWidth = canvas.width;
                            item.canvasHeight = canvas.height;

                            var ctx = canvas.getContext('2d');
                            ctx.drawImage(v, 0, 0, canvas.width, canvas.height);

                            // 导出为 JPEG Base64
                            var dataUrl = canvas.toDataURL('image/jpeg', 0.85);
                            var base64 = dataUrl.split(',')[1] || '';
                            item.frameData = base64;

                            // 采样检验是否全黑
                            var sampleW = Math.min(canvas.width, 100);
                            var sampleH = Math.min(canvas.height, 100);
                            var imgData = ctx.getImageData(0, 0, sampleW, sampleH);
                            var d = imgData.data;
                            var nonBlackCount = 0;
                            var totalSamples = 0;
                            for (var p = 0; p < d.length; p += 16) {
                                totalSamples++;
                                if (d[p] > 12 || d[p+1] > 12 || d[p+2] > 12) {
                                    nonBlackCount++;
                                }
                            }
                            var nonBlackRatio = totalSamples > 0 ? (nonBlackCount / totalSamples) : 0;
                            item.nonBlackPercent = Math.round(nonBlackRatio * 100);

                            if (nonBlackRatio < 0.05) {
                                item.status = 'success';
                                item.message = '抓帧成功但画面接近全黑 (' + item.nonBlackPercent + '%)，可能走硬件 SurfaceView 解码';
                            } else {
                                item.status = 'success';
                                item.message = '抓帧成功 (' + Math.round(base64.length / 1024) + 'KB)';
                            }

                        } catch(err) {
                            if (err.name === 'SecurityError') {
                                item.status = 'cors_blocked';
                                item.message = 'CORS 跨域阻止 (Tainted Canvas)';
                            } else {
                                item.status = 'error';
                                item.message = err.name + ': ' + err.message;
                            }
                        }

                        payload.targets.push(item);
                    }

                } catch(globalErr) {
                    payload.error = '全局异常: ' + globalErr.message;
                }

                AndroidLiveQrTest.onTestResult(JSON.stringify(payload));
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

        val isLiveUrl = targetUrl.contains("live.usst.edu.cn")
        binding.toolbar.menu.findItem(R.id.action_toggle_screen_signin)?.apply {
            isVisible = isLiveUrl
            title = if (isScreenAutoSignInEnabled) "🎯 大屏自动签到 (已开启)" else "🎯 大屏自动签到 (已暂停)"
        }
        binding.toolbar.menu.findItem(R.id.action_capture_screen_now)?.isVisible = isLiveUrl
        binding.toolbar.menu.findItem(R.id.action_test_frame_grab)?.isVisible = isLiveUrl

        // 悬浮横幅关闭按钮
        binding.btnRollcallBannerClose.setOnClickListener {
            stopPptScanningLoop()
            binding.cardRollcallBanner.visibility = View.GONE
        }

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
                R.id.action_toggle_screen_signin -> {
                    isScreenAutoSignInEnabled = !isScreenAutoSignInEnabled
                    item.title = if (isScreenAutoSignInEnabled) "🎯 大屏自动签到 (已开启)" else "🎯 大屏自动签到 (已暂停)"
                    if (isScreenAutoSignInEnabled) {
                        startLiveRollcallWatcher()
                        Toast.makeText(this, "已开启大屏签到感知：捕捉到老师发起签到时将自动从大屏扫码", Toast.LENGTH_SHORT).show()
                    } else {
                        stopLiveRollcallWatcher()
                        binding.cardRollcallBanner.visibility = View.GONE
                        Toast.makeText(this, "已暂停大屏签到感知", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.action_capture_screen_now -> {
                    capturePptFrameNow()
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
                if (url != null && url.contains("live.usst.edu.cn")) {
                    startLiveRollcallWatcher()
                    if (!searchQuery.isNullOrBlank() || !targetDate.isNullOrBlank()) {
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
        stopLiveRollcallWatcher()
        if (customVideoView != null) {
            binding.webView.webChromeClient?.onHideCustomView()
        }
        binding.webView.stopLoading()
        binding.webView.destroy()
        super.onDestroy()
    }
}
