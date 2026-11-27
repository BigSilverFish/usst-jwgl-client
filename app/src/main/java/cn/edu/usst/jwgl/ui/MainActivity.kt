package cn.edu.usst.jwgl.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
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
import cn.edu.usst.jwgl.util.NotificationDiagnosticHelper
import cn.edu.usst.jwgl.util.getFriendlyErrorMessage
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.model.AppVersionInfo
import cn.edu.usst.jwgl.data.model.ExamItem
import cn.edu.usst.jwgl.data.model.GradeDocumentType
import cn.edu.usst.jwgl.data.model.StudentProfile
import cn.edu.usst.jwgl.data.model.UnifiedCourse
import cn.edu.usst.jwgl.data.model.ChaoXingWork
import cn.edu.usst.jwgl.data.model.ChaoXingCourse
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.data.remote.RemoteConfigManager
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.data.wakeup.CourseBean
import cn.edu.usst.jwgl.data.wakeup.CourseUtils
import cn.edu.usst.jwgl.data.wakeup.TableBean
import cn.edu.usst.jwgl.data.wakeup.WakeupScheduleImporter
import cn.edu.usst.jwgl.databinding.ActivityMainBinding
import cn.edu.usst.jwgl.databinding.LayoutPageMoreFeaturesBinding
import cn.edu.usst.jwgl.ui.common.AuthorDisclaimerBottomSheet
import cn.edu.usst.jwgl.ui.grades.GradesFragment
import cn.edu.usst.jwgl.ui.settings.ProfileSettingsFragment
import cn.edu.usst.jwgl.ui.wakeup.AddCourseActivity
import cn.edu.usst.jwgl.ui.wakeup.ScheduleManagerBottomSheet
import cn.edu.usst.jwgl.ui.wakeup.SchedulePagerAdapter
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.TronClassCourse
import cn.edu.usst.jwgl.ui.tronclass.TronClassFragment
import cn.edu.usst.jwgl.worker.RollcallMonitorScheduler
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
import cn.edu.usst.jwgl.util.NotificationUiHelper
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
        const val EXTRA_FAST_PROFILE_HINT = "extra_fast_profile_hint"
        const val EXTRA_TAB = "extra_tab"
        const val EXTRA_GUEST_MODE = "extra_guest_mode"
        private const val KEY_SAVED_TAB_NAME = "key_saved_tab_name"
        var topBarHeightPx: Int = 0
        var bottomBarHeightPx: Int = 0
        private var hasCheckedUpdateOnColdStart: Boolean = false
    }

    internal lateinit var binding: ActivityMainBinding
    internal var moreFeaturesBinding: LayoutPageMoreFeaturesBinding? = null
    private var isTopBarBlurInitialized = false
    private var isBottomNavBlurInitialized = false
    internal var isTabletLandscapeMode: Boolean = false
    internal var timetableLandscapeExtraPadPx: Int = 0
    internal var isGuestMode: Boolean = false
    private lateinit var cacheManager: DataCacheManager
    internal lateinit var db: AppDatabase
    private var currentProfile: StudentProfile? = null

    internal var currentTable: TableBean? = null
        set(value) {
            field = value
            invalidateTableCoursesCache()
        }

    // TD-12: 缓存当前课表的课程列表，避免在滑动周次时频繁在主线程查询 SQLite 造成掉帧卡顿
    @Volatile
    private var cachedTableId: Int? = null
    @Volatile
    private var cachedTableCourses: List<CourseBean>? = null

    internal fun invalidateTableCoursesCache() {
        cachedTableId = null
        cachedTableCourses = null
    }

    internal fun warmTableCoursesCache(tableId: Int, courses: List<CourseBean>) {
        cachedTableId = tableId
        cachedTableCourses = courses
    }

    internal fun getTableCoursesCached(tableId: Int): List<CourseBean> {
        val cached = cachedTableCourses
        if (cachedTableId == tableId && cached != null) {
            return cached
        }
        val courses = db.courseBaseDao.getCourseOfTable(tableId)
        cachedTableId = tableId
        cachedTableCourses = courses
        return courses
    }
    private var isUpdatingChipProgrammatically = false
    internal var scheduleAdapter: SchedulePagerAdapter? = null
    internal var currentWeek: Int = 1
    private var isInitialWeekLocated: Boolean = false
    private var fastInitTableId: Int = -1
    private var fastInitMaxWeek: Int = -1
    private var fastInitStartDate: String = ""
    private var fastInitWeek: Int = -1
    private var fastInitStudentId: String = ""

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
    internal fun getEffectiveCurrentWeek(): Int {
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

    private var activeDisclaimerDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            val baseTopPad = statusBarInsets.top + (8 * density).toInt()
            val baseBottomPad = (6 * density).toInt()
            if (!isTabletLandscapeMode) {
                timetableLandscapeExtraPadPx = 0
            }
            binding.headerTimetable.setPadding(
                binding.headerTimetable.paddingLeft,
                baseTopPad,
                binding.headerTimetable.paddingRight,
                baseBottomPad + timetableLandscapeExtraPadPx
            )
            binding.headerTimetableContainer.post {
                topBarHeightPx = binding.headerTimetableContainer.height
                alignLandscapeTimetableTopBarWithSidebar()
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

            moreFeaturesBinding?.let { mb ->
                mb.llMoreFeaturesHeaderContainer.setPadding(
                    mb.llMoreFeaturesHeaderContainer.paddingLeft,
                    statusTopPad,
                    mb.llMoreFeaturesHeaderContainer.paddingRight,
                    0
                )
                mb.llMoreFeaturesHeaderContainer.post {
                    val h = mb.llMoreFeaturesHeaderContainer.height
                    if (h > 0) updateMoreFeaturesHeaderSpacing(h)
                }
            }

            val drawerStatusTop = if (statusBarInsets.top > 0) statusBarInsets.top else (28 * density).toInt()
            if (binding.layoutDrawer.viewDrawerStatusBarTop.layoutParams.height != drawerStatusTop) {
                binding.layoutDrawer.viewDrawerStatusBarTop.layoutParams.height = drawerStatusTop
                binding.layoutDrawer.viewDrawerStatusBarTop.requestLayout()
            }

            insets
        }

        window.decorView.post {
            if (!isDestroyed) {
                setupMainBlurViews()
            }
        }

        cacheManager = DataCacheManager.getInstance(this)
        db = AppDatabase.getDatabase(this)
        RemoteConfigManager.init(this)
        currentWeek = RemoteConfigManager.getCurrentWeek()

        isGuestMode = intent.getBooleanExtra(EXTRA_GUEST_MODE, false)

        val passedProfile = if (isGuestMode) {
            null
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_PROFILE, StudentProfile::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_PROFILE) as? StudentProfile
        }
        val isFastProfileHint = !isGuestMode && intent.getBooleanExtra(EXTRA_FAST_PROFILE_HINT, false)
        val initialProfile = if (isGuestMode) {
            null
        } else {
            passedProfile ?: cacheManager.getFastProfileHint()
        }
        currentProfile = initialProfile

        displayProfile(initialProfile)

        // 异步水合完整加密学籍档案，并在完整档案为空时触发自愈拉取，避免冷启动主线程阻塞 Keystore IPC
        val needsAsyncProfileHydration = !isGuestMode && (isFastProfileHint || passedProfile == null)
        val needsDirectSelfHeal = !isGuestMode && !needsAsyncProfileHydration &&
            (initialProfile == null || (initialProfile.name.isBlank() && initialProfile.college.isBlank()))
        if (needsAsyncProfileHydration || needsDirectSelfHeal) {
            lifecycleScope.launch(Dispatchers.IO) {
                val targetProfile = if (needsAsyncProfileHydration) {
                    cacheManager.getProfile()?.also { loadedProfile ->
                        withContext(Dispatchers.Main) {
                            currentProfile = loadedProfile
                            displayProfile(loadedProfile)
                        }
                    }
                } else {
                    initialProfile
                }

                if (targetProfile == null || (targetProfile.name.isBlank() && targetProfile.college.isBlank())) {
                    val auth = AuthPreferences(this@MainActivity)
                    val stuId = auth.getStudentId()
                    val stuPwd = auth.getPassword()
                    if (stuId.isNotEmpty() && stuPwd.isNotEmpty()) {
                        try {
                            if (JwglClient.ensureSession(this@MainActivity)) {
                                val healedProfile = JwglClient.fetchStudentProfile(stuId)
                                if (healedProfile.name.isNotBlank() || healedProfile.college.isNotBlank()) {
                                    cacheManager.saveProfile(healedProfile)
                                    withContext(Dispatchers.Main) {
                                        currentProfile = healedProfile
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
        }

        setupTabletLandscapeLayout()
        applyPhoneNavigationMode()
        setupViews()
        setupListeners()
        initWakeupSchedule()
        if (!hasCheckedUpdateOnColdStart) {
            hasCheckedUpdateOnColdStart = true
            syncRemoteConfig(isManual = false, force = true)
        }

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

        // 错峰分步预热：避开冷启动前 1.5 秒核心主线程调度窗口，待系统稳定后轻量分步预热
        scheduleStaggeredWarmup()
    }

    private fun setupMainBlurViews() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!isTopBarBlurInitialized) {
            try {
                val decorView = window.decorView
                val rootView = decorView.findViewById<ViewGroup>(android.R.id.content) ?: (decorView as? ViewGroup) ?: return
                binding.blurViewTopBar.setupWith(rootView, RenderEffectBlur())
                    .setFrameClearDrawable(decorView.background)
                    .setBlurRadius(20f)
                isTopBarBlurInitialized = true
            } catch (_: Exception) {}
        }
        ensureBottomNavBlur()
    }

    internal fun ensureBottomNavBlur() {
        if (isBottomNavBlurInitialized || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (binding.blurViewBottomNav.visibility != View.VISIBLE) return
        try {
            val decorView = window.decorView
            val rootView = decorView.findViewById<ViewGroup>(android.R.id.content) ?: (decorView as? ViewGroup) ?: return
            binding.blurViewBottomNav.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(decorView.background)
                .setBlurRadius(20f)
            isBottomNavBlurInitialized = true
        } catch (_: Exception) {}
    }

    internal fun ensureMoreFeaturesPageInflated(): LayoutPageMoreFeaturesBinding {
        moreFeaturesBinding?.let { return it }
        val inflated = binding.stubMoreFeatures.inflate()
        val mb = LayoutPageMoreFeaturesBinding.bind(inflated)
        moreFeaturesBinding = mb

        mb.llMoreFeaturesHeaderContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0) {
                mb.llMoreFeaturesHeaderContainer.post {
                    if (!isDestroyed) updateMoreFeaturesHeaderSpacing(h)
                }
            }
        }
        mb.llMoreFeaturesHeaderContainer.setPadding(
            mb.llMoreFeaturesHeaderContainer.paddingLeft,
            getStatusTopPad(),
            mb.llMoreFeaturesHeaderContainer.paddingRight,
            0
        )
        mb.llMoreFeaturesHeaderContainer.post {
            val h = mb.llMoreFeaturesHeaderContainer.height
            if (h > 0) updateMoreFeaturesHeaderSpacing(h)
        }
        adaptiveLayoutController.syncMoreFeaturesPageAdaptiveState(mb)
        setupMoreFeaturesPageListeners(mb)
        ensureMoreFeaturesBlur()
        return mb
    }

    internal fun getProfileSettingsFragment(): ProfileSettingsFragment? =
        supportFragmentManager.findFragmentByTag("ProfileSettingsFragment") as? ProfileSettingsFragment

    internal fun notifyProfileSettingsChanged() {
        getProfileSettingsFragment()?.refreshSettingsState()
    }

    internal fun getCurrentStudentProfile(): StudentProfile? = currentProfile

    internal fun onProfileRefreshed(profile: StudentProfile) {
        displayProfile(profile)
    }

    internal fun onDayPartDividersChanged() {
        scheduleAdapter?.refreshAllFragments()
    }

    private fun setupMoreFeaturesPageListeners(mb: LayoutPageMoreFeaturesBinding) {
        mb.btnOpenDrawerMoreFeatures.setOnClickListener { openNavDrawer() }
        mb.rowMoreCourseSearch.setOnClickListener { switchTab(MainTab.COURSE_SEARCH) }
        mb.rowMoreFreeClassroom.setOnClickListener { switchTab(MainTab.FREE_CLASSROOM) }
        mb.rowMoreExamQuery.setOnClickListener { switchTab(MainTab.EXAM_QUERY) }
        mb.rowMoreGradeReports.setOnClickListener { switchTab(MainTab.GRADE_REPORTS) }
        mb.rowMoreWidgetConfig.setOnClickListener {
            startActivity(Intent(this, cn.edu.usst.jwgl.widget.TodayScheduleWidgetConfigActivity::class.java))
        }
        mb.rowMoreJwglWeb.setOnClickListener {
            JwglWebActivity.start(this, JwglWebActivity.DEFAULT_TARGET_URL, "官方教务系统 (jwgl)")
        }
        mb.rowMoreXgWeb.setOnClickListener {
            JwglWebActivity.start(this, JwglWebActivity.XG_TARGET_URL, "学工一体化 (new2017)")
        }
        mb.rowMoreLiveWeb.setOnClickListener {
            JwglWebActivity.start(this, JwglWebActivity.LIVE_TARGET_URL, "课程直播与点播平台 (live)")
        }
    }

    internal fun performLogout() {
        AuthPreferences(this).performFullLogout()
        Toast.makeText(this, "已退出登录并清除本地缓存", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, LoginActivity::class.java).apply {
            putExtra(LoginActivity.EXTRA_FROM_LOGOUT, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private var isMoreFeaturesBlurInitialized = false

    private fun ensureMoreFeaturesBlur() {
        if (isMoreFeaturesBlurInitialized || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val blurView = moreFeaturesBinding?.blurViewMoreFeatures ?: return
        try {
            val decorView = window.decorView
            val rootView = decorView.findViewById<ViewGroup>(android.R.id.content) ?: (decorView as? ViewGroup) ?: return
            blurView.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(decorView.background)
                .setBlurRadius(20f)
            isMoreFeaturesBlurInitialized = true
        } catch (_: Exception) {}
    }

    private fun scheduleStaggeredWarmup() {
        // 第一阶段错峰预热：启动 1.5 秒后（避开冷启动首屏后台 I/O 和用户刚进界面的黄金交互期）预热成绩页
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val fm = supportFragmentManager
            if (fm.findFragmentByTag("GradesFragment") == null) {
                fm.beginTransaction()
                    .add(R.id.containerGrades, GradesFragment.newInstance(), "GradesFragment")
                    .commitAllowingStateLoss()
            }
        }, 1500L)

        // 第二阶段错峰预热：启动 3.0 秒后在单独帧预热一网畅学页 & 同步桌面微件数据
        binding.root.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            val fm = supportFragmentManager
            if (fm.findFragmentByTag("TronClassFragment") == null) {
                fm.beginTransaction()
                    .add(R.id.containerTronclass, TronClassFragment.newInstance(), "TronClassFragment")
                    .commitAllowingStateLoss()
            }
            cn.edu.usst.jwgl.worker.WidgetFeedSyncScheduler.scheduleOrUpdate(this)
            cn.edu.usst.jwgl.worker.WidgetFeedSyncScheduler.triggerImmediateSync(this)
        }, 3000L)
    }

    internal var lastNavBarBottomInsetPx: Int = 0
    internal var lastStatusTopPadPx: Int = 0
    internal var lastStatusBarTopPx: Int = 0

    fun getStatusTopPad(): Int = lastStatusTopPadPx.takeIf { it > 0 } ?: (32 * resources.displayMetrics.density).toInt()
    fun getStatusBarTop(): Int = lastStatusBarTopPx.takeIf { it > 0 } ?: (24 * resources.displayMetrics.density).toInt()
    fun getBottomInset(): Int = lastNavBarBottomInsetPx
    fun isBottomBarModeActive(): Boolean = isPhoneBottomBarMode()
    fun isGuestModeActive(): Boolean = isGuestMode
    fun isTabletLandscapeModeActive(): Boolean = isTabletLandscapeMode
    fun getEffectiveCurrentWeekNumber(): Int = getEffectiveCurrentWeek()

    private val adaptiveLayoutController by lazy { MainAdaptiveLayoutController(this) }
    private val settingsDialogController by lazy { MainSettingsDialogController(this) }

    internal fun isPhoneBottomBarMode(): Boolean = adaptiveLayoutController.isPhoneBottomBarMode()
    private fun applyContentBottomPadding() = adaptiveLayoutController.applyContentBottomPadding()
    internal fun applyPhoneNavigationMode() = adaptiveLayoutController.applyPhoneNavigationMode()
    fun onTimetableEmptySpaceDoubleTapped(): Boolean = adaptiveLayoutController.onTimetableEmptySpaceDoubleTapped()
    fun openNavDrawer() = adaptiveLayoutController.openNavDrawer()
    private fun closeNavDrawer() = adaptiveLayoutController.closeNavDrawer()
    private fun isNavDrawerOpen(): Boolean = adaptiveLayoutController.isNavDrawerOpen()
    private fun setupTabletLandscapeLayout(config: Configuration? = null) = adaptiveLayoutController.setupTabletLandscapeLayout(config)
    private fun alignLandscapeTimetableTopBarWithSidebar() = adaptiveLayoutController.alignLandscapeTimetableTopBarWithSidebar()
    private fun snapSchedulePagerToCurrentItem(targetPos: Int = binding.vpSchedule.currentItem) = adaptiveLayoutController.snapSchedulePagerToCurrentItem(targetPos)
    private fun restorePortraitLayout() = adaptiveLayoutController.restorePortraitLayout()
    override fun onConfigurationChanged(newConfig: Configuration) {
        val preservedSchedulePos = if (isInitialWeekLocated && binding.vpSchedule.adapter != null) {
            binding.vpSchedule.currentItem
        } else {
            -1
        }
        super.onConfigurationChanged(newConfig)

        val isTabletLandscape = TabletLayoutHelper.isTabletLandscape(this, newConfig)
        val modeChanged = isTabletLandscape != isTabletLandscapeMode
        if (modeChanged) {
            if (isTabletLandscape) {
                setupTabletLandscapeLayout(newConfig)
            } else {
                restorePortraitLayout()
            }
        }

        applyPhoneNavigationMode()
        applyContentBottomPadding()

        // 重新请求分发 WindowInsets，更新状态栏与导航栏高度
        ViewCompat.requestApplyInsets(binding.root)

        // 刷新各活跃 Fragment
        (supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)
            ?.updateTabletLandscapeMode(isTabletLandscape)

        (supportFragmentManager.findFragmentByTag("TronClassFragment") as? TronClassFragment)
            ?.let { tf ->
                tf.applyAdaptiveLayout()
                tf.checkAndRefreshHeaderSpacing()
            }

        moreFeaturesBinding?.let { mb ->
            mb.llMoreFeaturesHeaderContainer.post {
                val h = mb.llMoreFeaturesHeaderContainer.height
                if (h > 0) updateMoreFeaturesHeaderSpacing(h)
            }
        }
        getProfileSettingsFragment()?.let { pf ->
            pf.updateTabletLandscapeMode(isTabletLandscape)
            pf.syncAdaptiveState()
        }

        // 刷新课表周页面排版与内边距
        binding.vpSchedule.post {
            if (preservedSchedulePos >= 0) {
                snapSchedulePagerToCurrentItem(preservedSchedulePos)
            } else {
                snapSchedulePagerToCurrentItem()
            }
        }
        binding.headerTimetableContainer.post { alignLandscapeTimetableTopBarWithSidebar() }
        supportFragmentManager.fragments.filterIsInstance<cn.edu.usst.jwgl.ui.wakeup.ScheduleWeekFragment>()
            .forEach {
                it.applyContentPadding()
                it.view?.post { if (it.isAdded) it.renderSchedule() }
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SAVED_TAB_NAME, (currentTab ?: MainTab.TIMETABLE).name)
    }

    private var isFirstResumeAfterCreate = true

    override fun onResume() {
        super.onResume()
        val isFirstResume = isFirstResumeAfterCreate
        isFirstResumeAfterCreate = false

        if (!isFirstResume) {
            reloadTimetableFromDb()
        }
        cn.edu.usst.jwgl.devaddon.DevAddonManager.refreshIfPackageChanged(this)
        notifyProfileSettingsChanged()
        ApkDownloadHelper.pendingInstallApkFile?.let { apk ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
                ApkDownloadHelper.pendingInstallApkFile = null
                ApkDownloadHelper.installApk(this, apk)
            }
        }
        if (RemoteConfigManager.isUpdateAvailable(BuildConfig.VERSION_CODE) &&
            RemoteConfigManager.getLatestVersion().forceUpdate
        ) {
            showUpdateDialog(RemoteConfigManager.getLatestVersion(), isManual = false)
        } else if (RemoteConfigManager.shouldPerformDailySync(this)) {
            syncRemoteConfig(isManual = false)
        }
        lifecycleScope.launch(Dispatchers.IO) {
            if (TronClassPreferences.getInstance(this@MainActivity).isRollcallMonitorEnabled()) {
                RollcallMonitorScheduler.checkAndSchedule(this@MainActivity)
            }
            if (!isFirstResume) {
                cn.edu.usst.jwgl.widget.TodayScheduleWidgetProvider.updateAllWidgets(this@MainActivity)
                cn.edu.usst.jwgl.widget.NextCourseWidgetProvider.updateAllWidgets(this@MainActivity)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val appCtx = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            cn.edu.usst.jwgl.widget.TodayScheduleWidgetProvider.updateAllWidgets(appCtx)
            cn.edu.usst.jwgl.widget.NextCourseWidgetProvider.updateAllWidgets(appCtx)
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
        if (tab in 0..8) {
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
                8 -> switchTab(MainTab.TRONCLASS)
            }
        }

        if (intent.getBooleanExtra("extra_open_radar_rollcall", false)) {
            val rId = intent.getLongExtra("extra_rollcall_id", 0L)
            val cName = intent.getStringExtra("extra_course_name")
            val room = intent.getStringExtra("extra_classroom")
            val tLat = if (intent.hasExtra("extra_latitude")) intent.getDoubleExtra("extra_latitude", 0.0).takeIf { it != 0.0 } else null
            val tLng = if (intent.hasExtra("extra_longitude")) intent.getDoubleExtra("extra_longitude", 0.0).takeIf { it != 0.0 } else null
            val tScope = if (intent.hasExtra("extra_scope")) intent.getDoubleExtra("extra_scope", 0.0).takeIf { it != 0.0 } else null
            switchTab(MainTab.TRONCLASS)
            binding.root.postDelayed({
                cn.edu.usst.jwgl.ui.tronclass.RadarRollcallBottomSheetDialog.newInstance(
                    rollcallId = rId,
                    courseName = cName,
                    classroom = room,
                    teacherLat = tLat,
                    teacherLng = tLng,
                    teacherScope = tScope
                ).show(supportFragmentManager, "RadarRollcallBottomSheet")
            }, 250)
        }

        if (intent.getBooleanExtra("extra_open_number_rollcall", false)) {
            val rId = intent.getLongExtra("extra_rollcall_id", 0L)
            val cName = intent.getStringExtra("extra_course_name")
            switchTab(MainTab.TRONCLASS)
            binding.root.postDelayed({
                cn.edu.usst.jwgl.ui.tronclass.NumberRollcallBottomSheetDialog.newInstance(rId, cName)
                    .show(supportFragmentManager, "NumberRollcallBottomSheet")
            }, 250)
        }

        if (intent.getBooleanExtra("extra_open_qr_scan", false)) {
            switchTab(MainTab.TRONCLASS)
            binding.root.postDelayed({
                getTronClassFragment()?.startTronClassQrScan()
            }, 300)
        }

        if (intent.getBooleanExtra("extra_open_chaoxing_sign", false)) {
            val cxCourseId = intent.getStringExtra("extra_cx_course_id").orEmpty()
            val cxClassId = intent.getStringExtra("extra_cx_class_id").orEmpty()
            val cxActiveId = intent.getLongExtra("extra_cx_active_id", 0L)
            val cxCourseName = intent.getStringExtra("extra_cx_course_name")
            val cxSignType = intent.getStringExtra("extra_cx_sign_type")
            val cxOtherId = intent.getIntExtra("extra_cx_other_id", -1)
            switchTab(MainTab.TRONCLASS)
            if (cxCourseId.isNotBlank() && cxClassId.isNotBlank() && cxActiveId > 0L) {
                binding.root.postDelayed({
                    getTronClassFragment()?.openChaoXingSignFromAlert(
                        courseId = cxCourseId,
                        classId = cxClassId,
                        activeId = cxActiveId,
                        courseName = cxCourseName,
                        signTypeName = cxSignType,
                        otherId = cxOtherId
                    )
                }, 300)
            }
        }

        if (intent.getBooleanExtra("extra_trigger_smart_rollcall", false)) {
            switchTab(MainTab.TRONCLASS)
            binding.root.postDelayed({
                getTronClassFragment()?.triggerSmartRollcall()
            }, 300)
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
            if (currentTab != MainTab.PROFILE) {
                switchTab(MainTab.PROFILE)
            }
            supportFragmentManager.executePendingTransactions()
            getProfileSettingsFragment()?.scrollToBottom()
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
            scheduleAdapter?.refreshAllFragments()
            notifyProfileSettingsChanged()
        }

        if (intent.hasExtra("extra_nav_mode")) {
            val mode = intent.getIntExtra("extra_nav_mode", TimetableSettingHelper.NAV_MODE_BOTTOM)
            TimetableSettingHelper.setPhoneNavMode(this, mode)
            applyPhoneNavigationMode()
        }

        if (intent.hasExtra("extra_double_tap_drawer")) {
            val enabled = intent.getBooleanExtra("extra_double_tap_drawer", true)
            TimetableSettingHelper.setDoubleTapOpenDrawerEnabled(this, enabled)
            notifyProfileSettingsChanged()
        }

        if (intent.getBooleanExtra("extra_open_drawer", false)) {
            openNavDrawer()
        }

        val widgetClickType = intent.getStringExtra(cn.edu.usst.jwgl.widget.TodayScheduleWidgetService.EXTRA_WIDGET_CLICK_TYPE)
        if (widgetClickType == cn.edu.usst.jwgl.widget.TodayScheduleWidgetService.CLICK_TYPE_TODO) {
            val platform = intent.getStringExtra(cn.edu.usst.jwgl.widget.TodayScheduleWidgetService.EXTRA_PLATFORM)
            val todoId = intent.getStringExtra(cn.edu.usst.jwgl.widget.TodayScheduleWidgetService.EXTRA_TODO_ID).orEmpty()
            val courseId = intent.getStringExtra(cn.edu.usst.jwgl.widget.TodayScheduleWidgetService.EXTRA_COURSE_ID).orEmpty()
            val classId = intent.getStringExtra(cn.edu.usst.jwgl.widget.TodayScheduleWidgetService.EXTRA_CLASS_ID).orEmpty()
            val title = intent.getStringExtra(cn.edu.usst.jwgl.widget.TodayScheduleWidgetService.EXTRA_TITLE).orEmpty()
            val courseName = intent.getStringExtra(cn.edu.usst.jwgl.widget.TodayScheduleWidgetService.EXTRA_COURSE_NAME).orEmpty()
            val detailUrl = intent.getStringExtra(cn.edu.usst.jwgl.widget.TodayScheduleWidgetService.EXTRA_DETAIL_URL).orEmpty()

            if (platform == cn.edu.usst.jwgl.data.model.LearningPlatform.CHAOXING.name) {
                val safeUrl = detailUrl.ifBlank {
                    "https://mooc1.chaoxing.com/work/phone/work?courseId=$courseId&classId=$classId&workId=$todoId"
                }
                JwglWebActivity.start(this, safeUrl, title.ifBlank { "学习通作业" })
            } else {
                val actId = todoId.toLongOrNull() ?: 0L
                val cid = courseId.toLongOrNull() ?: 0L
                if (actId > 0L) {
                    val hwIntent = Intent(this, cn.edu.usst.jwgl.ui.tronclass.TronClassHomeworkActivity::class.java).apply {
                        putExtra("extra_activity_id", actId)
                        putExtra("extra_course_id", cid)
                        putExtra("extra_title", title)
                        putExtra("extra_course_name", courseName)
                    }
                    startActivity(hwIntent)
                } else {
                    switchTab(MainTab.TRONCLASS)
                }
            }
        } else if (widgetClickType == cn.edu.usst.jwgl.widget.TodayScheduleWidgetService.CLICK_TYPE_NOTICE) {
            switchTab(MainTab.TRONCLASS)
            val bulletinId = intent.getStringExtra(cn.edu.usst.jwgl.widget.TodayScheduleWidgetService.EXTRA_BULLETIN_ID)
            if (!bulletinId.isNullOrBlank()) {
                binding.root.postDelayed({
                    getTronClassFragment()?.openBulletinFromWidget(bulletinId)
                }, 350)
            }
        }

        if (intent.getBooleanExtra("extra_set_disclaimer_agreed", false)) {
            AuthPreferences(this).setDisclaimerAgreed(true)
            (supportFragmentManager.findFragmentByTag(AuthorDisclaimerBottomSheet.TAG) as? AuthorDisclaimerBottomSheet)?.dismiss()
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

    fun getDatabaseInstance(): AppDatabase = db

    private fun fastInitTimetableFromPref() {
        val currentSid = AuthPreferences(this).getStudentId()
        val fastTableId = TimetableSettingHelper.getLastActiveTableId(this, currentSid)
        val fastTableName = TimetableSettingHelper.getLastActiveTableName(this, currentSid)
        val fastMaxWeek = TimetableSettingHelper.getLastActiveMaxWeek(this, currentSid)
        val fastStartDate = TimetableSettingHelper.getLastActiveStartDate(this, currentSid)

        if (fastTableId > 0 && fastMaxWeek > 0 && fastStartDate.isNotBlank()) {
            val calcWeek = CourseUtils.countWeek(fastStartDate)
            val determinedWeek = calcWeek.coerceIn(1, fastMaxWeek)
            currentWeek = determinedWeek
            isInitialWeekLocated = true
            fastInitTableId = fastTableId
            fastInitMaxWeek = fastMaxWeek
            fastInitStartDate = fastStartDate
            fastInitWeek = determinedWeek
            fastInitStudentId = currentSid

            binding.tvTimetableSemester.text = formatSemesterTitle(fastTableName)
            updateWeekSelectionUI(determinedWeek)
            updateWeekHeaderDates(determinedWeek)

            scheduleAdapter = SchedulePagerAdapter(this@MainActivity, fastMaxWeek, fastTableId, currentSid)
            binding.vpSchedule.adapter = scheduleAdapter
            binding.vpSchedule.setCurrentItem(determinedWeek - 1, false)
            binding.vpSchedule.post {
                if (!isDestroyed && binding.vpSchedule.offscreenPageLimit != 1) {
                    binding.vpSchedule.offscreenPageLimit = 1
                }
            }

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
        }
    }

    private fun initWakeupSchedule() {
        fastInitTimetableFromPref()

        lifecycleScope.launch {
            val initData = withContext(Dispatchers.IO) {
                val currentSid = AuthPreferences(this@MainActivity).getStudentId()
                db = AppDatabase.getDatabase(this@MainActivity, currentSid)
                db.ensureLatestTimeTableAndDefaults()
                val defaultT = db.tableDao.getDefaultTable()

                // Seamless auto-import of pre-existing cached timetables into WakeUP database (only once)
                if (!TimetableSettingHelper.hasMigratedLegacyTimetables(this@MainActivity, currentSid)) {
                    try {
                        val cachedList = cacheManager.getAllCachedTimetables(currentSid)
                        if (cachedList.isNotEmpty() && db.courseBaseDao.getCourseOfTable(defaultT.id).isEmpty()) {
                            for (cached in cachedList) {
                                val resolvedStartDate = RemoteConfigManager.resolveStartDateForTimetable(cached)
                                WakeupScheduleImporter.importTimetableData(db, cached, resolvedStartDate)
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("MainActivity", "Legacy timetable migration skipped due to incompatible cache", e)
                    } finally {
                        TimetableSettingHelper.setHasMigratedLegacyTimetables(this@MainActivity, true, currentSid)
                    }
                }

                // Calibrate all local tables' startDate before auto-locating active semester
                try {
                    RemoteConfigManager.calibrateTablesAndSync(this@MainActivity, RemoteConfigManager.getConfig(), db)
                } catch (_: Exception) {
                }

                // 确保课表管理中包含下学期（非默认，依然保持本学期默认）
                SemesterHelper.ensureNextSemesterTable(db)

                // Auto determine active semester & week on startup
                val allTables = db.tableDao.getAllTables()
                val autoTarget = CourseUtils.findAutoScheduleTarget(allTables)
                val targetTable = autoTarget?.table ?: db.tableDao.getDefaultTable()
                val targetWeek = autoTarget?.week ?: 1

                db.tableDao.setDefaultTable(targetTable.id)

                // 持久化当前课表配置供下次冷启动 0ms 秒级直出
                TimetableSettingHelper.saveLastActiveTableInfo(
                    this@MainActivity,
                    targetTable.id,
                    targetTable.tableName,
                    targetTable.maxWeek,
                    targetTable.startDate,
                    currentSid
                )

                val preloadedCourses = db.courseBaseDao.getCourseOfTable(targetTable.id)
                Pair(Triple(targetTable, targetWeek, targetTable.maxWeek), preloadedCourses)
            }

            val (tableTriple, preloadedCourses) = initData
            val (targetTable, targetWeek, maxWeek) = tableTriple
            currentTable = targetTable
            warmTableCoursesCache(targetTable.id, preloadedCourses)
            val realCurrentWeek = CourseUtils.countWeek(targetTable.startDate)
            val determinedWeek = if (targetWeek in 1..maxWeek) targetWeek else if (realCurrentWeek in 1..maxWeek) realCurrentWeek else 1
            currentWeek = determinedWeek

            val adapter = scheduleAdapter
            val activeSid = AuthPreferences(this@MainActivity).getStudentId()
            val fastInitMatched = adapter != null &&
                fastInitTableId == targetTable.id &&
                fastInitMaxWeek == maxWeek &&
                fastInitStartDate == targetTable.startDate &&
                fastInitWeek == determinedWeek &&
                fastInitStudentId == activeSid

            if (adapter == null) {
                scheduleAdapter = SchedulePagerAdapter(this@MainActivity, maxWeek, targetTable.id, activeSid)
                binding.vpSchedule.adapter = scheduleAdapter
                binding.vpSchedule.setCurrentItem(determinedWeek - 1, false)
                binding.vpSchedule.post {
                    if (!isDestroyed && binding.vpSchedule.offscreenPageLimit != 1) {
                        binding.vpSchedule.offscreenPageLimit = 1
                    }
                }

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
            } else if (fastInitMatched) {
                binding.tvTimetableSemester.text = formatSemesterTitle(targetTable.tableName)
                isUpdatingChipProgrammatically = true
                binding.chipFilterOnlyCurrentWeek.isChecked = !targetTable.showOtherWeekCourse
                isUpdatingChipProgrammatically = false
                updateWeekSelectionUI(determinedWeek)
                updateWeekHeaderDates(determinedWeek)
                binding.vpSchedule.post {
                    if (!isDestroyed) {
                        if (binding.vpSchedule.offscreenPageLimit != 1) {
                            binding.vpSchedule.offscreenPageLimit = 1
                        }
                        cn.edu.usst.jwgl.widget.TodayScheduleWidgetProvider.updateAllWidgets(this@MainActivity)
                        cn.edu.usst.jwgl.widget.NextCourseWidgetProvider.updateAllWidgets(this@MainActivity)
                    }
                }
            } else {
                adapter.updateConfig(maxWeek, targetTable.id, activeSid)
                binding.vpSchedule.setCurrentItem(determinedWeek - 1, false)
                reloadTimetableFromDb(initialWeek = determinedWeek, forceWeek = true)
            }
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
            invalidateTableCoursesCache()
            val table = withContext(Dispatchers.IO) {
                db.tableDao.getDefaultTable()
            }
            currentTable = table
            TimetableSettingHelper.saveLastActiveTableInfo(
                this@MainActivity,
                table.id,
                table.tableName,
                table.maxWeek,
                table.startDate
            )

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

            val activeSid = AuthPreferences(this@MainActivity).getStudentId()
            scheduleAdapter?.updateConfig(table.maxWeek, table.id, activeSid)

            binding.vpSchedule.setCurrentItem(currentWeek - 1, false)
            updateWeekSelectionUI(currentWeek)
            updateWeekHeaderDates(currentWeek)

            scheduleAdapter?.refreshAllFragments()
            cn.edu.usst.jwgl.widget.TodayScheduleWidgetProvider.updateAllWidgets(this@MainActivity)
            cn.edu.usst.jwgl.widget.NextCourseWidgetProvider.updateAllWidgets(this@MainActivity)
        }
    }

    internal fun updateWeekSelectionUI(week: Int) {
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
    internal var currentTab: MainTab? = null

    fun switchTab(tab: MainTab, force: Boolean = false) {
        if (!force && currentTab == tab) {
            // 已在当前Tab时拦截多余重绘；若点击课表Tab则平滑滚回当前实际学周
            if (tab == MainTab.TIMETABLE) {
                val effectiveWeek = getEffectiveCurrentWeek()
                if (currentWeek != effectiveWeek && effectiveWeek in 1..(currentTable?.maxWeek ?: 25)) {
                    binding.vpSchedule.setCurrentItem(effectiveWeek - 1, true)
                }
            }
            return
        }
        val prevTab = currentTab
        currentTab = tab

        if (!isPhoneBottomBarMode() || isNavDrawerOpen()) {
            updateDrawerActiveTab(tab)
        }
        updateBottomNavActiveTab(tab)

        val targetView = when (tab) {
            MainTab.TIMETABLE -> binding.layoutTimetable
            MainTab.GRADES -> binding.containerGrades
            MainTab.PROFILE -> binding.containerProfile
            MainTab.MORE_FEATURES -> ensureMoreFeaturesPageInflated().layoutMoreFeatures
            MainTab.COURSE_SEARCH -> binding.containerCourseSearch
            MainTab.FREE_CLASSROOM -> binding.containerFreeClassroom
            MainTab.EXAM_QUERY -> binding.containerExamQuery
            MainTab.GRADE_REPORTS -> binding.containerGradeReports
            MainTab.TRONCLASS -> binding.containerTronclass
        }

        val allContainers = listOfNotNull(
            binding.layoutTimetable,
            binding.containerGrades,
            binding.containerProfile,
            moreFeaturesBinding?.layoutMoreFeatures,
            binding.containerCourseSearch,
            binding.containerFreeClassroom,
            binding.containerExamQuery,
            binding.containerGradeReports,
            binding.containerTronclass
        )

        // 仅对状态发生改变的视图执行 setVisibility，避免对整个视图树触发级联重排测量
        allContainers.forEach { v ->
            val shouldBeVisible = (v == targetView)
            val isCurrentlyVisible = (v.visibility == View.VISIBLE)
            if (shouldBeVisible != isCurrentlyVisible) {
                v.visibility = if (shouldBeVisible) View.VISIBLE else View.GONE
            }
        }

        when (tab) {
            MainTab.TIMETABLE -> {
                // 优化：视图直接秒显，不调用 reloadTimetableFromDb()，彻底消除重新查库与全量重绘 ViewPager Fragment 带来的卡顿
            }
            MainTab.GRADES -> {
                val gf = supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment
                if (gf == null) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.containerGrades, GradesFragment.newInstance(), "GradesFragment")
                        .commitAllowingStateLoss()
                } else {
                    gf.syncDrawerButtonVisibility()
                }
            }
            MainTab.TRONCLASS -> {
                val tf = supportFragmentManager.findFragmentByTag("TronClassFragment") as? TronClassFragment
                if (tf == null) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.containerTronclass, TronClassFragment.newInstance(), "TronClassFragment")
                        .commitAllowingStateLoss()
                } else {
                    tf.onTabSelected()
                }
            }
            MainTab.MORE_FEATURES -> {
                ensureMoreFeaturesBlur()
                moreFeaturesBinding?.llMoreFeaturesHeaderContainer?.post {
                    val h = moreFeaturesBinding?.llMoreFeaturesHeaderContainer?.height ?: 0
                    if (h > 0) updateMoreFeaturesHeaderSpacing(h)
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
                val pf = getProfileSettingsFragment()
                if (pf == null) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.containerProfile, ProfileSettingsFragment.newInstance(), "ProfileSettingsFragment")
                        .commitAllowingStateLoss()
                } else {
                    pf.onTabSelected()
                }
            }
        }
    }

    private fun updateBottomNavActiveTab(tab: MainTab) = adaptiveLayoutController.updateBottomNavActiveTab(tab)
    private fun updateDrawerActiveTab(tab: MainTab) = adaptiveLayoutController.updateDrawerActiveTab(tab)
    private fun setupViews() {
        updateDrawerProfileHeaderVisibility()

        binding.headerTimetableContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0 && h != topBarHeightPx) {
                topBarHeightPx = h
                binding.headerTimetableContainer.post {
                    if (!isDestroyed) {
                        supportFragmentManager.fragments
                            .filterIsInstance<cn.edu.usst.jwgl.ui.wakeup.ScheduleWeekFragment>()
                            .forEach { it.applyContentPadding() }
                    }
                }
            }
            if (isTabletLandscapeMode) {
                binding.headerTimetableContainer.post { alignLandscapeTimetableTopBarWithSidebar() }
            }
        }

        binding.layoutDrawer.dividerDrawerProfileHeader.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (isTabletLandscapeMode) {
                binding.headerTimetableContainer.post { alignLandscapeTimetableTopBarWithSidebar() }
            }
        }

        binding.vpSchedule.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft && right - left > 0) {
                binding.vpSchedule.post { snapSchedulePagerToCurrentItem() }
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
                cacheManager.saveTimetable(xnm, xqm, data)
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

    private fun showWeekPickerDialog() = settingsDialogController.showWeekPickerDialog()
    internal fun showFontSizeDialog() = settingsDialogController.showFontSizeDialog()
    internal fun updateWeekHeaderDates(week: Int) {
        val table = currentTable ?: return
        val dateStrings = CourseUtils.getDateStringFromWeek(table.startDate, week, table.sundayFirst)
        val todayWeekday = CourseUtils.getTodayWeekdayInt()
        val curWeek = CourseUtils.countWeek(table.startDate)
        val isExamWeek = ExamHelper.isExamWeek(table.tableName, week)
        val cachedExams = if (isExamWeek) cacheManager.getAllCachedExams() else emptyList()
        val allCourses = getTableCoursesCached(table.id)

        binding.tvMonthHeader.text = if (isExamWeek) "${dateStrings[0]}\n月\n[考]" else "${dateStrings[0]}\n月"
        val isSysDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val isDark = isSysDark || ThemeManager.getThemeMode(this) == ThemeManager.MODE_DARK
        binding.tvMonthHeader.setTextColor(if (isDark) Color.parseColor("#94A3B8") else androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary))

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
                if (isDark) Color.WHITE else androidx.core.content.ContextCompat.getColor(this, R.color.primary)
            } else if (resolved.isHolidayOff) {
                0xFF4CAF50.toInt()
            } else {
                if (isDark) Color.parseColor("#CBD5E1") else androidx.core.content.ContextCompat.getColor(this, R.color.text_primary)
            }

            val dateStr = if (i + 1 < dateStrings.size) dateStrings[i + 1] else ""
            val dayDateText = "$dateStr 日"
            val dayDateColor = if (isToday) {
                if (isDark) Color.parseColor("#FFD8D6") else androidx.core.content.ContextCompat.getColor(this, R.color.primary)
            } else {
                if (isDark) Color.parseColor("#64748B") else androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary)
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

            if (isToday) {
                val density = resources.displayMetrics.density
                val shape = android.graphics.drawable.GradientDrawable().apply {
                    if (isDark) {
                        setColor(Color.parseColor("#661D24")) // 典雅的高对比暗红胶囊底色
                        setStroke((1 * density).toInt().coerceAtLeast(1), Color.parseColor("#8C2B35"))
                    } else {
                        setColor(Color.parseColor("#FFDAD8"))
                    }
                    cornerRadius = 10 * density
                }
                dayView.background = shape
                val vPad = (2 * density).toInt()
                dayView.setPadding(0, vPad, 0, vPad)
            } else {
                dayView.background = null
                dayView.setPadding(0, 0, 0, 0)
            }

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

    internal fun updateDrawerProfileHeaderVisibility() {
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

        // 抽屉栏头部版本与学号信息立即可见
        binding.layoutDrawer.tvDrawerVersion.text = "USST JWGL · v${BuildConfig.VERSION_NAME}"
        updateDrawerProfileHeaderVisibility()

        getProfileSettingsFragment()?.updateProfileData(profile)
    }

    internal fun showDarkModeDialog() = settingsDialogController.showDarkModeDialog()
    internal fun showPhoneNavModeDialog() = settingsDialogController.showPhoneNavModeDialog()
    internal fun showCourseReminderAdvanceDialog() = settingsDialogController.showCourseReminderAdvanceDialog()
    internal fun showExamReminderAdvanceDialog() = settingsDialogController.showExamReminderAdvanceDialog()
    internal fun showTronClassDdlUrgentAdvanceDialog() = settingsDialogController.showTronClassDdlUrgentAdvanceDialog()
    internal fun showRollcallAlertModeDialog() = settingsDialogController.showRollcallAlertModeDialog()
    internal fun showRollcallAutoSignDelayDialog() = settingsDialogController.showRollcallAutoSignDelayDialog()
    internal fun showNotificationSettingsDialog() = settingsDialogController.showNotificationSettingsDialog()
    internal fun showLiveFrameTestDialog() = settingsDialogController.showLiveFrameTestDialog()

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
        // Drawer visibility & slide state handling
        binding.drawerLayout.addDrawerListener(object : androidx.drawerlayout.widget.DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                if (!isTabletLandscapeMode && !isPhoneBottomBarMode()) {
                    if (slideOffset > 0.001f) {
                        if (drawerView.visibility != View.VISIBLE) {
                            drawerView.visibility = View.VISIBLE
                        }
                    } else {
                        if (drawerView.visibility != View.GONE) {
                            drawerView.visibility = View.GONE
                        }
                    }
                }
            }
            override fun onDrawerStateChanged(newState: Int) {
                if (newState != androidx.drawerlayout.widget.DrawerLayout.STATE_IDLE && !isTabletLandscapeMode && !isPhoneBottomBarMode()) {
                    val dv = binding.layoutDrawer.root
                    if (dv.parent == binding.drawerLayout && dv.visibility != View.VISIBLE) {
                        dv.visibility = View.VISIBLE
                    }
                }
            }
            override fun onDrawerClosed(drawerView: View) {
                if (!isTabletLandscapeMode) {
                    drawerView.visibility = View.GONE
                }
            }
        })

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

        // Custom 5-Tab Bottom Navigation Bar (Phone Bottom Mode)
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

        // Hamburger Menu Button to Open Drawer
        binding.btnOpenDrawerTimetable.setOnClickListener {
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

        binding.layoutDrawer.drawerItemWidgetConfig.setOnClickListener {
            closeNavDrawer()
            val intent = Intent(this, cn.edu.usst.jwgl.widget.TodayScheduleWidgetConfigActivity::class.java)
            startActivity(intent)
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
            performLogout()
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
    }

    internal fun showAuthorDisclaimerDialog(allowDismiss: Boolean = false) = settingsDialogController.showAuthorDisclaimerDialog(allowDismiss)
    internal fun syncRemoteConfig(isManual: Boolean, force: Boolean = false) = settingsDialogController.syncRemoteConfig(isManual, force)
    private fun showUpdateDialog(versionInfo: AppVersionInfo, isManual: Boolean = false) = settingsDialogController.showUpdateDialog(versionInfo, isManual)

    // ==========================================
    // --- 9. TronClass Native Embedded Page ---
    // ==========================================
    private fun updateMoreFeaturesHeaderSpacing(headerHeight: Int) {
        if (headerHeight <= 0) return
        val spacer = moreFeaturesBinding?.viewMoreFeaturesHeaderSpacing ?: return
        val params = spacer.layoutParams
        if (params.height != headerHeight) {
            params.height = headerHeight
            spacer.layoutParams = params
            spacer.requestLayout()
        }
    }

    fun getTronClassFragment(): TronClassFragment? =
        supportFragmentManager.findFragmentByTag("TronClassFragment") as? TronClassFragment

    fun openTronClassCourseDetail(course: UnifiedCourse) {
        if (currentTab != MainTab.TRONCLASS) {
            switchTab(MainTab.TRONCLASS)
        }
        supportFragmentManager.executePendingTransactions()
        getTronClassFragment()?.openCourseDetail(course)
    }

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

    fun openChaoXingHomework(work: ChaoXingWork, course: ChaoXingCourse? = null) {
        if (currentTab != MainTab.TRONCLASS) {
            switchTab(MainTab.TRONCLASS)
        }
        supportFragmentManager.executePendingTransactions()
        getTronClassFragment()?.openChaoXingHomework(work, course)
    }

    fun openTronClassExam(examId: Long, courseId: Long, title: String, courseName: String) {
        if (currentTab != MainTab.TRONCLASS) {
            switchTab(MainTab.TRONCLASS)
        }
        supportFragmentManager.executePendingTransactions()
        getTronClassFragment()?.openExam(examId, courseId, title, courseName)
    }

    fun openChaoXingExam(exam: cn.edu.usst.jwgl.data.model.ChaoXingExam, course: cn.edu.usst.jwgl.data.model.ChaoXingCourse? = null) {
        if (currentTab != MainTab.TRONCLASS) {
            switchTab(MainTab.TRONCLASS)
        }
        supportFragmentManager.executePendingTransactions()
        getTronClassFragment()?.openChaoXingExam(exam, course)
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












    internal fun showAccountSwitchBottomSheet() {
        val sheet = cn.edu.usst.jwgl.ui.account.AccountSwitchBottomSheet()
        sheet.setOnAccountSwitchListener(object : cn.edu.usst.jwgl.ui.account.AccountSwitchBottomSheet.OnAccountSwitchListener {
            override fun onAccountSwitched(studentId: String) {
                handleAccountSwitched(studentId)
            }

            override fun onAllAccountsRemoved() {
                performLogout()
            }
        })
        sheet.show(supportFragmentManager, "AccountSwitchBottomSheet")
    }

    private fun handleAccountSwitched(newStudentId: String) {
        lifecycleScope.launch {
            // 0. 清除启动 Intent 中残留的上一个登录者的 StudentProfile 缓存
            intent?.removeExtra(EXTRA_PROFILE)

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

            // 3. 彻底重置课表 ViewPager2 适配器及缓存，切换数据库为新账号专属库并重新加载课表
            binding.vpSchedule.adapter = null
            scheduleAdapter = null
            currentTable = null
            isInitialWeekLocated = false
            invalidateTableCoursesCache()

            withContext(Dispatchers.IO) {
                db = AppDatabase.getDatabase(this@MainActivity, newStudentId)
                db.ensureLatestTimeTableAndDefaults()
                val existing = db.tableDao.getAllTables()
                if (existing.isEmpty() || existing.all { db.courseBaseDao.getCourseOfTable(it.id).isEmpty() }) {
                    val cachedList = cacheManager.getAllCachedTimetables(newStudentId)
                    if (cachedList.isNotEmpty()) {
                        for (cached in cachedList) {
                            val resolvedStartDate = RemoteConfigManager.resolveStartDateForTimetable(cached)
                            WakeupScheduleImporter.importTimetableData(db, cached, resolvedStartDate)
                        }
                    }
                }
            }
            initWakeupSchedule()
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    CourseReminderManager.rescheduleAllForActiveAccount(this@MainActivity)
                    cn.edu.usst.jwgl.worker.RollcallMonitorScheduler.scheduleFromPreferences(this@MainActivity)
                    cn.edu.usst.jwgl.worker.TronClassDdlScheduler.scheduleFromPreferences(this@MainActivity)
                } catch (e: Exception) {
                    Log.w("MainActivity", "Failed to reschedule reminders on account switch", e)
                }
            }

            // 4. 刷新成绩 Fragment
            (supportFragmentManager.findFragmentByTag("GradesFragment") as? GradesFragment)?.loadGrades(isManual = false)

            // 5. 刷新 TronClass 与超星学习通
            getTronClassFragment()?.onAccountSwitched(newStudentId)

            // 6. 核心：账号切换后自动静默联网拉取新账号排课，确保课表跟着自动切换
            autoSyncTimetableForSwitchedAccount(newStudentId)
        }
    }

    private fun autoSyncTimetableForSwitchedAccount(studentId: String) {
        lifecycleScope.launch {
            val semConfig = RemoteConfigManager.getSemesterConfig()
            val parsed = SemesterHelper.parseSemesterFromTableName(currentTable?.tableName)
            val (xnm, xqm) = parsed ?: SemesterHelper.getCurrentSemester()

            binding.timetableProgressBar.visibility = View.VISIBLE
            val res = withContext(Dispatchers.IO) {
                try {
                    if (JwglClient.ensureSession(this@MainActivity)) {
                        JwglClient.fetchTimetable(xnm, xqm, context = this@MainActivity)
                    } else {
                        Result.failure(Exception("无法建立教务会话"))
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            binding.timetableProgressBar.visibility = View.GONE

            res.onSuccess { data ->
                val activeSid = AuthPreferences(this@MainActivity).getStudentId()
                if (activeSid != studentId) return@onSuccess

                val targetDb = AppDatabase.getDatabase(this@MainActivity, studentId)
                val startDate = semConfig.week1Monday.ifBlank { "2026-09-07" }
                val importedTable = WakeupScheduleImporter.importTimetableData(
                    db = targetDb,
                    data = data,
                    startDate = startDate,
                    setAsDefault = true
                )
                cacheManager.saveTimetable(xnm, xqm, data, studentId)
                withContext(Dispatchers.IO) {
                    targetDb.tableDao.setDefaultTable(importedTable.id)
                }
                currentTable = importedTable
                invalidateTableCoursesCache()
                scheduleAdapter?.updateConfig(importedTable.maxWeek, importedTable.id, studentId)
                reloadTimetableFromDb(forceWeek = true)
                binding.tvTimetableSyncTime.text = "已联网同步 · 刚刚"
                withContext(Dispatchers.IO) {
                    try {
                        CourseReminderManager.scheduleUpcomingReminders(this@MainActivity)
                        cn.edu.usst.jwgl.worker.RollcallMonitorScheduler.scheduleFromPreferences(this@MainActivity)
                    } catch (e: Exception) {
                        Log.w("MainActivity", "Failed to schedule reminders after switched timetable sync", e)
                    }
                }
            }.onFailure { e ->
                Log.w("MainActivity", "Auto sync timetable failed for $studentId: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}