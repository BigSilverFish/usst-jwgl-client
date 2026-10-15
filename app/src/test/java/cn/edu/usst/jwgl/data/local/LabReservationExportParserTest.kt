package cn.edu.usst.jwgl.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class LabReservationExportParserTest {
    @Test
    fun parsesCurrentWeekGridAndIgnoresEmptyCells() {
        val csv = """
            上课时间,星期一,星期二,星期三,星期四,星期五
            "(1-2) 08:00~09:25","热工实验","-","-","-","-"
            "(3-4) 09:45~11:10","-","流体实验","-","-","-"
        """.trimIndent()

        val result = LabReservationExportParser.parseCsv(csv, "我的预约 第05周 2026-10-05 ~ 2026-10-09")

        assertEquals(5, result.week)
        assertEquals(2, result.reservations.size)
        assertEquals(LabReservationExportParser.Reservation("热工实验", 1, 1, 2, 5), result.reservations[0])
        assertEquals(LabReservationExportParser.Reservation("流体实验", 2, 3, 2, 5), result.reservations[1])
    }

    @Test
    fun decodesLayuiExcelDataUrl() {
        val csv = "上课时间,星期一\n\"(1-2) 08:00~09:25\",热工实验"
        val dataUrl = "data:application/vnd.ms-excel;charset=utf-8,%EF%BB%BF" +
            URLEncoder.encode(csv, StandardCharsets.UTF_8.name())

        val result = LabReservationExportParser.parseDataUrl(dataUrl, "第05周")

        assertEquals("热工实验", result.reservations.single().name)
        assertEquals(5, result.week)
    }

    @Test
    fun decodesUrlEncodedCsvFromBlobDownload() {
        val csv = "上课时间,星期一\n\"(1-2) 08:00~09:25\",热工实验"
        val encoded = URLEncoder.encode(csv, StandardCharsets.UTF_8.name())
        val dataUrl = "data:text/csv;charset=utf-8,$encoded"

        val result = LabReservationExportParser.parseDataUrl(dataUrl, "第05周")

        assertEquals("热工实验", result.reservations.single().name)
        assertEquals(5, result.week)
    }

    @Test
    fun parsesLayuiExcelHtmlDataUrl() {
        val html = "<table><tr><th>上课时间</th><th>星期一</th></tr>" +
            "<tr><td>(1-2) 08:00~09:25</td><td>热工实验</td></tr></table>"
        val dataUrl = "data:application/vnd.ms-excel;charset=utf-8," +
            URLEncoder.encode(html, StandardCharsets.UTF_8.name())

        val result = LabReservationExportParser.parseDataUrl(dataUrl, "第05周")

        assertEquals(1, result.reservations.size)
        assertEquals("热工实验", result.reservations.single().name)
        assertEquals(5, result.week)
    }

    @Test
    fun supportsQuotedCommasAndMultilineCells() {
        val csv = "上课时间,星期一\r\n\"(5-5)\\n时段\",\"燃烧,实验\""
            .replace("\\n", "\n")

        val result = LabReservationExportParser.parseCsv(csv, "第2周")

        assertEquals("燃烧,实验", result.reservations.single().name)
        assertEquals(5, result.reservations.single().startNode)
        assertEquals(1, result.reservations.single().step)
    }

    @Test
    fun returnsAnEmptyResultForAValidWeekWithNoReservations() {
        val csv = "上课时间,星期一,星期二\n\"(1-2) 08:00~09:25\",-,—"

        val result = LabReservationExportParser.parseCsv(csv, "第05周")

        assertTrue(result.reservations.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsExportWithoutAVisibleWeek() {
        LabReservationExportParser.parseCsv("上课时间,星期一\n\"(1-2)\",预约", "我的预约")
    }
}
