package cn.edu.usst.jwgl.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.TronClassTodo
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.ui.tronclass.TronClassHomeworkActivity
import java.util.concurrent.TimeUnit

class TronClassDdlWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "tronclass_ddl_channel"
        private const val PREF_DDL_NOTIFIED = "tronclass_ddl_notified"
        private const val NOTIFY_SUPPRESS_WINDOW_HOURS = 10L // 10小时内不重复通知同一待办
    }

    override suspend fun doWork(): Result {
        val client = TronClassClient(appContext)
        if (!client.isLoggedIn()) {
            return Result.success()
        }

        val res = client.fetchTodos()
        if (res.isFailure) {
            return Result.retry()
        }

        val todos = res.getOrDefault(emptyList())
        val prefs = TronClassPreferences(appContext)
        prefs.saveCachedTodos(todos)

        val now = System.currentTimeMillis()
        val sp = appContext.getSharedPreferences(PREF_DDL_NOTIFIED, Context.MODE_PRIVATE)

        for (todo in todos) {
            val endMillis = todo.getDeadlineDate()?.time ?: continue
            val remainingMillis = endMillis - now

            // 只针对未来 24 小时内即将截止且未过期的待办进行高优先级提醒
            if (remainingMillis in 0..TimeUnit.HOURS.toMillis(24)) {
                val lastNotified = sp.getLong("todo_${todo.id}", 0L)
                val hoursSinceLast = TimeUnit.MILLISECONDS.toHours(now - lastNotified)

                if (hoursSinceLast >= NOTIFY_SUPPRESS_WINDOW_HOURS) {
                    showDdlNotification(todo, remainingMillis)
                    sp.edit().putLong("todo_${todo.id}", now).apply()
                }
            }
        }

        return Result.success()
    }

    private fun showDdlNotification(todo: TronClassTodo, remainingMillis: Long) {
        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // 创建通知渠道 (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "一网畅学作业与测验截止预警",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "在作业或测验临近截止时间（24小时内）时发出提醒"
                enableVibration(true)
                enableLights(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val hoursLeft = TimeUnit.MILLISECONDS.toHours(remainingMillis)
        val minutesLeft = (remainingMillis % TimeUnit.HOURS.toMillis(1)) / TimeUnit.MINUTES.toMillis(1)
        val timeLeftStr = if (hoursLeft > 0) "${hoursLeft}小时${minutesLeft}分钟" else "${minutesLeft}分钟"

        val courseName = todo.getCleanCourseName()
        val title = "【一网畅学】作业临近截止 ($timeLeftStr)"
        val content = "《$courseName》的「${todo.title}」即将在 $timeLeftStr 后截止，请尽快完成并提交！"

        // 点击通知跳转到作业详情页
        val intent = Intent(appContext, TronClassHomeworkActivity::class.java).apply {
            putExtra("extra_activity_id", todo.id)
            putExtra("extra_course_id", todo.courseId)
            putExtra("extra_title", todo.title)
            putExtra("extra_course_name", courseName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            appContext,
            todo.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(todo.id.hashCode(), notification)
    }
}
