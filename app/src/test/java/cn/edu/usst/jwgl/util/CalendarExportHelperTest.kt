package cn.edu.usst.jwgl.util

import cn.edu.usst.jwgl.data.model.ExamItem
import cn.edu.usst.jwgl.data.wakeup.CourseBean
import cn.edu.usst.jwgl.data.wakeup.CourseUtils
import cn.edu.usst.jwgl.data.wakeup.TableBean
import cn.edu.usst.jwgl.data.wakeup.TimeDetailBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class CalendarExportHelperTest {

    @Test
    fun testScheduleIcsGeneration() {
        val table = TableBean(
            id = 1,
            tableName = "2026-2027学年第1学期",
            startDate = "2026-09-07",
            maxWeek = 16,
            nodes = 13,
            timeTable = 1,
            type = 1
        )

        val courses = listOf(
            CourseBean(
                id = 101,
                courseName = "高等数学A(1)",
                day = 1, // Monday
                room = "卓越楼402",
                teacher = "李教授",
                startNode = 1,
                step = 2,
                startWeek = 1,
                endWeek = 4,
                type = 0 // Every week
            ),
            CourseBean(
                id = 102,
                courseName = "大学英语(1)",
                day = 3, // Wednesday
                room = "卓越楼301",
                teacher = "王老师",
                startNode = 3,
                step = 2,
                startWeek = 1,
                endWeek = 4,
                type = 1 // Odd weeks only (week 1, week 3)
            )
        )

        val times = listOf(
            TimeDetailBean(1, "08:00", "08:45", 1),
            TimeDetailBean(2, "08:50", "09:35", 1),
            TimeDetailBean(3, "09:55", "10:40", 1),
            TimeDetailBean(4, "10:45", "11:30", 1)
        )

        val ics = CalendarExportHelper.buildScheduleIcs(table, courses, times)

        // 1. Structure assertions
        assertTrue("ICS must start with VCALENDAR", ics.startsWith("BEGIN:VCALENDAR"))
        assertTrue("ICS must have VERSION:2.0", ics.contains("VERSION:2.0"))
        assertTrue("ICS must have USST PRODID", ics.contains("PRODID:-//USST JWGL Assistant//CN"))
        assertTrue("ICS must declare Asia/Shanghai timezone", ics.contains("TZID:Asia/Shanghai"))
        assertTrue("ICS must end with END:VCALENDAR", ics.trimEnd().endsWith("END:VCALENDAR"))

        // 2. Event assertions
        assertTrue("ICS must contain course name", ics.contains("SUMMARY:高等数学A(1)"))
        assertTrue("ICS must contain room", ics.contains("LOCATION:卓越楼402"))
        assertTrue("ICS must contain teacher", ics.contains("李教授"))

        // Monday week 1 -> 2026-09-07 08:00 to 09:35
        assertTrue("ICS must have exact start time for week 1 Monday", ics.contains("DTSTART;TZID=Asia/Shanghai:20260907T080000"))
        assertTrue("ICS must have exact end time for week 1 Monday", ics.contains("DTEND;TZID=Asia/Shanghai:20260907T093500"))

        // 15-minute advance reminder
        assertTrue("ICS must contain 15m VALARM trigger", ics.contains("TRIGGER:-PT15M"))

        // Odd-week course (大学英语) in week 1 (Wednesday -> 2026-09-09)
        assertTrue("ICS must contain Wednesday course for week 1", ics.contains("DTSTART;TZID=Asia/Shanghai:20260909T095500"))
        // Odd-week course should NOT appear in week 2 (Wednesday -> 2026-09-16)
        assertTrue("ICS must skip even week for odd-only course", !ics.contains("DTSTART;TZID=Asia/Shanghai:20260916T095500"))
        // Odd-week course SHOULD appear in week 3 (Wednesday -> 2026-09-23)
        assertTrue("ICS must include week 3 for odd-only course", ics.contains("DTSTART;TZID=Asia/Shanghai:20260923T095500"))
    }

    @Test
    fun testExamIcsGeneration() {
        val exams = listOf(
            ExamItem(
                courseName = "大学物理B",
                courseCode = "12000510",
                examName = "期末考试",
                examTime = "2027-01-18 09:00-11:00",
                location = "一教201",
                seatNumber = "15",
                session = "第1场",
                examNature = "正常",
                examMethod = "闭卷",
                credit = 3.5
            ),
            ExamItem(
                courseName = "线性代数",
                courseCode = "22000150",
                examName = "期中考试",
                examTime = "2026-11-12 13:00-15:00",
                location = "卓越楼402",
                seatNumber = "28",
                session = "第2场",
                examNature = "正常",
                examMethod = "闭卷",
                credit = 2.5
            )
        )

        val ics = CalendarExportHelper.buildExamsIcs(exams)

        assertTrue("Exam ICS must start with VCALENDAR", ics.startsWith("BEGIN:VCALENDAR"))
        assertTrue("Exam ICS must include [考试] prefix", ics.contains("SUMMARY:【考试】大学物理B"))
        assertTrue("Exam ICS must format location with seat number", ics.contains("LOCATION:一教201 (座位号: 15)"))
        assertTrue("Exam ICS must have exact exam start time", ics.contains("DTSTART;TZID=Asia/Shanghai:20270118T090000"))
        assertTrue("Exam ICS must have exact exam end time", ics.contains("DTEND;TZID=Asia/Shanghai:20270118T110000"))
        assertTrue("Exam ICS must include 30-minute advance alarm", ics.contains("TRIGGER:-PT30M"))

        // Second exam
        assertTrue("Exam ICS must include linear algebra exam", ics.contains("SUMMARY:【考试】线性代数"))
        assertTrue("Exam ICS must include linear algebra start time", ics.contains("DTSTART;TZID=Asia/Shanghai:20261112T130000"))
        assertTrue("Exam ICS must include linear algebra end time", ics.contains("DTEND;TZID=Asia/Shanghai:20261112T150000"))
    }

    @Test
    fun testUnreleasedExamsIcsHandling() {
        val unreleasedExams = listOf(
            ExamItem(
                courseName = "毛泽东思想和中国特色社会主义理论体系概论",
                courseCode = "32000120",
                examName = "期末考试",
                examTime = "未放开不可查",
                location = "未放开不可查",
                seatNumber = "未放开不可查",
                session = "",
                examNature = "",
                examMethod = "考试",
                credit = 3.0
            )
        )

        val ics = CalendarExportHelper.buildExamsIcs(unreleasedExams)
        // Should produce valid VCALENDAR container without any VEVENT
        assertTrue(ics.startsWith("BEGIN:VCALENDAR"))
        assertTrue(ics.trimEnd().endsWith("END:VCALENDAR"))
        assertTrue("Should not have any VEVENT for unreleased exams", !ics.contains("BEGIN:VEVENT"))
    }

    @Test
    fun testColdStartWeekCountCalculation() {
        // Monday of week 1
        val startDate = "2026-09-07"

        // Target: 2026-10-16 (Friday of week 6)
        val targetCal = Calendar.getInstance(Locale.CHINA).apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.OCTOBER)
            set(Calendar.DAY_OF_MONTH, 16)
        }

        val week = CourseUtils.countWeek(startDate, targetCal)
        assertEquals("2026-10-16 relative to 2026-09-07 should be week 6", 6, week)

        // Before semester starts -> returns 1
        val beforeCal = Calendar.getInstance(Locale.CHINA).apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.AUGUST)
            set(Calendar.DAY_OF_MONTH, 20)
        }
        val beforeWeek = CourseUtils.countWeek(startDate, beforeCal)
        assertEquals("Date before semester should return 1", 1, beforeWeek)
    }
}
