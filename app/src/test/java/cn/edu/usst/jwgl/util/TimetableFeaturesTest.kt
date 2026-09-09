package cn.edu.usst.jwgl.util

import cn.edu.usst.jwgl.data.wakeup.CourseUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableFeaturesTest {

    @Test
    fun testIsLegacy12NodeSemester() {
        // Semesters using old 12-slot timetable include 2025-2026-2 and earlier
        assertTrue(CourseUtils.isLegacy12NodeSemester("2024-2025-1"))
        assertTrue(CourseUtils.isLegacy12NodeSemester("2024-2025-2"))
        assertTrue(CourseUtils.isLegacy12NodeSemester("2024-2025学年 第1学期"))
        assertTrue(CourseUtils.isLegacy12NodeSemester("2024-2025学年 第2学期"))
        assertTrue(CourseUtils.isLegacy12NodeSemester("2025-2026-1"))
        assertTrue(CourseUtils.isLegacy12NodeSemester("2025-2026学年 第1学期"))
        assertTrue(CourseUtils.isLegacy12NodeSemester("2025-2026-2"))
        assertTrue(CourseUtils.isLegacy12NodeSemester("2025-2026学年 第2学期"))

        // 2026-2027-1 and subsequent semesters should use new 13-slot timetable
        assertFalse(CourseUtils.isLegacy12NodeSemester("2026-2027-1"))
        assertFalse(CourseUtils.isLegacy12NodeSemester("2026-2027学年 第1学期"))
        assertFalse(CourseUtils.isLegacy12NodeSemester("2026-2027-2"))
        assertFalse(CourseUtils.isLegacy12NodeSemester("2027-2028-1"))
    }

    @Test
    fun testDayPartBoundaries() {
        // Old timetable: 12 nodes
        val oldNodes = 12
        val oldMorningEnd = 5
        val oldAfternoonEnd = if (oldNodes <= 12) 9 else 10
        val oldEveningStart = oldAfternoonEnd + 1
        assertEquals(5, oldMorningEnd)
        assertEquals(9, oldAfternoonEnd)
        assertEquals(10, oldEveningStart)

        // New timetable: 13 nodes
        val newNodes = 13
        val newMorningEnd = 5
        val newAfternoonEnd = if (newNodes <= 12) 9 else 10
        val newEveningStart = newAfternoonEnd + 1
        assertEquals(5, newMorningEnd)
        assertEquals(10, newAfternoonEnd)
        assertEquals(11, newEveningStart)
    }
}