package cn.edu.usst.jwgl.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastTimetableParserTest {

    @Test
    fun testParseFastTimetableJson_normalAndMultiSegment() {
        val sampleJson = """
        {
            "totalCount": 3,
            "items": [
                {
                    "kcmc": "Web应用开发",
                    "kch": "12003550",
                    "xf": "3.0",
                    "kcxz": "专业课程",
                    "khfs": "考试",
                    "jsxx": "03672/李锐/讲师",
                    "sksj": "星期三第8-10节{3-18周}",
                    "jxdd": "一教132"
                },
                {
                    "kcmc": "高等数学A(1)",
                    "kch": "11001010",
                    "xf": "5.5",
                    "kcxz": "学科基础课程",
                    "khfs": "考试",
                    "jsxx": "12345/张老师/教授",
                    "sksj": "星期一第1-2节{3-18周};星期三第4-5节{3-18周};星期五第1-2节{3-18周}",
                    "jxdd": "申二教210;申二教210;申二教210"
                },
                {
                    "kcmc": "Web应用开发实验",
                    "kch": "12102090",
                    "xf": "1.0",
                    "kcxz": "实践必修",
                    "khfs": "考查",
                    "jsxx": "03672/李锐/讲师",
                    "sksj": null,
                    "jxdd": null
                }
            ]
        }
        """.trimIndent()

        val data = JwglClient.parseFastTimetableJson(
            body = sampleJson,
            xnm = "2026",
            xqm = "3",
            studentName = "周智轩",
            className = "计算机24120801班",
            currentWeek = 10
        )

        assertEquals("2026", data.academicYear)
        assertEquals("3", data.semester)
        assertEquals("2026-2027学年 第1学期", data.semesterTitle)
        assertEquals("周智轩", data.studentName)
        assertEquals("计算机24120801班", data.className)
        assertEquals(10, data.currentWeek)

        // Web应用开发: 1 slot; 高等数学: 3 slots; 实验: 0 slots -> Total 4 slots
        assertEquals(4, data.courses.size)

        // Check Web应用开发
        val web = data.courses.find { it.name == "Web应用开发" }
        assertNotNull(web)
        assertEquals("12003550", web!!.courseCode)
        assertEquals("李锐", web.teacher)
        assertEquals("一教132", web.classroom)
        assertEquals(3, web.dayOfWeek)
        assertEquals(8, web.startSection)
        assertEquals(3, web.step)
        assertEquals("8-10", web.rawSections)
        assertEquals("3-18周", web.rawWeeks)
        assertEquals((3..18).toList(), web.weeks)
        assertEquals(3.0, web.credit, 0.01)
        assertEquals("考试", web.examType)

        // Check 高等数学 slots
        val mathSlots = data.courses.filter { it.name == "高等数学A(1)" }
        assertEquals(3, mathSlots.size)

        val monSlot = mathSlots.find { it.dayOfWeek == 1 }
        assertNotNull(monSlot)
        assertEquals(1, monSlot!!.startSection)
        assertEquals(2, monSlot.step)
        assertEquals("申二教210", monSlot.classroom)
        assertEquals("张老师", monSlot.teacher)

        val wedSlot = mathSlots.find { it.dayOfWeek == 3 }
        assertNotNull(wedSlot)
        assertEquals(4, wedSlot!!.startSection)
        assertEquals(2, wedSlot.step)

        val friSlot = mathSlots.find { it.dayOfWeek == 5 }
        assertNotNull(friSlot)
        assertEquals(1, friSlot!!.startSection)
        assertEquals(2, friSlot.step)
    }

    @Test
    fun testParseFastTimetableJson_emptyAndNulls() {
        val emptyJson = """{"totalCount": 0, "items": []}"""
        val data = JwglClient.parseFastTimetableJson(
            body = emptyJson,
            xnm = "2025",
            xqm = "12"
        )
        assertEquals("2025-2026学年 第2学期", data.semesterTitle)
        assertTrue(data.courses.isEmpty())
    }
}
