package cn.edu.usst.jwgl.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.RemoteViews
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

class NextCourseWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH_NEXT_COURSE = "cn.edu.usst.jwgl.widget.ACTION_REFRESH_NEXT_COURSE"
        private const val REQUEST_CODE_OPEN_APP = 3001
        private const val REQUEST_CODE_REFRESH = 3002

        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val componentName = ComponentName(context, NextCourseWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            if (appWidgetIds != null && appWidgetIds.isNotEmpty()) {
                val intent = Intent(context, NextCourseWidgetProvider::class.java).apply {
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
            val views = RemoteViews(context.packageName, R.layout.widget_next_course)
            val config = WidgetConfigManager.getConfig(context, appWidgetId)

            val nowCal = Calendar.getInstance()
            val dayOfWeek = nowCal.get(Calendar.DAY_OF_WEEK)
            val dayNumber = if (dayOfWeek == Calendar.SUNDAY) 7 else (dayOfWeek - 1)
            val nowMinutes = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)

            var isHoliday = false
            var holidayName = ""
            var targetCourse: CourseBean? = null
            var targetStatusBadge = ""
            var targetTimeRange = ""
            var allCoursesPassed = false
            var hasAnyCoursesToday = false

            try {
                val db = AppDatabase.getDatabase(context)
                val activeTable = db.tableDao.getDefaultTable()
                val weekNum = CourseUtils.countWeek(activeTable.startDate, nowCal)
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(nowCal.time)
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
                } else {
                    val dayCourses: List<CourseBean> = if (resolved.isSwapped) {
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

                    if (dayCourses.isNotEmpty()) {
                        hasAnyCoursesToday = true
                        val timeDetails = db.timeDetailDao.getTimeDetails(activeTable.timeTable)

                        // 查找正在进行中或即将开始的课程
                        for (c in dayCourses) {
                            val startNode = c.startNode
                            val endNode = c.startNode + c.step - 1
                            val startTimeStr = timeDetails.find { it.node == startNode }?.startTime
                                ?: CourseReminderManager.SECTION_START_TIMES[startNode]?.let { "%02d:%02d".format(it.first, it.second) }
                                ?: ""
                            val endTimeStr = timeDetails.find { it.node == endNode }?.endTime ?: ""

                            val startMin = parseTimeToMinutes(startTimeStr) ?: (startNode * 50)
                            val endMin = parseTimeToMinutes(endTimeStr) ?: (endNode * 50 + 45)

                            val sectionStr = if (c.step > 1) "${startNode}-${endNode}节" else "${startNode}节"
                            val timeStr = if (startTimeStr.isNotEmpty() && endTimeStr.isNotEmpty()) {
                                "$sectionStr $startTimeStr-$endTimeStr"
                            } else {
                                sectionStr
                            }

                            // 1. 正在上课中
                            if (nowMinutes in startMin..endMin) {
                                targetCourse = c
                                val remaining = endMin - nowMinutes
                                targetStatusBadge = "正在上课 · 距下课 ${remaining}分钟"
                                targetTimeRange = timeStr
                                break
                            }

                            // 2. 距离开始最近的下一门课
                            if (nowMinutes < startMin) {
                                targetCourse = c
                                val diff = startMin - nowMinutes
                                targetStatusBadge = if (diff <= 60) {
                                    "下一节课 · ${diff}分钟后"
                                } else {
                                    "下一节课 · ${diff / 60}小时${diff % 60}分后"
                                }
                                targetTimeRange = timeStr
                                break
                            }
                        }

                        // 如果今天有课程，但所有课程的结束时间均早于当前时间
                        if (targetCourse == null) {
                            allCoursesPassed = true
                        }
                    }
                }
            } catch (e: Exception) {
                // DB might be initializing
            }

            // 1. 设置外观色彩与透明度
            val isSystemNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            val isDark = when (config.themeMode) {
                WidgetConfig.THEME_DARK -> true
                WidgetConfig.THEME_LIGHT -> false
                WidgetConfig.THEME_TRANSPARENT -> false
                else -> isSystemNight
            }

            val isTransparent = config.themeMode == WidgetConfig.THEME_TRANSPARENT
            val alphaPercent = if (isTransparent) 0 else config.bgAlpha
            val alphaInt = (alphaPercent * 255 / 100).coerceIn(0, 255)

            val finalBgColor = if (isDark) {
                Color.argb(alphaInt, 30, 30, 30)
            } else {
                Color.argb(alphaInt, 255, 255, 255)
            }
            views.setInt(R.id.llNextWidgetRoot, "setBackgroundColor", finalBgColor)

            val primaryTextColor = if (isDark) Color.parseColor("#F3F4F6") else Color.parseColor("#1D2939")
            val secondaryTextColor = if (isDark) Color.parseColor("#9CA3AF") else Color.parseColor("#667085")
            val tertiaryTextColor = if (isDark) Color.parseColor("#6B7280") else Color.parseColor("#98A2B3")

            views.setTextColor(R.id.tvNextCourseName, primaryTextColor)
            views.setTextColor(R.id.tvNextTime, secondaryTextColor)
            views.setTextColor(R.id.tvNextTeacher, tertiaryTextColor)

            // 2. 绑定内容或空状态
            if (targetCourse != null) {
                views.setViewVisibility(R.id.llNextCourseContent, View.VISIBLE)
                views.setViewVisibility(R.id.llNextEmptyState, View.GONE)

                views.setTextViewText(R.id.tvNextBadge, targetStatusBadge)
                views.setTextViewText(R.id.tvNextCourseName, targetCourse.courseName)

                // 教室地点
                val room = targetCourse.room?.ifEmpty { "未指定地点" } ?: "未指定地点"
                views.setTextViewText(R.id.tvNextRoom, room)
                views.setViewVisibility(R.id.llNextRoomRow, if (config.showRoom) View.VISIBLE else View.GONE)

                // 时间
                views.setTextViewText(R.id.tvNextTime, targetTimeRange)
                views.setViewVisibility(R.id.tvNextTime, if (config.showTimeRange) View.VISIBLE else View.GONE)

                // 教师
                val teacher = targetCourse.teacher?.ifEmpty { "--" } ?: "--"
                views.setTextViewText(R.id.tvNextTeacher, "主讲: $teacher")
                views.setViewVisibility(R.id.tvNextTeacher, if (config.showTeacher) View.VISIBLE else View.GONE)

            } else {
                // 空状态
                views.setViewVisibility(R.id.llNextCourseContent, View.GONE)
                views.setViewVisibility(R.id.llNextEmptyState, View.VISIBLE)

                if (isHoliday) {
                    views.setTextViewText(R.id.tvNextBadge, "放假休息")
                    views.setTextViewText(R.id.tvNextEmptyTitle, "今日放假 ($holidayName)")
                    views.setTextViewText(R.id.tvNextEmptySubtitle, "假期愉快，尽情享受悠闲时光吧~")
                } else if (allCoursesPassed) {
                    views.setTextViewText(R.id.tvNextBadge, "今日课程已结")
                    views.setTextViewText(R.id.tvNextEmptyTitle, "🎉 今日课程已全部结束")
                    views.setTextViewText(R.id.tvNextEmptySubtitle, "好好休息，享受美好的大学时光~")
                } else {
                    views.setTextViewText(R.id.tvNextBadge, "今日无课")
                    views.setTextViewText(R.id.tvNextEmptyTitle, "今天没有课程安排")
                    views.setTextViewText(R.id.tvNextEmptySubtitle, "自由学习或休息放松一下吧~")
                }
            }

            // 3. 点击卡片打开课表
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
                REQUEST_CODE_OPEN_APP + appWidgetId * 10,
                openAppIntent,
                flags
            )
            views.setOnClickPendingIntent(R.id.llNextWidgetRoot, openAppPendingIntent)

            // 4. 刷新按钮 Intent
            val refreshIntent = Intent(context, NextCourseWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_NEXT_COURSE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_REFRESH + appWidgetId * 10,
                refreshIntent,
                flags
            )
            views.setOnClickPendingIntent(R.id.btnNextRefresh, refreshPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun parseTimeToMinutes(timeStr: String): Int? {
            if (timeStr.isEmpty()) return null
            return try {
                val parts = timeStr.split(":")
                if (parts.size >= 2) {
                    parts[0].trim().toInt() * 60 + parts[1].trim().toInt()
                } else null
            } catch (_: Exception) {
                null
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
            ACTION_REFRESH_NEXT_COURSE -> {
                val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    updateAppWidget(context, appWidgetManager, widgetId)
                } else {
                    val componentName = ComponentName(context, NextCourseWidgetProvider::class.java)
                    val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName) ?: return
                    for (id in appWidgetIds) {
                        updateAppWidget(context, appWidgetManager, id)
                    }
                }
            }
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_TICK -> {
                val componentName = ComponentName(context, NextCourseWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName) ?: return
                for (id in appWidgetIds) {
                    updateAppWidget(context, appWidgetManager, id)
                }
            }
        }
    }
}
