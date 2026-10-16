package cn.edu.usst.jwgl.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
        private const val REQUEST_CODE_OPEN_APP = 2001
        private const val REQUEST_CODE_REFRESH = 2002

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

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH_WIDGET,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED -> {
                val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
                val componentName = ComponentName(context, TodayScheduleWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName) ?: return
                appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.lvWidgetCourses)
                for (id in appWidgetIds) {
                    updateAppWidget(context, appWidgetManager, id)
                }
            }
            ACTION_PIN_WIDGET -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
                    val myProvider = ComponentName(context, TodayScheduleWidgetProvider::class.java)
                    if (appWidgetManager.isRequestPinAppWidgetSupported) {
                        appWidgetManager.requestPinAppWidget(myProvider, null, null)
                    }
                }
            }
        }
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_today_schedule)

        // 1. Calculate today's date, week, and course info
        val todayCal = Calendar.getInstance()
        val sdfDate = SimpleDateFormat("MM/dd", Locale.CHINA)
        val dateDisplay = sdfDate.format(todayCal.time)
        val dayOfWeek = todayCal.get(Calendar.DAY_OF_WEEK)
        val dayNumber = if (dayOfWeek == Calendar.SUNDAY) 7 else (dayOfWeek - 1)
        val dayStr = CourseUtils.getDayStr(dayNumber)

        var weekNum = 1
        var badgeText = "今日无课"
        var isHoliday = false
        var holidayName = ""
        var isSwapped = false
        var swapRemark = ""
        var courseCount = 0

        try {
            val db = AppDatabase.getDatabase(context)
            val activeTable = db.tableDao.getDefaultTable()
            weekNum = CourseUtils.countWeek(activeTable.startDate, todayCal)
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
                badgeText = if (courseCount > 0) "共 $courseCount 门课" else "今日无课"
            }
        } catch (e: Exception) {
            // DB might be initializing
        }

        // 2. Set Header Title & Badge
        val headerTitle = "第 $weekNum 周 · $dayStr ($dateDisplay)"
        views.setTextViewText(R.id.tvWidgetHeaderDate, headerTitle)
        views.setTextViewText(R.id.tvWidgetStatusBadge, badgeText)

        // Empty state subtitle customization
        if (isHoliday) {
            views.setTextViewText(R.id.tvWidgetEmptyTitle, "今日放假 ($holidayName)")
            views.setTextViewText(R.id.tvWidgetEmptySubtitle, "假期愉快，尽情享受悠闲时光吧~")
        } else {
            views.setTextViewText(R.id.tvWidgetEmptyTitle, "今日无课")
            views.setTextViewText(R.id.tvWidgetEmptySubtitle, "好好休息，享受大学时光吧~")
        }

        // 3. Connect RemoteViewsService for the ListView
        val serviceIntent = Intent(context, TodayScheduleWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
        }
        views.setRemoteAdapter(R.id.lvWidgetCourses, serviceIntent)
        views.setEmptyView(R.id.lvWidgetCourses, R.id.llWidgetEmpty)

        // 4. Click Intent: Open MainActivity at Timetable Tab
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_TAB, 1)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN_APP,
            openAppIntent,
            flags
        )
        views.setOnClickPendingIntent(R.id.llWidgetHeader, openAppPendingIntent)
        views.setOnClickPendingIntent(R.id.llWidgetEmpty, openAppPendingIntent)

        // 5. Item Click Template for ListView
        views.setPendingIntentTemplate(R.id.lvWidgetCourses, openAppPendingIntent)

        // 6. Refresh Button Intent
        val refreshIntent = Intent(context, TodayScheduleWidgetProvider::class.java).apply {
            action = ACTION_REFRESH_WIDGET
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_REFRESH,
            refreshIntent,
            flags
        )
        views.setOnClickPendingIntent(R.id.btnWidgetRefresh, refreshPendingIntent)

        // Notify data changed and update widget
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.lvWidgetCourses)
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
