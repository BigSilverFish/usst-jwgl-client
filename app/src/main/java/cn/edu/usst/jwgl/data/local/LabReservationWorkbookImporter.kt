package cn.edu.usst.jwgl.data.local

import cn.edu.usst.jwgl.data.wakeup.LabReservation
import cn.edu.usst.jwgl.data.wakeup.TimeDetailBean
import org.w3c.dom.Element
import java.io.File
import java.io.IOException
import java.net.URI
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipFile

object LabReservationWorkbookImporter {
    private const val MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val DOC_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val MAX_ROWS = 2_000
    private const val MAX_CELL_CHARS = 4_096
    private val cellRefPattern = Regex("^([A-Z]+)\\d+$")
    private val datePattern = Regex("^(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})日?$")
    private val timePattern = Regex("(\\d{1,2})\\s*(?:点|:|：)\\s*(\\d{1,2})\\s*分?")

    data class ImportPreview(
        val reservations: List<LabReservation>,
        val skippedBlankRows: Int,
        val skippedDuplicateRows: Int = 0
    )

    fun parse(
        file: File,
        semesterStartDate: String,
        maxWeek: Int,
        maxNodes: Int,
        timeDetails: List<TimeDetailBean>
    ): ImportPreview {
        require(maxWeek > 0 && maxNodes > 0) { "课表周次或节数配置无效" }
        val startDate = parseDate(semesterStartDate)
            ?: throw IOException("默认课表的开学日期无效：$semesterStartDate")

        val result = ZipFile(file).use { zip ->
            val sharedStrings = zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
                zip.getInputStream(entry).use { stream ->
                    val document = WorkbookXml.parse(stream.readBytes(), entry.name)
                    document.getElementsByTagNameNS(MAIN_NS, "si").let { items ->
                        (0 until items.length).map { index ->
                            val item = items.item(index) as Element
                            descendantsText(item, "t")
                        }
                    }
                }
            }.orEmpty()

            val workbook = zip.getEntry("xl/workbook.xml")
                ?: throw IOException("工作簿缺少 xl/workbook.xml")
            val workbookDoc = zip.getInputStream(workbook).use { WorkbookXml.parse(it.readBytes(), workbook.name) }
            val firstSheet = workbookDoc.getElementsByTagNameNS(MAIN_NS, "sheet").item(0) as? Element
                ?: throw IOException("工作簿没有工作表")
            val relationshipId = firstSheet.getAttributeNS(DOC_REL_NS, "id")
            if (relationshipId.isBlank()) throw IOException("工作表缺少关系标识")

            val relationshipsPath = "xl/_rels/workbook.xml.rels"
            val relationships = zip.getEntry(relationshipsPath)
                ?: throw IOException("工作簿缺少工作表关系文件")
            val relationshipsDoc = zip.getInputStream(relationships).use {
                WorkbookXml.parse(it.readBytes(), relationshipsPath)
            }
            val target = (0 until relationshipsDoc.getElementsByTagNameNS(REL_NS, "Relationship").length)
                .asSequence()
                .map { relationshipsDoc.getElementsByTagNameNS(REL_NS, "Relationship").item(it) as Element }
                .firstOrNull { it.getAttribute("Id") == relationshipId }
                ?.getAttribute("Target")
                ?.takeIf(String::isNotBlank)
                ?: throw IOException("找不到工作表对应关系")
            val targetUri = try {
                URI(target)
            } catch (error: Exception) {
                throw IOException("工作表路径格式无效", error)
            }
            if (targetUri.isAbsolute || targetUri.rawAuthority != null || targetUri.rawQuery != null || targetUri.rawFragment != null) {
                throw IOException("工作表路径不安全")
            }
            val sheetPath = if (target.startsWith('/')) target.drop(1) else
                URI("xl/workbook.xml").resolve(targetUri).normalize().path.trimStart('/')
            if (!sheetPath.startsWith("xl/worksheets/") || sheetPath.contains("../")) {
                throw IOException("工作表路径无效")
            }
            val sheetEntry = zip.getEntry(sheetPath) ?: throw IOException("找不到工作表：$sheetPath")
            val sheetDoc = zip.getInputStream(sheetEntry).use { WorkbookXml.parse(it.readBytes(), sheetPath) }
            parseRows(
                rows = readRows(sheetDoc, sharedStrings),
                startDate = startDate,
                maxWeek = maxWeek,
                maxNodes = maxNodes,
                timeDetails = timeDetails
            )
        }
        return result
    }

    fun parseRows(
        rows: List<Map<Int, String>>,
        semesterStartDate: String,
        maxWeek: Int,
        maxNodes: Int,
        timeDetails: List<TimeDetailBean>
    ): ImportPreview {
        require(maxWeek > 0 && maxNodes > 0) { "课表周次或节数配置无效" }
        val startDate = parseDate(semesterStartDate)
            ?: throw IOException("默认课表的开学日期无效")
        return parseRows(rows, startDate, maxWeek, maxNodes, timeDetails)
    }

    internal fun parseRows(
        rows: List<Map<Int, String>>,
        startDate: Calendar,
        maxWeek: Int,
        maxNodes: Int,
        timeDetails: List<TimeDetailBean>
    ): ImportPreview {
        require(rows.size <= MAX_ROWS) { "预约表行数超过限制" }
        val headerIndex = rows.indexOfFirst { row ->
            val headers = row.values.map(::normalizeHeader)
            headers.any { it == "日期" } && headers.any { it.contains("时间") } &&
                headers.any { it == "课程" } && headers.any { it.contains("项目") } &&
                headers.any { it.contains("实验室") || it == "地点" }
        }
        if (headerIndex < 0) throw IOException("未识别到预约表头，需要日期、时间、课程、实验项目和实验室列")

        val headers = rows[headerIndex].mapValues { normalizeHeader(it.value) }
        val dateCol = headers.entries.first { it.value == "日期" }.key
        val timeCol = headers.entries.first { it.value.contains("时间") }.key
        val courseCol = headers.entries.first { it.value == "课程" }.key
        val projectCol = headers.entries.first { it.value.contains("项目") }.key
        val roomCol = headers.entries.first { it.value.contains("实验室") || it.value == "地点" }.key
        val weekdayCol = headers.entries.firstOrNull { it.value == "星期" || it.value == "星期几" }?.key

        val reservations = mutableListOf<LabReservation>()
        var skipped = 0
        for ((offset, row) in rows.drop(headerIndex + 1).withIndex()) {
            val values = row.mapValues { cleanCell(it.value) }
            if (values.values.all(String::isBlank)) {
                skipped++
                continue
            }
            val rowNumber = headerIndex + offset + 2
            val dateText = values[dateCol].orEmpty()
            val eventDate = parseDate(dateText)
                ?: throw IOException("第 $rowNumber 行日期无效：$dateText")
            val daysFromStart = daysBetween(startDate, eventDate)
            if (daysFromStart < 0) throw IOException("第 $rowNumber 行日期早于默认课表开学日")
            val week = daysFromStart / 7 + 1
            if (week !in 1..maxWeek) throw IOException("第 $rowNumber 行日期超出默认课表周次范围")

            val calendarDay = eventDate.get(Calendar.DAY_OF_WEEK).let { if (it == Calendar.SUNDAY) 7 else it - 1 }
            val suppliedDay = weekdayCol?.let { parseWeekday(values[it].orEmpty()) }
            if (weekdayCol != null && suppliedDay == null) {
                throw IOException("第 $rowNumber 行星期格式无效：${values[weekdayCol]}")
            }
            if (suppliedDay != null && suppliedDay != calendarDay) {
                throw IOException("第 $rowNumber 行日期与星期不一致")
            }

            val course = values[courseCol].orEmpty()
            val project = values[projectCol].orEmpty()
            val room = values[roomCol].orEmpty()
            if (course.isBlank() || project.isBlank() || room.isBlank()) {
                throw IOException("第 $rowNumber 行课程、实验项目或实验室为空")
            }
            val range = parseTimeRange(values[timeCol].orEmpty())
                ?: throw IOException("第 $rowNumber 行时间格式无效：${values[timeCol]}")
            val (startNode, endNode) = resolveNodes(range.first, range.second, timeDetails)
                ?: throw IOException("第 $rowNumber 行时间无法对应到课表节次：${values[timeCol]}")
            if (startNode !in 1..maxNodes || endNode !in startNode..maxNodes) {
                throw IOException("第 $rowNumber 行节次超出默认课表范围")
            }

            reservations += LabReservation(
                name = "$course · $project",
                day = calendarDay,
                startNode = startNode,
                step = endNode - startNode + 1,
                week = week,
                room = room
            )
        }

        val unique = reservations.distinctBy {
            listOf(it.week, it.day, it.startNode, it.step, it.name, it.room)
        }
        if (unique.isEmpty()) throw IOException("预约表中没有可导入的数据行")
        if (unique.size > 500) throw IOException("预约条目超过 500 条限制")
        return ImportPreview(unique, skipped, reservations.size - unique.size)
    }

    private fun readRows(document: org.w3c.dom.Document, sharedStrings: List<String>): List<Map<Int, String>> {
        val rowNodes = document.getElementsByTagNameNS(MAIN_NS, "row")
        if (rowNodes.length > MAX_ROWS) throw IOException("预约表行数超过限制")
        return (0 until rowNodes.length).map { rowIndex ->
            val rowElement = rowNodes.item(rowIndex) as Element
            val cellNodes = rowElement.getElementsByTagNameNS(MAIN_NS, "c")
            val row = LinkedHashMap<Int, String>()
            for (cellIndex in 0 until cellNodes.length) {
                val cell = cellNodes.item(cellIndex) as? Element ?: continue
                val column = cellRefPattern.matchEntire(cell.getAttribute("r"))?.groupValues?.get(1)
                    ?.let(::columnIndex) ?: continue
                val value = when (cell.getAttribute("t")) {
                    "inlineStr" -> descendantsText(cell, "t")
                    "s" -> {
                        val index = cell.getElementsByTagNameNS(MAIN_NS, "v").item(0)?.textContent?.toIntOrNull()
                            ?: throw IOException("共享字符串索引无效")
                        sharedStrings.getOrNull(index) ?: throw IOException("共享字符串索引超出范围")
                    }
                    else -> cell.getElementsByTagNameNS(MAIN_NS, "v").item(0)?.textContent.orEmpty()
                }
                if (value.length > MAX_CELL_CHARS) throw IOException("预约表单元格内容过长")
                row[column] = value
            }
            row
        }
    }

    private fun descendantsText(element: Element, tag: String): String {
        val nodes = element.getElementsByTagNameNS(MAIN_NS, tag)
        return (0 until nodes.length).joinToString("") { nodes.item(it).textContent.orEmpty() }
    }

    private fun columnIndex(label: String): Int {
        var value = 0
        for (char in label) value = value * 26 + (char - 'A' + 1)
        return value - 1
    }

    private fun normalizeHeader(value: String): String = cleanCell(value).replace(" ", "")

    private fun cleanCell(value: String): String = value
        .replace('\uFEFF', ' ')
        .replace('\u00A0', ' ')
        .trim()

    private fun parseDate(value: String): Calendar? {
        val match = datePattern.matchEntire(cleanCell(value))
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply {
            clear()
            isLenient = false
        }
        if (match != null) {
            calendar.set(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt() - 1,
                match.groupValues[3].toInt()
            )
            return runCatching { calendar.time; calendar }.getOrNull()
        }

        val serial = value.toDoubleOrNull()?.toLong() ?: return null
        if (serial !in 1..100_000) return null
        calendar.set(1899, Calendar.DECEMBER, 30)
        calendar.add(Calendar.DAY_OF_YEAR, serial.toInt())
        return calendar
    }

    private fun daysBetween(start: Calendar, end: Calendar): Int =
        ((end.timeInMillis - start.timeInMillis) / MILLIS_PER_DAY).toInt()

    private fun parseWeekday(value: String): Int? {
        val text = cleanCell(value).removePrefix("星期").removePrefix("周")
        return when (text) {
            "一", "1", "星期一", "周一" -> 1
            "二", "2", "星期二", "周二" -> 2
            "三", "3", "星期三", "周三" -> 3
            "四", "4", "星期四", "周四" -> 4
            "五", "5", "星期五", "周五" -> 5
            "六", "6", "星期六", "周六" -> 6
            "日", "天", "7", "星期日", "星期天", "周日", "周天" -> 7
            else -> null
        }
    }

    private fun parseTimeRange(value: String): Pair<Int, Int>? {
        val matches = timePattern.findAll(value.replace('：', ':')).toList()
        if (matches.size < 2) return null
        fun minutes(match: MatchResult): Int? {
            val hour = match.groupValues[1].toIntOrNull() ?: return null
            val minute = match.groupValues[2].toIntOrNull() ?: return null
            return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
        }
        val start = minutes(matches[0]) ?: return null
        val end = minutes(matches[1]) ?: return null
        return if (end > start) start to end else null
    }

    private fun resolveNodes(
        start: Int,
        end: Int,
        timeDetails: List<TimeDetailBean>
    ): Pair<Int, Int>? {
        val startNode = timeDetails.firstOrNull { toMinutes(it.startTime) == start }?.node
        val endNode = timeDetails.firstOrNull { toMinutes(it.endTime) == end }?.node
        if (startNode != null && endNode != null && endNode >= startNode) return startNode to endNode

        return LAB_PERIODS.firstOrNull { it.startMinute == start && it.endMinute == end }
            ?.let { it.startNode to it.endNode }
    }

    private fun toMinutes(value: String): Int? {
        val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(value.trim()) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
    }

    private data class LabPeriod(val startMinute: Int, val endMinute: Int, val startNode: Int, val endNode: Int)

    private val LAB_PERIODS = listOf(
        LabPeriod(8 * 60, 9 * 60 + 25, 1, 2),
        LabPeriod(9 * 60 + 45, 11 * 60 + 10, 3, 4),
        LabPeriod(11 * 60 + 15, 11 * 60 + 55, 5, 5),
        LabPeriod(13 * 60, 14 * 60 + 25, 6, 7),
        LabPeriod(14 * 60 + 45, 16 * 60 + 10, 8, 9),
        LabPeriod(16 * 60 + 15, 16 * 60 + 55, 10, 10)
    )

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
}
