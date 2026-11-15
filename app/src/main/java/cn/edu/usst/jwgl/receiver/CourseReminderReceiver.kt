package cn.edu.usst.jwgl.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.util.NotificationDiagnosticHelper
import cn.edu.usst.jwgl.util.NotificationUiHelper
import kotlinx.coroutines.launch

class CourseReminderReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = NotificationDiagnosticHelper.CHANNEL_COURSE
        const val EXAM_CHANNEL_ID = NotificationDiagnosticHelper.CHANNEL_EXAM
        const val EXTRA_IS_EXAM = "extra_is_exam"
        const val EXTRA_EXAM_NATURE = "extra_exam_nature"
        const val EXTRA_SEAT_NUMBER = "extra_seat_number"
        const val EXTRA_COURSE_NAME = "extra_course_name"
        const val EXTRA_CLASSROOM = "extra_classroom"
        const val EXTRA_TEACHER = "extra_teacher"
        const val EXTRA_START_TIME = "extra_start_time"
        const val EXTRA_SECTION = "extra_section"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_ADVANCE_MINUTES = "extra_advance_minutes"

        fun createNotificationChannel(context: Context) {
            NotificationDiagnosticHelper.initNotificationChannels(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val isExam = intent.getBooleanExtra(EXTRA_IS_EXAM, false)
        val courseName = intent.getStringExtra(EXTRA_COURSE_NAME) ?: if (isExam) "考试科目" else "课程"
        val classroom = intent.getStringExtra(EXTRA_CLASSROOM) ?: ""
        val teacher = intent.getStringExtra(EXTRA_TEACHER) ?: ""
        val startTime = intent.getStringExtra(EXTRA_START_TIME) ?: ""
        val section = intent.getStringExtra(EXTRA_SECTION) ?: ""
        val examNature = intent.getStringExtra(EXTRA_EXAM_NATURE) ?: "考试"
        val seatNumber = intent.getStringExtra(EXTRA_SEAT_NUMBER) ?: ""
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 10001)
        val advanceMins = intent.getIntExtra(EXTRA_ADVANCE_MINUTES, if (isExam) 30 else 15)
        val advanceText = cn.edu.usst.jwgl.util.CourseReminderManager.formatAdvanceMinutes(advanceMins)

        createNotificationChannel(context)

        // 熄屏时自动点亮屏幕 5 秒，防课桌静音漏看
        NotificationDiagnosticHelper.wakeScreenBriefly(context, 5000L)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("extra_tab", 1) // Switch to Timetable tab
            if (isExam) {
                putExtra("extra_open_exam", true)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissPendingIntent = NotificationUiHelper.createDismissPendingIntent(context, notifId)
        val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        val primaryColor = ContextCompat.getColor(context, R.color.primary)

        val builder = if (isExam) {
            val roomText = if (classroom.isNotEmpty()) "考场：$classroom" else "考场待定"
            val seatText = if (seatNumber.isNotEmpty() && seatNumber != "未指定") " | 座号：$seatNumber" else ""
            val content = "${roomText}${seatText} | 时间：$startTime"
            val examColor = ContextCompat.getColor(context, R.color.error)
            val largeIcon = NotificationDiagnosticHelper.createCircleIconBitmap(context, R.drawable.ic_exam, examColor)

            NotificationCompat.Builder(context, EXAM_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .apply { if (largeIcon != null) setLargeIcon(largeIcon) }
                .setColor(examColor)
                .setColorized(true)
                .setContentTitle("📝 考试提醒：还有 $advanceText 开始【$courseName】")
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "科目：$courseName ($examNature)\n考场地点：$classroom\n座位号：${seatNumber.ifEmpty { "见考场公布栏" }}\n考试时间：$startTime\n请备齐学生证/身份证与考试文具，提前到达考场签到！"
                ))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(defaultSound)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVibrate(longArrayOf(0, 450, 150, 450, 150, 450))
                .setTicker("【考试提醒】$courseName 即将开始，考场：$classroom")
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_exam, "查看考场日程", pendingIntent)
                .addAction(R.drawable.ic_check_circle, "我知道了", dismissPendingIntent)
        } else {
            val roomText = if (classroom.isNotEmpty()) "@$classroom" else "未分配教室"
            val teacherText = if (teacher.isNotEmpty()) " · $teacher" else ""
            val content = "${startTime}开始 (${section}) | ${roomText}${teacherText}"
            val largeIcon = NotificationDiagnosticHelper.createCircleIconBitmap(context, R.drawable.ic_schedule, primaryColor)

            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .apply { if (largeIcon != null) setLargeIcon(largeIcon) }
                .setColor(primaryColor)
                .setColorized(true)
                .setContentTitle("🔔 还有 $advanceText 上课：$courseName")
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${content}\n请带齐课件与学习用品，准时到达教室。"))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(defaultSound)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setVibrate(longArrayOf(0, 350, 180, 350))
                .setTicker("【上课提醒】$courseName 还有 $advanceText 开始")
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_nav_timetable, "查看课表", pendingIntent)
                .addAction(R.drawable.ic_check_circle, "我知道了", dismissPendingIntent)
        }

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(notifId, builder.build())
        } catch (e: SecurityException) {
            // Android 13+ without POST_NOTIFICATIONS permission
        }

        // Asynchronously advance the rolling schedule so future alarms stay fresh
        val pendingResult = goAsync()
        val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
            android.util.Log.e("CourseReminderReceiver", "Unhandled exception in CourseReminderReceiver async task", throwable)
            try {
                pendingResult.finish()
            } catch (_: Exception) {
            }
        }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO + exceptionHandler).launch {
            try {
                cn.edu.usst.jwgl.util.CourseReminderManager.scheduleUpcomingReminders(context)
            } catch (e: Exception) {
                android.util.Log.w("CourseReminderReceiver", "Failed to schedule upcoming reminders", e)
            } finally {
                try {
                    pendingResult.finish()
                } catch (_: Exception) {
                }
            }
        }
    }
}
