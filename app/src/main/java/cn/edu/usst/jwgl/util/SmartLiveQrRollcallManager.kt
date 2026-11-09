package cn.edu.usst.jwgl.util

import android.content.Context
import android.util.Log
import cn.edu.usst.jwgl.data.model.LiveDeviceViewDto
import cn.edu.usst.jwgl.data.model.TronClassRollcall
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
 * 4. 自动化提交签到并发送结果通知
 */
class SmartLiveQrRollcallManager(private val context: Context) {

    companion object {
        private const val TAG = "SmartLiveQrRollcall"
    }

    private val liveClient by lazy { LiveUsstClient(context) }
    private val tronClassClient by lazy { TronClassClient(context) }

    private val barcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        BarcodeScanning.getClient(options)
    }

    /**
     * 针对指定课堂签到发起极速识别与自动签到流程
     * @return 是否成功全自动完成签到
     */
    suspend fun tryAutoSignFromLive(
        rollcall: TronClassRollcall,
        courseName: String,
        classroom: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val rollcallId = rollcall.getEffectiveId()
        Log.i(TAG, "Starting auto live QR sign for rollcallId=$rollcallId, course=$courseName, room=$classroom")

        // 1. 搜索录播系统直播状态
        val courseRecord = liveClient.searchLiveCourse(courseName, classroom).getOrNull()
        if (courseRecord == null) {
            Log.w(TAG, "No live broadcast found for course: $courseName")
            return@withContext false
        }

        Log.i(TAG, "Found live broadcast: id=${courseRecord.id}, room=${courseRecord.clroName}")

        // 2. 获取直播间各机位列表
        val views = liveClient.fetchLiveVideoInfos(courseRecord.id).getOrNull()
        if (views.isNullOrEmpty()) {
            Log.w(TAG, "No camera views returned for courseId=${courseRecord.id}")
            return@withContext false
        }

        // 3. 智能机位降级匹配（优先 PPT，无则并发合成与教师机位）
        val candidates = liveClient.selectOptimalCandidateViews(views)
        if (candidates.isEmpty()) {
            Log.w(TAG, "No suitable camera views found")
            return@withContext false
        }

        // 4. 并发拉取首帧关键帧与二维码扫描
        var detectedQrContent: String? = null
        var successfulViewName: String? = null

        coroutineScope {
            val jobs = candidates.map { viewDto ->
                async {
                    val fullUrl = viewDto.getFullPlayUrl() ?: return@async
                    Log.i(TAG, "Sampling keyframe from view [${viewDto.viewName}]...")
                    val inputImage = FlvKeyframeExtractor.extractFirstKeyframe(fullUrl)
                    if (inputImage != null) {
                        val qr = scanQrCode(inputImage)
                        if (!qr.isNullOrBlank()) {
                            synchronized(this@SmartLiveQrRollcallManager) {
                                if (detectedQrContent == null) {
                                    detectedQrContent = qr
                                    successfulViewName = viewDto.viewName
                                }
                            }
                        }
                    }
                }
            }
            jobs.awaitAll()
        }

        val qrContent = detectedQrContent
        if (qrContent.isNullOrBlank()) {
            Log.w(TAG, "Failed to detect QR code from candidate views")
            return@withContext false
        }

        Log.i(TAG, "Successfully detected QR code from [${successfulViewName}]: $qrContent")

        // 5. 自动执行签到提交
        val parsed = tronClassClient.parseRollcallQr(qrContent)
        if (parsed != null) {
            val results = tronClassClient.batchAnswerQRRollcall(parsed.rollcallId, parsed.qrData)

            val successCount = results.count { it.success }
            val primarySuccess = results.firstOrNull()?.success == true

            val msg = if (results.size > 1) {
                "$successCount/${results.size} 个账号签到成功"
            } else {
                results.firstOrNull()?.message ?: if (primarySuccess) "签到成功" else "签到未成功"
            }

            // 发送自动签到通知
            RollcallNotificationHelper.notifyLiveQrResult(
                context = context,
                rollcallId = rollcallId,
                courseName = courseName,
                success = primarySuccess,
                viewName = successfulViewName ?: "大屏机位",
                message = msg
            )

            return@withContext primarySuccess
        }

        false
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
