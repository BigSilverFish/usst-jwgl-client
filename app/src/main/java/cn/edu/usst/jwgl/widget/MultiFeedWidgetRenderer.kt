package cn.edu.usst.jwgl.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
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
import cn.edu.usst.jwgl.worker.WidgetFeedSyncScheduler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 多标签桌面微件共享渲染与交互处理引擎：
 * 同时支撑「学习动态 (待办|通知)」与「全能聚合 (课表|待办|通知)」两种形态。
 */
internal object MultiFeedWidgetRenderer {

    const val ACTION_SWITCH_TAB = "cn.edu.usst.jwgl.widget.ACTION_SWITCH_TAB"
    const val ACTION_REFRESH_FEED = "cn.edu.usst.jwgl.widget.ACTION_REFRESH_FEED"
    const val ACTION_PREV_DAY = "cn.edu.usst.jwgl.widget.ACTION_MULTI_PREV_DAY"
    const val ACTION_NEXT_DAY = "cn.edu.usst.jwgl.widget.ACTION_MULTI_NEXT_DAY"
    const val ACTION_RESET_TODAY = "cn.edu.usst.jwgl.widget.ACTION_MULTI_RESET_TODAY"
    const val EXTRA_TARGET_TAB = "extra_target_tab"

    private const val RC_BASE_TAB = 4100
    private const val RC_BASE_NAV = 4400
    private const val RC_BASE_ACTION = 4700

    fun updateAllOf(context: Context, providerClass: Class<*>) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, providerClass))
        if (ids != null && ids.isNotEmpty()) {
            val intent = Intent(context, providerClass).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            context.sendBroadcast(intent)
        }
    }

    fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        providerClass: Class<*>,
        showScheduleTab: Boolean
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_multi_feed)
        val config = WidgetConfigManager.getConfig(context, appWidgetId)
        val defaultTab = if (showScheduleTab) WidgetConfig.TAB_SCHEDULE else WidgetConfig.TAB_TODOS
        var activeTab = WidgetConfigManager.getActiveTab(context, appWidgetId, defaultTab)
        if (!showScheduleTab && activeTab == WidgetConfig.TAB_SCHEDULE) {
            activeTab = WidgetConfig.TAB_TODOS
        }

        val todosCount = WidgetFeedRepository.loadTodosWithin(context, config.feedDaysWindow).size
        val noticesCount = WidgetFeedRepository.loadNoticesWithin(context, config.feedDaysWindow).size

        // 1. 主题背景与文字颜色
        val isSystemNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val isDark = when (config.themeMode) {
            WidgetConfig.THEME_DARK -> true
            WidgetConfig.THEME_LIGHT -> false
            WidgetConfig.THEME_TRANSPARENT -> false
            else -> isSystemNight
        }
        views.setInt(R.id.llWidgetRoot, "setBackgroundResource", config.getBackgroundResId(isDark))
        val primaryTextColor = if (isDark) Color.parseColor("#F3F4F6") else Color.parseColor("#1D2939")
        views.setTextColor(R.id.tvWidgetHeaderDate, primaryTextColor)

        // 2. 顶部标签可见性、计数与选中态样式
        views.setViewVisibility(R.id.tabWidgetSchedule, if (showScheduleTab) View.VISIBLE else View.GONE)
        views.setTextViewText(
            R.id.tabWidgetTodos,
            if (todosCount > 0) "待办($todosCount)" else "待办"
        )
        views.setTextViewText(
            R.id.tabWidgetNotices,
            if (noticesCount > 0) "通知($noticesCount)" else "通知"
        )

        styleTabPill(views, R.id.tabWidgetSchedule, activeTab == WidgetConfig.TAB_SCHEDULE)
        styleTabPill(views, R.id.tabWidgetTodos, activeTab == WidgetConfig.TAB_TODOS)
        styleTabPill(views, R.id.tabWidgetNotices, activeTab == WidgetConfig.TAB_NOTICES)

        val immutableFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val templateFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        // 绑定顶部标签点击广播
        bindTabClick(context, views, R.id.tabWidgetSchedule, providerClass, appWidgetId, WidgetConfig.TAB_SCHEDULE, immutableFlags)
        bindTabClick(context, views, R.id.tabWidgetTodos, providerClass, appWidgetId, WidgetConfig.TAB_TODOS, immutableFlags)
        bindTabClick(context, views, R.id.tabWidgetNotices, providerClass, appWidgetId, WidgetConfig.TAB_NOTICES, immutableFlags)

        // 3. 副标题栏与空状态文案
        val dayOffset = WidgetConfigManager.getDayOffset(context, appWidgetId)
        when (activeTab) {
            WidgetConfig.TAB_TODOS -> {
                views.setViewVisibility(R.id.llWidgetDateNav, View.GONE)
                views.setViewVisibility(R.id.btnWidgetQuickSign, View.GONE)
                views.setTextViewText(R.id.tvWidgetHeaderDate, "未来 ${config.feedDaysWindow} 天待办")
                views.setTextViewText(
                    R.id.tvWidgetStatusBadge,
                    if (todosCount > 0) "$todosCount 项待交" else "已清空"
                )
                views.setTextViewText(R.id.tvWidgetEmptyTitle, "未来 ${config.feedDaysWindow} 天暂无待办")
                views.setTextViewText(R.id.tvWidgetEmptySubtitle, "畅课与超星作业均已完成或暂无截止项")
            }
            WidgetConfig.TAB_NOTICES -> {
                views.setViewVisibility(R.id.llWidgetDateNav, View.GONE)
                views.setViewVisibility(R.id.btnWidgetQuickSign, View.GONE)
                views.setTextViewText(R.id.tvWidgetHeaderDate, "近 ${config.feedDaysWindow} 天课程通知")
                views.setTextViewText(
                    R.id.tvWidgetStatusBadge,
                    if (noticesCount > 0) "$noticesCount 条公告" else "暂无新通知"
                )
                views.setTextViewText(R.id.tvWidgetEmptyTitle, "近 ${config.feedDaysWindow} 天暂无课程通知")
                views.setTextViewText(R.id.tvWidgetEmptySubtitle, "点击右上角刷新或打开课程门户同步最新公告")
            }
            else -> {
                bindScheduleSubHeader(context, views, config, providerClass, appWidgetId, dayOffset, immutableFlags)
            }
        }

        // 4. 绑定 ListView 数据源
        val serviceIntent = Intent(context, TodayScheduleWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(TodayScheduleWidgetProvider.EXTRA_DAY_OFFSET, dayOffset)
            putExtra(TodayScheduleWidgetService.EXTRA_ACTIVE_TAB, activeTab)
            data = Uri.parse("usst_widget://multi_${appWidgetId}_tab_${activeTab}_offset_$dayOffset")
        }
        views.setRemoteAdapter(R.id.lvWidgetCourses, serviceIntent)
        views.setEmptyView(R.id.lvWidgetCourses, R.id.llWidgetEmpty)

        // 5. 列表项点击模板 (必须 FLAG_MUTABLE 以保留子项 fillInIntent 参数)
        val templateIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val templatePi = PendingIntent.getActivity(
            context,
            RC_BASE_ACTION + appWidgetId * 10,
            templateIntent,
            templateFlags
        )
        views.setPendingIntentTemplate(R.id.lvWidgetCourses, templatePi)

        // 空状态点击打开对应页
        val emptyTargetTab = if (activeTab == WidgetConfig.TAB_SCHEDULE) 1 else 8
        val emptyIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_TAB, emptyTargetTab)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val emptyPi = PendingIntent.getActivity(
            context,
            RC_BASE_ACTION + appWidgetId * 10 + 1,
            emptyIntent,
            immutableFlags
        )
        views.setOnClickPendingIntent(R.id.llWidgetEmpty, emptyPi)

        // 6. 刷新按钮
        val refreshIntent = Intent(context, providerClass).apply {
            action = ACTION_REFRESH_FEED
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        val refreshPi = PendingIntent.getBroadcast(
            context,
            RC_BASE_ACTION + appWidgetId * 10 + 2,
            refreshIntent,
            immutableFlags
        )
        views.setOnClickPendingIntent(R.id.btnWidgetRefresh, refreshPi)

        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.lvWidgetCourses)
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    private fun styleTabPill(views: RemoteViews, viewId: Int, selected: Boolean) {
        if (selected) {
            views.setInt(viewId, "setBackgroundResource", R.drawable.bg_widget_sign_pill)
            views.setTextColor(viewId, Color.WHITE)
        } else {
            views.setInt(viewId, "setBackgroundResource", R.drawable.bg_widget_badge)
            views.setTextColor(viewId, Color.parseColor("#8C1D27"))
        }
    }

    private fun bindTabClick(
        context: Context,
        views: RemoteViews,
        viewId: Int,
        providerClass: Class<*>,
        appWidgetId: Int,
        targetTab: Int,
        flags: Int
    ) {
        val intent = Intent(context, providerClass).apply {
            action = ACTION_SWITCH_TAB
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(EXTRA_TARGET_TAB, targetTab)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            RC_BASE_TAB + appWidgetId * 10 + targetTab,
            intent,
            flags
        )
        views.setOnClickPendingIntent(viewId, pi)
    }

    private fun bindScheduleSubHeader(
        context: Context,
        views: RemoteViews,
        config: WidgetConfig,
        providerClass: Class<*>,
        appWidgetId: Int,
        dayOffset: Int,
        flags: Int
    ) {
        val targetCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, dayOffset) }
        val dateDisplay = SimpleDateFormat("MM/dd", Locale.CHINA).format(targetCal.time)
        val dayOfWeek = targetCal.get(Calendar.DAY_OF_WEEK)
        val dayNumber = if (dayOfWeek == Calendar.SUNDAY) 7 else (dayOfWeek - 1)
        val dayStr = CourseUtils.getDayStr(dayNumber)

        var weekNum = 1
        var badgeText = "无课"
        var isHoliday = false
        var holidayName = ""

        try {
            val db = AppDatabase.getDatabase(context)
            val activeTable = db.tableDao.getDefaultTable()
            weekNum = CourseUtils.countWeek(activeTable.startDate, targetCal)
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(targetCal.time)
            val allCourses = db.courseBaseDao.getCourseOfTable(activeTable.id)
            val resolved = CourseAdjustmentResolver.resolve(
                db, activeTable.id, dateStr, weekNum, dayNumber, allCourses
            )
            if (resolved.isHolidayOff) {
                isHoliday = true
                holidayName = resolved.holidayName ?: "放假"
                badgeText = "放假: $holidayName"
            } else if (resolved.isSwapped) {
                val swapRemark = resolved.swapRemark ?: "补课"
                badgeText = "$swapRemark · ${resolved.courses.size}节课"
            } else {
                val weekType = if (weekNum % 2 != 0) 1 else 2
                val count = db.courseBaseDao.getCourseByDayAndWeekOfTable(
                    dayNumber, weekNum, weekType, activeTable.id
                ).size
                badgeText = if (count > 0) "共 $count 门课" else "无课"
            }
        } catch (_: Exception) {
        }

        val headerTitle = when (dayOffset) {
            0 -> "第 $weekNum 周 · $dayStr ($dateDisplay)"
            1 -> "明天 · 第 $weekNum 周 · $dayStr ($dateDisplay)"
            -1 -> "昨天 · 第 $weekNum 周 · $dayStr ($dateDisplay)"
            else -> "${if (dayOffset > 0) "+${dayOffset}天" else "${dayOffset}天"} · 第 $weekNum 周 · $dayStr ($dateDisplay)"
        }
        views.setTextViewText(R.id.tvWidgetHeaderDate, headerTitle)
        views.setTextViewText(R.id.tvWidgetStatusBadge, badgeText)

        val emptyPrefix = if (dayOffset == 0) "今日" else "该日"
        if (isHoliday) {
            views.setTextViewText(R.id.tvWidgetEmptyTitle, "$emptyPrefix 放假 ($holidayName)")
            views.setTextViewText(R.id.tvWidgetEmptySubtitle, "假期愉快，尽情享受悠闲时光吧~")
        } else {
            views.setTextViewText(R.id.tvWidgetEmptyTitle, "$emptyPrefix 无课")
            views.setTextViewText(R.id.tvWidgetEmptySubtitle, "好好休息，享受大学时光吧~")
        }

        if (config.allowDateSwitching) {
            views.setViewVisibility(R.id.llWidgetDateNav, View.VISIBLE)
            views.setViewVisibility(R.id.btnWidgetResetToday, if (dayOffset != 0) View.VISIBLE else View.GONE)

            val prevPi = PendingIntent.getBroadcast(
                context,
                RC_BASE_NAV + appWidgetId * 10 + 1,
                Intent(context, providerClass).apply {
                    action = ACTION_PREV_DAY
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                },
                flags
            )
            views.setOnClickPendingIntent(R.id.btnWidgetPrevDay, prevPi)

            val nextPi = PendingIntent.getBroadcast(
                context,
                RC_BASE_NAV + appWidgetId * 10 + 2,
                Intent(context, providerClass).apply {
                    action = ACTION_NEXT_DAY
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                },
                flags
            )
            views.setOnClickPendingIntent(R.id.btnWidgetNextDay, nextPi)

            val resetPi = PendingIntent.getBroadcast(
                context,
                RC_BASE_NAV + appWidgetId * 10 + 3,
                Intent(context, providerClass).apply {
                    action = ACTION_RESET_TODAY
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                },
                flags
            )
            views.setOnClickPendingIntent(R.id.btnWidgetResetToday, resetPi)
        } else {
            views.setViewVisibility(R.id.llWidgetDateNav, View.GONE)
        }

        if (config.showQuickSign) {
            views.setViewVisibility(R.id.btnWidgetQuickSign, View.VISIBLE)
            val signPi = PendingIntent.getActivity(
                context,
                RC_BASE_ACTION + appWidgetId * 10 + 3,
                Intent(context, MainActivity::class.java).apply {
                    putExtra("extra_trigger_smart_rollcall", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                flags
            )
            views.setOnClickPendingIntent(R.id.btnWidgetQuickSign, signPi)
        } else {
            views.setViewVisibility(R.id.btnWidgetQuickSign, View.GONE)
        }
    }

    fun handleReceive(
        context: Context,
        intent: Intent,
        providerClass: Class<*>,
        showScheduleTab: Boolean
    ) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )

        when (intent.action) {
            ACTION_SWITCH_TAB -> {
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val targetTab = intent.getIntExtra(
                        EXTRA_TARGET_TAB,
                        if (showScheduleTab) WidgetConfig.TAB_SCHEDULE else WidgetConfig.TAB_TODOS
                    )
                    WidgetConfigManager.setActiveTab(context, widgetId, targetTab)
                    updateAppWidget(context, manager, widgetId, providerClass, showScheduleTab)
                }
            }
            ACTION_PREV_DAY -> {
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val current = WidgetConfigManager.getDayOffset(context, widgetId)
                    WidgetConfigManager.setDayOffset(context, widgetId, current - 1)
                    updateAppWidget(context, manager, widgetId, providerClass, showScheduleTab)
                }
            }
            ACTION_NEXT_DAY -> {
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val current = WidgetConfigManager.getDayOffset(context, widgetId)
                    WidgetConfigManager.setDayOffset(context, widgetId, current + 1)
                    updateAppWidget(context, manager, widgetId, providerClass, showScheduleTab)
                }
            }
            ACTION_RESET_TODAY -> {
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetConfigManager.setDayOffset(context, widgetId, 0)
                    updateAppWidget(context, manager, widgetId, providerClass, showScheduleTab)
                }
            }
            ACTION_REFRESH_FEED -> {
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    updateAppWidget(context, manager, widgetId, providerClass, showScheduleTab)
                } else {
                    val ids = manager.getAppWidgetIds(ComponentName(context, providerClass)) ?: return
                    for (id in ids) {
                        updateAppWidget(context, manager, id, providerClass, showScheduleTab)
                    }
                }
                WidgetFeedSyncScheduler.triggerImmediateSync(context)
            }
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_DATE_CHANGED -> {
                WidgetConfigManager.resetAllDayOffsets(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, providerClass)) ?: return
                for (id in ids) {
                    updateAppWidget(context, manager, id, providerClass, showScheduleTab)
                }
            }
        }
    }
}
