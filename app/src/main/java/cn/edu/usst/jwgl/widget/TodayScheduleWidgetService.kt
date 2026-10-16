package cn.edu.usst.jwgl.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.data.wakeup.CourseAdjustmentResolver
import cn.edu.usst.jwgl.data.wakeup.CourseBean
import cn.edu.usst.jwgl.data.wakeup.CourseUtils
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.util.CourseReminderManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class TodayScheduleWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return TodayScheduleRemoteViewsFactory(applicationContext)
    }
}

private class TodayScheduleRemoteViewsFactory(private val context: Context) : RemoteViewsService.RemoteViewsFactory {

    private data class DisplayCourse(
        val name: String,
        val timeStr: String,
        val room: String,
        val teacher: String,
        val colorHex: String
    )

    private val courseList = mutableListOf<DisplayCourse>()

    override fun onCreate() {
        // Initial setup
    }

    override fun onDataSetChanged() {
        courseList.clear()
        try {
            val db = AppDatabase.getDatabase(context)
            val activeTable = db.tableDao.getDefaultTable()
            val todayCal = Calendar.getInstance()
            val weekNum = CourseUtils.countWeek(activeTable.startDate, todayCal)
            val dayOfWeek = todayCal.get(Calendar.DAY_OF_WEEK)
            val dayNumber = if (dayOfWeek == Calendar.SUNDAY) 7 else (dayOfWeek - 1)
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(todayCal.time)

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

            val timeDetails = db.timeDetailDao.getTimeDetails(activeTable.timeTable)

            for (c in dayCourses) {
                val startNode = c.startNode
                val endNode = c.startNode + c.step - 1
                val startTime = timeDetails.find { it.node == startNode }?.startTime
                    ?: CourseReminderManager.SECTION_START_TIMES[startNode]?.let { "%02d:%02d".format(it.first, it.second) }
                    ?: ""
                val endTime = timeDetails.find { it.node == endNode }?.endTime ?: ""

                val sectionStr = if (c.step > 1) "${startNode}-${endNode}节" else "${startNode}节"
                val timeStr = if (startTime.isNotEmpty() && endTime.isNotEmpty()) {
                    "$sectionStr $startTime-$endTime"
                } else if (startTime.isNotEmpty()) {
                    "$sectionStr $startTime"
                } else {
                    sectionStr
                }

                val room = c.room?.ifEmpty { "未指定地点" } ?: "未指定地点"
                val teacher = c.teacher?.ifEmpty { "--" } ?: "--"
                val colorHex = if (!c.color.isNullOrEmpty() && c.color.startsWith("#")) c.color else "#4A90E2"

                courseList.add(
                    DisplayCourse(
                        name = c.courseName,
                        timeStr = timeStr,
                        room = room,
                        teacher = teacher,
                        colorHex = colorHex
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        courseList.clear()
    }

    override fun getCount(): Int = courseList.size

    override fun getViewAt(position: Int): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_item_course)
        if (position !in courseList.indices) return views

        val item = courseList[position]
        views.setTextViewText(R.id.tvCourseName, item.name)
        views.setTextViewText(R.id.tvCourseTime, item.timeStr)
        views.setTextViewText(R.id.tvCourseRoom, item.room)
        views.setTextViewText(R.id.tvCourseTeacher, item.teacher)

        val colorInt = try {
            Color.parseColor(item.colorHex)
        } catch (_: Exception) {
            0xFF8C1D27.toInt()
        }
        views.setInt(R.id.viewCourseColor, "setBackgroundColor", colorInt)

        // Fill-in Intent for list item click: opens timetable in MainActivity
        val fillInIntent = Intent().apply {
            putExtra(MainActivity.EXTRA_TAB, 1)
        }
        views.setOnClickFillInIntent(R.id.llCourseItemRoot, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}
