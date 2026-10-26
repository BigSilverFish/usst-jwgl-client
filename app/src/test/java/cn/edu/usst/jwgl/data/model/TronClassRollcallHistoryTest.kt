package cn.edu.usst.jwgl.data.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TronClassRollcallHistoryTest {

    private val gson = Gson()

    @Test
    fun testStudentRollcallStatusRecognition() {
        val attended = TronClassStudentRollcall(
            id = 101L,
            title = "第一周上机签到",
            studentStatus = "on_call",
            type = "radar"
        )
        assertTrue(attended.isAttended())
        assertFalse(attended.isAbsent())
        assertFalse(attended.isLateOrEarly())
        assertEquals("已到", attended.getStatusDisplayName())
        assertEquals("雷达定位", attended.getTypeDisplayName())

        val attendedFine = TronClassStudentRollcall(
            id = 1011L,
            title = "正常出勤",
            studentStatus = "on_call_fine",
            type = "number"
        )
        assertTrue(attendedFine.isAttended())
        assertEquals("已到", attendedFine.getStatusDisplayName())

        val late = TronClassStudentRollcall(
            id = 102L,
            title = "第二周理论课签到",
            studentStatus = "on_call_arrive_late",
            type = "number"
        )
        assertFalse(late.isAttended())
        assertTrue(late.isLateOrEarly())
        assertEquals("迟到", late.getStatusDisplayName())
        assertEquals("数字口令", late.getTypeDisplayName())

        val absent = TronClassStudentRollcall(
            id = 103L,
            title = "第三周课堂签到",
            studentStatus = "absent",
            type = "qr_code"
        )
        assertTrue(absent.isAbsent())
        assertEquals("缺勤", absent.getStatusDisplayName())
        assertEquals("二维码", absent.getTypeDisplayName())

        val onLeave = TronClassStudentRollcall(
            id = 104L,
            studentStatus = "on_leave"
        )
        assertTrue(onLeave.isLeave())
        assertEquals("请假", onLeave.getStatusDisplayName())
    }

    @Test
    fun testJsonDeserializationAndFormattedTime() {
        val json = """
            {
                "rollcall_id": 998877,
                "title": "移动端综合实验课签到",
                "rollcall_time": "2026-03-24 10:15:30",
                "type": "radar",
                "is_radar": true,
                "status": "on_call",
                "student_status": "on_call"
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, TronClassStudentRollcall::class.java)
        assertEquals(998877L, parsed.getEffectiveId())
        assertEquals("移动端综合实验课签到", parsed.getDisplayTitle())
        assertEquals("2026-03-24 10:15:30", parsed.getFormattedTime())
        assertTrue(parsed.isRadar)
        assertTrue(parsed.isAttended())
    }

    @Test
    fun testAttendanceStatsCalculation() {
        val list = listOf(
            TronClassStudentRollcall(id = 1L, studentStatus = "on_call"),
            TronClassStudentRollcall(id = 2L, studentStatus = "on_call"),
            TronClassStudentRollcall(id = 3L, studentStatus = "on_call"),
            TronClassStudentRollcall(id = 4L, studentStatus = "on_call"),
            TronClassStudentRollcall(id = 5L, studentStatus = "on_call"),
            TronClassStudentRollcall(id = 6L, studentStatus = "absent")
        )

        val total = list.size
        val attended = list.count { it.isAttended() }
        val abnormal = list.count { it.isAbsent() || it.isLateOrEarly() }
        val ratePercent = if (total > 0) ((attended * 100f) / total).toInt() else 100

        assertEquals(6, total)
        assertEquals(5, attended)
        assertEquals(1, abnormal)
        assertEquals(83, ratePercent)
    }
}
