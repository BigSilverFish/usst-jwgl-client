package cn.edu.usst.jwgl.data.model

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class TronClassAttendanceStatsTest {

    private val gson = Gson()

    @Test
    fun testAttendanceStatsFormatting() {
        val stats = RollcallAttendanceStats(
            rollcallId = 123456L,
            presentCount = 38,
            totalCount = 45,
            lateCount = 2,
            absentCount = 4,
            myStatus = "on_call_fine"
        )

        assertEquals("已签到 38 / 45 人", stats.formatBadgeText())
        assertEquals(84, stats.presentRatePercent)
        assertEquals("已签到 38 / 45 人 (84%)", stats.formatSummary())
    }

    @Test
    fun testEmptyAttendanceStatsFormatting() {
        val emptyStats = RollcallAttendanceStats(
            rollcallId = 123456L,
            presentCount = 0,
            totalCount = 0
        )

        assertEquals("等待首位同学签到", emptyStats.formatBadgeText())
        assertEquals("暂无人签到", emptyStats.formatSummary())
        assertFalse(emptyStats.isAvailable)
    }

    @Test
    fun testJsonParsingSimulation() {
        val jsonString = """
            {
                "student_rollcalls": [
                    {
                        "student_id": 1001,
                        "student_name": "张三",
                        "student_no": "21350001",
                        "status": "on_call_fine"
                    },
                    {
                        "student_id": 1002,
                        "student_name": "李四",
                        "student_no": "21350002",
                        "status": "absent"
                    },
                    {
                        "student_id": 1003,
                        "student_name": "王五",
                        "student_no": "21350003",
                        "status": "late"
                    },
                    {
                        "student_id": 1004,
                        "student_name": "赵六",
                        "student_no": "21350004",
                        "status": "present"
                    }
                ]
            }
        """.trimIndent()

        val jsonObject = gson.fromJson(jsonString, JsonObject::class.java)
        val array = jsonObject.getAsJsonArray("student_rollcalls")
        assertNotNull(array)

        var total = 0
        var present = 0
        var late = 0
        var absent = 0
        var myStatus: String? = null

        val expectedAccount = "21350001"

        for (elem in array) {
            val item = elem.asJsonObject
            total++
            val status = item.get("status")?.asString?.lowercase().orEmpty()
            val userNo = item.get("student_no")?.asString.orEmpty()
            if (userNo == expectedAccount) {
                myStatus = status
            }

            when {
                status.contains("fine") || status == "present" || status == "attended" -> present++
                status.contains("late") -> late++
                status.contains("leave") -> {}
                else -> absent++
            }
        }

        val stats = RollcallAttendanceStats(
            rollcallId = 9999L,
            presentCount = present,
            totalCount = total,
            lateCount = late,
            absentCount = absent,
            myStatus = myStatus
        )

        assertEquals(4, stats.totalCount)
        assertEquals(2, stats.presentCount)
        assertEquals(1, stats.lateCount)
        assertEquals(1, stats.absentCount)
        assertEquals("on_call_fine", stats.myStatus)
        assertEquals("已签到 2 / 4 人", stats.formatBadgeText())
        assertEquals("已签到 2 / 4 人 (50%)", stats.formatSummary())
    }
}
