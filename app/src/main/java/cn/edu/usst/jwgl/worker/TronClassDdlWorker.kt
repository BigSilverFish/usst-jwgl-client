package cn.edu.usst.jwgl.worker

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
import cn.edu.usst.jwgl.receiver.TronClassDdlReceiver
import cn.edu.usst.jwgl.ui.tronclass.TronClassHomeworkActivity
import java.util.concurrent.TimeUnit

class TronClassDdlWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = TronClassDdlReceiver.CHANNEL_ID
        const val PREF_DDL_NOTIFIED = TronClassDdlReceiver.PREF_DDL_NOTIFIED
    }

    override suspend fun doWork(): Result {
        val prefs = TronClassPreferences(appContext)
        val is24hEnabled = prefs.isDdlReminder24hEnabled()
        val isUrgentEnabled = prefs.isDdlReminderUrgentEnabled()

        // 若二级提醒开关全部关闭，则静默跳过
        if (!is24hEnabled && !isUrgentEnabled) {
            return Result.success()
        }

        val client = TronClassClient(appContext)
        if (!client.isLoggedIn()) {
            return Result.success()
        }

        val res = client.fetchTodos()
        val todos = if (res.isSuccess) {
            val list = res.getOrDefault(emptyList())
            prefs.saveCachedTodos(list)
            list
        } else {
            // 网络异常时回退检查本地缓存待办
            prefs.getCachedTodos()
        }

        if (todos.isEmpty() && res.isFailure) {
            return Result.retry()
        }

        // 同步更新精确系统闹钟
        TronClassDdlScheduler.scheduleExactAlarms(appContext, todos)

        val now = System.currentTimeMillis()
        val sp = appContext.getSharedPreferences(PREF_DDL_NOTIFIED, Context.MODE_PRIVATE)
        val urgentHours = prefs.getDdlReminderUrgentHours()
        val urgentMillis = TimeUnit.HOURS.toMillis(urgentHours.toLong())

        for (todo in todos) {
            val endMillis = todo.getDeadlineDate()?.time ?: continue
            val remainingMillis = endMillis - now

            // 已过截止时间
            if (remainingMillis <= 0) continue

            // 老师延长截止时间（宽限大于 25 小时）时，自动重置之前的通知标记
            if (remainingMillis > TimeUnit.HOURS.toMillis(25)) {
                sp.edit()
                    .remove("todo_${todo.id}_notified_24h")
                    .remove("todo_${todo.id}_notified_urgent")
                    .apply()
                continue
            }

            // 1. 二级紧急提醒：临近 1-3 小时 (0..urgentMillis)
            if (isUrgentEnabled && remainingMillis in 0..urgentMillis) {
                val notifiedUrgent = sp.getBoolean("todo_${todo.id}_notified_urgent", false)
                if (!notifiedUrgent) {
                    showDdlNotification(todo, remainingMillis, isUrgent = true)
                    sp.edit().putBoolean("todo_${todo.id}_notified_urgent", true).apply()
                }
                continue
            }

            // 2. 一级截止预警：截止前 24 小时 (urgentMillis..24h)
            if (is24hEnabled && remainingMillis in urgentMillis..TimeUnit.HOURS.toMillis(24)) {
                val notified24h = sp.getBoolean("todo_${todo.id}_notified_24h", false)
                if (!notified24h) {
                    showDdlNotification(todo, remainingMillis, isUrgent = false)
                    sp.edit().putBoolean("todo_${todo.id}_notified_24h", true).apply()
                }
            }
        }

        // 尝试拉取超星学习通作业待办并纳入双轨 DDL 预警
        try {
            val cxClient = cn.edu.usst.jwgl.data.network.ChaoXingClient(appContext)
            if (cxClient.isLoggedIn()) {
                val cxTodos = cxClient.fetchTodos().getOrDefault(emptyList())
                for (cxTodo in cxTodos) {
                    val endMillis = cxTodo.deadlineMillis ?: continue
                    val remainingMillis = endMillis - now
                    if (remainingMillis <= 0) continue

                    val todoKey = "cx_todo_${cxTodo.originalTodoId}"

                    // 1. 二级紧急提醒
                    if (isUrgentEnabled && remainingMillis in 0..urgentMillis) {
                        val notifiedUrgent = sp.getBoolean("${todoKey}_notified_urgent", false)
                        if (!notifiedUrgent) {
                            showChaoXingDdlNotification(cxTodo, remainingMillis, isUrgent = true)
                            sp.edit().putBoolean("${todoKey}_notified_urgent", true).apply()
                        }
                        continue
                    }

                    // 2. 一级截止预警
                    if (is24hEnabled && remainingMillis in urgentMillis..TimeUnit.HOURS.toMillis(24)) {
                        val notified24h = sp.getBoolean("${todoKey}_notified_24h", false)
                        if (!notified24h) {
                            showChaoXingDdlNotification(cxTodo, remainingMillis, isUrgent = false)
                            sp.edit().putBoolean("${todoKey}_notified_24h", true).apply()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // 超星预警静默兜底
        }

        return Result.success()
    }

    private fun showChaoXingDdlNotification(todo: cn.edu.usst.jwgl.data.model.UnifiedTodo, remainingMillis: Long, isUrgent: Boolean) {
        TronClassDdlReceiver.createNotificationChannel(appContext)

        val hoursLeft = TimeUnit.MILLISECONDS.toHours(remainingMillis)
        val minutesLeft = (remainingMillis % TimeUnit.HOURS.toMillis(1)) / TimeUnit.MINUTES.toMillis(1)
        val timeLeftStr = if (hoursLeft > 0) "${hoursLeft}小时${minutesLeft}分钟" else "${minutesLeft}分钟"

        val courseName = todo.cleanCourseName
        val title = if (isUrgent) {
            "⚠️【学习通】作业紧急临期提醒 (仅剩$timeLeftStr)"
        } else {
            "【学习通】作业截止预警 (剩$timeLeftStr)"
        }

        val content = if (isUrgent) {
            "《$courseName》的「${todo.cleanTitle}」即将在 $timeLeftStr 后截止，请立即提交，避免逾期！"
        } else {
            "《$courseName》的「${todo.cleanTitle}」将在 $timeLeftStr 后截止，请合理安排时间尽快完成！"
        }

        val safeUrl = when {
            !todo.detailUrl.isNullOrBlank() -> todo.detailUrl
            else -> "https://mooc1.chaoxing.com/work/phone/work?courseId=${todo.originalCourseId}&classId=${todo.originalClassId ?: ""}&workId=${todo.originalTodoId}"
        }

        val intent = Intent(appContext, cn.edu.usst.jwgl.ui.JwglWebActivity::class.java).apply {
            putExtra(cn.edu.usst.jwgl.ui.JwglWebActivity.EXTRA_URL, safeUrl)
            putExtra(cn.edu.usst.jwgl.ui.JwglWebActivity.EXTRA_TITLE, todo.cleanTitle)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            appContext,
            todo.unifiedId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(defaultSound)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(todo.unifiedId.hashCode(), notification)
    }

    private fun showDdlNotification(todo: TronClassTodo, remainingMillis: Long, isUrgent: Boolean) {
        TronClassDdlReceiver.createNotificationChannel(appContext)

        val hoursLeft = TimeUnit.MILLISECONDS.toHours(remainingMillis)
        val minutesLeft = (remainingMillis % TimeUnit.HOURS.toMillis(1)) / TimeUnit.MINUTES.toMillis(1)
        val timeLeftStr = if (hoursLeft > 0) "${hoursLeft}小时${minutesLeft}分钟" else "${minutesLeft}分钟"

        val courseName = todo.getCleanCourseName()
        val title = if (isUrgent) {
            "⚠️【一网畅学】作业紧急临期提醒 (仅剩$timeLeftStr)"
        } else {
            "【一网畅学】作业截止预警 (剩$timeLeftStr)"
        }

        val content = if (isUrgent) {
            "《$courseName》的「${todo.title}」即将在 $timeLeftStr 后截止，请立即提交，避免逾期！"
        } else {
            "《$courseName》的「${todo.title}」将在 $timeLeftStr 后截止，请合理安排时间尽快完成！"
        }

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

        val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(defaultSound)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(todo.id.hashCode(), notification)
    }
}
