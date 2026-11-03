package cn.edu.usst.jwgl.ui.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfViewerUtilsTest {

    @Test
    fun testIsGenericTitle() {
        assertTrue(PdfViewerUtils.isGenericTitle(null))
        assertTrue(PdfViewerUtils.isGenericTitle(""))
        assertTrue(PdfViewerUtils.isGenericTitle("   "))
        assertTrue(PdfViewerUtils.isGenericTitle("上理教务"))
        assertTrue(PdfViewerUtils.isGenericTitle("文档查看"))
        assertTrue(PdfViewerUtils.isGenericTitle("PDF 阅览"))
        assertTrue(PdfViewerUtils.isGenericTitle("PDF阅览"))
        assertTrue(PdfViewerUtils.isGenericTitle("PDF 阅读器"))
        assertTrue(PdfViewerUtils.isGenericTitle("temp_17300000.pdf"))

        assertFalse(PdfViewerUtils.isGenericTitle("高数第一周作业.pdf"))
        assertFalse(PdfViewerUtils.isGenericTitle("计算机网络期末复习.pdf"))
        assertFalse(PdfViewerUtils.isGenericTitle("temp_17300000_教学大纲.pdf"))
    }

    @Test
    fun testCleanFileName() {
        // 普通文件名
        assertEquals("作业.pdf", PdfViewerUtils.cleanFileName("作业.pdf"))

        // 去除 URL 参数与哈希
        assertEquals("test.pdf", PdfViewerUtils.cleanFileName("https://example.com/docs/test.pdf?token=123&t=456#page=1"))

        // Unix 路径与 Windows 路径
        assertEquals("report.pdf", PdfViewerUtils.cleanFileName("/storage/emulated/0/Download/report.pdf"))
        assertEquals("schedule.pdf", PdfViewerUtils.cleanFileName("C:\\Users\\User\\Documents\\schedule.pdf"))

        // URL 编码解码
        assertEquals("线性代数讲义.pdf", PdfViewerUtils.cleanFileName("https://example.com/%E7%BA%BF%E6%80%A7%E4%BB%A3%E6%95%B0%E8%AE%B2%E4%B9%89.pdf"))

        // 剥离 temp_时间戳_ 前缀
        assertEquals("大学物理实验.pdf", PdfViewerUtils.cleanFileName("temp_1730602458836_大学物理实验.pdf"))
    }

    @Test
    fun testParseContentDispositionFileName() {
        // 标准带双引号
        assertEquals(
            "syllabus.pdf",
            PdfViewerUtils.parseContentDispositionFileName("""attachment; filename="syllabus.pdf"""")
        )

        // 标准不带引号
        assertEquals(
            "exam_notice.pdf",
            PdfViewerUtils.parseContentDispositionFileName("attachment; filename=exam_notice.pdf; size=1024")
        )

        // RFC 5987 / RFC 6266 UTF-8 编码
        assertEquals(
            "上海理工大学课程设计说明书.pdf",
            PdfViewerUtils.parseContentDispositionFileName(
                "attachment; filename*=UTF-8''%E4%B8%8A%E6%B5%B7%E7%90%86%E5%B7%A5%E5%A4%A7%E5%AD%A6%E8%AF%BE%E7%A8%8B%E8%AE%BE%E8%AE%A1%E8%AF%B4%E6%98%8E%E4%B9%A6.pdf"
            )
        )

        // 空或无效
        assertEquals(null, PdfViewerUtils.parseContentDispositionFileName(null))
        assertEquals(null, PdfViewerUtils.parseContentDispositionFileName(""))
        assertEquals(null, PdfViewerUtils.parseContentDispositionFileName("inline"))
    }

    @Test
    fun testResolveTitlePriority() {
        // 1. 显式传参有真实名字优先使用
        assertEquals(
            "软件工程作业1.pdf",
            PdfViewerUtils.resolveTitle(
                explicitTitle = "软件工程作业1.pdf",
                rawPath = "https://example.com/raw.pdf",
                contentDisposition = """attachment; filename="different.pdf""""
            )
        )

        // 2. 显式传参是通用占位符“上理教务”，应退回 Content-Disposition 响应头
        assertEquals(
            "编译原理实验大纲.pdf",
            PdfViewerUtils.resolveTitle(
                explicitTitle = "上理教务",
                rawPath = "https://example.com/file?id=999",
                contentDisposition = """attachment; filename="编译原理实验大纲.pdf""""
            )
        )

        // 3. 显式传参为空，无 Content-Disposition，从 URI display_name 获取
        assertEquals(
            "外语成绩单.pdf",
            PdfViewerUtils.resolveTitle(
                explicitTitle = null,
                rawPath = "content://com.android.providers.media.documents/document/123",
                contentUriDisplayName = "外语成绩单.pdf"
            )
        )

        // 4. 从 rawPath 中提取
        assertEquals(
            "概率论与数理统计.pdf",
            PdfViewerUtils.resolveTitle(
                explicitTitle = "",
                rawPath = "/storage/emulated/0/Download/%E6%A6%82%E7%8E%87%E8%AE%BA%E4%B8%8E%E6%95%B0%E7%90%86%E7%BB%9F%E8%AE%A1.pdf"
            )
        )

        // 5. 全部缺失或均为占位符，返回兜底
        assertEquals(
            "PDF 阅览",
            PdfViewerUtils.resolveTitle(
                explicitTitle = "上理教务",
                rawPath = ""
            )
        )
    }
}
