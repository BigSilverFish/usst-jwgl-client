package cn.edu.usst.jwgl.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.view.View
import android.webkit.WebView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.databinding.ActivityWebViewBinding
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.BuildConfig
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.model.MultiAccountRollcallResult
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.data.network.RollcallPollingSource
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.util.DeveloperModeManager
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
import org.json.JSONObject

/**
 * 直播课 PPT 大屏抓帧与 ML Kit 二维码自动签到控制器
 */
internal class LiveRollcallWebController(
    private val activity: AppCompatActivity,
    private val binding: ActivityWebViewBinding,
    private val onSetupNumberRollcallFab: (Long, String?) -> Unit
) {
    companion object {
        private const val TAG = "LiveRollcallWebCtrl"
    }

    var isScreenAutoSignInEnabled = true
    private var isPptScanningActive = false
    private var pptScanJob: Job? = null
    private var liveRollcallWatcherJob: Job? = null
    private var lastHandledRollcallId: Long = -1L
    private var isSubmittingQr: Boolean = false
    private var activeRollcallForCapture: TronClassRollcall? = null

    inner class LiveQrTestBridge {
        @JavascriptInterface
        fun onTestResult(jsonResult: String) {
            activity.runOnUiThread {
                processTestResult(jsonResult)
            }
        }

        @JavascriptInterface
        fun onPptFrameCaptured(jsonResult: String) {
            activity.runOnUiThread {
                handlePptFrame(jsonResult)
            }
        }
    }

    fun createJsBridge(): LiveQrTestBridge = LiveQrTestBridge()

    /**
     * 启动直录播签到协同监听器（每 12 秒探测一次是否有正在进行的课堂签到）
     * 仅在检测到有签到活动时才唤醒高频抓帧，平时完全 0 额外功耗
     */
    fun startLiveRollcallWatcher() {
        liveRollcallWatcherJob?.cancel()
        if (!isScreenAutoSignInEnabled) return

        val authPrefs = AuthPreferences(activity)
        val studentId = authPrefs.getStudentId()
        if (studentId.isBlank()) return

        Log.d(TAG, "Starting Live Rollcall Watcher for student: $studentId via RollcallPollingSource")
        liveRollcallWatcherJob = activity.lifecycleScope.launch {
            RollcallPollingSource.getInstance(activity)
                .observe(intervalMillis = 12_000L, enabled = { isScreenAutoSignInEnabled })
                .collect { result ->
                    result.onSuccess { rollcalls ->
                        val active = rollcalls.firstOrNull { it.id != lastHandledRollcallId }
                        if (active != null) {
                            Log.i(TAG, "Active rollcall detected: id=${active.id}, course=${active.courseName}, type=${active.type}")
                            onRollcallTriggered(active)
                        }
                    }.onFailure { e ->
                        Log.w(TAG, "Error checking ongoing rollcalls", e)
                    }
                }
        }
    }

    fun stopLiveRollcallWatcher() {
        liveRollcallWatcherJob?.cancel()
        liveRollcallWatcherJob = null
        stopPptScanningLoop()
    }

    /**
     * 捕捉到了签到事件：根据签到类型智能调度（仅限二维码签到才唤醒大屏识码引擎）
     */
    private fun onRollcallTriggered(rollcall: TronClassRollcall) {
        val normType = rollcall.getNormalizedType()
        val courseTitle = rollcall.courseName?.takeIf { it.isNotBlank() } ?: "当前课堂"
        val isNumberFabMode = activity.intent.getBooleanExtra(JwglWebActivity.EXTRA_ENABLE_LIVE_NUMBER_FAB, false)

        // 🎯 核心控制：如果是数字口令签到或处于数字联动模式，绝对不启动大屏二维码抓帧！
        if (normType == "number" || isNumberFabMode) {
            Log.i(TAG, "Rollcall is number code or in number FAB mode, skipping QR scanning")
            onSetupNumberRollcallFab(rollcall.getEffectiveId(), rollcall.courseName)
            binding.cardRollcallBanner.visibility = View.VISIBLE
            binding.pbRollcallScanning.visibility = View.GONE
            binding.ivRollcallStatusIcon.visibility = View.VISIBLE
            binding.ivRollcallStatusIcon.setImageResource(R.drawable.ic_dialpad)
            binding.tvRollcallBannerTitle.text = "请关注大屏展示的 4 位数字口令，点击右下角按钮直接输入"
            return
        }

        // 非二维码签到（如雷达签到等），直接跳过
        if (normType != "qr_code") {
            Log.i(TAG, "Rollcall type ($normType) is not qr_code, skipping QR scanning")
            return
        }

        if (isPptScanningActive && activeRollcallForCapture?.id == rollcall.id) return

        activeRollcallForCapture = rollcall
        binding.cardRollcallBanner.visibility = View.VISIBLE
        binding.pbRollcallScanning.visibility = View.VISIBLE
        binding.ivRollcallStatusIcon.visibility = View.GONE
        binding.tvRollcallBannerTitle.text = "检测到【$courseTitle】开启二维码签到，正在从大屏采集卡识别二维码..."

        startPptScanningLoop(rollcall)
    }

    /**
     * 开启高频抓帧循环（每 1.2 秒截取一次 PPT 视角画面，最长 90 秒超时保护）
     */
    private fun startPptScanningLoop(rollcall: TronClassRollcall?) {
        pptScanJob?.cancel()
        isPptScanningActive = true
        activeRollcallForCapture = rollcall

        pptScanJob = activity.lifecycleScope.launch {
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
                Toast.makeText(activity, "未能从大屏画面中识别到有效二维码，已恢复静默监控", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun stopPptScanningLoop() {
        isPptScanningActive = false
        pptScanJob?.cancel()
        pptScanJob = null
    }

    /**
     * 手动立刻捕捉大屏画面并识别签到（支持直播与点播回放）
     */
    fun capturePptFrameNow() {
        binding.cardRollcallBanner.visibility = View.VISIBLE
        binding.pbRollcallScanning.visibility = View.VISIBLE
        binding.ivRollcallStatusIcon.visibility = View.GONE
        binding.tvRollcallBannerTitle.text = "正在从大屏采集卡捕获二维码..."
        injectPptFrameCaptureJs(isManual = true)
    }

    /**
     * 处理捕获到的大屏视频帧并调用 ML Kit 识别二维码
     */
    fun handlePptFrame(jsonResult: String) {
        try {
            val json = JSONObject(jsonResult)
            val isManual = json.optBoolean("isManual", false)
            val status = json.optString("status", "unknown")
            val message = json.optString("message", "")
            val framesArray = json.optJSONArray("frames")

            if (status != "success" || framesArray == null || framesArray.length() == 0) {
                if (isManual) {
                    binding.cardRollcallBanner.visibility = View.GONE
                    Toast.makeText(activity, "大屏抓帧未能就绪: $message", Toast.LENGTH_LONG).show()
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
                                Toast.makeText(                                    activity,
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
                                Toast.makeText(activity, "二维码识别扫描失败", Toast.LENGTH_SHORT).show()
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
        val tcClient = TronClassClient(activity)
        val parsed = tcClient.parseRollcallQr(rawQr)
        val rollcallId = parsed?.rollcallId ?: activeRollcallForCapture?.id

        if (rollcallId == null || parsed == null) {
            Log.w(TAG, "Unrecognized QR format from live screen: $rawQr")
            return
        }

        // 已经处理过同一个签到或正在提交，直接忽略防止重复提交
        if (rollcallId == lastHandledRollcallId || isSubmittingQr) {
            stopPptScanningLoop()
            binding.cardRollcallBanner.visibility = View.GONE
            return
        }

        // 成功捕获有效二维码，立即停止抓帧！
        stopPptScanningLoop()
        isSubmittingQr = true

        // 触觉反馈
        vibratePhone()

        // 界面更新为提交中
        binding.pbRollcallScanning.visibility = View.VISIBLE
        binding.ivRollcallStatusIcon.visibility = View.GONE
        binding.tvRollcallBannerTitle.text = "已捕获大屏二维码，正在自动提交签到..."

        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val results = tcClient.batchAnswerQRRollcall(rollcallId, parsed.qrData)
                val currentStudentId = AuthPreferences.currentStudentId(activity)
                val primarySuccess = MultiAccountRollcallResult.isPrimaryAccountSuccess(results, currentStudentId)
                val primaryRes = MultiAccountRollcallResult.findPrimaryResult(results, currentStudentId)
                val successCount = results.count { it.success }
                val primaryMsg = primaryRes?.message?.takeIf { it.isNotBlank() }
                    ?: results.firstOrNull()?.message.orEmpty()

                withContext(Dispatchers.Main) {
                    if (primarySuccess) {
                        lastHandledRollcallId = rollcallId
                        cn.edu.usst.jwgl.ui.tronclass.TronClassFragment.onRollcallCompleted(rollcallId)
                        binding.pbRollcallScanning.visibility = View.GONE
                        binding.ivRollcallStatusIcon.visibility = View.VISIBLE
                        binding.tvRollcallBannerTitle.text = if (results.size > 1) {
                            "签到成功！($successCount/${results.size} 个账号成功)"
                        } else {
                            "签到成功！已通过大屏采集卡完成签到"
                        }

                        if (results.size > 1 && results.any { !it.success }) {
                            showLiveBatchResultDialog(results, primarySuccess = true)
                        }

                        // 5 秒后优雅隐藏横幅
                        binding.cardRollcallBanner.postDelayed({
                            binding.cardRollcallBanner.visibility = View.GONE
                        }, 5000L)
                    } else {
                        binding.pbRollcallScanning.visibility = View.GONE
                        val errDetail = if (primaryMsg.isNotBlank()) "：$primaryMsg" else ""
                        val batchSuffix = if (results.size > 1) " ($successCount/${results.size} 个账号成功)" else ""
                        binding.tvRollcallBannerTitle.text = "本人签到未通过$errDetail$batchSuffix"

                        if (results.size > 1) {
                            showLiveBatchResultDialog(results, primarySuccess = false)
                        }

                        binding.cardRollcallBanner.postDelayed({
                            binding.cardRollcallBanner.visibility = View.GONE
                        }, 4000L)
                    }
                }
            } finally {
                isSubmittingQr = false
            }
        }
    }

    private fun showLiveBatchResultDialog(
        results: List<cn.edu.usst.jwgl.data.model.MultiAccountRollcallResult>,
        primarySuccess: Boolean
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val successCount = results.count { it.success }
        val totalCount = results.size
        val title = if (primarySuccess) {
            "📋 本人已签，部分账号未成功 ($successCount/$totalCount)"
        } else {
            "📋 大屏扫码签到未通过 ($successCount/$totalCount 成功)"
        }
        val sb = StringBuilder()
        for (r in results) {
            val icon = if (r.success) "🟢" else "🔴"
            val status = if (r.statusName.isNotBlank()) " [${r.statusName}]" else ""
            sb.append("$icon ${r.studentName} (${r.studentId})$status\n")
            sb.append("   ${r.message.trim()}\n\n")
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(sb.toString().trimEnd())
            .setPositiveButton("确定", null)
            .show()
    }

    private fun vibratePhone() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
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

                        // 追帧协同：在大屏抓帧扫码前，若视频流滞后，立即快进至最新直播前沿
                        if (window.__usst_live_chase_enabled && v.seekable && v.seekable.length > 0) {
                            var liveEnd = v.seekable.end(v.seekable.length - 1);
                            if (liveEnd - v.currentTime > 2.0) {
                                v.currentTime = Math.max(0, liveEnd - 0.7);
                            }
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

    fun processTestResult(jsonResult: String) {
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
                val bitmap: Bitmap?,
                val currentTime: Double,
                val liveEnd: Double,
                val lag: Double,
                val playbackRate: Double
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
                val currTime = item.optDouble("currentTime", 0.0)
                val liveEnd = item.optDouble("liveEnd", 0.0)
                val lag = item.optDouble("lag", 0.0)
                val pRate = item.optDouble("playbackRate", 1.0)

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
                        bitmap = decodedBitmap,
                        currentTime = currTime,
                        liveEnd = liveEnd,
                        lag = lag,
                        playbackRate = pRate
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

                    if (task.liveEnd > 0) {
                        val lagFormatted = String.format("%.1f", task.lag)
                        val statusTag = if (task.lag <= 1.8) "极低延迟 🟢" else if (task.lag <= 4.5) "平滑追帧中 (${task.playbackRate}x) ⏩" else "滞后较大 ⚠️"
                        finalReport.appendLine("   • 画面延迟测算: 滞后约 ${lagFormatted}秒 [$statusTag] (当前: ${String.format("%.1f", task.currentTime)}s, 最新: ${String.format("%.1f", task.liveEnd)}s)")
                    }

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
            Toast.makeText(activity, "多机位测试结果解析失败，请检查网络或稍后重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTestResultDialog(report: String) {
        val textView = TextView(activity).apply {
            text = report
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        AlertDialog.Builder(activity)
            .setTitle("🧪 视频帧抓取测试结果")
            .setView(textView)
            .setPositiveButton("确定", null)
            .show()
    }

    fun injectFrameGrabTestJs() {
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

                        var liveEnd = (v.seekable && v.seekable.length > 0) ? v.seekable.end(v.seekable.length - 1) : 0;
                        var currTime = v.currentTime || 0;
                        var lag = liveEnd > 0 ? (liveEnd - currTime) : 0;

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
                            frameData: '',
                            currentTime: currTime,
                            liveEnd: liveEnd,
                            lag: lag,
                            playbackRate: v.playbackRate || 1.0
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

}
