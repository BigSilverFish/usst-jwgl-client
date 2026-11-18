package cn.edu.usst.jwgl.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import cn.edu.usst.jwgl.data.model.LiveDeviceViewDto
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.data.network.ChaoXingClient
import cn.edu.usst.jwgl.data.network.ChaoXingQrPayload
import cn.edu.usst.jwgl.data.network.LiveUsstClient
import cn.edu.usst.jwgl.data.network.TronClassClient
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
    private val chaoXingClient by lazy { ChaoXingClient(context) }

    private val barcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
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
        onFrameCaptured: (FrameObservation) -> Unit,
        onCompleted: (Boolean, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val rollcallId = rollcall.getEffectiveId()
        Log.i(TAG, "Starting monitorAndAutoSign for rollcallId=$rollcallId, course=$courseName, room=$classroom, maxSec=$maxDurationSeconds")

        // 1. 搜索录播系统直播状态
        val courseRecord = liveClient.searchLiveCourse(courseName, classroom).getOrNull()
        if (courseRecord == null) {
            val msg = "未检索到【$courseName】对应教室的在线录播"
            Log.w(TAG, msg)
            withContext(Dispatchers.Main) {
                onCompleted(false, msg)
            }
            return@withContext
        }

        // 2. 获取直播间各机位列表
        val views = liveClient.fetchLiveVideoInfos(courseRecord.id).getOrNull()
        if (views.isNullOrEmpty()) {
            val msg = "录播间暂无可用机位推流"
            Log.w(TAG, msg)
            withContext(Dispatchers.Main) {
                onCompleted(false, msg)
            }
            return@withContext
        }

        // 3. 智能候选机位
        val candidates = liveClient.selectOptimalCandidateViews(views)
        if (candidates.isEmpty()) {
            val msg = "未找到大屏或有效候选机位"
            Log.w(TAG, msg)
            withContext(Dispatchers.Main) {
                onCompleted(false, msg)
            }
            return@withContext
        }

        val startTime = System.currentTimeMillis()
        var frameCounter = 0
        var candidateIndex = 0

        while (isActive && (System.currentTimeMillis() - startTime) < maxDurationSeconds * 1000L) {
            frameCounter++
            // 轮换或优先采纳机位
            val targetView = if (candidates.size == 1) {
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
            val decodedFrame = FlvKeyframeExtractor.extractFirstKeyframeDetailed(viewUrl, needBitmap = true)

            if (decodedFrame != null) {
                val qr = scanQrCode(decodedFrame.inputImage)
                val observation = FrameObservation(
                    frameIndex = frameCounter,
                    viewName = targetView.viewName ?: "大屏机位",
                    bitmap = decodedFrame.bitmap,
                    detectedQr = qr,
                    message = if (!qr.isNullOrBlank()) "🎉 成功捕获到二维码！" else "已获取 ${decodedFrame.width}x${decodedFrame.height} 画面帧，未检测到二维码"
                )

                withContext(Dispatchers.Main) {
                    onFrameCaptured(observation)
                }

                if (!qr.isNullOrBlank()) {
                    Log.i(TAG, "QR code detected: $qr")
                    val isChaoXing = !chaoXingCourseId.isNullOrBlank() && !chaoXingClassId.isNullOrBlank()
                    if (isChaoXing || ChaoXingQrPayload.isChaoXingQrUrl(qr)) {
                        val expectedId = rollcallId.takeIf { it > 0L }
                        val parsedCx = ChaoXingQrPayload.parse(qr, expectedId).getOrNull()
                        if (parsedCx != null && isChaoXing) {
                            val submitRes = chaoXingClient.submitSign(
                                courseId = chaoXingCourseId!!,
                                classId = chaoXingClassId!!,
                                activeId = parsedCx.activeId,
                                signType = "二维码签到",
                                enc = parsedCx.enc,
                                signCode = parsedCx.code,
                                allowRejectedRetry = true
                            )
                            val cxResult = submitRes.getOrNull()
                            val success = cxResult?.isSuccess == true
                            val summaryMsg = cxResult?.message ?: submitRes.exceptionOrNull()?.message ?: "提交失败"
                            if (success || (!summaryMsg.contains("过期") && !summaryMsg.contains("失效") && !summaryMsg.contains("刷新"))) {
                                RollcallNotificationHelper.notifyLiveQrResult(
                                    context = context,
                                    rollcallId = parsedCx.activeId,
                                    courseName = courseName,
                                    success = success,
                                    viewName = targetView.viewName ?: "大屏机位",
                                    message = summaryMsg
                                )
                                withContext(Dispatchers.Main) {
                                    onCompleted(success, summaryMsg)
                                }
                                return@withContext
                            }
                        }
                    } else {
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
                                viewName = targetView.viewName ?: "大屏机位",
                                message = summaryMsg
                            )

                            withContext(Dispatchers.Main) {
                                onCompleted(primarySuccess, summaryMsg)
                            }
                            return@withContext
                        }
                    }
                }
            } else {
                val obs = FrameObservation(
                    frameIndex = frameCounter,
                    viewName = targetView.viewName ?: "大屏机位",
                    bitmap = null,
                    detectedQr = null,
                    message = "第 $frameCounter 帧解码等待中..."
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
    }

    /**
     * 将 InputImage 传入 ML Kit 进行异步扫码并挂起返回
     */
    private suspend fun scanQrCode(image: InputImage): String? = suspendCancellableCoroutine { continuation ->
        barcodeScanner.process(image)
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
    }
}
