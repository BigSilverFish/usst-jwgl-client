package cn.edu.usst.jwgl.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * 超轻量 HTTP-FLV 裸流关键帧抽取与解码器
 * 1. 抓取首个或持续抓取 H.264 (AVC) IDR 关键帧
 * 2. 通过 MediaCodec + ImageReader 解码为 ML Kit 的 InputImage 及用于可视化调试预览的 Bitmap
 */
object FlvKeyframeExtractor {

    private const val TAG = "FlvKeyframeExtractor"

    data class DecodedFrame(
        val inputImage: InputImage,
        val bitmap: Bitmap?,
        val width: Int,
        val height: Int
    )

    data class ExtractionAttempt(
        val frame: DecodedFrame? = null,
        val errorReason: String? = null
    )

    private val flvHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 从 HTTP-FLV 流中抓取首帧并封装为 ML Kit 的 InputImage
     * @param flvUrl 完整的 FLV 播放地址 (带 token)
     * @return 解码后的 InputImage 或 null
     */
    suspend fun extractFirstKeyframe(flvUrl: String): InputImage? {
        return extractFirstKeyframeDetailed(flvUrl, needBitmap = false)?.inputImage
    }

    /**
     * 从 HTTP-FLV 流中抓取首帧（支持同时解码 Bitmap 供可视化调试渲染）
     */
    suspend fun extractFirstKeyframeDetailed(flvUrl: String, needBitmap: Boolean = true): DecodedFrame? {
        return extractFirstKeyframeWithDiagnostics(flvUrl, needBitmap).frame
    }

    internal fun resolveHttpFlvUrl(rawUrl: String): Pair<HttpUrl?, String?> {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            return null to "推流地址为空"
        }
        val normalized = if (trimmed.startsWith("//")) "https:$trimmed" else trimmed
        val parsed = normalized.toHttpUrlOrNull()
        if (parsed == null) {
            val sanitized = normalized.substringBefore("?").take(64)
            return null to "暂不支持的直播流地址格式: $sanitized"
        }
        return parsed to null
    }

    /**
     * 从 HTTP-FLV 流中抓取首帧并返回详细诊断信息
     */
    suspend fun extractFirstKeyframeWithDiagnostics(
        flvUrl: String,
        needBitmap: Boolean = true
    ): ExtractionAttempt = withContext(Dispatchers.IO) {
        var call: Call? = null
        try {
            val (httpUrl, urlError) = resolveHttpFlvUrl(flvUrl)
            if (httpUrl == null) {
                val reason = urlError ?: "无效的直播流地址"
                Log.w(TAG, reason)
                return@withContext ExtractionAttempt(errorReason = reason)
            }

            val req = Request.Builder()
                .url(httpUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                .header("Referer", "https://live.usst.edu.cn/")
                .header("Origin", "https://live.usst.edu.cn")
                .header("Accept", "*/*")
                .build()

            call = flvHttpClient.newCall(req)
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val reason = "FLV 拉流失败: HTTP ${response.code}"
                    Log.w(TAG, reason)
                    return@withContext ExtractionAttempt(errorReason = reason)
                }

                val body = response.body ?: return@withContext ExtractionAttempt(errorReason = "FLV 响应体为空")
                val inputStream = body.byteStream()

                val parseResult = parseFlvFirstKeyframe(inputStream)
                runCatching { call.cancel() } // 收集完成立即断开流，节省流量与服务端连接

                if (parseResult.keyframe == null) {
                    val reason = parseResult.errorReason ?: "未从 FLV 流中提取到 H.264 关键帧"
                    Log.w(TAG, reason)
                    return@withContext ExtractionAttempt(errorReason = reason)
                }

                val decodeResult = decodeAvcToFrame(
                    parseResult.keyframe.sps,
                    parseResult.keyframe.pps,
                    parseResult.keyframe.idrNalus,
                    needBitmap = needBitmap
                )
                if (decodeResult.frame == null) {
                    ExtractionAttempt(errorReason = decodeResult.errorReason ?: "AVC 关键帧解码失败或超时")
                } else {
                    ExtractionAttempt(frame = decodeResult.frame)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val reason = "FLV 抽帧异常: ${t.javaClass.simpleName}: ${t.message ?: "未知错误"}"
            Log.w(TAG, reason, t)
            ExtractionAttempt(errorReason = reason)
        } finally {
            runCatching { call?.cancel() }
        }
    }

    internal class KeyframeData(
        val sps: ByteArray,
        val pps: ByteArray,
        val idrNalus: ByteArray
    )

    internal class ParseResult(
        val keyframe: KeyframeData? = null,
        val errorReason: String? = null
    )

    private class DecodeResult(
        val frame: DecodedFrame? = null,
        val errorReason: String? = null
    )

    /**
     * 极简解析 FLV Header 与 Tags，提取 SPS, PPS 和第一个完整 IDR 关键帧
     */
    internal fun parseFlvFirstKeyframe(input: InputStream): ParseResult {
        val dis = DataInputStream(input)

        // 1. 读 9 字节 FLV Header
        val header = ByteArray(9)
        dis.readFully(header)
        if (header[0] != 'F'.code.toByte() || header[1] != 'L'.code.toByte() || header[2] != 'V'.code.toByte()) {
            return ParseResult(errorReason = "响应非有效 FLV 封装头")
        }

        // 2. 读 PreviousTagSize0 (4 字节)
        dis.skipBytes(4)

        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var idrData: ByteArray? = null
        var nonAvcCodecId: Int? = null

        val startTime = System.currentTimeMillis()
        var tagCount = 0
        while (tagCount < 300 && (System.currentTimeMillis() - startTime) < 8000) {
            tagCount++

            // FLV Tag Header: 11 字节
            val tagType = dis.read()
            if (tagType == -1) break

            val b1 = dis.read()
            val b2 = dis.read()
            val b3 = dis.read()
            if (b1 == -1 || b2 == -1 || b3 == -1) break
            val dataSize = (b1 shl 16) or (b2 shl 8) or b3

            // Timestamp (3 bytes) + TimestampExt (1 byte) + StreamID (3 bytes)
            dis.skipBytes(7)

            if (dataSize <= 0 || dataSize > 4_000_000) {
                break
            }

            val tagData = ByteArray(dataSize)
            dis.readFully(tagData)
            dis.skipBytes(4) // PreviousTagSizeN

            // TagType 9 为 Video
            if (tagType == 9 && tagData.size >= 5) {
                val frameInfo = tagData[0].toInt() and 0xFF
                val frameType = (frameInfo ushr 4) and 0x0F // 1: keyframe
                val codecId = frameInfo and 0x0F             // 7: AVC (H.264)
                val avcPacketType = tagData[1].toInt() and 0xFF // 0: seq header, 1: NALU

                if (codecId == 7) {
                    if (avcPacketType == 0) {
                        val config = parseAvcDecoderConfigurationRecord(tagData, 5)
                        if (config != null) {
                            sps = config.first
                            pps = config.second
                            Log.d(TAG, "Extracted SPS (${sps.size} bytes) and PPS (${pps.size} bytes)")
                        }
                    } else if (avcPacketType == 1 && frameType == 1) {
                        val converted = convertLengthPrefixedToAnnexB(tagData, 5)
                        if (converted.isNotEmpty()) {
                            idrData = converted
                            Log.d(TAG, "Extracted IDR keyframe (${idrData.size} bytes) at tag #$tagCount")
                            if (sps != null && pps != null) {
                                return ParseResult(keyframe = KeyframeData(sps, pps, idrData))
                            }
                        }
                    }
                } else {
                    nonAvcCodecId = codecId
                }
            }
        }

        if (sps != null && pps != null && idrData != null) {
            return ParseResult(keyframe = KeyframeData(sps, pps, idrData))
        }

        val reason = when {
            nonAvcCodecId != null && sps == null -> "视频流编码非 H.264/AVC (codecId=$nonAvcCodecId)"
            sps == null || pps == null -> "已读取 $tagCount 个 FLV Tag，未发现 AVC SPS/PPS 配置头"
            else -> "已收到配置头，但在 $tagCount 个 Tag 内未等来 IDR 关键帧"
        }
        return ParseResult(errorReason = reason)
    }

    /**
     * 解析 AVCDecoderConfigurationRecord 提取 SPS 和 PPS
     */
    private fun parseAvcDecoderConfigurationRecord(data: ByteArray, offset: Int): Pair<ByteArray, ByteArray>? {
        try {
            var cur = offset
            if (cur + 6 > data.size) return null
            cur += 5 // skip version, profile, profile_compat, level, lengthSizeMinusOne
            val numSps = data[cur].toInt() and 0x1F
            cur += 1
            if (numSps < 1 || cur + 2 > data.size) return null

            val spsLen = ((data[cur].toInt() and 0xFF) shl 8) or (data[cur + 1].toInt() and 0xFF)
            cur += 2
            if (cur + spsLen > data.size) return null
            val sps = data.copyOfRange(cur, cur + spsLen)
            cur += spsLen

            if (cur + 1 > data.size) return null
            val numPps = data[cur].toInt() and 0xFF
            cur += 1
            if (numPps < 1 || cur + 2 > data.size) return null

            val ppsLen = ((data[cur].toInt() and 0xFF) shl 8) or (data[cur + 1].toInt() and 0xFF)
            cur += 2
            if (cur + ppsLen > data.size) return null
            val pps = data.copyOfRange(cur, cur + ppsLen)

            return Pair(sps, pps)
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing AVC config record: ${e.message}")
            return null
        }
    }

    /**
     * 将 4 字节长度前缀的 NALU 转换为 Annex-B 格式 (00 00 00 01)
     */
    private fun convertLengthPrefixedToAnnexB(data: ByteArray, offset: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var cur = offset
        val annexBHeader = byteArrayOf(0, 0, 0, 1)

        while (cur + 4 <= data.size) {
            val len = ((data[cur].toInt() and 0xFF) shl 24) or
                    ((data[cur + 1].toInt() and 0xFF) shl 16) or
                    ((data[cur + 2].toInt() and 0xFF) shl 8) or
                    (data[cur + 3].toInt() and 0xFF)
            cur += 4
            if (len <= 0 || cur + len > data.size) break

            out.write(annexBHeader)
            out.write(data, cur, len)
            cur += len
        }
        return out.toByteArray()
    }

    /**
     * 优先选用标准软件 H.264/AVC 解码器（对单帧任意分辨率更稳定，避免部分硬解器 Surface/YUV 兼容崩溃）
     */
    private fun createPreferredAvcDecoder(): Pair<MediaCodec, String> {
        val preferredNames = listOf("c2.android.avc.decoder", "OMX.google.h264.decoder")
        val codecInfos = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        }.getOrNull().orEmpty()

        val avcDecoders = codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) }
        }

        for (targetName in preferredNames) {
            val matched = avcDecoders.firstOrNull { it.name.equals(targetName, ignoreCase = true) }
            if (matched != null) {
                runCatching {
                    return MediaCodec.createByCodecName(matched.name) to matched.name
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val swCodec = avcDecoders.firstOrNull { it.isSoftwareOnly }
            if (swCodec != null) {
                runCatching {
                    return MediaCodec.createByCodecName(swCodec.name) to swCodec.name
                }
            }
        }

        val fallback = MediaCodec.createDecoderByType("video/avc")
        val name = runCatching { fallback.name }.getOrDefault("video/avc")
        return fallback to name
    }

    /**
     * 使用无 Surface 模式的 MediaCodec (getOutputImage) 解码首帧，
     * 避免写死分辨率的 ImageReader Surface 引发底层硬解崩溃
     */
    private fun decodeAvcToFrame(
        sps: ByteArray,
        pps: ByteArray,
        idrNalus: ByteArray,
        needBitmap: Boolean = false
    ): DecodeResult {
        var codec: MediaCodec? = null
        var codecName = "video/avc"
        try {
            val startCode = byteArrayOf(0, 0, 0, 1)
            val fullPacket = startCode + sps + startCode + pps + idrNalus
            val (createdCodec, resolvedName) = createPreferredAvcDecoder()
            codec = createdCodec
            codecName = resolvedName

            val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                )
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(fullPacket.size + 65536, 512 * 1024))
                setByteBuffer("csd-0", ByteBuffer.wrap(startCode + sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(startCode + pps))
            }

            codec.configure(format, null, null, 0)
            codec.start()

            // 1. 输入关键帧数据（校验缓冲区容量，防止 BufferOverflowException）
            val inputIndex = codec.dequeueInputBuffer(200_000)
            if (inputIndex < 0) {
                return DecodeResult(errorReason = "AVC 解码器($codecName)输入缓冲超时")
            }
            val inputBuffer = codec.getInputBuffer(inputIndex)
                ?: return DecodeResult(errorReason = "AVC 解码器($codecName)输入缓冲为空")
            inputBuffer.clear()
            val payload = when {
                fullPacket.size <= inputBuffer.remaining() -> fullPacket
                idrNalus.size <= inputBuffer.remaining() -> idrNalus
                else -> {
                    return DecodeResult(
                        errorReason = "关键帧体积(${idrNalus.size}B)超出解码器缓冲(${inputBuffer.remaining()}B)"
                    )
                }
            }
            inputBuffer.put(payload)
            codec.queueInputBuffer(inputIndex, 0, payload.size, 0, MediaCodec.BUFFER_FLAG_KEY_FRAME)

            // 2. 立即发送 EOS 冲刷解码器内部缓冲，确保单帧无需等待后续帧即刻吐出
            var eosSent = false
            for (eosAttempt in 0 until 5) {
                val eosIndex = codec.dequeueInputBuffer(50_000)
                if (eosIndex >= 0) {
                    codec.getInputBuffer(eosIndex)?.clear()
                    codec.queueInputBuffer(eosIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    eosSent = true
                    break
                }
            }
            if (!eosSent) {
                Log.d(TAG, "EOS input buffer not immediately available for $codecName")
            }

            val bufferInfo = MediaCodec.BufferInfo()
            val startTime = System.currentTimeMillis()
            var lastDiag: String? = null

            while (System.currentTimeMillis() - startTime < 2500) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 50_000)
                if (outIndex >= 0) {
                    var decodedResult: DecodeResult? = null
                    try {
                        if (bufferInfo.size > 0) {
                            val outputImage = codec.getOutputImage(outIndex)
                            if (outputImage != null) {
                                try {
                                    val crop = outputImage.cropRect
                                    val rawWidth = if (crop.width() > 0) crop.width() else outputImage.width
                                    val rawHeight = if (crop.height() > 0) crop.height() else outputImage.height
                                    val width = rawWidth and 1.inv()
                                    val height = rawHeight and 1.inv()
                                    if (width > 0 && height > 0) {
                                        val nv21 = yuv420888ToNv21(outputImage, crop, width, height)
                                        val inputImage = InputImage.fromByteArray(
                                            nv21,
                                            width,
                                            height,
                                            0,
                                            InputImage.IMAGE_FORMAT_NV21
                                        )
                                        val bmp = if (needBitmap) nv21ToBitmap(nv21, width, height) else null
                                        Log.i(TAG, "Successfully extracted frame via $codecName: ${width}x${height}, hasBitmap=${bmp != null}")
                                        decodedResult = DecodeResult(
                                            frame = DecodedFrame(inputImage, bmp, width, height)
                                        )
                                    } else {
                                        lastDiag = "AVC 解码输出尺寸无效: ${rawWidth}x${rawHeight}"
                                    }
                                } finally {
                                    runCatching { outputImage.close() }
                                }
                            } else {
                                lastDiag = "AVC 解码器($codecName)未返回 YUV Image"
                            }
                        }
                    } finally {
                        runCatching { codec.releaseOutputBuffer(outIndex, false) }
                    }
                    if (decodedResult != null) {
                        return decodedResult
                    }
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "Codec($codecName) output format changed: ${codec.outputFormat}")
                }
            }
            return DecodeResult(errorReason = lastDiag ?: "AVC 解码($codecName)超时未输出有效帧")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val reason = "AVC 解码异常($codecName): ${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
            Log.e(TAG, reason, t)
            return DecodeResult(errorReason = reason)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    /**
     * 将 ImageFormat.YUV_420_888 拷贝为独立 NV21 字节数组（带严格边界保护）
     */
    private fun yuv420888ToNv21(image: Image, crop: Rect, width: Int, height: Int): ByteArray {
        val planes = image.planes
        require(planes.size >= 3) { "YUV planes count < 3 (${planes.size})" }
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]

        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        val nv21 = ByteArray(width * height * 3 / 2)
        val cropLeft = crop.left.coerceIn(0, maxOf(0, image.width - width))
        val cropTop = crop.top.coerceIn(0, maxOf(0, image.height - height))

        // Y plane
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val yLimit = yBuffer.limit()
        var pos = 0
        if (yPixelStride == 1) {
            for (row in 0 until height) {
                val rowStart = (row + cropTop) * yRowStride + cropLeft
                if (rowStart in 0 until yLimit) {
                    val copyLen = minOf(width, yLimit - rowStart)
                    yBuffer.position(rowStart)
                    yBuffer.get(nv21, pos, copyLen)
                }
                pos += width
            }
        } else {
            for (row in 0 until height) {
                val rowStart = (row + cropTop) * yRowStride + cropLeft * yPixelStride
                for (col in 0 until width) {
                    val idx = rowStart + col * yPixelStride
                    nv21[pos++] = if (idx in 0 until yLimit) yBuffer.get(idx) else 0
                }
            }
        }

        // U and V planes -> interleaved V then U (NV21)
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vLimit = vBuffer.limit()
        val uLimit = uBuffer.limit()

        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val chromaTop = cropTop / 2
        val chromaLeft = cropLeft / 2

        for (row in 0 until chromaHeight) {
            val vRowStart = (row + chromaTop) * vRowStride + chromaLeft * vPixelStride
            val uRowStart = (row + chromaTop) * uRowStride + chromaLeft * uPixelStride
            for (col in 0 until chromaWidth) {
                val vIdx = vRowStart + col * vPixelStride
                val uIdx = uRowStart + col * uPixelStride
                nv21[pos++] = if (vIdx in 0 until vLimit) vBuffer.get(vIdx) else 128.toByte()
                nv21[pos++] = if (uIdx in 0 until uLimit) uBuffer.get(uIdx) else 128.toByte()
            }
        }
        return nv21
    }

    private fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
            val jpegBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "nv21ToBitmap failed: ${e.message}")
            null
        }
    }
}
