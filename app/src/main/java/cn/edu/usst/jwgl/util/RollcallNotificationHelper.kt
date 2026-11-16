package cn.edu.usst.jwgl.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.ui.tronclass.RollcallAlertActivity

object RollcallNotificationHelper {

    const val CHANNEL_ID_SERVICE = NotificationDiagnosticHelper.CHANNEL_ROLLCALL_SERVICE
    const val CHANNEL_ID_ALERT = NotificationDiagnosticHelper.CHANNEL_ROLLCALL_ALERT

    const val NOTIFICATION_ID_FOREGROUND = 88001

    /**
     * 发现课堂签到时尝试直接呼出锁屏/桌面强弹窗（若开关开启）
     */
    fun launchAlertPopupIfEnabled(context: Context, intent: Intent) {
        val prefs = TronClassPreferences.getInstance(context)
        if (!prefs.isRollcallPopupEnabled()) return
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.d("RollcallNotifHelper", "Direct background startActivity prevented: ${e.message}")
        }
    }

    fun createChannels(context: Context) {
        NotificationDiagnosticHelper.initNotificationChannels(context)
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
        classroom: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        scope: Double? = null
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
                if (latitude != null) putExtra("extra_latitude", latitude)
                if (longitude != null) putExtra("extra_longitude", longitude)
                if (scope != null) putExtra("extra_scope", scope)
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

        if (!success) {
            val alertIntent = RollcallAlertActivity.createIntent(
                context = context,
                rollcallId = rollcallId,
                courseName = courseName,
                type = RollcallAlertActivity.TYPE_RADAR,
                classroom = classroom,
                buildingName = building?.name,
                latitude = latitude,
                longitude = longitude
            )
            val fullScreenPendingIntent = PendingIntent.getActivity(
                context,
                notifId + 500,
                alertIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setFullScreenIntent(fullScreenPendingIntent, true)
            launchAlertPopupIfEnabled(context, alertIntent)
        }
        applyAlertStyling(context, builder, !success, notifId, pendingIntent, if (!success) "选择点位签到" else "查看详情")

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

        val alertIntent = RollcallAlertActivity.createIntent(
            context = context,
            rollcallId = rollcallId,
            courseName = courseName,
            type = RollcallAlertActivity.TYPE_NUMBER
        )
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            notifId + 500,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setFullScreenIntent(fullScreenPendingIntent, true)
        launchAlertPopupIfEnabled(context, alertIntent)

        applyAlertStyling(context, builder, true, notifId, pendingIntent, "输入签到码")

        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    /**
     * 数字口令自动签到结果通知（后台自动从服务端获取到签到码并提交后）
     */
    fun notifyNumberAutoResult(
        context: Context,
        rollcallId: Long,
        courseName: String?,
        success: Boolean,
        message: String?
    ) {
        createChannels(context)

        val notifId = ((rollcallId % 100000L).toInt() + 35000)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, 8)
            if (!success) {
                putExtra("extra_open_number_rollcall", true)
                putExtra("extra_rollcall_id", rollcallId)
                putExtra("extra_course_name", courseName)
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
            NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("🎉 数字口令已自动签到")
                .setContentText("【${courseName ?: "课堂"}】已自动获取签到码并成功签到！")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "课程：${courseName ?: "未指定课程"}\n" +
                    "签到状态：$message\n" +
                    "已从服务端自动获取数字口令并在后台完成签到。"
                ))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(defaultSound)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
        } else {
            NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("⚠️ 数字口令自动签到未成功")
                .setContentText("【${courseName ?: "课堂"}】自动签到未成功，点击手动输入口令")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "课程：${courseName ?: "课堂"}\n" +
                    "反馈：$message\n" +
                    "自动获取的口令签到未成功，请点击通知手动输入教师提供的4位数字签到码。"
                ))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(defaultSound)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
        }
        applyAlertStyling(context, builder, !success, notifId, pendingIntent, if (!success) "输入签到码" else "查看详情")

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

        val alertIntent = RollcallAlertActivity.createIntent(
            context = context,
            rollcallId = rollcallId,
            courseName = courseName,
            type = RollcallAlertActivity.TYPE_QR
        )
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            notifId + 500,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setFullScreenIntent(fullScreenPendingIntent, true)
        launchAlertPopupIfEnabled(context, alertIntent)

        applyAlertStyling(context, builder, true, notifId, pendingIntent, "打开扫码相机")

        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    /**
     * 智慧录播大屏二维码自动签到结果通知
     */
    fun notifyLiveQrResult(
        context: Context,
        rollcallId: Long,
        courseName: String?,
        success: Boolean,
        viewName: String,
        message: String?
    ) {
        createChannels(context)

        val notifId = ((rollcallId % 100000L).toInt() + 45000)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, 8)
            if (!success) {
                putExtra("extra_open_qr_scan", true)
                putExtra("extra_rollcall_id", rollcallId)
                putExtra("extra_course_name", courseName)
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
            NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("🎉 大屏二维码已秒级自动签到")
                .setContentText("【${courseName ?: "课堂"}】已通过智慧录播 [$viewName] 自动扫码并签到成功！")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "课程：${courseName ?: "未指定课程"}\n" +
                    "识别机位：$viewName\n" +
                    "签到状态：$message\n" +
                    "已通过 FLV 低延迟机位极速抽取大屏画面，在 1.5 秒内全自动完成二维码识别与提交。"
                ))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(defaultSound)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
        } else {
            NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("📢 收到扫码签到 (大屏未检测到)")
                .setContentText("【${courseName ?: "课堂"}】教师已发起二维码签到，点击打开扫码器")
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    "课程：${courseName ?: "课堂"}\n" +
                    "自动检测反馈：$message\n" +
                    "当前正在进行二维码扫码签到！点击此通知将立即打开扫码相机手动对准幕布完成签到。"
                ))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(defaultSound)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
        }
        applyAlertStyling(context, builder, !success, notifId, pendingIntent, if (!success) "手动扫码" else "查看详情")

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

        val alertIntent = RollcallAlertActivity.createIntent(
            context = context,
            rollcallId = rollcallId,
            courseName = courseName,
            type = RollcallAlertActivity.TYPE_GENERIC
        )
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            notifId + 500,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setFullScreenIntent(fullScreenPendingIntent, true)
        launchAlertPopupIfEnabled(context, alertIntent)

        applyAlertStyling(context, builder, true, notifId, pendingIntent, "前往处理")

        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    /**
     * 自主签到结果通知
     */
    fun notifySelfRegistrationResult(
        context: Context,
        rollcallId: Long,
        courseName: String?,
        success: Boolean,
        message: String
    ) {
        createChannels(context)

        val notifId = ((rollcallId % 100000L).toInt() + 60000)

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

        val title = if (success) "✅ 自主签到成功" else "⚠️ 自主签到失败"
        val content = if (success) {
            "【${courseName ?: "课堂"}】已全自动完成自主点名确认"
        } else {
            "【${courseName ?: "课堂"}】自主签到未能确认: $message"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        applyAlertStyling(context, builder, !success, notifId, pendingIntent, if (!success) "手动签到" else "查看详情")

        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    /**
     * 超星位置签到自动提交结果通知
     */
    fun notifyChaoXingLocationResult(
        context: Context,
        activeId: Long,
        courseName: String?,
        locationLabel: String,
        success: Boolean,
        message: String
    ) {
        createChannels(context)

        val notifId = ((activeId % 100000L).toInt() + 70000)

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
        val title = if (success) "🎉 超星位置签到已自动完成" else "⚠️ 超星位置自动签到需手动确认"
        val body = if (success) {
            "【${courseName ?: "课堂"}】已使用 [$locationLabel] 自动完成学习通位置签到 ($message)"
        } else {
            "【${courseName ?: "课堂"}】尝试使用 [$locationLabel] 自动签到未确认 ($message)，点击进入课程门户手动选点或定位"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(defaultSound)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
        applyAlertStyling(context, builder, !success, notifId, pendingIntent, if (!success) "前往签到" else "查看详情")

        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    private fun applyAlertStyling(
        context: Context,
        builder: NotificationCompat.Builder,
        isWarningOrActionNeeded: Boolean,
        notifId: Int,
        actionPendingIntent: PendingIntent? = null,
        actionLabel: String = "立即处理"
    ) {
        NotificationDiagnosticHelper.wakeScreenBriefly(context, 5000L)
        val alertColor = if (isWarningOrActionNeeded) {
            Color.parseColor("#E53935") // 鲜艳警示红
        } else {
            Color.parseColor("#2E7D32") // 绿色成功
        }
        val iconRes = if (isWarningOrActionNeeded) R.drawable.ic_radar else R.drawable.ic_check_circle
        val largeIcon = NotificationDiagnosticHelper.createCircleIconBitmap(context, iconRes, alertColor)

        builder.setColor(alertColor)
            .setColorized(true)
            .setPriority(if (isWarningOrActionNeeded) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setVibrate(if (isWarningOrActionNeeded) longArrayOf(0, 400, 120, 400, 120, 400) else longArrayOf(0, 250, 100, 250))
            .apply {
                if (largeIcon != null) setLargeIcon(largeIcon)
            }

        if (actionPendingIntent != null && isWarningOrActionNeeded) {
            builder.addAction(iconRes, actionLabel, actionPendingIntent)
        }
        val dismissPendingIntent = NotificationUiHelper.createDismissPendingIntent(context, notifId)
        builder.addAction(R.drawable.ic_check_circle, "我知道了", dismissPendingIntent)
    }
}
