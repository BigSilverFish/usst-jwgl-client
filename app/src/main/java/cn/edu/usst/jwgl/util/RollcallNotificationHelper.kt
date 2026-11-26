package cn.edu.usst.jwgl.util

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
    private const val NOTIF_OFFSET_AUTO_DETECTED = 15000

    internal fun shouldWakeScreenBeforeNotify(silent: Boolean, hasFullScreenIntent: Boolean): Boolean =
        !silent && !hasFullScreenIntent

    /**
     * 发现课堂签到时按前台 Activity 路由尝试呼出强弹窗。
     * 仅在前台 Activity 已恢复且未被拦截时返回 true；后台弹窗由独立悬浮窗通道负责并确认附着后才静默通知。
     */
    fun launchAlertPopupIfEnabled(context: Context, intent: Intent): Boolean {
        if (resolvePopupRoute(context, RollcallAlertStage.MANUAL_DETECTED) != RollcallPopupRoute.FOREGROUND_ACTIVITY) {
            return false
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.d("RollcallNotifHelper", "Foreground startActivity failed: ${e.message}")
            false
        }
    }

    private fun resolveEffectiveMode(
        context: Context,
        stage: RollcallAlertStage,
        overrideMode: RollcallAlertMode?
    ): RollcallAlertMode {
        if (overrideMode != null) return overrideMode
        val userMode = TronClassPreferences.getInstance(context.applicationContext).getRollcallAlertMode()
        return RollcallPopupRouter.resolveEffectiveMode(userMode, stage)
    }

    private fun resolvePopupRoute(
        context: Context,
        stage: RollcallAlertStage,
        overrideMode: RollcallAlertMode? = null
    ): RollcallPopupRoute {
        val effectiveMode = resolveEffectiveMode(context, stage, overrideMode)
        return resolvePopupRouteForMode(context, effectiveMode)
    }

    private fun resolvePopupRouteForMode(
        context: Context,
        effectiveMode: RollcallAlertMode
    ): RollcallPopupRoute {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return RollcallPopupRouter.decide(
            effectiveMode = effectiveMode,
            hasResumedActivity = RollcallOverlayAlert.hasResumedActivity(),
            isScreenInteractive = powerManager?.isInteractive ?: true,
            isKeyguardLocked = keyguardManager?.isKeyguardLocked ?: false,
            canDrawOverlays = NotificationDiagnosticHelper.isOverlayPermissionGranted(context)
        )
    }

    /**
     * 仅取消自动签到探测阶段的高优悬浮横幅（不误伤后续结果通知）
     */
    fun cancelAutoSignDetectedBanner(context: Context, rollcallId: Long) {
        if (rollcallId <= 0) return
        RollcallOverlayAlert.dismissBannerIfMatching(rollcallId)
        val notifId = ((rollcallId % 100000L).toInt() + NOTIF_OFFSET_AUTO_DETECTED)
        try {
            NotificationManagerCompat.from(context).cancel(notifId)
        } catch (_: Exception) {}
    }

    /**
     * 取消指定课堂签到关联的全部系统通知与悬浮窗（实现处理完毕后即时消隐）
     */
    fun cancelRollcallNotification(context: Context, rollcallId: Long) {
        if (rollcallId <= 0) return
        RollcallOverlayAlert.dismissIfMatching(rollcallId)
        val baseId = (rollcallId % 100000L).toInt()
        val notifManager = NotificationManagerCompat.from(context)
        val idsToCancel = intArrayOf(
            baseId + NOTIF_OFFSET_AUTO_DETECTED,
            baseId + 20000,
            baseId + 30000,
            baseId + 35000,
            baseId + 40000,
            baseId + 45000,
            baseId + 50000,
            baseId + 60000,
            baseId + 70000,
            baseId + 75000
        )
        for (id in idsToCancel) {
            try {
                notifManager.cancel(id)
            } catch (_: Exception) {}
        }
    }

    private fun buildManualRollcallIntent(
        context: Context,
        rollcallId: Long,
        courseName: String?,
        rollcallType: String,
        classroom: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        scope: Double? = null
    ): Intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_TAB, 8)
        when (rollcallType) {
            "radar" -> {
                putExtra("extra_open_radar_rollcall", true)
                putExtra("extra_rollcall_id", rollcallId)
                putExtra("extra_course_name", courseName)
                putExtra("extra_classroom", classroom)
                if (latitude != null) putExtra("extra_latitude", latitude)
                if (longitude != null) putExtra("extra_longitude", longitude)
                if (scope != null) putExtra("extra_scope", scope)
            }
            "number" -> {
                putExtra("extra_open_number_rollcall", true)
                putExtra("extra_rollcall_id", rollcallId)
                putExtra("extra_course_name", courseName)
            }
            "qr_code" -> {
                putExtra("extra_open_qr_scan", true)
                putExtra("extra_rollcall_id", rollcallId)
                putExtra("extra_course_name", courseName)
            }
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
     * 开发者自动签到模式：探测到签到的第一时间发送轻量化悬浮横幅提醒（不抢焦点、不弹窗）
     */
    fun notifyAutoSignDetectedBanner(
        context: Context,
        rollcallId: Long,
        courseName: String?,
        rollcallType: String,
        typeDisplayName: String,
        waitHint: String,
        classroom: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        scope: Double? = null,
        shouldShowPopup: () -> Boolean = { true }
    ) {
        createChannels(context)

        val notifId = ((rollcallId % 100000L).toInt() + NOTIF_OFFSET_AUTO_DETECTED)
        val displayCourse = courseName?.takeIf { it.isNotBlank() } ?: "课堂"
        val intent = buildManualRollcallIntent(
            context = context,
            rollcallId = rollcallId,
            courseName = displayCourse,
            rollcallType = rollcallType,
            classroom = classroom,
            latitude = latitude,
            longitude = longitude,
            scope = scope
        )
        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        val title = "检测到签到 · 即将自动提交"
        val summary = "【$displayCourse】$typeDisplayName · 将在 $waitHint 尝试自动签到"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "课程：$displayCourse\n" +
                        "类型：$typeDisplayName\n" +
                        "将在 $waitHint 尝试自动提交；如自动签到未成功将为您回退手动提醒，也可点击“手动签到”提前处理。"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(defaultSound)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        dispatchAlertNotificationWithPopup(
            context = context,
            notifId = notifId,
            rollcallId = rollcallId,
            courseName = displayCourse,
            typeDisplayName = typeDisplayName,
            builder = builder,
            actionPendingIntent = pendingIntent,
            actionLabel = "手动签到",
            alertIntent = null,
            fullScreenPendingIntent = null,
            bannerTitle = title,
            bannerSubtitle = summary,
            bannerActionIntent = intent,
            bannerTone = RollcallOverlayAlert.BannerTone.INFO,
            stage = RollcallAlertStage.AUTO_SIGN_DETECTED,
            shouldShowPopup = shouldShowPopup
        )
    }

    /**
     * 诊断页专用：模拟测试顶部悬浮横幅提醒（蓝/红横幅）
     */
    fun sendTestRollcallBanner(
        context: Context,
        tone: RollcallOverlayAlert.BannerTone = RollcallOverlayAlert.BannerTone.INFO
    ) {
        createChannels(context)
        val testRollcallId = 99998L
        cancelRollcallNotification(context, testRollcallId)

        val notifId = ((testRollcallId % 100000L).toInt() + NOTIF_OFFSET_AUTO_DETECTED)
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

        val isWarning = tone == RollcallOverlayAlert.BannerTone.WARNING
        val title = if (isWarning) "自动签到未成功，请手动签到" else "检测到签到 · 即将自动提交"
        val summary = if (isWarning) {
            "【模拟测试】高等数学（上）· 失败回退高优红色悬浮横幅，点击立即手动签到"
        } else {
            "【模拟测试】高等数学（上）· 雷达定位签到 · 将在 10秒后 尝试自动提交"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setPriority(if (isWarning) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (isWarning) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(defaultSound)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        dispatchAlertNotificationWithPopup(
            context = context,
            notifId = notifId,
            rollcallId = testRollcallId,
            courseName = "【模拟测试】高等数学（上）",
            typeDisplayName = "悬浮横幅测试",
            builder = builder,
            actionPendingIntent = pendingIntent,
            actionLabel = if (isWarning) "立即签到" else "手动签到",
            alertIntent = null,
            fullScreenPendingIntent = null,
            bannerTitle = title,
            bannerSubtitle = summary,
            bannerActionIntent = intent,
            bannerTone = tone,
            stage = if (isWarning) RollcallAlertStage.AUTO_SIGN_FAILED_FALLBACK else RollcallAlertStage.AUTO_SIGN_DETECTED,
            overrideMode = RollcallAlertMode.BANNER_ONLY,
            shouldShowPopup = { true }
        )
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
        scope: Double? = null,
        stage: RollcallAlertStage = if (success) RollcallAlertStage.AUTO_SIGN_SUCCEEDED else RollcallAlertStage.MANUAL_DETECTED,
        shouldShowPopup: () -> Boolean = { true }
    ) {
        createChannels(context)

        val notifId = ((rollcallId % 100000L).toInt() + 20000)

        val intent = if (!success) {
            buildManualRollcallIntent(
                context = context,
                rollcallId = rollcallId,
                courseName = courseName,
                rollcallType = "radar",
                classroom = classroom,
                latitude = latitude,
                longitude = longitude,
                scope = scope
            )
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_TAB, 8)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        val bName = building?.let { "${it.name} (${it.campus})" } ?: "匹配基准点位"

        if (success) {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("雷达点名已自动签到")
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

            dispatchAlertNotificationWithPopup(
                context = context,
                notifId = notifId,
                rollcallId = rollcallId,
                courseName = courseName,
                typeDisplayName = "雷达定位签到",
                builder = builder,
                actionPendingIntent = pendingIntent,
                actionLabel = "查看详情",
                alertIntent = null,
                fullScreenPendingIntent = null,
                bannerTitle = "雷达点名已自动签到",
                bannerSubtitle = "【${courseName ?: "课堂"}】已自动匹配 [$bName] 并签到成功",
                bannerActionIntent = intent,
                bannerTone = RollcallOverlayAlert.BannerTone.SUCCESS,
                stage = RollcallAlertStage.AUTO_SIGN_SUCCEEDED,
                shouldShowPopup = shouldShowPopup
            )
            return
        }

        val failTitle = if (stage == RollcallAlertStage.AUTO_SIGN_FAILED_FALLBACK) {
            "雷达自动签到未成功，请手动确认"
        } else {
            "雷达自动签到需手动确认"
        }
        val failText = "【${courseName ?: "课堂"}】尝试匹配 [$bName] 未成功 ($message)，点击手动选择点位"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(failTitle)
            .setContentText(failText)
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
        dispatchAlertNotificationWithPopup(
            context = context,
            notifId = notifId,
            rollcallId = rollcallId,
            courseName = courseName,
            typeDisplayName = "雷达定位签到",
            builder = builder,
            actionPendingIntent = pendingIntent,
            actionLabel = "选择点位签到",
            alertIntent = alertIntent,
            fullScreenPendingIntent = fullScreenPendingIntent,
            bannerTitle = failTitle,
            bannerSubtitle = failText,
            bannerActionIntent = intent,
            bannerTone = RollcallOverlayAlert.BannerTone.WARNING,
            stage = stage,
            shouldShowPopup = shouldShowPopup
        )
    }

    /**
     * 数字口令签到通知（高优先级全屏/横幅提醒，点击直接打开九宫格数字输入弹窗）
     */
    fun notifyNumberRollcall(
        context: Context,
        rollcallId: Long,
        courseName: String?,
        stage: RollcallAlertStage = RollcallAlertStage.MANUAL_DETECTED,
        overrideMode: RollcallAlertMode? = null,
        shouldShowPopup: () -> Boolean = { true }
    ) {
        createChannels(context)

        val notifId = ((rollcallId % 100000L).toInt() + 30000)

        val intent = buildManualRollcallIntent(
            context = context,
            rollcallId = rollcallId,
            courseName = courseName,
            rollcallType = "number"
        )

        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        val title = if (stage == RollcallAlertStage.AUTO_SIGN_FAILED_FALLBACK) {
            "数字口令自动获取未成功，请手动输入"
        } else {
            "收到数字签到通知"
        }
        val contentText = "【${courseName ?: "课堂"}】教师已发起数字口令签到，点击输入4位签到码"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
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
        dispatchAlertNotificationWithPopup(
            context = context,
            notifId = notifId,
            rollcallId = rollcallId,
            courseName = courseName,
            typeDisplayName = "数字口令签到",
            builder = builder,
            actionPendingIntent = pendingIntent,
            actionLabel = "输入签到码",
            alertIntent = alertIntent,
            fullScreenPendingIntent = fullScreenPendingIntent,
            bannerTitle = title,
            bannerSubtitle = contentText,
            bannerActionIntent = intent,
            bannerTone = RollcallOverlayAlert.BannerTone.WARNING,
            stage = stage,
            overrideMode = overrideMode,
            shouldShowPopup = shouldShowPopup
        )
    }

    /**
     * 数字口令自动签到结果通知（后台自动从服务端获取到签到码并提交后）
     */
    fun notifyNumberAutoResult(
        context: Context,
        rollcallId: Long,
        courseName: String?,
        success: Boolean,
        message: String?,
        shouldShowPopup: () -> Boolean = { true }
    ) {
        createChannels(context)

        val notifId = ((rollcallId % 100000L).toInt() + 35000)

        val intent = if (!success) {
            buildManualRollcallIntent(
                context = context,
                rollcallId = rollcallId,
                courseName = courseName,
                rollcallType = "number"
            )
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_TAB, 8)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)

        if (success) {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("数字口令已自动签到")
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

            dispatchAlertNotificationWithPopup(
                context = context,
                notifId = notifId,
                rollcallId = rollcallId,
                courseName = courseName,
                typeDisplayName = "数字口令签到",
                builder = builder,
                actionPendingIntent = pendingIntent,
                actionLabel = "查看详情",
                alertIntent = null,
                fullScreenPendingIntent = null,
                bannerTitle = "数字口令已自动签到",
                bannerSubtitle = "【${courseName ?: "课堂"}】$message",
                bannerActionIntent = intent,
                bannerTone = RollcallOverlayAlert.BannerTone.SUCCESS,
                stage = RollcallAlertStage.AUTO_SIGN_SUCCEEDED,
                shouldShowPopup = shouldShowPopup
            )
            return
        }

        val failTitle = "数字口令自动签到未成功"
        val failText = "【${courseName ?: "课堂"}】自动签到未成功 (${message ?: "请手动确认"})，点击手动输入口令"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(failTitle)
            .setContentText(failText)
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
        dispatchAlertNotificationWithPopup(
            context = context,
            notifId = notifId,
            rollcallId = rollcallId,
            courseName = courseName,
            typeDisplayName = "数字口令签到",
            builder = builder,
            actionPendingIntent = pendingIntent,
            actionLabel = "输入签到码",
            alertIntent = alertIntent,
            fullScreenPendingIntent = fullScreenPendingIntent,
            bannerTitle = failTitle,
            bannerSubtitle = failText,
            bannerActionIntent = intent,
            bannerTone = RollcallOverlayAlert.BannerTone.WARNING,
            stage = RollcallAlertStage.AUTO_SIGN_FAILED_FALLBACK,
            shouldShowPopup = shouldShowPopup
        )
    }

    /**
     * 二维码扫码签到通知
     */
    fun notifyQrRollcall(
        context: Context,
        rollcallId: Long,
        courseName: String?,
        stage: RollcallAlertStage = RollcallAlertStage.MANUAL_DETECTED,
        overrideMode: RollcallAlertMode? = null,
        shouldShowPopup: () -> Boolean = { true }
    ) {
        createChannels(context)

        val notifId = ((rollcallId % 100000L).toInt() + 40000)

        val intent = buildManualRollcallIntent(
            context = context,
            rollcallId = rollcallId,
            courseName = courseName,
            rollcallType = "qr_code"
        )

        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
        val title = if (stage == RollcallAlertStage.AUTO_SIGN_FAILED_FALLBACK) {
            "大屏自动扫码未成功，请手动扫码"
        } else {
            "收到扫码签到通知"
        }
        val contentText = "【${courseName ?: "课堂"}】教师已发起二维码签到，点击打开扫码器"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
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
        dispatchAlertNotificationWithPopup(
            context = context,
            notifId = notifId,
            rollcallId = rollcallId,
            courseName = courseName,
            typeDisplayName = "二维码签到",
            builder = builder,
            actionPendingIntent = pendingIntent,
            actionLabel = "打开扫码相机",
            alertIntent = alertIntent,
            fullScreenPendingIntent = fullScreenPendingIntent,
            bannerTitle = title,
            bannerSubtitle = contentText,
            bannerActionIntent = intent,
            bannerTone = RollcallOverlayAlert.BannerTone.WARNING,
            stage = stage,
            overrideMode = overrideMode,
            shouldShowPopup = shouldShowPopup
        )
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
        message: String?,
        shouldShowPopup: () -> Boolean = { true }
    ) {
        createChannels(context)

        val notifId = ((rollcallId % 100000L).toInt() + 45000)

        val intent = if (!success) {
            buildManualRollcallIntent(
                context = context,
                rollcallId = rollcallId,
                courseName = courseName,
                rollcallType = "qr_code"
            )
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_TAB, 8)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)

        if (success) {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("大屏二维码已自动签到")
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

            dispatchAlertNotificationWithPopup(
                context = context,
                notifId = notifId,
                rollcallId = rollcallId,
                courseName = courseName,
                typeDisplayName = "二维码签到",
                builder = builder,
                actionPendingIntent = pendingIntent,
                actionLabel = "查看详情",
                alertIntent = null,
                fullScreenPendingIntent = null,
                bannerTitle = "大屏二维码已自动签到",
                bannerSubtitle = "【${courseName ?: "课堂"}】已通过 [$viewName] 自动完成扫码",
                bannerActionIntent = intent,
                bannerTone = RollcallOverlayAlert.BannerTone.SUCCESS,
                stage = RollcallAlertStage.AUTO_SIGN_SUCCEEDED,
                shouldShowPopup = shouldShowPopup
            )
            return
        }

        val failTitle = "收到扫码签到 (大屏未检测到)"
        val failText = "【${courseName ?: "课堂"}】教师已发起二维码签到，点击打开扫码器"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(failTitle)
            .setContentText(failText)
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
        dispatchAlertNotificationWithPopup(
            context = context,
            notifId = notifId,
            rollcallId = rollcallId,
            courseName = courseName,
            typeDisplayName = "二维码签到",
            builder = builder,
            actionPendingIntent = pendingIntent,
            actionLabel = "手动扫码",
            alertIntent = alertIntent,
            fullScreenPendingIntent = fullScreenPendingIntent,
            bannerTitle = failTitle,
            bannerSubtitle = failText,
            bannerActionIntent = intent,
            bannerTone = RollcallOverlayAlert.BannerTone.WARNING,
            stage = RollcallAlertStage.AUTO_SIGN_FAILED_FALLBACK,
            shouldShowPopup = shouldShowPopup
        )
    }

    /**
     * 其他类型签到通知
     */
    fun notifyGenericRollcall(
        context: Context,
        rollcallId: Long,
        courseName: String?,
        typeDesc: String,
        stage: RollcallAlertStage = RollcallAlertStage.MANUAL_DETECTED,
        shouldShowPopup: () -> Boolean = { true }
    ) {
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
        val title = "收到课堂签到通知"
        val contentText = "【${courseName ?: "课堂"}】正在进行 $typeDesc，点击进入处理"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
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
            type = RollcallAlertActivity.TYPE_GENERIC
        )
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            notifId + 500,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        dispatchAlertNotificationWithPopup(
            context = context,
            notifId = notifId,
            rollcallId = rollcallId,
            courseName = courseName,
            typeDisplayName = typeDesc,
            builder = builder,
            actionPendingIntent = pendingIntent,
            actionLabel = "前往处理",
            alertIntent = alertIntent,
            fullScreenPendingIntent = fullScreenPendingIntent,
            bannerTitle = title,
            bannerSubtitle = contentText,
            bannerActionIntent = intent,
            bannerTone = RollcallOverlayAlert.BannerTone.WARNING,
            stage = stage,
            shouldShowPopup = shouldShowPopup
        )
    }

    /**
     * 自主签到结果通知
     */
    fun notifySelfRegistrationResult(
        context: Context,
        rollcallId: Long,
        courseName: String?,
        success: Boolean,
        message: String,
        shouldShowPopup: () -> Boolean = { true }
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

        val defaultSound = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)

        if (success) {
            val content = "【${courseName ?: "课堂"}】已全自动完成自主点名确认"
            val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("自主签到成功")
                .setContentText(content)
                .setStyle(NotificationCompat.BigTextStyle().bigText(content))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setSound(defaultSound)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)

            dispatchAlertNotificationWithPopup(
                context = context,
                notifId = notifId,
                rollcallId = rollcallId,
                courseName = courseName,
                typeDisplayName = "自主签到",
                builder = builder,
                actionPendingIntent = pendingIntent,
                actionLabel = "查看详情",
                alertIntent = null,
                fullScreenPendingIntent = null,
                bannerTitle = "自主签到成功",
                bannerSubtitle = content,
                bannerActionIntent = intent,
                bannerTone = RollcallOverlayAlert.BannerTone.SUCCESS,
                stage = RollcallAlertStage.AUTO_SIGN_SUCCEEDED,
                shouldShowPopup = shouldShowPopup
            )
            return
        }

        val failTitle = "自主签到未成功，请手动确认"
        val failContent = "【${courseName ?: "课堂"}】自主签到未能确认: $message"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(failTitle)
            .setContentText(failContent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(failContent))
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
            type = RollcallAlertActivity.TYPE_GENERIC
        )
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            notifId + 500,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        dispatchAlertNotificationWithPopup(
            context = context,
            notifId = notifId,
            rollcallId = rollcallId,
            courseName = courseName,
            typeDisplayName = "自主签到",
            builder = builder,
            actionPendingIntent = pendingIntent,
            actionLabel = "手动签到",
            alertIntent = alertIntent,
            fullScreenPendingIntent = fullScreenPendingIntent,
            bannerTitle = failTitle,
            bannerSubtitle = failContent,
            bannerActionIntent = intent,
            bannerTone = RollcallOverlayAlert.BannerTone.WARNING,
            stage = RollcallAlertStage.AUTO_SIGN_FAILED_FALLBACK,
            shouldShowPopup = shouldShowPopup
        )
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
        val title = if (success) "超星位置签到已自动完成" else "超星位置签到需手动确认"
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
        val tone = if (success) RollcallOverlayAlert.BannerTone.SUCCESS else RollcallOverlayAlert.BannerTone.WARNING
        applyAlertStyling(context, builder, tone, notifId, pendingIntent, if (!success) "前往签到" else "查看详情")

        try {
            NotificationManagerCompat.from(context).notify(notifId, builder.build())
        } catch (_: SecurityException) {
        }
    }

    /**
     * 超星学习通活跃签到探测通知（复用锁屏全屏弹窗 + 桌面悬浮窗 + 高优横幅兜底路由）
     */
    fun notifyChaoXingActiveSign(
        context: Context,
        courseId: String,
        classId: String,
        activeId: Long,
        courseName: String?,
        signTypeName: String?,
        otherId: Int = -1,
        shouldShowPopup: () -> Boolean = { true }
    ) {
        createChannels(context)

        val notifId = ((activeId % 100000L).toInt()).coerceAtLeast(1) + 75000
        val displayType = signTypeName?.takeIf { it.isNotBlank() } ?: "课堂签到"
        val displayCourse = courseName?.takeIf { it.isNotBlank() } ?: "当前课堂"

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TAB, 8)
            putExtra("extra_open_chaoxing_sign", true)
            putExtra("extra_cx_course_id", courseId)
            putExtra("extra_cx_class_id", classId)
            putExtra("extra_cx_active_id", activeId)
            putExtra("extra_cx_course_name", displayCourse)
            putExtra("extra_cx_sign_type", displayType)
            putExtra("extra_cx_other_id", otherId)
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
            .setContentTitle("收到学习通$displayType")
            .setContentText("【$displayCourse】正在进行 $displayType，点击立即前往完成")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "课程：$displayCourse\n" +
                        "签到类型：$displayType\n" +
                        "已为您探测到学习通课堂签到活动，点击立即打开对应签到页面。"
                )
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(defaultSound)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val alertIntent = RollcallAlertActivity.createChaoXingIntent(
            context = context,
            activeId = activeId,
            courseId = courseId,
            classId = classId,
            courseName = displayCourse,
            signTypeName = displayType,
            otherId = otherId
        )
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            notifId + 500,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        dispatchAlertNotificationWithPopup(
            context = context,
            notifId = notifId,
            rollcallId = activeId,
            courseName = displayCourse,
            typeDisplayName = "学习通 · $displayType",
            builder = builder,
            actionPendingIntent = pendingIntent,
            actionLabel = "前往签到",
            alertIntent = alertIntent,
            fullScreenPendingIntent = fullScreenPendingIntent,
            bannerTitle = "收到学习通$displayType",
            bannerSubtitle = "【$displayCourse】正在进行 $displayType，点击立即前往完成",
            bannerActionIntent = intent,
            bannerTone = RollcallOverlayAlert.BannerTone.WARNING,
            stage = RollcallAlertStage.MANUAL_DETECTED,
            shouldShowPopup = shouldShowPopup
        )
    }

    private fun dispatchAlertNotificationWithPopup(
        context: Context,
        notifId: Int,
        rollcallId: Long,
        courseName: String?,
        typeDisplayName: String,
        builder: NotificationCompat.Builder,
        actionPendingIntent: PendingIntent?,
        actionLabel: String,
        alertIntent: Intent?,
        fullScreenPendingIntent: PendingIntent? = null,
        bannerTitle: String,
        bannerSubtitle: String,
        bannerActionIntent: Intent? = alertIntent,
        bannerTone: RollcallOverlayAlert.BannerTone = RollcallOverlayAlert.BannerTone.WARNING,
        stage: RollcallAlertStage = RollcallAlertStage.MANUAL_DETECTED,
        overrideMode: RollcallAlertMode? = null,
        shouldShowPopup: () -> Boolean = { true }
    ) {
        val effectiveMode = resolveEffectiveMode(context, stage, overrideMode)
        val route = resolvePopupRouteForMode(context, effectiveMode)

        fun postNotification(silent: Boolean, hasFullScreenIntent: Boolean = false) {
            applyAlertStyling(
                context = context,
                builder = builder,
                tone = bannerTone,
                notifId = notifId,
                actionPendingIntent = actionPendingIntent,
                actionLabel = actionLabel,
                showActionButton = bannerTone != RollcallOverlayAlert.BannerTone.SUCCESS,
                silent = silent,
                hasFullScreenIntent = hasFullScreenIntent
            )
            try {
                NotificationManagerCompat.from(context).notify(notifId, builder.build())
            } catch (_: SecurityException) {
            }
        }

        when (route) {
            RollcallPopupRoute.FOREGROUND_ACTIVITY -> {
                if (!shouldShowPopup()) return
                val launched = if (alertIntent != null) {
                    try {
                        context.startActivity(alertIntent)
                        true
                    } catch (e: Exception) {
                        Log.d("RollcallNotifHelper", "Foreground startActivity failed: ${e.message}")
                        false
                    }
                } else {
                    false
                }
                postNotification(silent = launched)
            }
            RollcallPopupRoute.BACKGROUND_OVERLAY -> {
                if (alertIntent != null) {
                    RollcallOverlayAlert.show(
                        context = context,
                        rollcallId = rollcallId,
                        courseName = courseName,
                        typeName = typeDisplayName,
                        actionIntent = alertIntent,
                        shouldShow = shouldShowPopup,
                        vibrate = false,
                        onShown = { postNotification(silent = false, hasFullScreenIntent = false) },
                        onUnavailable = { postNotification(silent = false, hasFullScreenIntent = false) }
                    )
                } else {
                    postNotification(silent = false, hasFullScreenIntent = false)
                }
            }
            RollcallPopupRoute.TOP_BANNER_OVERLAY -> {
                RollcallOverlayAlert.showTopBanner(
                    context = context,
                    rollcallId = rollcallId,
                    title = bannerTitle,
                    subtitle = bannerSubtitle,
                    tone = bannerTone,
                    actionLabel = if (bannerActionIntent != null && bannerTone != RollcallOverlayAlert.BannerTone.SUCCESS) actionLabel else null,
                    actionIntent = bannerActionIntent,
                    shouldShow = shouldShowPopup,
                    vibrate = true,
                    onShown = { postNotification(silent = true, hasFullScreenIntent = false) },
                    onUnavailable = { postNotification(silent = false, hasFullScreenIntent = false) }
                )
            }
            RollcallPopupRoute.NOTIFICATION_ONLY -> {
                val attachFullScreen = effectiveMode == RollcallAlertMode.POPUP && fullScreenPendingIntent != null
                if (attachFullScreen) {
                    builder.setFullScreenIntent(fullScreenPendingIntent, true)
                }
                postNotification(silent = false, hasFullScreenIntent = attachFullScreen)
                if (attachFullScreen) {
                    val appContext = context.applicationContext
                    Handler(Looper.getMainLooper()).postDelayed({
                        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                        if (pm != null && !pm.isInteractive) {
                            NotificationDiagnosticHelper.wakeScreenBriefly(appContext, 5000L)
                        }
                    }, 650L)
                }
            }
        }
    }

    private fun applyAlertStyling(
        context: Context,
        builder: NotificationCompat.Builder,
        tone: RollcallOverlayAlert.BannerTone,
        notifId: Int,
        actionPendingIntent: PendingIntent? = null,
        actionLabel: String = "立即处理",
        showActionButton: Boolean = tone != RollcallOverlayAlert.BannerTone.SUCCESS,
        silent: Boolean = false,
        hasFullScreenIntent: Boolean = false
    ) {
        if (tone == RollcallOverlayAlert.BannerTone.WARNING && shouldWakeScreenBeforeNotify(silent, hasFullScreenIntent)) {
            NotificationDiagnosticHelper.wakeScreenBriefly(context, 5000L)
        }
        val alertColor = when (tone) {
            RollcallOverlayAlert.BannerTone.WARNING -> Color.parseColor("#E53935") // 鲜艳警示红
            RollcallOverlayAlert.BannerTone.INFO -> Color.parseColor("#1E88E5") // 探测进行中蓝
            RollcallOverlayAlert.BannerTone.SUCCESS -> Color.parseColor("#2E7D32") // 绿色成功
        }
        val iconRes = if (tone == RollcallOverlayAlert.BannerTone.SUCCESS) R.drawable.ic_check_circle else R.drawable.ic_radar
        val largeIcon = NotificationDiagnosticHelper.createCircleIconBitmap(context, iconRes, alertColor)

        builder.setColor(alertColor)
            .setColorized(true)
            .apply {
                if (silent) {
                    setSilent(true)
                    setPriority(NotificationCompat.PRIORITY_DEFAULT)
                } else {
                    val isWarning = tone == RollcallOverlayAlert.BannerTone.WARNING
                    setPriority(if (isWarning) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
                    val vibratePattern = when (tone) {
                        RollcallOverlayAlert.BannerTone.WARNING -> longArrayOf(0, 400, 120, 400, 120, 400)
                        RollcallOverlayAlert.BannerTone.INFO -> longArrayOf(0, 140)
                        RollcallOverlayAlert.BannerTone.SUCCESS -> longArrayOf(0, 250, 100, 250)
                    }
                    setVibrate(vibratePattern)
                }
                if (largeIcon != null) setLargeIcon(largeIcon)
            }

        if (actionPendingIntent != null && showActionButton) {
            builder.addAction(iconRes, actionLabel, actionPendingIntent)
        }
        val dismissPendingIntent = NotificationUiHelper.createDismissPendingIntent(context, notifId)
        builder.addAction(R.drawable.ic_check_circle, "我知道了", dismissPendingIntent)
    }
}
