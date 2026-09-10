package cn.edu.usst.jwgl.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class SemesterHelperTest {

    @Test
    fun testCalculateCurrentWeekWithCustomDates() {
        // Suppose Week 1 Monday is 2026-03-02
        val week1Monday = "2026-03-02"

        // Exactly on Week 1 Monday
        val calWeek1 = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 2, 10, 0, 0)
        }
        val week = SemesterHelper.calculateCurrentWeek(week1Monday, 16, calWeek1)
        assertEquals(1, week)

        // On Week 3 Wednesday (2026-03-18)
        val calWeek3 = Calendar.getInstance().apply {
            set(2026, Calendar.MARCH, 18, 14, 30, 0)
        }
        val week3 = SemesterHelper.calculateCurrentWeek(week1Monday, 16, calWeek3)
        assertEquals(3, week3)

        // Far in the future exceeds totalWeeks (e.g. 2026-10-01) clamped to totalWeeks
        val calFar = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 1, 10, 0, 0)
        }
        val weekClamped = SemesterHelper.calculateCurrentWeek(week1Monday, 20, calFar)
        assertEquals(20, weekClamped)
    }

    @Test
    fun testGetDatesForWeek() {
        val week1Monday = "2026-02-23"
        val week1Dates = SemesterHelper.getDatesForWeek(week1Monday, 1)
        assertEquals(7, week1Dates.size)

        // Monday of week 1 should be 2026-02-23
        assertEquals(2026, week1Dates[0].get(Calendar.YEAR))
        assertEquals(Calendar.FEBRUARY, week1Dates[0].get(Calendar.MONTH))
        assertEquals(23, week1Dates[0].get(Calendar.DAY_OF_MONTH))

        // Sunday of week 1 should be 2026-03-01
        assertEquals(2026, week1Dates[6].get(Calendar.YEAR))
        assertEquals(Calendar.MARCH, week1Dates[6].get(Calendar.MONTH))
        assertEquals(1, week1Dates[6].get(Calendar.DAY_OF_MONTH))
    }

    @Test
    fun testParseSemesterFromTableName() {
        // Standard formats
        val sem1 = SemesterHelper.parseSemesterFromTableName("2026-2027学年 第1学期")
        assertEquals(Pair("2026", "3"), sem1)

        val sem2 = SemesterHelper.parseSemesterFromTableName("2025-2026学年 第2学期")
        assertEquals(Pair("2025", "12"), sem2)

        val sem3 = SemesterHelper.parseSemesterFromTableName("2024-2025-1")
        assertEquals(Pair("2024", "3"), sem3)

        val sem4 = SemesterHelper.parseSemesterFromTableName("2023-2024-2")
        assertEquals(Pair("2023", "12"), sem4)

        val sem5 = SemesterHelper.parseSemesterFromTableName("2025-2026学年第3学期")
        assertEquals(Pair("2025", "16"), sem5)

        val semNull = SemesterHelper.parseSemesterFromTableName("我的自定义课表")
        assertEquals(null, semNull)
    }

    @Test
    fun testGetSemestersToSync() {
        val testCal = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 10)
        }
        // Student enrolled in 2024 (like 2435055230)
        val list = SemesterHelper.getSemestersToSync(2024, testCal)
        assertEquals(5, list.size)
        assertEquals("2024", list[0].xnm)
        assertEquals("3", list[0].xqm)
        assertEquals("2024", list[1].xnm)
        assertEquals("12", list[1].xqm)
        assertEquals("2025", list[2].xnm)
        assertEquals("3", list[2].xqm)
        assertEquals("2025", list[3].xnm)
        assertEquals("12", list[3].xqm)
        assertEquals("2026", list[4].xnm)
        assertEquals("3", list[4].xqm)
        assertTrue(list[4].isCurrent)
    }
}
