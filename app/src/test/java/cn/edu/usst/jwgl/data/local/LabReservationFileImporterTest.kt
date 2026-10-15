package cn.edu.usst.jwgl.data.local

import cn.edu.usst.jwgl.data.wakeup.TimeDetailBean
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabReservationFileImporterTest {
    @Test
    fun importsLegacyExcelTextWithUtf8BomAndCleansItDuringImport() {
        val content = "\uFEFF我的预约 第05周\n上课时间\t星期一\t星期二\n(1-2) 08:00~09:25\t热工实验\t-"
        val result = LabReservationFileImporter.inspect(
            ByteArrayInputStream(content.toByteArray()),
            tempDir(), "2026-09-07", 20, 13, timeDetails()
        )

        assertEquals(1, result.preview.reservations.size)
        assertEquals("热工实验", result.preview.reservations.single().name)
        assertEquals(5, result.preview.reservations.single().week)
        assertTrue(result.removedUtf8Bom)
        assertEquals(null, result.report)
    }

    @Test
    fun rejectsBinaryLegacyXlsWithActionableConversionMessage() {
        val error = runCatching {
            LabReservationFileImporter.inspect(
                ByteArrayInputStream(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())),
                tempDir(), "2026-09-07", 20, 13, timeDetails()
            )
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(error?.message?.contains("另存为 XLSX") == true)
    }

    @Test
    fun importsBomPrefixedHtmlAppointmentTableAndRemovesDuplicateRows() {
        val row = "<tr><td>2026-10-29</td><td>08:00-09:25</td><td>热工实验</td><td>测试项目</td><td>H212</td><td>星期四</td></tr>"
        val html = "\uFEFF<table><tr><th>日期</th><th>时间</th><th>课程</th><th>实验项目</th><th>实验室</th><th>星期</th></tr>$row$row</table>"
        val result = LabReservationFileImporter.inspect(
            html.byteInputStream(), tempDir(), "2026-09-07", 20, 13, timeDetails()
        )
        assertEquals(1, result.preview.reservations.size)
        assertEquals(1, result.preview.skippedDuplicateRows)
        assertEquals(8, result.preview.reservations.single().week)
    }

    @Test
    fun refusesEmptyWeekExportSoItCannotClearExistingAppointments() {
        val content = "第05周\n上课时间,星期一\n\"(1-2)\",-"
        val error = runCatching {
            LabReservationFileImporter.inspect(content.byteInputStream(), tempDir(), "2026-09-07", 20, 13, timeDetails())
        }.exceptionOrNull()
        assertTrue(error?.message?.contains("没有可导入") == true)
    }

    @Test
    fun doesNotTreatTruncatedWorkbookAsTextOrLeaveRepairFilesBehind() {
        val directory = tempDir()
        val error = runCatching {
            LabReservationFileImporter.inspect(
                byteArrayOf(0x50, 0x4B, 0x03, 0x04).inputStream(), directory,
                "2026-09-07", 20, 13, timeDetails()
            )
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(0, directory.listFiles()?.size)
    }

    private fun timeDetails() = listOf(
        TimeDetailBean(1, "08:00", "08:40"),
        TimeDetailBean(2, "08:45", "09:25")
    )

    private fun tempDir(): File = kotlin.io.path.createTempDirectory("lab-file-import-").toFile()
        .also { it.deleteOnExit() }
}
