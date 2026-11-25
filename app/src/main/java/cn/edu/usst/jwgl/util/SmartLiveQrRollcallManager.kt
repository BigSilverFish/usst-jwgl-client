package cn.edu.usst.jwgl.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import cn.edu.usst.jwgl.data.model.LiveDeviceViewDto
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.data.network.ChaoXingQrPayload
import cn.edu.usst.jwgl.data.network.LiveUsstClient
import cn.edu.usst.jwgl.data.network.TronClassClient
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.*
import kotlin.coroutines.resume

/**
 * 智慧录播底层 API 直连 & 极速二维码自动签到调度器
 * 1. 毫秒级检索课程直播状态与各机位推流地址
 * 2. 优先选取机位 5 (PPT)，若无则降级并发抽取备用机位
 * 3. HTTP-FLV 关键帧解码与 Google ML Kit 智能解析
 * 4. 支持单次或持续循环采样（带实时画面回调，供可视化页面展现）
 * 5. 自动化提交签到并发送结果通知
 */
class SmartLiveQrRollcallManager(private val context: Context) {

    companion object {
        private const val TAG = "SmartLiveQrRollcall"
    }

    data class FrameObservation(
        val frameIndex: Int,
        val viewName: String,
        val bitmap: Bitmap?,
        val detectedQr: String?,
        val message: String
    )

    private val liveClient by lazy { LiveUsstClient(context) }
    private val tronClassClient by lazy { TronClassClient(context) }

    private var scannerInitError: String? = null

    private val barcodeScanner: BarcodeScanner? by lazy {
        runCatching {
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            BarcodeScanning.getClient(options)
        }.onFailure { t ->
            scannerInitError = "${t.javaClass.simpleName}: ${t.message ?: "ML Kit 初始化失败"}"
            Log.e(TAG, "Failed to initialize ML Kit BarcodeScanner", t)
        }.getOrNull()
    }

    /**
     * 单次快速探测或自动签到
     */
    suspend fun tryAutoSignFromLive(
        rollcall: TronClassRollcall,
        courseName: String,
        classroom: String? = null
    ): Boolean {
        var signSuccess = false
        monitorAndAutoSign(
            rollcall = rollcall,
            courseName = courseName,
            classroom = classroom,
            maxDurationSeconds = 25,
            intervalMs = 2500L,
            onFrameCaptured = { _ -> },
            onCompleted = { success, _ -> signSuccess = success }
        )
        return signSuccess
    }

    /**
     * 持续循环抓帧探测，直至扫码成功、超时或主动取消。
     * 每次抓到最新一帧都会通过 [onFrameCaptured] 回传最新渲染的 Bitmap 与识别状态。
     */
    suspend fun monitorAndAutoSign(
        rollcall: TronClassRollcall,
        courseName: String,
        classroom: String? = null,
        chaoXingCourseId: String? = null,
        chaoXingClassId: String? = null,
        maxDurationSeconds: Int = 120,
        intervalMs: Long = 2500L,
        designatedLiveCourseId: Long? = null,
        testOnlyNoSubmit: Boolean = false,
        preferredViewNumProvider: (() -> Int?)? = null,
        onViewsDiscovered: ((List<LiveDeviceViewDto>) -> Unit)? = null,
        onFrameCaptured: (FrameObservation) -> Unit,
        onCompleted: (Boolean, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val rollcallId = rollcall.getEffectiveId()
            Log.i(
                TAG,
                "Starting monitorAndAutoSign for rollcallId=$rollcallId, course=$courseName, room=$classroom, designatedLiveId=$designatedLiveCourseId, testOnly=$testOnlyNoSubmit, maxSec=$maxDurationSeconds"
            )

            // 1. 定位录播系统直播间 ID
            val liveCourseId = if (designatedLiveCourseId != null && designatedLiveCourseId > 0L) {
                designatedLiveCourseId
            } else {
                val searchRes = liveClient.searchLiveCourse(courseName, classroom)
                if (searchRes.isFailure) {
                    val msg = searchRes.exceptionOrNull()?.message ?: "检索直播课程失败"
                    Log.w(TAG, "searchLiveCourse failed: $msg")
                    withContext(Dispatchers.Main) {
                        onCompleted(false, msg)
                    }
                    return@withContext
                }
                val courseRecord = searchRes.getOrNull()
                if (courseRecord == null) {
                    val msg = "未检索到【$courseName】对应教室的在线录播"
                    Log.w(TAG, msg)
                    withContext(Dispatchers.Main) {
                        onCompleted(false, msg)
                    }
                    return@withContext
                }
                courseRecord.id
            }

            // 2. 获取直播间各机位列表
            val viewsRes = liveClient.fetchLiveVideoInfos(liveCourseId)
            if (viewsRes.isFailure) {
                val msg = viewsRes.exceptionOrNull()?.message ?: "获取录播机位失败"
                Log.w(TAG, "fetchLiveVideoInfos failed: $msg")
                withContext(Dispatchers.Main) {
                    onCompleted(false, msg)
                }
                return@withContext
            }
            val views = viewsRes.getOrDefault(emptyList())
            val playableViews = views.filter { !it.chanNameMainPlayUrl.isNullOrBlank() }
            if (playableViews.isEmpty()) {
                val msg = "录播间暂无可用机位推流"
                Log.w(TAG, msg)
                withContext(Dispatchers.Main) {
                    onCompleted(false, msg)
                }
                return@withContext
            }

            if (onViewsDiscovered != null) {
                withContext(Dispatchers.Main) {
                    onViewsDiscovered(playableViews)
                }
            }

            // 3. 智能候选机位（若智能过滤为空则回退全部可用机位）
            val candidates = liveClient.selectOptimalCandidateViews(playableViews)
                .ifEmpty { playableViews }

            val startTime = System.currentTimeMillis()
            var frameCounter = 0
            var candidateIndex = 0

            while (isActive && (System.currentTimeMillis() - startTime) < maxDurationSeconds * 1000L) {
                frameCounter++
                val preferredViewNum = preferredViewNumProvider?.invoke()
                val targetView = if (preferredViewNum != null) {
                    playableViews.firstOrNull { it.deviViewNum == preferredViewNum }
                        ?: candidates[candidateIndex % candidates.size]
                } else if (candidates.size == 1) {
                    candidates[0]
                } else {
                    candidates[candidateIndex % candidates.size]
                }
                candidateIndex++

                val viewUrl = targetView.getFullPlayUrl()
                if (viewUrl.isNullOrBlank()) {
                    delay(intervalMs)
                    continue
                }

                Log.i(TAG, "Sampling frame #$frameCounter from [${targetView.viewName}]...")
                val attempt = try {
                    FlvKeyframeExtractor.extractFirstKeyframeWithDiagnostics(viewUrl, needBitmap = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "extractFirstKeyframeWithDiagnostics threw ${t.javaClass.simpleName}", t)
                    FlvKeyframeExtractor.ExtractionAttempt(
                        errorReason = "抽帧异常: ${t.javaClass.simpleName}: ${t.message ?: "未知错误"}"
                    )
                }
                val decodedFrame = attempt.frame

                if (decodedFrame != null) {
                    val qr = scanQrCode(decodedFrame.inputImage)
                    val observationMsg = if (!qr.isNullOrBlank()) {
                        if (testOnlyNoSubmit) {
                            describeDetectedQrForTest(qr)
                        } else {
                            "成功捕获到二维码"
                        }
                    } else if (scannerInitError != null) {
                        "已获取 ${decodedFrame.width}x${decodedFrame.height} 画面帧（识码器不可用: $scannerInitError）"
                    } else {
                        "已获取 ${decodedFrame.width}x${decodedFrame.height} 画面帧，未检测到二维码"
                    }

                    val observation = FrameObservation(
                        frameIndex = frameCounter,
                        viewName = targetView.viewName,
                        bitmap = decodedFrame.bitmap,
                        detectedQr = qr,
                        message = observationMsg
                    )

                    withContext(Dispatchers.Main) {
                        onFrameCaptured(observation)
                    }

                    if (!qr.isNullOrBlank()) {
                        Log.i(TAG, "QR code detected: $qr")
                        if (testOnlyNoSubmit) {
                            delay(intervalMs)
                            continue
                        }

                        val parsed = tronClassClient.parseRollcallQr(qr)
                        if (parsed != null) {
                            val results = tronClassClient.batchAnswerQRRollcall(parsed.rollcallId, parsed.qrData)
                            val primarySuccess = results.firstOrNull()?.success == true
                            val successCount = results.count { it.success }
                            val summaryMsg = if (results.size > 1) {
                                "$successCount/${results.size} 个账号签到成功"
                            } else {
                                results.firstOrNull()?.message ?: if (primarySuccess) "签到成功" else "签到未成功"
                            }

                            RollcallNotificationHelper.notifyLiveQrResult(
                                context = context,
                                rollcallId = rollcallId,
                                courseName = courseName,
                                success = primarySuccess,
                                viewName = targetView.viewName,
                                message = summaryMsg
                            )

                            withContext(Dispatchers.Main) {
                                onCompleted(primarySuccess, summaryMsg)
                            }
                            return@withContext
                        }
                    }
                } else {
                    val obs = FrameObservation(
                        frameIndex = frameCounter,
                        viewName = targetView.viewName,
                        bitmap = null,
                        detectedQr = null,
                        message = attempt.errorReason ?: "第 $frameCounter 帧解码等待中..."
                    )
                    withContext(Dispatchers.Main) {
                        onFrameCaptured(obs)
                    }
                }

                delay(intervalMs)
            }

            withContext(Dispatchers.Main) {
                onCompleted(false, "已持续追帧 ${maxDurationSeconds}s，未在大屏上发现有效二维码")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "monitorAndAutoSign failed unexpectedly", t)
            withContext(Dispatchers.Main) {
                onCompleted(false, "抓帧流程异常: ${t.javaClass.simpleName}: ${t.message ?: "未知错误"}")
            }
        }
    }

    private fun describeDetectedQrForTest(rawQr: String): String {
        if (ChaoXingQrPayload.isChaoXingQrUrl(rawQr)) {
            val cx = ChaoXingQrPayload.parse(rawQr).getOrNull()
            return if (cx != null) {
                "识别到学习通签到码 (activeId=${cx.activeId})，测试模式不提交"
            } else {
                "识别到学习通链接二维码: ${rawQr.take(80)}"
            }
        }
        val tc = tronClassClient.parseRollcallQr(rawQr)
        if (tc != null) {
            return "识别到畅课签到码 (rollcallId=${tc.rollcallId})，测试模式不提交"
        }
        return "识别到二维码内容: ${rawQr.take(80)}"
    }

    /**
     * 将 InputImage 传入 ML Kit 进行异步扫码并挂起返回
     */
    private suspend fun scanQrCode(image: InputImage): String? = suspendCancellableCoroutine { continuation ->
        val scanner = barcodeScanner
        if (scanner == null) {
            if (continuation.isActive) {
                continuation.resume(null)
            }
            return@suspendCancellableCoroutine
        }
        try {
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val result = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "ML Kit barcode scan failure: ${e.message}")
                    if (continuation.isActive) {
                        continuation.resume(null)
                    }
                }
        } catch (t: Throwable) {
            Log.e(TAG, "ML Kit barcodeScanner.process threw ${t.javaClass.simpleName}", t)
            if (continuation.isActive) {
                continuation.resume(null)
            }
        }
    }
}
