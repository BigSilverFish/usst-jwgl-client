package cn.edu.usst.jwgl.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import cn.edu.usst.jwgl.BuildConfig
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.StudentProfile
import cn.edu.usst.jwgl.data.network.ChaoXingRequestGuard
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.data.remote.RemoteConfigManager
import cn.edu.usst.jwgl.databinding.FragmentProfileSettingsBinding
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.util.CourseReminderManager
import cn.edu.usst.jwgl.util.DeveloperModeManager
import cn.edu.usst.jwgl.util.NotificationDiagnosticHelper
import cn.edu.usst.jwgl.util.RollcallPermissionGuide
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import cn.edu.usst.jwgl.util.ThemeManager
import cn.edu.usst.jwgl.util.TimetableSettingHelper
import cn.edu.usst.jwgl.util.getFriendlyErrorMessage
import cn.edu.usst.jwgl.worker.RollcallMonitorScheduler
import cn.edu.usst.jwgl.worker.TronClassDdlScheduler
import eightbitlab.com.blurview.RenderEffectBlur
import kotlinx.coroutines.launch

/**
 * 个人与设置页面 Fragment（分组卡片列表 + 主从折叠 + 平板双栏自适应）
 */
class ProfileSettingsFragment : Fragment(), ProfileSettingsAdapter.Listener {

    companion object {
        private const val TAG = "ProfileSettingsFragment"

        fun newInstance(): ProfileSettingsFragment = ProfileSettingsFragment()
    }

    private enum class ReminderType {
        COURSE,
        EXAM,
        TRONCLASS_24H,
        TRONCLASS_URGENT,
        ROLLCALL_MONITOR
    }

    private var _binding: FragmentProfileSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ProfileSettingsAdapter
    private var isArchiveExpanded = false
    private var isTabletLandscapeMode = false
    private var lastHeaderHeightPx = 0
    private var lastBottomPaddingPx = 0
    private var pendingReminderType: ReminderType? = null

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        val ctx = context ?: return@registerForActivityResult
        val act = activity as? MainActivity
        val tcPrefs = TronClassPreferences.getInstance(ctx)

        if (isGranted) {
            when (pendingReminderType) {
                ReminderType.COURSE -> CourseReminderManager.setReminderEnabled(ctx, true)
                ReminderType.EXAM -> CourseReminderManager.setExamReminderEnabled(ctx, true)
                ReminderType.TRONCLASS_24H -> {
                    tcPrefs.setDdlReminder24hEnabled(true)
                    TronClassDdlScheduler.scheduleFromPreferences(ctx)
                }
                ReminderType.TRONCLASS_URGENT -> {
                    tcPrefs.setDdlReminderUrgentEnabled(true)
                    TronClassDdlScheduler.scheduleFromPreferences(ctx)
                }
                ReminderType.ROLLCALL_MONITOR -> {
                    tcPrefs.setRollcallMonitorEnabled(true)
                    RollcallMonitorScheduler.onPreferenceChanged(ctx)
                    if (act != null) {
                        RollcallPermissionGuide.guideIfNeeded(act)
                    }
                }
                null -> {}
            }
        } else {
            when (pendingReminderType) {
                ReminderType.COURSE -> CourseReminderManager.setReminderEnabled(ctx, false)
                ReminderType.EXAM -> CourseReminderManager.setExamReminderEnabled(ctx, false)
                ReminderType.TRONCLASS_24H -> tcPrefs.setDdlReminder24hEnabled(false)
                ReminderType.TRONCLASS_URGENT -> tcPrefs.setDdlReminderUrgentEnabled(false)
                ReminderType.ROLLCALL_MONITOR -> {
                    // 拒绝通知权限不强制关闭签到后台监听，继续以弹窗引导补齐权限
                    tcPrefs.setRollcallMonitorEnabled(true)
                    RollcallMonitorScheduler.onPreferenceChanged(ctx)
                    if (act != null) {
                        RollcallPermissionGuide.guideIfNeeded(act)
                    }
                }
                null -> {}
            }
            if (pendingReminderType == ReminderType.ROLLCALL_MONITOR) {
                Toast.makeText(ctx, "已保持签到后台监听，未授予通知权限可能影响提醒", Toast.LENGTH_LONG).show()
            } else if (pendingReminderType != null) {
                Toast.makeText(ctx, "需要通知权限才能发送提醒通知", Toast.LENGTH_LONG).show()
            }
        }
        pendingReminderType = null
        refreshSettingsState()
    }

    private val devModeListener: (Boolean) -> Unit = {
        view?.post {
            if (_binding != null && isAdded) {
                refreshSettingsState()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ProfileSettingsAdapter(this)
        binding.rvSettings.layoutManager = LinearLayoutManager(requireContext())
        binding.rvSettings.adapter = adapter
        (binding.rvSettings.itemAnimator as? DefaultItemAnimator)?.supportsChangeAnimations = false

        initBlurHeader()
        applyWindowInsets()
        setupListeners()

        binding.llProfileHeaderContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val h = bottom - top
            val oldH = oldBottom - oldTop
            if (h > 0 && (h != oldH || h != lastHeaderHeightPx)) {
                lastHeaderHeightPx = h
                applyPanePaddings()
            }
        }
        binding.llProfileHeaderContainer.post {
            if (_binding != null) {
                val h = binding.llProfileHeaderContainer.height
                if (h > 0 && h != lastHeaderHeightPx) {
                    lastHeaderHeightPx = h
                    applyPanePaddings()
                }
            }
        }

        DeveloperModeManager.addListener(devModeListener)

        (activity as? MainActivity)?.let { act ->
            lastBottomPaddingPx = MainActivity.bottomBarHeightPx
            isTabletLandscapeMode = act.isTabletLandscapeModeActive()
        }
        updateTabletLandscapeMode(isTabletLandscapeMode)
        syncAdaptiveState()
        updateProfileData((activity as? MainActivity)?.getCurrentStudentProfile())
    }

    override fun onResume() {
        super.onResume()
        context?.let { cn.edu.usst.jwgl.devaddon.DevAddonManager.refreshIfPackageChanged(it) }
        syncAdaptiveState()
        refreshSettingsState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isTabletLandscape = (activity as? MainActivity)?.isTabletLandscapeModeActive()
            ?: TabletLayoutHelper.isTabletLandscape(requireContext(), newConfig)
        updateTabletLandscapeMode(isTabletLandscape)
        syncAdaptiveState()
    }

    override fun onDestroyView() {
        DeveloperModeManager.removeListener(devModeListener)
        _binding = null
        super.onDestroyView()
    }

    private fun initBlurHeader() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val act = activity ?: return
        try {
            val decorView = act.window.decorView
            val rootView = decorView.findViewById<ViewGroup>(android.R.id.content)
                ?: (decorView as? ViewGroup)
                ?: return
            binding.blurViewProfile.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(decorView.background)
                .setBlurRadius(20f)
        } catch (_: Exception) {}
    }

    private fun applyWindowInsets() {
        val act = activity as? MainActivity
        val fallbackTop = act?.getStatusTopPad() ?: (32 * resources.displayMetrics.density).toInt()
        binding.llProfileHeaderContainer.setPadding(
            binding.llProfileHeaderContainer.paddingLeft,
            fallbackTop,
            binding.llProfileHeaderContainer.paddingRight,
            0
        )

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val density = resources.displayMetrics.density
            val topPad = statusBarInsets.top + (8 * density).toInt()
            binding.llProfileHeaderContainer.setPadding(
                binding.llProfileHeaderContainer.paddingLeft,
                topPad,
                binding.llProfileHeaderContainer.paddingRight,
                0
            )
            binding.llProfileHeaderContainer.post {
                if (_binding != null) {
                    val h = binding.llProfileHeaderContainer.height
                    if (h > 0 && h != lastHeaderHeightPx) {
                        lastHeaderHeightPx = h
                        applyPanePaddings()
                    }
                }
            }
            insets
        }
    }

    private fun setupListeners() {
        binding.btnOpenDrawerProfile.setOnClickListener {
            (activity as? MainActivity)?.openNavDrawer()
        }
        binding.btnRefreshProfile.setOnClickListener {
            refreshProfile()
        }
        binding.btnTabletSwitchAccount.setOnClickListener {
            (activity as? MainActivity)?.showAccountSwitchBottomSheet()
        }
        binding.btnTabletLogout.setOnClickListener {
            (activity as? MainActivity)?.performLogout()
        }
    }

    fun onTabSelected() {
        if (_binding == null) return
        syncAdaptiveState()
        refreshSettingsState()
    }

    fun applyBottomPadding(bottomPaddingPx: Int) {
        lastBottomPaddingPx = bottomPaddingPx
        if (_binding == null) return
        applyPanePaddings()
    }

    fun syncAdaptiveState() {
        if (_binding == null) return
        val act = activity as? MainActivity
        val isGuest = act?.isGuestModeActive() ?: false
        val isBottomMode = act?.isBottomBarModeActive() ?: false
        val isTablet = act?.isTabletLandscapeModeActive() ?: isTabletLandscapeMode

        binding.btnOpenDrawerProfile.visibility = if (!isGuest && !isTablet && !isBottomMode) {
            View.VISIBLE
        } else {
            View.GONE
        }
        refreshSettingsState()
    }

    fun updateTabletLandscapeMode(isTabletLandscape: Boolean) {
        isTabletLandscapeMode = isTabletLandscape
        if (_binding == null) return

        val density = resources.displayMetrics.density
        val isGuest = (activity as? MainActivity)?.isGuestModeActive() ?: false

        if (isTabletLandscape) {
            binding.llProfileBody.orientation = LinearLayout.HORIZONTAL
            binding.scrollProfileLeftPane.visibility = View.VISIBLE
            binding.scrollProfileLeftPane.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                0.40f
            ).apply {
                marginEnd = (8 * density).toInt()
            }
            binding.rvSettings.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                0.60f
            )
            binding.btnTabletSwitchAccount.visibility = if (isGuest) View.GONE else View.VISIBLE
            bindTabletProfileViews((activity as? MainActivity)?.getCurrentStudentProfile())
        } else {
            binding.llProfileBody.orientation = LinearLayout.VERTICAL
            binding.scrollProfileLeftPane.visibility = View.GONE
            binding.rvSettings.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        applyPanePaddings()
        refreshSettingsState()
    }

    fun updateProfileData(profile: StudentProfile?) {
        if (_binding == null) return
        bindTabletProfileViews(profile)
        refreshSettingsState()
    }

    fun scrollToBottom() {
        if (_binding == null) return
        binding.rvSettings.postDelayed({
            if (_binding != null && adapter.itemCount > 0) {
                binding.rvSettings.scrollToPosition(adapter.itemCount - 1)
            }
        }, 200)
    }

    fun refreshSettingsState() {
        if (_binding == null || !isAdded) return
        val ctx = context ?: return
        val act = activity as? MainActivity
        val tcPrefs = TronClassPreferences.getInstance(ctx)
        val profile = act?.getCurrentStudentProfile()

        val studentName = profile?.name?.takeIf { it.isNotBlank() } ?: "学生用户"
        val studentId = profile?.studentId?.takeIf { it.isNotBlank() }
            ?: AuthPreferences(ctx).getStudentId().ifBlank { "--" }
        val roleBadge = if (profile != null &&
            (profile.educationLevel.isNotBlank() || profile.status.isNotBlank())
        ) {
            listOf(profile.educationLevel, profile.status).filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            "本科生 · 在读"
        }
        val gradeDisplay = if (profile != null && profile.grade.isNotBlank()) {
            "${profile.grade}级 (${profile.durationYears}年制)"
        } else {
            "--"
        }

        val isGuest = act?.isGuestModeActive() ?: false
        val isTablet = act?.isTabletLandscapeModeActive() ?: isTabletLandscapeMode
        val isBottomMode = act?.isBottomBarModeActive() ?: false
        val navMode = TimetableSettingHelper.getPhoneNavMode(ctx)
        val phoneNavShortLabel = if (navMode == TimetableSettingHelper.NAV_MODE_BOTTOM) "底栏模式" else "侧栏模式"

        val semConfig = RemoteConfigManager.getSemesterConfig()
        val semesterSummary = "${semConfig.currentSemester} · 第 1 周 ${semConfig.week1Monday}"
        val diagnosticSnapshot = NotificationDiagnosticHelper.inspectRollcallPermissions(ctx)

        val addonInfo = cn.edu.usst.jwgl.devaddon.DevAddonManager.getStatusInfo(ctx)
        val state = ProfileSettingsState(
            studentName = studentName,
            studentId = studentId,
            roleBadge = roleBadge,
            college = profile?.college.orEmpty(),
            major = profile?.major.orEmpty(),
            className = profile?.className.orEmpty(),
            gradeDisplay = gradeDisplay,
            gender = profile?.gender.orEmpty(),
            isArchiveExpanded = isArchiveExpanded,
            isGuestMode = isGuest,
            isTabletLandscape = isTablet,
            isBottomBarMode = isBottomMode,
            isDeveloperMode = DeveloperModeManager.isDeveloperMode(ctx),
            devAddonStatus = addonInfo.status,
            devAddonVersionName = addonInfo.versionName,
            isDevAddonUserEnabled = addonInfo.isUserEnabled,
            devAddonRequiresRestart = addonInfo.requiresRestart,
            devAddonErrorMessage = addonInfo.errorMessage,
            darkModeLabel = ThemeManager.getThemeModeName(ThemeManager.getThemeMode(ctx)),
            phoneNavModeLabel = phoneNavShortLabel,
            isDoubleTapDrawerEnabled = TimetableSettingHelper.isDoubleTapOpenDrawerEnabled(ctx),
            isDrawerProfileHeaderEnabled = TimetableSettingHelper.isDrawerProfileHeaderEnabled(ctx),
            timetableFontSizeLabel = ProfileSettingsListBuilder.formatFontSizeLabel(act?.currentTable?.itemHeight),
            isDayPartDividersEnabled = TimetableSettingHelper.isDayPartDividersEnabled(ctx),
            isCourseReminderEnabled = CourseReminderManager.isReminderEnabled(ctx),
            courseAdvanceLabel = CourseReminderManager.formatAdvanceMinutes(
                CourseReminderManager.getCourseReminderAdvanceMinutes(ctx)
            ),
            isExamReminderEnabled = CourseReminderManager.isExamReminderEnabled(ctx),
            examAdvanceLabel = CourseReminderManager.formatAdvanceMinutes(
                CourseReminderManager.getExamReminderAdvanceMinutes(ctx)
            ),
            isDdl24hEnabled = tcPrefs.isDdlReminder24hEnabled(),
            isDdlUrgentEnabled = tcPrefs.isDdlReminderUrgentEnabled(),
            ddlUrgentHours = tcPrefs.getDdlReminderUrgentHours(),
            isRollcallMonitorEnabled = tcPrefs.isRollcallMonitorEnabled(),
            isChaoXingMonitorEnabled = tcPrefs.isRollcallChaoXingMonitorEnabled(),
            isChaoXingLoggedIn = ChaoXingPreferences(ctx).isLoggedIn(),
            isChaoXingCircuitOpen = ChaoXingRequestGuard.isCircuitOpen(ctx),
            rollcallAlertMode = tcPrefs.getRollcallAlertMode(),
            hasOverlayPermission = NotificationDiagnosticHelper.isOverlayPermissionGranted(ctx),
            isRollcallAutoSignEnabled = tcPrefs.isRollcallAutoSignEnabled(),
            autoSignStrategyLabel = tcPrefs.getRollcallAutoSignStrategyDisplayName(),
            isPowerfulReminderEnabled = CourseReminderManager.isPowerfulReminderEnabled(ctx),
            missingDiagnosticCount = diagnosticSnapshot.missingItems().size,
            appVersionLabel = "v${BuildConfig.VERSION_NAME}",
            semesterSummary = semesterSummary
        )

        adapter.submitList(ProfileSettingsListBuilder.build(state))
    }

    private fun bindTabletProfileViews(profile: StudentProfile?) {
        if (_binding == null) return
        val ctx = context ?: return
        val studentName = profile?.name?.takeIf { it.isNotBlank() } ?: "学生用户"
        val studentId = profile?.studentId?.takeIf { it.isNotBlank() }
            ?: AuthPreferences(ctx).getStudentId().ifBlank { "--" }
        val avatarInitial = studentName.trim().firstOrNull { it.isLetter() }?.toString() ?: "理"

        binding.tvTabletAvatar.text = avatarInitial
        binding.tvTabletStudentName.text = studentName
        binding.tvTabletStudentId.text = "学号: $studentId"
        binding.tvTabletStudentRoleBadge.text = if (profile != null &&
            (profile.educationLevel.isNotBlank() || profile.status.isNotBlank())
        ) {
            listOf(profile.educationLevel, profile.status).filter { it.isNotBlank() }.joinToString(" · ")
        } else {
            "本科生 · 在读"
        }

        binding.tvTabletCollege.text = profile?.college?.ifBlank { "未知学院" } ?: "--"
        binding.tvTabletMajor.text = profile?.major?.ifBlank { "未知专业" } ?: "--"
        binding.tvTabletClass.text = profile?.className?.ifBlank { "--" } ?: "--"
        binding.tvTabletGrade.text = if (profile != null && profile.grade.isNotBlank()) {
            "${profile.grade}级 (${profile.durationYears}年制)"
        } else {
            "--"
        }
        binding.tvTabletGender.text = profile?.gender?.ifBlank { "--" } ?: "--"
    }

    private fun applyPanePaddings() {
        if (_binding == null) return
        val density = resources.displayMetrics.density
        val headerHeight = lastHeaderHeightPx.takeIf { it > 0 } ?: (96 * density).toInt()
        val topPad = headerHeight + (4 * density).toInt()
        val bottomPad = maxOf(lastBottomPaddingPx, (24 * density).toInt())

        binding.rvSettings.setPadding(
            binding.rvSettings.paddingLeft,
            topPad,
            binding.rvSettings.paddingRight,
            bottomPad
        )
        binding.scrollProfileLeftPane.setPadding(
            binding.scrollProfileLeftPane.paddingLeft,
            topPad,
            binding.scrollProfileLeftPane.paddingRight,
            bottomPad
        )
    }

    private fun refreshProfile() {
        val ctx = context ?: return
        val act = activity as? MainActivity ?: return
        binding.pbRefreshProfile.visibility = View.VISIBLE
        binding.btnRefreshProfile.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val auth = AuthPreferences(ctx)
                val id = auth.getStudentId()
                if (id.isEmpty()) {
                    Toast.makeText(ctx, "未检测到已登录学号，请重新登录", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val profile = JwglClient.fetchStudentProfile(id, ctx)
                if (profile.name.isNotEmpty() || profile.college.isNotEmpty() || profile.major.isNotEmpty()) {
                    DataCacheManager.getInstance(ctx).saveProfile(profile)
                    act.onProfileRefreshed(profile)
                    Toast.makeText(ctx, "个人信息与档案已更新", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, "未能获取到有效的学籍档案信息", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "refreshProfile error", e)
                Toast.makeText(ctx, e.getFriendlyErrorMessage("个人信息更新失败，请稍后重试"), Toast.LENGTH_SHORT).show()
            } finally {
                if (_binding != null) {
                    binding.pbRefreshProfile.visibility = View.GONE
                    binding.btnRefreshProfile.isEnabled = true
                }
            }
        }
    }

    // ==========================================
    // ProfileSettingsAdapter.Listener
    // ==========================================

    override fun onToggleArchiveExpanded() {
        isArchiveExpanded = !isArchiveExpanded
        refreshSettingsState()
    }

    override fun onSwitchAccountClick() {
        (activity as? MainActivity)?.showAccountSwitchBottomSheet()
    }

    override fun onLogoutClick() {
        (activity as? MainActivity)?.performLogout()
    }

    override fun onActionRowClick(id: SettingsItemId) {
        val act = activity as? MainActivity ?: return
        when (id) {
            SettingsItemId.DARK_MODE -> act.showDarkModeDialog()
            SettingsItemId.PHONE_NAV_MODE -> act.showPhoneNavModeDialog()
            SettingsItemId.TIMETABLE_FONT_SIZE -> act.showFontSizeDialog()
            SettingsItemId.ROLLCALL_ALERT_MODE -> act.showRollcallAlertModeDialog()
            SettingsItemId.LIVE_QR_TEST -> act.showLiveFrameTestDialog()
            SettingsItemId.NOTIFICATION_DIAGNOSTIC -> act.showNotificationSettingsDialog()
            SettingsItemId.CHECK_UPDATE -> act.syncRemoteConfig(isManual = true, force = true)
            SettingsItemId.GITHUB_REPO -> {
                val githubUrl = "https://github.com/BigSilverFish/usst-jwgl-client"
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
                } catch (_: Exception) {
                    Toast.makeText(act, "无法打开浏览器，请确认已安装浏览器应用", Toast.LENGTH_SHORT).show()
                }
            }
            SettingsItemId.DISCLAIMER -> act.showAuthorDisclaimerDialog(allowDismiss = true)
            SettingsItemId.DEV_ADDON_STATUS -> {
                val addonInfo = cn.edu.usst.jwgl.devaddon.DevAddonManager.getStatusInfo(act)
                val msg = when (addonInfo.status) {
                    cn.edu.usst.jwgl.devapi.DevAddonStatus.SIGNATURE_MISMATCH ->
                        "开发者扩展包与主程序签名不一致，请安装同签名版本"
                    cn.edu.usst.jwgl.devapi.DevAddonStatus.LOAD_ERROR ->
                        "加载失败: ${addonInfo.errorMessage ?: "请重启或升级主程序/扩展包"}"
                    else -> "请下载并安装与当前主程序接口版本匹配的开发者扩展包 APK"
                }
                Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
            }
            else -> {}
        }
    }

    override fun onParamChipClick(id: SettingsItemId) {
        val act = activity as? MainActivity ?: return
        when (id) {
            SettingsItemId.COURSE_REMINDER -> act.showCourseReminderAdvanceDialog()
            SettingsItemId.EXAM_REMINDER -> act.showExamReminderAdvanceDialog()
            SettingsItemId.DDL_URGENT -> act.showTronClassDdlUrgentAdvanceDialog()
            SettingsItemId.ROLLCALL_AUTO_SIGN -> act.showRollcallAutoSignDelayDialog()
            else -> {}
        }
    }

    override fun onSwitchChanged(id: SettingsItemId, isChecked: Boolean) {
        val ctx = context ?: return
        val act = activity as? MainActivity
        val tcPrefs = TronClassPreferences.getInstance(ctx)

        when (id) {
            SettingsItemId.DOUBLE_TAP_DRAWER -> {
                TimetableSettingHelper.setDoubleTapOpenDrawerEnabled(ctx, isChecked)
                refreshSettingsState()
            }
            SettingsItemId.DRAWER_PROFILE_HEADER -> {
                TimetableSettingHelper.setDrawerProfileHeaderEnabled(ctx, isChecked)
                act?.updateDrawerProfileHeaderVisibility()
                refreshSettingsState()
            }
            SettingsItemId.DAY_PART_DIVIDERS -> {
                TimetableSettingHelper.setDayPartDividersEnabled(ctx, isChecked)
                act?.onDayPartDividersChanged()
                refreshSettingsState()
            }
            SettingsItemId.COURSE_REMINDER -> {
                if (isChecked && needsNotificationPermission(ctx)) {
                    pendingReminderType = ReminderType.COURSE
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
                CourseReminderManager.setReminderEnabled(ctx, isChecked)
                refreshSettingsState()
            }
            SettingsItemId.EXAM_REMINDER -> {
                if (isChecked && needsNotificationPermission(ctx)) {
                    pendingReminderType = ReminderType.EXAM
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
                CourseReminderManager.setExamReminderEnabled(ctx, isChecked)
                refreshSettingsState()
            }
            SettingsItemId.DDL_24H -> {
                if (isChecked && needsNotificationPermission(ctx)) {
                    pendingReminderType = ReminderType.TRONCLASS_24H
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
                tcPrefs.setDdlReminder24hEnabled(isChecked)
                TronClassDdlScheduler.scheduleFromPreferences(ctx)
                refreshSettingsState()
            }
            SettingsItemId.DDL_URGENT -> {
                if (isChecked && needsNotificationPermission(ctx)) {
                    pendingReminderType = ReminderType.TRONCLASS_URGENT
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    return
                }
                tcPrefs.setDdlReminderUrgentEnabled(isChecked)
                TronClassDdlScheduler.scheduleFromPreferences(ctx)
                refreshSettingsState()
            }
            SettingsItemId.ROLLCALL_MONITOR -> {
                if (isChecked) {
                    val newlyEnabled = !tcPrefs.isRollcallMonitorEnabled()
                    tcPrefs.setRollcallMonitorEnabled(true)
                    RollcallMonitorScheduler.onPreferenceChanged(ctx)
                    if (newlyEnabled && !tcPrefs.isLoggedIn()) {
                        Toast.makeText(ctx, "已开启上课签到监听（提示：请先登录一网畅学）", Toast.LENGTH_LONG).show()
                    }
                    if (newlyEnabled) {
                        if (needsNotificationPermission(ctx)) {
                            pendingReminderType = ReminderType.ROLLCALL_MONITOR
                            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else if (act != null) {
                            RollcallPermissionGuide.guideIfNeeded(act)
                        }
                    }
                } else {
                    tcPrefs.setRollcallMonitorEnabled(false)
                    RollcallMonitorScheduler.onPreferenceChanged(ctx)
                }
                refreshSettingsState()
            }
            SettingsItemId.ROLLCALL_CHAOXING -> {
                if (isChecked && !tcPrefs.isRollcallMonitorEnabled()) {
                    Toast.makeText(ctx, "需先开启上方的上课签到后台监听总开关", Toast.LENGTH_SHORT).show()
                    refreshSettingsState()
                    return
                }
                if (tcPrefs.isRollcallChaoXingMonitorEnabled() != isChecked) {
                    tcPrefs.setRollcallChaoXingMonitorEnabled(isChecked)
                    RollcallMonitorScheduler.onPreferenceChanged(ctx)
                    if (isChecked && !ChaoXingPreferences(ctx).isLoggedIn()) {
                        Toast.makeText(ctx, "已开启学习通监听（提示：请先在一网畅学页登录超星学习通）", Toast.LENGTH_LONG).show()
                    }
                }
                refreshSettingsState()
            }
            SettingsItemId.ROLLCALL_AUTO_SIGN -> {
                if (isChecked && !tcPrefs.isRollcallMonitorEnabled()) {
                    Toast.makeText(ctx, "需先开启上课签到后台监听功能", Toast.LENGTH_SHORT).show()
                    refreshSettingsState()
                    return
                }
                tcPrefs.setRollcallAutoSignEnabled(isChecked)
                refreshSettingsState()
            }
            SettingsItemId.POWERFUL_REMINDER -> {
                CourseReminderManager.setPowerfulReminderEnabled(ctx, isChecked)
                refreshSettingsState()
            }
            SettingsItemId.DEV_ADDON_STATUS -> {
                cn.edu.usst.jwgl.devaddon.DevAddonManager.setUserEnabled(ctx, isChecked)
                Toast.makeText(
                    ctx,
                    if (isChecked) "开发者扩展包已启用" else "开发者扩展包已停用",
                    Toast.LENGTH_SHORT
                ).show()
                refreshSettingsState()
            }
            else -> {}
        }
    }

    private fun needsNotificationPermission(ctx: android.content.Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    }
}
