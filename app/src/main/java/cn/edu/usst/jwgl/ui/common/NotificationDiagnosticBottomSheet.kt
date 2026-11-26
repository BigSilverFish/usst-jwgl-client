package cn.edu.usst.jwgl.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.ui.tronclass.RollcallAlertActivity
import cn.edu.usst.jwgl.util.DeveloperModeManager
import cn.edu.usst.jwgl.util.NotificationDiagnosticHelper
import cn.edu.usst.jwgl.util.RollcallAlertMode
import cn.edu.usst.jwgl.util.RollcallNotificationHelper
import cn.edu.usst.jwgl.util.RollcallOverlayAlert
import cn.edu.usst.jwgl.util.TabletLayoutHelper

class NotificationDiagnosticBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_TARGET_CHANNEL_ID = "arg_target_channel_id"

        fun newInstance(
            targetChannelId: String = NotificationDiagnosticHelper.CHANNEL_COURSE
        ): NotificationDiagnosticBottomSheet = NotificationDiagnosticBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_TARGET_CHANNEL_ID, targetChannelId)
            }
        }
    }

    private val targetChannelId: String
        get() = arguments?.getString(ARG_TARGET_CHANNEL_ID) ?: NotificationDiagnosticHelper.CHANNEL_COURSE

    private lateinit var tvDeviceBrandTitle: TextView
    private lateinit var tvDeviceSpecificTips: TextView
    private lateinit var btnOpenManufacturerSettings: MaterialButton
    private lateinit var btnOpenManufacturerPopupSettings: MaterialButton

    private lateinit var tvStatusNotifTotal: TextView
    private lateinit var btnSetNotifTotal: MaterialButton

    private lateinit var tvStatusHeadsUp: TextView
    private lateinit var btnSetHeadsUp: MaterialButton

    private lateinit var tvStatusBattery: TextView
    private lateinit var btnSetBattery: MaterialButton

    private lateinit var tvStatusExactAlarm: TextView
    private lateinit var btnSetExactAlarm: MaterialButton

    private lateinit var tvStatusOverlay: TextView
    private lateinit var btnSetOverlay: MaterialButton

    private lateinit var tvStatusFullScreenIntent: TextView
    private lateinit var btnSetFullScreenIntent: MaterialButton

    private lateinit var btnTestRollcallAlert: MaterialButton
    private lateinit var btnTestRollcallBanner: MaterialButton
    private lateinit var btnSendTestNotification: MaterialButton
    private lateinit var btnRefreshDiagnostic: ImageButton
    private lateinit var btnCloseDiagnostic: ImageButton

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_notification_diagnostic, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews(view)
        setupDeviceSpecificInfo()
        setupListeners()
    }

    override fun onStart() {
        super.onStart()
        TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, requireContext())
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时自动重新体检
        runDiagnostic()
    }

    private fun initViews(v: View) {
        tvDeviceBrandTitle = v.findViewById(R.id.tvDeviceBrandTitle)
        tvDeviceSpecificTips = v.findViewById(R.id.tvDeviceSpecificTips)
        btnOpenManufacturerSettings = v.findViewById(R.id.btnOpenManufacturerSettings)
        btnOpenManufacturerPopupSettings = v.findViewById(R.id.btnOpenManufacturerPopupSettings)

        tvStatusNotifTotal = v.findViewById(R.id.tvStatusNotifTotal)
        btnSetNotifTotal = v.findViewById(R.id.btnSetNotifTotal)

        tvStatusHeadsUp = v.findViewById(R.id.tvStatusHeadsUp)
        btnSetHeadsUp = v.findViewById(R.id.btnSetHeadsUp)

        tvStatusBattery = v.findViewById(R.id.tvStatusBattery)
        btnSetBattery = v.findViewById(R.id.btnSetBattery)

        tvStatusExactAlarm = v.findViewById(R.id.tvStatusExactAlarm)
        btnSetExactAlarm = v.findViewById(R.id.btnSetExactAlarm)

        tvStatusOverlay = v.findViewById(R.id.tvStatusOverlay)
        btnSetOverlay = v.findViewById(R.id.btnSetOverlay)

        tvStatusFullScreenIntent = v.findViewById(R.id.tvStatusFullScreenIntent)
        btnSetFullScreenIntent = v.findViewById(R.id.btnSetFullScreenIntent)

        btnTestRollcallAlert = v.findViewById(R.id.btnTestRollcallAlert)
        btnTestRollcallBanner = v.findViewById(R.id.btnTestRollcallBanner)
        btnSendTestNotification = v.findViewById(R.id.btnSendTestNotification)
        btnRefreshDiagnostic = v.findViewById(R.id.btnRefreshDiagnostic)
        btnCloseDiagnostic = v.findViewById(R.id.btnCloseDiagnostic)
    }

    private fun setupDeviceSpecificInfo() {
        val (brand, uiName) = NotificationDiagnosticHelper.getManufacturerFriendlyName()
        tvDeviceBrandTitle.text = "检测机型：$brand · $uiName"

        val tips = when (NotificationDiagnosticHelper.getDeviceManufacturer()) {
            NotificationDiagnosticHelper.Manufacturer.XIAOMI ->
                "小米 HyperOS / MIUI：请在应用权限中开启【自启动】、【后台无限制】、【后台弹出界面】与【锁屏显示】，并在通知管理中开启「悬浮通知」和「锁屏通知」。"
            NotificationDiagnosticHelper.Manufacturer.HUAWEI ->
                "华为 HarmonyOS：请在应用启动管理中将掌上理工改为【手动管理】（允许自启动/关联启动/后台活动），并在权限管理中允许【悬浮窗】与【后台弹窗】。"
            NotificationDiagnosticHelper.Manufacturer.HONOR ->
                "荣耀 MagicOS：请在权限管理中允许【自启动】、【后台弹窗】与【悬浮通知】，并开启锁屏显示以保障签到及时弹出。"
            NotificationDiagnosticHelper.Manufacturer.OPPO ->
                "OPPO / 一加 (ColorOS)：请在应用权限中开启【自启动】、【悬浮窗】及【后台弹出界面】，并在通知管理中勾选「横幅通知」与「锁屏通知」。"
            NotificationDiagnosticHelper.Manufacturer.VIVO ->
                "vivo / iQOO (OriginOS)：请在权限管理中同时开启【自启动】、【悬浮窗】、【后台弹出界面】与【锁屏显示】，并将电池策略设为「允许后台高耗电」。"
            NotificationDiagnosticHelper.Manufacturer.MEIZU ->
                "魅族 Flyme：请在手机管家 -> 权限管理中开启【自启动】、【悬浮窗】与【后台弹出界面】，并将掌上理工加入后台白名单。"
            NotificationDiagnosticHelper.Manufacturer.SAMSUNG ->
                "三星 One UI：请进入电池和设备维护 -> 电池 -> 后台使用限制，将掌上理工添加到【从不休眠的应用程序】中。"
            NotificationDiagnosticHelper.Manufacturer.AOSP ->
                "原生 Android / AOSP 系统：建议在系统设置中将电池优化设为【无限制】，并开启横幅悬浮、锁屏通知与全屏通知权限。"
            NotificationDiagnosticHelper.Manufacturer.OTHER ->
                "请确保开启横幅悬浮通知、锁屏通知、后台弹窗与无限制电池策略，以保证提醒准时送达。"
        }
        tvDeviceSpecificTips.text = tips

        val isAosp = NotificationDiagnosticHelper.getDeviceManufacturer() == NotificationDiagnosticHelper.Manufacturer.AOSP ||
                NotificationDiagnosticHelper.getDeviceManufacturer() == NotificationDiagnosticHelper.Manufacturer.OTHER
        btnOpenManufacturerSettings.text = if (isAosp) "配置原生电池优化策略" else "前往厂商后台与自启动管理"
        btnOpenManufacturerPopupSettings.visibility = if (isAosp) View.GONE else View.VISIBLE
    }

    private fun setupListeners() {
        btnCloseDiagnostic.setOnClickListener {
            dismiss()
        }

        btnRefreshDiagnostic.setOnClickListener {
            runDiagnostic()
        }

        btnOpenManufacturerSettings.setOnClickListener {
            NotificationDiagnosticHelper.openManufacturerAutoStartSettings(requireContext())
        }

        btnOpenManufacturerPopupSettings.setOnClickListener {
            NotificationDiagnosticHelper.openManufacturerPopupPermissionSettings(requireContext())
        }

        btnSetNotifTotal.setOnClickListener {
            NotificationDiagnosticHelper.openAppNotificationSettings(requireContext())
        }

        btnSetHeadsUp.setOnClickListener {
            NotificationDiagnosticHelper.openChannelSettings(
                requireContext(),
                targetChannelId
            )
        }

        btnSetBattery.setOnClickListener {
            NotificationDiagnosticHelper.requestIgnoreBatteryOptimizations(requireContext())
        }

        btnSetExactAlarm.setOnClickListener {
            NotificationDiagnosticHelper.openExactAlarmSettings(requireContext())
        }

        btnSetOverlay.setOnClickListener {
            NotificationDiagnosticHelper.openOverlayPermissionSettings(requireContext())
        }

        btnSetFullScreenIntent.setOnClickListener {
            NotificationDiagnosticHelper.openFullScreenIntentSettings(requireContext())
        }

        btnTestRollcallAlert.setOnClickListener {
            val options = arrayOf(
                "立即模拟弹窗（测试桌面居中提醒卡片）",
                "延迟 5 秒模拟（点此后请按电源键息屏，测试锁屏点亮弹窗）"
            )
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("模拟课堂签到弹窗测试")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> testRollcallAlert(0L)
                        1 -> testRollcallAlert(5000L)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        btnTestRollcallBanner.setOnClickListener {
            val isDevMode = DeveloperModeManager.isDeveloperMode(requireContext())
            val options = if (isDevMode) {
                arrayOf(
                    "立即模拟：自动签到探测中横幅（蓝色）",
                    "立即模拟：自动签到失败红色横幅",
                    "延迟 5 秒模拟（切回桌面或锁屏，测试后台悬浮横幅）"
                )
            } else {
                arrayOf(
                    "立即模拟：常规课堂提醒横幅（蓝色）",
                    "立即模拟：高优签到警示横幅（红色）",
                    "延迟 5 秒模拟（切回桌面或锁屏，测试后台悬浮横幅）"
                )
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("模拟课堂签到悬浮横幅测试")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> testRollcallBanner(0L, RollcallOverlayAlert.BannerTone.INFO)
                        1 -> testRollcallBanner(0L, RollcallOverlayAlert.BannerTone.WARNING)
                        2 -> testRollcallBanner(5000L, RollcallOverlayAlert.BannerTone.WARNING)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        btnSendTestNotification.setOnClickListener {
            NotificationDiagnosticHelper.sendDiagnosticTestNotification(requireContext())
            Toast.makeText(
                requireContext(),
                "已发送测试通知！请下拉通知栏或留意状态栏/挖孔处",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun testRollcallAlert(delayMs: Long) {
        val ctx = requireContext().applicationContext
        val alertIntent = RollcallAlertActivity.createIntent(
            context = ctx,
            rollcallId = 99999L,
            courseName = "【模拟测试】高等数学（上）",
            type = RollcallAlertActivity.TYPE_NUMBER,
            classroom = "第一教学楼 101"
        )

        if (delayMs <= 0L) {
            dismiss()
            try {
                ctx.startActivity(alertIntent)
            } catch (e: Exception) {
                RollcallNotificationHelper.notifyNumberRollcall(ctx, 99999L, "【模拟测试】高等数学（上）")
            }
        } else {
            dismiss()
            RollcallNotificationHelper.cancelRollcallNotification(ctx, 99999L)
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val delayWakeLock = runCatching {
                pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "usst_jwgl:RollcallTestDelayLock")?.apply {
                    setReferenceCounted(false)
                    acquire(delayMs + 3_000L)
                }
            }.getOrNull()
            Toast.makeText(
                ctx,
                "⏳ 倒计时 5 秒！请立即按下电源键关闭屏幕，测试锁屏直接唤醒弹窗",
                Toast.LENGTH_LONG
            ).show()

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    RollcallNotificationHelper.notifyNumberRollcall(
                        context = ctx,
                        rollcallId = 99999L,
                        courseName = "【模拟测试】高等数学（上）",
                        overrideMode = RollcallAlertMode.POPUP
                    )
                } finally {
                    runCatching {
                        if (delayWakeLock?.isHeld == true) delayWakeLock.release()
                    }
                }
            }, delayMs)
        }
    }

    private fun testRollcallBanner(delayMs: Long, tone: RollcallOverlayAlert.BannerTone) {
        val ctx = requireContext().applicationContext
        if (delayMs <= 0L) {
            dismiss()
            RollcallNotificationHelper.sendTestRollcallBanner(ctx, tone)
        } else {
            dismiss()
            RollcallNotificationHelper.cancelRollcallNotification(ctx, 99998L)
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val delayWakeLock = runCatching {
                pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "usst_jwgl:RollcallBannerTestDelayLock")?.apply {
                    setReferenceCounted(false)
                    acquire(delayMs + 3_000L)
                }
            }.getOrNull()
            Toast.makeText(
                ctx,
                "⏳ 倒计时 5 秒！请切回桌面或锁屏，测试后台悬浮横幅提醒",
                Toast.LENGTH_LONG
            ).show()

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    RollcallNotificationHelper.sendTestRollcallBanner(ctx, tone)
                } finally {
                    runCatching {
                        if (delayWakeLock?.isHeld == true) delayWakeLock.release()
                    }
                }
            }, delayMs)
        }
    }

    private fun runDiagnostic() {
        val context = context ?: return

        // 1. 系统通知总开关
        val notifEnabled = NotificationDiagnosticHelper.areNotificationsEnabled(context)
        if (notifEnabled) {
            tvStatusNotifTotal.text = "✅ 已开启通知权限"
            tvStatusNotifTotal.setTextColor(Color.parseColor("#2E7D32"))
            btnSetNotifTotal.text = "已正常"
            btnSetNotifTotal.isEnabled = false
        } else {
            tvStatusNotifTotal.text = "❌ 系统通知总开关已关闭，所有提醒将无法送达！"
            tvStatusNotifTotal.setTextColor(Color.parseColor("#C62828"))
            btnSetNotifTotal.text = "去开启"
            btnSetNotifTotal.isEnabled = true
        }

        // 2. 悬浮横幅与胶囊权限
        val headsUpAllowed = NotificationDiagnosticHelper.isChannelHeadsUpAllowed(
            context,
            targetChannelId
        )
        val isRollcallTarget = targetChannelId == NotificationDiagnosticHelper.CHANNEL_ROLLCALL_ALERT
        if (headsUpAllowed) {
            tvStatusHeadsUp.text = if (isRollcallTarget) {
                "✅ 签到通知渠道已开启横幅与高重要性"
            } else {
                "✅ 允许横幅悬浮与状态栏胶囊"
            }
            tvStatusHeadsUp.setTextColor(Color.parseColor("#2E7D32"))
            btnSetHeadsUp.text = "查看渠道"
        } else {
            tvStatusHeadsUp.text = if (isRollcallTarget) {
                "⚠️ 签到通知渠道未达高重要性或被渠道组屏蔽，请点击开启「悬浮通知」"
            } else {
                "⚠️ 当前渠道可能被静默或被渠道组屏蔽，建议点击开启「悬浮通知」"
            }
            tvStatusHeadsUp.setTextColor(Color.parseColor("#E65100"))
            btnSetHeadsUp.text = "去设置"
        }

        // 3. 电池优化白名单
        val ignoringBattery = NotificationDiagnosticHelper.isIgnoringBatteryOptimizations(context)
        if (ignoringBattery) {
            tvStatusBattery.text = "✅ 已加入电池无限制白名单 (灭屏不休眠)"
            tvStatusBattery.setTextColor(Color.parseColor("#2E7D32"))
            btnSetBattery.text = "已加白"
            btnSetBattery.isEnabled = false
        } else {
            tvStatusBattery.text = "⚠️ 处于智能省电限制下，手机息屏可能导致提醒延迟"
            tvStatusBattery.setTextColor(Color.parseColor("#E65100"))
            btnSetBattery.text = "加白名单"
            btnSetBattery.isEnabled = true
        }

        // 4. 精确闹钟权限
        val exactAlarm = NotificationDiagnosticHelper.canScheduleExactAlarms(context)
        if (exactAlarm) {
            tvStatusExactAlarm.text = "✅ 精确闹钟权限已就绪 (准时唤醒)"
            tvStatusExactAlarm.setTextColor(Color.parseColor("#2E7D32"))
            btnSetExactAlarm.text = "已就绪"
            btnSetExactAlarm.isEnabled = false
        } else {
            tvStatusExactAlarm.text = "⚠️ 缺少精确闹钟权限，上课/考试提醒可能产生误差"
            tvStatusExactAlarm.setTextColor(Color.parseColor("#E65100"))
            btnSetExactAlarm.text = "去授权"
            btnSetExactAlarm.isEnabled = true
        }

        // 5. 显示在其他应用上层 (桌面悬浮权限)
        val overlayGranted = NotificationDiagnosticHelper.isOverlayPermissionGranted(context)
        if (overlayGranted) {
            tvStatusOverlay.text = "✅ 允许显示在其他应用上层 (支持桌面居中弹出签到卡片)"
            tvStatusOverlay.setTextColor(Color.parseColor("#2E7D32"))
            btnSetOverlay.text = "已开启"
            btnSetOverlay.isEnabled = false
        } else {
            tvStatusOverlay.text = "⚠️ 未授权悬浮窗，亮屏使用时将通过顶部横幅提醒"
            tvStatusOverlay.setTextColor(Color.parseColor("#E65100"))
            btnSetOverlay.text = "去授权"
            btnSetOverlay.isEnabled = true
        }

        // 6. 锁屏全屏弹窗权限 (Android 14+)
        val fullScreenGranted = NotificationDiagnosticHelper.canUseFullScreenIntent(context)
        if (fullScreenGranted) {
            tvStatusFullScreenIntent.text = "✅ 允许锁屏全屏弹窗 (息屏可直接点亮弹出)"
            tvStatusFullScreenIntent.setTextColor(Color.parseColor("#2E7D32"))
            btnSetFullScreenIntent.text = "已开启"
            btnSetFullScreenIntent.isEnabled = false
        } else {
            tvStatusFullScreenIntent.text = "❌ 未授权「全屏通知」，Android 14+ 锁屏弹窗将被系统静默拦截！"
            tvStatusFullScreenIntent.setTextColor(Color.parseColor("#C62828"))
            btnSetFullScreenIntent.text = "去授权"
            btnSetFullScreenIntent.isEnabled = true
        }
    }
}
