package cn.edu.usst.jwgl.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class FlvKeyframeExtractorTest {

    @Test
    fun resolveHttpFlvUrl_acceptsHttpHttpsAndProtocolRelative() {
        val (httpsUrl, httpsErr) = FlvKeyframeExtractor.resolveHttpFlvUrl(
            " https://live.usst.edu.cn/live/1.flv?account_token=secret123 "
        )
        assertNull(httpsErr)
        assertNotNull(httpsUrl)
        assertEquals("https://live.usst.edu.cn/live/1.flv?account_token=secret123", httpsUrl.toString())

        val (relProtoUrl, relProtoErr) = FlvKeyframeExtractor.resolveHttpFlvUrl(
            "//live.usst.edu.cn/live/1.flv"
        )
        assertNull(relProtoErr)
        assertNotNull(relProtoUrl)
        assertEquals("https://live.usst.edu.cn/live/1.flv", relProtoUrl.toString())
    }

    @Test
    fun resolveHttpFlvUrl_rejectsUnsupportedSchemesWithoutLeakingToken() {
        val cases = listOf(
            "",
            "   ",
            "/relative/path.flv?account_token=xyz",
            "rtmp://live.usst.edu.cn/app/stream?token=xyz",
            "ws://live.usst.edu.cn/live.flv?token=xyz"
        )

        for (raw in cases) {
            val (url, err) = FlvKeyframeExtractor.resolveHttpFlvUrl(raw)
            assertNull("Expected null HttpUrl for: $raw", url)
            assertNotNull("Expected error reason for: $raw", err)
            assertTrue("Expected non-blank error reason for: $raw", err!!.isNotBlank())
            assertFalse("Query token leaked in error reason: $err", err.contains("xyz"))
        }
    }

    @Test
    fun parseFlvFirstKeyframe_rejectsNonFlvHeader() {
        val result = FlvKeyframeExtractor.parseFlvFirstKeyframe(
            ByteArrayInputStream("HTML error page".toByteArray())
        )
        assertNull(result.keyframe)
        assertEquals("响应非有效 FLV 封装头", result.errorReason)
    }

    @Test
    fun parseFlvFirstKeyframe_extractsSpsPpsAndAnnexBIdr() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E, 0x9A.toByte(), 0x74)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val idrNalu = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x21)

        // AVCDecoderConfigurationRecord
        val avcConfig = ByteArrayOutputStream().apply {
            write(0x01) // configurationVersion
            write(sps[1].toInt()) // AVCProfileIndication
            write(sps[2].toInt()) // profile_compatibility
            write(sps[3].toInt()) // AVCLevelIndication
            write(0xFF) // lengthSizeMinusOne = 3
            write(0xE1) // numOfSequenceParameterSets = 1
            write((sps.size ushr 8) and 0xFF)
            write(sps.size and 0xFF)
            write(sps)
            write(0x01) // numOfPictureParameterSets = 1
            write((pps.size ushr 8) and 0xFF)
            write(pps.size and 0xFF)
            write(pps)
        }.toByteArray()

        val seqHeaderTagData = ByteArrayOutputStream().apply {
            write(0x17) // frameType=1 (keyframe), codecId=7 (AVC)
            write(0x00) // avcPacketType=0 (seq header)
            write(0); write(0); write(0) // compositionTime
            write(avcConfig)
        }.toByteArray()

        val idrTagData = ByteArrayOutputStream().apply {
            write(0x17) // frameType=1 (keyframe), codecId=7 (AVC)
            write(0x01) // avcPacketType=1 (NALU)
            write(0); write(0); write(0) // compositionTime
            // 4-byte length prefix
            write((idrNalu.size ushr 24) and 0xFF)
            write((idrNalu.size ushr 16) and 0xFF)
            write((idrNalu.size ushr 8) and 0xFF)
            write(idrNalu.size and 0xFF)
            write(idrNalu)
        }.toByteArray()

        val flvStream = ByteArrayOutputStream().apply {
            // FLV Header (9 bytes) + PreviousTagSize0 (4 bytes)
            write(byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1, 1, 0, 0, 0, 9))
            write(byteArrayOf(0, 0, 0, 0))
            writeFlvVideoTag(seqHeaderTagData)
            writeFlvVideoTag(idrTagData)
        }.toByteArray()

        val result = FlvKeyframeExtractor.parseFlvFirstKeyframe(ByteArrayInputStream(flvStream))
        assertNull(result.errorReason)
        val kf = result.keyframe
        assertNotNull(kf)
        assertArrayEquals(sps, kf!!.sps)
        assertArrayEquals(pps, kf.pps)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + idrNalu, kf.idrNalus)
    }

    private fun ByteArrayOutputStream.writeFlvVideoTag(tagData: ByteArray) {
        val size = tagData.size
        write(9) // Video tag
        write((size ushr 16) and 0xFF)
        write((size ushr 8) and 0xFF)
        write(size and 0xFF)
        write(0); write(0); write(0); write(0) // timestamp + ext
        write(0); write(0); write(0) // streamId
        write(tagData)
        val prevSize = 11 + size
        write((prevSize ushr 24) and 0xFF)
        write((prevSize ushr 16) and 0xFF)
        write((prevSize ushr 8) and 0xFF)
        write(prevSize and 0xFF)
    }
}
