package cn.edu.usst.jwgl.data.local

import cn.edu.usst.jwgl.data.model.AccountProfile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.*
import org.junit.Test

class AccountVaultTest {

    private val gson = Gson()

    @Test
    fun testAccountProfileSerialization() {
        val original = AccountProfile(
            studentId = "2100000001",
            studentName = "测试同学",
            college = "光电信息与计算机工程学院",
            major = "计算机科学与技术",
            className = "计科211",
            lastActiveTime = 1700000000000L,
            isAutoLogin = true
        )

        val json = gson.toJson(original)
        assertFalse(json.contains("password")) // 确保元数据不包含密码字段

        val deserialized = gson.fromJson(json, AccountProfile::class.java)
        assertEquals(original.studentId, deserialized.studentId)
        assertEquals(original.studentName, deserialized.studentName)
        assertEquals(original.college, deserialized.college)
        assertEquals(original.major, deserialized.major)
        assertEquals(original.className, deserialized.className)
        assertEquals(original.isAutoLogin, deserialized.isAutoLogin)
    }

    @Test
    fun testAccountsOrderingByLastActiveTime() {
        val acc1 = AccountProfile("1001", studentName = "A", lastActiveTime = 1000L)
        val acc2 = AccountProfile("1002", studentName = "B", lastActiveTime = 3000L)
        val acc3 = AccountProfile("1003", studentName = "C", lastActiveTime = 2000L)

        val list = listOf(acc1, acc2, acc3)
        val sorted = list.sortedByDescending { it.lastActiveTime }

        assertEquals("1002", sorted[0].studentId)
        assertEquals("1003", sorted[1].studentId)
        assertEquals("1001", sorted[2].studentId)
    }

    @Test
    fun testDataCacheKeyPrefixSeparation() {
        fun buildProfileKey(studentId: String): String {
            val prefix = if (studentId.isNotBlank()) "${studentId}_" else ""
            return prefix + "cache_profile_json"
        }

        fun buildTimetableKey(studentId: String, semester: String): String {
            val prefix = if (studentId.isNotBlank()) "${studentId}_" else ""
            return prefix + "cache_timetable_json_" + semester
        }

        val keyUserA = buildProfileKey("2021001")
        val keyUserB = buildProfileKey("2021002")
        val keyLegacy = buildProfileKey("")

        assertNotEquals(keyUserA, keyUserB)
        assertEquals("2021001_cache_profile_json", keyUserA)
        assertEquals("2021002_cache_profile_json", keyUserB)
        assertEquals("cache_profile_json", keyLegacy)

        val tableKeyA = buildTimetableKey("2021001", "2026-2027_1")
        val tableKeyB = buildTimetableKey("2021002", "2026-2027_1")
        assertNotEquals(tableKeyA, tableKeyB)
    }
}
