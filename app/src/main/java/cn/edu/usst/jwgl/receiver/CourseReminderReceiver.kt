package cn.edu.usst.jwgl.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.util.NotificationDiagnosticHelper
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

        // 统一初始化/升级渠道
        createNotificationChannel(context)

        // 息屏唤醒：点亮屏幕 5 秒，避免放在课桌上完全察觉不到
        NotificationDiagnosticHelper.wakeScreenBriefly(context, 5000L)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("extra_tab", 1) // 切换到课表 Tab
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

        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val targetStartTimeMillis = System.currentTimeMillis() + advanceMins * 60 * 1000L

        val builder = if (isExam) {
            val roomText = if (classroom.isNotEmpty()) "考场：$classroom" else "考场待定"
            val seatText = if (seatNumber.isNotEmpty() && seatNumber != "未指定") " | 座号：$seatNumber" else ""
            val content = "${roomText}${seatText} | 时间：$startTime"
            val examColor = 0xFFD32F2F.toInt() // 警示高亮红
            val largeIcon = NotificationDiagnosticHelper.createCircleIconBitmap(
                context,
                R.drawable.ic_exam,
                examColor
            )

            NotificationCompat.Builder(context, EXAM_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_exam)
                .setContentTitle("📝 考试倒计时：还有 $advanceText【$courseName】")
                .setContentText(content)
                .setSubText("期末/阶段考试")
                .setLargeIcon(largeIcon)
                .setColor(examColor)
                .setColorized(true)
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "科目：$courseName ($examNature)\n考场地点：$classroom\n座位号：${seatNumber.ifEmpty { "见考场公布栏" }}\n考试时间：$startTime\n请备齐学生证/身份证与考试文具，提前到达考场签到！"
                    )
                )
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(targetStartTimeMillis)
                .setShowWhen(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(defaultSound)
                .setVibrate(longArrayOf(0, 450, 150, 450, 150, 450))
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_location, "查看考场与座号", pendingIntent)
                .addAction(R.drawable.ic_check_circle, "我知道了", pendingIntent)
        } else {
            val roomText = if (classroom.isNotEmpty()) "@$classroom" else "未分配教室"
            val teacherText = if (teacher.isNotEmpty()) " · $teacher" else ""
            val content = "${startTime}开始 (${section}) | ${roomText}${teacherText}"
            val courseColor = ContextCompat.getColor(context, R.color.primary)
            val largeIcon = NotificationDiagnosticHelper.createCircleIconBitmap(
                context,
                R.drawable.ic_school,
                courseColor
            )

            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_school)
                .setContentTitle("🔔 还有 $advanceText 上课：$courseName")
                .setContentText(content)
                .setSubText("上课提醒") // 适配 HyperOS / ColorOS 状态栏胶囊缩略标题
                .setLargeIcon(largeIcon)
                .setColor(courseColor)
                .setColorized(true)
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "${content}\n请带齐课件与学习用品，准时到达教室。"
                    )
                )
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setWhen(targetStartTimeMillis)
                .setShowWhen(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(defaultSound)
                .setVibrate(longArrayOf(0, 350, 180, 350))
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .addAction(R.drawable.ic_location, "查看教室路线", pendingIntent)
                .addAction(R.drawable.ic_check_circle, "我知道了", pendingIntent)
        }

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(notifId, builder.build())
        } catch (e: SecurityException) {
            // Android 13+ without POST_NOTIFICATIONS permission
        }

        // 异步滚动推进未来提醒计划
        val pendingResult = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                cn.edu.usst.jwgl.util.CourseReminderManager.scheduleUpcomingReminders(context)
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }
    }
}
