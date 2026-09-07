package cn.edu.usst.jwgl.ui

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.CustomCourseManager
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.data.model.CourseItem
import cn.edu.usst.jwgl.data.model.GradeReport
import cn.edu.usst.jwgl.data.model.StudentProfile
import cn.edu.usst.jwgl.data.model.TimetableData
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.databinding.ActivityMainBinding
import cn.edu.usst.jwgl.ui.adapter.CourseGradeAdapter
import cn.edu.usst.jwgl.ui.view.TimetableView
import cn.edu.usst.jwgl.util.SemesterHelper
import cn.edu.usst.jwgl.util.SemesterInfo
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import cn.edu.usst.jwgl.BuildConfig
import cn.edu.usst.jwgl.data.model.AppVersionInfo
import cn.edu.usst.jwgl.data.remote.RemoteConfigManager
import cn.edu.usst.jwgl.util.CourseReminderManager
import cn.edu.usst.jwgl.util.ThemeManager
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE = "extra_student_profile"
        private val CARD_PALETTE = intArrayOf(
            0xFF4A90E2.toInt(), 0xFF50E3C2.toInt(), 0xFFB8E986.toInt(), 0xFFF5A623.toInt(),
            0xFFBD10E0.toInt(), 0xFF9013FE.toInt(), 0xFFFF6F61.toInt(), 0xFF4ECDC4.toInt(),
            0xFFFF8B94.toInt(), 0xFF45B649.toInt(), 0xFF3498DB.toInt(), 0xFF9B59B6.toInt(),
            0xFFE67E22.toInt(), 0xFF1ABC9C.toInt()
        )
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var customCourseManager: CustomCourseManager
    private lateinit var cacheManager: DataCacheManager
    private val gradeAdapter = CourseGradeAdapter()
    private var gradeReport: GradeReport? = null
    private var timetableData: TimetableData? = null
    private val currentDisplayCourses = mutableListOf<CourseItem>()
    private var currentWeek: Int = 3
    private var currentXnm: String = "2025"
    private var currentXqm: String = "3"

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            binding.switchCourseReminder.isChecked = true
            CourseReminderManager.setReminderEnabled(this, true, currentDisplayCourses)
            Toast.makeText(this, "已开启上课前 15 分钟通知提醒", Toast.LENGTH_SHORT).show()
        } else {
            binding.switchCourseReminder.isChecked = false
            CourseReminderManager.setReminderEnabled(this, false)
            Toast.makeText(this, "需要通知权限才能发送上课提醒", Toast.LENGTH_LONG).show()
        }
    }

    private fun getTodayDayOfWeek(): Int {
        val cal = SemesterHelper.getToday()
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            Calendar.SUNDAY -> 7
            else -> 1
        }
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

        val testDateStr = intent.getStringExtra("extra_test_date")
        if (!testDateStr.isNullOrBlank()) {
            val parts = testDateStr.split("-")
            if (parts.size == 3) {
                SemesterHelper.simulatedToday = Calendar.getInstance().apply {
                    set(Calendar.YEAR, parts[0].toInt())
                    set(Calendar.MONTH, parts[1].toInt() - 1)
                    set(Calendar.DAY_OF_MONTH, parts[2].toInt())
                }
            }
        } else {
            SemesterHelper.simulatedToday = null
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        customCourseManager = CustomCourseManager(this)
        cacheManager = DataCacheManager(this)
        RemoteConfigManager.init(this)
        currentWeek = RemoteConfigManager.getCurrentWeek()

        // Initialize semester based on Feb 1 / Aug 16 boundaries
        val (calcXnm, calcXqm) = SemesterHelper.getCurrentSemester()
        currentXnm = calcXnm
        currentXqm = calcXqm

        // Restore font size preference
        val savedFontScale = customCourseManager.getFontScale()
        binding.timetableView.setFontScale(savedFontScale)

        val profile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_PROFILE, StudentProfile::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_PROFILE) as? StudentProfile
        } ?: cacheManager.getProfile()

        displayProfile(profile)
        setupViews()
        setupListeners()
        syncRemoteConfig(isManual = false)

        val initialTab = intent.getIntExtra("extra_tab", 1) // Default to 1 (课程表)
        when (initialTab) {
            0 -> binding.bottomNav.selectedItemId = R.id.nav_profile
            2 -> binding.bottomNav.selectedItemId = R.id.nav_grades
            else -> binding.bottomNav.selectedItemId = R.id.nav_timetable
        }

        handleIntentExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val powerManager = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        @Suppress("DEPRECATION")
        val wakeLock = powerManager?.newWakeLock(
            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "USST:WakeLock"
        )
        wakeLock?.acquire(3000L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentExtras(intent)
    }

    private fun handleIntentExtras(intent: Intent?) {
        if (intent == null) return
        android.util.Log.d("USST_DEBUG", "handleIntentExtras: extras=${intent.extras?.keySet()?.joinToString()} week=${intent.getIntExtra("extra_week", -1)} onlyCurrent=${intent.getBooleanExtra("extra_only_current_week", true)}")
        val tab = intent.getIntExtra("extra_tab", -1)
        val testDateStr = intent.getStringExtra("extra_test_date")
        if (!testDateStr.isNullOrBlank()) {
            val parts = testDateStr.split("-")
            if (parts.size == 3) {
                SemesterHelper.simulatedToday = Calendar.getInstance().apply {
                    set(Calendar.YEAR, parts[0].toInt())
                    set(Calendar.MONTH, parts[1].toInt() - 1)
                    set(Calendar.DAY_OF_MONTH, parts[2].toInt())
                }
            }
        } else {
            SemesterHelper.simulatedToday = null
        }

        when (tab) {
            0 -> binding.bottomNav.selectedItemId = R.id.nav_profile
            1 -> binding.bottomNav.selectedItemId = R.id.nav_timetable
            2 -> binding.bottomNav.selectedItemId = R.id.nav_grades
        }

        val testTheme = intent.getIntExtra("extra_test_theme", -1)
        if (testTheme in 0..2) {
            ThemeManager.setThemeMode(this, testTheme)
            binding.tvDarkModeStatus.text = ThemeManager.getThemeModeName(testTheme)
        }

        if (intent.getBooleanExtra("extra_test_reminder", false)) {
            CourseReminderManager.scheduleTestReminder(this, "数据结构课程设计", "一教344")
        }

        if (intent.getBooleanExtra("extra_test_update", false)) {
            val dummyUpdate = AppVersionInfo(
                versionCode = 2,
                versionName = "1.1.0",
                releaseDate = "2026-09-06",
                releaseNotes = "1. 全新 Material Design 3 界面风格\n2. 接入云端远程配置与动态开学周次校准\n3. 优化上课时刻表与字体大小自适应",
                downloadUrl = "https://github.com/usst-jwgl/app/releases"
            )
            showUpdateDialog(dummyUpdate)
        }

        val semXnm = intent.getStringExtra("extra_semester_xnm")
        val semXqm = intent.getStringExtra("extra_semester_xqm")
        if (!semXnm.isNullOrBlank() && !semXqm.isNullOrBlank()) {
            currentXnm = semXnm
            currentXqm = semXqm
            val semList = SemesterHelper.getSemesterList()
            val semItem = semList.find { it.xnm == semXnm && it.xqm == semXqm }
            if (semItem != null) {
                binding.tvTimetableSemester.text = semItem.title.replace(Regex("\\s*\\(.*\\)"), "")
            }
            showOrLoadTimetable(currentXnm, currentXqm)
        }

        val week = intent.getIntExtra("extra_week", -1)
        if (week in 1..20) {
            currentWeek = week
            val realCurrentWeek = RemoteConfigManager.getCurrentWeek()
            val semConfig = RemoteConfigManager.getSemesterConfig()
            val isCurrent = (week == realCurrentWeek)
            binding.tvCurrentWeekIndicator.text = if (isCurrent) "第 $week 周 (本周)" else "第 $week 周"
            binding.timetableHeader.setDateInfo(semConfig.week1Monday, week)
            binding.timetableView.setHighlightDayOfWeek(if (isCurrent) getTodayDayOfWeek() else null)
            binding.timetableView.setWeek(week)
            for (i in 0 until binding.weekChipGroup.childCount) {
                val chip = binding.weekChipGroup.getChildAt(i) as? Chip
                chip?.isChecked = (i + 1 == week)
            }
            binding.hsvWeekChips.post {
                val child = binding.weekChipGroup.getChildAt(week - 1)
                if (child != null) {
                    binding.hsvWeekChips.smoothScrollTo(child.left - 50, 0)
                }
            }
        }

        if (intent.hasExtra("extra_only_current_week")) {
            val onlyCurrent = intent.getBooleanExtra("extra_only_current_week", true)
            binding.chipFilterOnlyCurrentWeek.isChecked = onlyCurrent
            binding.timetableView.setShowOnlyCurrentWeek(onlyCurrent)
        }

        if (intent.getBooleanExtra("extra_show_add_course", false)) {
            binding.root.postDelayed({
                showAddCourseDialog()
            }, 300)
        }

        if (intent.getBooleanExtra("extra_show_detail", false)) {
            val reqName = intent.getStringExtra("extra_course_name")
            binding.root.postDelayed({
                val course = if (!reqName.isNullOrBlank()) {
                    currentDisplayCourses.firstOrNull { it.name == reqName }
                } else {
                    currentDisplayCourses.firstOrNull()
                }
                if (course != null) {
                    showCourseDetail(course, currentDisplayCourses.filter { it.dayOfWeek == course.dayOfWeek && it.startSection == course.startSection })
                }
            }, 300)
        }

    }

    private fun setupViews() {
        binding.rvGrades.layoutManager = LinearLayoutManager(this)
        binding.rvGrades.adapter = gradeAdapter

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_timetable -> {
                    binding.layoutProfile.visibility = View.GONE
                    binding.layoutTimetable.visibility = View.VISIBLE
                    binding.layoutGrades.visibility = View.GONE
                    if (timetableData == null) {
                        showOrLoadTimetable(currentXnm, currentXqm)
                    }
                    true
                }
                R.id.nav_grades -> {
                    binding.layoutProfile.visibility = View.GONE
                    binding.layoutTimetable.visibility = View.GONE
                    binding.layoutGrades.visibility = View.VISIBLE
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

        binding.timetableView.onWeekendVisibilityChanged = { hasWeekend ->
            binding.timetableHeader.setWeekendVisible(hasWeekend)
        }

        binding.chipFilterOnlyCurrentWeek.setOnCheckedChangeListener { _, isChecked ->
            binding.timetableView.setShowOnlyCurrentWeek(isChecked)
        }

        binding.timetableView.onCourseClickListener = { course, allCoursesInSlot ->
            showCourseDetail(course, allCoursesInSlot)
        }

        // Semester Picker Dialog
        binding.btnSelectSemester.setOnClickListener {
            showSemesterPickerDialog()
        }

        // Font Size Dialog
        binding.btnFontSize.setOnClickListener {
            showFontSizeDialog()
        }

        // Add Course Dialog
        binding.btnAddCourse.setOnClickListener {
            showAddCourseDialog()
        }

        // Pull to refresh styling
        binding.swipeRefreshTimetable.setColorSchemeResources(R.color.primary)
        binding.swipeRefreshGrades.setColorSchemeResources(R.color.primary)
        binding.layoutProfile.setColorSchemeResources(R.color.primary)

        // Refresh triggers
        binding.swipeRefreshTimetable.setOnRefreshListener {
            loadTimetable(currentXnm, currentXqm, isManual = true)
        }
        binding.btnRefreshTimetable.setOnClickListener {
            loadTimetable(currentXnm, currentXqm, isManual = true)
        }

        binding.swipeRefreshGrades.setOnRefreshListener {
            loadGrades(isManual = true)
        }
        binding.btnRefreshGrades.setOnClickListener {
            loadGrades(isManual = true)
        }

        binding.layoutProfile.setOnRefreshListener {
            refreshProfile()
        }

        // Appearance: Dark Mode selection
        binding.rowDarkMode.setOnClickListener {
            showDarkModeDialog()
        }

        // Notification: Course Reminder Switch (15-min before class)
        binding.switchCourseReminder.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        requestNotificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        return@setOnCheckedChangeListener
                    }
                }
                CourseReminderManager.setReminderEnabled(this, true, currentDisplayCourses)
                Toast.makeText(this, "已开启上课前 15 分钟通知提醒", Toast.LENGTH_SHORT).show()
            } else {
                CourseReminderManager.setReminderEnabled(this, false)
                Toast.makeText(this, "已关闭上课提醒", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun displayProfile(profile: StudentProfile?) {
        binding.tvAppVersion.text = "v${BuildConfig.VERSION_NAME}"
        val semConfig = RemoteConfigManager.getSemesterConfig()
        binding.tvSemesterConfigSummary.text = "${semConfig.currentSemester} · 第 1 周 ${semConfig.week1Monday}"

        // Reflect current theme & reminder status
        binding.tvDarkModeStatus.text = ThemeManager.getThemeModeName(ThemeManager.getThemeMode(this))
        binding.switchCourseReminder.isChecked = CourseReminderManager.isReminderEnabled(this)

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
            .show()
    }

    private fun showSemesterPickerDialog() {
        val semesterList = SemesterHelper.getSemesterList()
        val titles = semesterList.map { it.title }.toTypedArray()
        val currentIdx = semesterList.indexOfFirst { it.xnm == currentXnm && it.xqm == currentXqm }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle("选择查看学期")
            .setSingleChoiceItems(titles, currentIdx) { dialog, which ->
                val selected = semesterList[which]
                if (currentXnm != selected.xnm || currentXqm != selected.xqm) {
                    currentXnm = selected.xnm
                    currentXqm = selected.xqm
                    binding.tvTimetableSemester.text = selected.title.replace(Regex("\\s*\\(.*\\)"), "")
                    dialog.dismiss()
                    showOrLoadTimetable(currentXnm, currentXqm)
                } else {
                    dialog.dismiss()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showFontSizeDialog() {
        val scales = arrayOf(
            Pair("小 (85%)", 0.85f),
            Pair("标准 (100%)", 1.0f),
            Pair("大 (115%)", 1.15f),
            Pair("特大 (130%)", 1.30f)
        )
        val titles = scales.map { it.first }.toTypedArray()
        val currentScale = customCourseManager.getFontScale()
        var currentIdx = scales.indexOfFirst { kotlin.math.abs(it.second - currentScale) < 0.05f }
        if (currentIdx == -1) currentIdx = 1

        MaterialAlertDialogBuilder(this)
            .setTitle("调整课表字体大小")
            .setSingleChoiceItems(titles, currentIdx) { dialog, which ->
                val selectedScale = scales[which].second
                customCourseManager.setFontScale(selectedScale)
                binding.timetableView.setFontScale(selectedScale)
                dialog.dismiss()
                Toast.makeText(this, "字体大小已设置为：${scales[which].first}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showOrLoadTimetable(xnm: String, xqm: String) {
        val cached = cacheManager.getTimetable(xnm, xqm)
        if (cached != null) {
            timetableData = cached
            refreshCoursesList(cached)
            displayTimetable(cached)
            val updateTime = cacheManager.getTimetableUpdateTime(xnm, xqm)
            binding.tvTimetableSyncTime.text = cacheManager.formatUpdateTime(updateTime)
        } else {
            timetableData = null
            currentDisplayCourses.clear()
            binding.timetableView.setCourses(emptyList(), currentWeek)
            binding.tvTimetableSyncTime.text = "正在加载..."
            loadTimetable(xnm, xqm, isManual = false)
        }
    }

    private fun loadTimetable(xnm: String, xqm: String, isManual: Boolean = false) {
        if (isManual) {
            binding.swipeRefreshTimetable.isRefreshing = true
        } else if (timetableData == null) {
            binding.timetableProgressBar.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            val result = JwglClient.fetchTimetable(xnm, xqm, context = this@MainActivity)
            binding.timetableProgressBar.visibility = View.GONE
            binding.swipeRefreshTimetable.isRefreshing = false

            result.onSuccess { data ->
                timetableData = data
                cacheManager.saveTimetable(xnm, xqm, data)
                refreshCoursesList(data)
                displayTimetable(data)
                val updateTime = System.currentTimeMillis()
                binding.tvTimetableSyncTime.text = cacheManager.formatUpdateTime(updateTime)
                if (isManual) {
                    Toast.makeText(this@MainActivity, "${data.semesterTitle} 课表已联网刷新", Toast.LENGTH_SHORT).show()
                }
                if (data.courses.isEmpty()) {
                    Toast.makeText(this@MainActivity, "${data.semesterTitle} 暂无排课数据，您可自主添加课程", Toast.LENGTH_LONG).show()
                }
            }.onFailure { error ->
                val reason = error.message ?: "未知网络异常"
                Toast.makeText(this@MainActivity, "课表联网刷新失败: $reason (展示本地缓存)", Toast.LENGTH_LONG).show()
                val cached = cacheManager.getTimetable(xnm, xqm)
                if (cached != null) {
                    timetableData = cached
                    refreshCoursesList(cached)
                    displayTimetable(cached)
                } else {
                    binding.tvTimetableSyncTime.text = "暂无缓存"
                    binding.timetableView.setCourses(emptyList(), currentWeek)
                }
            }
        }
    }

    private fun refreshCoursesList(data: TimetableData) {
        val semKey = "${currentXnm}_${currentXqm}"
        val deletedIds = customCourseManager.getDeletedCourseIds(semKey)
        val customCourses = customCourseManager.getCustomCourses(semKey)

        currentDisplayCourses.clear()
        for (c in data.courses) {
            if (deletedIds.contains(c.id)) continue
            val excluded = customCourseManager.getExcludedWeeks(semKey, c.id)
            if (excluded.isNotEmpty()) {
                val remainingWeeks = c.weeks.filter { !excluded.contains(it) }
                currentDisplayCourses.add(c.copy(weeks = remainingWeeks))
            } else {
                currentDisplayCourses.add(c)
            }
        }
        currentDisplayCourses.addAll(customCourses.filter { !deletedIds.contains(it.id) })
        if (CourseReminderManager.isReminderEnabled(this)) {
            CourseReminderManager.scheduleUpcomingReminders(this, currentDisplayCourses)
        }
    }

    private fun displayTimetable(data: TimetableData) {
        binding.tvTimetableSemester.text = data.semesterTitle
        val realCurrentWeek = RemoteConfigManager.getCurrentWeek()
        val semConfig = RemoteConfigManager.getSemesterConfig()
        if (currentWeek !in 1..20) {
            currentWeek = realCurrentWeek
        }
        val isCurrent = (currentWeek == realCurrentWeek)
        binding.tvCurrentWeekIndicator.text = if (isCurrent) "第 $currentWeek 周 (本周)" else "第 $currentWeek 周"

        // Update timetable header dates & today column highlight
        binding.timetableHeader.setDateInfo(semConfig.week1Monday, currentWeek)
        binding.timetableView.setHighlightDayOfWeek(if (isCurrent) getTodayDayOfWeek() else null)

        // Setup Week Chips (Weeks 1 to 20)
        binding.weekChipGroup.removeAllViews()
        for (w in 1..20) {
            val chip = Chip(this).apply {
                text = if (w == realCurrentWeek) "第${w}周 (本周)" else "第${w}周"
                isCheckable = true
                isChecked = (w == currentWeek)
                setOnClickListener {
                    currentWeek = w
                    binding.tvCurrentWeekIndicator.text = if (w == realCurrentWeek) "第 $w 周 (本周)" else "第 $w 周"
                    binding.timetableHeader.setDateInfo(semConfig.week1Monday, w)
                    binding.timetableView.setHighlightDayOfWeek(if (w == realCurrentWeek) getTodayDayOfWeek() else null)
                    binding.timetableView.setWeek(w)
                }
            }
            binding.weekChipGroup.addView(chip)
        }

        binding.timetableView.setCourses(currentDisplayCourses, currentWeek)
        binding.hsvWeekChips.post {
            val child = binding.weekChipGroup.getChildAt(currentWeek - 1)
            if (child != null) {
                binding.hsvWeekChips.smoothScrollTo(child.left - 50, 0)
            }
        }
    }

    private fun dp2px(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun showAddCourseDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_add_course, null)
        dialog.setContentView(view)

        val etName = view.findViewById<TextInputEditText>(R.id.etCourseName)
        val etCode = view.findViewById<TextInputEditText>(R.id.etCourseCode)
        val spExamType = view.findViewById<Spinner>(R.id.spinnerExamType)
        val etTeacher = view.findViewById<TextInputEditText>(R.id.etCourseTeacher)
        val etCredit = view.findViewById<TextInputEditText>(R.id.etCredit)
        val layoutSlots = view.findViewById<LinearLayout>(R.id.layoutSlotsContainer)
        val btnAddSlot = view.findViewById<MaterialButton>(R.id.btnAddSlot)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancelAdd)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveAdd)

        val examTypes = arrayOf("考查", "考试", "考核")
        spExamType.adapter = ArrayAdapter(this, R.layout.item_spinner, examTypes).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }

        val days = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val sections = (1..13).map { "第${it}节" }.toTypedArray()

        data class SlotViewHolder(
            val root: View,
            val tvTitle: TextView,
            val btnDelete: TextView,
            val spDay: Spinner,
            val spStart: Spinner,
            val spEnd: Spinner,
            val etRoom: TextInputEditText,
            val etWeeks: TextInputEditText
        )

        val slotHolders = mutableListOf<SlotViewHolder>()

        fun updateSlotTitles() {
            for ((idx, h) in slotHolders.withIndex()) {
                h.tvTitle.text = "时间段 ${idx + 1}"
                h.btnDelete.visibility = if (slotHolders.size > 1) View.VISIBLE else View.GONE
            }
        }

        fun addNewSlot(defaultDay: Int = 1, defaultStart: Int = 1, defaultEnd: Int = 2) {
            val slotView = layoutInflater.inflate(R.layout.item_add_course_slot, layoutSlots, false)
            val tvTitle = slotView.findViewById<TextView>(R.id.tvSlotTitle)
            val btnDel = slotView.findViewById<TextView>(R.id.btnDeleteSlot)
            val spDay = slotView.findViewById<Spinner>(R.id.spinnerSlotDay)
            val spStart = slotView.findViewById<Spinner>(R.id.spinnerSlotStart)
            val spEnd = slotView.findViewById<Spinner>(R.id.spinnerSlotEnd)
            val etRoom = slotView.findViewById<TextInputEditText>(R.id.etSlotRoom)
            val etWeeks = slotView.findViewById<TextInputEditText>(R.id.etSlotWeeks)

            val chipAll = slotView.findViewById<TextView>(R.id.chipPresetAll)
            val chipOdd = slotView.findViewById<TextView>(R.id.chipPresetOdd)
            val chipEven = slotView.findViewById<TextView>(R.id.chipPresetEven)
            val chipFirst = slotView.findViewById<TextView>(R.id.chipPresetFirstHalf)
            val chipSecond = slotView.findViewById<TextView>(R.id.chipPresetSecondHalf)

            chipAll.setOnClickListener { etWeeks.setText("1-16周") }
            chipOdd.setOnClickListener { etWeeks.setText("1-15周(单)") }
            chipEven.setOnClickListener { etWeeks.setText("2-16周(双)") }
            chipFirst.setOnClickListener { etWeeks.setText("1-8周") }
            chipSecond.setOnClickListener { etWeeks.setText("9-16周") }

            spDay.adapter = ArrayAdapter(this, R.layout.item_spinner, days).apply {
                setDropDownViewResource(R.layout.item_spinner_dropdown)
            }
            spStart.adapter = ArrayAdapter(this, R.layout.item_spinner, sections).apply {
                setDropDownViewResource(R.layout.item_spinner_dropdown)
            }
            spEnd.adapter = ArrayAdapter(this, R.layout.item_spinner, sections).apply {
                setDropDownViewResource(R.layout.item_spinner_dropdown)
            }

            spDay.setSelection((defaultDay - 1).coerceIn(0, 6))
            spStart.setSelection((defaultStart - 1).coerceIn(0, 12))
            spEnd.setSelection((defaultEnd - 1).coerceIn(0, 12))

            val holder = SlotViewHolder(slotView, tvTitle, btnDel, spDay, spStart, spEnd, etRoom, etWeeks)
            btnDel.setOnClickListener {
                if (slotHolders.size > 1) {
                    layoutSlots.removeView(slotView)
                    slotHolders.remove(holder)
                    updateSlotTitles()
                }
            }

            slotHolders.add(holder)
            layoutSlots.addView(slotView)
            updateSlotTitles()
        }

        // Add 1st slot by default
        addNewSlot(defaultDay = 1, defaultStart = 1, defaultEnd = 2)

        btnAddSlot.setOnClickListener {
            val nextDay = ((slotHolders.lastOrNull()?.spDay?.selectedItemPosition ?: 0) + 2) % 7 + 1
            addNewSlot(defaultDay = nextDay, defaultStart = 3, defaultEnd = 4)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            if (name.isEmpty()) {
                etName.error = "请输入课程名称"
                return@setOnClickListener
            }

            val teacher = etTeacher.text.toString().trim()
            val code = etCode.text.toString().trim()
            val examType = spExamType.selectedItem.toString()
            val credit = etCredit.text.toString().toDoubleOrNull() ?: 2.0
            val colorIdx = (0..13).random()
            val sharedBaseId = "custom_" + UUID.randomUUID().toString().substring(0, 6)

            val newCourses = mutableListOf<CourseItem>()
            for ((idx, h) in slotHolders.withIndex()) {
                val dayOfWeek = h.spDay.selectedItemPosition + 1
                val startSec = h.spStart.selectedItemPosition + 1
                val endSec = (h.spEnd.selectedItemPosition + 1).coerceAtLeast(startSec)
                val step = endSec - startSec + 1
                val rawSec = if (startSec == endSec) "$startSec" else "$startSec-$endSec"
                val room = h.etRoom.text.toString().trim()
                val rawWeeksStr = h.etWeeks.text.toString().trim().ifEmpty { "1-16周" }
                val weeksList = JwglClient.parseWeeks(rawWeeksStr).ifEmpty { (1..16).toList() }

                newCourses.add(
                    CourseItem(
                        id = "${sharedBaseId}_${dayOfWeek}_${startSec}_${step}",
                        name = name,
                        teacher = teacher,
                        classroom = room,
                        dayOfWeek = dayOfWeek,
                        startSection = startSec,
                        step = step,
                        rawSections = rawSec,
                        weeks = weeksList,
                        rawWeeks = rawWeeksStr,
                        credit = credit,
                        courseType = "自定义课程",
                        colorIndex = colorIdx,
                        courseCode = code,
                        examType = examType
                    )
                )
            }

            val semKey = "${currentXnm}_${currentXqm}"
            customCourseManager.addCustomCourses(semKey, newCourses)
            currentDisplayCourses.addAll(newCourses)
            binding.timetableView.setCourses(currentDisplayCourses, currentWeek)

            dialog.dismiss()
            Toast.makeText(this, "课程【$name】已添加 (共 ${newCourses.size} 个上课时段)", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun showCourseDetail(course: CourseItem, allCoursesInSlot: List<CourseItem>) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_course_detail, null)
        dialog.setContentView(view)

        val viewColor = view.findViewById<View>(R.id.viewCourseColor)
        val tvName = view.findViewById<TextView>(R.id.tvDetailCourseName)
        val tvCode = view.findViewById<TextView>(R.id.tvDetailCourseCode)
        val tvExamType = view.findViewById<TextView>(R.id.tvDetailExamType)
        val tvCredit = view.findViewById<TextView>(R.id.tvDetailCredit)
        val tvTeacher = view.findViewById<TextView>(R.id.tvDetailTeacher)
        val tvClassroom = view.findViewById<TextView>(R.id.tvDetailClassroom)
        val tvSections = view.findViewById<TextView>(R.id.tvDetailSections)
        val tvWeeks = view.findViewById<TextView>(R.id.tvDetailWeeks)
        val tvType = view.findViewById<TextView>(R.id.tvDetailType)
        val tvAllSlotsTitle = view.findViewById<TextView>(R.id.tvAllSlotsTitle)
        val layoutAllSlots = view.findViewById<LinearLayout>(R.id.layoutAllCourseSlots)
        val btnReschedule = view.findViewById<MaterialButton>(R.id.btnRescheduleCourse)
        val btnDelete = view.findViewById<MaterialButton>(R.id.btnDeleteCourse)
        val btnClose = view.findViewById<MaterialButton>(R.id.btnCloseDetail)

        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val cardColor = TimetableView.getCardColor(course.colorIndex, isNight)
        viewColor.setBackgroundColor(cardColor.bg)
        tvName.text = course.name

        // Course Code (错行布置)
        val codeText = course.courseCode.ifEmpty {
            if (course.id.contains("_") && !course.id.startsWith("custom_") && !course.id.startsWith("resched_")) {
                course.id.substringBefore("_")
            } else ""
        }
        if (codeText.isNotEmpty()) {
            tvCode.visibility = View.VISIBLE
            tvCode.text = codeText
        } else {
            tvCode.visibility = View.GONE
        }

        // Exam Type (考试/考查)
        val examText = course.examType.ifEmpty {
            if (course.courseType.contains("考查") || course.name.contains("设计") || course.name.contains("实验") || course.name.contains("实践")) "考查" else "考试"
        }
        tvExamType.text = examText

        tvCredit.text = "${course.credit}学分"
        tvTeacher.text = course.teacher.ifEmpty { "暂无教师信息" }
        tvClassroom.text = course.classroom.ifEmpty { "暂无教室信息" }

        val weekDayName = when (course.dayOfWeek) {
            1 -> "星期一"
            2 -> "星期二"
            3 -> "星期三"
            4 -> "星期四"
            5 -> "星期五"
            6 -> "星期六"
            7 -> "星期日"
            else -> "星期${course.dayOfWeek}"
        }
        tvSections.text = "$weekDayName 第${course.rawSections}节"
        tvWeeks.text = course.rawWeeks.ifEmpty { "全学期" }
        tvType.text = course.courseType.ifEmpty { "课程" }

        // Find all schedule slots of this course
        val sameCourseSlots = currentDisplayCourses.filter {
            (course.courseCode.isNotEmpty() && it.courseCode == course.courseCode) ||
            (it.name == course.name)
        }.sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }))

        tvAllSlotsTitle.text = "全周上课安排 (共 ${sameCourseSlots.size} 个时间段)："
        layoutAllSlots.removeAllViews()

        val daysNameMap = mapOf(1 to "周一", 2 to "周二", 3 to "周三", 4 to "周四", 5 to "周五", 6 to "周六", 7 to "周日")

        for ((idx, slot) in sameCourseSlots.withIndex()) {
            val isCurrentSlot = (slot.id == course.id)
            val slotRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp2px(10), dp2px(8), dp2px(10), dp2px(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp2px(6)
                }
                background = ContextCompat.getDrawable(
                    this@MainActivity,
                    if (isCurrentSlot) R.drawable.badge_bg else R.drawable.chip_bg
                )
            }

            val tvSlotInfo = TextView(this).apply {
                val dName = daysNameMap[slot.dayOfWeek] ?: "周${slot.dayOfWeek}"
                val roomStr = if (slot.classroom.isNotEmpty()) " @${slot.classroom}" else ""
                val weeksStr = if (slot.rawWeeks.isNotEmpty()) " (${slot.rawWeeks})" else ""
                text = "时段 ${idx + 1}: $dName 第${slot.rawSections}节$weeksStr$roomStr"
                textSize = 12.5f
                setTextColor(if (isCurrentSlot) ContextCompat.getColor(this@MainActivity, R.color.primary) else ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                if (isCurrentSlot) paint.isFakeBoldText = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            slotRow.addView(tvSlotInfo)

            if (isCurrentSlot) {
                val tvCurrentTag = TextView(this).apply {
                    text = "当前时段"
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
                    paint.isFakeBoldText = true
                }
                slotRow.addView(tvCurrentTag)
            }

            layoutAllSlots.addView(slotRow)
        }

        // Reschedule / Adjust Time
        btnReschedule.setOnClickListener {
            dialog.dismiss()
            showRescheduleDialog(course)
        }

        // Delete / Cancel Course (支持仅删除一个时间段、临时取消、删除整门课)
        btnDelete.setOnClickListener {
            val options = if (sameCourseSlots.size > 1) {
                arrayOf(
                    "仅取消本周该节课 (第 $currentWeek 周临时取消)",
                    "仅删除当前时间段 ($weekDayName 第${course.rawSections}节)",
                    "删除整门课程 (包含全部 ${sameCourseSlots.size} 个时段)"
                )
            } else {
                arrayOf(
                    "仅取消本周该节课 (第 $currentWeek 周临时取消)",
                    "永久删除整门课程"
                )
            }

            MaterialAlertDialogBuilder(this)
                .setTitle("删除 / 取消【${course.name}】")
                .setItems(options) { optDialog, which ->
                    val semKey = "${currentXnm}_${currentXqm}"
                    when (which) {
                        0 -> {
                            // 仅取消本周该节课
                            customCourseManager.excludeWeekFromCourse(semKey, course.id, currentWeek)
                            timetableData?.let { refreshCoursesList(it) }
                            binding.timetableView.setCourses(currentDisplayCourses, currentWeek)
                            optDialog.dismiss()
                            dialog.dismiss()
                            Toast.makeText(this@MainActivity, "已取消第 $currentWeek 周【${course.name}】($weekDayName 第${course.rawSections}节)", Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            if (sameCourseSlots.size > 1) {
                                // 仅删除当前时间段
                                customCourseManager.markCourseDeleted(semKey, course.id)
                                currentDisplayCourses.removeAll { it.id == course.id }
                                binding.timetableView.setCourses(currentDisplayCourses, currentWeek)
                                optDialog.dismiss()
                                dialog.dismiss()
                                Toast.makeText(this@MainActivity, "已移除【${course.name}】$weekDayName 第${course.rawSections}节 时段", Toast.LENGTH_SHORT).show()
                            } else {
                                // 永久删除整门课程
                                customCourseManager.markCourseDeleted(semKey, course.id)
                                currentDisplayCourses.removeAll { it.id == course.id }
                                binding.timetableView.setCourses(currentDisplayCourses, currentWeek)
                                optDialog.dismiss()
                                dialog.dismiss()
                                Toast.makeText(this@MainActivity, "课程【${course.name}】已删除", Toast.LENGTH_SHORT).show()
                            }
                        }
                        2 -> {
                            // 删除整门课程 (全部时段)
                            val slotIds = sameCourseSlots.map { it.id }
                            customCourseManager.markMultipleCoursesDeleted(semKey, slotIds)
                            currentDisplayCourses.removeAll { slotIds.contains(it.id) }
                            binding.timetableView.setCourses(currentDisplayCourses, currentWeek)
                            optDialog.dismiss()
                            dialog.dismiss()
                            Toast.makeText(this@MainActivity, "已删除课程【${course.name}】全部 ${sameCourseSlots.size} 个时段", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showRescheduleDialog(course: CourseItem) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_reschedule_course, null)
        dialog.setContentView(view)

        val tvTitle = view.findViewById<TextView>(R.id.tvRescheduleCourseTitle)
        val rbThisWeek = view.findViewById<RadioButton>(R.id.rbThisWeekOnly)
        val spDay = view.findViewById<Spinner>(R.id.spinnerRescheduleDay)
        val spStart = view.findViewById<Spinner>(R.id.spinnerRescheduleStart)
        val spEnd = view.findViewById<Spinner>(R.id.spinnerRescheduleEnd)
        val etRoom = view.findViewById<TextInputEditText>(R.id.etRescheduleRoom)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancelReschedule)
        val btnConfirm = view.findViewById<MaterialButton>(R.id.btnConfirmReschedule)

        tvTitle.text = "课程：${course.name} (${course.teacher})"
        rbThisWeek.text = "仅修改当前周 (第 $currentWeek 周 临时调课)"

        val days = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        val sections = (1..13).map { "第${it}节" }.toTypedArray()

        spDay.adapter = ArrayAdapter(this, R.layout.item_spinner, days).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spStart.adapter = ArrayAdapter(this, R.layout.item_spinner, sections).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spEnd.adapter = ArrayAdapter(this, R.layout.item_spinner, sections).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }

        spDay.setSelection((course.dayOfWeek - 1).coerceIn(0, 6))
        spStart.setSelection((course.startSection - 1).coerceIn(0, 12))
        spEnd.setSelection((course.startSection + course.step - 2).coerceIn(0, 12))
        etRoom.setText(course.classroom)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            val semKey = "${currentXnm}_${currentXqm}"
            val newDay = spDay.selectedItemPosition + 1
            val newStart = spStart.selectedItemPosition + 1
            val newEnd = (spEnd.selectedItemPosition + 1).coerceAtLeast(newStart)
            val newStep = newEnd - newStart + 1
            val newRawSec = if (newStart == newEnd) "$newStart" else "$newStart-$newEnd"
            val newRoom = etRoom.text.toString().trim().ifEmpty { course.classroom }

            if (rbThisWeek.isChecked) {
                // 1. Exclude this specific week from original course
                customCourseManager.excludeWeekFromCourse(semKey, course.id, currentWeek)

                // 2. Add a new adjusted single-week course
                val adjustedCourse = CourseItem(
                    id = "resched_" + UUID.randomUUID().toString().substring(0, 8),
                    name = course.name,
                    teacher = course.teacher,
                    classroom = newRoom,
                    dayOfWeek = newDay,
                    startSection = newStart,
                    step = newStep,
                    rawSections = newRawSec,
                    weeks = listOf(currentWeek),
                    rawWeeks = "第${currentWeek}周(调课)",
                    credit = course.credit,
                    courseType = "调课",
                    colorIndex = course.colorIndex
                )
                customCourseManager.addCustomCourse(semKey, adjustedCourse)
            } else {
                // Modify for all weeks: delete original and add full-week adjusted
                customCourseManager.markCourseDeleted(semKey, course.id)
                val adjustedCourse = CourseItem(
                    id = "resched_all_" + UUID.randomUUID().toString().substring(0, 8),
                    name = course.name,
                    teacher = course.teacher,
                    classroom = newRoom,
                    dayOfWeek = newDay,
                    startSection = newStart,
                    step = newStep,
                    rawSections = newRawSec,
                    weeks = course.weeks,
                    rawWeeks = course.rawWeeks,
                    credit = course.credit,
                    courseType = course.courseType,
                    colorIndex = course.colorIndex
                )
                customCourseManager.addCustomCourse(semKey, adjustedCourse)
            }

            timetableData?.let { refreshCoursesList(it) }
            binding.timetableView.setCourses(currentDisplayCourses, currentWeek)

            dialog.dismiss()
            Toast.makeText(this, "调课成功！", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
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
                Toast.makeText(this@MainActivity, "成绩联网刷新失败: $reason (展示本地缓存)", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this@MainActivity, "学籍档案已更新", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "档案更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.layoutProfile.isRefreshing = false
            }
        }
    }

    private fun displayGrades(report: GradeReport) {
        // 1. Overall summary numbers
        binding.tvCumulativeCredits.text = String.format("%.1f", report.totalCredits)
        binding.tvCumulativeAvgScore.text = String.format("%.2f", report.cumulativeWeightedScore)
        binding.tvCumulativeGpa.text = String.format("%.2f", report.cumulativeGpa)

        // 2. Setup Semester Filter Chips
        binding.semesterChipGroup.removeAllViews()

        val allChip = Chip(this).apply {
            text = "全部学期 (${report.totalCredits}学分)"
            isCheckable = true
            isChecked = true
            setOnClickListener {
                val allCourses = report.semesters.flatMap { it.courses }
                gradeAdapter.submitList(allCourses)
            }
        }
        binding.semesterChipGroup.addView(allChip)

        for (sem in report.semesters) {
            val semChip = Chip(this).apply {
                text = "${sem.semesterTitle} [${sem.totalCredits}学分 | 均分${String.format("%.1f", sem.weightedAverageScore)} | GPA ${String.format("%.2f", sem.weightedGpa)}]"
                isCheckable = true
                setOnClickListener {
                    gradeAdapter.submitList(sem.courses)
                }
            }
            binding.semesterChipGroup.addView(semChip)
        }

        // Show all courses by default
        val allCourses = report.semesters.flatMap { it.courses }
        gradeAdapter.submitList(allCourses)
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
            syncRemoteConfig(isManual = true)
        }
    }

    private fun syncRemoteConfig(isManual: Boolean) {
        lifecycleScope.launch {
            if (isManual) {
                Toast.makeText(this@MainActivity, "正在检查更新并同步云端配置...", Toast.LENGTH_SHORT).show()
            }
            val result = RemoteConfigManager.fetchConfig(this@MainActivity)
            result.onSuccess { config ->
                // Update profile summary
                binding.tvAppVersion.text = "v${BuildConfig.VERSION_NAME}"
                val semConfig = config.semesterConfig
                binding.tvSemesterConfigSummary.text = "${semConfig.currentSemester} · 第 1 周 ${semConfig.week1Monday}"

                // If timetable is currently displayed, dynamically update current week chip & indicator
                val realCurrentWeek = RemoteConfigManager.getCurrentWeek()
                if (timetableData != null) {
                    for (i in 0 until binding.weekChipGroup.childCount) {
                        val chip = binding.weekChipGroup.getChildAt(i) as? Chip
                        val w = i + 1
                        chip?.text = if (w == realCurrentWeek) "第${w}周 (本周)" else "第${w}周"
                        chip?.isChecked = (w == currentWeek)
                    }
                    val isCurrent = (currentWeek == realCurrentWeek)
                    binding.tvCurrentWeekIndicator.text = if (isCurrent) "第 $currentWeek 周 (本周)" else "第 $currentWeek 周"
                    binding.timetableHeader.setDateInfo(semConfig.week1Monday, currentWeek)
                    binding.timetableView.setHighlightDayOfWeek(if (isCurrent) getTodayDayOfWeek() else null)
                }

                // Check version
                if (RemoteConfigManager.isUpdateAvailable(BuildConfig.VERSION_CODE)) {
                    showUpdateDialog(config.appVersion)
                } else if (isManual) {
                    Toast.makeText(this@MainActivity, "已是最新版本 (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                if (isManual) {
                    Toast.makeText(this@MainActivity, "同步云端配置失败，请检查网络", Toast.LENGTH_SHORT).show()
                }
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
        dialog.show()
    }
}