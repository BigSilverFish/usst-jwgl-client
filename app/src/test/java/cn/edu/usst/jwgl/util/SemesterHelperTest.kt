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
}
