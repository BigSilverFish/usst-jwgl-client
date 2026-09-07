package cn.edu.usst.jwgl.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.ui.MainActivity

class CourseReminderReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "course_reminder"
        const val EXTRA_COURSE_NAME = "extra_course_name"
        const val EXTRA_CLASSROOM = "extra_classroom"
        const val EXTRA_TEACHER = "extra_teacher"
        const val EXTRA_START_TIME = "extra_start_time"
        const val EXTRA_SECTION = "extra_section"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val name = "上课前提醒"
                val descriptionText = "上课前 15 分钟自动提醒即将开始的课程"
                val importance = NotificationManager.IMPORTANCE_HIGH
                val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                    description = descriptionText
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 200, 250)
                }
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.createNotificationChannel(channel)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val courseName = intent.getStringExtra(EXTRA_COURSE_NAME) ?: "课程"
        val classroom = intent.getStringExtra(EXTRA_CLASSROOM) ?: ""
        val teacher = intent.getStringExtra(EXTRA_TEACHER) ?: ""
        val startTime = intent.getStringExtra(EXTRA_START_TIME) ?: ""
        val section = intent.getStringExtra(EXTRA_SECTION) ?: ""
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 10001)

        createNotificationChannel(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("extra_tab", 1) // Switch to Timetable tab
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val roomText = if (classroom.isNotEmpty()) "@$classroom" else "未分配教室"
        val teacherText = if (teacher.isNotEmpty()) " · $teacher" else ""
        val content = "${startTime}开始 (${section}) | ${roomText}${teacherText}"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("🔔 还有 15 分钟上课：$courseName")
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${content}\n请带齐课件与学习用品，准时到达教室。"))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(notifId, builder.build())
        } catch (e: SecurityException) {
            // Android 13+ without POST_NOTIFICATIONS permission
        }
    }
}
