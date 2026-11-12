package cn.edu.usst.jwgl.ui.wakeup

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.network.JwglClient
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.data.wakeup.CourseBaseBean
import cn.edu.usst.jwgl.data.wakeup.CourseDetailBean
import cn.edu.usst.jwgl.data.wakeup.CourseUtils
import cn.edu.usst.jwgl.data.wakeup.UnscheduledCourseCandidate
import cn.edu.usst.jwgl.data.wakeup.UnscheduledCourseHelper
import cn.edu.usst.jwgl.util.SemesterHelper
import kotlinx.coroutines.launch

class AddCourseActivity : AppCompatActivity() {

    private var courseId: Int = -1
    private var tableId: Int = 1
    private var selectedColor: String = CourseUtils.getColorHex(0)
    private var prefilledTeacher: String = ""
    private var selectedCandidateKey: String? = null

    private lateinit var etCourseName: TextInputEditText
    private lateinit var etClassCode: TextInputEditText
    private lateinit var actvExamType: AutoCompleteTextView
    private lateinit var llColorPalette: LinearLayout
    private lateinit var llSlotsContainer: LinearLayout
    private lateinit var switchLockCourse: MaterialSwitch

    private lateinit var cardQuickPickUnscheduled: MaterialCardView
    private lateinit var progressUnscheduled: ProgressBar
    private lateinit var btnRefreshUnscheduled: MaterialButton
    private lateinit var tvUnscheduledEmpty: TextView
    private lateinit var llUnscheduledList: LinearLayout

    private val slotViews = mutableListOf<View>()

    private val dayNames = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val weekTypeNames = arrayOf("全周", "单周", "双周")
    private val examTypeOptions = arrayOf("不设置", "考试", "考查")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_course)

        courseId = intent.getIntExtra("id", -1)
        tableId = intent.getIntExtra("tableId", -1)

        val db = AppDatabase.getDatabase(this)
        if (tableId <= 0) {
            tableId = db.tableDao.getDefaultTable().id
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarAddCourse)
        toolbar.setNavigationOnClickListener { finish() }
        toolbar.title = if (courseId > 0) "编辑课程" else "添加课程"

        val btnSaveCourse = findViewById<MaterialButton>(R.id.btnSaveCourse)
        btnSaveCourse.setOnClickListener { saveCourse() }

        etCourseName = findViewById(R.id.etCourseName)
        etClassCode = findViewById(R.id.etClassCode)
        actvExamType = findViewById(R.id.actvExamType)
        llColorPalette = findViewById(R.id.llColorPalette)
        llSlotsContainer = findViewById(R.id.llSlotsContainer)
        switchLockCourse = findViewById(R.id.switchLockCourse)
        cardQuickPickUnscheduled = findViewById(R.id.cardQuickPickUnscheduled)
        progressUnscheduled = findViewById(R.id.progressUnscheduled)
        btnRefreshUnscheduled = findViewById(R.id.btnRefreshUnscheduled)
        tvUnscheduledEmpty = findViewById(R.id.tvUnscheduledEmpty)
        llUnscheduledList = findViewById(R.id.llUnscheduledList)

        actvExamType.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, examTypeOptions))
        actvExamType.setText(examTypeOptions[0], false)

        findViewById<MaterialCardView>(R.id.cardLockCourse).setOnClickListener {
            switchLockCourse.toggle()
        }

        val btnAddSlot = findViewById<MaterialButton>(R.id.btnAddSlot)
        btnAddSlot.setOnClickListener { addSlotView(null) }

        initColorPalette()

        if (courseId > 0) {
            // Edit mode
            cardQuickPickUnscheduled.visibility = View.GONE
            val baseBean = db.courseBaseDao.getCourseBaseById(courseId, tableId)
            if (baseBean != null) {
                etCourseName.setText(baseBean.courseName)
                selectedColor = baseBean.color
                switchLockCourse.isChecked = baseBean.isLocked
                etClassCode.setText(baseBean.classCode)
                actvExamType.setText(baseBean.examType.ifBlank { examTypeOptions[0] }, false)
                initColorPalette()
            }
            val details = db.courseDetailDao.getDetailByIdOfTable(courseId, tableId)
            if (details.isNotEmpty()) {
                for (detail in details) {
                    addSlotView(detail)
                }
            } else {
                addSlotView(null)
            }
        } else {
            // New course: default locked so manual entries survive network sync
            cardQuickPickUnscheduled.visibility = View.VISIBLE
            switchLockCourse.isChecked = true
            val nextId = db.courseBaseDao.getLastIdOfTable(tableId) + 1
            courseId = nextId
            selectedColor = CourseUtils.getColorHex(courseId - 1)
            initColorPalette()
            addSlotView(null)
            setupQuickPickUnscheduled(db)
        }
    }

    private fun initColorPalette() {
        llColorPalette.removeAllViews()
        for (colorInt in CourseUtils.CUSTOMIZED_COLORS) {
            val hex = String.format("#%06X", 0xFFFFFF and colorInt)
            val isSelected = hex.equals(selectedColor, ignoreCase = true)

            val circle = View(this).apply {
                val size = if (isSelected) dpToPx(36f) else dpToPx(28f)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = dpToPx(6f)
                    marginEnd = dpToPx(6f)
                }
                val gd = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorInt)
                    if (isSelected) {
                        setStroke(dpToPx(3f), 0xFFFFFFFF.toInt())
                    }
                }
                background = gd

                setOnClickListener {
                    selectedColor = hex
                    initColorPalette()
                }
            }
            llColorPalette.addView(circle)
        }
    }

    private fun addSlotView(detail: CourseDetailBean?) {
        val slotIndex = slotViews.size + 1
        val slotView = LayoutInflater.from(this).inflate(R.layout.item_course_slot, llSlotsContainer, false)

        val tvSlotTitle = slotView.findViewById<TextView>(R.id.tvSlotTitle)
        val btnDeleteSlot = slotView.findViewById<MaterialButton>(R.id.btnDeleteSlot)
        val actvDay = slotView.findViewById<AutoCompleteTextView>(R.id.actvDay)
        val etStartNode = slotView.findViewById<TextInputEditText>(R.id.etStartNode)
        val etEndNode = slotView.findViewById<TextInputEditText>(R.id.etEndNode)
        val etStartWeek = slotView.findViewById<TextInputEditText>(R.id.etStartWeek)
        val etEndWeek = slotView.findViewById<TextInputEditText>(R.id.etEndWeek)
        val actvWeekType = slotView.findViewById<AutoCompleteTextView>(R.id.actvWeekType)
        val etRoom = slotView.findViewById<TextInputEditText>(R.id.etRoom)
        val etTeacher = slotView.findViewById<TextInputEditText>(R.id.etTeacher)

        tvSlotTitle.text = "时间段 $slotIndex"

        actvDay.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, dayNames))
        actvWeekType.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, weekTypeNames))

        if (detail != null) {
            val dayIdx = (detail.day - 1).coerceIn(0, 6)
            actvDay.setText(dayNames[dayIdx], false)
            etStartNode.setText(detail.startNode.toString())
            etEndNode.setText((detail.startNode + detail.step - 1).toString())
            etStartWeek.setText(detail.startWeek.toString())
            etEndWeek.setText(detail.endWeek.toString())
            val typeIdx = detail.type.coerceIn(0, 2)
            actvWeekType.setText(weekTypeNames[typeIdx], false)
            etRoom.setText(detail.room ?: "")
            etTeacher.setText(detail.teacher ?: "")
        } else {
            actvDay.setText(dayNames[0], false)
            actvWeekType.setText(weekTypeNames[0], false)
            if (prefilledTeacher.isNotBlank()) {
                etTeacher.setText(prefilledTeacher)
            }
        }

        btnDeleteSlot.setOnClickListener {
            if (slotViews.size <= 1) {
                Toast.makeText(this, "至少需要保留一个上课时间段", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            llSlotsContainer.removeView(slotView)
            slotViews.remove(slotView)
            renumberSlots()
        }

        slotViews.add(slotView)
        llSlotsContainer.addView(slotView)
    }

    private fun renumberSlots() {
        slotViews.forEachIndexed { index, view ->
            val tv = view.findViewById<TextView>(R.id.tvSlotTitle)
            tv.text = "时间段 ${index + 1}"
        }
    }

    private fun saveCourse() {
        val courseName = etCourseName.text?.toString()?.trim() ?: ""
        if (courseName.isBlank()) {
            Toast.makeText(this, "请输入课程名称", Toast.LENGTH_SHORT).show()
            etCourseName.requestFocus()
            return
        }

        val details = mutableListOf<CourseDetailBean>()

        for (slotView in slotViews) {
            val actvDay = slotView.findViewById<AutoCompleteTextView>(R.id.actvDay)
            val etStartNode = slotView.findViewById<TextInputEditText>(R.id.etStartNode)
            val etEndNode = slotView.findViewById<TextInputEditText>(R.id.etEndNode)
            val etStartWeek = slotView.findViewById<TextInputEditText>(R.id.etStartWeek)
            val etEndWeek = slotView.findViewById<TextInputEditText>(R.id.etEndWeek)
            val actvWeekType = slotView.findViewById<AutoCompleteTextView>(R.id.actvWeekType)
            val etRoom = slotView.findViewById<TextInputEditText>(R.id.etRoom)
            val etTeacher = slotView.findViewById<TextInputEditText>(R.id.etTeacher)

            val dayStr = actvDay.text.toString()
            val day = (dayNames.indexOf(dayStr) + 1).let { if (it <= 0) 1 else it }

            val startNode = etStartNode.text?.toString()?.toIntOrNull() ?: 1
            val endNode = etEndNode.text?.toString()?.toIntOrNull() ?: startNode
            val step = (endNode - startNode + 1).coerceAtLeast(1)

            val startWeek = etStartWeek.text?.toString()?.toIntOrNull() ?: 1
            val endWeek = etEndWeek.text?.toString()?.toIntOrNull() ?: startWeek

            val typeStr = actvWeekType.text.toString()
            val type = when (typeStr) {
                "单周" -> 1
                "双周" -> 2
                else -> 0
            }

            val room = etRoom.text?.toString()?.trim() ?: ""
            val teacher = etTeacher.text?.toString()?.trim() ?: ""

            details.add(
                CourseDetailBean(
                    id = courseId,
                    day = day,
                    room = room,
                    teacher = teacher,
                    startNode = startNode,
                    step = step,
                    startWeek = startWeek,
                    endWeek = endWeek,
                    type = type,
                    tableId = tableId
                )
            )
        }

        val db = AppDatabase.getDatabase(this)
        val conflicts = db.findConflictsForDetails(tableId, excludeCourseId = courseId, details)
        if (conflicts.isNotEmpty()) {
            val conflictLines = conflicts.joinToString("\n") { it.formatConflictSummary() }
            val message = "检测到课程与当前课表中的以下课程存在时间冲突：\n\n$conflictLines\n\n是否仍要保存到课表？"
            MaterialAlertDialogBuilder(this)
                .setTitle("课程时间冲突提示")
                .setMessage(message)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton("仍要保存") { _, _ ->
                    doSaveCourse(db, courseName, details)
                }
                .setNegativeButton("取消", null)
                .show()
        } else {
            doSaveCourse(db, courseName, details)
        }
    }

    private fun doSaveCourse(
        db: AppDatabase,
        courseName: String,
        details: List<CourseDetailBean>
    ) {
        val classCode = etClassCode.text?.toString()?.trim().orEmpty()
        val rawExamType = actvExamType.text?.toString()?.trim().orEmpty()
        val examType = if (rawExamType == examTypeOptions[0]) "" else rawExamType

        val baseBean = CourseBaseBean(
            id = courseId,
            courseName = courseName,
            color = selectedColor,
            tableId = tableId,
            isLocked = switchLockCourse.isChecked,
            classCode = classCode,
            examType = examType
        )

        db.courseBaseDao.insertCourseBase(baseBean)
        db.courseDetailDao.deleteByIdOfTable(courseId, tableId)
        db.courseDetailDao.insertList(details)

        val table = db.tableDao.getTableById(tableId)
        if (table != null && details.any { it.day == 6 || it.day == 7 }) {
            if (!table.showSat || !table.showSun) {
                table.showSat = true
                table.showSun = true
                db.tableDao.updateTable(table)
            }
        }

        Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun setupQuickPickUnscheduled(db: AppDatabase) {
        val table = db.tableDao.getTableById(tableId) ?: db.tableDao.getDefaultTable()
        val (xnm, xqm) = SemesterHelper.parseSemesterFromTableName(table.tableName) ?: SemesterHelper.getCurrentSemester()
        val semNum = if (xqm == "3") "1" else if (xqm == "12") "2" else xqm
        val semCode = "$xnm-${(xnm.toIntOrNull() ?: 2025) + 1}-$semNum"

        val dataCache = DataCacheManager(this)
        val cachedTimetable = dataCache.getTimetable(xnm, xqm)
        val tronClassCourses = try {
            TronClassDataCacheManager.getInstance(this).getCachedCourses()
        } catch (_: Exception) {
            emptyList()
        }
        val existingBases = db.courseBaseDao.getCourseBasesOfTable(tableId)

        var candidates = UnscheduledCourseHelper.collectCandidates(
            timetableData = cachedTimetable,
            tronClassCourses = tronClassCourses,
            existingCourses = existingBases,
            expectedSemesterCode = semCode
        )
        renderUnscheduledCandidates(candidates)

        fun refreshFromJwgl(showToast: Boolean) {
            progressUnscheduled.visibility = View.VISIBLE
            btnRefreshUnscheduled.isEnabled = false
            lifecycleScope.launch {
                val res = JwglClient.fetchTimetable(xnm, xqm, context = this@AddCourseActivity)
                progressUnscheduled.visibility = View.GONE
                btnRefreshUnscheduled.isEnabled = true

                res.onSuccess { freshData ->
                    dataCache.saveTimetable(xnm, xqm, freshData)
                    candidates = UnscheduledCourseHelper.collectCandidates(
                        timetableData = freshData,
                        tronClassCourses = tronClassCourses,
                        existingCourses = db.courseBaseDao.getCourseBasesOfTable(tableId),
                        expectedSemesterCode = semCode
                    )
                    renderUnscheduledCandidates(candidates)
                    if (showToast) {
                        Toast.makeText(
                            this@AddCourseActivity,
                            if (candidates.isEmpty()) "未获取到未上课表课程" else "已刷新 ${candidates.size} 门候选课程",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }.onFailure { err ->
                    if (showToast) {
                        Toast.makeText(this@AddCourseActivity, "刷新失败: ${err.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        btnRefreshUnscheduled.setOnClickListener {
            refreshFromJwgl(showToast = true)
        }

        // If cache doesn't include unscheduledCourses yet (e.g. cached before update), fetch silently once
        if (cachedTimetable?.unscheduledCourses == null) {
            refreshFromJwgl(showToast = false)
        }
    }

    private fun renderUnscheduledCandidates(candidates: List<UnscheduledCourseCandidate>) {
        llUnscheduledList.removeAllViews()
        if (candidates.isEmpty()) {
            tvUnscheduledEmpty.visibility = View.VISIBLE
            return
        }
        tvUnscheduledEmpty.visibility = View.GONE

        for (candidate in candidates) {
            val key = "${candidate.name}|${candidate.classCode}"
            val isSelected = selectedCandidateKey == key

            val itemCard = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(8f)
                }
                radius = dpToPx(10f).toFloat()
                cardElevation = 0f
                setCardBackgroundColor(
                    getColor(if (isSelected) R.color.primary_container else R.color.surface_variant)
                )
                strokeWidth = dpToPx(if (isSelected) 1.5f else 1f)
                strokeColor = getColor(if (isSelected) R.color.primary else R.color.border)
                isClickable = true
                isFocusable = true
            }

            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(12f), dpToPx(10f), dpToPx(12f), dpToPx(10f))
            }

            val topRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val tvTitle = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = candidate.name
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(getColor(if (isSelected) R.color.on_primary_container else R.color.text_primary))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            topRow.addView(tvTitle)

            if (candidate.examType.isNotBlank()) {
                val tvExamBadge = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dpToPx(6f) }
                    text = candidate.examType
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dpToPx(6f), dpToPx(2f), dpToPx(6f), dpToPx(2f))
                    val (bgRes, fgRes) = when {
                        candidate.examType.contains("考试") -> R.color.grade_red_bg to R.color.grade_red_text
                        candidate.examType.contains("考查") -> R.color.grade_blue_bg to R.color.grade_blue_text
                        else -> R.color.surface_container to R.color.text_secondary
                    }
                    setTextColor(getColor(fgRes))
                    background = GradientDrawable().apply {
                        setColor(getColor(bgRes))
                        cornerRadius = dpToPx(5f).toFloat()
                    }
                }
                topRow.addView(tvExamBadge)
            }

            if (candidate.alreadyInTable) {
                val tvImported = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dpToPx(6f) }
                    text = "已添加"
                    textSize = 11f
                    setPadding(dpToPx(6f), dpToPx(2f), dpToPx(6f), dpToPx(2f))
                    setTextColor(getColor(R.color.text_secondary))
                    background = GradientDrawable().apply {
                        setColor(getColor(R.color.surface_container))
                        cornerRadius = dpToPx(5f).toFloat()
                    }
                }
                topRow.addView(tvImported)
            }

            inner.addView(topRow)

            val metaParts = buildList {
                if (candidate.teacher.isNotBlank()) add(candidate.teacher)
                if (candidate.classCode.isNotBlank()) add(candidate.classCode)
                if (isEmpty()) add(candidate.sourceLabel)
            }
            val tvMeta = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dpToPx(3f) }
                text = metaParts.joinToString(" · ")
                textSize = 12f
                setTextColor(getColor(if (isSelected) R.color.on_primary_container else R.color.text_secondary))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            inner.addView(tvMeta)

            itemCard.addView(inner)
            itemCard.setOnClickListener {
                applyCandidateSelection(candidate, key, candidates)
            }
            llUnscheduledList.addView(itemCard)
        }
    }

    private fun applyCandidateSelection(
        candidate: UnscheduledCourseCandidate,
        key: String,
        allCandidates: List<UnscheduledCourseCandidate>
    ) {
        selectedCandidateKey = key
        etCourseName.setText(candidate.name)
        etClassCode.setText(candidate.classCode.ifBlank { candidate.courseCode })
        val targetExam = if (candidate.examType.isNotBlank()) candidate.examType else examTypeOptions[0]
        actvExamType.setText(targetExam, false)

        prefilledTeacher = candidate.teacher
        if (candidate.teacher.isNotBlank()) {
            for (slotView in slotViews) {
                slotView.findViewById<TextInputEditText>(R.id.etTeacher).setText(candidate.teacher)
            }
        }

        renderUnscheduledCandidates(allCandidates)
        Toast.makeText(this, "已填入《${candidate.name}》，请设置上课时间与地点", Toast.LENGTH_SHORT).show()
    }

    private fun dpToPx(dp: Float): Int {
        val metrics = resources.displayMetrics
        return (dp * metrics.density).toInt()
    }
}
