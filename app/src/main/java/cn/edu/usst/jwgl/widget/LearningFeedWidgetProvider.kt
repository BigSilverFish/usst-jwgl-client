package cn.edu.usst.jwgl.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import cn.edu.usst.jwgl.worker.WidgetFeedSyncScheduler

/**
 * 独立「学习动态」桌面微件 (4×2)：
 * 专注展示 7 天内未交待办与近 7 天课程通知，支持一键切换标签。
 */
class LearningFeedWidgetProvider : AppWidgetProvider() {

    companion object {
        fun updateAllWidgets(context: Context) {
            MultiFeedWidgetRenderer.updateAllOf(context, LearningFeedWidgetProvider::class.java)
        }

        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            MultiFeedWidgetRenderer.updateAppWidget(
                context,
                appWidgetManager,
                appWidgetId,
                LearningFeedWidgetProvider::class.java,
                showScheduleTab = false
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
            LearningFeedWidgetProvider::class.java,
            showScheduleTab = false
        )
    }
}
