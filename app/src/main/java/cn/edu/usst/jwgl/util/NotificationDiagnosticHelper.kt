package cn.edu.usst.jwgl.util

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.ui.MainActivity

/**
 * 通知与灵动胶囊增强助手 & 系统权限诊断工具
 *
 * 负责：
 * 1. 统一管理全 App 关键高优先级通知渠道 (v2)
 * 2. 诊断通知、悬浮横幅 (Heads-up)、锁屏显示、电池优化白名单、自启动等状态
 * 3. 适配小米 HyperOS、OPPO 流体云、vivo 原子通知等定制 UI 的类灵动岛胶囊通知规范
 * 4. 息屏唤醒与即时测试通知
 */
object NotificationDiagnosticHelper {

    private const val TAG = "NotifDiagnosticHelper"

    // 升级版渠道 ID，彻底覆盖老旧低优先级缓存
    const val CHANNEL_COURSE = "course_reminder_channel_v2"
    const val CHANNEL_EXAM = "exam_reminder_channel_v2"
    const val CHANNEL_DDL = "tronclass_ddl_channel_v2"
    const val CHANNEL_ROLLCALL_ALERT = "tronclass_rollcall_alert_v2"
    const val CHANNEL_ROLLCALL_SERVICE = "tronclass_rollcall_service"

    const val TEST_NOTIFICATION_ID = 99901

    /**
     * 统一创建并注册所有高优通知渠道 (Android 8.0+)
     */
    fun initNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val vibrationPatternCourse = longArrayOf(0, 350, 180, 350)
        val vibrationPatternExam = longArrayOf(0, 450, 150, 450, 150, 450)
        val vibrationPatternDdl = longArrayOf(0, 300, 150, 300)
        val vibrationPatternRollcall = longArrayOf(0, 400, 120, 400, 120, 400)

        // 1. 上课前提醒渠道
        val channelCourse = NotificationChannel(
            CHANNEL_COURSE,
            "上课前提醒 (灵动胶囊/横幅)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "上课前根据预设提前提醒教室、教师及倒计时，支持状态栏胶囊与锁屏显示"
            enableVibration(true)
            vibrationPattern = vibrationPatternCourse
            setSound(defaultSound, audioAttributes)
            enableLights(true)
            lightColor = 0xFF1976D2.toInt()
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }

        // 2. 考试日程提醒渠道
        val channelExam = NotificationChannel(
            CHANNEL_EXAM,
            "考试日程提醒 (强提醒/横幅)",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "期末与阶段考试前提醒考场、座号与时间，高优先级强震动"
            enableVibration(true)
            vibrationPattern = vibrationPatternExam
            setSound(defaultSound, audioAttributes)
            enableLights(true)
            lightColor = 0xFFD32F2F.toInt()
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }

        // 3. 一网畅学作业与测验截止预警
        val channelDdl = NotificationChannel(
            CHANNEL_DDL,
            "作业与测验截止预警",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "一网畅学作业或测验临近截止时发出倒计时提醒"
            enableVibration(true)
            vibrationPattern = vibrationPatternDdl
            setSound(defaultSound, audioAttributes)
            enableLights(true)
            lightColor = 0xFFF57C00.toInt()
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }

        // 4. 课堂点名与签到提醒
        val channelRollcallAlert = NotificationChannel(
            CHANNEL_ROLLCALL_ALERT,
            "课堂签到提醒与通知",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "检测到课堂点名或自动签到状态时的即时横幅与胶囊提醒"
            enableVibration(true)
            vibrationPattern = vibrationPatternRollcall
            setSound(defaultSound, audioAttributes)
            enableLights(true)
            lightColor = 0xFF388E3C.toInt()
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setShowBadge(true)
        }

        // 5. 课堂签到后台守护服务渠道 (静默，维持常驻)
        val channelRollcallService = NotificationChannel(
            CHANNEL_ROLLCALL_SERVICE,
            "课堂签到后台守护",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "上课期间在后台监测签到的守护进程通道"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }

        nm.createNotificationChannels(
            listOf(channelCourse, channelExam, channelDdl, channelRollcallAlert, channelRollcallService)
        )
    }

    /**
     * 息屏唤醒：在屏幕关闭时唤醒点亮屏幕数秒，避免放在桌面上无感知
     */
    fun wakeScreenBriefly(context: Context, durationMs: Long = 5000L) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                val wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "usst_jwgl:ScreenWakeLock"
                )
                wakeLock.acquire(durationMs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "wakeScreenBriefly failed: ${e.message}")
        }
    }

    /**
     * 将 Drawable 转为用于通知的大位图
     */
    fun drawableToBitmap(context: Context, drawableResId: Int, sizeDp: Int = 48): Bitmap? {
        return try {
            val drawable: Drawable = ContextCompat.getDrawable(context, drawableResId) ?: return null
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                return drawable.bitmap
            }
            val density = context.resources.displayMetrics.density
            val sizePx = (sizeDp * density).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 创建一个带背景色圆形的彩色图标 Bitmap
     */
    fun createCircleIconBitmap(context: Context, iconResId: Int, bgColor: Int, sizeDp: Int = 56): Bitmap? {
        return try {
            val density = context.resources.displayMetrics.density
            val sizePx = (sizeDp * density).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // 绘制圆形背景
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bgColor
                style = Paint.Style.FILL
            }
            val radius = sizePx / 2f
            canvas.drawCircle(radius, radius, radius, paint)

            // 绘制居中白色图标
            val drawable: Drawable = ContextCompat.getDrawable(context, iconResId) ?: return bitmap
            drawable.setTint(android.graphics.Color.WHITE)
            val padding = (sizePx * 0.22f).toInt()
            drawable.setBounds(padding, padding, sizePx - padding, sizePx - padding)
            drawable.draw(canvas)

            bitmap
        } catch (_: Exception) {
            null
        }
    }

    // ==========================================
    // 权限诊断部分
    // ==========================================

    /**
     * 检查通知总开关是否开启
     */
    fun areNotificationsEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    data class RollcallPermissionSnapshot(
        val notificationsEnabled: Boolean,
        val alertChannelHigh: Boolean,
        val overlayGranted: Boolean,
        val ignoringBatteryOptimizations: Boolean,
        val fullScreenIntentGranted: Boolean = true
    ) {
        val needsGuidance: Boolean
            get() = !notificationsEnabled || !alertChannelHigh || !overlayGranted || !ignoringBatteryOptimizations || !fullScreenIntentGranted

        fun missingItems(): List<String> = buildList {
            if (!notificationsEnabled) add("通知总开关")
            if (!alertChannelHigh) add("签到通知渠道（高重要性/横幅）")
            if (!overlayGranted) add("悬浮窗权限（后台居中弹窗）")
            if (!ignoringBatteryOptimizations) add("忽略电池优化（防止息屏中断）")
            if (!fullScreenIntentGranted) add("锁屏全屏弹窗权限（Android 14+ 全屏通知）")
        }
    }

    internal fun evaluateChannelAvailability(
        channelImportance: Int?,
        minImportance: Int,
        isGroupBlocked: Boolean
    ): Boolean = channelImportance != null && channelImportance >= minImportance && !isGroupBlocked

    private fun isChannelGroupBlocked(nm: NotificationManager, channel: NotificationChannel): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val groupId = channel.group ?: return false
        return runCatching {
            nm.getNotificationChannelGroup(groupId)?.isBlocked == true
        }.getOrDefault(false)
    }

    /**
     * 检查某个通知渠道是否有效且被开启（含 Android 9+ 渠道组屏蔽检测）
     */
    fun isChannelEnabled(context: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return areNotificationsEnabled(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        val channel = nm.getNotificationChannel(channelId) ?: return false
        return evaluateChannelAvailability(
            channelImportance = channel.importance,
            minImportance = NotificationManager.IMPORTANCE_MIN,
            isGroupBlocked = isChannelGroupBlocked(nm, channel)
        )
    }

    /**
     * 检查渠道是否允许横幅浮动通知 (IMPORTANCE_HIGH 及以上且未被渠道组屏蔽)
     */
    fun isChannelHeadsUpAllowed(context: Context, channelId: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        val channel = nm.getNotificationChannel(channelId) ?: return false
        return evaluateChannelAvailability(
            channelImportance = channel.importance,
            minImportance = NotificationManager.IMPORTANCE_HIGH,
            isGroupBlocked = isChannelGroupBlocked(nm, channel)
        )
    }

    fun inspectRollcallPermissions(context: Context): RollcallPermissionSnapshot {
        runCatching { initNotificationChannels(context) }
        return RollcallPermissionSnapshot(
            notificationsEnabled = areNotificationsEnabled(context),
            alertChannelHigh = isChannelHeadsUpAllowed(context, CHANNEL_ROLLCALL_ALERT),
            overlayGranted = isOverlayPermissionGranted(context),
            ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context),
            fullScreenIntentGranted = canUseFullScreenIntent(context)
        )
    }

    /**
     * 是否已忽略电池优化 (加入电池白名单)
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } else {
            true
        }
    }

    /**
     * 是否允许精确闹钟调度 (Android 12+)
     */
    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            am?.canScheduleExactAlarms() ?: true
        } else {
            true
        }
    }

    /**
     * 是否拥有显示在其他应用上层（悬浮窗/桌面直接强弹窗）权限
     */
    fun isOverlayPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 是否允许锁屏全屏弹窗通知 (Android 14 / API 34+ 需显式授权)
     */
    fun canUseFullScreenIntent(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.canUseFullScreenIntent() ?: true
        } else {
            true
        }
    }

    /**
     * 系统定制 ROM 类型枚举
     */
    enum class RomType {
        HYPEROS,        // 小米澎湃 OS (Xiaomi HyperOS)
        MIUI,           // 小米 MIUI
        HARMONYOS,      // 华为鸿蒙 HarmonyOS
        EMUI,           // 华为 EMUI
        MAGICOS,        // 荣耀 MagicOS
        COLOROS,        // OPPO / 一加 / Realme ColorOS
        ORIGINOS,       // vivo / iQOO OriginOS
        FLYME,          // 魅族 Flyme
        ONE_UI,         // 三星 One UI
        AOSP_CUSTOM,    // 类原生系统 (LineageOS, PixelOS, crDroid, EvolutionX 等)
        STOCK_ANDROID   // 谷歌 Pixel / 原生 Android
    }

    /**
     * 获取当前设备的品牌/厂商类型
     */
    enum class Manufacturer {
        XIAOMI,      // 小米 / 红米 (HyperOS / MIUI)
        HUAWEI,      // 华为 (HarmonyOS / EMUI)
        HONOR,       // 荣耀 (MagicOS)
        OPPO,        // OPPO / 一加 / Realme (ColorOS)
        VIVO,        // vivo / iQOO (OriginOS / FuntouchOS)
        MEIZU,       // 魅族 (Flyme)
        SAMSUNG,     // 三星 (One UI)
        AOSP,        // 类原生系统 (LineageOS, PixelOS 等)
        OTHER
    }

    /**
     * 反射读取系统属性 (System Properties)
     */
    fun getSystemProperty(key: String, defaultValue: String = ""): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java, String::class.java)
            getMethod.invoke(null, key, defaultValue) as? String ?: defaultValue
        } catch (_: Exception) {
            defaultValue
        }
    }

    fun getFirstNonEmptyProperty(vararg keys: String): String {
        for (key in keys) {
            val value = getSystemProperty(key).trim()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    /**
     * 识别类原生系统发行版名称（如 LineageOS, crDroid 等）
     */
    fun detectCustomRomName(): String {
        val lineage = getSystemProperty("ro.lineage.version")
        if (lineage.isNotBlank()) return "LineageOS ($lineage)"
        val crdroid = getSystemProperty("ro.crdroid.version")
        if (crdroid.isNotBlank()) return "crDroid ($crdroid)"
        val pixel = getFirstNonEmptyProperty("ro.pixel.build.version", "ro.pixelexperience.version")
        if (pixel.isNotBlank()) return "PixelExperience / PixelOS"
        val evo = getSystemProperty("ro.evolution.version")
        if (evo.isNotBlank()) return "Evolution X ($evo)"
        val pa = getSystemProperty("ro.pa.version")
        if (pa.isNotBlank()) return "Paranoid Android"
        val mod = getSystemProperty("ro.modversion")
        if (mod.isNotBlank()) return mod
        return "类原生 Android (AOSP)"
    }

    /**
     * 精准探测当前运行的 ROM 类型（通过底层系统属性而非单纯依赖硬件厂商）
     */
    fun detectRomType(): RomType {
        // 1. 小米 HyperOS / MIUI
        val hyperOsVer = getFirstNonEmptyProperty("ro.mi.os.version.name")
        if (hyperOsVer.isNotEmpty()) return RomType.HYPEROS
        val miuiVer = getFirstNonEmptyProperty("ro.miui.ui.version.name", "ro.miui.version.code_time")
        if (miuiVer.isNotEmpty()) return RomType.MIUI

        // 2. 华为 HarmonyOS / EMUI
        val harmonyVer = getFirstNonEmptyProperty("hw_sc.build.platform.version")
        if (harmonyVer.isNotEmpty()) return RomType.HARMONYOS
        val emuiVer = getFirstNonEmptyProperty("ro.build.version.emui")
        if (emuiVer.isNotEmpty()) return RomType.EMUI

        // 3. 荣耀 MagicOS
        val magicVer = getFirstNonEmptyProperty("ro.build.version.magic", "msc.config.magic.version")
        if (magicVer.isNotEmpty()) return RomType.MAGICOS

        // 4. OPPO / 一加 / Realme (ColorOS)
        val colorVer = getFirstNonEmptyProperty("ro.build.version.opporom", "ro.build.version.oplusrom", "ro.coloros.version")
        if (colorVer.isNotEmpty()) return RomType.COLOROS

        // 5. vivo / iQOO (OriginOS / FuntouchOS)
        val vivoVer = getFirstNonEmptyProperty("ro.vivo.os.version", "ro.vivo.os.name")
        if (vivoVer.isNotEmpty()) return RomType.ORIGINOS

        // 6. 魅族 Flyme
        val flymeVer = getFirstNonEmptyProperty("ro.flyme.version.id", "ro.build.display.id")
        if (flymeVer.lowercase().contains("flyme")) return RomType.FLYME

        // 7. 三星 One UI
        val oneUiVer = getFirstNonEmptyProperty("ro.build.version.oneui")
        val brand = Build.BRAND.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()
        if (oneUiVer.isNotEmpty() || brand.contains("samsung") || manufacturer.contains("samsung")) {
            return RomType.ONE_UI
        }

        // 8. 类原生特征检测
        val customRomProps = listOf(
            "ro.lineage.version",
            "ro.lineage.build.version",
            "ro.crdroid.version",
            "ro.pixel.build.version",
            "ro.pixelexperience.version",
            "ro.evolution.version",
            "ro.pa.version",
            "ro.arrow.version",
            "ro.havoc.version",
            "ro.bliss.version",
            "ro.modversion",
            "ro.custom.build.version"
        )
        for (prop in customRomProps) {
            if (getSystemProperty(prop).isNotBlank()) {
                return RomType.AOSP_CUSTOM
            }
        }

        // 9. 如果底层硬件是国内厂商，但没有任何定制系统Property，说明刷了类原生！
        if (brand.contains("xiaomi") || brand.contains("redmi") ||
            brand.contains("huawei") || brand.contains("honor") ||
            brand.contains("oppo") || brand.contains("oneplus") || brand.contains("realme") ||
            brand.contains("vivo") || brand.contains("iqoo") ||
            brand.contains("meizu")) {
            return RomType.AOSP_CUSTOM
        }

        return RomType.STOCK_ANDROID
    }

    fun getDeviceManufacturer(): Manufacturer {
        return when (detectRomType()) {
            RomType.HYPEROS, RomType.MIUI -> Manufacturer.XIAOMI
            RomType.HARMONYOS, RomType.EMUI -> Manufacturer.HUAWEI
            RomType.MAGICOS -> Manufacturer.HONOR
            RomType.COLOROS -> Manufacturer.OPPO
            RomType.ORIGINOS -> Manufacturer.VIVO
            RomType.FLYME -> Manufacturer.MEIZU
            RomType.ONE_UI -> Manufacturer.SAMSUNG
            RomType.AOSP_CUSTOM -> Manufacturer.AOSP
            RomType.STOCK_ANDROID -> Manufacturer.OTHER
        }
    }

    /**
     * 获取厂商对应的名称和定制 UI 名称
     */
    fun getManufacturerFriendlyName(): Pair<String, String> {
        val brand = Build.BRAND.replaceFirstChar { it.uppercase() }
        val romType = detectRomType()

        return when (romType) {
            RomType.HYPEROS -> {
                val ver = getSystemProperty("ro.mi.os.version.name")
                val detail = if (ver.isNotBlank()) "Xiaomi HyperOS ($ver)" else "Xiaomi HyperOS (澎湃 OS)"
                "小米 / 红米" to detail
            }
            RomType.MIUI -> {
                val ver = getSystemProperty("ro.miui.ui.version.name")
                val detail = if (ver.isNotBlank()) "MIUI $ver" else "Xiaomi MIUI"
                "小米 / 红米" to detail
            }
            RomType.HARMONYOS -> {
                val ver = getSystemProperty("hw_sc.build.platform.version")
                val detail = if (ver.isNotBlank()) "HarmonyOS $ver" else "Huawei HarmonyOS (鸿蒙)"
                "华为" to detail
            }
            RomType.EMUI -> "华为" to "Huawei EMUI"
            RomType.MAGICOS -> "荣耀" to "Honor MagicOS (灵动胶囊)"
            RomType.COLOROS -> "OPPO / 一加" to "ColorOS (流体云)"
            RomType.ORIGINOS -> "vivo / iQOO" to "OriginOS (原子通知)"
            RomType.FLYME -> "魅族" to "Flyme (灵动灵感)"
            RomType.ONE_UI -> "三星" to "One UI"
            RomType.AOSP_CUSTOM -> {
                val customName = detectCustomRomName()
                val hardwareTag = if (brand.isNotBlank() && !brand.equals("Unknown", ignoreCase = true)) " ($brand 硬件)" else ""
                "类原生系统$hardwareTag" to (customName.ifBlank { "类原生 Android (AOSP)" })
            }
            RomType.STOCK_ANDROID -> {
                "$brand 设备" to "原生 Android"
            }
        }
    }

    // ==========================================
    // 跳转系统设置 Intent 助手
    // ==========================================

    /**
     * 打开应用全局通知设置页
     */
    fun openAppNotificationSettings(context: Context) {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            } else {
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppDetailsSettings(context)
        }
    }

    /**
     * 打开特定通知渠道的设置页（可配置横幅、锁屏显示、声音、震动）
     */
    fun openChannelSettings(context: Context, channelId: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        openAppNotificationSettings(context)
    }

    /**
     * 申请加入电池优化白名单 (忽略耗电限制，保证熄屏准时唤醒)
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                try {
                    val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    context.startActivity(fallback)
                    return
                } catch (_: Exception) {}
            }
        }
        openAppDetailsSettings(context)
    }

    /**
     * 打开悬浮窗/显示在其他应用上层设置
     */
    fun openOverlayPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        openAppDetailsSettings(context)
    }

    /**
     * 打开锁屏全屏通知权限设置页 (Android 14+)
     */
    fun openFullScreenIntentSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        openAppNotificationSettings(context)
    }

    /**
     * 打开精确闹钟设置页 (Android 12+)
     */
    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        openAppDetailsSettings(context)
    }

    /**
     * 打开应用详情设置
     */
    fun openAppDetailsSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    /**
     * 尝试跳转各大厂商的自启动/后台管家页面
     */
    fun openManufacturerAutoStartSettings(context: Context): Boolean {
        val manufacturer = getDeviceManufacturer()
        if (manufacturer == Manufacturer.AOSP || manufacturer == Manufacturer.OTHER) {
            requestIgnoreBatteryOptimizations(context)
            return true
        }

        val intents = mutableListOf<Intent>()
        val pkg = context.packageName

        when (manufacturer) {
            Manufacturer.XIAOMI -> {
                // 小米自启动管理页
                intents.add(Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")))
                intents.add(Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT))
                intents.add(Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")).putExtra("package_name", pkg).putExtra("package_label", "掌上理工"))
            }
            Manufacturer.HUAWEI, Manufacturer.HONOR -> {
                // 华为 / 荣耀 启动管理
                intents.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
                intents.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity")))
                intents.add(Intent().setComponent(ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")))
            }
            Manufacturer.OPPO -> {
                // OPPO / 一加 自启动与权限
                intents.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")))
                intents.add(Intent().setComponent(ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")))
                intents.add(Intent().setComponent(ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")))
            }
            Manufacturer.VIVO -> {
                // vivo 自启动与后台高耗电
                intents.add(Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")))
                intents.add(Intent().setComponent(ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")))
                intents.add(Intent().setComponent(ComponentName("com.iqoo.secure", "com.iqoo.secure.MainActivity")))
            }
            Manufacturer.MEIZU -> {
                intents.add(Intent().setComponent(ComponentName("com.meizu.safe", "com.meizu.safe.security.SHOW_APPSEC")).putExtra("packageName", pkg))
            }
            else -> {}
        }

        // 尝试按序启动
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }

        // 降级：打开常规应用详情
        openAppDetailsSettings(context)
        return false
    }

    /**
     * 尝试跳转国产定制 ROM 的私有「后台弹出界面 / 锁屏显示」权限设置页
     */
    fun openManufacturerPopupPermissionSettings(context: Context): Boolean {
        val manufacturer = getDeviceManufacturer()
        val pkg = context.packageName
        val intents = mutableListOf<Intent>()

        when (manufacturer) {
            Manufacturer.XIAOMI -> {
                intents.add(Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", pkg))
                intents.add(
                    Intent().setComponent(
                        ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    ).putExtra("extra_pkgname", pkg)
                )
            }
            Manufacturer.VIVO -> {
                intents.add(
                    Intent().setComponent(
                        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.SoftPermissionDetailActivity")
                    ).putExtra("packagename", pkg)
                )
            }
            Manufacturer.OPPO -> {
                intents.add(
                    Intent().setComponent(
                        ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.floatwindow.FloatWindowListActivity")
                    )
                )
                intents.add(
                    Intent().setComponent(
                        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.floatwindow.FloatWindowListActivity")
                    )
                )
            }
            Manufacturer.HUAWEI, Manufacturer.HONOR -> {
                intents.add(
                    Intent().setComponent(
                        ComponentName("com.huawei.systemmanager", "com.huawei.permissionmanager.ui.MainActivity")
                    )
                )
            }
            else -> {}
        }

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {}
        }

        openAppDetailsSettings(context)
        return false
    }

    // ==========================================
    // 发送灵动胶囊测试通知
    // ==========================================

    /**
     * 发送一条全特性模拟的“灵动胶囊 / 焦点通知”测试通知
     * 具备：倒计时 Chronometer、醒目色彩、大图标、操作按钮、横幅悬浮
     */
    fun sendDiagnosticTestNotification(context: Context) {
        initNotificationChannels(context)

        // 熄屏唤醒一下测试
        wakeScreenBriefly(context, 4000L)

        val clickIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("extra_tab", 1) // 课表
        }
        val pendingClick = PendingIntent.getActivity(
            context,
            TEST_NOTIFICATION_ID,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 5分钟倒计时模拟
        val targetTimeMillis = System.currentTimeMillis() + 15 * 60 * 1000L

        // 生成高亮大图标
        val largeIcon = createCircleIconBitmap(
            context,
            R.drawable.ic_school,
            0xFF1976D2.toInt()
        )

        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val builder = NotificationCompat.Builder(context, CHANNEL_COURSE)
            .setSmallIcon(R.drawable.ic_school)
            .setContentTitle("🔔 [测试] 还有 15 分钟上课：数字图像处理")
            .setContentText("一教 302教室 · 张老师 (13:30 开始)")
            .setSubText("上课提醒") // HyperOS / ColorOS 胶囊收缩态标题
            .setLargeIcon(largeIcon)
            .setColor(ContextCompat.getColor(context, R.color.primary))
            .setColorized(true)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "课程：数字图像处理A\n考场/教室：第一教学楼 302多媒体教室\n时间：13:30 - 15:05 (第5-6节)\n状态：状态栏胶囊与横幅浮动测试正常运行中！"
                )
            )
            // 胶囊与实时活动核心属性：
            .setOngoing(false) // 测试通知允许滑动消除
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(targetTimeMillis)
            .setShowWhen(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSound(defaultSound)
            .setVibrate(longArrayOf(0, 350, 180, 350))
            .setAutoCancel(true)
            .setContentIntent(pendingClick)
            // 添加快捷操作按钮（撑大卡片并展示交互）
            .addAction(R.drawable.ic_location, "查看教室路线", pendingClick)
            .addAction(R.drawable.ic_check_circle, "我知道了", pendingClick)

        try {
            val nm = NotificationManagerCompat.from(context)
            nm.notify(TEST_NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.e(TAG, "Notification permission missing: ${e.message}")
        }
    }
}
