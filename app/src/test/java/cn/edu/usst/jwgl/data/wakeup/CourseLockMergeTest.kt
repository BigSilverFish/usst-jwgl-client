package cn.edu.usst.jwgl.data.wakeup

import cn.edu.usst.jwgl.data.model.CourseItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseLockMergeTest {

    private fun dummyCourseItem(name: String, day: Int = 1): CourseItem {
        return CourseItem(
            id = "${name}_$day",
            name = name,
            teacher = "测试教师",
            classroom = "一教101",
            dayOfWeek = day,
            startSection = 1,
            step = 2,
            rawSections = "1-2",
            weeks = listOf(1, 2, 3),
            rawWeeks = "1-3周"
        )
    }

    @Test
    fun testComputeMergePlan_preservesLockedAndSkipsOverwriting() {
        val existingBases = listOf(
            CourseBaseBean(id = 1, courseName = "软件工程", isLocked = false),
            CourseBaseBean(id = 2, courseName = "Web应用开发实验", isLocked = true), // Locked manual course at ID 2
            CourseBaseBean(id = 3, courseName = "人工智能A", isLocked = false),
            CourseBaseBean(id = 4, courseName = "自主体育锻炼", isLocked = true) // Locked custom course at ID 4
        )

        val networkCourses = listOf(
            dummyCourseItem("软件工程", 1),
            dummyCourseItem("Web应用开发实验", 5), // Network has same name, should NOT overwrite locked ID 2
            dummyCourseItem("人工智能A", 3),
            dummyCourseItem("数字图像处理A", 2)
        )

        val plan = WakeupScheduleImporter.computeMergePlan(existingBases, networkCourses)

        // Only unlocked IDs (1, 3) should be scheduled for deletion
        assertEquals(listOf(1, 3), plan.courseIdsToDelete)
        assertEquals(setOf("Web应用开发实验", "自主体育锻炼"), plan.preservedCourseNames)

        // Network assignments should skip "Web应用开发实验", leaving 3 courses
        assertEquals(3, plan.newCourseAssignments.size)

        val assignedIds = plan.newCourseAssignments.map { it.first }
        val assignedNames = plan.newCourseAssignments.map { it.second.first }

        // Locked IDs 2 and 4 must never be reused! Available IDs starting from 1: 1, 3, 5
        assertEquals(listOf(1, 3, 5), assignedIds)
        assertFalse(assignedIds.contains(2))
        assertFalse(assignedIds.contains(4))
        assertEquals(listOf("软件工程", "人工智能A", "数字图像处理A"), assignedNames)
    }

    @Test
    fun testComputeMergePlan_allUnlockedClearsAll() {
        val existingBases = listOf(
            CourseBaseBean(id = 1, courseName = "老课1", isLocked = false),
            CourseBaseBean(id = 2, courseName = "老课2", isLocked = false)
        )
        val networkCourses = listOf(
            dummyCourseItem("新课A", 1)
        )

        val plan = WakeupScheduleImporter.computeMergePlan(existingBases, networkCourses)
        assertEquals(listOf(1, 2), plan.courseIdsToDelete)
        assertTrue(plan.preservedCourseNames.isEmpty())
        assertEquals(1, plan.newCourseAssignments.size)
        assertEquals(1, plan.newCourseAssignments[0].first)
        assertEquals("新课A", plan.newCourseAssignments[0].second.first)
    }
}
