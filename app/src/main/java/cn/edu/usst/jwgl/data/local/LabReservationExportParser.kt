package cn.edu.usst.jwgl.data.local

import android.util.Base64
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.jsoup.Jsoup

object LabReservationExportParser {
    private const val MAX_EXPORT_CHARS = 4 * 1024 * 1024
    private const val MAX_ROWS = 2_000
    private const val MAX_COLUMNS = 32
    private const val MAX_CELL_CHARS = 4_096
    private const val MAX_RESERVATIONS = 500
    private val weekPattern = Regex("第\\s*0*(\\d{1,2})\\s*周")
    private val sectionPattern = Regex("[（(]\\s*(\\d{1,2})\\s*[-－~～至]\\s*(\\d{1,2})\\s*[）)]")

    data class Reservation(
        val name: String,
        val day: Int,
        val startNode: Int,
        val step: Int,
        val week: Int
    )

    data class Export(
        val week: Int,
        val reservations: List<Reservation>,
        val rowCount: Int
    )

    fun parseDataUrl(dataUrl: String, pageText: String): Export {
        require(dataUrl.length <= MAX_EXPORT_CHARS) { "导出内容超过大小限制" }
        val separator = dataUrl.indexOf(',')
        require(separator > 0) { "导出内容格式无效" }
        val metadata = dataUrl.substring(0, separator).lowercase()
        require(
            metadata.startsWith("data:application/vnd.ms-excel") ||
                metadata.startsWith("data:text/csv") ||
                metadata.startsWith("data:text/html")
        ) { "只支持选课系统导出的 Excel/CSV 内容" }

        val payload = dataUrl.substring(separator + 1)
        val text = if (metadata.split(';').any { it == "base64" }) {
            String(Base64.decode(payload, Base64.DEFAULT), StandardCharsets.UTF_8)
        } else {
            URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8")
        }
        return parseExportText(text, pageText)
    }

    fun parseExportText(text: String, pageText: String): Export {
        val content = text.removePrefix("\uFEFF").trimStart()
        return if (content.startsWith("<") && Regex("(?is)<table\\b").containsMatchIn(content)) {
            parseHtml(content, pageText)
        } else {
            parseCsv(content, pageText)
        }
    }

    private fun parseHtml(html: String, pageText: String): Export {
        val rows = readTableRows(html).map { row ->
            row.joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
        }
        require(rows.isNotEmpty()) { "导出的 Excel 中没有可读取的表格" }
        return parseCsv(rows.joinToString("\n"), pageText + "\n" + Jsoup.parse(html).text())
    }

    internal fun readTableRows(text: String): List<List<String>> {
        require(text.length <= MAX_EXPORT_CHARS) { "导出内容超过大小限制" }
        val content = text.removePrefix("\uFEFF").trimStart()
        if (!content.startsWith("<")) return readCsv(content)
        val rows = Jsoup.parse(content).select("table tr").map { row ->
            row.select("th, td").map { it.text() }
        }.filter { it.any(String::isNotBlank) }
        require(rows.size <= MAX_ROWS) { "导出表格行数异常" }
        require(rows.all { row -> row.size <= MAX_COLUMNS && row.all { it.length <= MAX_CELL_CHARS } }) {
            "导出表格单元格或列数超过限制"
        }
        return rows
    }

    fun parseCsv(csv: String, pageText: String): Export {
        require(csv.length <= MAX_EXPORT_CHARS) { "导出内容超过大小限制" }
        val rows = readCsv(csv.removePrefix("\uFEFF"))
        require(rows.size >= 2) { "导出表格没有数据行" }

        val headerIndex = rows.indexOfFirst { row ->
            row.any { it.contains("时间") } && row.any { dayFromHeader(it) != null }
        }
        require(headerIndex >= 0) { "未识别到预约表格的星期和时间列" }
        val headers = rows[headerIndex]
        val timeColumn = headers.indexOfFirst { it.contains("时间") }.takeIf { it >= 0 } ?: 0
        val dayColumns = headers.mapIndexedNotNull { index, value ->
            dayFromHeader(value)?.let { index to it }
        }
        require(dayColumns.isNotEmpty()) { "预约表格没有可识别的星期列" }

        val week = weekPattern.find(pageText)?.groupValues?.get(1)?.toIntOrNull()
            ?: weekPattern.find(csv)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IllegalArgumentException("页面没有显示当前周次，请停留在周视图后重新导出")
        require(week in 1..30) { "导出的周次无效" }

        val reservations = mutableListOf<Reservation>()
        for (row in rows.drop(headerIndex + 1)) {
            val section = row.getOrNull(timeColumn)?.let(::parseSection) ?: continue
            for ((column, day) in dayColumns) {
                val name = row.getOrNull(column)?.let(::cleanCell).orEmpty()
                if (name.isBlank() || name in EMPTY_CELLS) continue
                reservations += Reservation(
                    name = name,
                    day = day,
                    startNode = section.first,
                    step = section.second - section.first + 1,
                    week = week
                )
            }
        }

        val unique = reservations.distinctBy { listOf(it.day, it.startNode, it.week, it.name) }
        require(unique.size <= MAX_RESERVATIONS) { "预约条目数量异常" }
        return Export(week = week, reservations = unique, rowCount = rows.size - headerIndex - 1)
    }

    private fun readCsv(input: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var index = 0
        val headerLine = input.lineSequence().firstOrNull { it.contains("时间") }
            ?: input.lineSequence().firstOrNull { it.contains(',') || it.contains('\t') }.orEmpty()
        val delimiter = if ('\t' in headerLine && ',' !in headerLine) '\t' else ','

        fun addField() {
            require(field.length <= MAX_CELL_CHARS) { "导出表格单元格过长" }
            row += field.toString()
            field.setLength(0)
            require(row.size <= MAX_COLUMNS) { "导出表格列数异常" }
        }

        fun addRow() {
            addField()
            if (row.any { it.isNotBlank() }) rows += row
            row = mutableListOf()
            require(rows.size <= MAX_ROWS) { "导出表格行数异常" }
        }

        while (index < input.length) {
            val char = input[index]
            when {
                char == '"' && quoted && input.getOrNull(index + 1) == '"' -> {
                    field.append('"')
                    index++
                }
                char == '"' && quoted -> quoted = false
                char == '"' && field.isEmpty() -> quoted = true
                char == delimiter && !quoted -> addField()
                (char == '\n' || char == '\r') && !quoted -> {
                    if (char == '\r' && input.getOrNull(index + 1) == '\n') index++
                    addRow()
                }
                else -> field.append(char)
            }
            index++
        }
        require(!quoted) { "导出表格的引号格式不完整" }
        if (field.isNotEmpty() || row.isNotEmpty()) addRow()
        return rows
    }

    private fun parseSection(value: String): Pair<Int, Int>? {
        val match = sectionPattern.find(value) ?: return null
        val start = match.groupValues[1].toIntOrNull() ?: return null
        val end = match.groupValues[2].toIntOrNull() ?: return null
        return if (start in 1..13 && end in start..13) start to end else null
    }

    private fun dayFromHeader(value: String): Int? {
        val normalized = value.replace(" ", "")
        val chinese = Regex("(?:星期|周)([一二三四五六日天])").find(normalized)?.groupValues?.get(1)
        if (chinese != null) {
            return when (chinese) {
                "一" -> 1
                "二" -> 2
                "三" -> 3
                "四" -> 4
                "五" -> 5
                "六" -> 6
                else -> 7
            }
        }
        return when (Regex("(?i)\\b(mon|tue|wed|thu|fri|sat|sun)(?:day)?\\b").find(normalized)?.groupValues?.get(1)?.lowercase()) {
            "mon" -> 1
            "tue" -> 2
            "wed" -> 3
            "thu" -> 4
            "fri" -> 5
            "sat" -> 6
            "sun" -> 7
            else -> null
        }
    }

    private fun cleanCell(value: String): String = value
        .replace('\u00A0', ' ')
        .replace('\uFEFF', ' ')
        .lines()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(" ")
        .trim()

    private val EMPTY_CELLS = setOf("-", "—", "–", "无", "未预约", "/", "--")
}
