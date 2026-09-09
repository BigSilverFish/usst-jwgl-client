package cn.edu.usst.jwgl.data.wakeup

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object CourseUtils {

    val CUSTOMIZED_COLORS = intArrayOf(
        0xFF4A90E2.toInt(), 0xFF50E3C2.toInt(), 0xFFB8E986.toInt(), 0xFFF5A623.toInt(),
        0xFFBD10E0.toInt(), 0xFF9013FE.toInt(), 0xFFFF6F61.toInt(), 0xFF4ECDC4.toInt(),
        0xFFFF8B94.toInt(), 0xFF45B649.toInt(), 0xFF3498DB.toInt(), 0xFF9B59B6.toInt(),
        0xFFE67E22.toInt(), 0xFF1ABC9C.toInt(), 0xFFE91E63.toInt(), 0xFF3F51B5.toInt()
    )

    fun getColorHex(index: Int): String {
        val color = CUSTOMIZED_COLORS[Math.abs(index) % CUSTOMIZED_COLORS.size]
        return String.format("#%06X", 0xFFFFFF and color)
    }

    fun getDayStr(weekDay: Int): String {
        return when (weekDay) {
            1 -> "周一"
            2 -> "周二"
            3 -> "周三"
            4 -> "周四"
            5 -> "周五"
            6 -> "周六"
            7 -> "周日"
            else -> ""
        }
    }

    fun daysBetween(dateStr: String): Int {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val cal = Calendar.getInstance()
            cal.time = sdf.parse(dateStr) ?: return 0
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val time1 = cal.timeInMillis

            val today = Calendar.getInstance()
            today.set(Calendar.HOUR_OF_DAY, 0)
            today.set(Calendar.MINUTE, 0)
            today.set(Calendar.SECOND, 0)
            today.set(Calendar.MILLISECOND, 0)
            val time2 = today.timeInMillis

            val betweenDays = (time2 - time1) / (1000 * 3600 * 24)
            betweenDays.toInt()
        } catch (e: Exception) {
            0
        }
    }

    data class AutoScheduleTarget(
        val table: TableBean,
        val week: Int
    )

    fun compareSemestersDescending(t1: TableBean, t2: TableBean): Int {
        if (t1.startDate.isNotBlank() && t2.startDate.isNotBlank() && t1.startDate != t2.startDate) {
            return t2.startDate.compareTo(t1.startDate)
        }
        val regex = Regex("(\\d{4})[-~](\\d{4})[^0-9]*第?([123])")
        val m1 = regex.find(t1.tableName)
        val m2 = regex.find(t2.tableName)
        if (m1 != null && m2 != null) {
            val y1 = m1.groupValues[1].toIntOrNull() ?: 0
            val s1 = m1.groupValues[3].toIntOrNull() ?: 0
            val y2 = m2.groupValues[1].toIntOrNull() ?: 0
            val s2 = m2.groupValues[3].toIntOrNull() ?: 0
            if (y1 != y2) return y2.compareTo(y1)
            if (s1 != s2) return s2.compareTo(s1)
        }
        val nameCmp = t2.tableName.compareTo(t1.tableName)
        if (nameCmp != 0) return nameCmp
        return t2.id.compareTo(t1.id)
    }

    /**
     * Determines which semester and week to automatically locate on app startup:
     * 1. Ongoing semester: today falls within startDate .. (startDate + maxWeek * 7 days). Week = current week.
     * 2. Not in any semester (vacation): locate to the NEXT semester's Week 1.
     * 3. Fallback: latest semester in DB, Week 1.
     */
    fun findAutoScheduleTarget(tables: List<TableBean>, now: Date = Date()): AutoScheduleTarget? {
        if (tables.isEmpty()) return null

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val todayCal = Calendar.getInstance().apply {
            time = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayMs = todayCal.timeInMillis

        data class TableInfo(
            val table: TableBean,
            val startMs: Long,
            val daysPassed: Int,
            val week: Int,
            val isOngoing: Boolean,
            val isUpcoming: Boolean
        )

        val infos = tables.mapNotNull { t ->
            try {
                val startCal = Calendar.getInstance().apply {
                    time = sdf.parse(t.startDate) ?: return@mapNotNull null
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startMs = startCal.timeInMillis
                val totalDays = t.maxWeek * 7
                val daysPassed = ((todayMs - startMs) / (24L * 3600L * 1000L)).toInt()
                val week = (daysPassed / 7) + 1

                val isOngoing = daysPassed in 0 until totalDays
                val isUpcoming = daysPassed < 0

                TableInfo(t, startMs, daysPassed, week, isOngoing, isUpcoming)
            } catch (e: Exception) {
                null
            }
        }

        // 1. Check for ongoing semester (current week)
        val ongoing = infos.filter { it.isOngoing }
            .maxByOrNull { it.startMs }
        if (ongoing != null) {
            val safeWeek = ongoing.week.coerceIn(1, ongoing.table.maxWeek)
            return AutoScheduleTarget(ongoing.table, safeWeek)
        }

        // 2. Vacation / Not in any semester: next upcoming semester (Week 1)
        val upcoming = infos.filter { it.isUpcoming }
            .minByOrNull { it.startMs }
        if (upcoming != null) {
            return AutoScheduleTarget(upcoming.table, 1)
        }

        // 3. Fallback: latest past semester (Week 1)
        val latest = infos.maxByOrNull { it.startMs }
        if (latest != null) {
            return AutoScheduleTarget(latest.table, 1)
        }

        return AutoScheduleTarget(tables.first(), 1)
    }

    fun countWeek(startDateStr: String): Int {
        val during = daysBetween(startDateStr)
        return if (during < 0) 1 else during / 7 + 1
    }

    fun getDateStringFromWeek(startDateStr: String, targetWeek: Int, sundayFirst: Boolean): List<String> {
        val dateList = ArrayList<String>()
        try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val cal = Calendar.getInstance()
            cal.time = sdf.parse(startDateStr) ?: Date()
            cal.firstDayOfWeek = if (sundayFirst) Calendar.SUNDAY else Calendar.MONDAY
            while (cal.get(Calendar.DAY_OF_WEEK) != cal.firstDayOfWeek) {
                cal.add(Calendar.DAY_OF_MONTH, -1)
            }
            cal.add(Calendar.WEEK_OF_YEAR, targetWeek - 1)
            // Month of the week (using first day)
            dateList.add((cal.get(Calendar.MONTH) + 1).toString())
            for (i in 0..6) {
                dateList.add(cal.get(Calendar.DAY_OF_MONTH).toString())
                cal.add(Calendar.DAY_OF_MONTH, 1)
            }
        } catch (e: Exception) {
            dateList.clear()
            dateList.add("1")
            for (i in 1..7) dateList.add(i.toString())
        }
        return dateList
    }

    fun getTodayWeekdayInt(): Int {
        val cal = Calendar.getInstance()
        var weekDay = cal.get(Calendar.DAY_OF_WEEK)
        return if (weekDay == Calendar.SUNDAY) 7 else weekDay - 1
    }

    fun getFullDateForWeekDay(startDateStr: String, targetWeek: Int, dayIndex: Int, sundayFirst: Boolean): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val cal = Calendar.getInstance()
            cal.time = sdf.parse(startDateStr) ?: Date()
            cal.firstDayOfWeek = if (sundayFirst) Calendar.SUNDAY else Calendar.MONDAY
            while (cal.get(Calendar.DAY_OF_WEEK) != cal.firstDayOfWeek) {
                cal.add(Calendar.DAY_OF_MONTH, -1)
            }
            cal.add(Calendar.WEEK_OF_YEAR, targetWeek - 1)
            cal.add(Calendar.DAY_OF_MONTH, dayIndex)
            sdf.format(cal.time)
        } catch (e: Exception) {
            ""
        }
    }

    fun courseBean2DetailBean(c: CourseBean): CourseDetailBean {
        return CourseDetailBean(
            id = c.id,
            room = c.room,
            day = c.day,
            teacher = c.teacher,
            startNode = c.startNode,
            step = c.step,
            startWeek = c.startWeek,
            endWeek = c.endWeek,
            tableId = c.tableId,
            type = c.type
        )
    }

    fun courseBean2BaseBean(c: CourseBean): CourseBaseBean {
        return CourseBaseBean(
            id = c.id,
            courseName = c.courseName,
            color = c.color,
            tableId = c.tableId
        )
    }

    fun intList2WeekBeanList(inputList: List<Int>): List<WeekBean> {
        if (inputList.isEmpty()) return emptyList()
        val sorted = inputList.distinct().sorted()
        val result = mutableListOf<WeekBean>()

        var i = 0
        while (i < sorted.size) {
            val start = sorted[i]
            // Try all-weeks pattern (step=1)
            var endStep1 = start
            var idxStep1 = i + 1
            while (idxStep1 < sorted.size && sorted[idxStep1] == endStep1 + 1) {
                endStep1 = sorted[idxStep1]
                idxStep1++
            }

            // Try alternate-weeks pattern (step=2, odd/even)
            var endStep2 = start
            var idxStep2 = i + 1
            while (idxStep2 < sorted.size && sorted[idxStep2] == endStep2 + 2) {
                endStep2 = sorted[idxStep2]
                idxStep2++
            }

            val countStep1 = idxStep1 - i
            val countStep2 = idxStep2 - i

            if (countStep1 >= 2 && countStep1 >= countStep2) {
                result.add(WeekBean(start = start, end = endStep1, type = 0))
                i = idxStep1
            } else if (countStep2 >= 2) {
                val type = if (start % 2 != 0) 1 else 2
                result.add(WeekBean(start = start, end = endStep2, type = type))
                i = idxStep2
            } else {
                result.add(WeekBean(start = start, end = start, type = 0))
                i++
            }
        }
        return result
    }
}

data class WeekBean(
    var start: Int,
    var end: Int,
    var type: Int // 0=all, 1=odd, 2=even
)

