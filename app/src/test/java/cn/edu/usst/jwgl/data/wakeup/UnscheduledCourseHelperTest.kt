package cn.edu.usst.jwgl.data.wakeup

import cn.edu.usst.jwgl.data.model.CourseItem
import cn.edu.usst.jwgl.data.model.TimetableData
import cn.edu.usst.jwgl.data.model.TronClassCourse
import cn.edu.usst.jwgl.data.model.UnscheduledCourseItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnscheduledCourseHelperTest {

    @Test
    fun `collectCandidates prioritizes unadded unscheduled courses and appends TronClass extras`() {
        val timetable = TimetableData(
            academicYear = "2026",
            semester = "3",
            semesterTitle = "2026-2027学年 第1学期",
            studentName = "周智轩",
            className = "计算机24120801班",
            courses = listOf(
                CourseItem(
                    id = "1",
                    name = "Web应用开发",
                    teacher = "李锐",
                    classroom = "一教132",
                    dayOfWeek = 3,
                    startSection = 8,
                    step = 3,
                    rawSections = "8-10",
                    weeks = (3..18).toList(),
                    rawWeeks = "3-18周",
                    classCode = "(2026-2027-1)-12003550-01"
                )
            ),
            unscheduledCourses = listOf(
                UnscheduledCourseItem(
                    name = "电路原理实验",
                    teacher = "符晓",
                    classCode = "(2026-2027-1)-12102050-02",
                    courseCode = "12102050",
                    examType = "考查"
                ),
                UnscheduledCourseItem(
                    name = "Web应用开发实验",
                    teacher = "李锐",
                    classCode = "(2026-2027-1)-12102090-01",
                    courseCode = "12102090",
                    examType = "考查"
                )
            )
        )

        val tronCourses = listOf(
            // Already scheduled in timetable -> should not duplicate
            TronClassCourse(
                id = 101L,
                name = "Web应用开发(2026-2027-1)-12003550-01",
                courseCode = "(2026-2027-1)-12003550-01",
                teacher = "李锐"
            ),
            // Different semester -> should filter out
            TronClassCourse(
                id = 102L,
                name = "电路原理(2025-2026-1)-12002050-01",
                courseCode = "(2025-2026-1)-12002050-01",
                teacher = "吴世青"
            ),
            // Non-semester _uuid_ activity -> should filter out
            TronClassCourse(
                id = 104L,
                name = "大学物理先修课",
                courseCode = "_uuid_223c926e-515e-46f9-b1b4-ca6b32ac007b",
                teacher = "李小丹"
            ),
            // Same semester, neither scheduled nor in table -> should appear as fallback
            TronClassCourse(
                id = 103L,
                name = "专业导论专题(2026-2027-1)-12009990-01",
                courseCode = "(2026-2027-1)-12009990-01",
                teacher = "王老师"
            )
        )

        val existingInDb = listOf(
            CourseBaseBean(id = 1, courseName = "Web应用开发"),
            CourseBaseBean(id = 2, courseName = "电路原理实验", isLocked = true)
        )

        val candidates = UnscheduledCourseHelper.collectCandidates(
            timetableData = timetable,
            tronClassCourses = tronCourses,
            existingCourses = existingInDb,
            expectedSemesterCode = "2026-2027-1"
        )

        assertEquals(3, candidates.size)
        // Unadded items must appear before alreadyInTable items
        assertEquals("Web应用开发实验", candidates[0].name)
        assertFalse(candidates[0].alreadyInTable)
        assertEquals("李锐", candidates[0].teacher)
        assertEquals("(2026-2027-1)-12102090-01", candidates[0].classCode)

        assertEquals("专业导论专题", candidates[1].name)
        assertFalse(candidates[1].alreadyInTable)
        assertEquals("一网畅学", candidates[1].sourceLabel)

        assertEquals("电路原理实验", candidates[2].name)
        assertTrue(candidates[2].alreadyInTable)
    }
}
