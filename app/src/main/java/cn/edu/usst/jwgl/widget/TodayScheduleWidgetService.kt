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
import cn.edu.usst.jwgl.data.model.LearningPlatform
import cn.edu.usst.jwgl.data.model.UnifiedBulletin
import cn.edu.usst.jwgl.data.model.UnifiedTodo
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

    companion object {
        const val EXTRA_ACTIVE_TAB = "extra_active_tab"
        const val EXTRA_WIDGET_CLICK_TYPE = "extra_widget_click_type"
        const val CLICK_TYPE_SCHEDULE = "schedule"
        const val CLICK_TYPE_TODO = "todo"
        const val CLICK_TYPE_NOTICE = "notice"

        const val EXTRA_PLATFORM = "extra_widget_platform"
        const val EXTRA_TODO_ID = "extra_widget_todo_id"
        const val EXTRA_COURSE_ID = "extra_widget_course_id"
        const val EXTRA_CLASS_ID = "extra_widget_class_id"
        const val EXTRA_TITLE = "extra_widget_title"
        const val EXTRA_COURSE_NAME = "extra_widget_course_name"
        const val EXTRA_DETAIL_URL = "extra_widget_detail_url"
        const val EXTRA_BULLETIN_ID = "extra_widget_bulletin_id"
    }

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val dayOffset = intent.getIntExtra(TodayScheduleWidgetProvider.EXTRA_DAY_OFFSET, 0)
        val activeTab = intent.getIntExtra(EXTRA_ACTIVE_TAB, WidgetConfig.TAB_SCHEDULE)
        return TodayScheduleRemoteViewsFactory(applicationContext, appWidgetId, dayOffset, activeTab)
    }
}

private class TodayScheduleRemoteViewsFactory(
    private val context: Context,
    private val appWidgetId: Int,
    private val dayOffset: Int,
    private val activeTab: Int
) : RemoteViewsService.RemoteViewsFactory {

    private sealed class FeedItem {
        data class Course(
            val name: String,
            val timeStr: String,
            val room: String,
            val teacher: String,
            val colorHex: String,
            val isPassed: Boolean = false,
            val hasTodo: Boolean = false
        ) : FeedItem()

        data class Todo(val todo: UnifiedTodo) : FeedItem()

        data class Notice(val bulletin: UnifiedBulletin) : FeedItem()
    }

    private val items = mutableListOf<FeedItem>()
    private var widgetConfig = WidgetConfig()

    override fun onCreate() {
        // Initial setup
    }

    override fun onDataSetChanged() {
        items.clear()
        widgetConfig = WidgetConfigManager.getConfig(context, appWidgetId)

        when (activeTab) {
            WidgetConfig.TAB_TODOS -> {
                val todos = WidgetFeedRepository.loadTodosWithin(context, widgetConfig.feedDaysWindow)
                todos.forEach { items.add(FeedItem.Todo(it)) }
            }
            WidgetConfig.TAB_NOTICES -> {
                val notices = WidgetFeedRepository.loadNoticesWithin(context, widgetConfig.feedDaysWindow)
                notices.forEach { items.add(FeedItem.Notice(it)) }
            }
            else -> loadCourses()
        }
    }

    private fun loadCourses() {
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
            val currentTimeVal = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)

            val activeTodos = if (widgetConfig.showPortalTodos) {
                WidgetFeedRepository.loadActiveTodos(context)
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

                items.add(
                    FeedItem.Course(
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
        items.clear()
    }

    override fun getCount(): Int = items.size

    private fun isDarkTheme(): Boolean {
        val isSystemNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return when (widgetConfig.themeMode) {
            WidgetConfig.THEME_DARK -> true
            WidgetConfig.THEME_LIGHT -> false
            WidgetConfig.THEME_TRANSPARENT -> false
            else -> isSystemNight
        }
    }

    override fun getViewAt(position: Int): RemoteViews {
        if (position !in items.indices) {
            return RemoteViews(context.packageName, R.layout.widget_item_course)
        }
        return when (val item = items[position]) {
            is FeedItem.Course -> bindCourseView(item)
            is FeedItem.Todo -> bindTodoView(item.todo)
            is FeedItem.Notice -> bindNoticeView(item.bulletin)
        }
    }

    private fun bindCourseView(item: FeedItem.Course): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_item_course)
        views.setTextViewText(R.id.tvCourseName, item.name)
        views.setTextViewText(R.id.tvCourseTime, item.timeStr)
        views.setTextViewText(R.id.tvCourseRoom, item.room)
        views.setTextViewText(R.id.tvCourseTeacher, item.teacher)

        views.setViewVisibility(R.id.tvCourseTeacher, if (widgetConfig.showTeacher) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.tvCourseRoom, if (widgetConfig.showRoom) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.tvCourseTodoBadge, if (widgetConfig.showPortalTodos && item.hasTodo) View.VISIBLE else View.GONE)

        val isDark = isDarkTheme()
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

        val fillInIntent = Intent().apply {
            putExtra(TodayScheduleWidgetService.EXTRA_WIDGET_CLICK_TYPE, TodayScheduleWidgetService.CLICK_TYPE_SCHEDULE)
            putExtra(MainActivity.EXTRA_TAB, 1)
        }
        views.setOnClickFillInIntent(R.id.llCourseItemRoot, fillInIntent)
        return views
    }

    private fun bindTodoView(todo: UnifiedTodo): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_item_todo)
        val isDark = isDarkTheme()
        val primaryTextColor = if (isDark) Color.parseColor("#F3F4F6") else Color.parseColor("#1D2939")
        val secondaryTextColor = if (isDark) Color.parseColor("#9CA3AF") else Color.parseColor("#667085")
        val tertiaryTextColor = if (isDark) Color.parseColor("#6B7280") else Color.parseColor("#98A2B3")

        views.setTextViewText(R.id.tvTodoTitle, todo.cleanTitle)
        views.setTextViewText(R.id.tvTodoCourse, todo.cleanCourseName)
        views.setTextViewText(
            R.id.tvTodoPlatform,
            if (todo.platform == LearningPlatform.TRONCLASS) "畅课" else "学习通"
        )
        views.setTextViewText(R.id.tvTodoRemaining, todo.getRemainingTimeText())
        views.setTextViewText(R.id.tvTodoDeadline, todo.getFormattedDeadline())

        views.setTextColor(R.id.tvTodoTitle, primaryTextColor)
        views.setTextColor(R.id.tvTodoCourse, secondaryTextColor)
        views.setTextColor(R.id.tvTodoDeadline, tertiaryTextColor)

        val urgent = todo.isUrgent()
        val stripColor = when {
            urgent -> Color.parseColor("#EF4444")
            todo.platform == LearningPlatform.TRONCLASS -> Color.parseColor("#2563EB")
            else -> Color.parseColor("#EA580C")
        }
        views.setInt(R.id.viewTodoColor, "setBackgroundColor", stripColor)

        if (urgent) {
            views.setInt(R.id.tvTodoRemaining, "setBackgroundResource", R.drawable.bg_widget_urgent_badge)
            views.setTextColor(R.id.tvTodoRemaining, Color.parseColor("#B91C1C"))
        } else {
            views.setInt(R.id.tvTodoRemaining, "setBackgroundResource", R.drawable.bg_widget_pill_accent)
            views.setTextColor(R.id.tvTodoRemaining, Color.parseColor("#8C1D27"))
        }

        val fillInIntent = Intent().apply {
            putExtra(TodayScheduleWidgetService.EXTRA_WIDGET_CLICK_TYPE, TodayScheduleWidgetService.CLICK_TYPE_TODO)
            putExtra(TodayScheduleWidgetService.EXTRA_PLATFORM, todo.platform.name)
            putExtra(TodayScheduleWidgetService.EXTRA_TODO_ID, todo.originalTodoId)
            putExtra(TodayScheduleWidgetService.EXTRA_COURSE_ID, todo.originalCourseId)
            putExtra(TodayScheduleWidgetService.EXTRA_CLASS_ID, todo.originalClassId ?: "")
            putExtra(TodayScheduleWidgetService.EXTRA_TITLE, todo.cleanTitle)
            putExtra(TodayScheduleWidgetService.EXTRA_COURSE_NAME, todo.cleanCourseName)
            putExtra(TodayScheduleWidgetService.EXTRA_DETAIL_URL, todo.detailUrl ?: "")
        }
        views.setOnClickFillInIntent(R.id.llTodoItemRoot, fillInIntent)
        return views
    }

    private fun bindNoticeView(bulletin: UnifiedBulletin): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_item_notice)
        val isDark = isDarkTheme()
        val primaryTextColor = if (isDark) Color.parseColor("#F3F4F6") else Color.parseColor("#1D2939")
        val secondaryTextColor = if (isDark) Color.parseColor("#9CA3AF") else Color.parseColor("#667085")
        val tertiaryTextColor = if (isDark) Color.parseColor("#6B7280") else Color.parseColor("#98A2B3")

        views.setTextViewText(R.id.tvNoticeTitle, bulletin.title)
        views.setTextViewText(R.id.tvNoticeCourse, bulletin.courseName)
        views.setTextViewText(
            R.id.tvNoticePlatform,
            if (bulletin.platform == LearningPlatform.TRONCLASS) "畅课" else "学习通"
        )
        val timeDisplay = bulletin.publishTimeText
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.length >= 16) it.substring(5, 16) else it }
            ?: ""
        views.setTextViewText(R.id.tvNoticeTime, timeDisplay)
        views.setTextViewText(R.id.tvNoticeSummary, bulletin.cleanContent)

        views.setTextColor(R.id.tvNoticeTitle, primaryTextColor)
        views.setTextColor(R.id.tvNoticeCourse, secondaryTextColor)
        views.setTextColor(R.id.tvNoticeTime, tertiaryTextColor)
        views.setTextColor(R.id.tvNoticeSummary, tertiaryTextColor)

        val stripColor = if (bulletin.platform == LearningPlatform.TRONCLASS) {
            Color.parseColor("#2563EB")
        } else {
            Color.parseColor("#EA580C")
        }
        views.setInt(R.id.viewNoticeColor, "setBackgroundColor", stripColor)

        val fillInIntent = Intent().apply {
            putExtra(TodayScheduleWidgetService.EXTRA_WIDGET_CLICK_TYPE, TodayScheduleWidgetService.CLICK_TYPE_NOTICE)
            putExtra(TodayScheduleWidgetService.EXTRA_BULLETIN_ID, bulletin.unifiedId)
            putExtra(TodayScheduleWidgetService.EXTRA_PLATFORM, bulletin.platform.name)
            putExtra(MainActivity.EXTRA_TAB, 8)
        }
        views.setOnClickFillInIntent(R.id.llNoticeItemRoot, fillInIntent)
        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 3

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = false
}
