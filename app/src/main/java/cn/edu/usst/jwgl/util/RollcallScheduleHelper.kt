package cn.edu.usst.jwgl.util

import android.content.Context
import android.util.Log
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.data.wakeup.CourseAdjustmentResolver
import cn.edu.usst.jwgl.data.wakeup.CourseBean
import cn.edu.usst.jwgl.data.wakeup.CourseUtils
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class ScheduledClassSession(
    val courseName: String,
    val room: String?,
    val teacher: String?,
    val startNode: Int,
    val endNode: Int,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val bufferStartTimeMillis: Long,
    val bufferEndTimeMillis: Long
) {
    fun isDuring(timeMillis: Long): Boolean = timeMillis in bufferStartTimeMillis..bufferEndTimeMillis
}

object RollcallScheduleHelper {

    private const val TAG = "RollcallScheduleHelper"

    // 上课前提前 10 分钟进入监听（教师常在课前或准点发起点名）
    const val BUFFER_PRE_CLASS_MINUTES = 10
    // 下课后延迟 5 分钟退出监听（以防教师拖堂点名）
    const val BUFFER_POST_CLASS_MINUTES = 5

    /**
     * 获取指定日期的所有课程时段
     */
    fun getClassSessionsForDay(context: Context, dayCal: Calendar): List<ScheduledClassSession> {
        val list = mutableListOf<ScheduledClassSession>()
        try {
            val db = AppDatabase.getDatabase(context)
            val activeTable = db.tableDao.getDefaultTable()
            val weekNum = CourseUtils.countWeek(activeTable.startDate, dayCal)

            if (weekNum !in 1..activeTable.maxWeek) {
                return emptyList()
            }

            val dayOfWeek = dayCal.get(Calendar.DAY_OF_WEEK)
            val dayNumber = if (dayOfWeek == Calendar.SUNDAY) 7 else (dayOfWeek - 1)
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(dayCal.time)

            val allCourses = db.courseBaseDao.getCourseOfTable(activeTable.id)
            val resolved = CourseAdjustmentResolver.resolve(
                db,
                activeTable.id,
                dateStr,
                weekNum,
                dayNumber,
                allCourses
            )

            val dayCourses: List<CourseBean> = if (resolved.isHolidayOff) {
                emptyList()
            } else if (resolved.isSwapped) {
                resolved.courses.sortedBy { it.startNode }
            } else {
                val weekType = if (weekNum % 2 != 0) 1 else 2
                db.courseBaseDao.getCourseByDayAndWeekOfTable(
                    dayNumber,
                    weekNum,
                    weekType,
                    activeTable.id
                ).sortedBy { it.startNode }
            }

            if (dayCourses.isEmpty()) return emptyList()

            val timeDetails = db.timeDetailDao.getTimeDetails(activeTable.timeTable)

            for (course in dayCourses) {
                val startNode = course.startNode
                val endNode = course.startNode + course.step - 1

                // 确定开始时间 (Hour, Minute)
                val (startHour, startMin) = parseNodeTime(timeDetails.find { it.node == startNode }?.startTime)
                    ?: CourseReminderManager.SECTION_START_TIMES[startNode]
                    ?: Pair(8, 0)

                // 确定结束时间 (Hour, Minute)
                val (endHour, endMin) = parseNodeTime(timeDetails.find { it.node == endNode }?.endTime)
                    ?: computeDefaultEndTime(startNode, endNode)

                val startCal = (dayCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, startHour)
                    set(Calendar.MINUTE, startMin)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                val endCal = (dayCal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, endHour)
                    set(Calendar.MINUTE, endMin)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                val startTimeMillis = startCal.timeInMillis
                val endTimeMillis = endCal.timeInMillis
                val bufferStartTime = startTimeMillis - BUFFER_PRE_CLASS_MINUTES * 60 * 1000L
                val bufferEndTime = endTimeMillis + BUFFER_POST_CLASS_MINUTES * 60 * 1000L

                list.add(
                    ScheduledClassSession(
                        courseName = course.courseName,
                        room = course.room,
                        teacher = course.teacher,
                        startNode = startNode,
                        endNode = endNode,
                        startTimeMillis = startTimeMillis,
                        endTimeMillis = endTimeMillis,
                        bufferStartTimeMillis = bufferStartTime,
                        bufferEndTimeMillis = bufferEndTime
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve day class sessions", e)
        }
        return list.sortedBy { it.startTimeMillis }
    }

    /**
     * 获取今天的所有课程时段
     */
    fun getTodaySessions(context: Context): List<ScheduledClassSession> {
        return getClassSessionsForDay(context, Calendar.getInstance())
    }

    /**
     * 判断当前时间是否正处于上课期间（含课前10分钟至课后5分钟缓冲区）
     */
    fun getCurrentSession(context: Context, nowMillis: Long = System.currentTimeMillis()): ScheduledClassSession? {
        val todaySessions = getTodaySessions(context)
        return todaySessions.find { it.isDuring(nowMillis) }
    }

    /**
     * 获取今天接下来的下一个课程时段
     */
    fun getNextUpcomingSessionToday(context: Context, nowMillis: Long = System.currentTimeMillis()): ScheduledClassSession? {
        val todaySessions = getTodaySessions(context)
        return todaySessions.firstOrNull { it.bufferStartTimeMillis > nowMillis }
    }

    /**
     * 跨天寻找下一个将要开课的课程时段（最多向后查找 7 天）
     */
    fun findNextSessionAcrossDays(context: Context, startCal: Calendar = Calendar.getInstance()): ScheduledClassSession? {
        val nowMillis = System.currentTimeMillis()
        for (dayOffset in 0..7) {
            val checkCal = (startCal.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            val sessions = getClassSessionsForDay(context, checkCal)
            val upcoming = sessions.firstOrNull { it.bufferStartTimeMillis > nowMillis }
            if (upcoming != null) {
                return upcoming
            }
        }
        return null
    }

    /**
     * 根据签到返回的课程名称，模糊匹配当前或今天的课程信息（用于获取所在教室 room）
     */
    fun findMatchingClassSession(context: Context, rollcallCourseName: String?): ScheduledClassSession? {
        val current = getCurrentSession(context)
        if (rollcallCourseName.isNullOrBlank()) return current

        val cleanQuery = rollcallCourseName.trim().lowercase()

        // 1. 如果当前课程正好匹配，优先返回当前课程
        if (current != null) {
            val curClean = current.courseName.trim().lowercase()
            if (curClean.contains(cleanQuery) || cleanQuery.contains(curClean)) {
                return current
            }
        }

        // 2. 在今天的所有课程中匹配
        val todaySessions = getTodaySessions(context)
        for (s in todaySessions) {
            val name = s.courseName.trim().lowercase()
            if (name.contains(cleanQuery) || cleanQuery.contains(name)) {
                return s
            }
        }

        return current
    }

    private fun parseNodeTime(timeStr: String?): Pair<Int, Int>? {
        if (timeStr.isNullOrBlank()) return null
        val parts = timeStr.split(":")
        if (parts.size >= 2) {
            val h = parts[0].trim().toIntOrNull()
            val m = parts[1].trim().toIntOrNull()
            if (h != null && m != null) {
                return Pair(h, m)
            }
        }
        return null
    }

    private fun computeDefaultEndTime(startNode: Int, endNode: Int): Pair<Int, Int> {
        val startPair = CourseReminderManager.SECTION_START_TIMES[endNode]
            ?: CourseReminderManager.SECTION_START_TIMES[startNode]
            ?: Pair(8, 0)
        // 默认节次持续 45 分钟
        var endHour = startPair.first
        var endMin = startPair.second + 45
        if (endMin >= 60) {
            endHour += endMin / 60
            endMin %= 60
        }
        return Pair(endHour, endMin)
    }
}
