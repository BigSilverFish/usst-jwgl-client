package cn.edu.usst.jwgl.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxCleanerTest {
    @Test
    fun removesLeadingUtf8BomAndPreservesWorkbookParts() {
        val directory = createTempDir(prefix = "xlsx-clean-")
        try {
            val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + validXlsx()
            val report = XlsxCleaner.clean(ByteArrayInputStream(bytes), directory)

            assertTrue(report.removedUtf8Bom)
            assertEquals(1, report.worksheetCount)
            assertEquals("PK", report.cleanedFile.inputStream().use { String(byteArrayOf(it.read().toByte(), it.read().toByte())) })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun acceptsValidWorkbookWithoutChangingIt() {
        val directory = createTempDir(prefix = "xlsx-clean-")
        try {
            val bytes = validXlsx()
            val report = XlsxCleaner.clean(ByteArrayInputStream(bytes), directory)
            assertFalse(report.removedUtf8Bom)
            assertEquals(bytes.toList(), report.cleanedFile.readBytes().toList())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsInvalidArchiveInsteadOfPretendingToRepairIt() {
        val directory = createTempDir(prefix = "xlsx-clean-")
        try {
            XlsxCleaner.clean(ByteArrayInputStream("not an xlsx".toByteArray()), directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun validXlsx(): ByteArray {
        val entries = linkedMapOf(
            "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
            "xl/workbook.xml" to "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            "xl/_rels/workbook.xml.rels" to "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
            "xl/worksheets/sheet1.xml" to "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"/>"
        )
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, xml) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(xml.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
