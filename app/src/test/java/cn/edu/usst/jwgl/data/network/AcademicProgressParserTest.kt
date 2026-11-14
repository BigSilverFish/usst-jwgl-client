package cn.edu.usst.jwgl.data.network

import cn.edu.usst.jwgl.data.model.AcademicCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicProgressParserTest {

    @Test
    fun testParseAcademicProgressHtml() {
        val html = """
            <li fxfyqjd_id=''>
                <p class='title1' id='pROOT' yxxf='50.5' yqzdxf='158.0' sftg='0'>" + "2025自动化类&nbsp;
            </li>
            <li fxfyqjd_id='ROOT'>
                <p class='title1' id='pTS' yxxf='20.0' yqzdxf='30.0' sftg='0'>" + "通识教育课程&nbsp;
            </li>
            <li fxfyqjd_id='TS'>
                <p class='title1' id='pSZ' yxxf='8.0' yqzdxf='8.0' sftg='1'>" + "思政类&nbsp;
            </li>
            <li fxfyqjd_id='ROOT'>
                <p class='title1' id='pZY' yxxf='30.5' yqzdxf='60.0' sftg='0'>" + "专业教育课程&nbsp;
            </li>
        """.trimIndent()

        val progress = JwglClient.parseAcademicProgress(html)
        assertEquals("2025自动化类", progress.majorName)
        assertEquals(50.5, progress.earned, 0.001)
        assertEquals(158.0, progress.required, 0.001)
        assertEquals(2, progress.categories.size)

        val ts = progress.categories.find { it.name == "通识教育课程" }!!
        assertEquals(20.0, ts.earned, 0.001)
        assertEquals(30.0, ts.required, 0.001)
        assertFalse(ts.passed)
        assertEquals(1, ts.children.size)

        val sz = ts.children[0]
        assertEquals("思政类", sz.name)
        assertEquals(8.0, sz.earned, 0.001)
        assertTrue(sz.passed)
    }

    @Test
    fun testParseAcademicCoursesJson() {
        val json = """
            [
                {"KCMC":"高等数学(1)","KCH":"MATH1001","XF":"5.0","XDZT":"4","CJ":"92","XNMC":"2025-2026","XQMMC":"1"},
                {"KCMC":"大学物理","KCH":"PHYS1001","XF":"3","XDZT":"1","XNMC":"2026-2027","XQMMC":"1"},
                {"KCMC":"电路原理","KCH":"EE2001","XF":"4","XDZT":"2","MAXCJ":"55"},
                {"KCMC":"数字逻辑","KCH":"CS2001","XF":"3.5","XDZT":"3"},
                {"KCMC":"工程学导论(2组)","KCH":"12004460","XF":"1.0","XDZT":"5","CJ":"90"}
            ]
        """.trimIndent()

        val list = JwglClient.parseAcademicCourses(json)
        assertEquals(5, list.size)

        assertEquals("高等数学(1)", list[0].name)
        assertEquals(5.0, list[0].credits, 0.001)
        assertEquals("已修", list[0].statusLabel)
        assertEquals("92", list[0].score)
        assertTrue(list[0].isPassed)

        assertEquals("在修", list[1].statusLabel)
        assertTrue(list[1].isInProgress)

        assertEquals("未过", list[2].statusLabel)
        assertEquals("55", list[2].score)
        assertTrue(list[2].isFailed)

        assertEquals("未修", list[3].statusLabel)
        assertTrue(list[3].isNotStarted)

        assertEquals("工程学导论(2组)", list[4].name)
        assertEquals(1.0, list[4].credits, 0.001)
        assertEquals("替换", list[4].statusLabel)
        assertEquals("90", list[4].score)
        assertTrue(list[4].isSubstituted)
    }
}
