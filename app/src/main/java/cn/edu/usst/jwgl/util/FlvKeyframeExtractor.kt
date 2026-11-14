package cn.edu.usst.jwgl.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private val flvHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
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
    suspend fun extractFirstKeyframeDetailed(flvUrl: String, needBitmap: Boolean = true): DecodedFrame? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(flvUrl)
            .header("User-Agent", "Lavf/58.29.100")
            .header("Accept", "*/*")
            .build()

        val call = flvHttpClient.newCall(req)
        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "FLV request failed: HTTP ${response.code}")
                return@withContext null
            }

            val body = response.body ?: return@withContext null
            val inputStream = body.byteStream()

            val frameData = parseFlvFirstKeyframe(inputStream)
            call.cancel() // 收集完成立即断开流，节省流量与服务端连接

            if (frameData == null) {
                Log.w(TAG, "Failed to extract keyframe NALU from FLV stream")
                return@withContext null
            }

            decodeAvcToFrame(frameData.sps, frameData.pps, frameData.idrNalus, needBitmap = needBitmap)
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting keyframe: ${e.message}")
            null
        } finally {
            call.cancel()
        }
    }

    private class KeyframeData(
        val sps: ByteArray,
        val pps: ByteArray,
        val idrNalus: ByteArray
    )

    /**
     * 极简解析 FLV Header 与 Tags，提取 SPS, PPS 和第一个完整 IDR 关键帧
     */
    private fun parseFlvFirstKeyframe(input: InputStream): KeyframeData? {
        val dis = DataInputStream(input)

        // 1. 读 9 字节 FLV Header
        val header = ByteArray(9)
        dis.readFully(header)
        if (header[0] != 'F'.code.toByte() || header[1] != 'L'.code.toByte() || header[2] != 'V'.code.toByte()) {
            Log.w(TAG, "Not a valid FLV header")
            return null
        }

        // 2. 读 PreviousTagSize0 (4 字节)
        dis.skipBytes(4)

        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var idrData: ByteArray? = null

        val startTime = System.currentTimeMillis()
        // 最多扫描 5 秒或读取前 80 个 Tag
        var tagCount = 0
        while (tagCount < 80 && (System.currentTimeMillis() - startTime) < 5000) {
            tagCount++

            // FLV Tag Header: 11 字节
            val tagType = dis.read()
            if (tagType == -1) break

            val dataSize = (dis.read() shl 16) or (dis.read() shl 8) or dis.read()
            val timestamp = (dis.read() shl 16) or (dis.read() shl 8) or dis.read()
            val timestampExt = dis.read()
            dis.skipBytes(3) // StreamID

            if (dataSize <= 0 || dataSize > 2_000_000) {
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
                        // AVC sequence header (AVCDecoderConfigurationRecord)
                        val config = parseAvcDecoderConfigurationRecord(tagData, 5)
                        if (config != null) {
                            sps = config.first
                            pps = config.second
                            Log.d(TAG, "Extracted SPS (${sps.size} bytes) and PPS (${pps.size} bytes)")
                        }
                    } else if (avcPacketType == 1 && frameType == 1) {
                        // IDR 关键帧数据 (包含 4 字节大端长度前缀的 NALU)
                        idrData = convertLengthPrefixedToAnnexB(tagData, 5)
                        Log.d(TAG, "Extracted IDR keyframe (${idrData.size} bytes)")
                        if (sps != null && pps != null) {
                            return KeyframeData(sps, pps, idrData)
                        }
                    }
                }
            }
        }

        return if (sps != null && pps != null && idrData != null) {
            KeyframeData(sps, pps, idrData)
        } else null
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
     * 使用 MediaCodec + ImageReader 解码首帧并输出为 ML Kit 的 InputImage（及可选 Bitmap）
     */
    private fun decodeAvcToFrame(
        sps: ByteArray,
        pps: ByteArray,
        idrNalus: ByteArray,
        needBitmap: Boolean = false
    ): DecodedFrame? {
        var codec: MediaCodec? = null
        var imageReader: ImageReader? = null
        try {
            // 估算或指定分辨率，默认 1920x1080 (MediaCodec 解码后会自动按实际 SPS 纠正)
            val format = MediaFormat.createVideoFormat("video/avc", 1920, 1080).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1) + pps))
            }

            imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.YUV_420_888, 2)
            codec = MediaCodec.createDecoderByType("video/avc").apply {
                configure(format, imageReader.surface, null, 0)
                start()
            }

            // 输入关键帧数据
            val inputIndex = codec.dequeueInputBuffer(100_000)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                val fullPacket = byteArrayOf(0, 0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps + idrNalus
                inputBuffer?.put(fullPacket)
                codec.queueInputBuffer(inputIndex, 0, fullPacket.size, 0, MediaCodec.BUFFER_FLAG_KEY_FRAME)
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var decodedImage: Image? = null
            val startTime = System.currentTimeMillis()

            // 等待解码输出渲染至 ImageReader
            while (System.currentTimeMillis() - startTime < 1000) {
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 20_000)
                if (outIndex >= 0) {
                    codec.releaseOutputBuffer(outIndex, true) // 渲染到 Surface
                    // 稍作等待以确保 ImageReader 捕获该帧
                    Thread.sleep(30)
                    decodedImage = imageReader.acquireLatestImage()
                    if (decodedImage != null) break
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Log.d(TAG, "Codec output format changed: $newFormat")
                }
            }

            if (decodedImage != null) {
                val width = decodedImage.width
                val height = decodedImage.height
                val inputImage = InputImage.fromMediaImage(decodedImage, 0)
                val bmp = if (needBitmap) yuv420888ToBitmap(decodedImage) else null
                Log.i(TAG, "Successfully extracted frame: ${width}x${height}, hasBitmap=${bmp != null}")
                return DecodedFrame(inputImage, bmp, width, height)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in decodeAvcToFrame: ${e.message}", e)
        } finally {
            try {
                codec?.stop()
                codec?.release()
                imageReader?.close()
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * 将 ImageFormat.YUV_420_888 格式的 Image 转换为 NV21 字节数组并压缩为 Bitmap
     */
    private fun yuv420888ToBitmap(image: Image): Bitmap? {
        return try {
            val width = image.width
            val height = image.height
            val planes = image.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + (width * height / 2))

            // Y plane
            val yRowStride = planes[0].rowStride
            val yPixelStride = planes[0].pixelStride
            var pos = 0
            if (yPixelStride == 1 && yRowStride == width) {
                yBuffer.get(nv21, 0, ySize)
                pos = ySize
            } else {
                for (row in 0 until height) {
                    yBuffer.position(row * yRowStride)
                    for (col in 0 until width) {
                        nv21[pos++] = yBuffer.get()
                        if (yPixelStride > 1 && col < width - 1) {
                            yBuffer.position(yBuffer.position() + yPixelStride - 1)
                        }
                    }
                }
            }

            // U and V planes -> interleaved V then U (NV21)
            val vRowStride = planes[2].rowStride
            val vPixelStride = planes[2].pixelStride
            val uRowStride = planes[1].rowStride
            val uPixelStride = planes[1].pixelStride

            val chromaHeight = height / 2
            val chromaWidth = width / 2

            for (row in 0 until chromaHeight) {
                val vRowStart = row * vRowStride
                val uRowStart = row * uRowStride
                for (col in 0 until chromaWidth) {
                    val vVal = vBuffer.get(vRowStart + col * vPixelStride)
                    val uVal = uBuffer.get(uRowStart + col * uPixelStride)
                    nv21[pos++] = vVal
                    nv21[pos++] = uVal
                }
            }

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            // 压缩为 JPEG 缩略图（针对 1080P，85% 品质肉眼极清晰且耗时极短）
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
            val jpegBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            Log.w(TAG, "yuv420888ToBitmap failed: ${e.message}")
            null
        }
    }
}
