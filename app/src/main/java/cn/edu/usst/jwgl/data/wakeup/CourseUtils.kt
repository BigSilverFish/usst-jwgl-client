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

    fun countWeek(startDateStr: String): Int {
        val during = daysBetween(startDateStr)
        return if (during < 0) 1 else during / 7 + 1
    }

    fun getDateStringFromWeek(curWeek: Int, targetWeek: Int, sundayFirst: Boolean): List<String> {
        val calendar = Calendar.getInstance()
        if (targetWeek != curWeek) {
            val amount = targetWeek - curWeek
            calendar.add(Calendar.WEEK_OF_YEAR, amount)
        }
        return getDateStringFromCalendar(calendar, sundayFirst)
    }

    private fun getDateStringFromCalendar(calendar: Calendar, sundayFirst: Boolean): List<String> {
        val dateList = ArrayList<String>()
        val cal = calendar.clone() as Calendar
        cal.firstDayOfWeek = if (sundayFirst) Calendar.SUNDAY else Calendar.MONDAY
        while (cal.get(Calendar.DAY_OF_WEEK) != cal.firstDayOfWeek) {
            cal.add(Calendar.DAY_OF_MONTH, -1)
        }
        // Month (1-based)
        dateList.add((cal.get(Calendar.MONTH) + 1).toString())
        for (i in 0..6) {
            dateList.add(cal.get(Calendar.DAY_OF_MONTH).toString())
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return dateList
    }

    fun getTodayWeekdayInt(): Int {
        val cal = Calendar.getInstance()
        var weekDay = cal.get(Calendar.DAY_OF_WEEK)
        return if (weekDay == Calendar.SUNDAY) 7 else weekDay - 1
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

