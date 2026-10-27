package cn.edu.usst.jwgl.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.ui.MainActivity

object RollcallNotificationHelper {

    const val CHANNEL_ID_SERVICE = "tronclass_rollcall_service"
    const val CHANNEL_ID_ALERT = "tronclass_rollcall_alert"

    const val NOTIFICATION_ID_FOREGROUND = 88001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // 1. 低优先级保活守护通道（静默、不干扰用户）
            val serviceChannel = NotificationChannel(
                CHANNEL_ID_SERVICE,
                "课堂签到后台守护",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "在上课时段驻留后台检测课堂签到的常驻通知"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            nm.createNotificationChannel(serviceChannel)

            // 2. 高优先级签到提醒通道（声音、振动、悬浮横幅）
            val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERT,
                "课堂签到提醒与通知",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "检测到课堂点名或雷达自动签到完成时的通知提醒"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300, 150, 300)
                setSound(defaultSound, audioAttributes)
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(alertChannel)
        }
    }

    /**
     * 构建前台服务的常驻通知
     */
    fun buildForegroundNotification(
        context: Context,
        session: ScheduledClassSession?,
        lastActionStatus: String? = null
    ): Notification {
        createChannels(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, 8) // Switch to TronClass tab
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_FOREGROUND,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val courseText = if (session != null) {
            val room = if (!session.room.isNullOrBlank()) "@${session.room}" else ""
            "正在监听【${session.courseName}】$room 签到"
        } else {
            "上课签到守护中 · 正在检测签到"
        }

        val subText = lastActionStatus ?: "驻留后台自动检测点名 · 雷达自动签到"

        return NotificationCompat.Builder(context, CHANNEL_ID_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("一网畅学课堂签到守护中")
            .setContentText(courseText)
            .setSubText(subText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * 雷达签到结果通知（自动匹配成功或需要手动介入）
     */
    fun notifyRadarResult(
        context: Context,
        rollcallId: Long,
        courseName: String?,
        building: CoordinateUtils.CampusBuilding?,
        success: Boolean,
        message: String?,
        classroom: String? = null
    ) {
        createChannels(context)

        val notifId = ((rollcallId % 100000L).toInt() + 20000)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, 8)
            if (!success) {
                putExtra("extra_open_radar_rollcall", true)
                putExtra("extra_rollcall_id", rollcallId)
                putExtra("extra_course_name", courseName)
                putExtra("extra_classroom", classroom)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)

        val builder = if (success) {
            val bName = building?.let { "${it.name} (${it.campus})" } ?: "匹配基准点位"
            NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("🎉 雷达点名已自动签到")
                .setContentText("【${courseName ?: "课堂"}】已自动匹配 [$bName] 并成功签到！")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "课程：${courseName ?: "未指定课程"}\n" +
                    "自动匹配点位：$bName\n" +
                    "签到状态：$message\n" +
                    "已为您在后台完成雷达定位签到。"
                ))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(defaultSound)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
        } else {
            val bName = building?.let { "${it.name} (${it.campus})" } ?: "基准点位"
            NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("⚠️ 雷达自动签到需手动确认")
                .setContentText("【${courseName ?: "课堂"}】尝试匹配 [$bName] 未成功 ($message)，点击手动选择点位")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "课程：${courseName ?: "未指定课程"}\n" +
                    "尝试匹配：$bName\n" +
                    "服务端反馈：$message\n" +
                    "请点击此通知打开雷达签到窗口，手动选择点位或使用设备 GPS 重新签到。"
                ))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(defaultSound)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
        }

        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    /**
     * 数字口令签到通知（高优先级全屏/横幅提醒，点击直接打开九宫格数字输入弹窗）
     */
    fun notifyNumberRollcall(context: Context, rollcallId: Long, courseName: String?) {
        createChannels(context)

        val notifId = ((rollcallId % 100000L).toInt() + 30000)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, 8)
            putExtra("extra_open_number_rollcall", true)
            putExtra("extra_rollcall_id", rollcallId)
            putExtra("extra_course_name", courseName)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("📢 收到数字签到通知")
            .setContentText("【${courseName ?: "课堂"}】教师已发起数字口令签到，点击输入4位签到码")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "课程：${courseName ?: "课堂"}\n" +
                "当前正在进行数字签到！请向任课教师或投影仪获取4位数字签到码，点击此通知即可快速输入并提交。"
            ))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(defaultSound)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    /**
     * 二维码扫码签到通知
     */
    fun notifyQrRollcall(context: Context, rollcallId: Long, courseName: String?) {
        createChannels(context)

        val notifId = ((rollcallId % 100000L).toInt() + 40000)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, 8)
            putExtra("extra_open_qr_scan", true)
            putExtra("extra_rollcall_id", rollcallId)
            putExtra("extra_course_name", courseName)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("📢 收到扫码签到通知")
            .setContentText("【${courseName ?: "课堂"}】教师已发起二维码签到，点击打开扫码器")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "课程：${courseName ?: "课堂"}\n" +
                "当前正在进行二维码扫码签到！点击此通知将立即打开扫码相机，对准投影仪二维码扫码即可完成所有账号签到。"
            ))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(defaultSound)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    /**
     * 其他类型签到通知
     */
    fun notifyGenericRollcall(context: Context, rollcallId: Long, courseName: String?, typeDesc: String) {
        createChannels(context)

        val notifId = ((rollcallId % 100000L).toInt() + 50000)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, 8)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("📢 收到课堂签到通知")
            .setContentText("【${courseName ?: "课堂"}】正在进行 $typeDesc，点击进入处理")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(defaultSound)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (_: SecurityException) {
        }
    }
}
