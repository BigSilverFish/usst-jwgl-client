package cn.edu.usst.jwgl.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import cn.edu.usst.jwgl.util.SemesterHelper
import cn.edu.usst.jwgl.util.ThemeManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE = "extra_student_profile"
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

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        reloadTimetableFromDb()
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
    }

    private fun initWakeupSchedule() {
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

        // Auto determine active semester & week on startup:
        // Locate to current semester current week, or next semester week 1 if currently in vacation
        val allTables = db.tableDao.getAllTables()
        val autoTarget = CourseUtils.findAutoScheduleTarget(allTables)
        val targetTable = autoTarget?.table ?: db.tableDao.getDefaultTable()
        val targetWeek = autoTarget?.week ?: 1

        db.tableDao.setDefaultTable(targetTable.id)
        currentTable = targetTable
        currentWeek = targetWeek

        scheduleAdapter = SchedulePagerAdapter(this, targetTable.maxWeek, targetTable.id)
        binding.vpSchedule.adapter = scheduleAdapter

        binding.vpSchedule.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                val selectedWeek = position + 1
                currentWeek = selectedWeek
                updateWeekSelectionUI(selectedWeek)
            }
        })

        reloadTimetableFromDb()
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
        val table = db.tableDao.getDefaultTable()
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

        scheduleAdapter?.refreshAllFragments()
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
            db.tableDao.updateTable(table)
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
    }

    private fun loadTimetableFromNetwork() {
        binding.timetableProgressBar.visibility = View.VISIBLE
        val (xnm, xqm) = SemesterHelper.getCurrentSemester()

        lifecycleScope.launch {
            val result = JwglClient.fetchTimetable(xnm, xqm, context = this@MainActivity)
            binding.timetableProgressBar.visibility = View.GONE

            result.onSuccess { data ->
                val semConfig = RemoteConfigManager.getSemesterConfig()
                val startDate = semConfig.week1Monday.ifBlank { currentTable?.startDate ?: "2026-09-07" }
                val importedTable = WakeupScheduleImporter.importTimetableData(db, data, startDate)
                currentTable = importedTable

                // Check if current week is within exam sync window:
                // "在考试周开始4周前至考试周结束时，刷新同步课表自动同步考试周"
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
                Toast.makeText(this@MainActivity, "${data.semesterTitle} 课表导入成功$examSyncMsg", Toast.LENGTH_SHORT).show()
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

    private fun displayProfile(profile: StudentProfile?) {
        binding.tvAppVersion.text = "v${BuildConfig.VERSION_NAME}"
        val semConfig = RemoteConfigManager.getSemesterConfig()
        binding.tvSemesterConfigSummary.text = "${semConfig.currentSemester} · 第 1 周 ${semConfig.week1Monday}"

        binding.tvDarkModeStatus.text = ThemeManager.getThemeModeName(ThemeManager.getThemeMode(this))
        binding.switchCourseReminder.isChecked = CourseReminderManager.isReminderEnabled(this)
        binding.switchExamReminder.isChecked = CourseReminderManager.isExamReminderEnabled(this)
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

    private fun displayGrades(report: GradeReport) {
        updateGradesUI(report)
    }

    private fun updateGradesUI(report: GradeReport) {
        if (selectedGradeSemesterTitle.isEmpty()) {
            binding.tvGradeSemester.text = "全部学期\n(汇总)"
            binding.tvGradeScopeBadge.text = "全部汇总"
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
                val badgeText = if (sem.semesterTitle.contains("第1学期")) "第 1 学期" else if (sem.semesterTitle.contains("第2学期")) "第 2 学期" else "分学期"
                binding.tvGradeScopeBadge.text = badgeText
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
                } else {
                    scheduleAdapter?.refreshAllFragments()
                }

                // Update reminders according to holidays & adjustments
                CourseReminderManager.scheduleUpcomingReminders(this@MainActivity)

                if (RemoteConfigManager.isUpdateAvailable(BuildConfig.VERSION_CODE)) {
                    showUpdateDialog(config.appVersion)
                } else if (isManual) {
                    Toast.makeText(this@MainActivity, "校历与调休配置已成功同步", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                Log.e("MainActivity", "Remote config sync failed", error)
                if (isManual) {
                    val msg = if (error is com.google.gson.JsonSyntaxException) "云端配置文件格式错误(JSON语法)" else "同步云端配置失败: ${error.message ?: "请检查网络"}"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
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