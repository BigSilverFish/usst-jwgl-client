package cn.edu.usst.jwgl.util

import cn.edu.usst.jwgl.data.model.ExamItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamHelperTest {

    @Test
    fun testSemesterTypeDetection() {
        assertEquals(1, ExamHelper.getSemesterType("2026-2027学年 第1学期"))
        assertEquals(1, ExamHelper.getSemesterType("2026-2027-1"))
        assertEquals(2, ExamHelper.getSemesterType("2026-2027学年 第2学期"))
        assertEquals(2, ExamHelper.getSemesterType("2025-2026-2"))
        assertEquals(0, ExamHelper.getSemesterType("自定义空白课表"))
    }

    @Test
    fun testIsExamWeek() {
        val sem1 = "2026-2027学年 第1学期"
        // Semester 1: Weeks 19 and 20 are exam weeks
        for (w in 1..18) {
            assertFalse("Week $w should not be exam week in sem 1", ExamHelper.isExamWeek(sem1, w))
        }
        assertTrue("Week 19 must be exam week in sem 1", ExamHelper.isExamWeek(sem1, 19))
        assertTrue("Week 20 must be exam week in sem 1", ExamHelper.isExamWeek(sem1, 20))
        assertFalse("Week 21 should not be exam week in sem 1", ExamHelper.isExamWeek(sem1, 21))

        val sem2 = "2026-2027学年 第2学期"
        // Semester 2: Weeks 17 and 18 are exam weeks
        for (w in 1..16) {
            assertFalse("Week $w should not be exam week in sem 2", ExamHelper.isExamWeek(sem2, w))
        }
        assertTrue("Week 17 must be exam week in sem 2", ExamHelper.isExamWeek(sem2, 17))
        assertTrue("Week 18 must be exam week in sem 2", ExamHelper.isExamWeek(sem2, 18))
        assertFalse("Week 19 should not be exam week in sem 2", ExamHelper.isExamWeek(sem2, 19))
    }

    @Test
    fun testIsExamSyncWindow() {
        val sem1 = "2026-2027学年 第1学期"
        // Semester 1: starts 4 weeks before week 19 (week 15) to week 20
        for (w in 1..14) {
            assertFalse("Week $w should not be in sync window for sem 1", ExamHelper.isExamSyncWindow(sem1, w))
        }
        for (w in 15..20) {
            assertTrue("Week $w must be in sync window for sem 1", ExamHelper.isExamSyncWindow(sem1, w))
        }
        assertFalse(ExamHelper.isExamSyncWindow(sem1, 21))

        val sem2 = "2026-2027学年 第2学期"
        // Semester 2: starts 4 weeks before week 17 (week 13) to week 18
        for (w in 1..12) {
            assertFalse("Week $w should not be in sync window for sem 2", ExamHelper.isExamSyncWindow(sem2, w))
        }
        for (w in 13..18) {
            assertTrue("Week $w must be in sync window for sem 2", ExamHelper.isExamSyncWindow(sem2, w))
        }
        assertFalse(ExamHelper.isExamSyncWindow(sem2, 19))
    }

    @Test
    fun testExamSessions() {
        val sessions = ExamHelper.SESSIONS
        assertEquals(3, sessions.size)
        assertEquals("09:00-11:00", sessions[0].timeRange)
        assertEquals("13:00-15:00", sessions[1].timeRange)
        assertEquals("15:30-17:30", sessions[2].timeRange)
    }

    @Test
    fun testExamItemParsingAndSessionIndex() {
        val item1 = ExamItem(
            courseName = "高等数学A(1)",
            examTime = "2027-01-18 09:00-11:00",
            location = "一教301",
            seatNumber = "25"
        )
        assertEquals("2027-01-18", item1.getDateString())
        assertEquals(1, item1.getSessionIndex())
        assertEquals("09:00-11:00", item1.getTimeRangeString())

        val item2 = ExamItem(
            courseName = "大学物理B",
            examTime = "2027-01-19 13:00-15:00",
            location = "二教205",
            seatNumber = "12"
        )
        assertEquals("2027-01-19", item2.getDateString())
        assertEquals(2, item2.getSessionIndex())
        assertEquals("13:00-15:00", item2.getTimeRangeString())

        val item3 = ExamItem(
            courseName = "离散数学",
            examTime = "2027-01-20(15:30-17:30)",
            location = "图文信息中心402",
            seatNumber = "3"
        )
        assertEquals("2027-01-20", item3.getDateString())
        assertEquals(3, item3.getSessionIndex())
        assertEquals("15:30-17:30", item3.getTimeRangeString())
    }
}
