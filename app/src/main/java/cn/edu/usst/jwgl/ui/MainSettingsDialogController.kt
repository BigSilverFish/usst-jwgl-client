package cn.edu.usst.jwgl.ui

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import android.util.Log
import cn.edu.usst.jwgl.BuildConfig
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.network.ChaoXingRequestGuard
import cn.edu.usst.jwgl.data.wakeup.CourseUtils
import cn.edu.usst.jwgl.util.TimetableSettingHelper
import cn.edu.usst.jwgl.worker.TronClassDdlScheduler
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.AppVersionInfo
import cn.edu.usst.jwgl.data.remote.RemoteConfigManager
import cn.edu.usst.jwgl.devaddon.DevAddonManager
import cn.edu.usst.jwgl.ui.common.AuthorDisclaimerBottomSheet
import cn.edu.usst.jwgl.util.CourseReminderManager
import cn.edu.usst.jwgl.util.DeveloperModeManager
import cn.edu.usst.jwgl.util.NotificationDiagnosticHelper
import cn.edu.usst.jwgl.util.RollcallAlertMode
import cn.edu.usst.jwgl.util.ThemeManager
import cn.edu.usst.jwgl.util.UpdateDialogHelper
import cn.edu.usst.jwgl.worker.RollcallMonitorScheduler
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * 主界面设置、提醒策略、自动签到策略与远程更新弹窗控制器
 */
internal class MainSettingsDialogController(
    private val activity: MainActivity
) {
    private val binding get() = activity.binding
    private val db get() = activity.db
    private var currentTable
        get() = activity.currentTable
        set(value) { activity.currentTable = value }
    private var currentWeek
        get() = activity.currentWeek
        set(value) { activity.currentWeek = value }
    private val scheduleAdapter get() = activity.scheduleAdapter

    fun showWeekPickerDialog() {
        val table = currentTable ?: return
        val realCurrentWeek = CourseUtils.countWeek(table.startDate)
        val maxWeek = table.maxWeek
        val options = Array(maxWeek) { i ->
            val w = i + 1
            if (w == realCurrentWeek) "第 $w 周 (本周)" else "第 $w 周"
        }
        val currentIdx = (currentWeek - 1).coerceIn(0, maxWeek - 1)

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle("切换周次 (共 ${maxWeek} 周)")
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                val targetWeek = which + 1
                currentWeek = targetWeek
                binding.vpSchedule.setCurrentItem(targetWeek - 1, true)
                activity.updateWeekSelectionUI(targetWeek)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)

        if (currentWeek != realCurrentWeek && realCurrentWeek in 1..maxWeek) {
            builder.setNeutralButton("回到本周") { dialog, _ ->
                currentWeek = realCurrentWeek
                binding.vpSchedule.setCurrentItem(realCurrentWeek - 1, true)
                activity.updateWeekSelectionUI(realCurrentWeek)
                dialog.dismiss()
            }
        }

        builder.safeShow()
    }

    fun showFontSizeDialog() {
        val table = currentTable ?: return
        val options = arrayOf(
            "紧凑 (48dp / 10sp)",
            "标准 (56dp / 11sp)",
            "宽松 (64dp / 12sp)",
            "大号 (72dp / 13sp)"
        )
        val configs = arrayOf(
            Pair(48, 10),
            Pair(56, 11),
            Pair(64, 12),
            Pair(72, 13)
        )

        var currentIdx = configs.indexOfFirst { it.first == table.itemHeight }
        if (currentIdx == -1) currentIdx = 1

        MaterialAlertDialogBuilder(activity)
            .setTitle("课表格子大小")
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                val (height, textSize) = configs[which]
                table.itemHeight = height
                table.itemTextSize = textSize
                db.tableDao.updateTable(table)
                scheduleAdapter?.refreshAllFragments()
                activity.notifyProfileSettingsChanged()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }


    fun showDarkModeDialog() {
        val options = arrayOf("跟随系统 (默认)", "浅色模式", "深色模式")
        val currentMode = ThemeManager.getThemeMode(activity)

        MaterialAlertDialogBuilder(activity)
            .setTitle("深色模式")
            .setSingleChoiceItems(options, currentMode) { dialog, which ->
                ThemeManager.setThemeMode(activity, which)
                activity.notifyProfileSettingsChanged()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    fun showPhoneNavModeDialog() {
        val options = arrayOf(
            "侧栏模式 (左滑/双击课表空白处呼出)",
            "底栏模式 (底部常驻四栏导航)"
        )
        val currentMode = TimetableSettingHelper.getPhoneNavMode(activity)

        MaterialAlertDialogBuilder(activity)
            .setTitle("导航栏模式 (手机)")
            .setSingleChoiceItems(options, currentMode) { dialog, which ->
                TimetableSettingHelper.setPhoneNavMode(activity, which)
                activity.applyPhoneNavigationMode()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    fun showRollcallAlertModeDialog() {
        val tcPrefs = TronClassPreferences.getInstance(activity)
        val currentMode = tcPrefs.getRollcallAlertMode()
        val isDevMode = DeveloperModeManager.isDeveloperMode(activity)
        val options = if (isDevMode) {
            arrayOf(
                "强弹窗提醒（默认）\n亮屏居中弹窗 + 锁屏唤醒；自动签到先发横幅，失败再弹窗",
                "仅悬浮横幅提醒\n顶部轻量浮条 / 系统横幅，不弹居中窗与锁屏全屏页；自动签到失败亦仅发高优红色横幅"
            )
        } else {
            arrayOf(
                "强弹窗提醒（默认）\n亮屏居中弹窗 + 锁屏唤醒，发现课堂点名立即弹出提醒",
                "仅悬浮横幅提醒\n顶部轻量浮条 / 系统横幅，不弹居中窗与锁屏全屏页"
            )
        }
        val checkedItem = if (currentMode == RollcallAlertMode.BANNER_ONLY) 1 else 0

        MaterialAlertDialogBuilder(activity)
            .setTitle("选择签到提醒方式")
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val newMode = if (which == 1) RollcallAlertMode.BANNER_ONLY else RollcallAlertMode.POPUP
                tcPrefs.setRollcallAlertMode(newMode)
                activity.notifyProfileSettingsChanged()
                if (!NotificationDiagnosticHelper.isOverlayPermissionGranted(activity)) {
                    Toast.makeText(
                        activity,
                        "建议前往「通知与弹窗运行诊断」开启悬浮窗权限，以获得最佳桌面提醒效果",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    fun showCourseReminderAdvanceDialog() {
        val options = arrayOf(
            "提前 5 分钟",
            "提前 10 分钟",
            "提前 15 分钟 (推荐)",
            "提前 20 分钟",
            "提前 30 分钟",
            "提前 45 分钟",
            "提前 1 小时"
        )
        val values = intArrayOf(5, 10, 15, 20, 30, 45, 60)
        val currentMins = CourseReminderManager.getCourseReminderAdvanceMinutes(activity)
        var selectedIdx = values.indexOf(currentMins).let { if (it >= 0) it else 2 }

        MaterialAlertDialogBuilder(activity)
            .setTitle("课前提醒时间")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                val chosenMins = values[which]
                CourseReminderManager.setCourseReminderAdvanceMinutes(activity, chosenMins)
                activity.notifyProfileSettingsChanged()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    fun showExamReminderAdvanceDialog() {
        val options = arrayOf(
            "提前 15 分钟",
            "提前 30 分钟 (推荐)",
            "提前 45 分钟",
            "提前 1 小时",
            "提前 2 小时",
            "提前 3 小时",
            "提前 1 天 (24小时)"
        )
        val values = intArrayOf(15, 30, 45, 60, 120, 180, 1440)
        val currentMins = CourseReminderManager.getExamReminderAdvanceMinutes(activity)
        var selectedIdx = values.indexOf(currentMins).let { if (it >= 0) it else 1 }

        MaterialAlertDialogBuilder(activity)
            .setTitle("考前提醒时间")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                val chosenMins = values[which]
                CourseReminderManager.setExamReminderAdvanceMinutes(activity, chosenMins)
                activity.notifyProfileSettingsChanged()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    fun showTronClassDdlUrgentAdvanceDialog() {
        val options = arrayOf(
            "提前 1 小时",
            "提前 2 小时 (推荐)",
            "提前 3 小时"
        )
        val values = intArrayOf(1, 2, 3)
        val tcPrefs = TronClassPreferences.getInstance(activity)
        val currentHours = tcPrefs.getDdlReminderUrgentHours()
        val selectedIdx = values.indexOf(currentHours).let { if (it >= 0) it else 1 }

        MaterialAlertDialogBuilder(activity)
            .setTitle("作业临期提醒时间")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                val chosenHours = values[which]
                tcPrefs.setDdlReminderUrgentHours(chosenHours)
                activity.notifyProfileSettingsChanged()
                TronClassDdlScheduler.scheduleFromPreferences(activity)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    fun showRollcallAutoSignDelayDialog() {
        DevAddonManager.getEntry(activity).autoSignExtension.showStrategyConfigDialog(activity) {
            activity.notifyProfileSettingsChanged()
        }
    }

    fun showNotificationSettingsDialog() {
        val bottomSheet = cn.edu.usst.jwgl.ui.common.NotificationDiagnosticBottomSheet()
        bottomSheet.show(activity.supportFragmentManager, "NotificationDiagnostic")
    }

    fun showLiveFrameTestDialog(keyword: String = "") {
        DevAddonManager.getEntry(activity).liveScreenExtension.showLiveFrameTestDialog(activity, keyword)
    }

    fun showAuthorDisclaimerDialog(allowDismiss: Boolean = false) {
        val existing = activity.supportFragmentManager.findFragmentByTag(AuthorDisclaimerBottomSheet.TAG) as? AuthorDisclaimerBottomSheet
        existing?.dismiss()
        val sheet = AuthorDisclaimerBottomSheet.newInstance(allowDismiss = allowDismiss).apply {
            onAgreedListener = {
                AuthPreferences(this@MainSettingsDialogController.activity).setDisclaimerAgreed(true)
            }
            onDisagreedListener = {
                this@MainSettingsDialogController.activity.finishAffinity()
            }
        }
        sheet.show(activity.supportFragmentManager, AuthorDisclaimerBottomSheet.TAG)
    }

    private var isSyncingConfig = false

    fun syncRemoteConfig(isManual: Boolean, force: Boolean = false) {
        if (isSyncingConfig) return
        if (!isManual && !force && !RemoteConfigManager.shouldPerformDailySync(activity)) {
            Log.d("MainActivity", "Daily sync already completed today (${RemoteConfigManager.getLastDailySyncDate(activity)}), skipping")
            return
        }

        isSyncingConfig = true
        val wasDailySyncPending = RemoteConfigManager.shouldPerformDailySync(activity)
        activity.lifecycleScope.launch {
            try {
                val result = RemoteConfigManager.fetchConfig(activity)
                result.onSuccess { config ->
                    RemoteConfigManager.markDailySyncCompleted(activity)
                    activity.notifyProfileSettingsChanged()

                    // Calibrate all local tables' start dates and weeks
                    val calibrated = RemoteConfigManager.calibrateTablesAndSync(activity, config)
                    if (calibrated) {
                        val allTables = db.tableDao.getAllTables()
                        val autoTarget = CourseUtils.findAutoScheduleTarget(allTables)
                        if (autoTarget != null) {
                            db.tableDao.setDefaultTable(autoTarget.table.id)
                            currentTable = autoTarget.table
                            currentWeek = autoTarget.week
                        }
                        activity.reloadTimetableFromDb()
                        Toast.makeText(activity, "已根据最新校历与调休安排自动校准课表", Toast.LENGTH_SHORT).show()
                    } else {
                        scheduleAdapter?.refreshAllFragments()
                    }

                    // Update reminders according to holidays & adjustments
                    CourseReminderManager.scheduleUpcomingReminders(activity)

                    if (RemoteConfigManager.isUpdateAvailable(BuildConfig.VERSION_CODE)) {
                        showUpdateDialog(config.appVersion, isManual = isManual)
                    } else if (isManual) {
                        Toast.makeText(activity, "当前已是最新版本，校历与调休配置已同步", Toast.LENGTH_SHORT).show()
                    } else if (wasDailySyncPending) {
                        // Daily first launch check: if there is an adjustment today, give a friendly reminder
                        val todayStr = RemoteConfigManager.getTodayDateString()
                        val todayAdjustments = RemoteConfigManager.getAdjustmentsForDate(todayStr)
                        if (todayAdjustments.isNotEmpty()) {
                            val adj = todayAdjustments.first()
                            val msg = if (adj.type == "HOLIDAY_OFF") "今日【${adj.name}】放假停课" else "今日调休: ${adj.remark}"
                            Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }.onFailure { error ->
                    Log.e("MainActivity", "Remote config sync failed", error)
                    if (RemoteConfigManager.isUpdateAvailable(BuildConfig.VERSION_CODE) &&
                        RemoteConfigManager.getLatestVersion().forceUpdate
                    ) {
                        showUpdateDialog(RemoteConfigManager.getLatestVersion(), isManual = false)
                    } else if (isManual) {
                        val msg = if (error is com.google.gson.JsonSyntaxException) "云端配置文件格式错误(JSON语法)" else "同步云端配置失败: ${error.message ?: "请检查网络"}"
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                isSyncingConfig = false
            }
        }
    }

    fun showUpdateDialog(versionInfo: AppVersionInfo, isManual: Boolean = false) {
        UpdateDialogHelper.showUpdateDialog(activity, versionInfo, forceShow = isManual)
    }

    fun MaterialAlertDialogBuilder.safeShow() {
        if (!activity.isFinishing && !activity.isDestroyed) {
            try {
                show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
}
}
}
