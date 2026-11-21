package cn.edu.usst.jwgl.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/**
 * 统一桌面微件刷新协调工具：集中检测及刷新全部 4 种桌面微件。
 */
object WidgetUpdateHelper {

    fun hasAnyActiveWidget(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        val providers = listOf(
            TodayScheduleWidgetProvider::class.java,
            LearningFeedWidgetProvider::class.java,
            OmniCampusWidgetProvider::class.java,
            NextCourseWidgetProvider::class.java
        )
        return providers.any { cls ->
            val ids = manager.getAppWidgetIds(ComponentName(context, cls))
            ids != null && ids.isNotEmpty()
        }
    }

    fun updateAllWidgets(context: Context) {
        TodayScheduleWidgetProvider.updateAllWidgets(context)
        LearningFeedWidgetProvider.updateAllWidgets(context)
        OmniCampusWidgetProvider.updateAllWidgets(context)
        NextCourseWidgetProvider.updateAllWidgets(context)
    }
}
