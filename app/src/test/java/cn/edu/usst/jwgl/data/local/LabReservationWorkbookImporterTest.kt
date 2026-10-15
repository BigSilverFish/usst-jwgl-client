package cn.edu.usst.jwgl.data.local

import cn.edu.usst.jwgl.data.wakeup.TimeDetailBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LabReservationWorkbookImporterTest {
    @Test
    fun parsesSharedStringAppointmentWorkbookIntoTimetableSlots() {
        val file = workbookFile("2026-10-29")
        try {
            val result = LabReservationWorkbookImporter.parse(
                file = file,
                semesterStartDate = "2026-09-07",
                maxWeek = 20,
                maxNodes = 13,
                timeDetails = timeDetails()
            )

            assertEquals(2, result.reservations.size)
            assertEquals(8, result.reservations[0].week)
            assertEquals(1, result.reservations[0].startNode)
            assertEquals(2, result.reservations[0].step)
            assertEquals("H212", result.reservations[0].room)
            assertEquals(9, result.reservations[1].week)
            assertEquals(3, result.reservations[1].startNode)
            assertEquals("H213", result.reservations[1].room)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsImpossibleCalendarDatesInsteadOfNormalizingThem() {
        val file = workbookFile("2026-02-30")
        try {
            assertThrows(IOException::class.java) {
                LabReservationWorkbookImporter.parse(
                    file = file,
                    semesterStartDate = "2026-09-07",
                    maxWeek = 20,
                    maxNodes = 13,
                    timeDetails = timeDetails()
                )
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun cleansBomPrefixedDownloadedWorkbookBeforeParsingAppointments() {
        val file = workbookFile("2026-10-29")
        val directory = kotlin.io.path.createTempDirectory("lab-download-").toFile()
        try {
            val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + file.readBytes()
            val inspected = LabReservationFileImporter.inspect(
                bytes.inputStream(), directory, "2026-09-07", 20, 13, timeDetails()
            )
            val result = inspected.preview
            assertEquals(true, inspected.removedUtf8Bom)
            assertEquals(2, result.reservations.size)
            assertEquals(8, result.reservations[0].week)
        } finally {
            file.delete()
            directory.deleteRecursively()
        }
    }

    private fun timeDetails() = listOf(
        TimeDetailBean(1, "08:00", "08:40"),
        TimeDetailBean(2, "08:45", "09:25"),
        TimeDetailBean(3, "09:45", "10:25"),
        TimeDetailBean(4, "10:30", "11:10")
    )

    private fun workbookFile(firstDate: String): File {
        val strings = listOf(
            "日期", "时间", "课程", "实验项目", "实验室", "星期", firstDate,
            "08:00-09:25", "热工实验", "风机性能测试", "H212", "星期四",
            "2026-11-05", "09:45-11:10", "流体力学", "管路阻力实验", "H213"
        )
        val sharedStrings = strings.joinToString("") { "<si><t>$it</t></si>" }
        val rows = listOf(
            row(1, 0, 1, 2, 3, 4, 5),
            row(2, 6, 7, 8, 9, 10, 11),
            row(3, 12, 13, 14, 15, 16, 11)
        ).joinToString("")
        val entries = linkedMapOf(
            "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
            "xl/workbook.xml" to "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Appointments\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            "xl/sharedStrings.xml" to "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">$sharedStrings</sst>",
            "xl/worksheets/sheet1.xml" to "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>$rows</sheetData></worksheet>"
        )
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return File.createTempFile("lab-import-", ".xlsx").apply { writeBytes(bytes.toByteArray()) }
    }

    private fun row(number: Int, vararg values: Int): String = buildString {
        append("<row r=\"$number\">")
        values.forEachIndexed { column, value ->
            append("<c r=\"${'A' + column}$number\" t=\"s\"><v>$value</v></c>")
        }
        append("</row>")
    }
}
