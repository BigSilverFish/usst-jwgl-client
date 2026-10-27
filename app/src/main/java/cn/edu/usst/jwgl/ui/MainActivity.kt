package cn.edu.usst.jwgl.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.view.ViewGroup
import eightbitlab.com.blurview.BlurView
import eightbitlab.com.blurview.RenderEffectBlur
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetDialog
import cn.edu.usst.jwgl.BuildConfig
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.model.AppVersionInfo
import cn.edu.usst.jwgl.data.model.ExamItem
import cn.edu.usst.jwgl.data.model.GradeDocumentType
import cn.edu.usst.jwgl.data.model.StudentProfile
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.data.remote.RemoteConfigManager
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.data.wakeup.CourseUtils
import cn.edu.usst.jwgl.data.wakeup.TableBean
import cn.edu.usst.jwgl.data.wakeup.WakeupScheduleImporter
import cn.edu.usst.jwgl.databinding.ActivityMainBinding
import cn.edu.usst.jwgl.ui.grades.GradesFragment
import cn.edu.usst.jwgl.ui.wakeup.AddCourseActivity
import cn.edu.usst.jwgl.ui.wakeup.ScheduleManagerBottomSheet
import cn.edu.usst.jwgl.ui.wakeup.SchedulePagerAdapter
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.TronClassCourse
import cn.edu.usst.jwgl.ui.tronclass.TronClassFragment
import cn.edu.usst.jwgl.worker.TronClassDdlScheduler
import android.app.ProgressDialog
import android.widget.EditText
import kotlinx.coroutines.async
import cn.edu.usst.jwgl.util.ApkDownloadHelper
import cn.edu.usst.jwgl.util.CalendarExportHelper
import android.Manifest
import android.content.pm.PackageManager
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import cn.edu.usst.jwgl.data.local.CustomCourseManager
import cn.edu.usst.jwgl.data.model.LappCourseItem
import cn.edu.usst.jwgl.data.network.LappClient
import cn.edu.usst.jwgl.data.network.CourseCatalogService
import com.google.android.material.materialswitch.MaterialSwitch
import android.widget.FrameLayout

import cn.edu.usst.jwgl.util.CourseReminderManager
import cn.edu.usst.jwgl.util.ExamHelper
import cn.edu.usst.jwgl.util.InitialSyncHelper
import cn.edu.usst.jwgl.util.SemesterHelper
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import cn.edu.usst.jwgl.util.ThemeManager
import cn.edu.usst.jwgl.util.TimetableSettingHelper
import cn.edu.usst.jwgl.util.UpdateDialogHelper
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_PROFILE = "extra_student_profile"
        const val EXTRA_TAB = "extra_tab"
        const val EXTRA_GUEST_MODE = "extra_guest_mode"
        private const val KEY_SAVED_TAB_NAME = "key_saved_tab_name"
        var topBarHeightPx: Int = 0
        var bottomBarHeightPx: Int = 0
    }

    private lateinit var binding: ActivityMainBinding
    internal var isTabletLandscapeMode: Boolean = false
    private var isGuestMode: Boolean = false
    private lateinit var cacheManager: DataCacheManager
    private lateinit var db: AppDatabase
    private var currentProfile: StudentProfile? = null

    internal var currentTable: TableBean? = null
    private var isUpdatingChipProgrammatically = false
    private var scheduleAdapter: SchedulePagerAdapter? = null
    private var currentWeek: Int = 1
    private var isInitialWeekLocated: Boolean = false

    private var pendingCalendarAction: (() -> Unit)? = null
    private val calendarPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.WRITE_CALENDAR] == true
        if (granted) {
            pendingCalendarAction?.invoke()
        } else {
            Toast.makeText(this, "需要日历读写权限才能将日程导入系统自带日历", Toast.LENGTH_LONG).show()
        }
        pendingCalendarAction = null
    }





    fun checkAndRequestCalendarPermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingCalendarAction = action
            calendarPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                )
            )
        }
    }

    /**
     * Returns the most accurate current academic week:
     * 1) From the active timetable startDate if valid;
     * 2) Otherwise from RemoteConfigManager.getCurrentWeek() (synced with cloud calendar);
     * 3) Otherwise falling back to currentWeek (coerced into 1..25).
     */
    private fun getEffectiveCurrentWeek(): Int {
        val tableWeek = currentTable?.let { CourseUtils.countWeek(it.startDate) }
        if (tableWeek != null && tableWeek in 1..25) {
            return tableWeek
        }
        val remoteWeek = RemoteConfigManager.getCurrentWeek()
        if (remoteWeek in 1..25) {
            return remoteWeek
        }
        return if (currentWeek in 1..25) currentWeek else 1
    }

    private val addCourseLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            reloadTimetableFromDb()
        }
    }

    private enum class ReminderType { COURSE, EXAM, TRONCLASS_24H, TRONCLASS_URGENT }
    private var pendingReminderType: ReminderType? = null

    private var activeDisclaimerDialog: androidx.appcompat.app.AlertDialog? = null

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            when (pendingReminderType) {
                ReminderType.COURSE -> {
                    binding.switchCourseReminder.isChecked = true
                    CourseReminderManager.setReminderEnabled(this, true)
                    updateReminderSubtitles()
                    val text = CourseReminderManager.formatAdvanceMinutes(CourseReminderManager.getCourseReminderAdvanceMinutes(this))
                    Toast.makeText(this, "已开启课前 $text 提醒", Toast.LENGTH_SHORT).show()
                }
                ReminderType.EXAM -> {
                    binding.switchExamReminder.isChecked = true
                    CourseReminderManager.setExamReminderEnabled(this, true)
                    updateReminderSubtitles()
                    val text = CourseReminderManager.formatAdvanceMinutes(CourseReminderManager.getExamReminderAdvanceMinutes(this))
                    Toast.makeText(this, "已开启考前 $text 提醒", Toast.LENGTH_SHORT).show()
                }
                ReminderType.TRONCLASS_24H -> {
                    binding.switchTronClassDdl24h.isChecked = true
                    TronClassPreferences(this).setDdlReminder24hEnabled(true)
                    updateReminderSubtitles()
                    TronClassDdlScheduler.scheduleFromPreferences(this)
                    Toast.makeText(this, "已开启作业截止 24 小时预警", Toast.LENGTH_SHORT).show()
                }
                ReminderType.TRONCLASS_URGENT -> {
                    binding.switchTronClassDdlUrgent.isChecked = true
                    val prefs = TronClassPreferences(this)
                    prefs.setDdlReminderUrgentEnabled(true)
                    updateReminderSubtitles()
                    TronClassDdlScheduler.scheduleFromPreferences(this)
                    val text = "${prefs.getDdlReminderUrgentHours()} 小时"
                    Toast.makeText(this, "已开启作业临期提前 $text 提醒", Toast.LENGTH_SHORT).show()
                }
                null -> {}
            }
        } else {
            when (pendingReminderType) {
                ReminderType.COURSE -> {
                    binding.switchCourseReminder.isChecked = false
                    CourseReminderManager.setReminderEnabled(this, false)
                    updateReminderSubtitles()
                }
                ReminderType.EXAM -> {
                    binding.switchExamReminder.isChecked = false
                    CourseReminderManager.setExamReminderEnabled(this, false)
                    updateReminderSubtitles()
                }
                ReminderType.TRONCLASS_24H -> {
                    binding.switchTronClassDdl24h.isChecked = false
                    TronClassPreferences(this).setDdlReminder24hEnabled(false)
                    updateReminderSubtitles()
                }
                ReminderType.TRONCLASS_URGENT -> {
                    binding.switchTronClassDdlUrgent.isChecked = false
                    TronClassPreferences(this).setDdlReminderUrgentEnabled(false)
                    updateReminderSubtitles()
                }
                null -> {}
            }
            Toast.makeText(this, "需要通知权限才能发送提醒通知", Toast.LENGTH_LONG).show()
        }
        pendingReminderType = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Enable edge-to-edge immersive transparent system bars
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val isDarkMode = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isDarkMode
        insetsController.isAppearanceLightNavigationBars = !isDarkMode

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val systemBarsInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val tappableInsets = insets.getInsets(WindowInsetsCompat.Type.tappableElement())
            val mandatoryInsets = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            val density = resources.displayMetrics.density

            // Extend frosted top bar into status bar while keeping control items nicely positioned below
            binding.headerTimetable.setPadding(
                binding.headerTimetable.paddingLeft,
                statusBarInsets.top + (8 * density).toInt(),
                binding.headerTimetable.paddingRight,
                (6 * density).toInt()
            )
            binding.headerTimetableContainer.post {
                topBarHeightPx = binding.headerTimetableContainer.height
            }

            // Dynamic bottom safe spacer for full screen gesture bar / 3-button navigation
            val navBarBottom = maxOf(
                navBarInsets.bottom,
                systemBarsInsets.bottom,
                tappableInsets.bottom,
                mandatoryInsets.bottom
            )
            lastNavBarBottomInsetPx = navBarBottom

            if (binding.viewNavBarSpacer.layoutParams.height != navBarBottom) {
                binding.viewNavBarSpacer.layoutParams.height = navBarBottom
                binding.viewNavBarSpacer.requestLayout()
            }

            val statusTopPad = statusBarInsets.top + (8 * density).toInt()
            lastStatusTopPadPx = statusTopPad
            lastStatusBarTopPx = statusBarInsets.top

            applyContentBottomPadding()

            binding.pageMoreFeatures.llMoreFeaturesHeaderContainer.setPadding(
                binding.pageMoreFeatures.llMoreFeaturesHeaderContainer.paddingLeft,
                statusTopPad,
                binding.pageMoreFeatures.llMoreFeaturesHeaderContainer.paddingRight,
                0
            )
            binding.pageMoreFeatures.llMoreFeaturesHeaderContainer.post {
                updateMoreFeaturesHeaderSpacing(binding.pageMoreFeatures.llMoreFeaturesHeaderContainer.height)
            }

            binding.llProfileHeaderContainer.setPadding(
                binding.llProfileHeaderContainer.paddingLeft,
                statusTopPad,
                binding.llProfileHeaderContainer.paddingRight,
                0
            )
            binding.llProfileHeaderContainer.post {
                updateProfileHeaderSpacing(binding.llProfileHeaderContainer.height)
            }

            val drawerStatusTop = if (statusBarInsets.top > 0) statusBarInsets.top else (28 * density).toInt()
            if (binding.layoutDrawer.viewDrawerStatusBarTop.layoutParams.height != drawerStatusTop) {
                binding.layoutDrawer.viewDrawerStatusBarTop.layoutParams.height = drawerStatusTop
                binding.layoutDrawer.viewDrawerStatusBarTop.requestLayout()
            }

            insets
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val decorView = window.decorView
            val rootView = decorView.findViewById<ViewGroup>(android.R.id.content)
            val windowBackground = decorView.background

            binding.blurViewTopBar.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)

            binding.blurViewBottomNav.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)


            binding.pageMoreFeatures.blurViewMoreFeatures.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)

            binding.blurViewProfile.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)

        }

        cacheManager = DataCacheManager(this)
        db = AppDatabase.getDatabase(this)
        RemoteConfigManager.init(this)
        currentWeek = RemoteConfigManager.getCurrentWeek()

        isGuestMode = intent.getBooleanExtra(EXTRA_GUEST_MODE, false)

        val profile = if (isGuestMode) {
            null
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_PROFILE, StudentProfile::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_PROFILE) as? StudentProfile
        } ?: if (isGuestMode) null else cacheManager.getProfile()
        currentProfile = profile

        displayProfile(profile)

        // 自动自愈保护：若本地缓存的学籍档案为空白（如历史版本被异常覆盖为未知），且已保存登录凭证，则在后台静默恢复
        if (!isGuestMode && (profile == null || (profile.name.isBlank() && profile.college.isBlank()))) {
            val auth = AuthPreferences(this)
            val stuId = auth.getStudentId()
            val stuPwd = auth.getPassword()
            if (stuId.isNotEmpty() && stuPwd.isNotEmpty()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        if (JwglClient.ensureSession(this@MainActivity)) {
                            val healedProfile = JwglClient.fetchStudentProfile(stuId)
                            if (healedProfile.name.isNotBlank() || healedProfile.college.isNotBlank()) {
                                cacheManager.saveProfile(healedProfile)
                                withContext(Dispatchers.Main) {
                                    displayProfile(healedProfile)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Background profile restoration failed", e)
                    }
                }
            }
        }

        setupTabletLandscapeLayout()
        applyPhoneNavigationMode()
        setupViews()
        setupListeners()
        initWakeupSchedule()
        syncRemoteConfig(isManual = false)

        if (!AuthPreferences(this).hasAgreedDisclaimer()) {
            showAuthorDisclaimerDialog(allowDismiss = false)
        }

        val savedTabName = savedInstanceState?.getString(KEY_SAVED_TAB_NAME)
        if (savedTabName != null) {
            try {
                switchTab(MainTab.valueOf(savedTabName))
            } catch (e: Exception) {
                switchTab(MainTab.TIMETABLE)
            }
        } else {
            val initialTab = intent.getIntExtra(EXTRA_TAB, 1)
            when (initialTab) {
                0 -> switchTab(MainTab.PROFILE)
                1 -> switchTab(MainTab.TIMETABLE)
                2 -> switchTab(MainTab.GRADES)
                3 -> switchTab(MainTab.COURSE_SEARCH)
                4 -> switchTab(MainTab.FREE_CLASSROOM)
                5 -> switchTab(MainTab.EXAM_QUERY)
                6 -> switchTab(MainTab.GRADE_REPORTS)
                7 -> switchTab(MainTab.MORE_FEATURES)
                8 -> switchTab(MainTab.TRONCLASS)
                else -> switchTab(MainTab.TIMETABLE)
            }
            handleIntentExtras(intent)
        }

        // 异步预热全校课表本地缓存至内存（避免用户切换至全校课表或空教室自习查询时等待）
        lifecycleScope.launch(Dispatchers.IO) {
            cn.edu.usst.jwgl.data.network.LappClient.getFullSchoolScheduleCached(this@MainActivity, "2026-2027", "1")
        }

        val authPrefs = AuthPreferences(this)
        if (!authPrefs.hasInitialSyncCompleted() && authPrefs.getStudentId().isNotEmpty() && !isGuestMode) {
            lifecycleScope.launch {
                binding.timetableProgressBar.visibility = View.VISIBLE
                val res = InitialSyncHelper.performInitialSync(this@MainActivity, authPrefs.getStudentId())
                binding.timetableProgressBar.visibility = View.GONE
                res.onSuccess { count ->
                    reloadTimetableFromDb()
                    Toast.makeText(this@MainActivity, "已自动同步过往全部学期课表 ($count 个学期)", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 空闲时异步预热成绩与一网畅学一级 Fragment（消除点击一级 Tab 时的初始化耗时与黑屏）
        binding.root.post {
            warmupCoreFragments()
        }
    }

    private fun warmupCoreFragments() {
        if (isFinishing || isDestroyed) return
        val fm = supportFragmentManager
        var transaction: androidx.fragment.app.FragmentTransaction? = null

        if (fm.findFragmentByTag("GradesFragment") == null) {
            if (transaction == null) transaction = fm.beginTransaction()
            transaction.add(R.id.containerGrades, GradesFragment.newInstance(), "GradesFragment")
        }
        if (fm.findFragmentByTag("TronClassFragment") == null) {
            if (transaction == null) transaction = fm.beginTransaction()
            transaction.add(R.id.containerTronclass, TronClassFragment.newInstance(), "TronClassFragment")
        }
        transaction?.commitAllowingStateLoss()
    }

    private var lastNavBarBottomInsetPx: Int = 0
    private var lastStatusTopPadPx: Int = 0
    private var lastStatusBarTopPx: Int = 0

    fun getStatusTopPad(): Int = lastStatusTopPadPx.takeIf { it > 0 } ?: (32 * resources.displayMetrics.density).toInt()
    fun getStatusBarTop(): Int = lastStatusBarTopPx.takeIf { it > 0 } ?: (24 * resources.displayMetrics.density).toInt()
    fun getBottomInset(): Int = lastNavBarBottomInsetPx
    fun isBottomBarModeActive(): Boolean = isPhoneBottomBarMode()
    fun isGuestModeActive(): Boolean = isGuestMode
    fun isTabletLandscapeModeActive(): Boolean = isTabletLandscapeMode
    fun getEffectiveCurrentWeekNumber(): Int = getEffectiveCurrentWeek()

    private fun isPhoneBottomBarMode(): Boolean {
        return !isTabletLandscapeMode && !isGuestMode &&
                TimetableSettingHelper.getPhoneNavMode(this) == TimetableSettingHelper.NAV_MODE_BOTTOM
    }

    private fun applyContentBottomPadding() {
        val density = resources.displayMetrics.density
        val isBottomMode = isPhoneBottomBarMode()
        val bottomNavBarHeight = if (isBottomMode) (64 * density).toInt() else 0
        bottomBarHeightPx = lastNavBarBottomInsetPx + bottomNavBarHeight + (16 * density).toInt()
        val statusTopPad = lastStatusTopPadPx.takeIf { it > 0 } ?: (32 * density).toInt()

        binding.scrollProfile.setPadding(
            binding.scrollProfile.paddingLeft,
            binding.scrollProfile.paddingTop,
            binding.scrollProfile.paddingRight,
            bottomBarHeightPx
        )
        binding.pageMoreFeatures.scrollMoreFeatures.setPadding(
            binding.pageMoreFeatures.scrollMoreFeatures.paddingLeft,
            binding.pageMoreFeatures.scrollMoreFeatures.paddingTop,
            binding.pageMoreFeatures.scrollMoreFeatures.paddingRight,
            bottomBarHeightPx
        )
        (supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)
            ?.applyBottomPadding(bottomBarHeightPx)

        supportFragmentManager.fragments.filterIsInstance<cn.edu.usst.jwgl.ui.wakeup.ScheduleWeekFragment>()
            .forEach { it.applyContentPadding() }
    }

    private fun applyPhoneNavigationMode() {
        if (isGuestMode) {
            closeNavDrawer()
            binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            binding.blurViewBottomNav.visibility = View.GONE
            binding.btnOpenDrawerTimetable.visibility = View.GONE
            (supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)?.updateDrawerButtonVisibility(false)
            binding.btnOpenDrawerProfile.visibility = View.GONE

            binding.pageMoreFeatures.btnOpenDrawerMoreFeatures.visibility = View.GONE

            binding.rowDoubleTapDrawer.visibility = View.GONE
            binding.dividerDoubleTapDrawer.visibility = View.GONE
            applyContentBottomPadding()
            return
        }

        if (isTabletLandscapeMode) {
            binding.blurViewBottomNav.visibility = View.GONE
            binding.rowPhoneNavMode.visibility = View.GONE
            binding.dividerPhoneNavMode.visibility = View.GONE
            binding.rowDoubleTapDrawer.visibility = View.GONE
            binding.dividerDoubleTapDrawer.visibility = View.GONE
            binding.cardBottomModeServices.visibility = View.GONE
            return
        }

        val navMode = TimetableSettingHelper.getPhoneNavMode(this)
        val isBottomMode = navMode == TimetableSettingHelper.NAV_MODE_BOTTOM

        binding.tvPhoneNavModeStatus.text = TimetableSettingHelper.getPhoneNavModeName(navMode)
        binding.switchDoubleTapDrawer.isChecked = TimetableSettingHelper.isDoubleTapOpenDrawerEnabled(this)
        binding.cardBottomModeServices.visibility = View.GONE

        if (isBottomMode) {
            closeNavDrawer()
            binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            binding.blurViewBottomNav.visibility = View.VISIBLE

            // Hide top-left drawer hamburger icons on the main bottom-bar pages
            binding.btnOpenDrawerTimetable.visibility = View.GONE
            (supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)?.updateDrawerButtonVisibility(false)
            binding.btnOpenDrawerProfile.visibility = View.GONE

            // All 4 school features (全校查课 / 空教室 / 考试日程 / 成绩单与证明) are under "更多功能" with a Back arrow
            binding.pageMoreFeatures.btnOpenDrawerMoreFeatures.visibility = View.GONE

            // Double-tap drawer option is only applicable in Sidebar mode
            binding.rowDoubleTapDrawer.visibility = View.GONE
            binding.dividerDoubleTapDrawer.visibility = View.GONE
        } else {
            binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED)
            binding.blurViewBottomNav.visibility = View.GONE

            binding.btnOpenDrawerTimetable.visibility = View.VISIBLE
            (supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)?.updateDrawerButtonVisibility(true)
            binding.btnOpenDrawerProfile.visibility = View.VISIBLE
            binding.pageMoreFeatures.btnOpenDrawerMoreFeatures.visibility = View.VISIBLE

            binding.rowDoubleTapDrawer.visibility = View.VISIBLE
            binding.dividerDoubleTapDrawer.visibility = View.VISIBLE

            if (currentTab == MainTab.MORE_FEATURES) {
                switchTab(MainTab.TIMETABLE)
            }
        }

        applyContentBottomPadding()
    }

    fun onTimetableEmptySpaceDoubleTapped(): Boolean {
        if (isTabletLandscapeMode) return false
        if (TimetableSettingHelper.getPhoneNavMode(this) != TimetableSettingHelper.NAV_MODE_DRAWER) return false
        if (!TimetableSettingHelper.isDoubleTapOpenDrawerEnabled(this)) return false
        openNavDrawer()
        return true
    }

    fun openNavDrawer() {
        if (!isTabletLandscapeMode &&
            !isPhoneBottomBarMode() &&
            binding.layoutDrawer.root.parent == binding.drawerLayout
        ) {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    private fun closeNavDrawer() {
        if (!isTabletLandscapeMode && binding.layoutDrawer.root.parent == binding.drawerLayout) {
            if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                binding.drawerLayout.closeDrawer(GravityCompat.START)
            }
        }
    }

    private fun isNavDrawerOpen(): Boolean {
        return !isTabletLandscapeMode &&
                binding.layoutDrawer.root.parent == binding.drawerLayout &&
                binding.drawerLayout.isDrawerOpen(GravityCompat.START)
    }

    private fun setupTabletLandscapeLayout() {
        isTabletLandscapeMode = TabletLayoutHelper.isTabletLandscape(this)
        if (!isTabletLandscapeMode) return

        val dp = { v: Float -> TabletLayoutHelper.dpToPx(this, v) }

        // 1. 常驻左侧导航栏 (Permanent Left Side Navigation)
        val drawerView = binding.layoutDrawer.root
        (drawerView.parent as? ViewGroup)?.removeView(drawerView)
        binding.permanentSidebarContainer.addView(
            drawerView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        binding.permanentSidebarContainer.visibility = View.VISIBLE
        binding.viewPermanentSidebarDivider.visibility = View.VISIBLE
        binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        // 隐藏各页面顶栏左上角汉堡包菜单图标
        binding.btnOpenDrawerTimetable.visibility = View.GONE
        (supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)?.updateDrawerButtonVisibility(false)
        binding.btnOpenDrawerProfile.visibility = View.GONE

        // 3. 双栏主从视图 (Two-Pane Master-Detail Layouts)
        // 3a. 学业情况查询 (委托给 GradesFragment)
        (supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)
            ?.updateTabletLandscapeMode(true)

        // 3b. 个人与设置 (左：学生名片 + 学籍档案；右：外观提醒设置 + 系统更新 + 退出登录)
        binding.llProfileBody.orientation = LinearLayout.HORIZONTAL
        binding.llProfileLeftPane.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            0.42f
        ).apply {
            marginEnd = dp(16f)
        }
        binding.llProfileRightPane.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            0.58f
        )
        (binding.cardSettings.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
    }

    private fun restorePortraitLayout() {
        isTabletLandscapeMode = false
        val dp = { v: Float -> TabletLayoutHelper.dpToPx(this, v) }

        // 1. 恢复侧边栏至抽屉
        val drawerView = binding.layoutDrawer.root
        (drawerView.parent as? ViewGroup)?.removeView(drawerView)
        if (drawerView.parent == null) {
            val lp = androidx.drawerlayout.widget.DrawerLayout.LayoutParams(
                (310 * resources.displayMetrics.density).toInt(),
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = GravityCompat.START
            }
            binding.drawerLayout.addView(drawerView, lp)
        }
        binding.permanentSidebarContainer.visibility = View.GONE
        binding.viewPermanentSidebarDivider.visibility = View.GONE
        binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED)

        /* 3. Restore two-pane to vertical */
        (supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)
            ?.updateTabletLandscapeMode(false)

        binding.llProfileBody.orientation = LinearLayout.VERTICAL
        binding.llProfileLeftPane.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = 0
        }
        binding.llProfileRightPane.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        (binding.cardSettings.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(14f)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isTabletLandscape = TabletLayoutHelper.isTabletLandscape(this)
        if (isTabletLandscape != isTabletLandscapeMode) {
            if (isTabletLandscape) {
                setupTabletLandscapeLayout()
            } else {
                restorePortraitLayout()
            }
            applyPhoneNavigationMode()
            applyContentBottomPadding()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SAVED_TAB_NAME, currentTab.name)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        reloadTimetableFromDb()
        ApkDownloadHelper.pendingInstallApkFile?.let { apk ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
                ApkDownloadHelper.pendingInstallApkFile = null
                ApkDownloadHelper.installApk(this, apk)
            }
        }
        if (RemoteConfigManager.shouldPerformDailySync(this)) {
            syncRemoteConfig(isManual = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentExtras(intent)
    }

    private fun handleIntentExtras(intent: Intent?) {
        if (intent == null) return

        val tab = intent.getIntExtra("extra_tab", -1)
        if (tab in 0..7) {
            closeNavDrawer()
            when (tab) {
                0 -> switchTab(MainTab.PROFILE)
                1 -> switchTab(MainTab.TIMETABLE)
                2 -> switchTab(MainTab.GRADES)
                3 -> switchTab(MainTab.COURSE_SEARCH)
                4 -> switchTab(MainTab.FREE_CLASSROOM)
                5 -> switchTab(MainTab.EXAM_QUERY)
                6 -> switchTab(MainTab.GRADE_REPORTS)
                7 -> switchTab(MainTab.MORE_FEATURES)
            }
        }

        val week = intent.getIntExtra("extra_week", -1)
        if (week > 0) {
            val maxW = currentTable?.maxWeek ?: 25
            if (week in 1..maxW) {
                currentWeek = week
                binding.vpSchedule.setCurrentItem(week - 1, false)
                updateWeekSelectionUI(week)
            }
        }

        if (intent.getBooleanExtra("extra_scroll_bottom", false)) {
            binding.scrollProfile.postDelayed({
                binding.scrollProfile.fullScroll(View.FOCUS_DOWN)
            }, 200)
        }

        val searchCourse = intent.getStringExtra("extra_search_course")
        if (!searchCourse.isNullOrEmpty()) {
            switchTab(MainTab.COURSE_SEARCH)
            val frag = supportFragmentManager.findFragmentByTag("SchoolCourseSearchFragment") as? cn.edu.usst.jwgl.ui.school.SchoolCourseSearchFragment
            if (frag == null) {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.containerCourseSearch, cn.edu.usst.jwgl.ui.school.SchoolCourseSearchFragment.newInstance(searchCourse), "SchoolCourseSearchFragment")
                    .commitAllowingStateLoss()
            } else {
                frag.arguments = Bundle().apply { putString("arg_initial_query", searchCourse) }
                frag.executeSearch(forceRefresh = false)
            }
        }

        if (intent.getBooleanExtra("extra_scroll_timetable_bottom", false)) {
            binding.vpSchedule.postDelayed({
                val frag = supportFragmentManager.fragments.filterIsInstance<cn.edu.usst.jwgl.ui.wakeup.ScheduleWeekFragment>()
                    .find { it.arguments?.getInt(cn.edu.usst.jwgl.ui.wakeup.ScheduleWeekFragment.ARG_WEEK) == currentWeek }
                frag?.scrollToBottom()
            }, 300)
        }

        if (intent.hasExtra("extra_day_parts")) {
            val enabled = intent.getBooleanExtra("extra_day_parts", true)
            TimetableSettingHelper.setDayPartDividersEnabled(this, enabled)
            binding.switchDayPartDividers.isChecked = enabled
            scheduleAdapter?.refreshAllFragments()
        }

        if (intent.hasExtra("extra_nav_mode")) {
            val mode = intent.getIntExtra("extra_nav_mode", TimetableSettingHelper.NAV_MODE_DRAWER)
            TimetableSettingHelper.setPhoneNavMode(this, mode)
            applyPhoneNavigationMode()
        }

        if (intent.hasExtra("extra_double_tap_drawer")) {
            val enabled = intent.getBooleanExtra("extra_double_tap_drawer", true)
            TimetableSettingHelper.setDoubleTapOpenDrawerEnabled(this, enabled)
            binding.switchDoubleTapDrawer.isChecked = enabled
        }

        if (intent.getBooleanExtra("extra_open_drawer", false)) {
            openNavDrawer()
        }

        if (intent.getBooleanExtra("extra_set_disclaimer_agreed", false)) {
            AuthPreferences(this).setDisclaimerAgreed(true)
            activeDisclaimerDialog?.dismiss()
            activeDisclaimerDialog = null
        }

        if (intent.getBooleanExtra("extra_show_disclaimer", false)) {
            showAuthorDisclaimerDialog(allowDismiss = true)
        }

        val tableIdExtra = intent.getIntExtra("extra_table_id", -1)
        if (tableIdExtra > 0) {
            lifecycleScope.launch {
                val target = withContext(Dispatchers.IO) { db.tableDao.getTableById(tableIdExtra) }
                if (target != null) {
                    withContext(Dispatchers.IO) { db.tableDao.setDefaultTable(target.id) }
                    currentTable = target
                    val realWeek = CourseUtils.countWeek(target.startDate)
                    currentWeek = if (realWeek in 1..target.maxWeek) realWeek else 1
                    isInitialWeekLocated = true
                    reloadTimetableFromDb()
                }
            }
        }

        val tableNameExtra = intent.getStringExtra("extra_table_name")
        if (!tableNameExtra.isNullOrBlank()) {
            lifecycleScope.launch {
                val target = withContext(Dispatchers.IO) { db.tableDao.getAllTables() }.find { it.tableName == tableNameExtra }
                if (target != null) {
                    withContext(Dispatchers.IO) { db.tableDao.setDefaultTable(target.id) }
                    currentTable = target
                    val realWeek = CourseUtils.countWeek(target.startDate)
                    currentWeek = if (realWeek in 1..target.maxWeek) realWeek else 1
                    isInitialWeekLocated = true
                    reloadTimetableFromDb()
                }
            }
        }

        if (intent.getBooleanExtra("extra_show_add_course", false)) {
            val addIntent = Intent(this, AddCourseActivity::class.java).apply {
                putExtra("tableId", currentTable?.id ?: 1)
            }
            addCourseLauncher.launch(addIntent)
        }

        val gradeSem = intent.getStringExtra("extra_grade_semester")
        if (gradeSem != null) {
            switchTab(MainTab.GRADES)
            supportFragmentManager.executePendingTransactions()
            (supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)?.filterSemester(gradeSem)
        }

        if (intent.getBooleanExtra("extra_open_exam", false)) {
            showExamQueryBottomSheet()
        }

        if (intent.getBooleanExtra("extra_show_grade_picker", false)) {
            switchTab(MainTab.GRADES)
            supportFragmentManager.executePendingTransactions()
            (supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)?.showGradeSemesterPicker()
        }

        if (intent.getBooleanExtra("extra_show_download_dialog", false)) {
            switchTab(MainTab.GRADE_REPORTS)
        }

        if (intent.getBooleanExtra("extra_show_week_picker", false)) {
            showWeekPickerDialog()
        }

        

        

        

        

        if (intent.hasExtra("extra_simulate_hour")) {
            val h = intent.getIntExtra("extra_simulate_hour", -1)
            if (h >= 0) {
                val m = intent.getIntExtra("extra_simulate_minute", 0)
                val dow = intent.getIntExtra("extra_simulate_dow", Calendar.WEDNESDAY)
                val cal = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_WEEK, dow)
                    set(Calendar.HOUR_OF_DAY, h)
                    set(Calendar.MINUTE, m)
                }
                SemesterHelper.simulatedToday = cal
            } else {
                SemesterHelper.simulatedToday = null
            }
            if (currentTab == MainTab.COURSE_SEARCH) {
                (supportFragmentManager.findFragmentByTag("SchoolCourseSearchFragment") as? cn.edu.usst.jwgl.ui.school.SchoolCourseSearchFragment)
                    ?.checkAndLoadDefaultCourseSearch(isUserInitiatedReset = true)
            }
        }
    }

    private fun initWakeupSchedule() {
        lifecycleScope.launch {
            val initData = withContext(Dispatchers.IO) {
                db.ensureLatestTimeTableAndDefaults()
                val defaultT = db.tableDao.getDefaultTable()

                // Seamless auto-import of pre-existing cached timetables into WakeUP database
                val cachedList = cacheManager.getAllCachedTimetables()
                if (cachedList.isNotEmpty() && db.courseBaseDao.getCourseOfTable(defaultT.id).isEmpty()) {
                    val semConfig = RemoteConfigManager.getSemesterConfig()
                    val startDate = semConfig.week1Monday.ifBlank { "2026-09-07" }
                    for (cached in cachedList) {
                        WakeupScheduleImporter.importTimetableData(db, cached, startDate)
                    }
                }

                // Clear legacy test exam items if any existed in cache
                if (cacheManager.getAllCachedExams().any { it.courseCode == "1001001" || it.courseCode == "1002002" }) {
                    cacheManager.clearExams()
                }

                // Auto determine active semester & week on startup
                val allTables = db.tableDao.getAllTables()
                val autoTarget = CourseUtils.findAutoScheduleTarget(allTables)
                val targetTable = autoTarget?.table ?: db.tableDao.getDefaultTable()
                val targetWeek = autoTarget?.week ?: 1

                db.tableDao.setDefaultTable(targetTable.id)
                Triple(targetTable, targetWeek, targetTable.maxWeek)
            }

            val (targetTable, targetWeek, maxWeek) = initData
            currentTable = targetTable
            val realCurrentWeek = CourseUtils.countWeek(targetTable.startDate)
            val determinedWeek = if (targetWeek in 1..maxWeek) targetWeek else if (realCurrentWeek in 1..maxWeek) realCurrentWeek else 1
            currentWeek = determinedWeek

            scheduleAdapter = SchedulePagerAdapter(this@MainActivity, maxWeek, targetTable.id)
            binding.vpSchedule.adapter = scheduleAdapter
            binding.vpSchedule.offscreenPageLimit = 1
            binding.vpSchedule.setCurrentItem(determinedWeek - 1, false)

            binding.vpSchedule.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    if (!isInitialWeekLocated) {
                        return
                    }
                    val selectedWeek = position + 1
                    currentWeek = selectedWeek
                    updateWeekSelectionUI(selectedWeek)
                    updateWeekHeaderDates(selectedWeek)
                }
            })

            reloadTimetableFromDb(initialWeek = determinedWeek, forceWeek = true)
        }
    }

    private fun formatSemesterTitle(title: String): String {
        val singleLine = title.replace("\r\n", " ").replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        val regex = Regex("(\\d{4}-\\d{4}学年)\\s*(第.+?学期)")
        return if (regex.containsMatchIn(singleLine)) {
            singleLine.replace(regex, "$1 $2")
        } else {
            singleLine.replace("学年第", "学年 第")
        }
    }

    fun reloadTimetableFromDb(initialWeek: Int? = null, forceWeek: Boolean = false) {
        lifecycleScope.launch {
            if (scheduleAdapter == null) {
                // Prevent race condition on cold startup before initWakeupSchedule completes
                return@launch
            }
            val table = withContext(Dispatchers.IO) {
                db.tableDao.getDefaultTable()
            }
            currentTable = table

            binding.tvTimetableSemester.text = formatSemesterTitle(table.tableName)
            isUpdatingChipProgrammatically = true
            binding.chipFilterOnlyCurrentWeek.isChecked = !table.showOtherWeekCourse
            isUpdatingChipProgrammatically = false

            val realCurrentWeek = CourseUtils.countWeek(table.startDate)
            if (!isInitialWeekLocated || forceWeek || currentWeek !in 1..table.maxWeek) {
                currentWeek = when {
                    initialWeek != null && initialWeek in 1..table.maxWeek -> initialWeek
                    realCurrentWeek in 1..table.maxWeek -> realCurrentWeek
                    else -> 1
                }
                isInitialWeekLocated = true
            }

            scheduleAdapter?.updateConfig(table.maxWeek, table.id)

            binding.vpSchedule.setCurrentItem(currentWeek - 1, false)
            updateWeekSelectionUI(currentWeek)
            updateWeekHeaderDates(currentWeek)

            scheduleAdapter?.refreshAllFragments()
            cn.edu.usst.jwgl.widget.TodayScheduleWidgetProvider.updateAllWidgets(this@MainActivity)
        }
    }

    private fun updateWeekSelectionUI(week: Int) {
        val table = currentTable ?: return
        val realCurrentWeek = CourseUtils.countWeek(table.startDate)
        val isCurrent = (week == realCurrentWeek)
        binding.tvCurrentWeekIndicator.text = if (isCurrent) "第 $week 周 (本周)" else "第 $week 周"
    }

    enum class MainTab {
        TIMETABLE,
        GRADES,
        TRONCLASS,
        MORE_FEATURES,
        COURSE_SEARCH,
        FREE_CLASSROOM,
        EXAM_QUERY,
        GRADE_REPORTS,
        PROFILE
    }
    private var currentTab: MainTab = MainTab.TIMETABLE

    fun switchTab(tab: MainTab) {
        currentTab = tab
        updateDrawerActiveTab(tab)
        updateBottomNavActiveTab(tab)

        binding.layoutTimetable.visibility = if (tab == MainTab.TIMETABLE) View.VISIBLE else View.GONE
        binding.containerGrades.visibility = if (tab == MainTab.GRADES) View.VISIBLE else View.GONE
        binding.layoutProfile.visibility = if (tab == MainTab.PROFILE) View.VISIBLE else View.GONE
        binding.pageMoreFeatures.layoutMoreFeatures.visibility = if (tab == MainTab.MORE_FEATURES) View.VISIBLE else View.GONE
        binding.containerCourseSearch.visibility = if (tab == MainTab.COURSE_SEARCH) View.VISIBLE else View.GONE
        binding.containerFreeClassroom.visibility = if (tab == MainTab.FREE_CLASSROOM) View.VISIBLE else View.GONE
        binding.containerExamQuery.visibility = if (tab == MainTab.EXAM_QUERY) View.VISIBLE else View.GONE
        binding.containerGradeReports.visibility = if (tab == MainTab.GRADE_REPORTS) View.VISIBLE else View.GONE
        binding.containerTronclass.visibility = if (tab == MainTab.TRONCLASS) View.VISIBLE else View.GONE

        when (tab) {
            MainTab.TIMETABLE -> reloadTimetableFromDb()
            MainTab.GRADES -> {
                if (supportFragmentManager.findFragmentByTag("GradesFragment") == null) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.containerGrades, GradesFragment.newInstance(), "GradesFragment")
                        .commitAllowingStateLoss()
                }
            }
            MainTab.TRONCLASS -> {
                if (supportFragmentManager.findFragmentByTag("TronClassFragment") == null) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.containerTronclass, TronClassFragment.newInstance(), "TronClassFragment")
                        .commitAllowingStateLoss()
                } else {
                    getTronClassFragment()?.checkAndRefreshHeaderSpacing()
                }
            }
            MainTab.MORE_FEATURES -> {
                binding.pageMoreFeatures.llMoreFeaturesHeaderContainer.post {
                    updateMoreFeaturesHeaderSpacing(binding.pageMoreFeatures.llMoreFeaturesHeaderContainer.height)
                }
            }
            MainTab.COURSE_SEARCH -> {
                if (supportFragmentManager.findFragmentByTag("SchoolCourseSearchFragment") == null) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.containerCourseSearch, cn.edu.usst.jwgl.ui.school.SchoolCourseSearchFragment.newInstance(), "SchoolCourseSearchFragment")
                        .commitAllowingStateLoss()
                }
            }
            MainTab.FREE_CLASSROOM -> {
                if (supportFragmentManager.findFragmentByTag("FreeClassroomFragment") == null) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.containerFreeClassroom, cn.edu.usst.jwgl.ui.school.FreeClassroomFragment.newInstance(), "FreeClassroomFragment")
                        .commitAllowingStateLoss()
                }
            }
            MainTab.EXAM_QUERY -> {
                if (supportFragmentManager.findFragmentByTag("ExamQueryFragment") == null) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.containerExamQuery, cn.edu.usst.jwgl.ui.exam.ExamQueryFragment.newInstance(), "ExamQueryFragment")
                        .commitAllowingStateLoss()
                }
            }
            MainTab.GRADE_REPORTS -> {
                if (supportFragmentManager.findFragmentByTag("GradeReportsFragment") == null) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.containerGradeReports, cn.edu.usst.jwgl.ui.school.GradeReportsFragment.newInstance(), "GradeReportsFragment")
                        .commitAllowingStateLoss()
                }
            }
            MainTab.PROFILE -> {
                binding.llProfileHeaderContainer.post {
                    updateProfileHeaderSpacing(binding.llProfileHeaderContainer.height)
                }
            }
        }
    }

    private fun updateBottomNavActiveTab(tab: MainTab) {
        fun setBottomTabStyle(pill: FrameLayout, icon: ImageView, label: TextView, isActive: Boolean) {
            if (isActive) {
                pill.setBackgroundResource(R.drawable.bg_bottom_nav_pill)
                icon.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.primary)
                )
                label.setTextColor(ContextCompat.getColor(this, R.color.primary))
                label.typeface = android.graphics.Typeface.DEFAULT_BOLD
            } else {
                pill.background = null
                icon.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.text_secondary)
                )
                label.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                label.typeface = android.graphics.Typeface.DEFAULT
            }
        }

        val isMoreSection = tab == MainTab.MORE_FEATURES ||
                tab == MainTab.COURSE_SEARCH ||
                tab == MainTab.FREE_CLASSROOM ||
                tab == MainTab.EXAM_QUERY ||
                tab == MainTab.GRADE_REPORTS

        setBottomTabStyle(
            binding.flBottomPillTimetable,
            binding.ivBottomTimetable,
            binding.tvBottomTimetable,
            tab == MainTab.TIMETABLE
        )
        setBottomTabStyle(
            binding.flBottomPillGrades,
            binding.ivBottomGrades,
            binding.tvBottomGrades,
            tab == MainTab.GRADES
        )
        setBottomTabStyle(
            binding.flBottomPillTronClass,
            binding.ivBottomTronClass,
            binding.tvBottomTronClass,
            tab == MainTab.TRONCLASS
        )
        setBottomTabStyle(
            binding.flBottomPillMore,
            binding.ivBottomMore,
            binding.tvBottomMore,
            isMoreSection
        )
        setBottomTabStyle(
            binding.flBottomPillProfile,
            binding.ivBottomProfile,
            binding.tvBottomProfile,
            tab == MainTab.PROFILE
        )
    }

    private fun updateDrawerActiveTab(tab: MainTab) {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
        val defaultBg = typedValue.resourceId

        fun setNavActive(layout: LinearLayout, icon: ImageView, text: TextView, isActive: Boolean) {
            if (isActive) {
                layout.setBackgroundResource(R.drawable.bg_drawer_active)
                text.setTextColor(ContextCompat.getColor(this, R.color.primary))
                text.typeface = android.graphics.Typeface.DEFAULT_BOLD
                icon.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.primary)
                )
            } else {
                layout.setBackgroundResource(defaultBg)
                text.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                text.typeface = android.graphics.Typeface.DEFAULT
                icon.imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.text_secondary)
                )
            }
        }

        setNavActive(
            binding.layoutDrawer.drawerNavTimetable,
            binding.layoutDrawer.ivNavTimetable,
            binding.layoutDrawer.tvNavTimetable,
            tab == MainTab.TIMETABLE
        )
        setNavActive(
            binding.layoutDrawer.drawerNavGrades,
            binding.layoutDrawer.ivNavGrades,
            binding.layoutDrawer.tvNavGrades,
            tab == MainTab.GRADES
        )
        setNavActive(
            binding.layoutDrawer.drawerNavTronClass,
            binding.layoutDrawer.ivNavTronClass,
            binding.layoutDrawer.tvNavTronClass,
            tab == MainTab.TRONCLASS
        )
        setNavActive(
            binding.layoutDrawer.drawerItemSchoolSearch,
            binding.layoutDrawer.ivDrawerSchoolSearch,
            binding.layoutDrawer.tvDrawerSchoolSearchTitle,
            tab == MainTab.COURSE_SEARCH
        )
        setNavActive(
            binding.layoutDrawer.drawerItemFreeClassroom,
            binding.layoutDrawer.ivDrawerFreeClassroom,
            binding.layoutDrawer.tvDrawerFreeClassroomTitle,
            tab == MainTab.FREE_CLASSROOM
        )
        setNavActive(
            binding.layoutDrawer.drawerItemExamQuery,
            binding.layoutDrawer.ivDrawerExamQuery,
            binding.layoutDrawer.tvDrawerExamQueryTitle,
            tab == MainTab.EXAM_QUERY
        )
        setNavActive(
            binding.layoutDrawer.drawerItemGradeReports,
            binding.layoutDrawer.ivDrawerGradeReports,
            binding.layoutDrawer.tvDrawerGradeReportsTitle,
            tab == MainTab.GRADE_REPORTS
        )
        setNavActive(
            binding.layoutDrawer.drawerNavProfile,
            binding.layoutDrawer.ivNavProfile,
            binding.layoutDrawer.tvNavProfile,
            tab == MainTab.PROFILE
        )
    }

    private fun setupViews() {
        updateDrawerProfileHeaderVisibility()

        binding.headerTimetableContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0) {
                topBarHeightPx = h
            }
        }

        // Open WakeUP Schedule Manager
        val openScheduleManager = View.OnClickListener {
            val sheet = ScheduleManagerBottomSheet()
            sheet.setOnScheduleChangedListener {
                currentWeek = -1
                reloadTimetableFromDb()
            }
            sheet.show(supportFragmentManager, "ScheduleManagerBottomSheet")
        }
        binding.btnSelectSemester.setOnClickListener(openScheduleManager)
        binding.btnSelectWeek.setOnClickListener { showWeekPickerDialog() }

        binding.btnAddCourse.setOnClickListener {
            androidx.appcompat.widget.PopupMenu(this, binding.btnAddCourse).apply {
                menu.add(0, 1, 0, "手动添加课程")
                menu.add(0, 2, 1, "导入实验课表")
                menu.add(0, 3, 2, "导入 / 导出日历 (.ics)")
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> {
                            val intent = Intent(this@MainActivity, AddCourseActivity::class.java).apply {
                                putExtra("tableId", currentTable?.id ?: 1)
                            }
                            addCourseLauncher.launch(intent)
                            true
                        }
                        2 -> {
                            addCourseLauncher.launch(Intent(this@MainActivity, ExcelCleanActivity::class.java))
                            true
                        }
                        3 -> {
                            CalendarExportHelper.showScheduleCalendarDialog(this@MainActivity, currentTable?.id) {
                                checkAndRequestCalendarPermission {
                                    lifecycleScope.launch {
                                        val result = CalendarExportHelper.importScheduleToSystemCalendar(this@MainActivity, currentTable?.id)
                                        result.onSuccess { count ->
                                            Toast.makeText(this@MainActivity, "成功将 $count 节课程导入系统日历 (含15分钟课前提醒)", Toast.LENGTH_LONG).show()
                                        }.onFailure { err ->
                                            Toast.makeText(this@MainActivity, "导入系统日历失败: ${err.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                            true
                        }
                        else -> false
                    }
                }
                show()
            }
        }

        // Font Size Dialog (adjust item height and text size)
        binding.btnFontSize.setOnClickListener {
            showFontSizeDialog()
        }

        // Filter: Show non-current week courses toggle
        binding.chipFilterOnlyCurrentWeek.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingChipProgrammatically) return@setOnCheckedChangeListener
            val table = currentTable ?: return@setOnCheckedChangeListener
            table.showOtherWeekCourse = !isChecked
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { db.tableDao.updateTable(table) }
                scheduleAdapter?.refreshAllFragments()
            }
        }

        // Refresh / Sync from USST JWGL
        binding.btnRefreshTimetable.setOnClickListener {
            loadTimetableFromNetwork()
        }

        // Profile Refresh
        binding.swipeRefreshProfile.isEnabled = false
        binding.btnRefreshProfile.setOnClickListener { refreshProfile() }

        // Dark Mode
        binding.rowDarkMode.setOnClickListener { showDarkModeDialog() }

        // Course Reminder Row & Switch
        binding.rowCourseReminder.setOnClickListener {
            showCourseReminderAdvanceDialog()
        }
        binding.switchDrawerProfileHeader.isChecked = TimetableSettingHelper.isDrawerProfileHeaderEnabled(this)
        binding.rowDrawerProfileHeader.setOnClickListener {
            binding.switchDrawerProfileHeader.toggle()
        }
        binding.switchDrawerProfileHeader.setOnCheckedChangeListener { _, isChecked ->
            TimetableSettingHelper.setDrawerProfileHeaderEnabled(this, isChecked)
            updateDrawerProfileHeaderVisibility()
        }

        binding.switchCourseReminder.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        pendingReminderType = ReminderType.COURSE
                        requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        return@setOnCheckedChangeListener
                    }
                }
                CourseReminderManager.setReminderEnabled(this, true)
                updateReminderSubtitles()
                val text = CourseReminderManager.formatAdvanceMinutes(CourseReminderManager.getCourseReminderAdvanceMinutes(this))
                Toast.makeText(this, "已开启课前 $text 提醒", Toast.LENGTH_SHORT).show()
            } else {
                CourseReminderManager.setReminderEnabled(this, false)
                updateReminderSubtitles()
                Toast.makeText(this, "已关闭课前提醒", Toast.LENGTH_SHORT).show()
            }
        }

        // Exam Reminder Row & Switch
        binding.rowExamReminder.setOnClickListener {
            showExamReminderAdvanceDialog()
        }
        binding.switchExamReminder.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        pendingReminderType = ReminderType.EXAM
                        requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        return@setOnCheckedChangeListener
                    }
                }
                CourseReminderManager.setExamReminderEnabled(this, true)
                updateReminderSubtitles()
                val text = CourseReminderManager.formatAdvanceMinutes(CourseReminderManager.getExamReminderAdvanceMinutes(this))
                Toast.makeText(this, "已开启考前 $text 提醒", Toast.LENGTH_SHORT).show()
            } else {
                CourseReminderManager.setExamReminderEnabled(this, false)
                updateReminderSubtitles()
                Toast.makeText(this, "已关闭考前提醒", Toast.LENGTH_SHORT).show()
            }
        }

        // TronClass DDL 24h Reminder Row & Switch
        val tcPrefs = TronClassPreferences(this)
        binding.rowTronClassDdl24h.setOnClickListener {
            binding.switchTronClassDdl24h.toggle()
        }
        binding.switchTronClassDdl24h.isChecked = tcPrefs.isDdlReminder24hEnabled()
        binding.switchTronClassDdl24h.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        pendingReminderType = ReminderType.TRONCLASS_24H
                        requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        return@setOnCheckedChangeListener
                    }
                }
                tcPrefs.setDdlReminder24hEnabled(true)
                updateReminderSubtitles()
                TronClassDdlScheduler.scheduleFromPreferences(this)
                Toast.makeText(this, "已开启作业截止 24 小时预警", Toast.LENGTH_SHORT).show()
            } else {
                tcPrefs.setDdlReminder24hEnabled(false)
                updateReminderSubtitles()
                TronClassDdlScheduler.scheduleFromPreferences(this)
                Toast.makeText(this, "已关闭作业截止 24 小时预警", Toast.LENGTH_SHORT).show()
            }
        }

        // TronClass DDL Urgent (1-3h) Reminder Row & Switch
        binding.rowTronClassDdlUrgent.setOnClickListener {
            showTronClassDdlUrgentAdvanceDialog()
        }
        binding.switchTronClassDdlUrgent.isChecked = tcPrefs.isDdlReminderUrgentEnabled()
        binding.switchTronClassDdlUrgent.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        pendingReminderType = ReminderType.TRONCLASS_URGENT
                        requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        return@setOnCheckedChangeListener
                    }
                }
                tcPrefs.setDdlReminderUrgentEnabled(true)
                updateReminderSubtitles()
                TronClassDdlScheduler.scheduleFromPreferences(this)
                val hours = tcPrefs.getDdlReminderUrgentHours()
                Toast.makeText(this, "已开启作业临期提前 ${hours} 小时提醒", Toast.LENGTH_SHORT).show()
            } else {
                tcPrefs.setDdlReminderUrgentEnabled(false)
                updateReminderSubtitles()
                TronClassDdlScheduler.scheduleFromPreferences(this)
                Toast.makeText(this, "已关闭作业临期提醒", Toast.LENGTH_SHORT).show()
            }
        }

        // Powerful AlarmClock Reminder Row & Switch
        binding.rowPowerfulReminder.setOnClickListener {
            binding.switchPowerfulReminder.toggle()
        }
        binding.switchPowerfulReminder.isChecked = CourseReminderManager.isPowerfulReminderEnabled(this)
        binding.switchPowerfulReminder.setOnCheckedChangeListener { _, isChecked ->
            CourseReminderManager.setPowerfulReminderEnabled(this, isChecked)
            updateReminderSubtitles()
            val msg = if (isChecked) {
                "已开启强力防漏模式（系统闹钟级别穿透）"
            } else {
                "已切换为标准后台提醒模式"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Battery Optimization & Anti-miss Guide Row
        binding.rowBatteryOptimizationGuide.setOnClickListener {
            showBatteryOptimizationGuideDialog()
        }

        // Day-part Dividers (上午/下午/晚上) Row & Switch
        binding.switchDayPartDividers.isChecked = TimetableSettingHelper.isDayPartDividersEnabled(this)
        binding.rowDayPartDividers.setOnClickListener {
            binding.switchDayPartDividers.toggle()
        }
        binding.switchDayPartDividers.setOnCheckedChangeListener { _, isChecked ->
            TimetableSettingHelper.setDayPartDividersEnabled(this, isChecked)
            scheduleAdapter?.refreshAllFragments()
            val msg = if (isChecked) "已开启时段区分 (上午/下午/晚上)" else "已关闭时段区分"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Phone Navigation Mode (侧栏模式 / 底栏模式)
        binding.rowPhoneNavMode.setOnClickListener {
            showPhoneNavModeDialog()
        }

        // Double-tap empty space on timetable to open sidebar drawer
        binding.switchDoubleTapDrawer.isChecked = TimetableSettingHelper.isDoubleTapOpenDrawerEnabled(this)
        binding.rowDoubleTapDrawer.setOnClickListener {
            binding.switchDoubleTapDrawer.toggle()
        }
        binding.switchDoubleTapDrawer.setOnCheckedChangeListener { _, isChecked ->
            TimetableSettingHelper.setDoubleTapOpenDrawerEnabled(this, isChecked)
            val msg = if (isChecked) "已开启双击课表空白处呼出侧栏" else "已关闭双击课表空白处呼出侧栏"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

    }

    private fun loadTimetableFromNetwork() {
        binding.timetableProgressBar.visibility = View.VISIBLE

        // 动态解析当前正在查看的课表所对应的学年与学期
        val parsedSem = SemesterHelper.parseSemesterFromTableName(currentTable?.tableName)
        val (xnm, xqm) = parsedSem ?: SemesterHelper.getCurrentSemester()
        val semNum = if (xqm == "3") "1" else if (xqm == "12") "2" else "3"

        lifecycleScope.launch {
            val result = JwglClient.fetchTimetable(xnm, xqm, context = this@MainActivity)
            binding.timetableProgressBar.visibility = View.GONE

            result.onSuccess { data ->
                val remoteSemesters = RemoteConfigManager.getSemesters()
                val matchedConfig = remoteSemesters.find {
                    it.semesterId == "$xnm-${(xnm.toIntOrNull() ?: 2025) + 1}-$semNum" ||
                    (it.semesterId.contains(xnm) && it.semesterId.endsWith(semNum)) ||
                    it.semesterTitle.contains(data.semesterTitle)
                }

                val startDate = matchedConfig?.startDate?.takeIf { it.isNotBlank() }
                    ?: currentTable?.startDate?.takeIf { it.isNotBlank() }
                    ?: (if (semNum == "1") "$xnm-09-07" else "${(xnm.toIntOrNull() ?: 2025) + 1}-03-01")

                val importedTable = WakeupScheduleImporter.importTimetableData(
                    db = db,
                    data = data,
                    startDate = startDate,
                    setAsDefault = true
                )
                withContext(Dispatchers.IO) {
                    db.tableDao.setDefaultTable(importedTable.id)
                }
                currentTable = importedTable
                scheduleAdapter?.updateConfig(importedTable.maxWeek, importedTable.id)

                // Check if current week is within exam sync window:
                val curWeek = CourseUtils.countWeek(importedTable.startDate)
                var examSyncMsg = ""
                if (ExamHelper.isExamSyncWindow(importedTable.tableName, curWeek)) {
                    val examRes = JwglClient.fetchExams(xnm, xqm, context = this@MainActivity)
                    examRes.onSuccess { exams ->
                        cacheManager.saveExams(xnm, xqm, exams)
                        CourseReminderManager.scheduleExamReminders(this@MainActivity, exams)
                        if (exams.isNotEmpty()) {
                            examSyncMsg = "，同步 ${exams.size} 门考试"
                        }
                    }.onFailure { e ->
                        Log.w("MainActivity", "Failed to sync exams", e)
                    }
                }

                reloadTimetableFromDb()
                binding.tvTimetableSyncTime.text = "已联网同步 · 刚刚"
                CourseReminderManager.scheduleUpcomingReminders(this@MainActivity)
                Toast.makeText(this@MainActivity, "${data.semesterTitle} 课表同步成功$examSyncMsg", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(this@MainActivity, "课表联网同步失败: ${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showWeekPickerDialog() {
        val table = currentTable ?: return
        val realCurrentWeek = CourseUtils.countWeek(table.startDate)
        val maxWeek = table.maxWeek
        val options = Array(maxWeek) { i ->
            val w = i + 1
            if (w == realCurrentWeek) "第 $w 周 (本周)" else "第 $w 周"
        }
        val currentIdx = (currentWeek - 1).coerceIn(0, maxWeek - 1)

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("切换周次 (共 ${maxWeek} 周)")
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                val targetWeek = which + 1
                currentWeek = targetWeek
                binding.vpSchedule.setCurrentItem(targetWeek - 1, true)
                updateWeekSelectionUI(targetWeek)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)

        if (currentWeek != realCurrentWeek && realCurrentWeek in 1..maxWeek) {
            builder.setNeutralButton("回到本周") { dialog, _ ->
                currentWeek = realCurrentWeek
                binding.vpSchedule.setCurrentItem(realCurrentWeek - 1, true)
                updateWeekSelectionUI(realCurrentWeek)
                dialog.dismiss()
            }
        }

        builder.safeShow()
    }

    private fun showFontSizeDialog() {
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

        MaterialAlertDialogBuilder(this)
            .setTitle("课表格子大小")
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                val (height, textSize) = configs[which]
                table.itemHeight = height
                table.itemTextSize = textSize
                db.tableDao.updateTable(table)
                scheduleAdapter?.refreshAllFragments()
                dialog.dismiss()
                Toast.makeText(this, "已设置：${options[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    private fun updateWeekHeaderDates(week: Int) {
        val table = currentTable ?: return
        val dateStrings = CourseUtils.getDateStringFromWeek(table.startDate, week, table.sundayFirst)
        val todayWeekday = CourseUtils.getTodayWeekdayInt()
        val curWeek = CourseUtils.countWeek(table.startDate)
        val isExamWeek = ExamHelper.isExamWeek(table.tableName, week)
        val cachedExams = if (isExamWeek) cacheManager.getAllCachedExams() else emptyList()
        val allCourses = db.courseBaseDao.getCourseOfTable(table.id)

        binding.tvMonthHeader.text = if (isExamWeek) "${dateStrings[0]}\n月\n[考]" else "${dateStrings[0]}\n月"

        val daysArray = if (table.sundayFirst) {
            arrayOf("日", "周一", "周二", "周三", "周四", "周五", "周六")
        } else {
            arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        }

        val totalDays = 7
        var childIndex = 0
        for (i in 0 until totalDays) {
            val dayNumber = if (table.sundayFirst) (if (i == 0) 7 else i) else (i + 1)
            val fullDateStr = CourseUtils.getFullDateForWeekDay(table.startDate, week, i, table.sundayFirst)
            val resolved = cn.edu.usst.jwgl.data.wakeup.CourseAdjustmentResolver.resolve(db, table.id, fullDateStr, week, dayNumber, allCourses)

            val dayExams = if (isExamWeek) {
                cachedExams.filter { it.getDateString() == fullDateStr }
            } else {
                emptyList()
            }

            val hasContent = resolved.isSwapped || resolved.courses.isNotEmpty() || dayExams.isNotEmpty()
            if (!table.showSat && dayNumber == 6 && !hasContent) continue
            if (!table.showSun && dayNumber == 7 && !hasContent) continue

            val isToday = (dayNumber == todayWeekday && week == curWeek)

            val badge = when {
                resolved.isHolidayOff -> " [休]"
                resolved.isSwapped -> " [调]"
                isExamWeek && dayExams.isNotEmpty() -> " [考]"
                else -> ""
            }

            val dayNameText = "${daysArray[i]}$badge"
            val dayNameTextSize = if (badge.isNotEmpty()) 10.5f else 12f
            val dayNameTypeface = if (isToday) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            val dayNameColor = if (isToday) {
                androidx.core.content.ContextCompat.getColor(this, R.color.primary)
            } else if (resolved.isHolidayOff) {
                0xFF4CAF50.toInt()
            } else {
                androidx.core.content.ContextCompat.getColor(this, R.color.text_primary)
            }

            val dateStr = if (i + 1 < dateStrings.size) dateStrings[i + 1] else ""
            val dayDateText = "$dateStr 日"
            val dayDateColor = if (isToday) {
                androidx.core.content.ContextCompat.getColor(this, R.color.primary)
            } else {
                androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary)
            }

            val existingDayView = binding.llDayHeaderContainer.getChildAt(childIndex) as? android.widget.LinearLayout
            val dayView = if (existingDayView != null && existingDayView.childCount == 2) {
                existingDayView
            } else {
                android.widget.LinearLayout(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    addView(android.widget.TextView(context).apply { gravity = android.view.Gravity.CENTER })
                    addView(android.widget.TextView(context).apply { gravity = android.view.Gravity.CENTER })
                    if (existingDayView != null) {
                        binding.llDayHeaderContainer.removeViewAt(childIndex)
                    }
                    binding.llDayHeaderContainer.addView(this, childIndex)
                }
            }

            dayView.background = if (isToday) androidx.core.content.ContextCompat.getDrawable(this, R.drawable.badge_bg) else null

            val tvDayName = dayView.getChildAt(0) as android.widget.TextView
            tvDayName.text = dayNameText
            tvDayName.textSize = dayNameTextSize
            tvDayName.typeface = dayNameTypeface
            tvDayName.setTextColor(dayNameColor)

            val tvDayDate = dayView.getChildAt(1) as android.widget.TextView
            tvDayDate.text = dayDateText
            tvDayDate.textSize = 10.5f
            tvDayDate.setTextColor(dayDateColor)

            childIndex++
        }

        while (binding.llDayHeaderContainer.childCount > childIndex) {
            binding.llDayHeaderContainer.removeViewAt(binding.llDayHeaderContainer.childCount - 1)
        }
    }

    private fun updateDrawerProfileHeaderVisibility() {
        val isEnabled = TimetableSettingHelper.isDrawerProfileHeaderEnabled(this)
        if (isEnabled && !isGuestMode) {
            binding.layoutDrawer.cardDrawerProfileHeader.visibility = View.VISIBLE
            binding.layoutDrawer.dividerDrawerProfileHeader.visibility = View.VISIBLE
            binding.layoutDrawer.viewDrawerStatusBarTop.visibility = View.GONE
            val profile = currentProfile
            if (profile != null && profile.name.isNotBlank()) {
                val studentName = profile.name.trim()
                val displayName = if (studentName.endsWith("同学")) studentName else "${studentName}同学"
                binding.layoutDrawer.tvDrawerStudentName.text = displayName
                binding.layoutDrawer.tvDrawerStudentId.text = "学号: ${profile.studentId.ifBlank { "--" }}"
                val avatarChar = studentName.filter { it.isLetter() }.firstOrNull()?.toString() ?: "理"
                binding.layoutDrawer.tvDrawerAvatar.text = avatarChar
                val collegeMajor = listOfNotNull(profile.college.takeIf { it.isNotBlank() }, profile.major.takeIf { it.isNotBlank() }).joinToString(" · ")
                binding.layoutDrawer.tvDrawerCollegeMajor.text = collegeMajor.ifBlank { "上海理工大学" }
            } else {
                binding.layoutDrawer.tvDrawerStudentName.text = "同学"
                binding.layoutDrawer.tvDrawerStudentId.text = "学号: --"
                binding.layoutDrawer.tvDrawerAvatar.text = "理"
                binding.layoutDrawer.tvDrawerCollegeMajor.text = "上海理工大学"
            }
        } else {
            binding.layoutDrawer.cardDrawerProfileHeader.visibility = View.GONE
            binding.layoutDrawer.dividerDrawerProfileHeader.visibility = View.GONE
            binding.layoutDrawer.viewDrawerStatusBarTop.visibility = View.VISIBLE
        }
    }

    private fun displayProfile(profile: StudentProfile?) {
        currentProfile = profile
        binding.tvAppVersion.text = "v${BuildConfig.VERSION_NAME}"
        val semConfig = RemoteConfigManager.getSemesterConfig()
        binding.tvSemesterConfigSummary.text = "${semConfig.currentSemester} · 第 1 周 ${semConfig.week1Monday}"

        binding.tvDarkModeStatus.text = ThemeManager.getThemeModeName(ThemeManager.getThemeMode(this))
        binding.tvPhoneNavModeStatus.text = TimetableSettingHelper.getPhoneNavModeName(TimetableSettingHelper.getPhoneNavMode(this))
        binding.switchCourseReminder.isChecked = CourseReminderManager.isReminderEnabled(this)
        binding.switchExamReminder.isChecked = CourseReminderManager.isExamReminderEnabled(this)
        val tcPrefs = TronClassPreferences(this)
        binding.switchTronClassDdl24h.isChecked = tcPrefs.isDdlReminder24hEnabled()
        binding.switchTronClassDdlUrgent.isChecked = tcPrefs.isDdlReminderUrgentEnabled()
        binding.switchPowerfulReminder.isChecked = CourseReminderManager.isPowerfulReminderEnabled(this)
        binding.switchDayPartDividers.isChecked = TimetableSettingHelper.isDayPartDividersEnabled(this)
        binding.switchDoubleTapDrawer.isChecked = TimetableSettingHelper.isDoubleTapOpenDrawerEnabled(this)
        binding.switchDrawerProfileHeader.isChecked = TimetableSettingHelper.isDrawerProfileHeaderEnabled(this)
        updateReminderSubtitles()

        // Update Drawer Version and Profile Header
        binding.layoutDrawer.tvDrawerVersion.text = "USST JWGL · v${BuildConfig.VERSION_NAME}"
        updateDrawerProfileHeaderVisibility()

        if (profile == null) return

        val studentName = profile.name.ifEmpty { "学生用户" }
        binding.tvStudentName.text = studentName
        binding.tvStudentId.text = "学号: ${profile.studentId.ifEmpty { "--" }}"
        binding.tvStudentRoleBadge.text = "${profile.educationLevel} · ${profile.status}"

        binding.tvCollege.text = profile.college.ifEmpty { "未知学院" }
        binding.tvMajor.text = profile.major.ifEmpty { "未知专业" }
        binding.tvClass.text = profile.className.ifEmpty { "--" }
        binding.tvGrade.text = if (profile.grade.isNotEmpty()) "${profile.grade}级 (${profile.durationYears}年制)" else "--"
        binding.tvGender.text = profile.gender.ifEmpty { "--" }
    }

    private fun showDarkModeDialog() {
        val options = arrayOf("跟随系统 (默认)", "浅色模式", "深色模式")
        val currentMode = ThemeManager.getThemeMode(this)

        MaterialAlertDialogBuilder(this)
            .setTitle("深色模式")
            .setSingleChoiceItems(options, currentMode) { dialog, which ->
                ThemeManager.setThemeMode(this, which)
                binding.tvDarkModeStatus.text = ThemeManager.getThemeModeName(which)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    private fun showPhoneNavModeDialog() {
        val options = arrayOf(
            "侧栏模式 (左滑/双击课表空白处呼出)",
            "底栏模式 (底部常驻四栏导航)"
        )
        val currentMode = TimetableSettingHelper.getPhoneNavMode(this)

        MaterialAlertDialogBuilder(this)
            .setTitle("导航栏模式 (手机)")
            .setSingleChoiceItems(options, currentMode) { dialog, which ->
                TimetableSettingHelper.setPhoneNavMode(this, which)
                applyPhoneNavigationMode()
                val modeName = TimetableSettingHelper.getPhoneNavModeName(which)
                Toast.makeText(this, "已切换为：$modeName", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    private fun updateReminderSubtitles() {
        val courseMins = CourseReminderManager.getCourseReminderAdvanceMinutes(this)
        val courseMinsStr = CourseReminderManager.formatAdvanceMinutes(courseMins)
        binding.tvCourseReminderSubtitle.text = if (CourseReminderManager.isReminderEnabled(this)) {
            "提前 $courseMinsStr · 点击修改"
        } else {
            "已关闭 · 点击可设置提前时长"
        }

        val examMins = CourseReminderManager.getExamReminderAdvanceMinutes(this)
        val examMinsStr = CourseReminderManager.formatAdvanceMinutes(examMins)
        binding.tvExamReminderSubtitle.text = if (CourseReminderManager.isExamReminderEnabled(this)) {
            "提前 $examMinsStr · 点击修改"
        } else {
            "已关闭 · 点击可设置提前时长"
        }

        val tcPrefs = TronClassPreferences(this)
        binding.tvTronClassDdl24hSubtitle.text = if (tcPrefs.isDdlReminder24hEnabled()) {
            "截止前 24 小时首轮预警"
        } else {
            "已关闭首轮预警"
        }

        val urgentHours = tcPrefs.getDdlReminderUrgentHours()
        binding.tvTronClassDdlUrgentSubtitle.text = if (tcPrefs.isDdlReminderUrgentEnabled()) {
            "提前 $urgentHours 小时 · 点击设置"
        } else {
            "已关闭 · 点击可设置提前时长"
        }

        val powerfulEnabled = CourseReminderManager.isPowerfulReminderEnabled(this)
        binding.tvPowerfulReminderSubtitle.text = if (powerfulEnabled) {
            "已开启系统时钟穿透 · 状态栏显示 ⏰ 标记，防杀防漏"
        } else {
            "标准模式 · 灭屏深度休眠时可能受系统节流"
        }
    }

    private fun showCourseReminderAdvanceDialog() {
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
        val currentMins = CourseReminderManager.getCourseReminderAdvanceMinutes(this)
        var selectedIdx = values.indexOf(currentMins).let { if (it >= 0) it else 2 }

        MaterialAlertDialogBuilder(this)
            .setTitle("课前提醒时间")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                val chosenMins = values[which]
                CourseReminderManager.setCourseReminderAdvanceMinutes(this, chosenMins)
                updateReminderSubtitles()
                val text = CourseReminderManager.formatAdvanceMinutes(chosenMins)
                Toast.makeText(this, "已设置课前 $text 提醒", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    private fun showExamReminderAdvanceDialog() {
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
        val currentMins = CourseReminderManager.getExamReminderAdvanceMinutes(this)
        var selectedIdx = values.indexOf(currentMins).let { if (it >= 0) it else 1 }

        MaterialAlertDialogBuilder(this)
            .setTitle("考前提醒时间")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                val chosenMins = values[which]
                CourseReminderManager.setExamReminderAdvanceMinutes(this, chosenMins)
                updateReminderSubtitles()
                val text = CourseReminderManager.formatAdvanceMinutes(chosenMins)
                Toast.makeText(this, "已设置考前 $text 提醒", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    private fun showTronClassDdlUrgentAdvanceDialog() {
        val options = arrayOf(
            "提前 1 小时",
            "提前 2 小时 (推荐)",
            "提前 3 小时"
        )
        val values = intArrayOf(1, 2, 3)
        val tcPrefs = TronClassPreferences(this)
        val currentHours = tcPrefs.getDdlReminderUrgentHours()
        val selectedIdx = values.indexOf(currentHours).let { if (it >= 0) it else 1 }

        MaterialAlertDialogBuilder(this)
            .setTitle("作业临期提醒时间")
            .setSingleChoiceItems(options, selectedIdx) { dialog, which ->
                val chosenHours = values[which]
                tcPrefs.setDdlReminderUrgentHours(chosenHours)
                updateReminderSubtitles()
                TronClassDdlScheduler.scheduleFromPreferences(this)
                Toast.makeText(this, "已设置作业临期提前 $chosenHours 小时提醒", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    private fun showBatteryOptimizationGuideDialog() {
        val guideMsg = """
            为避免手机灭屏休眠导致课前/考前提醒延迟或漏报，建议完成以下自检：

            1. 开启【允许自启动 / 后台运行】：
            进入应用权限设置，允许“自启动”和“后台运行”。

            2. 电池策略设为【无限制】：
            在电池管理中将本应用设为“无限制”或“不进行耗电优化”，避免被系统强制冻结。

            3. 开启【强力防漏模式】：
            使用系统底层 AlarmClock 协议唤醒，穿透国产系统休眠，保障 100% 准时提醒。

            4. 检查【通知与精确闹钟】权限：
            确保系统的通知开关已开启，且提示音与震动正常生效。
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("🔔 防漏提醒与保活自检指南")
            .setMessage(guideMsg)
            .setPositiveButton("前往应用详情") { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "无法打开设置页: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("电池优化设置") { _, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton("我知道了", null)
            .safeShow()
    }

    private fun refreshProfile() {
        binding.swipeRefreshProfile.isRefreshing = true
        lifecycleScope.launch {
            try {
                val auth = AuthPreferences(this@MainActivity)
                val id = auth.getStudentId()
                if (id.isEmpty()) {
                    Toast.makeText(this@MainActivity, "未检测到已登录学号，请重新登录", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val profile = JwglClient.fetchStudentProfile(id, this@MainActivity)

                if (profile.name.isNotEmpty() || profile.college.isNotEmpty() || profile.major.isNotEmpty()) {
                    cacheManager.saveProfile(profile)
                    displayProfile(profile)
                    Toast.makeText(this@MainActivity, "个人信息与档案已更新", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "未能获取到有效的学籍档案信息", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "refreshProfile error", e)
                Toast.makeText(this@MainActivity, "个人信息更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.swipeRefreshProfile.isRefreshing = false
            }
        }
    }

    private fun showExamQueryBottomSheet() {
        val table = currentTable
        val (calcXnm, calcXqm) = SemesterHelper.getCurrentSemester()
        var targetXnm = calcXnm
        var targetXqm = calcXqm
        var targetTitle = table?.tableName ?: ""

        if (table != null) {
            val semType = ExamHelper.getSemesterType(table.tableName)
            val yearMatch = Regex("(\\d{4})[-–—](\\d{4})").find(table.tableName)
            if (yearMatch != null) {
                targetXnm = yearMatch.groupValues[1]
            }
            if (semType == 1) targetXqm = "3"
            else if (semType == 2) targetXqm = "12"
        }

        val sheet = cn.edu.usst.jwgl.ui.exam.ExamQueryBottomSheet().apply {
            setSemester(targetXnm, targetXqm, targetTitle)
        }
        sheet.show(supportFragmentManager, "ExamQueryBottomSheet")
    }

    private fun setupListeners() {
        // Back press handling: close drawer first if opened
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isGuestMode) {
                    finish()
                } else if (isNavDrawerOpen()) {
                    closeNavDrawer()
                } else if (currentTab == MainTab.TRONCLASS && getTronClassFragment()?.handleBackPressed() == true) {
                    // Handled by TronClassFragment child fragment manager
                } else if (isPhoneBottomBarMode() && (
                        currentTab == MainTab.COURSE_SEARCH ||
                        currentTab == MainTab.FREE_CLASSROOM ||
                        currentTab == MainTab.EXAM_QUERY ||
                        currentTab == MainTab.GRADE_REPORTS
                    )) {
                    switchTab(MainTab.MORE_FEATURES)
                } else if (currentTab != MainTab.TIMETABLE) {
                    switchTab(MainTab.TIMETABLE)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        // Custom 4-Tab Bottom Navigation Bar (Phone Bottom Mode)
        binding.tabBottomTimetable.setOnClickListener {
            switchTab(MainTab.TIMETABLE)
        }
        binding.tabBottomGrades.setOnClickListener {
            switchTab(MainTab.GRADES)
        }
        binding.tabBottomTronClass.setOnClickListener {
            if (currentTab == MainTab.TRONCLASS && getTronClassFragment()?.hasBackStack() == true) {
                clearTronClassBackStack()
            } else {
                switchTab(MainTab.TRONCLASS)
            }
        }
        binding.tabBottomMore.setOnClickListener {
            switchTab(MainTab.MORE_FEATURES)
        }
        binding.tabBottomProfile.setOnClickListener {
            switchTab(MainTab.PROFILE)
        }

        // More Features Hub Page (Bottom Bar Mode)
        binding.pageMoreFeatures.btnOpenDrawerMoreFeatures.setOnClickListener {
            openNavDrawer()
        }
        binding.pageMoreFeatures.rowMoreCourseSearch.setOnClickListener {
            switchTab(MainTab.COURSE_SEARCH)
        }
        binding.pageMoreFeatures.rowMoreFreeClassroom.setOnClickListener {
            switchTab(MainTab.FREE_CLASSROOM)
        }
        binding.pageMoreFeatures.rowMoreExamQuery.setOnClickListener {
            switchTab(MainTab.EXAM_QUERY)
        }
        binding.pageMoreFeatures.rowMoreGradeReports.setOnClickListener {
            switchTab(MainTab.GRADE_REPORTS)
        }
        binding.pageMoreFeatures.rowMoreWidgetPin.setOnClickListener {
            handleWidgetPinRequest()
        }

        binding.pageMoreFeatures.rowMoreJwglWeb.setOnClickListener {
            JwglWebActivity.start(this, JwglWebActivity.DEFAULT_TARGET_URL, "官方教务系统 (jwgl)")
        }

        binding.pageMoreFeatures.rowMoreXgWeb.setOnClickListener {
            JwglWebActivity.start(this, JwglWebActivity.XG_TARGET_URL, "学工一体化 (new2017)")
        }

        binding.pageMoreFeatures.rowMoreLiveWeb.setOnClickListener {
            JwglWebActivity.start(this, JwglWebActivity.LIVE_TARGET_URL, "课程直播与点播平台 (live)")
        }
                        

        // Quick Service Entries in Profile (Bottom Bar Mode)
        binding.btnProfileExamQuery.setOnClickListener {
            switchTab(MainTab.EXAM_QUERY)
        }
        binding.btnProfileGradeReports.setOnClickListener {
            switchTab(MainTab.GRADE_REPORTS)
        }

        binding.btnProfileJwglWeb.setOnClickListener {
            JwglWebActivity.start(this, JwglWebActivity.DEFAULT_TARGET_URL, "官方教务系统 (jwgl)")
        }

        binding.btnProfileXgWeb.setOnClickListener {
            JwglWebActivity.start(this, JwglWebActivity.XG_TARGET_URL, "学工一体化 (new2017)")
        }

        binding.btnProfileLiveWeb.setOnClickListener {
            JwglWebActivity.start(this, JwglWebActivity.LIVE_TARGET_URL, "课程直播与点播平台 (live)")
        }
                                
        // Hamburger Menu Buttons to Open Drawer
        binding.btnOpenDrawerTimetable.setOnClickListener {
            openNavDrawer()
        }
        binding.btnOpenDrawerProfile.setOnClickListener {
            openNavDrawer()
        }

        // Side Navigation Drawer: Main Tabs
        binding.layoutDrawer.cardDrawerProfileHeader.setOnClickListener {
            closeNavDrawer()
            switchTab(MainTab.PROFILE)
        }
        binding.layoutDrawer.drawerNavTimetable.setOnClickListener {
            closeNavDrawer()
            switchTab(MainTab.TIMETABLE)
        }
        binding.layoutDrawer.drawerNavGrades.setOnClickListener {
            closeNavDrawer()
            switchTab(MainTab.GRADES)
        }
        binding.layoutDrawer.drawerNavProfile.setOnClickListener {
            closeNavDrawer()
            switchTab(MainTab.PROFILE)
        }

        // Side Navigation Drawer: School Features (Independent Pages)
        binding.layoutDrawer.drawerItemSchoolSearch.setOnClickListener {
            closeNavDrawer()
            switchTab(MainTab.COURSE_SEARCH)
        }

        binding.layoutDrawer.drawerItemFreeClassroom.setOnClickListener {
            closeNavDrawer()
            switchTab(MainTab.FREE_CLASSROOM)
        }

        binding.layoutDrawer.drawerItemExamQuery.setOnClickListener {
            closeNavDrawer()
            switchTab(MainTab.EXAM_QUERY)
        }

        binding.layoutDrawer.drawerItemGradeReports.setOnClickListener {
            closeNavDrawer()
            switchTab(MainTab.GRADE_REPORTS)
        }

        binding.layoutDrawer.drawerItemJwglWeb.setOnClickListener {
            closeNavDrawer()
            JwglWebActivity.start(this, JwglWebActivity.DEFAULT_TARGET_URL, "官方教务系统 (jwgl)")
        }

        binding.layoutDrawer.drawerItemXgWeb.setOnClickListener {
            closeNavDrawer()
            JwglWebActivity.start(this, JwglWebActivity.XG_TARGET_URL, "学工一体化 (new2017)")
        }

        binding.layoutDrawer.drawerItemLiveWeb.setOnClickListener {
            closeNavDrawer()
            JwglWebActivity.start(this, JwglWebActivity.LIVE_TARGET_URL, "课程直播与点播平台 (live)")
        }


        
        
        
        binding.layoutDrawer.drawerNavTronClass.setOnClickListener {
            closeNavDrawer()
            if (currentTab == MainTab.TRONCLASS && getTronClassFragment()?.hasBackStack() == true) {
                clearTronClassBackStack()
            } else {
                switchTab(MainTab.TRONCLASS)
            }
        }

        // Side Navigation Drawer: Logout
        binding.layoutDrawer.btnDrawerLogout.setOnClickListener {
            closeNavDrawer()
            binding.btnLogout.performClick()
        }

        // Side Navigation Drawer: Switch Account
        binding.layoutDrawer.btnDrawerSwitchAccount.setOnClickListener {
            closeNavDrawer()
            showAccountSwitchBottomSheet()
        }
        binding.layoutDrawer.cardDrawerProfileHeader.setOnClickListener {
            closeNavDrawer()
            showAccountSwitchBottomSheet()
        }

        // Profile Tab: Switch Account Button
        binding.btnSwitchAccount.setOnClickListener {
            showAccountSwitchBottomSheet()
        }

        // Profile Tab: System & Updates card
        binding.btnLogout.setOnClickListener {
            JwglClient.clearSession()
            AuthPreferences(this).clearAutoLogin()
            TronClassPreferences(this).clearSession()
            TronClassDataCacheManager.clearDataCache(this)
            val intent = Intent(this, LoginActivity::class.java).apply {
                putExtra(LoginActivity.EXTRA_FROM_LOGOUT, true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            startActivity(intent)
            finish()
        }

        binding.btnCheckUpdate.setOnClickListener {
            syncRemoteConfig(isManual = true, force = true)
        }

        binding.rowGithubRepo.setOnClickListener {
            val githubUrl = "https://github.com/BigSilverFish/usst-jwgl-client"
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开浏览器: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.rowDisclaimer.setOnClickListener {
            showAuthorDisclaimerDialog(allowDismiss = true)
        }
    }

    private fun showAuthorDisclaimerDialog(allowDismiss: Boolean = false) {
        activeDisclaimerDialog?.dismiss()
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("作者声明")
            .setMessage("本app非官方项目，仅供开发学习，不上传任何数据，不泄露任何隐私。")
            .setCancelable(allowDismiss)
            .setPositiveButton("同意") { dialog, _ ->
                AuthPreferences(this).setDisclaimerAgreed(true)
                activeDisclaimerDialog = null
                dialog.dismiss()
            }
        if (!allowDismiss) {
            builder.setNegativeButton("不同意并退出") { _, _ ->
                finishAffinity()
            }
        } else {
            builder.setNegativeButton("关闭") { _, _ ->
                activeDisclaimerDialog = null
            }
        }
        val dialog = builder.create()
        activeDisclaimerDialog = dialog
        dialog.show()
    }

    private var isSyncingConfig = false

    private fun syncRemoteConfig(isManual: Boolean, force: Boolean = false) {
        if (isSyncingConfig) return
        if (!isManual && !force && !RemoteConfigManager.shouldPerformDailySync(this)) {
            Log.d("MainActivity", "Daily sync already completed today (${RemoteConfigManager.getLastDailySyncDate(this)}), skipping")
            return
        }

        isSyncingConfig = true
        lifecycleScope.launch {
            try {
                if (isManual) {
                    Toast.makeText(this@MainActivity, "正在检查更新并同步云端配置...", Toast.LENGTH_SHORT).show()
                }
                val result = RemoteConfigManager.fetchConfig(this@MainActivity)
                result.onSuccess { config ->
                    RemoteConfigManager.markDailySyncCompleted(this@MainActivity)
                    binding.tvAppVersion.text = "v${BuildConfig.VERSION_NAME}"
                    val semConfig = config.semesterConfig
                    binding.tvSemesterConfigSummary.text = "${semConfig.currentSemester} · 第 1 周 ${semConfig.week1Monday}"

                    // Calibrate all local tables' start dates and weeks
                    val calibrated = RemoteConfigManager.calibrateTablesAndSync(this@MainActivity, config)
                    if (calibrated) {
                        val allTables = db.tableDao.getAllTables()
                        val autoTarget = CourseUtils.findAutoScheduleTarget(allTables)
                        if (autoTarget != null) {
                            db.tableDao.setDefaultTable(autoTarget.table.id)
                            currentTable = autoTarget.table
                            currentWeek = autoTarget.week
                        }
                        reloadTimetableFromDb()
                        Toast.makeText(this@MainActivity, "已根据最新校历与调休安排自动校准课表", Toast.LENGTH_SHORT).show()
                    } else {
                        scheduleAdapter?.refreshAllFragments()
                    }

                    // Update reminders according to holidays & adjustments
                    CourseReminderManager.scheduleUpcomingReminders(this@MainActivity)

                    if (RemoteConfigManager.isUpdateAvailable(BuildConfig.VERSION_CODE)) {
                        showUpdateDialog(config.appVersion, isManual = isManual)
                    } else if (isManual) {
                        Toast.makeText(this@MainActivity, "当前已是最新版本，校历与调休配置已同步", Toast.LENGTH_SHORT).show()
                    } else {
                        // Daily first launch check: if there is an adjustment today, give a friendly reminder
                        val todayStr = RemoteConfigManager.getTodayDateString()
                        val todayAdjustments = RemoteConfigManager.getAdjustmentsForDate(todayStr)
                        if (todayAdjustments.isNotEmpty()) {
                            val adj = todayAdjustments.first()
                            val msg = if (adj.type == "HOLIDAY_OFF") "今日【${adj.name}】放假停课" else "今日调休: ${adj.remark}"
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    }
                }.onFailure { error ->
                    Log.e("MainActivity", "Remote config sync failed", error)
                    if (isManual) {
                        val msg = if (error is com.google.gson.JsonSyntaxException) "云端配置文件格式错误(JSON语法)" else "同步云端配置失败: ${error.message ?: "请检查网络"}"
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                isSyncingConfig = false
            }
        }
    }

    private fun showUpdateDialog(versionInfo: AppVersionInfo, isManual: Boolean = false) {
        UpdateDialogHelper.showUpdateDialog(this, versionInfo, forceShow = isManual)
    }

    private fun MaterialAlertDialogBuilder.safeShow() {
        if (!this@MainActivity.isFinishing && !this@MainActivity.isDestroyed) {
            try {
                show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ==========================================
    // --- 9. TronClass Native Embedded Page ---
    // ==========================================
    private fun updateMoreFeaturesHeaderSpacing(headerHeight: Int) {
        val params = binding.pageMoreFeatures.viewMoreFeaturesHeaderSpacing.layoutParams
        if (params.height != headerHeight) {
            params.height = headerHeight
            binding.pageMoreFeatures.viewMoreFeaturesHeaderSpacing.layoutParams = params
        }
    }

    private fun updateProfileHeaderSpacing(headerHeight: Int) {
        val params = binding.viewProfileHeaderSpacing.layoutParams
        if (params.height != headerHeight) {
            params.height = headerHeight
            binding.viewProfileHeaderSpacing.layoutParams = params
        }
    }

    fun getTronClassFragment(): TronClassFragment? =
        supportFragmentManager.findFragmentByTag("TronClassFragment") as? TronClassFragment

    fun openTronClassCourseDetail(course: TronClassCourse) {
        if (currentTab != MainTab.TRONCLASS) {
            switchTab(MainTab.TRONCLASS)
        }
        supportFragmentManager.executePendingTransactions()
        getTronClassFragment()?.openCourseDetail(course)
    }

    fun openTronClassCourseDetail(courseId: Long, courseName: String) {
        if (currentTab != MainTab.TRONCLASS) {
            switchTab(MainTab.TRONCLASS)
        }
        supportFragmentManager.executePendingTransactions()
        getTronClassFragment()?.openCourseDetail(courseId, courseName)
    }

    fun openTronClassHomework(activityId: Long, courseId: Long, title: String, courseName: String) {
        if (currentTab != MainTab.TRONCLASS) {
            switchTab(MainTab.TRONCLASS)
        }
        supportFragmentManager.executePendingTransactions()
        getTronClassFragment()?.openHomework(activityId, courseId, title, courseName)
    }

    fun openTronClassExam(examId: Long, courseId: Long, title: String, courseName: String) {
        if (currentTab != MainTab.TRONCLASS) {
            switchTab(MainTab.TRONCLASS)
        }
        supportFragmentManager.executePendingTransactions()
        getTronClassFragment()?.openExam(examId, courseId, title, courseName)
    }

    fun openTronClassDownloads() {
        if (currentTab != MainTab.TRONCLASS) {
            switchTab(MainTab.TRONCLASS)
        }
        supportFragmentManager.executePendingTransactions()
        getTronClassFragment()?.openDownloads()
    }

    fun clearTronClassBackStack() {
        getTronClassFragment()?.clearBackStack()
    }












    private fun handleWidgetPinRequest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appWidgetManager = getSystemService(android.appwidget.AppWidgetManager::class.java)
            val provider = android.content.ComponentName(this, cn.edu.usst.jwgl.widget.TodayScheduleWidgetProvider::class.java)
            var pinSent = false
            if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported) {
                val successIntent = Intent(this, cn.edu.usst.jwgl.widget.TodayScheduleWidgetProvider::class.java).apply {
                    action = cn.edu.usst.jwgl.widget.TodayScheduleWidgetProvider.ACTION_REFRESH_WIDGET
                }
                val successPendingIntent = android.app.PendingIntent.getBroadcast(
                    this,
                    0,
                    successIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                pinSent = appWidgetManager.requestPinAppWidget(provider, null, successPendingIntent)
            }
            showWidgetPinGuidanceDialog(pinSent)
        } else {
            showWidgetPinGuidanceDialog(false)
        }
    }

    private fun showWidgetPinGuidanceDialog(autoPinTriggered: Boolean) {
        val msg = StringBuilder()
        if (autoPinTriggered) {
            msg.append("已向系统发送添加桌面微件请求！\n\n")
        } else {
            msg.append("当前手机桌面启动器未支持自动添加微件。\n\n")
        }
        msg.append("📌【国内定制系统（小米/红米 HyperOS、华为鸿蒙、OPPO ColorOS、vivo OriginOS 等）注意事项】\n")
            .append("1. 国产系统为保障桌面安全，默认会静默拦截第三方 App 的微件添加请求，常表现为点击后界面没有任何反应。\n")
            .append("2. 如未弹出系统添加确认框，请点击下方【前往权限设置】开启本应用的“桌面快捷方式”或“添加桌面小部件”权限。\n\n")
            .append("💡【最推荐：桌面手动捏合添加（100%成功）】\n")
            .append("👉 返回手机桌面 -> 双指捏合或长按空白处 -> 点击底部【微件 / 小组件 / 插件】 -> 搜索或下滑找到【上理教务】的【今日课表】拖拽到桌面即可！")

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("添加今日课表小组件")
            .setMessage(msg.toString())
            .setPositiveButton("我知道了", null)
            .setNeutralButton("前往权限设置") { _, _ ->
                openAppPermissionSettings()
            }
            .setNegativeButton("再次尝试自动添加") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val appWidgetManager = getSystemService(android.appwidget.AppWidgetManager::class.java)
                    val provider = android.content.ComponentName(this, cn.edu.usst.jwgl.widget.TodayScheduleWidgetProvider::class.java)
                    if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported) {
                        val successIntent = Intent(this, cn.edu.usst.jwgl.widget.TodayScheduleWidgetProvider::class.java).apply {
                            action = cn.edu.usst.jwgl.widget.TodayScheduleWidgetProvider.ACTION_REFRESH_WIDGET
                        }
                        val successPendingIntent = android.app.PendingIntent.getBroadcast(
                            this,
                            0,
                            successIntent,
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                        )
                        appWidgetManager.requestPinAppWidget(provider, null, successPendingIntent)
                        Toast.makeText(this, "已再次触发系统微件添加请求", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun openAppPermissionSettings() {
        try {
            val miuiIntent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", packageName)
            }
            startActivity(miuiIntent)
            return
        } catch (_: Exception) {}

        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "请前往手机“设置 - 应用设置”开启桌面权限", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAccountSwitchBottomSheet() {
        val sheet = cn.edu.usst.jwgl.ui.account.AccountSwitchBottomSheet()
        sheet.setOnAccountSwitchListener(object : cn.edu.usst.jwgl.ui.account.AccountSwitchBottomSheet.OnAccountSwitchListener {
            override fun onAccountSwitched(studentId: String) {
                handleAccountSwitched(studentId)
            }

            override fun onAllAccountsRemoved() {
                binding.btnLogout.performClick()
            }
        })
        sheet.show(supportFragmentManager, "AccountSwitchBottomSheet")
    }

    private fun handleAccountSwitched(newStudentId: String) {
        lifecycleScope.launch {
            // 1. 刷新个人档案缓存及 UI 展示
            val profile = cacheManager.getProfile(newStudentId)
            currentProfile = profile
            displayProfile(profile)

            // 2. 如果缺少姓名等核心信息，后台静默拉取补全
            if (profile == null || (profile.name.isBlank() && profile.college.isBlank())) {
                val auth = AuthPreferences(this@MainActivity)
                val pwd = auth.getPassword()
                if (pwd.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        try {
                            if (JwglClient.ensureSession(this@MainActivity)) {
                                val healed = JwglClient.fetchStudentProfile(newStudentId)
                                if (healed.name.isNotBlank() || healed.college.isNotBlank()) {
                                    cacheManager.saveProfile(healed, newStudentId)
                                    withContext(Dispatchers.Main) {
                                        currentProfile = healed
                                        displayProfile(healed)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("MainActivity", "Account switched: failed to auto heal profile", e)
                        }
                    }
                }
            }

            // 3. 重新加载课表
            initWakeupSchedule()

            // 4. 刷新成绩 Fragment
            (supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)?.loadGrades(isManual = false)

            // 5. 刷新 TronClass
            getTronClassFragment()?.loadTronClassData(forceRefresh = true, silent = true)
        }
    }
}
