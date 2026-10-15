package cn.edu.usst.jwgl.data.local

import cn.edu.usst.jwgl.data.wakeup.LabReservation
import cn.edu.usst.jwgl.data.wakeup.TimeDetailBean
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object LabReservationFileImporter {
    private const val MAX_INPUT_BYTES = 30 * 1024 * 1024
    private const val MAX_TEXT_BYTES = 4 * 1024 * 1024
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val OLE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())

    data class Inspection(
        val preview: LabReservationWorkbookImporter.ImportPreview,
        val report: XlsxCleaner.Report?,
        val removedUtf8Bom: Boolean
    )

    fun inspect(
        input: InputStream,
        cacheDir: File,
        semesterStartDate: String,
        maxWeek: Int,
        maxNodes: Int,
        timeDetails: List<TimeDetailBean>
    ): Inspection {
        val bytes = input.use(::readBounded)
        require(bytes.isNotEmpty()) { "所选文件为空" }
        val hasBom = bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals(BOM)
        val offset = if (hasBom) 3 else 0
        if (bytes.size >= offset + 2 && bytes[offset] == 0x50.toByte() && bytes[offset + 1] == 0x4B.toByte()) {
            val report = XlsxCleaner.clean(bytes.inputStream(), cacheDir)
            try {
                val preview = LabReservationWorkbookImporter.parse(
                    report.cleanedFile, semesterStartDate, maxWeek, maxNodes, timeDetails
                )
                return Inspection(preview, report, report.removedUtf8Bom)
            } catch (error: Exception) {
                report.cleanedFile.delete()
                throw error
            }
        }
        if (bytes.size >= offset + 4 && bytes.copyOfRange(offset, offset + 4).contentEquals(OLE)) {
            throw IOException("暂不支持二进制 XLS，请在 Excel 中另存为 XLSX 后导入")
        }
        require(bytes.size <= MAX_TEXT_BYTES) { "旧版 Excel 文本文件超过 4 MB 限制" }
        val text = decodeText(bytes.copyOfRange(offset, bytes.size)).trimStart()
        val rows = LabReservationExportParser.readTableRows(text)
        val dateTable = rows.any { row -> row.any { it.trim() == "日期" } }
        val preview = if (dateTable) {
            LabReservationWorkbookImporter.parseRows(
                rows.map { row -> row.mapIndexed { column, value -> column to value }.toMap() },
                semesterStartDate, maxWeek, maxNodes, timeDetails
            )
        } else {
            val export = LabReservationExportParser.parseExportText(text, text)
            require(export.week in 1..maxWeek) { "导出的周次超出默认课表范围" }
            require(export.reservations.isNotEmpty()) { "预约表中没有可导入的数据行" }
            require(export.reservations.all { it.startNode + it.step - 1 <= maxNodes }) { "导出的节次超出默认课表范围" }
            LabReservationWorkbookImporter.ImportPreview(
                export.reservations.map { LabReservation(it.name, it.day, it.startNode, it.step, it.week) }, 0
            )
        }
        return Inspection(preview, null, hasBom)
    }

    private fun decodeText(bytes: ByteArray): String {
        fun decode(charset: Charset): String = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        return runCatching { decode(StandardCharsets.UTF_8) }.getOrElse {
            runCatching { decode(Charset.forName("GB18030")) }.getOrElse {
                throw IOException("预约表文字编码无法识别，请另存为 XLSX 后导入")
            }
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_INPUT_BYTES) throw IOException("文件超过 30 MB 限制")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
