package cn.edu.usst.jwgl.util

import cn.edu.usst.jwgl.data.model.StudentProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RankingPdfParserTest {

    @Test
    fun testOfficialUsstRankingPdfIfPresent() {
        val pdfFile = File("debug_usst_ranking.pdf")
        if (pdfFile.exists()) {
            val result = RankingPdfParser.parse(pdfFile, null)
            assertTrue("解析真实 USST 排名证明 PDF 应当成功", result.isSuccess)
            val info = result.getOrThrow()
            assertEquals("排名应精确匹配为 82", 82, info.rank)
            assertEquals("总人数应精确匹配为 97", 97, info.total)
            assertEquals("百分比应计算为 85", 85, info.percentile)
            assertTrue("专业应包含计算机", info.major.contains("计算机"))
            assertEquals("年级应匹配为 2024", "2024", info.grade)
        }
    }

    @Test
    fun testParseOfficialUsstSentence() {
        val text = "该生在该专业现排名为第 82 名，该专业共有学生 97 名。"
        val info = RankingPdfParser.parseTextContent(text, null)
        assertEquals(82, info.rank)
        assertEquals(97, info.total)
        assertEquals(85, info.percentile)
    }

    @Test
    fun testParseEnglishRankingSentence() {
        val text = "The student is ranked 82 out of 97 in Computer Science and Technology, Grade 2024."
        val info = RankingPdfParser.parseTextContent(text, null)
        assertEquals(82, info.rank)
        assertEquals(97, info.total)
        assertEquals(85, info.percentile)
    }

    @Test
    fun testParseTextContentPatternA() {
        val text = """
            上海理工大学
            学生成绩排名证明
            兹证明学生 周智轩（学号：2435055230），系我校光电信息与计算机工程学院 计算机科学与技术 专业 2024 级全日制在读本科生。
            在 计算机科学与技术 专业 2024 级共 97 人中，综合成绩排名第 82 名。
            特此证明。
        """.trimIndent()

        val info = RankingPdfParser.parseTextContent(text, null)
        assertEquals("计算机科学与技术", info.major)
        assertEquals("2024", info.grade)
        assertEquals("计算机科学与技术 2024级", info.majorAndGrade)
        assertEquals(82, info.rank)
        assertEquals(97, info.total)
        assertEquals(85, info.percentile) // Math.round(82.0 / 97.0 * 100) = 85
    }

    @Test
    fun testParseTextContentPatternB() {
        val text = "该生综合测评排名：82 / 97 名，百分比排位为 85 %。"
        val profile = StudentProfile(major = "软件工程", grade = "2023")
        val info = RankingPdfParser.parseTextContent(text, profile)

        assertEquals("软件工程", info.major)
        assertEquals("2023", info.grade)
        assertEquals("软件工程 2023级", info.majorAndGrade)
        assertEquals(82, info.rank)
        assertEquals(97, info.total)
        assertEquals(85, info.percentile)
    }

    @Test
    fun testParseMetadataTag() {
        val dummyPdfContent = """
            %PDF-1.4
            %USST_RANK:major=计算机科学与技术;grade=2024级;rank=82;total=97;percentile=85%
            %%EOF
        """.trimIndent()

        val tempFile = File.createTempFile("test_ranking_meta", ".pdf")
        try {
            tempFile.writeText(dummyPdfContent)
            val result = RankingPdfParser.parse(tempFile, null)
            assertTrue(result.isSuccess)
            val info = result.getOrThrow()
            assertEquals("计算机科学与技术", info.major)
            assertEquals("2024级", info.grade)
            assertEquals(82, info.rank)
            assertEquals(97, info.total)
            assertEquals(85, info.percentile)
        } finally {
            tempFile.delete()
            assertFalse(tempFile.exists())
        }
    }

    @Test
    fun testLifecycleDownloadParseDeletePrinciple() {
        val tempFile = File.createTempFile("test_lifecycle_ranking", ".pdf")
        tempFile.writeText("%PDF-1.4\n%USST_RANK:major=计算机科学与技术;grade=2024级;rank=82;total=97;percentile=85%\n")
        assertTrue(tempFile.exists())

        try {
            val result = RankingPdfParser.parse(tempFile, null)
            assertTrue(result.isSuccess)
            val info = result.getOrThrow()
            assertEquals(82, info.rank)
            assertEquals(97, info.total)
        } finally {
            // Principle: Delete PDF after parsing
            tempFile.delete()
        }

        assertFalse("PDF 文件在解析后必须被立即删除", tempFile.exists())
    }
}
