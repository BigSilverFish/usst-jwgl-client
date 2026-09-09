package cn.edu.usst.jwgl.data.wakeup

import cn.edu.usst.jwgl.data.model.ScheduleAdjustment
import cn.edu.usst.jwgl.data.remote.RemoteConfigManager

data class ResolvedDaySchedule(
    val courses: List<CourseBean>,
    val isHolidayOff: Boolean = false,
    val holidayName: String? = null,
    val isSwapped: Boolean = false,
    val swapRemark: String? = null
)

object CourseAdjustmentResolver {

    /**
     * Resolves schedule for a specific date considering holiday off and weekday swaps
     */
    fun resolve(
        db: AppDatabase,
        tableId: Int,
        dateStr: String, // "YYYY-MM-DD"
        week: Int,
        day: Int, // 1..7 (1=Mon, 7=Sun)
        allCourses: List<CourseBean>
    ): ResolvedDaySchedule {
        val adjustments = RemoteConfigManager.getAdjustments()

        // 1. Check Holiday Off
        if (dateStr.isNotBlank()) {
            val holiday = adjustments.find { adj ->
                adj.type == "HOLIDAY_OFF" && (
                    adj.dates.contains(dateStr) ||
                    (adj.dateRange != null && dateStr >= adj.dateRange.start && dateStr <= adj.dateRange.end)
                )
            }
            if (holiday != null) {
                return ResolvedDaySchedule(
                    courses = emptyList(),
                    isHolidayOff = true,
                    holidayName = holiday.name
                )
            }

            // 2. Check Swapped Weekday
            val swap = adjustments.find { adj ->
                adj.type == "SWAP_WEEKDAY" && adj.date == dateStr
            }
            if (swap != null && swap.targetWeekday != null) {
                val targetWeek = swap.targetWeek ?: week
                val targetDay = swap.targetWeekday
                val weekType = if (targetWeek % 2 != 0) 1 else 2
                val swappedCourses = db.courseBaseDao.getCourseByDayAndWeekOfTable(
                    targetDay,
                    targetWeek,
                    weekType,
                    tableId
                )
                val remark = if (swap.remark.isNotBlank()) swap.remark else "补周${CourseUtils.getDayStr(targetDay)}课"
                return ResolvedDaySchedule(
                    courses = swappedCourses,
                    isSwapped = true,
                    swapRemark = remark
                )
            }
        }

        // 3. Normal Day Courses
        val dayCourses = allCourses.filter { it.day == day }
        return ResolvedDaySchedule(
            courses = dayCourses,
            isHolidayOff = false,
            isSwapped = false
        )
    }

    /**
     * Helper to check if a specific day in a week has any active courses or swap courses
     */
    fun isDayActiveWithAdjustments(
        db: AppDatabase,
        tableId: Int,
        dateStr: String,
        week: Int,
        day: Int,
        allCourses: List<CourseBean>
    ): Boolean {
        val resolved = resolve(db, tableId, dateStr, week, day, allCourses)
        if (resolved.isHolidayOff) return false
        if (resolved.isSwapped) return resolved.courses.isNotEmpty()

        return resolved.courses.any { course ->
            (week >= course.startWeek && week <= course.endWeek) &&
            (course.type == 0 || (course.type == 1 && week % 2 != 0) || (course.type == 2 && week % 2 == 0))
        }
    }
}
