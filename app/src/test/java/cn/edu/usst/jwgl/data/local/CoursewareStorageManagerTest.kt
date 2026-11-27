package cn.edu.usst.jwgl.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class CoursewareStorageManagerTest {

    @Test
    fun `sanitizeSegment cleans illegal characters and falls back cleanly`() {
        assertEquals("数字图像处理_A_", CoursewareStorageManager.sanitizeSegment("数字图像处理/A:", "默认"))
        assertEquals("第1讲 概述.pdf", CoursewareStorageManager.sanitizeSegment("  第1讲   概述.pdf  ", "默认"))
        assertEquals("默认", CoursewareStorageManager.sanitizeSegment("...   ", "默认"))
        assertEquals("默认", CoursewareStorageManager.sanitizeSegment(null, "默认"))
    }

    @Test
    fun `buildSystemDownloadsRelativePath respects groupByCourse setting`() {
        assertEquals(
            "Download/usst-jwgl-client/高等数学/",
            CoursewareStorageManager.buildSystemDownloadsRelativePath("高等数学", groupByCourse = true)
        )
        assertEquals(
            "Download/usst-jwgl-client/",
            CoursewareStorageManager.buildSystemDownloadsRelativePath("高等数学", groupByCourse = false)
        )
        assertEquals(
            "Download/usst-jwgl-client/",
            CoursewareStorageManager.buildSystemDownloadsRelativePath("  ", groupByCourse = true)
        )
    }

    @Test
    fun `formatTreeUriDisplayPath formats primary and external SAF segments`() {
        assertEquals(
            "内部存储/Download/畅课课件",
            CoursewareStorageManager.formatTreeUriDisplayPath("primary:Download/%E7%95%85%E8%AF%BE%E8%AF%BE%E4%BB%B6")
        )
        assertEquals(
            "内部存储",
            CoursewareStorageManager.formatTreeUriDisplayPath("primary:")
        )
        assertEquals(
            "外部存储/Courses",
            CoursewareStorageManager.formatTreeUriDisplayPath("1A2B-3C4D:Courses")
        )
    }

    @Test
    fun `resolveMimeType maps common courseware extensions`() {
        assertEquals("application/pdf", CoursewareStorageManager.resolveMimeType("讲义.pdf"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            CoursewareStorageManager.resolveMimeType("slides.PPTX")
        )
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            CoursewareStorageManager.resolveMimeType("实验报告.docx")
        )
        assertEquals("application/zip", CoursewareStorageManager.resolveMimeType("code.zip"))
        assertEquals("video/mp4", CoursewareStorageManager.resolveMimeType("record.mp4"))
    }

    @Test
    fun `legacy TronClassDownloadedItem json without storageLocation or mimeType does not throw NPE`() {
        val legacyJson = """
            {
              "fileId": "12345",
              "fileName": "第一讲.pdf",
              "courseId": 10,
              "courseName": "高等数学",
              "filePath": "/data/user/0/bsf.usst.jwgl/files/Download/TronClass/12345_第一讲.pdf",
              "fileSize": 1024,
              "downloadTime": 1700000000000
            }
        """.trimIndent()
        val item = com.google.gson.Gson().fromJson(legacyJson, TronClassDownloadedItem::class.java)
        assertEquals("", item.getSafeStorageLocation())
        assertEquals("", item.getSafeMimeType())
        assertEquals(false, item.isContentUri())
        assertEquals("pdf", item.getExtension())
    }
}
