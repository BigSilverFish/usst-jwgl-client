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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.widget.ViewPager2
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
import cn.edu.usst.jwgl.data.wakeup.CourseUtils
import cn.edu.usst.jwgl.data.wakeup.TableBean
import cn.edu.usst.jwgl.data.wakeup.WakeupScheduleImporter
import cn.edu.usst.jwgl.databinding.ActivityMainBinding
import cn.edu.usst.jwgl.ui.adapter.CourseGradeAdapter
import cn.edu.usst.jwgl.ui.wakeup.AddCourseActivity
import cn.edu.usst.jwgl.ui.wakeup.ScheduleManagerBottomSheet
import cn.edu.usst.jwgl.ui.wakeup.SchedulePagerAdapter
import cn.edu.usst.jwgl.util.CourseReminderManager
import cn.edu.usst.jwgl.util.ExamHelper
import cn.edu.usst.jwgl.util.InitialSyncHelper
import cn.edu.usst.jwgl.util.SemesterHelper
import cn.edu.usst.jwgl.util.ThemeManager
import cn.edu.usst.jwgl.util.TimetableSettingHelper
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE = "extra_student_profile"
        var topBarHeightPx: Int = 0
        var bottomBarHeightPx: Int = 0
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cacheManager: DataCacheManager
    private lateinit var db: AppDatabase
    private val gradeAdapter = CourseGradeAdapter()
    private var gradeReport: GradeReport? = null
    private var selectedGradeSemesterTitle: String = ""

    private var currentTable: TableBean? = null
    private var scheduleAdapter: SchedulePagerAdapter? = null
    private var currentWeek: Int = 1

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
            val spacerHeight = maxOf(navBarBottom, (18 * density).toInt())
            if (binding.viewNavBarSpacer.layoutParams.height != spacerHeight) {
                binding.viewNavBarSpacer.layoutParams.height = spacerHeight
                binding.viewNavBarSpacer.requestLayout()
            }

            // Reset bottomNav internal padding so items are not squashed or spread apart
            binding.bottomNav.setPadding(0, 0, 0, 0)

            // Shift bottomNav labels down slightly so they don't stick too close to the icons / active indicator
            val applyBottomNavLabelAdjustment = {
                val menuView = binding.bottomNav.getChildAt(0) as? ViewGroup
                if (menuView != null) {
                    menuView.clipChildren = false
                    menuView.clipToPadding = false
                    val shiftPx = 3.5f * density
                    for (i in 0 until menuView.childCount) {
                        val item = menuView.getChildAt(i) as? ViewGroup ?: continue
                        item.clipChildren = false
                        item.clipToPadding = false
                        val labelGroup = item.findViewById<View>(com.google.android.material.R.id.navigation_bar_item_labels_group)
                        labelGroup?.translationY = shiftPx
                    }
                }
            }
            binding.bottomNav.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                applyBottomNavLabelAdjustment()
            }
            binding.bottomNav.post {
                applyBottomNavLabelAdjustment()
            }

            // Adjust grades spacing view to match floating header height
            binding.llGradesHeaderContainer.post {
                val gradesHeaderH = binding.llGradesHeaderContainer.height
                if (gradesHeaderH > 0) {
                    updateGradesHeaderSpacing(gradesHeaderH)
                }
            }

            binding.blurViewBottomNav.post {
                bottomBarHeightPx = binding.blurViewBottomNav.height
                binding.scrollProfile.setPadding(
                    binding.scrollProfile.paddingLeft,
                    statusBarInsets.top,
                    binding.scrollProfile.paddingRight,
                    bottomBarHeightPx
                )
                binding.scrollGrades.setPadding(
                    binding.scrollGrades.paddingLeft,
                    binding.scrollGrades.paddingTop,
                    binding.scrollGrades.paddingRight,
                    bottomBarHeightPx
                )
            }

            insets
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val decorView = window.decorView
            val rootView = decorView.findViewById<ViewGroup>(android.R.id.content)
            val windowBackground = decorView.background

            binding.blurViewBottomNav.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)

            binding.blurViewTopBar.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)

            binding.blurViewGradesHeader.setupWith(rootView, RenderEffectBlur())
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)
        }

        cacheManager = DataCacheManager(this)
        db = AppDatabase.getDatabase(this)
        RemoteConfigManager.init(this)

        val profile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_PROFILE, StudentProfile::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_PROFILE) as? StudentProfile
        } ?: cacheManager.getProfile()

        displayProfile(profile)
        setupViews()
        setupListeners()
        initWakeupSchedule()
        syncRemoteConfig(isManual = false)

        if (!AuthPreferences(this).hasAgreedDisclaimer()) {
            showAuthorDisclaimerDialog(allowDismiss = false)
        }

        val initialTab = intent.getIntExtra("extra_tab", 1) // Default to 1 (课程表)
        when (initialTab) {
            0 -> binding.bottomNav.selectedItemId = R.id.nav_profile
            2 -> binding.bottomNav.selectedItemId = R.id.nav_grades
            else -> binding.bottomNav.selectedItemId = R.id.nav_timetable
        }

        handleIntentExtras(intent)

        val authPrefs = AuthPreferences(this)
        if (!authPrefs.hasInitialSyncCompleted() && authPrefs.getStudentId().isNotEmpty()) {
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

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        reloadTimetableFromDb()
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
        if (tab in 0..2) {
            when (tab) {
                0 -> binding.bottomNav.selectedItemId = R.id.nav_profile
                1 -> binding.bottomNav.selectedItemId = R.id.nav_timetable
                2 -> binding.bottomNav.selectedItemId = R.id.nav_grades
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
                    currentWeek = 1
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
                    currentWeek = 1
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
            selectedGradeSemesterTitle = if (gradeSem == "ALL") "" else gradeSem
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

                // Auto-seed sample exam schedule in debug mode if empty for previewing exam weeks
                if (BuildConfig.DEBUG && cacheManager.getAllCachedExams().isEmpty()) {
                    cacheManager.saveExams("2026", "3", listOf(
                        ExamItem(
                            courseName = "高等数学A(1)",
                            courseCode = "1001001",
                            examName = "期末考试",
                            examTime = "2027-01-13 09:00-11:00",
                            location = "一教301",
                            building = "第一教学楼",
                            seatNumber = "25",
                            session = "第一场",
                            examNature = "正常",
                            examMethod = "笔试(闭卷)",
                            credit = 5.0,
                            yearName = "2026-2027",
                            semesterName = "1",
                            remarks = "请携带学生证和身份证"
                        ),
                        ExamItem(
                            courseName = "大学物理B",
                            courseCode = "1002002",
                            examName = "期末考试",
                            examTime = "2027-01-14 13:00-15:00",
                            location = "二教205",
                            building = "第二教学楼",
                            seatNumber = "12",
                            session = "第二场",
                            examNature = "正常",
                            examMethod = "笔试(闭卷)",
                            credit = 4.0,
                            yearName = "2026-2027",
                            semesterName = "1",
                            remarks = "可携带科学计算器"
                        )
                    ))
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
            currentWeek = targetWeek

            scheduleAdapter = SchedulePagerAdapter(this@MainActivity, maxWeek, targetTable.id)
            binding.vpSchedule.adapter = scheduleAdapter

            binding.vpSchedule.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    val selectedWeek = position + 1
                    currentWeek = selectedWeek
                    updateWeekSelectionUI(selectedWeek)
                    updateWeekHeaderDates(selectedWeek)
                }
            })

            reloadTimetableFromDb()
        }
    }

    private fun formatSemesterTitle(title: String): String {
        if (title.contains("\n")) return title
        val regex = Regex("(\\d{4}-\\d{4}学年)\\s*(第\\d+学期)")
        return if (regex.containsMatchIn(title)) {
            title.replace(regex, "$1\n$2")
        } else {
            title.replace("学年 ", "学年\n").replace("学年第", "学年\n第")
        }
    }

    private fun reloadTimetableFromDb() {
        lifecycleScope.launch {
            val table = withContext(Dispatchers.IO) {
                db.tableDao.getDefaultTable()
            }
            currentTable = table

            binding.tvTimetableSemester.text = formatSemesterTitle(table.tableName)
            binding.chipFilterOnlyCurrentWeek.isChecked = !table.showOtherWeekCourse

            val realCurrentWeek = CourseUtils.countWeek(table.startDate)
            if (currentWeek !in 1..table.maxWeek) {
                currentWeek = if (realCurrentWeek in 1..table.maxWeek) realCurrentWeek else 1
            }

            scheduleAdapter?.updateConfig(table.maxWeek, table.id)

            binding.vpSchedule.setCurrentItem(currentWeek - 1, false)
            updateWeekSelectionUI(currentWeek)
            updateWeekHeaderDates(currentWeek)

            scheduleAdapter?.refreshAllFragments()
        }
    }

    private fun updateWeekSelectionUI(week: Int) {
        val table = currentTable ?: return
        val realCurrentWeek = CourseUtils.countWeek(table.startDate)
        val isCurrent = (week == realCurrentWeek)
        binding.tvCurrentWeekIndicator.text = if (isCurrent) "第 $week 周 (本周)" else "第 $week 周"
    }

    private fun setupViews() {
        binding.rvGrades.layoutManager = LinearLayoutManager(this)
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

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_timetable -> {
                    binding.layoutProfile.visibility = View.GONE
                    binding.layoutTimetable.visibility = View.VISIBLE
                    binding.layoutGrades.visibility = View.GONE
                    reloadTimetableFromDb()
                    true
                }
                R.id.nav_grades -> {
                    binding.layoutProfile.visibility = View.GONE
                    binding.layoutTimetable.visibility = View.GONE
                    binding.layoutGrades.visibility = View.VISIBLE
                    binding.llGradesHeaderContainer.post {
                        updateGradesHeaderSpacing(binding.llGradesHeaderContainer.height)
                    }
                    if (gradeReport == null) {
                        showOrLoadGrades()
                    }
                    true
                }
                R.id.nav_profile -> {
                    binding.layoutProfile.visibility = View.VISIBLE
                    binding.layoutTimetable.visibility = View.GONE
                    binding.layoutGrades.visibility = View.GONE
                    true
                }
                else -> false
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
        binding.btnScheduleManager.setOnClickListener(openScheduleManager)
        binding.btnSelectWeek.setOnClickListener { showWeekPickerDialog() }

        // Add Course (WakeUP course add / edit activity)
        binding.btnAddCourse.setOnClickListener {
            val intent = Intent(this, AddCourseActivity::class.java).apply {
                putExtra("tableId", currentTable?.id ?: 1)
            }
            addCourseLauncher.launch(intent)
        }

        // Font Size Dialog (adjust item height and text size)
        binding.btnFontSize.setOnClickListener {
            showFontSizeDialog()
        }

        // Filter: Show non-current week courses toggle
        binding.chipFilterOnlyCurrentWeek.setOnCheckedChangeListener { _, isChecked ->
            val table = currentTable ?: return@setOnCheckedChangeListener
            table.showOtherWeekCourse = !isChecked
            lifecycleScope.launch(Dispatchers.IO) { db.tableDao.updateTable(table) }
            scheduleAdapter?.refreshAllFragments()
        }

        // Exam Query Button
        binding.btnExamQuery.setOnClickListener {
            showExamQueryBottomSheet()
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
        binding.btnDownloadGradeReports.setOnClickListener { showDownloadGradeReportsDialog() }

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

        binding.llDayHeaderContainer.removeAllViews()
        val daysArray = if (table.sundayFirst) {
            arrayOf("日", "周一", "周二", "周三", "周四", "周五", "周六")
        } else {
            arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        }

        val totalDays = 7
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

            val dayView = android.widget.LinearLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                if (isToday) {
                    background = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.badge_bg)
                }
            }

            val tvDayName = android.widget.TextView(this).apply {
                val badge = when {
                    resolved.isHolidayOff -> " [休]"
                    resolved.isSwapped -> " [调]"
                    isExamWeek && dayExams.isNotEmpty() -> " [考]"
                    else -> ""
                }
                text = "${daysArray[i]}$badge"
                textSize = if (badge.isNotEmpty()) 10.5f else 12f
                typeface = if (isToday) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                setTextColor(if (isToday) androidx.core.content.ContextCompat.getColor(context, R.color.primary) else if (resolved.isHolidayOff) 0xFF4CAF50.toInt() else androidx.core.content.ContextCompat.getColor(context, R.color.text_primary))
                gravity = android.view.Gravity.CENTER
            }

            val tvDayDate = android.widget.TextView(this).apply {
                val dateStr = if (i + 1 < dateStrings.size) dateStrings[i + 1] else ""
                text = "$dateStr 日"
                textSize = 10.5f
                setTextColor(if (isToday) androidx.core.content.ContextCompat.getColor(context, R.color.primary) else androidx.core.content.ContextCompat.getColor(context, R.color.text_secondary))
                gravity = android.view.Gravity.CENTER
            }

            dayView.addView(tvDayName)
            dayView.addView(tvDayDate)
            binding.llDayHeaderContainer.addView(dayView)
        }
    }

    private fun displayProfile(profile: StudentProfile?) {
        binding.tvAppVersion.text = "v${BuildConfig.VERSION_NAME}"
        val semConfig = RemoteConfigManager.getSemesterConfig()
        binding.tvSemesterConfigSummary.text = "${semConfig.currentSemester} · 第 1 周 ${semConfig.week1Monday}"

        binding.tvDarkModeStatus.text = ThemeManager.getThemeModeName(ThemeManager.getThemeMode(this))
        binding.switchCourseReminder.isChecked = CourseReminderManager.isReminderEnabled(this)
        binding.switchExamReminder.isChecked = CourseReminderManager.isExamReminderEnabled(this)
        binding.switchDayPartDividers.isChecked = TimetableSettingHelper.isDayPartDividersEnabled(this)
        updateReminderSubtitles()

        if (profile == null) return

        binding.tvStudentName.text = profile.name.ifEmpty { "学生用户" }
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
                val profile = JwglClient.fetchStudentProfile(auth.getStudentId())
                cacheManager.saveProfile(profile)
                displayProfile(profile)
                Toast.makeText(this@MainActivity, "个人信息与档案已更新", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
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

        binding.rowJwglWeb.setOnClickListener {
            JwglWebActivity.start(this)
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
                        showUpdateDialog(config.appVersion)
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

    private fun showUpdateDialog(versionInfo: AppVersionInfo) {
        val notes = if (versionInfo.releaseNotes.isNotBlank()) "\n\n更新说明：\n${versionInfo.releaseNotes}" else ""
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("发现新版本 v${versionInfo.versionName}")
            .setMessage("检测到新版本发布 (发布日期: ${versionInfo.releaseDate})$notes")
            .setPositiveButton("立即下载更新") { _, _ ->
                if (versionInfo.downloadUrl.isNotBlank()) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(versionInfo.downloadUrl))
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this, "无法打开下载链接: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "暂无直接下载链接", Toast.LENGTH_SHORT).show()
                }
            }

        if (!versionInfo.forceUpdate) {
            dialog.setNegativeButton("稍后再说", null)
        } else {
            dialog.setCancelable(false)
        }
        dialog.safeShow()
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
}