package cn.edu.usst.jwgl.data.wakeup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 验证多账号切换时课表数据库命名与分桶隔离规则
 */
class MultiAccountScheduleIsolationTest {

    @Test
    fun testDatabaseNamingPartitionByStudentId() {
        val dbNameA = AppDatabase.getDatabaseName("2435055230")
        val dbNameB = AppDatabase.getDatabaseName("2435055231")
        val dbNameLegacy = AppDatabase.getDatabaseName("")
        val dbNameNull = AppDatabase.getDatabaseName(null)

        assertEquals("wakeup_2435055230.db", dbNameA)
        assertEquals("wakeup_2435055231.db", dbNameB)
        assertNotEquals(dbNameA, dbNameB)

        // 空学号回退到默认全局 wakeup.db 兼容单账号
        assertEquals("wakeup.db", dbNameLegacy)
        assertEquals("wakeup.db", dbNameNull)
    }

    @Test
    fun testDatabaseNameSanitizesSpecialCharacters() {
        val dbNameSpecial = AppDatabase.getDatabaseName("24350/..\\55230")
        assertEquals("wakeup_2435055230.db", dbNameSpecial)
    }
}
