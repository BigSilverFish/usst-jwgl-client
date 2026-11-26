package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.network.ChaoXingRequestGuard
import cn.edu.usst.jwgl.databinding.DialogTronclassRollcallWizardBinding
import cn.edu.usst.jwgl.worker.RollcallMonitorScheduler
import cn.edu.usst.jwgl.ui.common.NotificationDiagnosticBottomSheet
import cn.edu.usst.jwgl.util.DeveloperModeManager
import cn.edu.usst.jwgl.util.RollcallPermissionGuide
import cn.edu.usst.jwgl.util.TabletLayoutHelper

/**
 * 课堂签到向导 BottomSheet 弹窗
 *
 * 优化重构自原先简陋的单选 Alert Dialog：
 * 1. 采用现代化 BottomSheet 抽屉设计，单手友好；
 * 2. 状态感知横幅：明确显示目标课程或当前检测状态；
 * 3. 3 列网格快捷入口：大图标卡片直观呈现扫码、口令、雷达三种签到形态；
 * 4. 自动化与后台守护：真实的 MaterialSwitch 开关与时延策略即时配置；
 * 5. 底部系统诊断与点名刷新辅助操作。
 */
class TronClassRollcallWizardBottomSheet : BottomSheetDialogFragment() {

    override fun onStart() {
        super.onStart()
        TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, requireContext())
    }

    private var _binding: DialogTronclassRollcallWizardBinding? = null
    private val binding get() = _binding!!

    private var designatedId: Long? = null
    private var courseName: String? = null

    // 交互回调
    var onStartQrScanListener: (() -> Unit)? = null
    var onStartNumberSignListener: ((designatedId: Long?, courseName: String?) -> Unit)? = null
    var onStartRadarSignListener: ((designatedId: Long?, courseName: String?) -> Unit)? = null
    var onRefreshRollcallListener: (() -> Unit)? = null

    companion object {
        const val TAG = "TronClassRollcallWizardBottomSheet"
        private const val ARG_DESIGNATED_ID = "arg_designated_id"
        private const val ARG_COURSE_NAME = "arg_course_name"

        fun newInstance(designatedId: Long? = null, courseName: String? = null): TronClassRollcallWizardBottomSheet {
            return TronClassRollcallWizardBottomSheet().apply {
                arguments = Bundle().apply {
                    if (designatedId != null) putLong(ARG_DESIGNATED_ID, designatedId)
                    if (courseName != null) putString(ARG_COURSE_NAME, courseName)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            if (it.containsKey(ARG_DESIGNATED_ID)) designatedId = it.getLong(ARG_DESIGNATED_ID)
            courseName = it.getString(ARG_COURSE_NAME)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogTronclassRollcallWizardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
    }

    private fun setupViews() {
        val ctx = requireContext()
        val prefs = TronClassPreferences.getInstance(ctx)

        // 1. 顶部标题与状态横幅
        binding.btnCloseWizard.setOnClickListener {
            dismiss()
        }

        if (courseName != null) {
            binding.tvWizardTitle.text = "课程签到 · $courseName"
            binding.tvStatusTitle.text = "目标课程：$courseName"
            binding.tvStatusSubtitle.text = "点名活动已就绪，请选择下方签到方式完成打卡"
            binding.viewStatusIndicator.backgroundTintList =
                ContextCompat.getColorStateList(ctx, R.color.primary)
        } else {
            binding.tvWizardTitle.text = "课堂签到向导"
            binding.tvStatusTitle.text = "未检测到集中点名"
            binding.tvStatusSubtitle.text = "若教师已发布签到，可点击下方刷新或选择方式主动提交"
            binding.viewStatusIndicator.backgroundTintList =
                ContextCompat.getColorStateList(ctx, R.color.grade_green_text)
        }

        // 2. 快捷签到卡片
        binding.cardActionScan.setOnClickListener {
            dismiss()
            onStartQrScanListener?.invoke()
        }

        binding.cardActionNumber.setOnClickListener {
            dismiss()
            onStartNumberSignListener?.invoke(designatedId, courseName)
        }

        binding.cardActionRadar.setOnClickListener {
            dismiss()
            onStartRadarSignListener?.invoke(designatedId, courseName)
        }

        // 3. 自动化与后台守护设置
        updateSwitchStates(prefs)

        // 监听开关
        binding.switchRollcallMonitor.setOnCheckedChangeListener { _, isChecked ->
            val newlyEnabled = isChecked && !prefs.isRollcallMonitorEnabled()
            prefs.setRollcallMonitorEnabled(isChecked)
            RollcallMonitorScheduler.onPreferenceChanged(ctx)
            if (isChecked) {
                Toast.makeText(ctx, "已开启上课签到后台监听：上课时自动检测课堂点名", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "已关闭上课签到后台监听", Toast.LENGTH_SHORT).show()
            }
            updateSwitchStates(prefs)
            if (newlyEnabled) {
                activity?.let { act ->
                    RollcallPermissionGuide.guideIfNeeded(act) {
                        if (isAdded) dismissAllowingStateLoss()
                    }
                }
            }
        }

        binding.layoutMonitorRow.setOnClickListener {
            binding.switchRollcallMonitor.isChecked = !binding.switchRollcallMonitor.isChecked
        }

        // 同步监听学习通签到开关
        binding.switchChaoXingMonitor.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !prefs.isRollcallMonitorEnabled()) {
                if (binding.switchChaoXingMonitor.isChecked) {
                    binding.switchChaoXingMonitor.isChecked = false
                }
                Toast.makeText(ctx, "需先开启上方的上课后台自动监听开关", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            if (prefs.isRollcallChaoXingMonitorEnabled() == isChecked) return@setOnCheckedChangeListener
            prefs.setRollcallChaoXingMonitorEnabled(isChecked)
            RollcallMonitorScheduler.onPreferenceChanged(ctx)
            if (isChecked && !ChaoXingPreferences(ctx).isLoggedIn()) {
                Toast.makeText(ctx, "已开启学习通监听（提示：请先登录超星学习通）", Toast.LENGTH_LONG).show()
            }
            updateSwitchStates(prefs)
        }

        binding.layoutChaoXingMonitorRow.setOnClickListener {
            if (!prefs.isRollcallMonitorEnabled()) {
                Toast.makeText(ctx, "需先开启上方的上课后台自动监听开关", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.switchChaoXingMonitor.isChecked = !binding.switchChaoXingMonitor.isChecked
        }

        // 自动签到提交开关
        binding.switchAutoSign.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !prefs.isRollcallMonitorEnabled()) {
                binding.switchAutoSign.isChecked = false
                Toast.makeText(ctx, "自动签到功能需要先开启上课签到后台监听", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            prefs.setRollcallAutoSignEnabled(isChecked)
            updateSwitchStates(prefs)
        }

        // 点击自动签到行 -> 打开时延与策略选择器
        binding.layoutAutoSignRow.setOnClickListener {
            if (!prefs.isRollcallMonitorEnabled()) {
                Toast.makeText(ctx, "自动签到功能需要先开启上课签到后台监听", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showAutoSignStrategyChooser(ctx, prefs)
        }

        // 4. 底部辅助操作
        binding.btnDiagnostic.setOnClickListener {
            dismiss()
            NotificationDiagnosticBottomSheet()
                .show(parentFragmentManager, "NotificationDiagnostic")
        }

        binding.btnRefreshRollcall.setOnClickListener {
            dismiss()
            onRefreshRollcallListener?.invoke()
        }
    }

    private fun updateSwitchStates(prefs: TronClassPreferences) {
        val isMonitor = prefs.isRollcallMonitorEnabled()
        val isAutoSign = prefs.isRollcallAutoSignEnabled()
        val strategyText = prefs.getRollcallAutoSignStrategyDisplayName()

        // 监听项
        binding.switchRollcallMonitor.isChecked = isMonitor
        binding.tvMonitorDesc.text = if (isMonitor) {
            "后台监听已就绪 · 上课时自动检测点名"
        } else {
            "已关闭 · 开启后上课时自动检测并弹窗提醒"
        }

        // 同步监听学习通项
        val ctx = requireContext()
        val isCxMonitor = prefs.isRollcallChaoXingMonitorEnabled()
        val isCxLoggedIn = ChaoXingPreferences(ctx).isLoggedIn()
        val isCxCircuitOpen = ChaoXingRequestGuard.isCircuitOpen(ctx)
        binding.switchChaoXingMonitor.isEnabled = isMonitor
        binding.layoutChaoXingMonitorRow.alpha = if (isMonitor) 1.0f else 0.5f
        val cxChecked = isMonitor && isCxMonitor
        if (binding.switchChaoXingMonitor.isChecked != cxChecked) {
            binding.switchChaoXingMonitor.isChecked = cxChecked
        }
        binding.tvChaoXingMonitorDesc.text = when {
            !isMonitor -> "未启用 (需先开启上方后台监听)"
            !isCxMonitor -> "已关闭 · 仅锁定当前课程 50~60s 低频探测，防风控"
            isCxCircuitOpen -> "已开启 · 当前处于风控冷却中，已自动暂停探测"
            !isCxLoggedIn -> "已开启 · 尚未登录超星学习通，请先授权登录"
            else -> "已开启 · 仅锁定当前课程 50~60s 低频探测，发现即弹窗"
        }

        // 自动提交项（受开发者模式管控：未激活时隐藏）
        val isDev = DeveloperModeManager.isDeveloperMode(requireContext())
        binding.layoutAutoSignRow.visibility = if (isDev) View.VISIBLE else View.GONE
        binding.dividerAutoSign.visibility = if (isDev) View.VISIBLE else View.GONE

        binding.switchAutoSign.isEnabled = isMonitor
        binding.switchAutoSign.isChecked = isMonitor && isAutoSign
        binding.layoutAutoSignRow.alpha = if (isMonitor) 1.0f else 0.5f

        binding.tvAutoSignStrategy.text = when {
            !isMonitor -> "未启用 (需先开启上方后台监听)"
            !isAutoSign -> "已关闭自动提交 (仅保留监听与弹窗)"
            else -> "当前策略: $strategyText · 点击配置"
        }
    }

    private fun showAutoSignStrategyChooser(ctx: Context, prefs: TronClassPreferences) {
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
        val selectedIdx = prefs.getRollcallAutoSignStrategyIndex()

        MaterialAlertDialogBuilder(ctx)
            .setTitle("选择自动签到时延与策略")
            .setSingleChoiceItems(options, selectedIdx) { d, item ->
                when (item) {
                    0 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_NO_DELAY)
                        d.dismiss()
                        updateSwitchStates(prefs)
                    }
                    1 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_DELAY_10S)
                        d.dismiss()
                        updateSwitchStates(prefs)
                    }
                    2 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_DELAY_30S)
                        d.dismiss()
                        updateSwitchStates(prefs)
                    }
                    3 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_COUNT_1)
                        d.dismiss()
                        updateSwitchStates(prefs)
                    }
                    4 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_COUNT_10)
                        d.dismiss()
                        updateSwitchStates(prefs)
                    }
                    5 -> {
                        prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_HALF_CLASS)
                        d.dismiss()
                        updateSwitchStates(prefs)
                    }
                    6 -> {
                        d.dismiss()
                        showCustomDelaySecondsDialog(ctx, prefs)
                    }
                    7 -> {
                        d.dismiss()
                        showCustomRatioDialog(ctx, prefs)
                    }
                }
            }
            .setNegativeButton("返回", null)
            .show()
    }

    private fun showCustomDelaySecondsDialog(ctx: Context, prefs: TronClassPreferences) {
        val input = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "输入等待秒数 (0~300)"
            setText(prefs.getRollcallAutoSignCustomSeconds().toString())
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("自定义延迟等待时间")
            .setMessage("检测到签到开始后，等待指定的秒数后再自动提交（0~300秒）：")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val seconds = input.text.toString().trim().toIntOrNull() ?: 15
                prefs.setRollcallAutoSignCustomSeconds(seconds)
                prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_CUSTOM_TIME)
                updateSwitchStates(prefs)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCustomRatioDialog(ctx: Context, prefs: TronClassPreferences) {
        val input = android.widget.EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = "输入签到人数百分比 (1~100)"
            setText(prefs.getRollcallAutoSignCustomRatio().toString())
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("自定义签到人数比例")
            .setMessage("班级已签到人数达到该比例时自动提交（1%~100%）：")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val ratio = input.text.toString().trim().toIntOrNull() ?: 30
                prefs.setRollcallAutoSignCustomRatio(ratio)
                prefs.setRollcallAutoSignStrategy(TronClassPreferences.STRATEGY_CUSTOM_RATIO)
                updateSwitchStates(prefs)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
