package cn.edu.usst.jwgl.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import cn.edu.usst.jwgl.worker.WidgetFeedSyncScheduler

/**
 * 全能聚合「校园动态」桌面微件 (4×2)：
 * 支持在同一个微件顶部一键切换「课表 | 待办 | 通知」三种视图。
 */
class OmniCampusWidgetProvider : AppWidgetProvider() {

    companion object {
        fun updateAllWidgets(context: Context) {
            MultiFeedWidgetRenderer.updateAllOf(context, OmniCampusWidgetProvider::class.java)
        }

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            MultiFeedWidgetRenderer.updateAppWidget(
                context,
                appWidgetManager,
                appWidgetId,
                OmniCampusWidgetProvider::class.java,
                showScheduleTab = true
            )
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, id)
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetFeedSyncScheduler.scheduleOrUpdate(context)
        WidgetFeedSyncScheduler.triggerImmediateSync(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetFeedSyncScheduler.scheduleOrUpdate(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        for (id in appWidgetIds) {
            WidgetConfigManager.deleteConfig(context, id)
        }
        WidgetFeedSyncScheduler.scheduleOrUpdate(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        MultiFeedWidgetRenderer.handleReceive(
            context,
            intent,
            OmniCampusWidgetProvider::class.java,
            showScheduleTab = true
        )
    }
}
