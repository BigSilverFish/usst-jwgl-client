package cn.edu.usst.jwgl.data.network

import cn.edu.usst.jwgl.data.model.LiveDeviceViewDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class LiveUsstJsonParserTest {

    private fun shanghaiMillis(dateTime: String): Long {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            isLenient = false
        }.parse(dateTime)!!.time
    }

    @Test
    fun parseUnionSearchResponse_handlesFormattedDateStringsFromScreenshot() {
        val json = """
            {
              "status": 1,
              "data": {
                "records": [
                  {
                    "id": 10086,
                    "teclId": "T2026001",
                    "subjName": "高等数学A",
                    "clroName": "一教101",
                    "teclTeacNames": ["张老师"],
                    "courBeginTime": "2026-11-25 16:15:00",
                    "courEndTime": "2026-11-25 17:50:00",
                    "courStatus": 0
                  }
                ],
                "rowCount": 1,
                "pageCount": 1,
                "todayBeginTime": "2026-11-25 00:00:00"
              }
            }
        """.trimIndent()

        val resp = LiveUsstJsonParser.parseUnionSearchResponse(json)
        assertEquals(1, resp.status)
        val data = resp.data
        assertNotNull(data)
        assertEquals(shanghaiMillis("2026-11-25 00:00:00"), data!!.todayBeginTime)

        val record = data.records!!.single()
        assertEquals(10086L, record.id)
        assertEquals("高等数学A", record.subjName)
        assertEquals("一教101", record.clroName)
        assertEquals(listOf("张老师"), record.teclTeacNames)
        assertEquals(shanghaiMillis("2026-11-25 16:15:00"), record.courBeginTime)
        assertEquals(shanghaiMillis("2026-11-25 17:50:00"), record.courEndTime)

        assertTrue(record.isCurrentlyLive(shanghaiMillis("2026-11-25 16:39:00")))
        assertFalse(record.isCurrentlyLive(shanghaiMillis("2026-11-25 18:10:00")))
    }

    @Test
    fun parseFlexibleTimestampMillis_handlesEpochAndIsoVariants() {
        val expectedMillis = shanghaiMillis("2026-11-25 16:15:00")
        val expectedSeconds = expectedMillis / 1000L

        assertEquals(expectedMillis, LiveUsstJsonParser.parseFlexibleTimestampMillis(expectedMillis.toString()))
        assertEquals(expectedMillis, LiveUsstJsonParser.parseFlexibleTimestampMillis(expectedSeconds.toString()))
        assertEquals(expectedMillis, LiveUsstJsonParser.parseFlexibleTimestampMillis("2026-11-25T16:15:00+08:00"))
        assertEquals(expectedMillis, LiveUsstJsonParser.parseFlexibleTimestampMillis("2026/11/25 16:15:00"))
        assertNull(LiveUsstJsonParser.parseFlexibleTimestampMillis(""))
        assertNull(LiveUsstJsonParser.parseFlexibleTimestampMillis("null"))
        assertNull(LiveUsstJsonParser.parseFlexibleTimestampMillis(null as String?))
    }

    @Test
    fun parseUnionSearchResponse_handlesFlexibleTypesAndFallbackKeys() {
        val json = """
            {
              "code": "200",
              "data": {
                "list": [
                  {
                    "courseId": "900123",
                    "courseName": "大学物理",
                    "classroomName": "二教204",
                    "teacherNames": "李老师, 王老师",
                    "courBeginTime": 1795594500,
                    "courEndTime": 1795600200000,
                    "courStatus": "1"
                  }
                ],
                "total": "1"
              }
            }
        """.trimIndent()

        val resp = LiveUsstJsonParser.parseUnionSearchResponse(json)
        assertEquals(200, resp.status)
        assertEquals(1, resp.data?.rowCount)
        val record = resp.data?.records?.single()
        assertNotNull(record)
        assertEquals(900123L, record!!.id)
        assertEquals("大学物理", record.subjName)
        assertEquals("二教204", record.clroName)
        assertEquals(listOf("李老师", "王老师"), record.teclTeacNames)
        assertEquals(1795594500000L, record.courBeginTime)
        assertEquals(1795600200000L, record.courEndTime)
        assertEquals(1, record.courStatus)
    }

    @Test
    fun parseVideoInfoResponse_handlesStringifiedNumbersAndBooleanStatus() {
        val json = """
            {
              "status": 1,
              "code": "SUCCESS",
              "message": "ok",
              "data": {
                "teclId": "T2026001",
                "lvcrLiveStatus": true,
                "userIp": "10.1.1.1",
                "courseDeviceViewDtoList": [
                  {
                    "deviViewNum": "5",
                    "chanNameMainPlayUrl": "https://live.usst.edu.cn/stream/ppt.flv",
                    "mainTokenStr": "abc123",
                    "deviceStatus": "1"
                  }
                ]
              }
            }
        """.trimIndent()

        val resp = LiveUsstJsonParser.parseVideoInfoResponse(json)
        assertEquals(1, resp.status)
        assertNull(resp.code)
        assertEquals("ok", resp.message)
        val data = resp.data!!
        assertEquals(1, data.lvcrLiveStatus)
        val view = data.courseDeviceViewDtoList!!.single()
        assertEquals(5, view.deviViewNum)
        assertEquals(1, view.deviceStatus)
        assertEquals("https://live.usst.edu.cn/stream/ppt.flv?account_token=abc123", view.getFullPlayUrl())
    }

    @Test
    fun liveDeviceViewDto_getFullPlayUrl_handlesBlankAndExistingToken() {
        val blankToken = LiveDeviceViewDto(
            deviViewNum = 5,
            chanNameMainPlayUrl = " https://live.usst.edu.cn/a.flv ",
            mainTokenStr = "   ",
            chanGbidMain = null,
            deviceStatus = 1
        )
        assertEquals("https://live.usst.edu.cn/a.flv", blankToken.getFullPlayUrl())

        val existingToken = LiveDeviceViewDto(
            deviViewNum = 5,
            chanNameMainPlayUrl = "https://live.usst.edu.cn/a.flv?account_token=already",
            mainTokenStr = "newtok",
            chanGbidMain = null,
            deviceStatus = 1
        )
        assertEquals("https://live.usst.edu.cn/a.flv?account_token=already", existingToken.getFullPlayUrl())
    }

    @Test
    fun parseServerDateTimeMillis_handlesNumericAndFormattedStrings() {
        val numericJson = """{"timestamp": 1795594500}"""
        assertEquals(1795594500000L, LiveUsstJsonParser.parseServerDateTimeMillis(numericJson))

        val formattedJson = """{"data": {"todayBeginTime": "2026-11-25 00:00:00"}}"""
        assertEquals(shanghaiMillis("2026-11-25 00:00:00"), LiveUsstJsonParser.parseServerDateTimeMillis(formattedJson))
    }
}
