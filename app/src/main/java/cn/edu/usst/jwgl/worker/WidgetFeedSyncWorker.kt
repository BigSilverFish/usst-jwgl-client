package cn.edu.usst.jwgl.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cn.edu.usst.jwgl.widget.WidgetFeedRepository
import cn.edu.usst.jwgl.widget.WidgetUpdateHelper

/**
 * 桌面微件后台定时静默同步 Worker：
 * 定期拉取一网畅学与超星学习通的最新待办与近7天课程公告并刷新桌面微件。
 */
class WidgetFeedSyncWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!WidgetUpdateHelper.hasAnyActiveWidget(appContext)) {
            return Result.success()
        }
        return try {
            WidgetFeedRepository.syncFeedFromNetwork(appContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
