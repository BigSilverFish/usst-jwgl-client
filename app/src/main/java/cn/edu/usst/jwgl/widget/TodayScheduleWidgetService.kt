package cn.edu.usst.jwgl.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
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
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val dayOffset = intent.getIntExtra(TodayScheduleWidgetProvider.EXTRA_DAY_OFFSET, 0)
        return TodayScheduleRemoteViewsFactory(applicationContext, appWidgetId, dayOffset)
    }
}

private class TodayScheduleRemoteViewsFactory(
    private val context: Context,
    private val appWidgetId: Int,
    private val dayOffset: Int
) : RemoteViewsService.RemoteViewsFactory {

    private data class DisplayCourse(
        val name: String,
        val timeStr: String,
        val room: String,
        val teacher: String,
        val colorHex: String,
        val isPassed: Boolean = false,
        val hasTodo: Boolean = false
    )

    private val courseList = mutableListOf<DisplayCourse>()
    private var widgetConfig = WidgetConfig()

    override fun onCreate() {
        // Initial setup
    }

    override fun onDataSetChanged() {
        courseList.clear()
        widgetConfig = WidgetConfigManager.getConfig(context, appWidgetId)

        try {
            val db = AppDatabase.getDatabase(context)
            val activeTable = db.tableDao.getDefaultTable()
            val targetCal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            val weekNum = CourseUtils.countWeek(activeTable.startDate, targetCal)
            val dayOfWeek = targetCal.get(Calendar.DAY_OF_WEEK)
            val dayNumber = if (dayOfWeek == Calendar.SUNDAY) 7 else (dayOfWeek - 1)
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(targetCal.time)

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
            val nowCal = Calendar.getInstance()
            val currentHour = nowCal.get(Calendar.HOUR_OF_DAY)
            val currentMinute = nowCal.get(Calendar.MINUTE)
            val currentTimeVal = currentHour * 60 + currentMinute
            val nowMs = System.currentTimeMillis()

            // 获取课程门户缓存待办
            val activeTodos = if (widgetConfig.showPortalTodos) {
                WidgetConfig.loadActivePortalTodos(context)
            } else {
                emptyList()
            }

            for (c in dayCourses) {
                val startNode = c.startNode
                val endNode = c.startNode + c.step - 1
                val startTime = timeDetails.find { it.node == startNode }?.startTime
                    ?: CourseReminderManager.SECTION_START_TIMES[startNode]?.let { "%02d:%02d".format(it.first, it.second) }
                    ?: ""
                val endTime = timeDetails.find { it.node == endNode }?.endTime ?: ""

                val sectionStr = if (c.step > 1) "${startNode}-${endNode}节" else "${startNode}节"
                val timeStr = if (widgetConfig.showTimeRange && startTime.isNotEmpty() && endTime.isNotEmpty()) {
                    "$sectionStr $startTime-$endTime"
                } else if (widgetConfig.showTimeRange && startTime.isNotEmpty()) {
                    "$sectionStr $startTime"
                } else {
                    sectionStr
                }

                // 判断是否已结束 (仅对当天 dayOffset == 0 生效)
                var isPassed = false
                if (dayOffset == 0 && endTime.isNotEmpty()) {
                    try {
                        val parts = endTime.split(":")
                        if (parts.size == 2) {
                            val endVal = parts[0].trim().toInt() * 60 + parts[1].trim().toInt()
                            if (currentTimeVal > endVal) {
                                isPassed = true
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (dayOffset == 0 && widgetConfig.hidePastCourses && isPassed) {
                    continue
                }

                val room = c.room?.ifEmpty { "未指定地点" } ?: "未指定地点"
                val teacher = c.teacher?.ifEmpty { "--" } ?: "--"
                val colorHex = if (!c.color.isNullOrEmpty() && c.color.startsWith("#")) c.color else "#4A90E2"

                val hasTodo = if (widgetConfig.showPortalTodos) {
                    activeTodos.any { 
                        WidgetConfig.matchesCourseName(it.cleanCourseName, c.courseName) ||
                        WidgetConfig.matchesCourseName(it.courseName, c.courseName)
                    }
                } else false

                courseList.add(
                    DisplayCourse(
                        name = c.courseName,
                        timeStr = timeStr,
                        room = room,
                        teacher = teacher,
                        colorHex = colorHex,
                        isPassed = isPassed,
                        hasTodo = hasTodo
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

        // 显隐控制
        views.setViewVisibility(R.id.tvCourseTeacher, if (widgetConfig.showTeacher) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.tvCourseRoom, if (widgetConfig.showRoom) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.tvCourseTodoBadge, if (widgetConfig.showPortalTodos && item.hasTodo) View.VISIBLE else View.GONE)

        // 色彩模式计算
        val isSystemNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        val isDark = when (widgetConfig.themeMode) {
            WidgetConfig.THEME_DARK -> true
            WidgetConfig.THEME_LIGHT -> false
            WidgetConfig.THEME_TRANSPARENT -> false
            else -> isSystemNight
        }

        val primaryTextColor = if (isDark) Color.parseColor("#F3F4F6") else Color.parseColor("#1D2939")
        val secondaryTextColor = if (isDark) Color.parseColor("#9CA3AF") else Color.parseColor("#667085")
        val tertiaryTextColor = if (isDark) Color.parseColor("#6B7280") else Color.parseColor("#98A2B3")

        views.setTextColor(R.id.tvCourseName, primaryTextColor)
        views.setTextColor(R.id.tvCourseTime, secondaryTextColor)
        views.setTextColor(R.id.tvCourseRoom, secondaryTextColor)
        views.setTextColor(R.id.tvCourseTeacher, tertiaryTextColor)

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
