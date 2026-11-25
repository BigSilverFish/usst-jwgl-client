package cn.edu.usst.jwgl.util

import androidx.fragment.app.FragmentActivity
import cn.edu.usst.jwgl.ui.common.NotificationDiagnosticBottomSheet
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Non-blocking rollcall permission guidance shown when enabling background monitoring.
 * Refusing or deferring permissions never disables rollcall monitoring.
 */
object RollcallPermissionGuide {

    private const val DIAGNOSTIC_TAG = "NotificationDiagnostic"

    fun guideIfNeeded(
        activity: FragmentActivity,
        onBeforeOpenDiagnostic: (() -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val snapshot = NotificationDiagnosticHelper.inspectRollcallPermissions(activity)
        if (!snapshot.needsGuidance) return

        val uiName = NotificationDiagnosticHelper.getManufacturerFriendlyName().second
        val message = buildString {
            append("监听已开启。完善以下权限可显著提高后台弹窗与通知到达率：\n\n")
            append("• 通知总开关：${if (snapshot.notificationsEnabled) "已允许" else "未允许"}\n")
            append("• 签到通知渠道：${if (snapshot.alertChannelHigh) "已开启高重要性" else "未达高重要性或被屏蔽"}\n")
            append("• 悬浮窗权限：${if (snapshot.overlayGranted) "已允许" else "未允许"}\n")
            append("• 忽略电池优化：${if (snapshot.ignoringBatteryOptimizations) "已允许" else "未允许"}\n")
            append("• 锁屏全屏弹窗：${if (snapshot.fullScreenIntentGranted) "已允许" else "未允许"}\n\n")
            append("当前系统（$uiName）还需在权限管理中开启【后台弹出界面】和【锁屏显示】以保障息屏与后台弹出。\n")
            append("选择“稍后”仍会保持后台监听，并按当前可用方式提醒。")
        }

        runCatching {
            MaterialAlertDialogBuilder(activity)
                .setTitle("完善签到提醒授权")
                .setMessage(message)
                .setPositiveButton("前往系统诊断") { _, _ ->
                    onBeforeOpenDiagnostic?.invoke()
                    val fm = activity.supportFragmentManager
                    if (!activity.isFinishing &&
                        !activity.isDestroyed &&
                        !fm.isStateSaved &&
                        fm.findFragmentByTag(DIAGNOSTIC_TAG) == null
                    ) {
                        NotificationDiagnosticBottomSheet
                            .newInstance(NotificationDiagnosticHelper.CHANNEL_ROLLCALL_ALERT)
                            .show(fm, DIAGNOSTIC_TAG)
                    }
                }
                .setNegativeButton("稍后", null)
                .show()
        }
    }
}
