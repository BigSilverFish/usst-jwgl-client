package cn.edu.usst.jwgl.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.ui.tronclass.TronClassHomeworkActivity
import java.util.concurrent.TimeUnit

class TronClassDdlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TRONCLASS_DDL_ALARM = "cn.edu.usst.jwgl.action.TRONCLASS_DDL_ALARM"
        const val CHANNEL_ID = "tronclass_ddl_channel"
        const val PREF_DDL_NOTIFIED = "tronclass_ddl_notified"

        const val EXTRA_TODO_ID = "extra_todo_id"
        const val EXTRA_TODO_TITLE = "extra_todo_title"
        const val EXTRA_COURSE_NAME = "extra_course_name"
        const val EXTRA_COURSE_ID = "extra_course_id"
        const val EXTRA_IS_URGENT = "extra_is_urgent"
        const val EXTRA_DEADLINE_MILLIS = "extra_deadline_millis"

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()

                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "一网畅学作业与测验截止预警",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "在作业或测验临近截止时间（24小时内及临近1-3小时）时发出提醒"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 350, 150, 350)
                    setSound(defaultSound, audioAttributes)
                    enableLights(true)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val todoId = intent.getLongExtra(EXTRA_TODO_ID, 0L)
        if (todoId == 0L) return

        val todoTitle = intent.getStringExtra(EXTRA_TODO_TITLE) ?: "作业"
        val courseName = intent.getStringExtra(EXTRA_COURSE_NAME) ?: "课程"
        val courseId = intent.getLongExtra(EXTRA_COURSE_ID, 0L)
        val isUrgent = intent.getBooleanExtra(EXTRA_IS_URGENT, false)
        val deadlineMillis = intent.getLongExtra(EXTRA_DEADLINE_MILLIS, 0L)

        val prefs = TronClassPreferences(context)
        if (isUrgent && !prefs.isDdlReminderUrgentEnabled()) return
        if (!isUrgent && !prefs.isDdlReminder24hEnabled()) return

        // 检查去重缓存，避免重复弹窗
        val sp = context.getSharedPreferences(PREF_DDL_NOTIFIED, Context.MODE_PRIVATE)
        val notifyKey = if (isUrgent) "todo_${todoId}_notified_urgent" else "todo_${todoId}_notified_24h"
        if (sp.getBoolean(notifyKey, false)) return

        // 若已有本地缓存且该作业已被移除（已提交），则不再提醒
        val cachedTodos = prefs.getCachedTodos()
        if (cachedTodos.isNotEmpty() && cachedTodos.none { it.id == todoId }) {
            return
        }

        val now = System.currentTimeMillis()
        val remainingMillis = (deadlineMillis - now).coerceAtLeast(0L)
        val hoursLeft = TimeUnit.MILLISECONDS.toHours(remainingMillis)
        val minutesLeft = (remainingMillis % TimeUnit.HOURS.toMillis(1)) / TimeUnit.MINUTES.toMillis(1)
        val timeLeftStr = if (hoursLeft > 0) "${hoursLeft}小时${minutesLeft}分钟" else "${minutesLeft}分钟"

        val title = if (isUrgent) {
            "⚠️【一网畅学】作业紧急临期提醒 (仅剩$timeLeftStr)"
        } else {
            "【一网畅学】作业截止预警 (剩$timeLeftStr)"
        }

        val content = if (isUrgent) {
            "《$courseName》的「$todoTitle」即将在 $timeLeftStr 后截止，请立即提交，避免逾期！"
        } else {
            "《$courseName》的「$todoTitle」将在 $timeLeftStr 后截止，请合理安排时间尽快完成！"
        }

        createNotificationChannel(context)

        val openIntent = Intent(context, TronClassHomeworkActivity::class.java).apply {
            putExtra("extra_activity_id", todoId)
            putExtra("extra_course_id", courseId)
            putExtra("extra_title", todoTitle)
            putExtra("extra_course_name", courseName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            todoId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
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
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(todoId.hashCode(), notification)

        sp.edit().putBoolean(notifyKey, true).apply()
    }
}
