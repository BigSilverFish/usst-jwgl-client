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
        const val EXAM_CHANNEL_ID = "exam_reminder"
        const val EXTRA_IS_EXAM = "extra_is_exam"
        const val EXTRA_EXAM_NATURE = "extra_exam_nature"
        const val EXTRA_SEAT_NUMBER = "extra_seat_number"
        const val EXTRA_COURSE_NAME = "extra_course_name"
        const val EXTRA_CLASSROOM = "extra_classroom"
        const val EXTRA_TEACHER = "extra_teacher"
        const val EXTRA_START_TIME = "extra_start_time"
        const val EXTRA_SECTION = "extra_section"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                // Course Reminder Channel (15 mins before class)
                val channelCourse = NotificationChannel(
                    CHANNEL_ID,
                    "上课前提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "上课前 15 分钟自动提醒即将开始的课程"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 200, 250)
                }
                notificationManager.createNotificationChannel(channelCourse)

                // Exam Reminder Channel (30 mins before exam)
                val channelExam = NotificationChannel(
                    EXAM_CHANNEL_ID,
                    "考试日程提醒",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "考试开始前 30 分钟提醒考场、座号与时间"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 300, 200, 300, 200, 300)
                }
                notificationManager.createNotificationChannel(channelExam)
            }
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

        createNotificationChannel(context)

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

        val builder = if (isExam) {
            val roomText = if (classroom.isNotEmpty()) "考场：$classroom" else "考场待定"
            val seatText = if (seatNumber.isNotEmpty() && seatNumber != "未指定") " | 座号：$seatNumber" else ""
            val content = "${roomText}${seatText} | 时间：$startTime"
            NotificationCompat.Builder(context, EXAM_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("📝 考试提醒：还有 30 分钟开始【$courseName】")
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "科目：$courseName ($examNature)\n考场地点：$classroom\n座位号：${seatNumber.ifEmpty { "见考场公布栏" }}\n考试时间：$startTime\n请备齐学生证/身份证与考试文具，提前到达考场签到！"
                ))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
        } else {
            val roomText = if (classroom.isNotEmpty()) "@$classroom" else "未分配教室"
            val teacherText = if (teacher.isNotEmpty()) " · $teacher" else ""
            val content = "${startTime}开始 (${section}) | ${roomText}${teacherText}"
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("🔔 还有 15 分钟上课：$courseName")
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText("${content}\n请带齐课件与学习用品，准时到达教室。"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
        }

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(notifId, builder.build())
        } catch (e: SecurityException) {
            // Android 13+ without POST_NOTIFICATIONS permission
        }
    }
}
