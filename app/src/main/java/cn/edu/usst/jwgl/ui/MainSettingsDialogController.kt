package cn.edu.usst.jwgl.ui

import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import cn.edu.usst.jwgl.data.model.LiveCourseRecord
import cn.edu.usst.jwgl.data.network.LiveUsstClient
import cn.edu.usst.jwgl.data.remote.RemoteConfigManager
import cn.edu.usst.jwgl.ui.common.AuthorDisclaimerBottomSheet
import cn.edu.usst.jwgl.ui.tronclass.LiveQrRollcallBottomSheetDialog
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
                activity.profileBinding?.tvDarkModeStatus?.text = ThemeManager.getThemeModeName(which)
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

    fun updateReminderSubtitles() {
        val pb = activity.profileBinding ?: return

        val courseMins = CourseReminderManager.getCourseReminderAdvanceMinutes(activity)
        val courseMinsStr = CourseReminderManager.formatAdvanceMinutes(courseMins)
        pb.tvCourseReminderSubtitle.text = if (CourseReminderManager.isReminderEnabled(activity)) {
            "提前 $courseMinsStr · 点击修改"
        } else {
            "已关闭 · 点击可设置提前时长"
        }

        val examMins = CourseReminderManager.getExamReminderAdvanceMinutes(activity)
        val examMinsStr = CourseReminderManager.formatAdvanceMinutes(examMins)
        pb.tvExamReminderSubtitle.text = if (CourseReminderManager.isExamReminderEnabled(activity)) {
            "提前 $examMinsStr · 点击修改"
        } else {
            "已关闭 · 点击可设置提前时长"
        }

        val tcPrefs = TronClassPreferences.getInstance(activity)
        pb.tvTronClassDdl24hSubtitle.text = if (tcPrefs.isDdlReminder24hEnabled()) {
            "截止前 24 小时首轮预警"
        } else {
            "已关闭首轮预警"
        }

        val urgentHours = tcPrefs.getDdlReminderUrgentHours()
        pb.tvTronClassDdlUrgentSubtitle.text = if (tcPrefs.isDdlReminderUrgentEnabled()) {
            "提前 $urgentHours 小时 · 点击设置"
        } else {
            "已关闭 · 点击可设置提前时长"
        }

        val isMonitorEnabled = tcPrefs.isRollcallMonitorEnabled()
        pb.tvRollcallMonitorSubtitle.text = if (isMonitorEnabled) {
            "已开启 · 上课时驻留后台检测签到活动"
        } else {
            "已关闭 · 上课时驻留后台检测签到活动"
        }

        val isCxMonitorEnabled = tcPrefs.isRollcallChaoXingMonitorEnabled()
        val isCxLoggedIn = ChaoXingPreferences(activity).isLoggedIn()
        val isCxCircuitOpen = ChaoXingRequestGuard.isCircuitOpen(activity)
        pb.switchRollcallChaoXingMonitor.isEnabled = isMonitorEnabled
        pb.rowRollcallChaoXingMonitor.alpha = if (isMonitorEnabled) 1.0f else 0.45f
        if (pb.switchRollcallChaoXingMonitor.isChecked != (isMonitorEnabled && isCxMonitorEnabled)) {
            pb.switchRollcallChaoXingMonitor.isChecked = isMonitorEnabled && isCxMonitorEnabled
        }
        pb.tvRollcallChaoXingMonitorSubtitle.text = when {
            !isMonitorEnabled -> "未启用 · 需先开启上方的上课签到后台监听"
            !isCxMonitorEnabled -> "已关闭 · 按课表锁定当前课 50~60s 低频探测，防风控"
            isCxCircuitOpen -> "已开启 · 当前处于学习通风控冷却中，暂已暂停请求"
            !isCxLoggedIn -> "已开启 · 尚未登录超星学习通，请先授权登录"
            else -> "已开启 · 仅锁定当前课程 50~60s 低频探测，发现即弹窗"
        }

        val isDevMode = DeveloperModeManager.isDeveloperMode(activity)
        pb.rowRollcallAutoSign.visibility = if (isDevMode) View.VISIBLE else View.GONE
        pb.dividerRollcallAutoSign.visibility = if (isDevMode) View.VISIBLE else View.GONE
        pb.rowLiveQrTest.visibility = if (isDevMode) View.VISIBLE else View.GONE
        pb.dividerLiveQrTest.visibility = if (isDevMode) View.VISIBLE else View.GONE

        val isAutoSignEnabled = tcPrefs.isRollcallAutoSignEnabled()
        val strategyDisplay = tcPrefs.getRollcallAutoSignStrategyDisplayName()
        pb.switchRollcallAutoSign.isEnabled = isMonitorEnabled
        pb.rowRollcallAutoSign.alpha = if (isMonitorEnabled) 1.0f else 0.45f
        pb.tvRollcallAutoSignSubtitle.text = if (!isMonitorEnabled) {
            "未启用 · 需先开启上方的上课签到后台监听"
        } else if (isAutoSignEnabled) {
            "已开启 · 触发策略：$strategyDisplay (点击设置)"
        } else {
            "已关闭 · 仅后台监听并发通知，不自动提交签到"
        }

        val alertMode = tcPrefs.getRollcallAlertMode()
        val hasOverlay = NotificationDiagnosticHelper.isOverlayPermissionGranted(activity)
        when (alertMode) {
            RollcallAlertMode.POPUP -> {
                pb.tvRollcallAlertModeValue.text = "强弹窗 ›"
                pb.tvRollcallPopupSubtitle.text = when {
                    isDevMode && hasOverlay -> "亮屏居中弹窗 + 锁屏唤醒（自动签到探测先发横幅，失败再弹窗）"
                    hasOverlay -> "亮屏居中弹窗 + 锁屏唤醒，发现课堂点名立即提醒"
                    else -> "锁屏点亮提醒（授权悬浮窗权限后支持亮屏桌面居中弹窗）"
                }
            }
            RollcallAlertMode.BANNER_ONLY -> {
                pb.tvRollcallAlertModeValue.text = "仅横幅 ›"
                pb.tvRollcallPopupSubtitle.text = if (hasOverlay) {
                    "顶部轻量悬浮横幅 + 高优通知，不弹居中窗与锁屏全屏页"
                } else {
                    "高优系统悬浮横幅提醒，不弹居中窗与锁屏全屏页"
                }
            }
        }

        val powerfulEnabled = CourseReminderManager.isPowerfulReminderEnabled(activity)
        pb.tvPowerfulReminderSubtitle.text = if (powerfulEnabled) {
            "已开启高优先级闹钟 · 状态栏常驻 ⏰ 图标，准时提醒"
        } else {
            "标准模式 · 息屏较久时可能出现轻微延迟"
        }
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
                updateReminderSubtitles()
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
                updateReminderSubtitles()
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
                updateReminderSubtitles()
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
                updateReminderSubtitles()
                TronClassDdlScheduler.scheduleFromPreferences(activity)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    fun showRollcallAutoSignDelayDialog() {
        val options = arrayOf(
            "不延迟",
            "延迟10秒",
            "延迟30秒",
            "有1人签到时",
            "有10人签到时 (默认)",
            "有一半人签到时",
            "自定义时间",
            "自定义签到人数比例"
        )
        val tcPrefs = TronClassPreferences.getInstance(activity)
        val selectedIdx = tcPrefs.getRollcallAutoSignStrategyIndex()

        MaterialAlertDialogBuilder(activity)
            .setTitle("自动签到时延与触发策略")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                when (which) {
                    0 -> {
                        tcPrefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_NO_DELAY)
                        updateReminderSubtitles()
                        dialog.dismiss()
                    }
                    1 -> {
                        tcPrefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_DELAY_10S)
                        updateReminderSubtitles()
                        dialog.dismiss()
                    }
                    2 -> {
                        tcPrefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_DELAY_30S)
                        updateReminderSubtitles()
                        dialog.dismiss()
                    }
                    3 -> {
                        tcPrefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_COUNT_1)
                        updateReminderSubtitles()
                        dialog.dismiss()
                    }
                    4 -> {
                        tcPrefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_COUNT_10)
                        updateReminderSubtitles()
                        dialog.dismiss()
                    }
                    5 -> {
                        tcPrefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_HALF_CLASS)
                        updateReminderSubtitles()
                        dialog.dismiss()
                    }
                    6 -> {
                        dialog.dismiss()
                        showCustomRollcallAutoSignTimeDialog()
                    }
                    7 -> {
                        dialog.dismiss()
                        showCustomRollcallAutoSignRatioDialog()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    fun showCustomRollcallAutoSignTimeDialog() {
        val tcPrefs = TronClassPreferences.getInstance(activity)
        val currentSeconds = tcPrefs.getRollcallAutoSignCustomSeconds()
        val input = android.widget.EditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentSeconds.toString())
            setSelection(text.length)
            hint = "请输入 0 到 300 之间的秒数"
        }

        val container = android.widget.FrameLayout(activity).apply {
            val padding = (24 * activity.resources.displayMetrics.density).toInt()
            setPadding(padding, (8 * activity.resources.displayMetrics.density).toInt(), padding, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("自定义自动签到时延 (秒)")
            .setMessage("设定自首次发现签到活动至开始尝试自动提交的等待时间（0 - 300 秒）：")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val entered = input.text.toString().trim().toIntOrNull()
                if (entered == null || entered !in 0..300) {
                    Toast.makeText(activity, "请输入 0 到 300 之间的秒数", Toast.LENGTH_SHORT).show()
                } else {
                    tcPrefs.setRollcallAutoSignCustomSeconds(entered)
                    tcPrefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_CUSTOM_TIME)
                    updateReminderSubtitles()
                }
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    fun showCustomRollcallAutoSignRatioDialog() {
        val tcPrefs = TronClassPreferences.getInstance(activity)
        val currentRatio = tcPrefs.getRollcallAutoSignCustomRatio()
        val input = android.widget.EditText(activity).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentRatio.toString())
            setSelection(text.length)
            hint = "请输入 1 到 100 之间的整数百分比"
        }

        val container = android.widget.FrameLayout(activity).apply {
            val padding = (24 * activity.resources.displayMetrics.density).toInt()
            setPadding(padding, (8 * activity.resources.displayMetrics.density).toInt(), padding, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("自定义签到人数比例 (%)")
            .setMessage("设定当班级已出勤人数达到指定比例后跟进提交（1% - 100%）：")
            .setView(container)
            .setPositiveButton("确定") { _, _ ->
                val entered = input.text.toString().trim().toIntOrNull()
                if (entered == null || entered !in 1..100) {
                    Toast.makeText(activity, "请输入 1 到 100 之间的百分比", Toast.LENGTH_SHORT).show()
                } else {
                    tcPrefs.setRollcallAutoSignCustomRatio(entered)
                    tcPrefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_CUSTOM_RATIO)
                    updateReminderSubtitles()
                }
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    fun showNotificationSettingsDialog() {
        val bottomSheet = cn.edu.usst.jwgl.ui.common.NotificationDiagnosticBottomSheet()
        bottomSheet.show(activity.supportFragmentManager, "NotificationDiagnostic")
    }

    fun showLiveFrameTestDialog(keyword: String = "") {
        if (!DeveloperModeManager.isDeveloperMode(activity)) return

        val progress = ProgressDialog(activity).apply {
            setMessage("正在获取当前在线直播课程...")
            setCancelable(false)
            if (!activity.isFinishing && !activity.isDestroyed) {
                show()
            }
        }

        activity.lifecycleScope.launch {
            val result = LiveUsstClient(activity.applicationContext).fetchOngoingLiveCourses(keyword)
            if (progress.isShowing) {
                progress.dismiss()
            }
            if (activity.isFinishing || activity.isDestroyed) return@launch

            result.onSuccess { records ->
                if (records.isEmpty()) {
                    showEmptyLiveCoursesDialog(keyword)
                } else {
                    showLiveCoursesPickerDialog(records)
                }
            }.onFailure { err ->
                MaterialAlertDialogBuilder(activity)
                    .setTitle("获取直播列表失败")
                    .setMessage(err.message ?: "请检查网络或统一身份认证登录状态")
                    .setPositiveButton("重试") { _, _ ->
                        showLiveFrameTestDialog(keyword)
                    }
                    .setNegativeButton("关闭", null)
                    .safeShow()
            }
        }
    }

    private fun showEmptyLiveCoursesDialog(lastKeyword: String) {
        val msg = if (lastKeyword.isBlank()) {
            "今日暂无在线课程直播记录，可尝试输入课程关键字检索。"
        } else {
            "未检索到包含「$lastKeyword」的直播课程。"
        }
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle("暂无匹配直播")
            .setMessage(msg)
            .setPositiveButton("输入关键字检索") { _, _ ->
                showLiveKeywordSearchDialog(lastKeyword)
            }
            .setNegativeButton("关闭", null)
        if (!getLastCrashLog().isNullOrBlank()) {
            builder.setNeutralButton("上次崩溃日志") { _, _ ->
                showLastCrashLogDialog()
            }
        }
        builder.safeShow()
    }

    private fun showLiveKeywordSearchDialog(initialKeyword: String = "") {
        val input = EditText(activity).apply {
            hint = "输入课程名称关键字（留空查今日全部）"
            setText(initialKeyword)
            setSelection(text.length)
        }
        val container = android.widget.FrameLayout(activity).apply {
            val padding = (24 * activity.resources.displayMetrics.density).toInt()
            setPadding(padding, (8 * activity.resources.displayMetrics.density).toInt(), padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("检索在线直播课程")
            .setView(container)
            .setPositiveButton("查询") { _, _ ->
                showLiveFrameTestDialog(input.text.toString().trim())
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    private fun showLiveCoursesPickerDialog(records: List<LiveCourseRecord>) {
        val now = System.currentTimeMillis()
        val labels = records.map { record ->
            val name = record.subjName?.takeIf { it.isNotBlank() } ?: "未命名课程"
            val room = record.clroName?.takeIf { it.isNotBlank() } ?: "未知教室"
            val statusSuffix = if (record.isCurrentlyLive(now)) " (直播中)" else ""
            "$name · $room$statusSuffix"
        }.toTypedArray()

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle("选择测试直播课程 (${records.size})")
            .setItems(labels) { _, which ->
                if (activity.isFinishing || activity.isDestroyed || activity.supportFragmentManager.isStateSaved) {
                    return@setItems
                }
                val chosen = records[which]
                LiveQrRollcallBottomSheetDialog.newTestInstance(
                    liveCourseId = chosen.id,
                    courseName = chosen.subjName,
                    classroom = chosen.clroName
                ).show(activity.supportFragmentManager, "DevLiveQrTest")
            }
            .setNeutralButton("关键字筛选") { _, _ ->
                showLiveKeywordSearchDialog()
            }
            .setNegativeButton("取消", null)
        if (!getLastCrashLog().isNullOrBlank()) {
            builder.setPositiveButton("上次崩溃日志") { _, _ ->
                showLastCrashLogDialog()
            }
        }
        builder.safeShow()
    }

    private fun getLastCrashLog(): String? {
        return runCatching {
            activity.getSharedPreferences("usst_crash_logs", Context.MODE_PRIVATE)
                .getString("last_crash", null)
        }.getOrNull()
    }

    private fun showLastCrashLogDialog() {
        val logText = getLastCrashLog()?.takeIf { it.isNotBlank() } ?: "暂无本地崩溃记录。"
        val dp = activity.resources.displayMetrics.density
        val textView = TextView(activity).apply {
            text = logText
            setTextIsSelectable(true)
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (12 * dp).toInt())
        }
        val scrollView = ScrollView(activity).apply {
            addView(textView)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("上次未捕获崩溃日志")
            .setView(scrollView)
            .setPositiveButton("复制") { _, _ ->
                val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("last_crash", logText))
                Toast.makeText(activity, "已复制崩溃日志", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("清空") { _, _ ->
                activity.getSharedPreferences("usst_crash_logs", Context.MODE_PRIVATE)
                    .edit()
                    .remove("last_crash")
                    .apply()
                Toast.makeText(activity, "已清空本地崩溃记录", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .safeShow()
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
                    val semConfig = config.semesterConfig
                    activity.profileBinding?.let { pb ->
                        pb.tvAppVersion.text = "v${BuildConfig.VERSION_NAME}"
                        pb.tvSemesterConfigSummary.text = "${semConfig.currentSemester} · 第 1 周 ${semConfig.week1Monday}"
                    }

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
