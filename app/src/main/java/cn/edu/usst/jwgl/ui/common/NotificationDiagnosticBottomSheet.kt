package cn.edu.usst.jwgl.ui.common

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.util.NotificationDiagnosticHelper
import cn.edu.usst.jwgl.util.TabletLayoutHelper

class NotificationDiagnosticBottomSheet : BottomSheetDialogFragment() {

    private lateinit var tvDeviceBrandTitle: TextView
    private lateinit var tvDeviceSpecificTips: TextView
    private lateinit var btnOpenManufacturerSettings: MaterialButton

    private lateinit var tvStatusNotifTotal: TextView
    private lateinit var btnSetNotifTotal: MaterialButton

    private lateinit var tvStatusHeadsUp: TextView
    private lateinit var btnSetHeadsUp: MaterialButton

    private lateinit var tvStatusBattery: TextView
    private lateinit var btnSetBattery: MaterialButton

    private lateinit var tvStatusExactAlarm: TextView
    private lateinit var btnSetExactAlarm: MaterialButton

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

        tvStatusNotifTotal = v.findViewById(R.id.tvStatusNotifTotal)
        btnSetNotifTotal = v.findViewById(R.id.btnSetNotifTotal)

        tvStatusHeadsUp = v.findViewById(R.id.tvStatusHeadsUp)
        btnSetHeadsUp = v.findViewById(R.id.btnSetHeadsUp)

        tvStatusBattery = v.findViewById(R.id.tvStatusBattery)
        btnSetBattery = v.findViewById(R.id.btnSetBattery)

        tvStatusExactAlarm = v.findViewById(R.id.tvStatusExactAlarm)
        btnSetExactAlarm = v.findViewById(R.id.btnSetExactAlarm)

        btnSendTestNotification = v.findViewById(R.id.btnSendTestNotification)
        btnRefreshDiagnostic = v.findViewById(R.id.btnRefreshDiagnostic)
        btnCloseDiagnostic = v.findViewById(R.id.btnCloseDiagnostic)
    }

    private fun setupDeviceSpecificInfo() {
        val (brand, uiName) = NotificationDiagnosticHelper.getManufacturerFriendlyName()
        tvDeviceBrandTitle.text = "检测机型：$brand · $uiName"

        val tips = when (NotificationDiagnosticHelper.getDeviceManufacturer()) {
            NotificationDiagnosticHelper.Manufacturer.XIAOMI ->
                "小米 HyperOS / MIUI：请在应用权限中开启【自启动】与【后台无限制】。并在通知管理中将「悬浮通知」和「锁屏通知」打开，即可在上课前触发状态栏焦点胶囊。"
            NotificationDiagnosticHelper.Manufacturer.HUAWEI ->
                "华为 HarmonyOS：请进入设置 -> 应用和服务 -> 应用启动管理，将掌上理工改为【手动管理】，允许自启动、关联启动和后台活动。"
            NotificationDiagnosticHelper.Manufacturer.HONOR ->
                "荣耀 MagicOS：请在权限管理中允许自启动与悬浮通知，系统将在状态栏胶囊与 YOYO 建议中展示上课及考试提醒。"
            NotificationDiagnosticHelper.Manufacturer.OPPO ->
                "OPPO / 一加 (ColorOS)：请进入权限与隐私 -> 自启动管理，允许掌上理工自启动，并在通知管理中勾选「横幅通知」以激活流体云交互。"
            NotificationDiagnosticHelper.Manufacturer.VIVO ->
                "vivo / iQOO (OriginOS)：请进入电池管理将掌上理工设为【允许高耗电后台运行】，并在应用权限中开启自启动以保障原子通知正常弹出。"
            NotificationDiagnosticHelper.Manufacturer.MEIZU ->
                "魅族 Flyme：请在手机管家 -> 权限管理中开启自启动，并将掌上理工加入后台保活白名单。"
            NotificationDiagnosticHelper.Manufacturer.SAMSUNG ->
                "三星 One UI：请进入电池和设备维护 -> 电池 -> 后台使用限制，将掌上理工添加到【从不休眠的应用程序】中。"
            NotificationDiagnosticHelper.Manufacturer.OTHER ->
                "请确保开启横幅悬浮通知、锁屏通知，并允许后台无限制电池策略，以保证提醒 100% 准时送达。"
        }
        tvDeviceSpecificTips.text = tips
    }

    private fun setupListeners() {
        btnCloseDiagnostic.setOnClickListener {
            dismiss()
        }

        btnRefreshDiagnostic.setOnClickListener {
            runDiagnostic()
            Toast.makeText(requireContext(), "诊断已刷新", Toast.LENGTH_SHORT).show()
        }

        btnOpenManufacturerSettings.setOnClickListener {
            NotificationDiagnosticHelper.openManufacturerAutoStartSettings(requireContext())
        }

        btnSetNotifTotal.setOnClickListener {
            NotificationDiagnosticHelper.openAppNotificationSettings(requireContext())
        }

        btnSetHeadsUp.setOnClickListener {
            NotificationDiagnosticHelper.openChannelSettings(
                requireContext(),
                NotificationDiagnosticHelper.CHANNEL_COURSE
            )
        }

        btnSetBattery.setOnClickListener {
            NotificationDiagnosticHelper.requestIgnoreBatteryOptimizations(requireContext())
        }

        btnSetExactAlarm.setOnClickListener {
            NotificationDiagnosticHelper.openExactAlarmSettings(requireContext())
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
            NotificationDiagnosticHelper.CHANNEL_COURSE
        )
        if (headsUpAllowed) {
            tvStatusHeadsUp.text = "✅ 允许横幅悬浮与状态栏胶囊"
            tvStatusHeadsUp.setTextColor(Color.parseColor("#2E7D32"))
            btnSetHeadsUp.text = "查看渠道"
        } else {
            tvStatusHeadsUp.text = "⚠️ 当前渠道可能被静默，建议点击开启「悬浮通知」"
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
    }
}
