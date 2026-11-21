package cn.edu.usst.jwgl.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cn.edu.usst.jwgl.widget.WidgetConfigManager
import cn.edu.usst.jwgl.widget.WidgetUpdateHelper
import java.util.concurrent.TimeUnit

/**
 * 桌面微件学习动态（待办/通知）同步调度器：
 * 支持 1/2/3/4/6/8/12/18/24 小时自定义周期，仅在桌面存在微件时维持周期任务。
 */
object WidgetFeedSyncScheduler {

    private const val WORK_NAME_PERIODIC = "widget_feed_periodic_sync"
    private const val WORK_NAME_IMMEDIATE = "widget_feed_immediate_sync"

    fun scheduleOrUpdate(context: Context) {
        val appContext = context.applicationContext
        if (!WidgetUpdateHelper.hasAnyActiveWidget(appContext)) {
            cancel(appContext)
            return
        }

        val intervalHours = WidgetConfigManager.getSyncIntervalHours(appContext).toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<WidgetFeedSyncWorker>(intervalHours, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun triggerImmediateSync(context: Context) {
        val appContext = context.applicationContext
        if (!WidgetUpdateHelper.hasAnyActiveWidget(appContext)) return

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<WidgetFeedSyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            WORK_NAME_IMMEDIATE,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME_PERIODIC)
    }
}
