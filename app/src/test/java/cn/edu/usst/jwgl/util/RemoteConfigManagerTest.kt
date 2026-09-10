package cn.edu.usst.jwgl.util

import cn.edu.usst.jwgl.data.model.DateRange
import cn.edu.usst.jwgl.data.model.AppConfig
import cn.edu.usst.jwgl.data.model.AppVersionInfo
import cn.edu.usst.jwgl.data.model.ScheduleAdjustment
import cn.edu.usst.jwgl.data.remote.RemoteConfigManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteConfigManagerTest {

    @Test
    fun testTodayDateStringFormat() {
        val todayStr = RemoteConfigManager.getTodayDateString()
        assertTrue("Format must be YYYY-MM-DD", todayStr.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
    }

    @Test
    fun testGetAdjustmentsForDate() {
        val testConfig = AppConfig(
            appVersion = AppVersionInfo(versionCode = 2, versionName = "1.0.1"),
            adjustments = listOf(
                ScheduleAdjustment(
                    id = "mid_autumn",
                    name = "中秋节",
                    type = "HOLIDAY_OFF",
                    dates = listOf("2026-09-25")
                ),
                ScheduleAdjustment(
                    id = "national_day",
                    name = "国庆节",
                    type = "HOLIDAY_OFF",
                    dateRange = DateRange(start = "2026-10-01", end = "2026-10-07")
                ),
                ScheduleAdjustment(
                    id = "national_day_swap",
                    name = "国庆调休",
                    type = "SWAP_WEEKDAY",
                    date = "2026-10-10",
                    targetWeekday = 4,
                    targetWeek = 5,
                    remark = "补周四课"
                )
            )
        )

        // Inject config directly for testing
        val configField = RemoteConfigManager::class.java.getDeclaredField("cachedConfig")
        configField.isAccessible = true
        configField.set(RemoteConfigManager, testConfig)

        // 1. Single date match
        val midAutumnAdj = RemoteConfigManager.getAdjustmentsForDate("2026-09-25")
        assertEquals(1, midAutumnAdj.size)
        assertEquals("中秋节", midAutumnAdj[0].name)
        assertEquals("HOLIDAY_OFF", midAutumnAdj[0].type)

        // 2. Date range match
        val nationalDayAdjStart = RemoteConfigManager.getAdjustmentsForDate("2026-10-01")
        assertEquals(1, nationalDayAdjStart.size)
        assertEquals("国庆节", nationalDayAdjStart[0].name)

        val nationalDayAdjMid = RemoteConfigManager.getAdjustmentsForDate("2026-10-04")
        assertEquals(1, nationalDayAdjMid.size)
        assertEquals("国庆节", nationalDayAdjMid[0].name)

        val nationalDayAdjEnd = RemoteConfigManager.getAdjustmentsForDate("2026-10-07")
        assertEquals(1, nationalDayAdjEnd.size)
        assertEquals("国庆节", nationalDayAdjEnd[0].name)

        // Outside range
        val afterNationalDay = RemoteConfigManager.getAdjustmentsForDate("2026-10-08")
        assertEquals(0, afterNationalDay.size)

        // 3. Swap weekday match
        val swapAdj = RemoteConfigManager.getAdjustmentsForDate("2026-10-10")
        assertEquals(1, swapAdj.size)
        assertEquals("SWAP_WEEKDAY", swapAdj[0].type)
        assertEquals(4, swapAdj[0].targetWeekday)
        assertEquals("补周四课", swapAdj[0].remark)

        // Blank or non-matching date
        assertTrue(RemoteConfigManager.getAdjustmentsForDate("").isEmpty())
        assertTrue(RemoteConfigManager.getAdjustmentsForDate("2026-11-11").isEmpty())
    }

    @Test
    fun testIsUpdateAvailable() {
        val testConfig = AppConfig(
            appVersion = AppVersionInfo(versionCode = 5, versionName = "1.2.0")
        )
        val configField = RemoteConfigManager::class.java.getDeclaredField("cachedConfig")
        configField.isAccessible = true
        configField.set(RemoteConfigManager, testConfig)

        assertTrue(RemoteConfigManager.isUpdateAvailable(4))
        assertFalse(RemoteConfigManager.isUpdateAvailable(5))
        assertFalse(RemoteConfigManager.isUpdateAvailable(6))
    }
}
