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
import cn.edu.usst.jwgl.data.model.AppVersionInfo
import cn.edu.usst.jwgl.data.model.ExamItem
import cn.edu.usst.jwgl.data.model.GradeDocumentType
import cn.edu.usst.jwgl.data.model.GradeReport
import cn.edu.usst.jwgl.data.model.StudentProfile
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.data.remote.RemoteConfigManager
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.data.wakeup.CourseConflictInfo
import cn.edu.usst.jwgl.data.wakeup.CourseUtils
import cn.edu.usst.jwgl.data.wakeup.TableBean
import cn.edu.usst.jwgl.data.wakeup.WakeupScheduleImporter
import cn.edu.usst.jwgl.databinding.ActivityMainBinding
import cn.edu.usst.jwgl.ui.adapter.CourseGradeAdapter
import cn.edu.usst.jwgl.ui.wakeup.AddCourseActivity
import cn.edu.usst.jwgl.ui.wakeup.ScheduleManagerBottomSheet
import cn.edu.usst.jwgl.ui.wakeup.SchedulePagerAdapter
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
import cn.edu.usst.jwgl.data.model.MergedSchoolCourse
import cn.edu.usst.jwgl.data.network.FreeClassroomInfo
import cn.edu.usst.jwgl.data.network.LappClient
import cn.edu.usst.jwgl.ui.exam.ExamAdapter
import cn.edu.usst.jwgl.ui.school.FreeClassroomAdapter
import cn.edu.usst.jwgl.ui.school.SchoolCourseAdapter
import cn.edu.usst.jwgl.ui.school.CourseSyllabusBottomSheet
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
        const val EXTRA_PROFILE = "extra_student_profile"
        const val EXTRA_TAB = "extra_tab"
        const val EXTRA_GUEST_MODE = "extra_guest_mode"
        private const val KEY_SAVED_TAB_NAME = "key_saved_tab_name"
        var topBarHeightPx: Int = 0
        var bottomBarHeightPx: Int = 0
    }

    private lateinit var binding: ActivityMainBinding
    private var isTabletLandscapeMode: Boolean = false
    private var isGuestMode: Boolean = false
    private lateinit var cacheManager: DataCacheManager
    private lateinit var db: AppDatabase
    private val gradeAdapter = CourseGradeAdapter()
    private var gradeReport: GradeReport? = null
    private var selectedGradeSemesterTitle: String = ""
    private var currentProfile: StudentProfile? = null

    internal var currentTable: TableBean? = null
    private var isUpdatingChipProgrammatically = false
    private var scheduleAdapter: SchedulePagerAdapter? = null
    private var currentWeek: Int = 1
    private var isInitialWeekLocated: Boolean = false
    private var currentExamList: List<ExamItem>? = null

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

    private enum class ReminderType { COURSE, EXAM }
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
            binding.headerGrades.setPadding(
                binding.headerGrades.paddingLeft,
                statusBarInsets.top + (8 * density).toInt(),
                binding.headerGrades.paddingRight,
                (6 * density).toInt()
            )

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

            // Adjust grades spacing view to match floating header height
            binding.llGradesHeaderContainer.post {
                val gradesHeaderH = binding.llGradesHeaderContainer.height
                if (gradesHeaderH > 0) {
                    updateGradesHeaderSpacing(gradesHeaderH)
                }
            }

            val statusTopPad = statusBarInsets.top + (8 * density).toInt()
            lastStatusTopPadPx = statusTopPad

            applyContentBottomPadding()

            // Independent feature pages status bar top padding
            binding.pageCourseSearch.headerCourseSearch.setPadding(
                binding.pageCourseSearch.headerCourseSearch.paddingLeft,
                statusTopPad,
                binding.pageCourseSearch.headerCourseSearch.paddingRight,
                (10 * density).toInt()
            )
            binding.pageFreeClassroom.headerFreeClassroom.setPadding(
                binding.pageFreeClassroom.headerFreeClassroom.paddingLeft,
                statusTopPad,
                binding.pageFreeClassroom.headerFreeClassroom.paddingRight,
                (10 * density).toInt()
            )
            binding.pageExamQuery.headerExamQuery.setPadding(
                binding.pageExamQuery.headerExamQuery.paddingLeft,
                statusTopPad,
                binding.pageExamQuery.headerExamQuery.paddingRight,
                (10 * density).toInt()
            )
            binding.pageGradeReports.headerGradeReports.setPadding(
                binding.pageGradeReports.headerGradeReports.paddingLeft,
                statusTopPad,
                binding.pageGradeReports.headerGradeReports.paddingRight,
                (10 * density).toInt()
            )

            val drawerStatusTop = if (statusBarInsets.top > 0) statusBarInsets.top else (28 * density).toInt()
            if (binding.layoutDrawer.viewDrawerStatusBarTop.layoutParams.height != drawerStatusTop) {
                binding.layoutDrawer.viewDrawerStatusBarTop.layoutParams.height = drawerStatusTop
                binding.layoutDrawer.viewDrawerStatusBarTop.requestLayout()
            }
            binding.layoutDrawer.cardDrawerProfileHeader.setPadding(
                binding.layoutDrawer.cardDrawerProfileHeader.paddingLeft,
                drawerStatusTop + (6 * density).toInt(),
                binding.layoutDrawer.cardDrawerProfileHeader.paddingRight,
                (14 * density).toInt()
            )

            insets
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val decorView = window.decorView
            val rootView = decorView.findViewById<ViewGroup>(android.R.id.content)
            val windowBackground = decorView.background

            binding.blurViewTopBar.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)

            binding.blurViewGradesHeader.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)

            binding.blurViewBottomNav.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)

            binding.pageCourseSearch.blurViewCourseSearch.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)

            binding.pageFreeClassroom.blurViewFreeClassroom.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)

            binding.pageExamQuery.blurViewExamQuery.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)

            binding.pageGradeReports.blurViewGradeReports.setupWith(rootView, RenderEffectBlur())
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
            val initialTab = intent.getIntExtra(EXTRA_TAB, 1) // Default to 1 (课程表)
            when (initialTab) {
                0 -> switchTab(MainTab.PROFILE)
                1 -> switchTab(MainTab.TIMETABLE)
                2 -> switchTab(MainTab.GRADES)
                3 -> switchTab(MainTab.COURSE_SEARCH)
                4 -> switchTab(MainTab.FREE_CLASSROOM)
                5 -> switchTab(MainTab.EXAM_QUERY)
                6 -> switchTab(MainTab.GRADE_REPORTS)
                7 -> switchTab(MainTab.MORE_FEATURES)
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
    }

    private var lastNavBarBottomInsetPx: Int = 0
    private var lastStatusTopPadPx: Int = 0

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
            statusTopPad,
            binding.scrollProfile.paddingRight,
            bottomBarHeightPx
        )
        binding.pageMoreFeatures.llMoreFeaturesContent.setPadding(
            binding.pageMoreFeatures.llMoreFeaturesContent.paddingLeft,
            statusTopPad,
            binding.pageMoreFeatures.llMoreFeaturesContent.paddingRight,
            bottomBarHeightPx
        )
        binding.scrollGrades.setPadding(
            binding.scrollGrades.paddingLeft,
            binding.scrollGrades.paddingTop,
            binding.scrollGrades.paddingRight,
            bottomBarHeightPx
        )
        binding.pageCourseSearch.rvCourseList.setPadding(
            binding.pageCourseSearch.rvCourseList.paddingLeft,
            binding.pageCourseSearch.rvCourseList.paddingTop,
            binding.pageCourseSearch.rvCourseList.paddingRight,
            bottomBarHeightPx
        )
        binding.pageFreeClassroom.rvFreeClassroomList.setPadding(
            binding.pageFreeClassroom.rvFreeClassroomList.paddingLeft,
            binding.pageFreeClassroom.rvFreeClassroomList.paddingTop,
            binding.pageFreeClassroom.rvFreeClassroomList.paddingRight,
            bottomBarHeightPx
        )
        binding.pageExamQuery.rvExamQueryList.setPadding(
            binding.pageExamQuery.rvExamQueryList.paddingLeft,
            binding.pageExamQuery.rvExamQueryList.paddingTop,
            binding.pageExamQuery.rvExamQueryList.paddingRight,
            bottomBarHeightPx
        )
        binding.pageGradeReports.scrollGradeReports.setPadding(
            binding.pageGradeReports.scrollGradeReports.paddingLeft,
            binding.pageGradeReports.scrollGradeReports.paddingTop,
            binding.pageGradeReports.scrollGradeReports.paddingRight,
            bottomBarHeightPx
        )

        supportFragmentManager.fragments.filterIsInstance<cn.edu.usst.jwgl.ui.wakeup.ScheduleWeekFragment>()
            .forEach { it.applyContentPadding() }
    }

    private fun applyPhoneNavigationMode() {
        if (isGuestMode) {
            closeNavDrawer()
            binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            binding.blurViewBottomNav.visibility = View.GONE
            binding.btnOpenDrawerTimetable.visibility = View.GONE
            binding.btnOpenDrawerGrades.visibility = View.GONE
            binding.btnOpenDrawerProfile.visibility = View.GONE

            binding.pageCourseSearch.btnOpenDrawerCourseSearch.visibility = View.VISIBLE
            binding.pageCourseSearch.btnOpenDrawerCourseSearch.setImageResource(R.drawable.ic_arrow_back)
            binding.pageFreeClassroom.btnOpenDrawerFreeClassroom.visibility = View.VISIBLE
            binding.pageFreeClassroom.btnOpenDrawerFreeClassroom.setImageResource(R.drawable.ic_arrow_back)
            binding.pageExamQuery.btnOpenDrawerExamQuery.visibility = View.VISIBLE
            binding.pageExamQuery.btnOpenDrawerExamQuery.setImageResource(R.drawable.ic_arrow_back)
            binding.pageGradeReports.btnOpenDrawerGradeReports.visibility = View.VISIBLE
            binding.pageGradeReports.btnOpenDrawerGradeReports.setImageResource(R.drawable.ic_arrow_back)

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
            val drawerView = binding.layoutDrawer.root
            (drawerView.parent as? ViewGroup)?.removeView(drawerView)
            binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            binding.blurViewBottomNav.visibility = View.VISIBLE

            // Hide top-left drawer hamburger icons on the main bottom-bar pages
            binding.btnOpenDrawerTimetable.visibility = View.GONE
            binding.btnOpenDrawerGrades.visibility = View.GONE
            binding.btnOpenDrawerProfile.visibility = View.GONE

            // All 4 school features (全校查课 / 空教室 / 考试日程 / 成绩单与证明) are under "更多功能" with a Back arrow
            binding.pageCourseSearch.btnOpenDrawerCourseSearch.visibility = View.VISIBLE
            binding.pageCourseSearch.btnOpenDrawerCourseSearch.setImageResource(R.drawable.ic_arrow_back)
            binding.pageFreeClassroom.btnOpenDrawerFreeClassroom.visibility = View.VISIBLE
            binding.pageFreeClassroom.btnOpenDrawerFreeClassroom.setImageResource(R.drawable.ic_arrow_back)
            binding.pageExamQuery.btnOpenDrawerExamQuery.visibility = View.VISIBLE
            binding.pageExamQuery.btnOpenDrawerExamQuery.setImageResource(R.drawable.ic_arrow_back)
            binding.pageGradeReports.btnOpenDrawerGradeReports.visibility = View.VISIBLE
            binding.pageGradeReports.btnOpenDrawerGradeReports.setImageResource(R.drawable.ic_arrow_back)

            // Double-tap drawer option is only applicable in Sidebar mode
            binding.rowDoubleTapDrawer.visibility = View.GONE
            binding.dividerDoubleTapDrawer.visibility = View.GONE
        } else {
            val drawerView = binding.layoutDrawer.root
            if (drawerView.parent != binding.drawerLayout) {
                (drawerView.parent as? ViewGroup)?.removeView(drawerView)
                drawerView.translationX = 0f
                drawerView.translationY = 0f
                drawerView.visibility = View.VISIBLE
                val lp = androidx.drawerlayout.widget.DrawerLayout.LayoutParams(
                    (310 * resources.displayMetrics.density).toInt(),
                    ViewGroup.LayoutParams.MATCH_PARENT
                ).apply {
                    gravity = GravityCompat.START
                }
                binding.drawerLayout.addView(drawerView, lp)
            }
            binding.drawerLayout.setDrawerLockMode(androidx.drawerlayout.widget.DrawerLayout.LOCK_MODE_UNLOCKED)
            binding.drawerLayout.closeDrawer(GravityCompat.START, false)
            binding.blurViewBottomNav.visibility = View.GONE

            binding.btnOpenDrawerTimetable.visibility = View.VISIBLE
            binding.btnOpenDrawerGrades.visibility = View.VISIBLE
            binding.btnOpenDrawerProfile.visibility = View.VISIBLE
            binding.pageCourseSearch.btnOpenDrawerCourseSearch.visibility = View.VISIBLE
            binding.pageCourseSearch.btnOpenDrawerCourseSearch.setImageResource(R.drawable.ic_menu)
            binding.pageFreeClassroom.btnOpenDrawerFreeClassroom.visibility = View.VISIBLE
            binding.pageFreeClassroom.btnOpenDrawerFreeClassroom.setImageResource(R.drawable.ic_menu)
            binding.pageExamQuery.btnOpenDrawerExamQuery.visibility = View.VISIBLE
            binding.pageExamQuery.btnOpenDrawerExamQuery.setImageResource(R.drawable.ic_menu)
            binding.pageGradeReports.btnOpenDrawerGradeReports.visibility = View.VISIBLE
            binding.pageGradeReports.btnOpenDrawerGradeReports.setImageResource(R.drawable.ic_menu)

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

    private fun openNavDrawer() {
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

    private fun setupTabletLandscapeLayout(config: Configuration? = null) {
        isTabletLandscapeMode = TabletLayoutHelper.isTabletLandscape(this, config)
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
        binding.btnOpenDrawerGrades.visibility = View.GONE
        binding.btnOpenDrawerProfile.visibility = View.GONE
        binding.pageCourseSearch.btnOpenDrawerCourseSearch.visibility = View.GONE
        binding.pageFreeClassroom.btnOpenDrawerFreeClassroom.visibility = View.GONE
        binding.pageExamQuery.btnOpenDrawerExamQuery.visibility = View.GONE
        binding.pageGradeReports.btnOpenDrawerGradeReports.visibility = View.GONE

        // 2. 顶部检索栏单行化 (Compact Single-Row Filter Bars in Landscape)
        // 2a. 全校课表检索栏单行化
        val csPage = binding.pageCourseSearch
        csPage.cardCourseSearchFilter.orientation = LinearLayout.HORIZONTAL
        csPage.cardCourseSearchFilter.gravity = android.view.Gravity.CENTER_VERTICAL
        csPage.rowCourseSearchInputs.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.35f
        )
        csPage.rowCourseSearchActions.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1.0f
        ).apply {
            topMargin = 0
            marginStart = dp(10f)
        }

        // 2b. 空教室查询检索栏单行化
        val fcPage = binding.pageFreeClassroom
        fcPage.cardFreeClassroomFilter.orientation = LinearLayout.HORIZONTAL
        fcPage.cardFreeClassroomFilter.gravity = android.view.Gravity.CENTER_VERTICAL
        fcPage.rowFreeClassroom1.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            3f
        )
        fcPage.rowFreeClassroom2.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            2f
        ).apply {
            topMargin = 0
            marginStart = dp(6f)
        }

        // 3. 双栏主从视图 (Two-Pane Master-Detail Layouts)
        // 3a. 学业情况查询 (左：学分绩点总览 + 历年学期切换卡片；右：双列课程成绩网格)
        binding.llGradesBody.orientation = LinearLayout.HORIZONTAL
        binding.llGradesLeftPane.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            0.36f
        ).apply {
            marginEnd = dp(14f)
        }
        binding.llGradesRightPane.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            0.64f
        )
        (binding.rvGrades.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
        binding.cardGradesSemesterMaster.visibility = View.VISIBLE

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

        // 3c. 学业证明与成绩单 (左：官方可下载证明；右：本地已下载文件管理)
        val grPage = binding.pageGradeReports
        grPage.llReportsBody.orientation = LinearLayout.HORIZONTAL
        grPage.llReportsLeftPane.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            0.54f
        ).apply {
            marginEnd = dp(16f)
        }
        grPage.llReportsRightPane.layoutParams = LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            0.46f
        )
    }

    private fun restorePortraitLayout() {
        isTabletLandscapeMode = false
        val dp = { v: Float -> TabletLayoutHelper.dpToPx(this, v) }

        // 1. Restore Sidebar
        val drawerView = binding.layoutDrawer.root
        (drawerView.parent as? ViewGroup)?.removeView(drawerView)
        binding.permanentSidebarContainer.visibility = View.GONE
        binding.viewPermanentSidebarDivider.visibility = View.GONE

        // 2. 顶部检索栏恢复纵向单列
        val csPage = binding.pageCourseSearch
        csPage.cardCourseSearchFilter.orientation = LinearLayout.VERTICAL
        csPage.rowCourseSearchInputs.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        csPage.rowCourseSearchActions.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(8f)
            marginStart = 0
        }

        val fcPage = binding.pageFreeClassroom
        fcPage.cardFreeClassroomFilter.orientation = LinearLayout.VERTICAL
        fcPage.rowFreeClassroom1.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        fcPage.rowFreeClassroom2.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(6f)
            marginStart = 0
        }

        // 3. Restore two-pane to vertical
        binding.llGradesBody.orientation = LinearLayout.VERTICAL
        binding.llGradesLeftPane.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = 0
        }
        binding.llGradesRightPane.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        (binding.rvGrades.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8f)
        binding.cardGradesSemesterMaster.visibility = View.GONE

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

        val grPage = binding.pageGradeReports
        grPage.llReportsBody.orientation = LinearLayout.VERTICAL
        grPage.llReportsLeftPane.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = 0
        }
        grPage.llReportsRightPane.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // 4. Restore list manager
        if (binding.rvGrades.adapter != null) {
            binding.rvGrades.layoutManager = LinearLayoutManager(this)
        }
        if (csPage.rvCourseList.adapter != null) {
            csPage.rvCourseList.layoutManager = LinearLayoutManager(this)
        }
        if (fcPage.rvFreeClassroomList.adapter != null) {
            fcPage.rvFreeClassroomList.layoutManager = LinearLayoutManager(this)
        }
        if (binding.pageExamQuery.rvExamQueryList.adapter != null) {
            binding.pageExamQuery.rvExamQueryList.layoutManager = LinearLayoutManager(this)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val isTabletLandscape = TabletLayoutHelper.isTabletLandscape(this, newConfig)
        if (isTabletLandscape != isTabletLandscapeMode) {
            if (isTabletLandscape) {
                setupTabletLandscapeLayout(newConfig)
            } else {
                restorePortraitLayout()
            }
            applyPhoneNavigationMode()
            applyContentBottomPadding()
        }

        val curPage = binding.vpSchedule.currentItem
        binding.vpSchedule.post {
            val rv = binding.vpSchedule.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
            rv?.scrollToPosition(curPage)
            binding.vpSchedule.post {
                binding.vpSchedule.setCurrentItem(curPage, false)
                scheduleAdapter?.refreshAllFragments()
                updateWeekHeaderDates(curPage + 1)
            }
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
            initCourseSearchPage()
            binding.pageCourseSearch.etSearchCourse.setText(searchCourse)
            binding.pageCourseSearch.btnDoSearch.postDelayed({
                binding.pageCourseSearch.btnDoSearch.performClick()
            }, 300)
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
            selectedGradeSemesterTitle = when (gradeSem) {
                "ALL" -> ""
                "FIRST" -> gradeReport?.semesters?.firstOrNull()?.semesterTitle ?: ""
                else -> gradeReport?.semesters?.find { it.semesterTitle.contains(gradeSem) }?.semesterTitle ?: gradeSem
            }
            gradeReport?.let { updateGradesUI(it) }
        }

        if (intent.getBooleanExtra("extra_open_exam", false)) {
            showExamQueryBottomSheet()
        }

        if (intent.getBooleanExtra("extra_show_grade_picker", false)) {
            showGradeSemesterPicker()
        }

        if (intent.getBooleanExtra("extra_show_download_dialog", false)) {
            showDownloadGradeReportsDialog()
        }

        if (intent.getBooleanExtra("extra_show_week_picker", false)) {
            showWeekPickerDialog()
        }

        if (intent.getBooleanExtra("extra_open_jwgl_web", false)) {
            JwglWebActivity.start(this)
        }

        if (intent.getBooleanExtra("extra_open_xg_web", false)) {
            JwglWebActivity.start(this, "https://new2017.webvpn.usst.edu.cn/StuDefault.aspx", "学工一体化")
        }

        if (intent.getBooleanExtra("extra_open_live_web", false)) {
            JwglWebActivity.start(this, JwglWebActivity.LIVE_TARGET_URL, "课程直播与点播")
        }

        if (intent.getBooleanExtra("extra_open_ywcx_web", false)) {
            JwglWebActivity.start(this, JwglWebActivity.YWCX_TARGET_URL, "一网畅学平台 (TronClass)")
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
                checkAndLoadDefaultCourseSearch(isUserInitiatedReset = true)
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

            binding.vpSchedule.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                val oldWidth = oldRight - oldLeft
                val newWidth = right - left
                if (oldWidth > 0 && newWidth > 0 && oldWidth != newWidth) {
                    val curPage = binding.vpSchedule.currentItem
                    val rv = binding.vpSchedule.getChildAt(0) as? androidx.recyclerview.widget.RecyclerView
                    rv?.scrollToPosition(curPage)
                    v.post {
                        binding.vpSchedule.setCurrentItem(curPage, false)
                        scheduleAdapter?.refreshAllFragments()
                        updateWeekHeaderDates(curPage + 1)
                    }
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
        MORE_FEATURES,
        COURSE_SEARCH,
        FREE_CLASSROOM,
        EXAM_QUERY,
        GRADE_REPORTS,
        PROFILE
    }
    private var currentTab: MainTab = MainTab.TIMETABLE

    private fun switchTab(tab: MainTab) {
        currentTab = tab
        updateDrawerActiveTab(tab)
        updateBottomNavActiveTab(tab)

        binding.layoutTimetable.visibility = if (tab == MainTab.TIMETABLE) View.VISIBLE else View.GONE
        binding.layoutGrades.visibility = if (tab == MainTab.GRADES) View.VISIBLE else View.GONE
        binding.layoutProfile.visibility = if (tab == MainTab.PROFILE) View.VISIBLE else View.GONE
        binding.pageMoreFeatures.layoutMoreFeatures.visibility = if (tab == MainTab.MORE_FEATURES) View.VISIBLE else View.GONE
        binding.pageCourseSearch.layoutCourseSearch.visibility = if (tab == MainTab.COURSE_SEARCH) View.VISIBLE else View.GONE
        binding.pageFreeClassroom.layoutFreeClassroom.visibility = if (tab == MainTab.FREE_CLASSROOM) View.VISIBLE else View.GONE
        binding.pageExamQuery.layoutExamQuery.visibility = if (tab == MainTab.EXAM_QUERY) View.VISIBLE else View.GONE
        binding.pageGradeReports.layoutGradeReports.visibility = if (tab == MainTab.GRADE_REPORTS) View.VISIBLE else View.GONE

        when (tab) {
            MainTab.TIMETABLE -> reloadTimetableFromDb()
            MainTab.GRADES -> {
                binding.llGradesHeaderContainer.post {
                    updateGradesHeaderSpacing(binding.llGradesHeaderContainer.height)
                }
                if (gradeReport == null) {
                    showOrLoadGrades()
                }
            }
            MainTab.MORE_FEATURES -> {}
            MainTab.COURSE_SEARCH -> {
                initCourseSearchPage()
                checkAndLoadDefaultCourseSearch()
                binding.pageCourseSearch.llCourseSearchHeaderContainer.post {
                    updateCourseSearchHeaderSpacing(binding.pageCourseSearch.llCourseSearchHeaderContainer.height)
                }
            }
            MainTab.FREE_CLASSROOM -> {
                initFreeClassroomPage()
                binding.pageFreeClassroom.llFreeClassroomHeaderContainer.post {
                    updateFreeClassroomHeaderSpacing(binding.pageFreeClassroom.llFreeClassroomHeaderContainer.height)
                }
            }
            MainTab.EXAM_QUERY -> {
                initExamQueryPage()
                binding.pageExamQuery.llExamQueryHeaderContainer.post {
                    updateExamQueryHeaderSpacing(binding.pageExamQuery.llExamQueryHeaderContainer.height)
                }
            }
            MainTab.GRADE_REPORTS -> {
                initGradeReportsPage()
                binding.pageGradeReports.llGradeReportsHeaderContainer.post {
                    updateGradeReportsHeaderSpacing(binding.pageGradeReports.llGradeReportsHeaderContainer.height)
                }
            }
            MainTab.PROFILE -> {}
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
        if (isTabletLandscapeMode) {
            binding.rvGrades.layoutManager = GridLayoutManager(this, 2)
            val gapPx = (5 * resources.displayMetrics.density).toInt()
            binding.rvGrades.addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: android.graphics.Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    val pos = parent.getChildAdapterPosition(view)
                    if (pos == RecyclerView.NO_POSITION) return
                    if (pos % 2 == 0) {
                        outRect.right = gapPx
                    } else {
                        outRect.left = gapPx
                    }
                }
            })
        } else {
            binding.rvGrades.layoutManager = LinearLayoutManager(this)
        }
        binding.rvGrades.adapter = gradeAdapter

        binding.headerTimetableContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0) {
                topBarHeightPx = h
            }
        }

        binding.llGradesHeaderContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0) {
                updateGradesHeaderSpacing(h)
            }
        }

        binding.pageCourseSearch.llCourseSearchHeaderContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0) {
                updateCourseSearchHeaderSpacing(h)
            }
        }

        binding.pageFreeClassroom.llFreeClassroomHeaderContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0) {
                updateFreeClassroomHeaderSpacing(h)
            }
        }

        binding.pageExamQuery.llExamQueryHeaderContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0) {
                updateExamQueryHeaderSpacing(h)
            }
        }

        binding.pageGradeReports.llGradeReportsHeaderContainer.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val h = bottom - top
            if (h > 0) {
                updateGradeReportsHeaderSpacing(h)
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

        // Grades Actions
        binding.swipeRefreshGrades.setColorSchemeResources(R.color.primary)
        binding.swipeRefreshGrades.setOnRefreshListener { loadGrades(isManual = true) }
        binding.btnRefreshGrades.setOnClickListener { loadGrades(isManual = true) }
        binding.btnSelectGradeSemester.setOnClickListener { showGradeSemesterPicker() }
        binding.btnDownloadGradeReports.setOnClickListener { switchTab(MainTab.GRADE_REPORTS) }

        // Profile Refresh
        binding.layoutProfile.setColorSchemeResources(R.color.primary)
        binding.layoutProfile.setOnRefreshListener { refreshProfile() }

        // Dark Mode
        binding.rowDarkMode.setOnClickListener { showDarkModeDialog() }

        // Course Reminder Row & Switch
        binding.rowCourseReminder.setOnClickListener {
            showCourseReminderAdvanceDialog()
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

        binding.switchDrawerProfileHeader.isChecked = TimetableSettingHelper.isDrawerProfileHeaderEnabled(this)
        binding.rowDrawerProfileHeader.setOnClickListener {
            binding.switchDrawerProfileHeader.toggle()
        }
        binding.switchDrawerProfileHeader.setOnCheckedChangeListener { _, isChecked ->
            TimetableSettingHelper.setDrawerProfileHeaderEnabled(this, isChecked)
            updateDrawerProfileHeaderVisibility()
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
            if (profile != null) {
                binding.layoutDrawer.tvDrawerStudentName.text = "${profile.name}同学"
                binding.layoutDrawer.tvDrawerStudentId.text = "学号: ${profile.studentId}"
                val avatarChar = profile.name.takeIf { it.isNotEmpty() }?.substring(0, 1) ?: "理"
                binding.layoutDrawer.tvDrawerAvatar.text = avatarChar
                val collegeMajor = listOfNotNull(profile.college.takeIf { it.isNotBlank() }, profile.major.takeIf { it.isNotBlank() }).joinToString(" · ")
                binding.layoutDrawer.tvDrawerCollegeMajor.text = collegeMajor.ifBlank { "上海理工大学" }
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
        binding.switchPowerfulReminder.isChecked = CourseReminderManager.isPowerfulReminderEnabled(this)
        binding.switchDayPartDividers.isChecked = TimetableSettingHelper.isDayPartDividersEnabled(this)
        binding.switchDoubleTapDrawer.isChecked = TimetableSettingHelper.isDoubleTapOpenDrawerEnabled(this)
        binding.switchDrawerProfileHeader.isChecked = TimetableSettingHelper.isDrawerProfileHeaderEnabled(this)
        updateReminderSubtitles()
        updateDrawerProfileHeaderVisibility()

        // Update Drawer Version
        binding.layoutDrawer.tvDrawerVersion.text = "USST JWGL · v${BuildConfig.VERSION_NAME}"

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

        // Populate Drawer Student Info
        binding.layoutDrawer.tvDrawerStudentName.text = studentName
        binding.layoutDrawer.tvDrawerStudentId.text = "学号: ${profile.studentId.ifEmpty { "--" }}"
        val col = profile.college.ifEmpty { "上海理工大学" }
        val maj = profile.major.ifEmpty { "" }
        binding.layoutDrawer.tvDrawerCollegeMajor.text = if (maj.isNotEmpty()) "$col · $maj" else col
        binding.layoutDrawer.tvDrawerAvatar.text = if (studentName.isNotEmpty()) studentName.take(1) else "理"
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

    private fun showOrLoadGrades() {
        val cached = cacheManager.getGrades()
        if (cached != null) {
            gradeReport = cached
            displayGrades(cached)
            val updateTime = cacheManager.getGradesUpdateTime()
            binding.tvGradesSyncTime.text = cacheManager.formatUpdateTime(updateTime)
        } else {
            loadGrades(isManual = false)
        }
    }

    private fun loadGrades(isManual: Boolean = false) {
        if (isManual) {
            binding.swipeRefreshGrades.isRefreshing = true
        } else if (gradeReport == null) {
            binding.gradesProgressBar.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            val result = JwglClient.fetchGrades(context = this@MainActivity)
            binding.gradesProgressBar.visibility = View.GONE
            binding.swipeRefreshGrades.isRefreshing = false

            result.onSuccess { report ->
                gradeReport = report
                cacheManager.saveGrades(report)
                displayGrades(report)
                val updateTime = System.currentTimeMillis()
                binding.tvGradesSyncTime.text = cacheManager.formatUpdateTime(updateTime)
                if (isManual) {
                    Toast.makeText(this@MainActivity, "成绩单已联网刷新", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                val reason = error.message ?: "未知网络异常"
                Toast.makeText(this@MainActivity, "成绩联网刷新失败: $reason", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshProfile() {
        binding.layoutProfile.isRefreshing = true
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
                binding.layoutProfile.isRefreshing = false
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

    private fun showGradeSemesterPicker() {
        val report = gradeReport ?: return
        val semesters = report.semesters
        if (semesters.isEmpty()) {
            Toast.makeText(this, "暂无学期成绩数据", Toast.LENGTH_SHORT).show()
            return
        }

        val options = mutableListOf<String>()
        options.add("全部学期 (汇总)")
        for (sem in semesters) {
            options.add(sem.semesterTitle)
        }

        val checkedIndex = if (selectedGradeSemesterTitle.isEmpty()) {
            0
        } else {
            val idx = semesters.indexOfFirst { it.semesterTitle == selectedGradeSemesterTitle }
            if (idx >= 0) idx + 1 else 0
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("选择成绩学期")
            .setSingleChoiceItems(options.toTypedArray(), checkedIndex) { dialog, which ->
                if (which == 0) {
                    selectedGradeSemesterTitle = ""
                } else {
                    selectedGradeSemesterTitle = semesters[which - 1].semesterTitle
                }
                updateGradesUI(report)
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    private fun updateGradesHeaderSpacing(headerHeightPx: Int) {
        if (headerHeightPx <= 0) return
        if (binding.viewGradesHeaderSpacing.layoutParams.height != headerHeightPx) {
            binding.viewGradesHeaderSpacing.layoutParams.height = headerHeightPx
            binding.viewGradesHeaderSpacing.requestLayout()
        }
        val density = resources.displayMetrics.density
        binding.swipeRefreshGrades.setProgressViewOffset(
            false,
            headerHeightPx,
            headerHeightPx + (56 * density).toInt()
        )
    }

    private fun updateCourseSearchHeaderSpacing(headerHeightPx: Int) {
        if (headerHeightPx <= 0) return
        val density = resources.displayMetrics.density
        val topPad = headerHeightPx + (4 * density).toInt()
        val currentTop = binding.pageCourseSearch.rvCourseList.paddingTop
        if (currentTop != topPad) {
            binding.pageCourseSearch.rvCourseList.setPadding(
                binding.pageCourseSearch.rvCourseList.paddingLeft,
                topPad,
                binding.pageCourseSearch.rvCourseList.paddingRight,
                bottomBarHeightPx
            )
            if (currentTop == 0) {
                binding.pageCourseSearch.rvCourseList.scrollToPosition(0)
            }
            binding.pageCourseSearch.layoutEmptyResults.setPadding(
                binding.pageCourseSearch.layoutEmptyResults.paddingLeft,
                headerHeightPx,
                binding.pageCourseSearch.layoutEmptyResults.paddingRight,
                bottomBarHeightPx
            )
        }
    }

    private fun updateFreeClassroomHeaderSpacing(headerHeightPx: Int) {
        if (headerHeightPx <= 0) return
        val density = resources.displayMetrics.density
        val topPad = headerHeightPx + (4 * density).toInt()
        val currentTop = binding.pageFreeClassroom.rvFreeClassroomList.paddingTop
        if (currentTop != topPad) {
            binding.pageFreeClassroom.rvFreeClassroomList.setPadding(
                binding.pageFreeClassroom.rvFreeClassroomList.paddingLeft,
                topPad,
                binding.pageFreeClassroom.rvFreeClassroomList.paddingRight,
                bottomBarHeightPx
            )
            if (currentTop == 0) {
                binding.pageFreeClassroom.rvFreeClassroomList.scrollToPosition(0)
            }
            binding.pageFreeClassroom.layoutEmptyFreeClassroom.setPadding(
                binding.pageFreeClassroom.layoutEmptyFreeClassroom.paddingLeft,
                headerHeightPx,
                binding.pageFreeClassroom.layoutEmptyFreeClassroom.paddingRight,
                bottomBarHeightPx
            )
        }
    }

    private fun updateExamQueryHeaderSpacing(headerHeightPx: Int) {
        if (headerHeightPx <= 0) return
        val density = resources.displayMetrics.density
        val topPad = headerHeightPx + (4 * density).toInt()
        val currentTop = binding.pageExamQuery.rvExamQueryList.paddingTop
        if (currentTop != topPad) {
            binding.pageExamQuery.rvExamQueryList.setPadding(
                binding.pageExamQuery.rvExamQueryList.paddingLeft,
                topPad,
                binding.pageExamQuery.rvExamQueryList.paddingRight,
                bottomBarHeightPx
            )
            if (currentTop == 0) {
                binding.pageExamQuery.rvExamQueryList.scrollToPosition(0)
            }
            binding.pageExamQuery.layoutEmptyExamQuery.setPadding(
                binding.pageExamQuery.layoutEmptyExamQuery.paddingLeft,
                headerHeightPx,
                binding.pageExamQuery.layoutEmptyExamQuery.paddingRight,
                bottomBarHeightPx
            )
        }
    }

    private fun updateGradeReportsHeaderSpacing(headerHeightPx: Int) {
        if (headerHeightPx <= 0) return
        val density = resources.displayMetrics.density
        val topPad = headerHeightPx + (6 * density).toInt()
        val currentTop = binding.pageGradeReports.scrollGradeReports.paddingTop
        if (currentTop != topPad) {
            binding.pageGradeReports.scrollGradeReports.setPadding(
                binding.pageGradeReports.scrollGradeReports.paddingLeft,
                topPad,
                binding.pageGradeReports.scrollGradeReports.paddingRight,
                bottomBarHeightPx
            )
        }
    }

    private fun displayGrades(report: GradeReport) {
        updateGradesUI(report)
    }

    private fun updateGradesUI(report: GradeReport) {
        if (selectedGradeSemesterTitle.isEmpty()) {
            binding.tvGradeSemester.text = "全部学期"
            binding.tvCreditsTitle.text = "累计修读学分"
            binding.tvAvgScoreTitle.text = "加权平均分"
            binding.tvGpaTitle.text = "平均学分绩点"
            binding.tvCumulativeCredits.text = String.format("%.1f", report.totalCredits)
            binding.tvCumulativeAvgScore.text = String.format("%.2f", report.cumulativeWeightedScore)
            binding.tvCumulativeGpa.text = String.format("%.2f", report.cumulativeGpa)

            val allCourses = report.semesters.flatMap { it.courses }
            gradeAdapter.submitList(allCourses)
            binding.tvGradesCountSummary.text = "共 ${allCourses.size} 门课程"
        } else {
            val sem = report.semesters.find { it.semesterTitle == selectedGradeSemesterTitle }
            if (sem != null) {
                binding.tvGradeSemester.text = formatSemesterTitle(sem.semesterTitle)
                binding.tvCreditsTitle.text = "学期修读学分"
                binding.tvAvgScoreTitle.text = "学期加权均分"
                binding.tvGpaTitle.text = "学期学分绩点"
                binding.tvCumulativeCredits.text = String.format("%.1f", sem.totalCredits)
                binding.tvCumulativeAvgScore.text = String.format("%.2f", sem.weightedAverageScore)
                binding.tvCumulativeGpa.text = String.format("%.2f", sem.weightedGpa)

                gradeAdapter.submitList(sem.courses)
                binding.tvGradesCountSummary.text = "本学期 ${sem.courses.size} 门课程"
            } else {
                selectedGradeSemesterTitle = ""
                updateGradesUI(report)
                return
            }
        }

        if (isTabletLandscapeMode) {
            populateGradesSemesterMasterList(report)
        }
    }

    private fun populateGradesSemesterMasterList(report: GradeReport) {
        val container = binding.llGradesSemesterMasterList
        container.removeAllViews()
        val density = resources.displayMetrics.density

        fun addMasterRow(
            title: String,
            subtitle: String,
            gpaBadge: String,
            isSelected: Boolean,
            onClick: () -> Unit
        ) {
            val rowCard = com.google.android.material.card.MaterialCardView(this).apply {
                radius = 12 * density
                cardElevation = 0f
                strokeWidth = ((if (isSelected) 1.5f else 0.8f) * density).toInt()
                strokeColor = ContextCompat.getColor(
                    this@MainActivity,
                    if (isSelected) R.color.primary else R.color.border
                )
                setCardBackgroundColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (isSelected) R.color.primary_container else R.color.surface_variant
                    )
                )
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (8 * density).toInt()
                }
                layoutParams = lp
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }

            val rowInner = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val hPad = (12 * density).toInt()
                val vPad = (10 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
            }

            val textCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTitle = TextView(this).apply {
                text = title
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (isSelected) R.color.primary else R.color.text_primary
                    )
                )
            }

            val tvSub = TextView(this).apply {
                text = subtitle
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                setPadding(0, (2 * density).toInt(), 0, 0)
            }

            textCol.addView(tvTitle)
            textCol.addView(tvSub)
            rowInner.addView(textCol)

            val tvGpa = TextView(this).apply {
                text = gpaBadge
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (isSelected) R.color.primary else R.color.text_primary
                    )
                )
            }
            rowInner.addView(tvGpa)
            rowCard.addView(rowInner)
            container.addView(rowCard)
        }

        val allCount = report.semesters.sumOf { it.courses.size }
        addMasterRow(
            title = "全部学期 (汇总)",
            subtitle = "$allCount 门 · ${String.format("%.1f", report.totalCredits)} 学分 · 均分 ${String.format("%.2f", report.cumulativeWeightedScore)}",
            gpaBadge = "GPA ${String.format("%.2f", report.cumulativeGpa)}",
            isSelected = selectedGradeSemesterTitle.isEmpty()
        ) {
            selectedGradeSemesterTitle = ""
            updateGradesUI(report)
        }

        for (sem in report.semesters) {
            val formatted = formatSemesterTitle(sem.semesterTitle)
            addMasterRow(
                title = formatted,
                subtitle = "${sem.courses.size} 门 · ${String.format("%.1f", sem.totalCredits)} 学分 · 均分 ${String.format("%.2f", sem.weightedAverageScore)}",
                gpaBadge = "GPA ${String.format("%.2f", sem.weightedGpa)}",
                isSelected = selectedGradeSemesterTitle == sem.semesterTitle
            ) {
                selectedGradeSemesterTitle = sem.semesterTitle
                updateGradesUI(report)
            }
        }
    }

    private fun showDownloadGradeReportsDialog() {
        val docTypes = GradeDocumentType.values()
        val items = docTypes.map { "${it.displayName}\n${it.description}" }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle("学业证明与成绩单下载")
            .setItems(items) { _, which ->
                val chosenType = docTypes[which]
                downloadAndOpenDocument(chosenType)
            }
            .setNegativeButton("取消", null)
            .safeShow()
    }

    private fun downloadAndOpenDocument(docType: GradeDocumentType) {
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setTitle("正在下载 ${docType.displayName}")
            .setMessage("正在向教务系统请求并生成 PDF 文件，请稍候...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                val targetDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
                    ?: java.io.File(filesDir, "downloads").apply { mkdirs() }
                val targetFile = java.io.File(targetDir, docType.defaultFileName)
                val auth = cn.edu.usst.jwgl.data.local.AuthPreferences(this@MainActivity)
                val studentId = auth.getStudentId()
                val result = JwglClient.downloadGradeDocument(this@MainActivity, docType, targetFile, studentId)
                progressDialog.dismiss()

                result.onSuccess { downloadedFile ->
                    showDownloadSuccessDialog(docType, downloadedFile)
                }.onFailure { error ->
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("下载失败")
                        .setMessage("未能成功下载 ${docType.displayName}：${error.message ?: "网络异常"}")
                        .setPositiveButton("确定", null)
                        .safeShow()
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                Toast.makeText(this@MainActivity, "下载异常: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDownloadSuccessDialog(docType: GradeDocumentType, file: java.io.File) {
        if (isGradeReportsInitialized) refreshDownloadedFilesList()
        MaterialAlertDialogBuilder(this)
            .setTitle("下载完成")
            .setMessage("${docType.displayName} 已生成并成功保存！\n\n文件路径: ${file.name}\n大小: ${file.length() / 1024} KB")
            .setPositiveButton("立即打开") { _, _ ->
                openPdfFile(file)
            }
            .setNeutralButton("分享文件") { _, _ ->
                sharePdfFile(file)
            }
            .setNegativeButton("关闭", null)
            .safeShow()
    }

    private fun openPdfFile(file: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "打开 PDF 文件"))
        } catch (e: Exception) {
            Toast.makeText(this, "打开 PDF 失败: ${e.message}，请安装支持 PDF 的阅读器应用", Toast.LENGTH_LONG).show()
        }
    }

    private fun sharePdfFile(file: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享文件"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupListeners() {
        // Back press handling: close drawer first if opened
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isGuestMode) {
                    finish()
                } else if (isNavDrawerOpen()) {
                    closeNavDrawer()
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
        binding.tabBottomMore.setOnClickListener {
            switchTab(MainTab.MORE_FEATURES)
        }
        binding.tabBottomProfile.setOnClickListener {
            switchTab(MainTab.PROFILE)
        }

        // More Features Hub Page (Bottom Bar Mode)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val appWidgetManager = getSystemService(android.appwidget.AppWidgetManager::class.java)
                val provider = android.content.ComponentName(this, cn.edu.usst.jwgl.widget.TodayScheduleWidgetProvider::class.java)
                if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported) {
                    appWidgetManager.requestPinAppWidget(provider, null, null)
                } else {
                    Toast.makeText(this, "当前手机桌面启动器不支持自动添加，请在桌面空白处长按手动添加小组件", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "请在手机桌面空白处长按手动添加今日课表小组件", Toast.LENGTH_LONG).show()
            }
        }
        binding.pageMoreFeatures.rowMoreJwglWeb.setOnClickListener {
            JwglWebActivity.start(this)
        }
        binding.pageMoreFeatures.rowMoreXgWeb.setOnClickListener {
            JwglWebActivity.start(this, "https://new2017.webvpn.usst.edu.cn/StuDefault.aspx", "学工一体化")
        }
        binding.pageMoreFeatures.rowMoreLiveWeb.setOnClickListener {
            JwglWebActivity.start(this, JwglWebActivity.LIVE_TARGET_URL, "课程直播与点播")
        }
        binding.pageMoreFeatures.rowMoreYwcxWeb.setOnClickListener {
            JwglWebActivity.start(this, JwglWebActivity.YWCX_TARGET_URL, "一网畅学平台 (TronClass)")
        }

        // Quick Service Entries in Profile (Bottom Bar Mode)
        binding.btnProfileExamQuery.setOnClickListener {
            switchTab(MainTab.EXAM_QUERY)
        }
        binding.btnProfileGradeReports.setOnClickListener {
            switchTab(MainTab.GRADE_REPORTS)
        }
        binding.btnProfileJwglWeb.setOnClickListener {
            JwglWebActivity.start(this)
        }
        binding.btnProfileXgWeb.setOnClickListener {
            JwglWebActivity.start(this, "https://new2017.webvpn.usst.edu.cn/StuDefault.aspx", "学工一体化")
        }
        binding.btnProfileLiveWeb.setOnClickListener {
            JwglWebActivity.start(this, JwglWebActivity.LIVE_TARGET_URL, "课程直播与点播")
        }
        binding.btnProfileYwcxWeb.setOnClickListener {
            JwglWebActivity.start(this, JwglWebActivity.YWCX_TARGET_URL, "一网畅学平台 (TronClass)")
        }

        // Hamburger Menu Buttons to Open Drawer
        binding.btnOpenDrawerTimetable.setOnClickListener {
            openNavDrawer()
        }
        binding.btnOpenDrawerGrades.setOnClickListener {
            openNavDrawer()
        }
        binding.btnOpenDrawerProfile.setOnClickListener {
            openNavDrawer()
        }

        // Side Navigation Drawer: Main Tabs
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
            JwglWebActivity.start(this)
        }

        binding.layoutDrawer.drawerItemXgWeb.setOnClickListener {
            closeNavDrawer()
            JwglWebActivity.start(this, "https://new2017.webvpn.usst.edu.cn/StuDefault.aspx", "学工一体化")
        }

        binding.layoutDrawer.drawerItemLiveWeb.setOnClickListener {
            closeNavDrawer()
            JwglWebActivity.start(this, JwglWebActivity.LIVE_TARGET_URL, "课程直播与点播")
        }

        binding.layoutDrawer.drawerItemYwcxWeb.setOnClickListener {
            closeNavDrawer()
            JwglWebActivity.start(this, JwglWebActivity.YWCX_TARGET_URL, "一网畅学平台 (TronClass)")
        }

        // Side Navigation Drawer: Logout
        binding.layoutDrawer.btnDrawerLogout.setOnClickListener {
            closeNavDrawer()
            binding.btnLogout.performClick()
        }

        // Profile Tab: System & Updates card
        binding.btnLogout.setOnClickListener {
            JwglClient.clearSession()
            AuthPreferences(this).clearAutoLogin()
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
    // --- 4. School Course Search Full Page ---
    // ==========================================
    private var isCourseSearchInitialized = false
    private var schoolCourseAdapter: SchoolCourseAdapter? = null
    private val schoolCourseList = mutableListOf<MergedSchoolCourse>()
    private var currentSearchXn = "2026-2027"
    private var currentSearchXq = "1"
    private var selectedSearchLevel = "ALL" // "ALL", "UNDERGRADUATE", "GRADUATE"
    private var selectedSearchWeek = -1
    private var selectedSearchCollege = ""
    private var selectedSearchDay = -1
    private var selectedSearchSection = "-1"
    private var defaultSlotQueryJob: Job? = null

    data class CurrentSlotSpec(
        val slotName: String,
        val queryJc: String,
        val statusText: String,
        val timeDesc: String
    )

    private fun getCurrentWeekdaySlot(): CurrentSlotSpec? {
        val cal = SemesterHelper.getToday()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        // Calendar.SUNDAY = 1, MONDAY = 2, ..., FRIDAY = 6, SATURDAY = 7
        if (dayOfWeek !in Calendar.MONDAY..Calendar.FRIDAY) {
            return null
        }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val totalMinutes = hour * 60 + minute

        // 07:00 is 420, 20:15 is 1215
        if (totalMinutes < 7 * 60 || totalMinutes > 20 * 60 + 15) {
            return null
        }

        return when {
            totalMinutes <= 9 * 60 + 25 -> {
                val status = if (totalMinutes < 8 * 60) "即将开始 (08:00开课)" else "正在上课 (08:00-09:25)"
                CurrentSlotSpec("第1-2节", "1-2", status, "08:00-09:25")
            }
            totalMinutes <= 11 * 60 + 55 -> {
                val status = if (totalMinutes < 9 * 60 + 45) "即将开始 (09:45开课)" else "正在上课 (09:45-11:55)"
                CurrentSlotSpec("第3-5节", "3-5", status, "09:45-11:55")
            }
            totalMinutes <= 14 * 60 + 25 -> {
                val status = if (totalMinutes < 13 * 60) "即将开始 (13:00开课)" else "正在上课 (13:00-14:25)"
                CurrentSlotSpec("第6-7节", "6-7", status, "13:00-14:25")
            }
            totalMinutes <= 16 * 60 + 55 -> {
                val status = if (totalMinutes < 14 * 60 + 45) "即将开始 (14:45开课)" else "正在上课 (14:45-16:55)"
                CurrentSlotSpec("第8-10节", "8-9", status, "14:45-16:55")
            }
            else -> {
                val status = if (totalMinutes < 18 * 60) "即将开始 (18:00开课)" else "正在上课 (18:00-20:10)"
                CurrentSlotSpec("第11-13节", "10-12", status, "18:00-20:10")
            }
        }
    }

    private fun checkAndLoadDefaultCourseSearch(isUserInitiatedReset: Boolean = false, forceRefresh: Boolean = false) {
        if (!isCourseSearchInitialized) return
        val page = binding.pageCourseSearch

        val hasActiveSearch = !page.etSearchCourse.text.isNullOrBlank() ||
                !page.etSearchTeacher.text.isNullOrBlank() ||
                selectedSearchCollege.isNotEmpty() ||
                selectedSearchDay != -1 ||
                selectedSearchWeek != -1 ||
                selectedSearchSection != "-1" ||
                selectedSearchLevel != "ALL"

        if (hasActiveSearch && !isUserInitiatedReset) {
            return
        }

        // 首次进入或重置检索时，在后台异步全量加载并缓存全校课表
        lifecycleScope.launch(Dispatchers.IO) {
            LappClient.getOrFetchFullSchoolSchedule(this@MainActivity, currentSearchXn, currentSearchXq)
        }

        defaultSlotQueryJob?.cancel()

        val slot = getCurrentWeekdaySlot()
        if (slot == null) {
            schoolCourseList.clear()
            schoolCourseAdapter?.notifyDataSetChanged()
            page.progressBarSearch.visibility = View.GONE
            page.rvCourseList.visibility = View.GONE
            page.layoutEmptyResults.visibility = View.VISIBLE
            page.tvEmptyTitle.text = "当前非上课时段"
            page.tvEmptyDesc.text = "工作日 07:00-20:15 默认展示当前时段课程\n您可通过上方搜索栏或筛选条件主动检索全校开课（支持本地缓存）"
            page.tvCourseSearchSubtitle.text = "当前非上课时段，请主动检索"
            return
        }

        val cal = SemesterHelper.getToday()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val todayZj = when (dayOfWeek) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            else -> 1
        }
        val dayNames = arrayOf("", "周一", "周二", "周三", "周四", "周五")
        val dayName = dayNames.getOrElse(todayZj) { "今天" }

        val curWeek = getEffectiveCurrentWeek()
        val targetWeek = if (curWeek in 1..25) curWeek else -1
        val weekPrefix = if (targetWeek > 0) "第${targetWeek}周 " else ""

        page.progressBarSearch.visibility = View.VISIBLE
        page.tvCourseSearchSubtitle.text = "$weekPrefix$dayName ${slot.slotName} · ${if (forceRefresh) "正在同步最新排课..." else "正在加载..."}"

        defaultSlotQueryJob = lifecycleScope.launch {
            try {
                val result = LappClient.queryClassSchedule(
                    context = this@MainActivity,
                    xn = currentSearchXn,
                    xq = currentSearchXq,
                    week = -1,
                    xy = "",
                    zj = todayZj,
                    jc = slot.queryJc,
                    kcmc = "",
                    kcdm = "",
                    lsmc = "",
                    rows = 500,
                    forceRefresh = forceRefresh
                )

                page.progressBarSearch.visibility = View.GONE

                if (result.isSuccess) {
                    val (_, rawItems) = result.getOrNull() ?: Pair(0, emptyList())
                    val cacheTag = LappClient.formatCacheTag()
                    val cacheSuffix = if (cacheTag.isNotEmpty()) " ($cacheTag)" else ""

                    // 核心要求：默认显示当前时段课程时只显示本周课程
                    val thisWeekItems = if (targetWeek > 0) {
                        rawItems.filter { item ->
                            if (item.weekList.isNotEmpty()) {
                                item.weekList.contains(targetWeek)
                            } else if (item.qsz > 0 && item.jsz >= item.qsz) {
                                targetWeek in item.qsz..item.jsz
                            } else if (item.zc.isNotBlank()) {
                                item.zc.split(",").mapNotNull { it.trim().toIntOrNull() }.contains(targetWeek)
                            } else {
                                true
                            }
                        }
                    } else {
                        rawItems
                    }

                    // 严格限定属于当前时段范围的节次
                    val validSlotItems = thisWeekItems.filter { item ->
                        when (slot.queryJc) {
                            "1-2" -> item.jc in 1..2
                            "3-5" -> item.jc in 3..5
                            "6-7" -> item.jc in 6..8
                            "8-9" -> item.jc in 8..10
                            "10-12" -> item.jc in 11..13
                            else -> true
                        }
                    }

                    val merged = MergedSchoolCourse.fromLappItems(validSlotItems)
                    val levelFiltered = when (selectedSearchLevel) {
                        "UNDERGRADUATE" -> merged.filter { it.isUndergraduate }
                        "GRADUATE" -> merged.filter { it.isGraduate }
                        else -> merged
                    }

                    if (levelFiltered.isNotEmpty()) {
                        schoolCourseList.clear()
                        schoolCourseList.addAll(levelFiltered)
                        schoolCourseAdapter?.notifyDataSetChanged()

                        page.rvCourseList.visibility = View.VISIBLE
                        page.layoutEmptyResults.visibility = View.GONE
                        val levelSuffix = when (selectedSearchLevel) {
                            "UNDERGRADUATE" -> " (本科生)"
                            "GRADUATE" -> " (研究生)"
                            else -> ""
                        }
                        page.tvCourseSearchSubtitle.text = "$weekPrefix$dayName ${slot.slotName}$levelSuffix · 共 ${levelFiltered.size} 门$cacheSuffix"

                        if (forceRefresh) {
                            Toast.makeText(this@MainActivity, "已手动刷新当前时段排课 (共 ${levelFiltered.size} 门课程)", Toast.LENGTH_SHORT).show()
                        }

                        val pendingItems = levelFiltered.filter { it.category == "待定" && it.kcdm.isNotEmpty() }
                            .distinctBy { it.kcdm }
                            .take(25)
                        if (pendingItems.isNotEmpty()) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                for (item in pendingItems) {
                                    val cat = CourseCatalogService.resolveCategory(this@MainActivity, item.kcdm, item.kcmc)
                                    if (cat != "待定") {
                                        withContext(Dispatchers.Main) {
                                            var hit = false
                                            for (c in schoolCourseList) {
                                                if (c.kcdm == item.kcdm) {
                                                    c.category = cat
                                                    hit = true
                                                }
                                            }
                                            if (hit) {
                                                schoolCourseAdapter?.notifyDataSetChanged()
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        schoolCourseList.clear()
                        schoolCourseAdapter?.notifyDataSetChanged()
                        page.rvCourseList.visibility = View.GONE
                        page.layoutEmptyResults.visibility = View.VISIBLE
                        page.tvEmptyTitle.text = "本周当前时段暂无排课"
                        page.tvEmptyDesc.text = "${weekPrefix}$dayName ${slot.slotName} 暂未检索到开课教学班，您可主动检索其他时段或周次"
                        page.tvCourseSearchSubtitle.text = "$weekPrefix$dayName ${slot.slotName} · 暂无开课$cacheSuffix"
                        if (forceRefresh) {
                            Toast.makeText(this@MainActivity, "已手动刷新，当前时段暂无排课", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    if (schoolCourseList.isEmpty()) {
                        page.rvCourseList.visibility = View.GONE
                        page.layoutEmptyResults.visibility = View.VISIBLE
                        page.tvEmptyTitle.text = "暂无本地排课缓存"
                        page.tvEmptyDesc.text = "可点击右上角刷新按键同步全校排课数据"
                        page.tvCourseSearchSubtitle.text = "支持教师/课程名/代码/学期同查"
                    }
                    if (forceRefresh) {
                        Toast.makeText(this@MainActivity, "同步失败，请检查是否正确连接无线校园网", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                page.progressBarSearch.visibility = View.GONE
                if (schoolCourseList.isEmpty()) {
                    page.rvCourseList.visibility = View.GONE
                    page.layoutEmptyResults.visibility = View.VISIBLE
                    page.tvEmptyTitle.text = "暂无本地排课缓存"
                    page.tvEmptyDesc.text = "可点击右上角刷新按键同步全校排课数据"
                    page.tvCourseSearchSubtitle.text = "支持教师/课程名/代码/学期同查"
                }
                if (forceRefresh) {
                    Toast.makeText(this@MainActivity, "同步失败，请检查是否正确连接无线校园网", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun initCourseSearchPage() {
        if (isCourseSearchInitialized) return
        isCourseSearchInitialized = true

        val page = binding.pageCourseSearch
        page.btnOpenDrawerCourseSearch.setOnClickListener {
            if (isGuestMode) {
                finish()
            } else if (isPhoneBottomBarMode()) {
                switchTab(MainTab.MORE_FEATURES)
            } else {
                openNavDrawer()
            }
        }
        page.btnOpenLappWeb.setOnClickListener {
            JwglWebActivity.start(
                this,
                "https://lapp.usst.edu.cn/_web/_customizes/usst/schoolTimetable/mobile/permit/pc/pub/index.html",
                "全校课表"
            )
        }

        schoolCourseAdapter = SchoolCourseAdapter(
            schoolCourseList,
            onSyllabusClick = { course ->
                val sheet = CourseSyllabusBottomSheet.newInstance(
                    courseCode = course.kcdm,
                    courseName = course.kcmc,
                    department = course.xy,
                    credit = course.xf
                )
                sheet.show(supportFragmentManager, "CourseSyllabus")
            }
        ) { mergedCourse ->
            lifecycleScope.launch(Dispatchers.IO) {
                val tableId = currentTable?.id ?: db.tableDao.getDefaultTable().id
                val conflicts = db.findConflictsForMergedCourse(tableId, mergedCourse)
                if (conflicts.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        showSchoolCourseConflictDialog(tableId, mergedCourse, conflicts)
                    }
                } else {
                    db.addMergedSchoolCourse(tableId, mergedCourse)
                    withContext(Dispatchers.Main) {
                        reloadTimetableFromDb()
                        Toast.makeText(
                            this@MainActivity,
                            "已成功添加《${mergedCourse.kcmc}》(共${mergedCourse.slots.size}个时段) 到课表！",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        page.rvCourseList.layoutManager = if (isTabletLandscapeMode) GridLayoutManager(this, 2) else LinearLayoutManager(this)
        page.rvCourseList.adapter = schoolCourseAdapter

        val (curXnm, curXqm) = SemesterHelper.getCurrentSemester()
        val curYear = curXnm.toIntOrNull() ?: 2026
        val curYearSpan = "$curYear-${curYear + 1}"
        val curTermNum = if (curXqm == "3") "1" else "2"

        val (nextXnm, nextXqm) = SemesterHelper.getNextSemester(curXnm, curXqm)
        val nextYear = nextXnm.toIntOrNull() ?: 2026
        val nextYearSpan = "$nextYear-${nextYear + 1}"
        val nextTermNum = if (nextXqm == "3") "1" else "2"

        val semesterOptions = mutableListOf<Pair<String, Pair<String, String>>>().apply {
            add(Pair("$nextYearSpan 第${nextTermNum}学期 (下一学期)", Pair(nextYearSpan, nextTermNum)))
            add(Pair("$curYearSpan 第${curTermNum}学期 (当前)", Pair(curYearSpan, curTermNum)))
            var pastYear = curYear
            var pastTerm = if (curTermNum == "1") "2" else "1"
            if (curTermNum == "1") {
                pastYear -= 1
            }
            for (i in 0 until 4) {
                val pastSpan = "$pastYear-${pastYear + 1}"
                val label = if (pastTerm == "2" && i == 0) "$pastSpan 第${pastTerm}学期 (春季参考)" else "$pastSpan 第${pastTerm}学期"
                add(Pair(label, Pair(pastSpan, pastTerm)))
                if (pastTerm == "2") {
                    pastTerm = "1"
                } else {
                    pastTerm = "2"
                    pastYear -= 1
                }
            }
        }
        val defaultSemIndex = semesterOptions.indexOfFirst { it.second == Pair(currentSearchXn, currentSearchXq) }.coerceAtLeast(0)
        val semAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, semesterOptions.map { it.first })

        val weekOptions = mutableListOf(Pair("全部周次", -1))
        for (w in 1..20) {
            weekOptions.add(Pair("第${w}周", w))
        }
        val weekAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, weekOptions.map { it.first })

        val dayOptions = listOf(
            Pair("全部星期", -1),
            Pair("周一", 1),
            Pair("周二", 2),
            Pair("周三", 3),
            Pair("周四", 4),
            Pair("周五", 5),
            Pair("周六", 6),
            Pair("周日", 7)
        )
        val dayAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dayOptions.map { it.first })

        val sectionOptions = listOf(
            Pair("全部时段", "-1"),
            Pair("1-2节 (08:00-09:25)", "1-2"),
            Pair("3-5节 (09:45-11:55)", "3-5"),
            Pair("6-7节 (13:00-14:25)", "6-7"),
            Pair("8-10节 (14:45-16:55)", "8-9"),
            Pair("11-13节 (18:00-20:15)", "10-12")
        )
        val sectionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sectionOptions.map { it.first })

        val levelOptions = listOf(
            Pair("全部层次 (本科/研究生)", "ALL"),
            Pair("仅本科生课程", "UNDERGRADUATE"),
            Pair("仅研究生课程", "GRADUATE")
        )
        val levelAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, levelOptions.map { it.first })

        val defaultColleges = mutableListOf("全部学院")
        val collegeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, defaultColleges)

        lifecycleScope.launch {
            val listRes = LappClient.queryColleges(this@MainActivity)
            if (listRes.isSuccess) {
                val dynamicList = listRes.getOrNull() ?: emptyList()
                if (dynamicList.isNotEmpty()) {
                    defaultColleges.clear()
                    defaultColleges.add("全部学院")
                    defaultColleges.addAll(dynamicList)
                    collegeAdapter.notifyDataSetChanged()
                }
            }
        }

        // 更多筛选弹窗 (收纳 学期、培养层次、周次、学院、星期、时段)
        val filterDialog = BottomSheetDialog(this)
        val filterView = layoutInflater.inflate(R.layout.dialog_course_search_filters, null)
        filterDialog.setContentView(filterView)
        filterDialog.setOnShowListener {
            TabletLayoutHelper.setupBottomSheetForLargeScreen(filterDialog, this)
        }

        val dialogSpinnerSemester = filterView.findViewById<Spinner>(R.id.dialogSpinnerSemester)
        val dialogSpinnerLevel = filterView.findViewById<Spinner>(R.id.dialogSpinnerLevel)
        val dialogSpinnerWeek = filterView.findViewById<Spinner>(R.id.dialogSpinnerWeek)
        val dialogSpinnerCollege = filterView.findViewById<Spinner>(R.id.dialogSpinnerCollege)
        val dialogSpinnerDay = filterView.findViewById<Spinner>(R.id.dialogSpinnerDay)
        val dialogSpinnerSection = filterView.findViewById<Spinner>(R.id.dialogSpinnerSection)
        val btnCloseFilterDialog = filterView.findViewById<View>(R.id.btnCloseFilterDialog)
        val btnResetFilters = filterView.findViewById<View>(R.id.btnResetFilters)
        val btnConfirmFilters = filterView.findViewById<View>(R.id.btnConfirmFilters)

        dialogSpinnerSemester.adapter = semAdapter
        dialogSpinnerSemester.setSelection(defaultSemIndex)
        dialogSpinnerLevel.adapter = levelAdapter
        val defaultLevelIndex = levelOptions.indexOfFirst { it.second == selectedSearchLevel }.coerceAtLeast(0)
        dialogSpinnerLevel.setSelection(defaultLevelIndex)
        dialogSpinnerWeek.adapter = weekAdapter
        dialogSpinnerCollege.adapter = collegeAdapter
        dialogSpinnerDay.adapter = dayAdapter
        dialogSpinnerSection.adapter = sectionAdapter

        fun updateFilterBadge() {
            val conditions = mutableListOf<String>()
            if (currentSearchXn != curYearSpan || currentSearchXq != curTermNum) {
                conditions.add("${currentSearchXn.takeLast(4)}-${currentSearchXq}")
            }
            if (selectedSearchLevel == "UNDERGRADUATE") {
                conditions.add("本科生")
            } else if (selectedSearchLevel == "GRADUATE") {
                conditions.add("研究生")
            }
            if (selectedSearchWeek != -1) {
                conditions.add("第${selectedSearchWeek}周")
            }
            if (selectedSearchCollege.isNotEmpty()) {
                conditions.add(selectedSearchCollege)
            }
            if (selectedSearchDay != -1) {
                val days = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
                conditions.add(days.getOrElse(selectedSearchDay) { "" })
            }
            if (selectedSearchSection != "-1") {
                val secLabel = sectionOptions.firstOrNull { it.second == selectedSearchSection }?.first
                if (secLabel != null) {
                    val shortLabel = secLabel.substringBefore(" ")
                    conditions.add(shortLabel)
                }
            }

            if (conditions.isNotEmpty()) {
                page.btnMoreFilters.text = "筛选 (${conditions.joinToString("·")}) ▾"
            } else {
                page.btnMoreFilters.text = "更多筛选 ▾"
            }
        }

        dialogSpinnerSemester.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                val pair = semesterOptions[position].second
                currentSearchXn = pair.first
                currentSearchXq = pair.second
                updateFilterBadge()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        dialogSpinnerLevel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                selectedSearchLevel = levelOptions[position].second
                updateFilterBadge()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        dialogSpinnerWeek.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                selectedSearchWeek = weekOptions[position].second
                updateFilterBadge()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        dialogSpinnerCollege.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                selectedSearchCollege = if (position == 0) "" else defaultColleges[position]
                updateFilterBadge()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        dialogSpinnerDay.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                selectedSearchDay = dayOptions[position].second
                updateFilterBadge()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        dialogSpinnerSection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                selectedSearchSection = sectionOptions[position].second
                updateFilterBadge()
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        btnCloseFilterDialog.setOnClickListener { filterDialog.dismiss() }

        btnResetFilters.setOnClickListener {
            val resetSemIndex = semesterOptions.indexOfFirst { it.second == Pair(curYearSpan, curTermNum) }.coerceAtLeast(0)
            dialogSpinnerSemester.setSelection(resetSemIndex)
            dialogSpinnerLevel.setSelection(0)
            dialogSpinnerWeek.setSelection(0)
            dialogSpinnerCollege.setSelection(0)
            dialogSpinnerDay.setSelection(0)
            dialogSpinnerSection.setSelection(0)
            currentSearchXn = curYearSpan
            currentSearchXq = curTermNum
            selectedSearchLevel = "ALL"
            selectedSearchWeek = -1
            selectedSearchCollege = ""
            selectedSearchDay = -1
            selectedSearchSection = "-1"
            updateFilterBadge()
        }

        page.btnMoreFilters.setOnClickListener {
            filterDialog.show()
        }

        fun executeSearch(forceRefresh: Boolean = false) {
            defaultSlotQueryJob?.cancel()
            val courseKeyword = page.etSearchCourse.text.toString().trim()
            val teacherKeyword = page.etSearchTeacher.text.toString().trim()

            if (courseKeyword.isEmpty() && teacherKeyword.isEmpty() && selectedSearchCollege.isEmpty() && selectedSearchDay == -1 && selectedSearchWeek == -1 && selectedSearchSection == "-1" && selectedSearchLevel == "ALL") {
                if (getCurrentWeekdaySlot() != null) {
                    checkAndLoadDefaultCourseSearch(isUserInitiatedReset = true, forceRefresh = forceRefresh)
                } else {
                    Toast.makeText(this, "请输入课程名/代码/教师名或选择筛选条件", Toast.LENGTH_SHORT).show()
                }
                return
            }

            page.progressBarSearch.visibility = View.VISIBLE
            page.btnDoSearch.isEnabled = false

            lifecycleScope.launch {
                try {
                    // 核心解决全校课表漏课：检查输入是否包含课程代码或班级号
                    val extractedCode = CourseCatalogService.extractCourseCode(courseKeyword)
                    var queryKcmc = courseKeyword

                    if (extractedCode != null) {
                        Log.i("MainActivity", "Detected course code: $extractedCode, resolving standard course name...")
                        val infoRes = CourseCatalogService.queryCourseByCode(this@MainActivity, extractedCode)
                        val resolvedCourse = infoRes.getOrNull()
                        if (resolvedCourse != null) {
                            CourseCatalogService.cacheCourseInfo(resolvedCourse)
                            if (resolvedCourse.courseName.isNotEmpty()) {
                                Log.i("MainActivity", "Resolved code $extractedCode to name: ${resolvedCourse.courseName}")
                                queryKcmc = resolvedCourse.courseName
                            }
                        }
                    } else if (courseKeyword.isNotBlank()) {
                        // 预先并发请求教务系统课程库填充类别缓存
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val catRes = CourseCatalogService.queryCourses(this@MainActivity, courseName = queryKcmc)
                                if (catRes.isSuccess) {
                                    withContext(Dispatchers.Main) {
                                        var anyUpdated = false
                                        for (c in schoolCourseList) {
                                            if (c.category == "待定") {
                                                val resolved = CourseCatalogService.getCategoryFromCacheOrHeuristic(c.kcdm, c.kcmc)
                                                if (resolved != "待定") {
                                                    c.category = resolved
                                                    anyUpdated = true
                                                }
                                            }
                                        }
                                        if (anyUpdated) {
                                            schoolCourseAdapter?.notifyDataSetChanged()
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    val result = LappClient.queryClassSchedule(
                        context = this@MainActivity,
                        xn = currentSearchXn,
                        xq = currentSearchXq,
                        week = selectedSearchWeek,
                        xy = selectedSearchCollege,
                        zj = selectedSearchDay,
                        jc = selectedSearchSection,
                        kcmc = queryKcmc,
                        kcdm = "",
                        lsmc = teacherKeyword,
                        rows = 200,
                        forceRefresh = forceRefresh
                    )

                    page.progressBarSearch.visibility = View.GONE
                    page.btnDoSearch.isEnabled = true

                    if (result.isSuccess) {
                        val (_, rawItems) = result.getOrNull() ?: Pair(0, emptyList())
                        val cacheTag = LappClient.formatCacheTag()
                        val cacheSuffix = if (cacheTag.isNotEmpty()) " ($cacheTag)" else ""
                        Log.i("MainActivity", "executeSearch: rawItems.size=${rawItems.size}, fromCache=${LappClient.lastQueryFromCache}")

                        var filtered = rawItems
                        if (extractedCode != null) {
                            // 针对课程代码进行精确二次筛查
                            filtered = filtered.filter {
                                it.kcdm == extractedCode || it.bjmc.contains(extractedCode)
                            }
                        } else if (courseKeyword.isNotBlank()) {
                            val normKeyword = LappClient.normalizeKeyword(courseKeyword)
                            filtered = filtered.filter {
                                LappClient.normalizeKeyword(it.kcmc).contains(normKeyword) ||
                                it.kcdm.contains(courseKeyword, ignoreCase = true) ||
                                it.bjmc.contains(courseKeyword, ignoreCase = true)
                            }
                        }

                        if (teacherKeyword.isNotBlank()) {
                            filtered = filtered.filter {
                                it.lsmc.contains(teacherKeyword, ignoreCase = true)
                            }
                        }
                        if (selectedSearchWeek > 0) {
                            filtered = filtered.filter {
                                it.weekList.contains(selectedSearchWeek) ||
                                (it.qsz <= selectedSearchWeek && it.jsz >= selectedSearchWeek) ||
                                it.zc.contains(selectedSearchWeek.toString())
                            }
                        }
                        if (selectedSearchCollege.isNotBlank() && selectedSearchCollege != "全部学院") {
                            filtered = filtered.filter {
                                it.xy.contains(selectedSearchCollege) || selectedSearchCollege.contains(it.xy)
                            }
                        }
                        if (selectedSearchDay > 0) {
                            filtered = filtered.filter { it.zj == selectedSearchDay }
                        }
                        if (selectedSearchSection != "-1") {
                            filtered = filtered.filter { item ->
                                when (selectedSearchSection) {
                                    "1-2" -> item.jc in 1..2
                                    "3-5" -> item.jc in 3..5
                                    "6-7" -> item.jc in 6..8
                                    "8-9" -> item.jc in 8..10
                                    "10-12" -> item.jc in 11..13
                                    else -> true
                                }
                            }
                        }

                        val merged = MergedSchoolCourse.fromLappItems(filtered)
                        val levelFiltered = when (selectedSearchLevel) {
                            "UNDERGRADUATE" -> merged.filter { it.isUndergraduate }
                            "GRADUATE" -> merged.filter { it.isGraduate }
                            else -> merged
                        }
                        Log.i("MainActivity", "executeSearch: filtered.size=${filtered.size}, merged.size=${merged.size}, levelFiltered.size=${levelFiltered.size}")

                        schoolCourseList.clear()
                        schoolCourseList.addAll(levelFiltered)
                        schoolCourseAdapter?.notifyDataSetChanged()

                        // 异步精确解析尚未匹配到类别的课程（前 25 项，随解析随更新 UI）
                        val pendingItems = levelFiltered.filter { it.category == "待定" && it.kcdm.isNotEmpty() }
                            .distinctBy { it.kcdm }
                            .take(25)
                        if (pendingItems.isNotEmpty()) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                for (item in pendingItems) {
                                    val cat = CourseCatalogService.resolveCategory(this@MainActivity, item.kcdm, item.kcmc)
                                    if (cat != "待定") {
                                        withContext(Dispatchers.Main) {
                                            var hit = false
                                            for (c in schoolCourseList) {
                                                if (c.kcdm == item.kcdm) {
                                                    c.category = cat
                                                    hit = true
                                                }
                                            }
                                            if (hit) {
                                                schoolCourseAdapter?.notifyDataSetChanged()
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (levelFiltered.isEmpty()) {
                            page.rvCourseList.visibility = View.GONE
                            page.layoutEmptyResults.visibility = View.VISIBLE
                            page.tvEmptyTitle.text = "未找到匹配开课班级"
                            page.tvEmptyDesc.text = "请尝试减少筛选条件、检查关键词拼写或更换学期"
                            page.tvCourseSearchSubtitle.text = "未找到匹配开课班级$cacheSuffix"
                            Toast.makeText(this@MainActivity, "未检索到匹配的开课班级", Toast.LENGTH_SHORT).show()
                        } else {
                            page.rvCourseList.visibility = View.VISIBLE
                            page.layoutEmptyResults.visibility = View.GONE
                            val levelSuffix = when (selectedSearchLevel) {
                                "UNDERGRADUATE" -> " [仅本科生]"
                                "GRADUATE" -> " [仅研究生]"
                                else -> ""
                            }
                            page.tvCourseSearchSubtitle.text = "检索结果 共 ${levelFiltered.size} 个教学班$levelSuffix$cacheSuffix"
                            val codeHint = if (extractedCode != null) " [代码 $extractedCode]" else ""
                            val msg = if (forceRefresh) {
                                "已刷新排课数据 · 共 ${levelFiltered.size} 个教学班$levelSuffix$codeHint"
                            } else {
                                "共检索到 ${levelFiltered.size} 个教学班$levelSuffix$codeHint (共 ${filtered.size} 个上课时段)"
                            }
                            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        if (schoolCourseList.isEmpty()) {
                            page.rvCourseList.visibility = View.GONE
                            page.layoutEmptyResults.visibility = View.VISIBLE
                            page.tvEmptyTitle.text = "暂无匹配本地缓存"
                            page.tvEmptyDesc.text = "可点击右上角刷新按键同步全校排课数据"
                            page.tvCourseSearchSubtitle.text = "支持教师/课程名/代码/学期同查"
                        }
                        if (forceRefresh) {
                            Toast.makeText(this@MainActivity, "同步失败，请检查是否正确连接无线校园网", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    page.progressBarSearch.visibility = View.GONE
                    page.btnDoSearch.isEnabled = true
                    if (forceRefresh) {
                        Toast.makeText(this@MainActivity, "同步失败，请检查是否正确连接无线校园网", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnConfirmFilters.setOnClickListener {
            filterDialog.dismiss()
            executeSearch(forceRefresh = false)
        }

        page.btnDoSearch.setOnClickListener { executeSearch(forceRefresh = false) }
        page.etSearchCourse.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                executeSearch(forceRefresh = false)
                true
            } else false
        }
        page.etSearchTeacher.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                executeSearch(forceRefresh = false)
                true
            } else false
        }
        // 右上角手动刷新按键：强制从校园网拉取最新排课数据并更新本地缓存
        page.btnClearSearch.setOnClickListener {
            val hasActive = !page.etSearchCourse.text.isNullOrBlank() ||
                    !page.etSearchTeacher.text.isNullOrBlank() ||
                    selectedSearchCollege.isNotEmpty() ||
                    selectedSearchDay != -1 ||
                    selectedSearchWeek != -1 ||
                    selectedSearchSection != "-1" ||
                    selectedSearchLevel != "ALL"
            if (hasActive) {
                executeSearch(forceRefresh = true)
            } else {
                checkAndLoadDefaultCourseSearch(isUserInitiatedReset = true, forceRefresh = true)
            }
        }

        checkAndLoadDefaultCourseSearch(isUserInitiatedReset = false, forceRefresh = false)
    }

    private fun showSchoolCourseConflictDialog(
        tableId: Int,
        mergedCourse: MergedSchoolCourse,
        conflicts: List<CourseConflictInfo>
    ) {
        val conflictLines = conflicts.joinToString("\n") { it.formatConflictSummary() }
        val message = "检测到《${mergedCourse.kcmc}》与当前课表中的以下课程存在时间冲突：\n\n$conflictLines\n\n是否仍要将其添加到课表？"

        MaterialAlertDialogBuilder(this)
            .setTitle("课程时间冲突提示")
            .setMessage(message)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton("仍要添加") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    db.addMergedSchoolCourse(tableId, mergedCourse)
                    withContext(Dispatchers.Main) {
                        reloadTimetableFromDb()
                        Toast.makeText(
                            this@MainActivity,
                            "已成功添加《${mergedCourse.kcmc}》(共${mergedCourse.slots.size}个时段) 到课表！",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==========================================
    // --- 5. Free Classroom Full Page ---
    // ==========================================
    private var isFreeClassroomInitialized = false
    private var freeClassroomAdapter: FreeClassroomAdapter? = null
    private val allFreeRooms = mutableListOf<FreeClassroomInfo>()
    private val displayFreeRooms = mutableListOf<FreeClassroomInfo>()
    private var selectedFreeBuilding = "全部教学楼"
    private var selectedFreeDay = 1
    private var selectedFreeSection = "1-2"

    private fun initFreeClassroomPage() {
        if (isFreeClassroomInitialized) return
        isFreeClassroomInitialized = true

        val page = binding.pageFreeClassroom
        page.btnOpenDrawerFreeClassroom.setOnClickListener {
            if (isGuestMode) {
                finish()
            } else if (isPhoneBottomBarMode()) {
                switchTab(MainTab.MORE_FEATURES)
            } else {
                openNavDrawer()
            }
        }

        freeClassroomAdapter = FreeClassroomAdapter(displayFreeRooms)
        page.rvFreeClassroomList.layoutManager = if (isTabletLandscapeMode) GridLayoutManager(this, 3) else LinearLayoutManager(this)
        page.rvFreeClassroomList.adapter = freeClassroomAdapter

        val buildingOptions = LappClient.ALL_KNOWN_BUILDINGS
        val bAdapter = ArrayAdapter(this, R.layout.item_spinner_free_classroom, buildingOptions)
        bAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown_free_classroom)
        page.spinnerFreeBuilding.adapter = bAdapter

        val cal = Calendar.getInstance()
        val rawDay = cal.get(Calendar.DAY_OF_WEEK)
        val todayZj = if (rawDay == Calendar.SUNDAY) 7 else rawDay - 1
        selectedFreeDay = todayZj

        val baseDays = listOf(
            Pair("周一", 1),
            Pair("周二", 2),
            Pair("周三", 3),
            Pair("周四", 4),
            Pair("周五", 5),
            Pair("周六", 6),
            Pair("周日", 7)
        )
        val dayOptions = baseDays.map { (name, zj) ->
            if (zj == todayZj) Pair("$name（今日）", zj) else Pair(name, zj)
        }
        val dAdapter = ArrayAdapter(this, R.layout.item_spinner_free_classroom, dayOptions.map { it.first })
        dAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown_free_classroom)
        page.spinnerFreeDay.adapter = dAdapter
        val todayIdx = dayOptions.indexOfFirst { it.second == todayZj }.coerceAtLeast(0)
        page.spinnerFreeDay.setSelection(todayIdx)

        val sectionOptions = listOf(
            Pair("1-2节 (08:00-09:25)", "1-2"),
            Pair("3-5节 (09:45-11:55)", "3-5"),
            Pair("6-7节 (13:00-14:25)", "6-7"),
            Pair("8-10节 (14:45-16:55)", "8-9"),
            Pair("11-13节(18:00-20:15)", "10-12")
        )
        val sAdapter = ArrayAdapter(this, R.layout.item_spinner_free_classroom, sectionOptions.map { it.first })
        sAdapter.setDropDownViewResource(R.layout.item_spinner_dropdown_free_classroom)
        page.spinnerFreeSection.adapter = sAdapter

        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val defaultSecIdx = when {
            hour < 10 -> 0
            hour < 12 -> 1
            hour < 15 -> 2
            hour < 17 -> 3
            else -> 4
        }
        page.spinnerFreeSection.setSelection(defaultSecIdx)
        selectedFreeSection = sectionOptions[defaultSecIdx].second

        var isOnlyFreeRooms = true

        fun updateOnlyFreeBtnStyle() {
            if (isOnlyFreeRooms) {
                page.btnOnlyFreeRooms.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
                page.btnOnlyFreeRooms.setTextColor(ContextCompat.getColor(this, android.R.color.white))
                page.btnOnlyFreeRooms.text = "仅空闲"
            } else {
                page.btnOnlyFreeRooms.setBackgroundColor(ContextCompat.getColor(this, R.color.surface_variant))
                page.btnOnlyFreeRooms.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                page.btnOnlyFreeRooms.text = "全部教室"
            }
        }
        updateOnlyFreeBtnStyle()

        fun filterAndDisplay(showToast: Boolean = false, isManualRefresh: Boolean = false) {
            val filtered = if (isOnlyFreeRooms) {
                allFreeRooms.filter { it.isFree }
            } else {
                // 关闭仅空闲时，按已知教室编号从小到大依次展示所有教室
                allFreeRooms.sortedBy { LappClient.ALL_KNOWN_CLASSROOMS_ORDER[it.name] ?: 9999 }
            }
            displayFreeRooms.clear()
            displayFreeRooms.addAll(filtered)
            freeClassroomAdapter?.notifyDataSetChanged()

            val freeCount = allFreeRooms.count { it.isFree }
            val totalCount = allFreeRooms.size
            val cacheTag = LappClient.formatCacheTag()
            val cacheSuffix = if (cacheTag.isNotEmpty()) " ($cacheTag)" else ""
            val curWeek = getEffectiveCurrentWeek()
            val targetWeek = if (curWeek in 1..25) curWeek else 1
            if (totalCount > 0) {
                page.tvFreeClassroomSubtitle.text = "第 $targetWeek 周 · 空闲 $freeCount / 共 $totalCount 间$cacheSuffix"
            }

            if (showToast && totalCount > 0) {
                val toastMsg = if (isManualRefresh) {
                    "已手动刷新 · 空闲 $freeCount / 共 $totalCount 间"
                } else {
                    "空闲 $freeCount / 共 $totalCount 间"
                }
                Toast.makeText(this@MainActivity, toastMsg, Toast.LENGTH_SHORT).show()
            }

            if (displayFreeRooms.isEmpty()) {
                page.rvFreeClassroomList.visibility = View.GONE
                page.layoutEmptyFreeClassroom.visibility = View.VISIBLE
            } else {
                page.rvFreeClassroomList.visibility = View.VISIBLE
                page.layoutEmptyFreeClassroom.visibility = View.GONE
            }
        }

        fun fetchFreeClassrooms(isUserQueryClick: Boolean = false, forceRefresh: Boolean = false) {
            page.progressBarFreeClassroom.visibility = View.VISIBLE
            page.btnQueryFreeClassroom.isEnabled = false
            page.btnRefreshFreeClassroom.isEnabled = false

            val actualDay = if (selectedFreeDay == 0) todayZj else selectedFreeDay
            val curWeek = getEffectiveCurrentWeek()
            val targetWeek = if (curWeek in 1..25) curWeek else 1

            lifecycleScope.launch {
                val result = LappClient.queryFreeClassrooms(
                    context = this@MainActivity,
                    xn = "2026-2027",
                    xq = "1",
                    week = targetWeek,
                    zj = actualDay,
                    jc = selectedFreeSection,
                    building = selectedFreeBuilding,
                    forceRefresh = forceRefresh
                )
                page.progressBarFreeClassroom.visibility = View.GONE
                page.btnQueryFreeClassroom.isEnabled = true
                page.btnRefreshFreeClassroom.isEnabled = true

                if (result.isSuccess) {
                    val list = result.getOrNull() ?: emptyList()
                    allFreeRooms.clear()
                    allFreeRooms.addAll(list)
                    filterAndDisplay(showToast = isUserQueryClick, isManualRefresh = forceRefresh)
                } else if (forceRefresh) {
                    Toast.makeText(this@MainActivity, "同步失败，请检查是否正确连接无线校园网", Toast.LENGTH_SHORT).show()
                }
            }
        }

        page.spinnerFreeBuilding.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                selectedFreeBuilding = buildingOptions[position]
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        page.spinnerFreeDay.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                selectedFreeDay = dayOptions[position].second
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        page.spinnerFreeSection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, id: Long) {
                selectedFreeSection = sectionOptions[position].second
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        page.btnOnlyFreeRooms.setOnClickListener {
            isOnlyFreeRooms = !isOnlyFreeRooms
            updateOnlyFreeBtnStyle()
            filterAndDisplay(showToast = false)
        }

        page.btnQueryFreeClassroom.setOnClickListener {
            fetchFreeClassrooms(isUserQueryClick = true, forceRefresh = false)
        }

        page.btnRefreshFreeClassroom.setOnClickListener {
            fetchFreeClassrooms(isUserQueryClick = true, forceRefresh = true)
        }

        fetchFreeClassrooms(isUserQueryClick = false, forceRefresh = false)
    }

    // ==========================================
    // --- 6. Exam Query Full Page ---
    // ==========================================
    private var isExamQueryInitialized = false
    private var examAdapter: ExamAdapter? = null
    private var examXnm: String = ""
    private var examXqm: String = ""

    private fun initExamQueryPage() {
        if (isExamQueryInitialized) return
        isExamQueryInitialized = true

        val page = binding.pageExamQuery
        page.btnOpenDrawerExamQuery.setOnClickListener {
            if (isPhoneBottomBarMode()) {
                switchTab(MainTab.MORE_FEATURES)
            } else {
                openNavDrawer()
            }
        }

        examAdapter = ExamAdapter(emptyList())
        page.rvExamQueryList.layoutManager = if (isTabletLandscapeMode) GridLayoutManager(this, 2) else LinearLayoutManager(this)
        page.rvExamQueryList.adapter = examAdapter

        val (curXnm, curXqm) = SemesterHelper.getCurrentSemester()
        examXnm = curXnm
        examXqm = curXqm

        fun formatSemesterName(xnm: String, xqm: String): String {
            val nextY = (xnm.toIntOrNull() ?: 2026) + 1
            val semName = when (xqm) {
                "3" -> "第一学期"
                "12" -> "第二学期"
                "16" -> "短学期"
                else -> "第一学期"
            }
            return "$xnm-$nextY $semName"
        }

        page.tvExamSemesterBadge.text = formatSemesterName(examXnm, examXqm)

        if (CourseReminderManager.isExamReminderEnabled(this)) {
            val adv = CourseReminderManager.formatAdvanceMinutes(CourseReminderManager.getExamReminderAdvanceMinutes(this))
            page.tvExamQuerySubtitle.text = "支持期末、期中与缓补考 · 考前 $adv 自动提醒"
        } else {
            page.tvExamQuerySubtitle.text = "期末、期中与缓补考日程查询 · 考场与座号"
        }

        fun updateExamList(exams: List<ExamItem>) {
            currentExamList = exams
            if (exams.isEmpty()) {
                page.rvExamQueryList.visibility = View.GONE
                page.layoutEmptyExamQuery.visibility = View.VISIBLE
                page.tvExamUpcomingSummary.text = "该学期暂无排定考试"
            } else {
                page.rvExamQueryList.visibility = View.VISIBLE
                page.layoutEmptyExamQuery.visibility = View.GONE

                val sorted = exams.sortedWith { e1, e2 ->
                    val t1 = e1.getExamStartTimeMillis() ?: Long.MAX_VALUE
                    val t2 = e2.getExamStartTimeMillis() ?: Long.MAX_VALUE
                    t1.compareTo(t2)
                }
                examAdapter?.submitList(sorted)

                val upcoming = sorted.firstOrNull { (it.getExamStartTimeMillis() ?: 0L) > System.currentTimeMillis() }
                if (upcoming != null) {
                    page.tvExamUpcomingSummary.text = "共 ${sorted.size} 门考试 · 下一场: ${upcoming.courseName} (${upcoming.getCountdownString()})"
                } else {
                    page.tvExamUpcomingSummary.text = "共 ${sorted.size} 门考试 · 当前已全部完成"
                }
            }
        }

        fun fetchOnlineExams() {
            page.progressBarExamQuery.visibility = View.VISIBLE
            page.btnRefreshExamQuery.isEnabled = false
            page.btnEmptySyncExamQuery.isEnabled = false

            lifecycleScope.launch {
                val res = JwglClient.fetchExams(examXnm, examXqm, this@MainActivity)
                page.progressBarExamQuery.visibility = View.GONE
                page.btnRefreshExamQuery.isEnabled = true
                page.btnEmptySyncExamQuery.isEnabled = true

                res.onSuccess { exams ->
                    cacheManager.saveExams(examXnm, examXqm, exams)
                    CourseReminderManager.scheduleExamReminders(this@MainActivity, exams)
                    updateExamList(exams)
                    val msg = if (exams.isNotEmpty()) "已同步 ${exams.size} 门考试" else "所选学期暂无考试安排"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    Toast.makeText(this@MainActivity, "同步考试日程失败: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val examSemesterOptions = listOf(
            Triple("2026-2027 第一学期 (当前)", "2026", "3"),
            Triple("2025-2026 第二学期", "2025", "12"),
            Triple("2025-2026 第一学期", "2025", "3"),
            Triple("2024-2025 第二学期", "2024", "12"),
            Triple("2024-2025 第一学期", "2024", "3"),
            Triple("2023-2024 第二学期", "2023", "12"),
            Triple("2023-2024 第一学期", "2023", "3")
        )

        page.btnSwitchExamSemester.setOnClickListener {
            val titles = examSemesterOptions.map { it.first }.toTypedArray()
            val currentIdx = examSemesterOptions.indexOfFirst { it.second == examXnm && it.third == examXqm }.coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle("选择考试查询学期")
                .setSingleChoiceItems(titles, currentIdx) { dialog, which ->
                    dialog.dismiss()
                    val selected = examSemesterOptions[which]
                    examXnm = selected.second
                    examXqm = selected.third
                    page.tvExamSemesterBadge.text = formatSemesterName(examXnm, examXqm)

                    val cachedForSem = cacheManager.getExams(examXnm, examXqm)
                    if (cachedForSem.isNotEmpty()) {
                        updateExamList(cachedForSem)
                    } else {
                        fetchOnlineExams()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        val cached = cacheManager.getExams(examXnm, examXqm).let {
            if (it.isEmpty()) cacheManager.getAllCachedExams() else it
        }
        if (cached.isNotEmpty()) {
            CourseReminderManager.scheduleExamReminders(this, cached)
            updateExamList(cached)
        } else {
            fetchOnlineExams()
        }

        page.btnExportExamCalendar.setOnClickListener {
            CalendarExportHelper.showExamCalendarDialog(this@MainActivity, currentExamList) {
                checkAndRequestCalendarPermission {
                    lifecycleScope.launch {
                        val result = CalendarExportHelper.importExamsToSystemCalendar(this@MainActivity, currentExamList)
                        result.onSuccess { count ->
                            Toast.makeText(this@MainActivity, "成功将 $count 门考试导入系统日历 (含30分钟考前提醒)", Toast.LENGTH_LONG).show()
                        }.onFailure { err ->
                            Toast.makeText(this@MainActivity, "导入系统日历失败: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        page.btnRefreshExamQuery.setOnClickListener { fetchOnlineExams() }
        page.btnEmptySyncExamQuery.setOnClickListener { fetchOnlineExams() }
    }

    // ==========================================
    // --- 7. Grade Reports Full Page ---
    // ==========================================
    private var isGradeReportsInitialized = false

    private fun initGradeReportsPage() {
        if (isGradeReportsInitialized) {
            refreshDownloadedFilesList()
            return
        }
        isGradeReportsInitialized = true

        val page = binding.pageGradeReports
        page.btnOpenDrawerGradeReports.setOnClickListener {
            if (isPhoneBottomBarMode()) {
                switchTab(MainTab.MORE_FEATURES)
            } else {
                openNavDrawer()
            }
        }

        val auth = AuthPreferences(this)
        val sId = auth.getStudentId()
        val profile = currentProfile
        val sName = profile?.name?.ifEmpty { "同学" } ?: "同学"
        val sCollege = profile?.college ?: "上海理工大学"
        val sMajor = profile?.major ?: ""
        page.tvReportsStudentName.text = "$sName · $sId"
        page.tvReportsStudentAvatar.text = if (sName.isNotEmpty()) sName.take(1) else "理"
        page.tvReportsStudentInfo.text = if (sMajor.isNotEmpty()) "$sCollege · $sMajor" else sCollege

        // Connect download buttons for the 5 document types
        page.btnDownloadChineseWeighted.setOnClickListener {
            downloadAndOpenDocument(GradeDocumentType.CHINESE_WEIGHTED_SCORE)
        }
        page.btnDownloadEnglishWeighted.setOnClickListener {
            downloadAndOpenDocument(GradeDocumentType.ENGLISH_WEIGHTED_SCORE)
        }
        page.btnDownloadRanking.setOnClickListener {
            downloadAndOpenDocument(GradeDocumentType.RANKING_CERTIFICATE)
        }
        page.btnDownloadChineseTranscript.setOnClickListener {
            downloadAndOpenDocument(GradeDocumentType.CHINESE_TRANSCRIPT)
        }
        page.btnDownloadEnglishTranscript.setOnClickListener {
            downloadAndOpenDocument(GradeDocumentType.ENGLISH_TRANSCRIPT)
        }

        page.tvRefreshDownloadedFiles.setOnClickListener {
            refreshDownloadedFilesList()
        }

        refreshDownloadedFilesList()
    }

    private fun refreshDownloadedFilesList() {
        val page = binding.pageGradeReports
        val targetDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: java.io.File(filesDir, "downloads")
        val files = targetDir.listFiles { file -> file.isFile && file.name.endsWith(".pdf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

        val container = page.llDownloadedFilesContainer
        container.removeAllViews()

        if (files.isEmpty()) {
            page.tvNoDownloadedFiles.visibility = View.VISIBLE
        } else {
            page.tvNoDownloadedFiles.visibility = View.GONE
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())

            for (file in files) {
                val card = com.google.android.material.card.MaterialCardView(this).apply {
                    radius = 12 * resources.displayMetrics.density
                    strokeColor = ContextCompat.getColor(this@MainActivity, R.color.border)
                    strokeWidth = (0.8 * resources.displayMetrics.density).toInt()
                    setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.surface))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (8 * resources.displayMetrics.density).toInt()
                    }
                    layoutParams = lp
                }

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val pad = (12 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                }

                val tvIcon = TextView(this).apply {
                    text = "📕"
                    textSize = 20f
                }
                row.addView(tvIcon)

                val textContainer = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (10 * resources.displayMetrics.density).toInt()
                    }
                    layoutParams = lp
                }

                val tvName = TextView(this).apply {
                    text = file.name
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    textSize = 13.5f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                val tvDetails = TextView(this).apply {
                    text = "${String.format("%.1f", file.length() / 1024f)} KB · ${sdf.format(java.util.Date(file.lastModified()))}"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    textSize = 11.5f
                }
                textContainer.addView(tvName)
                textContainer.addView(tvDetails)
                row.addView(textContainer)

                val btnOpen = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "打开"
                    textSize = 12f
                    setPadding(0, 0, 0, 0)
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minHeight = 0
                    val lp = LinearLayout.LayoutParams((58 * resources.displayMetrics.density).toInt(), (32 * resources.displayMetrics.density).toInt())
                    layoutParams = lp
                    setOnClickListener { openPdfFile(file) }
                }
                val btnShare = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = "分享"
                    textSize = 12f
                    setPadding(0, 0, 0, 0)
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    minHeight = 0
                    val lp = LinearLayout.LayoutParams((58 * resources.displayMetrics.density).toInt(), (32 * resources.displayMetrics.density).toInt()).apply {
                        marginStart = (6 * resources.displayMetrics.density).toInt()
                    }
                    layoutParams = lp
                    setOnClickListener { sharePdfFile(file) }
                }
                row.addView(btnOpen)
                row.addView(btnShare)

                card.addView(row)
                container.addView(card)
            }
        }
    }
}

