package cn.edu.usst.jwgl.util

import cn.edu.usst.jwgl.data.wakeup.CourseUtils
import cn.edu.usst.jwgl.data.wakeup.TableBean
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AutoScheduleSortAndTargetTest {

    @Test
    fun testCompareSemestersDescending() {
        val t1 = TableBean(id = 1, tableName = "2024-2025学年 第1学期", startDate = "2024-09-02", maxWeek = 20)
        val t2 = TableBean(id = 2, tableName = "2024-2025学年 第2学期", startDate = "2025-02-24", maxWeek = 20)
        val t3 = TableBean(id = 3, tableName = "2025-2026学年 第1学期", startDate = "2025-09-01", maxWeek = 20)
        val t4 = TableBean(id = 4, tableName = "2025-2026学年 第2学期", startDate = "2026-02-23", maxWeek = 20)
        val t5 = TableBean(id = 5, tableName = "2026-2027学年 第1学期", startDate = "2026-09-07", maxWeek = 20)
        val t6 = TableBean(id = 6, tableName = "2026-2027学年 第2学期", startDate = "2027-02-22", maxWeek = 20)

        val list = listOf(t1, t3, t5, t2, t6, t4)
        val sorted = list.sortedWith { a, b -> CourseUtils.compareSemestersDescending(a, b) }

        // Expected order: t6 (2027-02), t5 (2026-09), t4 (2026-02), t3 (2025-09), t2 (2025-02), t1 (2024-09)
        assertEquals(listOf(t6, t5, t4, t3, t2, t1).map { it.id }, sorted.map { it.id })
    }

    @Test
    fun testOngoingSemesterTarget() {
        val tPast = TableBean(id = 1, tableName = "2025-2026学年 第2学期", startDate = "2026-02-23", maxWeek = 20)
        val tOngoing = TableBean(id = 2, tableName = "2026-2027学年 第1学期", startDate = "2026-09-07", maxWeek = 20)
        val tFuture = TableBean(id = 3, tableName = "2026-2027学年 第2学期", startDate = "2027-02-22", maxWeek = 20)
        val tables = listOf(tPast, tOngoing, tFuture)

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        // Week 1 Wednesday: 2026-09-09
        val targetWeek1 = CourseUtils.findAutoScheduleTarget(tables, sdf.parse("2026-09-09")!!)
        assertEquals(2, targetWeek1?.table?.id)
        assertEquals(1, targetWeek1?.week)

        // Week 3 Monday: 2026-09-21
        val targetWeek3 = CourseUtils.findAutoScheduleTarget(tables, sdf.parse("2026-09-21")!!)
        assertEquals(2, targetWeek3?.table?.id)
        assertEquals(3, targetWeek3?.week)

        // Week 20 Friday: 2027-01-22
        val targetWeek20 = CourseUtils.findAutoScheduleTarget(tables, sdf.parse("2027-01-22")!!)
        assertEquals(2, targetWeek20?.table?.id)
        assertEquals(20, targetWeek20?.week)
    }

    @Test
    fun testVacationTargetNextUpcomingSemesterWeek1() {
        val tPast = TableBean(id = 1, tableName = "2025-2026学年 第2学期", startDate = "2026-02-23", maxWeek = 20)
        val tNext1 = TableBean(id = 2, tableName = "2026-2027学年 第1学期", startDate = "2026-09-07", maxWeek = 20)
        val tNext2 = TableBean(id = 3, tableName = "2026-2027学年 第2学期", startDate = "2027-02-22", maxWeek = 20)
        val tables = listOf(tPast, tNext1, tNext2)

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        // Summer vacation: 2026-08-15 (between 2026-02 semester end ~2026-07-12 and 2026-09-07 start)
        val summerTarget = CourseUtils.findAutoScheduleTarget(tables, sdf.parse("2026-08-15")!!)
        assertEquals(2, summerTarget?.table?.id) // Next semester is 2026-2027-1
        assertEquals(1, summerTarget?.week) // Week 1

        // Winter vacation: 2027-02-05 (between 2027-01-25 and 2027-02-22)
        val winterTarget = CourseUtils.findAutoScheduleTarget(tables, sdf.parse("2027-02-05")!!)
        assertEquals(3, winterTarget?.table?.id) // Next semester is 2026-2027-2
        assertEquals(1, winterTarget?.week) // Week 1
    }

    @Test
    fun testFallbackWhenAllSemestersPast() {
        val t1 = TableBean(id = 1, tableName = "2024-2025学年 第1学期", startDate = "2024-09-02", maxWeek = 20)
        val t2 = TableBean(id = 2, tableName = "2024-2025学年 第2学期", startDate = "2025-02-24", maxWeek = 20)
        val tables = listOf(t1, t2)

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        // Way in the future (e.g. 2026-01-01)
        val target = CourseUtils.findAutoScheduleTarget(tables, sdf.parse("2026-01-01")!!)
        assertEquals(2, target?.table?.id) // Fallback to latest semester
        assertEquals(1, target?.week)
    }
}
