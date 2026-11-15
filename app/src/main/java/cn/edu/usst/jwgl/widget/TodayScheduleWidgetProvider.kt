package cn.edu.usst.jwgl.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.data.wakeup.CourseAdjustmentResolver
import cn.edu.usst.jwgl.data.wakeup.CourseUtils
import cn.edu.usst.jwgl.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class TodayScheduleWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH_WIDGET = "cn.edu.usst.jwgl.widget.ACTION_REFRESH_WIDGET"
        const val ACTION_PIN_WIDGET = "cn.edu.usst.jwgl.widget.ACTION_PIN_WIDGET"
        const val ACTION_PREV_DAY = "cn.edu.usst.jwgl.widget.ACTION_PREV_DAY"
        const val ACTION_NEXT_DAY = "cn.edu.usst.jwgl.widget.ACTION_NEXT_DAY"
        const val ACTION_RESET_TODAY = "cn.edu.usst.jwgl.widget.ACTION_RESET_TODAY"

        const val EXTRA_DAY_OFFSET = "extra_day_offset"
        private const val REQUEST_CODE_OPEN_APP = 2001
        private const val REQUEST_CODE_REFRESH = 2002
        private const val REQUEST_CODE_PREV = 2003
        private const val REQUEST_CODE_NEXT = 2004
        private const val REQUEST_CODE_RESET = 2005
        private const val REQUEST_CODE_SIGN = 2006

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val componentName = ComponentName(context, TodayScheduleWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds != null && appWidgetIds.isNotEmpty()) {
                val intent = Intent(context, TodayScheduleWidgetProvider::class.java).apply {
                    action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
                }
                context.sendBroadcast(intent)
            }
        }

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_today_schedule)
            val config = WidgetConfigManager.getConfig(context, appWidgetId)
            val dayOffset = WidgetConfigManager.getDayOffset(context, appWidgetId)

            // 1. Calculate target date, week, and course info
            val targetCal = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            val sdfDate = SimpleDateFormat("MM/dd", Locale.CHINA)
            val dateDisplay = sdfDate.format(targetCal.time)
            val dayOfWeek = targetCal.get(Calendar.DAY_OF_WEEK)
            val dayNumber = if (dayOfWeek == Calendar.SUNDAY) 7 else (dayOfWeek - 1)
            val dayStr = CourseUtils.getDayStr(dayNumber)

            var weekNum = 1
            var badgeText = "无课"
            var isHoliday = false
            var holidayName = ""
            var isSwapped = false
            var swapRemark = ""
            var courseCount = 0

            try {
                val db = AppDatabase.getDatabase(context)
                val activeTable = db.tableDao.getDefaultTable()
                weekNum = CourseUtils.countWeek(activeTable.startDate, targetCal)
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

                if (resolved.isHolidayOff) {
                    isHoliday = true
                    holidayName = resolved.holidayName ?: "放假"
                    badgeText = "放假: $holidayName"
                } else if (resolved.isSwapped) {
                    isSwapped = true
                    swapRemark = resolved.swapRemark ?: "补课"
                    courseCount = resolved.courses.size
                    badgeText = "$swapRemark · ${courseCount}节课"
                } else {
                    val weekType = if (weekNum % 2 != 0) 1 else 2
                    val dayCourses = db.courseBaseDao.getCourseByDayAndWeekOfTable(
                        dayNumber,
                        weekNum,
                        weekType,
                        activeTable.id
                    )
                    courseCount = dayCourses.size
                    badgeText = if (courseCount > 0) "共 $courseCount 门课" else "无课"
                }
            } catch (e: Exception) {
                // DB might be initializing
            }

            // 2. Apply Custom Background and Text Colors
            val isSystemNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            val isDark = when (config.themeMode) {
                WidgetConfig.THEME_DARK -> true
                WidgetConfig.THEME_LIGHT -> false
                WidgetConfig.THEME_TRANSPARENT -> false
                else -> isSystemNight
            }

            val bgResId = config.getBackgroundResId(isDark)
            views.setInt(R.id.llWidgetRoot, "setBackgroundResource", bgResId)

            val primaryTextColor = if (isDark) Color.parseColor("#F3F4F6") else Color.parseColor("#1D2939")
            views.setTextColor(R.id.tvWidgetHeaderDate, primaryTextColor)

            // 3. Set Header Title & Badge
            val headerTitle = when (dayOffset) {
                0 -> "第 $weekNum 周 · $dayStr ($dateDisplay)"
                1 -> "明天 · 第 $weekNum 周 · $dayStr ($dateDisplay)"
                -1 -> "昨天 · 第 $weekNum 周 · $dayStr ($dateDisplay)"
                else -> "${if (dayOffset > 0) "+${dayOffset}天" else "${dayOffset}天"} · 第 $weekNum 周 · $dayStr ($dateDisplay)"
            }
            views.setTextViewText(R.id.tvWidgetHeaderDate, headerTitle)
            views.setTextViewText(R.id.tvWidgetStatusBadge, badgeText)

            // Empty state subtitle customization
            val emptyTitlePrefix = if (dayOffset == 0) "今日" else "该日"
            if (isHoliday) {
                views.setTextViewText(R.id.tvWidgetEmptyTitle, "$emptyTitlePrefix 放假 ($holidayName)")
                views.setTextViewText(R.id.tvWidgetEmptySubtitle, "假期愉快，尽情享受悠闲时光吧~")
            } else {
                views.setTextViewText(R.id.tvWidgetEmptyTitle, "$emptyTitlePrefix 无课")
                views.setTextViewText(R.id.tvWidgetEmptySubtitle, "好好休息，享受大学时光吧~")
            }

            // 4. Date Navigation controls visibility & PendingIntents
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            if (config.allowDateSwitching) {
                views.setViewVisibility(R.id.llWidgetDateNav, View.VISIBLE)
                views.setViewVisibility(R.id.btnWidgetResetToday, if (dayOffset != 0) View.VISIBLE else View.GONE)

                // Prev Day Intent
                val prevIntent = Intent(context, TodayScheduleWidgetProvider::class.java).apply {
                    action = ACTION_PREV_DAY
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                val prevPi = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE_PREV + appWidgetId * 10,
                    prevIntent,
                    pendingFlags
                )
                views.setOnClickPendingIntent(R.id.btnWidgetPrevDay, prevPi)

                // Next Day Intent
                val nextIntent = Intent(context, TodayScheduleWidgetProvider::class.java).apply {
                    action = ACTION_NEXT_DAY
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                val nextPi = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE_NEXT + appWidgetId * 10,
                    nextIntent,
                    pendingFlags
                )
                views.setOnClickPendingIntent(R.id.btnWidgetNextDay, nextPi)

                // Reset Today Intent
                val resetIntent = Intent(context, TodayScheduleWidgetProvider::class.java).apply {
                    action = ACTION_RESET_TODAY
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                val resetPi = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE_RESET + appWidgetId * 10,
                    resetIntent,
                    pendingFlags
                )
                views.setOnClickPendingIntent(R.id.btnWidgetResetToday, resetPi)
            } else {
                views.setViewVisibility(R.id.llWidgetDateNav, View.GONE)
            }

            // 5. Connect RemoteViewsService for the ListView
            val serviceIntent = Intent(context, TodayScheduleWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(EXTRA_DAY_OFFSET, dayOffset)
                data = Uri.parse("usst_widget://${appWidgetId}_offset_$dayOffset")
            }
            views.setRemoteAdapter(R.id.lvWidgetCourses, serviceIntent)
            views.setEmptyView(R.id.lvWidgetCourses, R.id.llWidgetEmpty)

            // 6. Click Intent: Open MainActivity at Timetable Tab
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_TAB, 1)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val openAppPendingIntent = PendingIntent.getActivity(
                context,
                REQUEST_CODE_OPEN_APP,
                openAppIntent,
                pendingFlags
            )
            views.setOnClickPendingIntent(R.id.llWidgetHeader, openAppPendingIntent)
            views.setOnClickPendingIntent(R.id.llWidgetEmpty, openAppPendingIntent)

            // 7. Item Click Template for ListView
            views.setPendingIntentTemplate(R.id.lvWidgetCourses, openAppPendingIntent)

            // 8. Refresh Button Intent
            val refreshIntent = Intent(context, TodayScheduleWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WIDGET
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_REFRESH + appWidgetId * 10,
                refreshIntent,
                pendingFlags
            )
            views.setOnClickPendingIntent(R.id.btnWidgetRefresh, refreshPendingIntent)

            // 9. 课程门户：快速签到入口
            if (config.showQuickSign) {
                views.setViewVisibility(R.id.btnWidgetQuickSign, View.VISIBLE)
                val signIntent = Intent(context, MainActivity::class.java).apply {
                    putExtra("extra_trigger_smart_rollcall", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                val signPendingIntent = PendingIntent.getActivity(
                    context,
                    REQUEST_CODE_SIGN + appWidgetId * 10,
                    signIntent,
                    pendingFlags
                )
                views.setOnClickPendingIntent(R.id.btnWidgetQuickSign, signPendingIntent)
            } else {
                views.setViewVisibility(R.id.btnWidgetQuickSign, View.GONE)
            }

            // Notify data changed and update widget
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.lvWidgetCourses)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            WidgetConfigManager.deleteConfig(context, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val appWidgetManager = AppWidgetManager.getInstance(context) ?: return

        when (intent.action) {
            ACTION_PREV_DAY -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val current = WidgetConfigManager.getDayOffset(context, widgetId)
                    WidgetConfigManager.setDayOffset(context, widgetId, current - 1)
                    updateAppWidget(context, appWidgetManager, widgetId)
                }
            }
            ACTION_NEXT_DAY -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val current = WidgetConfigManager.getDayOffset(context, widgetId)
                    WidgetConfigManager.setDayOffset(context, widgetId, current + 1)
                    updateAppWidget(context, appWidgetManager, widgetId)
                }
            }
            ACTION_RESET_TODAY -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetConfigManager.setDayOffset(context, widgetId, 0)
                    updateAppWidget(context, appWidgetManager, widgetId)
                }
            }
            ACTION_REFRESH_WIDGET -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    appWidgetManager.notifyAppWidgetViewDataChanged(widgetId, R.id.lvWidgetCourses)
                    updateAppWidget(context, appWidgetManager, widgetId)
                } else {
                    val componentName = ComponentName(context, TodayScheduleWidgetProvider::class.java)
                    val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName) ?: return
                    appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.lvWidgetCourses)
                    for (id in appWidgetIds) {
                        updateAppWidget(context, appWidgetManager, id)
                    }
                }
            }
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED -> {
                WidgetConfigManager.resetAllDayOffsets(context)
                val componentName = ComponentName(context, TodayScheduleWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName) ?: return
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.lvWidgetCourses)
                for (id in appWidgetIds) {
                    updateAppWidget(context, appWidgetManager, id)
                }
            }
            ACTION_PIN_WIDGET -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val myProvider = ComponentName(context, TodayScheduleWidgetProvider::class.java)
                    if (appWidgetManager.isRequestPinAppWidgetSupported) {
                        appWidgetManager.requestPinAppWidget(myProvider, null, null)
                    }
                }
            }
        }
    }
}
