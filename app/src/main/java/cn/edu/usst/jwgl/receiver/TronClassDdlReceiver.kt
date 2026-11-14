package cn.edu.usst.jwgl.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.ui.tronclass.TronClassHomeworkActivity
import cn.edu.usst.jwgl.util.NotificationDiagnosticHelper
import cn.edu.usst.jwgl.util.NotificationUiHelper
import java.util.concurrent.TimeUnit

class TronClassDdlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TRONCLASS_DDL_ALARM = "cn.edu.usst.jwgl.action.TRONCLASS_DDL_ALARM"
        const val CHANNEL_ID = NotificationDiagnosticHelper.CHANNEL_DDL
        const val PREF_DDL_NOTIFIED = "tronclass_ddl_notified"

        const val EXTRA_TODO_ID = "extra_todo_id"
        const val EXTRA_TODO_TITLE = "extra_todo_title"
        const val EXTRA_COURSE_NAME = "extra_course_name"
        const val EXTRA_COURSE_ID = "extra_course_id"
        const val EXTRA_IS_URGENT = "extra_is_urgent"
        const val EXTRA_DEADLINE_MILLIS = "extra_deadline_millis"

        fun createNotificationChannel(context: Context) {
            NotificationDiagnosticHelper.initNotificationChannels(context)
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
            "⏰【一网畅学】作业截止预警 (剩$timeLeftStr)"
        }

        val content = if (isUrgent) {
            "《$courseName》的「$todoTitle」即将在 $timeLeftStr 后截止，请立即提交，避免逾期！"
        } else {
            "《$courseName》的「$todoTitle」将在 $timeLeftStr 后截止，请合理安排时间尽快完成！"
        }

        createNotificationChannel(context)

        // 熄屏时自动点亮屏幕 4 秒
        NotificationDiagnosticHelper.wakeScreenBriefly(context, 4000L)

        val openIntent = Intent(context, TronClassHomeworkActivity::class.java).apply {
            putExtra("extra_activity_id", todoId)
            putExtra("extra_course_id", courseId)
            putExtra("extra_title", todoTitle)
            putExtra("extra_course_name", courseName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val notifId = todoId.hashCode()
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val dismissPendingIntent = NotificationUiHelper.createDismissPendingIntent(context, notifId)
        val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)

        val alertColor = if (isUrgent) {
            Color.parseColor("#D32F2F") // 鲜艳警示红
        } else {
            Color.parseColor("#E65100") // 琥珀深橙
        }
        val largeIcon = NotificationDiagnosticHelper.createCircleIconBitmap(context, R.drawable.ic_homework, alertColor)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .apply { if (largeIcon != null) setLargeIcon(largeIcon) }
            .setColor(alertColor)
            .setColorized(true)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(defaultSound)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 300, 150, 300))
            .setTicker(title)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_homework, "前往作业", pendingIntent)
            .addAction(R.drawable.ic_check_circle, "我知道了", dismissPendingIntent)
            .build()

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notifId, notification)

        sp.edit().putBoolean(notifyKey, true).apply()
    }
}
