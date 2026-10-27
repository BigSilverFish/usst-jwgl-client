package cn.edu.usst.jwgl.data.network

import cn.edu.usst.jwgl.data.model.MultiAccountRollcallResult
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class TronClassMultiAccountRollcallTest {

    private val gson = Gson()

    @Test
    fun testMultiAccountRollcallResultSerialization() {
        val r1 = MultiAccountRollcallResult(
            studentId = "20210001",
            studentName = "张三",
            success = true,
            message = "口令签到成功！状态: 已签到",
            statusName = "已签到"
        )

        val json = gson.toJson(r1)
        val deserialized = gson.fromJson(json, MultiAccountRollcallResult::class.java)

        assertEquals("20210001", deserialized.studentId)
        assertEquals("张三", deserialized.studentName)
        assertTrue(deserialized.success)
        assertEquals("口令签到成功！状态: 已签到", deserialized.message)
        assertEquals("已签到", deserialized.statusName)
    }

    @Test
    fun testDeviceIdSeparationLogic() {
        // 模拟 TronClassPreferences 中 getDeviceId(studentId) 的逻辑
        val map = mutableMapOf<String, String>()

        fun getDeviceId(studentId: String): String {
            val key = "${studentId}_tc_device_id"
            return map.getOrPut(key) { UUID.randomUUID().toString() }
        }

        val devUser1 = getDeviceId("2100000001")
        val devUser2 = getDeviceId("2100000002")

        // 验证不同账号分配到的设备码互不相同（破除一机一签限制）
        assertNotEquals(devUser1, devUser2)

        // 验证同一账号重复获取返回固定值（符合稳定设备指纹特征）
        val devUser1Again = getDeviceId("2100000001")
        assertEquals(devUser1, devUser1Again)
    }

    @Test
    fun testMultiAccountSummaryFormatting() {
        val results = listOf(
            MultiAccountRollcallResult("2021001", "张三", true, "签到成功", "已出勤"),
            MultiAccountRollcallResult("2021002", "李四", true, "签到成功", "已出勤"),
            MultiAccountRollcallResult("2021003", "王五", false, "未选此门课程", "")
        )

        val successCount = results.count { it.success }
        val totalCount = results.size

        assertEquals(2, successCount)
        assertEquals(3, totalCount)

        val title = if (successCount == totalCount) {
            "🎉 全员签到成功 ($successCount/$totalCount)"
        } else {
            "📋 多账号签到结果 ($successCount/$totalCount 成功)"
        }

        assertEquals("📋 多账号签到结果 (2/3 成功)", title)
    }
}
