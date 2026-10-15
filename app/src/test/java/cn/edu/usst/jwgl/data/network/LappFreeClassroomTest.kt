package cn.edu.usst.jwgl.data.network

import cn.edu.usst.jwgl.data.model.LappCourseItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LappFreeClassroomTest {

    @Test
    fun testIsCourseActiveInWeek() {
        // Course running only in weeks 1-4
        val pastCourse = LappCourseItem(
            kcmc = "测试短期课程",
            zc = "1,2,3,4",
            qsz = 1,
            jsz = 4
        )
        assertTrue(LappClient.isCourseActiveInWeek(pastCourse, 1))
        assertTrue(LappClient.isCourseActiveInWeek(pastCourse, 4))
        // Target week 6 -> must be INACTIVE
        assertFalse(LappClient.isCourseActiveInWeek(pastCourse, 6))

        // Course running in even weeks
        val evenCourse = LappCourseItem(
            kcmc = "双周实验课",
            zc = "2,4,6,8,10",
            qsz = 2,
            jsz = 10
        )
        assertTrue(LappClient.isCourseActiveInWeek(evenCourse, 6))
        assertFalse(LappClient.isCourseActiveInWeek(evenCourse, 5))

        // Full semester course (1-18)
        val fullCourse = LappCourseItem(
            kcmc = "高等数学",
            qsz = 1,
            jsz = 18
        )
        assertTrue(LappClient.isCourseActiveInWeek(fullCourse, 6))
        assertFalse(LappClient.isCourseActiveInWeek(fullCourse, 19))
    }

    @Test
    fun testMatchesSection() {
        // Morning period 1-2
        val morningCourse = LappCourseItem(
            kcmc = "大学英语",
            jc = 1,
            jccd = 2
        )
        assertTrue(LappClient.matchesSection(morningCourse, "1-2"))
        assertFalse(LappClient.matchesSection(morningCourse, "3-5"))
        assertFalse(LappClient.matchesSection(morningCourse, "8-9"))

        // Afternoon period 8-10 (jc=8, jccd=3)
        val afternoonCourse = LappCourseItem(
            kcmc = "物理实验",
            jc = 8,
            jccd = 3
        )
        assertTrue(LappClient.matchesSection(afternoonCourse, "8-9"))
        assertFalse(LappClient.matchesSection(afternoonCourse, "1-2"))
        assertFalse(LappClient.matchesSection(afternoonCourse, "6-7"))

        // Evening period 11-13 (jc=11, jccd=3)
        val eveningCourse = LappCourseItem(
            kcmc = "通识选修",
            jc = 11,
            jccd = 3
        )
        assertTrue(LappClient.matchesSection(eveningCourse, "10-12"))
        assertFalse(LappClient.matchesSection(eveningCourse, "1-2"))
    }
}
