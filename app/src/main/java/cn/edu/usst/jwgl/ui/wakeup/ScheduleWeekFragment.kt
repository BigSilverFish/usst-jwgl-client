package cn.edu.usst.jwgl.ui.wakeup

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Build
import eightbitlab.com.blurview.BlurView
import eightbitlab.com.blurview.RenderEffectBlur
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.data.model.ExamItem
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.data.wakeup.CourseAdjustmentResolver
import cn.edu.usst.jwgl.data.wakeup.CourseBean
import cn.edu.usst.jwgl.data.wakeup.CourseUtils
import cn.edu.usst.jwgl.data.wakeup.ResolvedDaySchedule
import cn.edu.usst.jwgl.data.wakeup.TableBean
import cn.edu.usst.jwgl.data.wakeup.TimeDetailBean
import cn.edu.usst.jwgl.util.ExamHelper
import cn.edu.usst.jwgl.util.TimetableSettingHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ScheduleWeekFragment : Fragment() {

    private var week: Int = 1
    private var tableId: Int = -1

    private lateinit var svScheduleContent: androidx.core.widget.NestedScrollView
    private lateinit var llSidebarNodes: LinearLayout
    private lateinit var llWeekColumnsContainer: LinearLayout
    private lateinit var flScheduleContent: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            week = it.getInt(ARG_WEEK, 1)
            tableId = it.getInt(ARG_TABLE_ID, -1)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_schedule_week, container, false)
        svScheduleContent = view.findViewById(R.id.svScheduleContent)
        llSidebarNodes = view.findViewById(R.id.llSidebarNodes)
        llWeekColumnsContainer = view.findViewById(R.id.llWeekColumnsContainer)
        flScheduleContent = view.findViewById(R.id.flScheduleContent)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        renderSchedule()
    }

    override fun onResume() {
        super.onResume()
        renderSchedule()
    }

    fun refresh() {
        if (isAdded) {
            renderSchedule()
        }
    }

    fun scrollToBottom() {
        val sv = view?.findViewById<androidx.core.widget.NestedScrollView>(R.id.svScheduleContent)
        sv?.post {
            sv.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun renderSchedule() {
        val context = context ?: return
        val db = AppDatabase.getDatabase(context)
        val table = if (tableId > 0) {
            db.tableDao.getTableById(tableId) ?: db.tableDao.getDefaultTable()
        } else {
            db.tableDao.getDefaultTable()
        }
        val times = db.timeDetailDao.getTimeDetails(table.timeTable)
        val allCourses = db.courseBaseDao.getCourseOfTable(table.id)

        val curWeek = CourseUtils.countWeek(table.startDate)
        val dateStrings = CourseUtils.getDateStringFromWeek(table.startDate, week, table.sundayFirst)
        val todayWeekday = CourseUtils.getTodayWeekdayInt() // 1..7 (1=Mon..7=Sun)

        val isExamWeek = ExamHelper.isExamWeek(table.tableName, week)
        val cachedExams = if (isExamWeek) {
            DataCacheManager(context).getAllCachedExams()
        } else {
            emptyList()
        }

        val itemHeightPx = dpToPx(table.itemHeight.toFloat())
        val examSessionHeightPx = dpToPx(105f)
        val marTopPx = dpToPx(2f)

        val showDayParts = !isExamWeek && TimetableSettingHelper.isDayPartDividersEnabled(context)
        val morningEndNode = 5
        val afternoonEndNode = if (table.nodes <= 12) 9 else 10
        val eveningStartNode = afternoonEndNode + 1

        // Adjust svScheduleContent padding so content starts right below the floating unified top bar and clears bottom bar
        svScheduleContent.post {
            val topPad = cn.edu.usst.jwgl.ui.MainActivity.topBarHeightPx.takeIf { it > 0 } ?: dpToPx(120f)
            val bottomPad = (cn.edu.usst.jwgl.ui.MainActivity.bottomBarHeightPx.takeIf { it > 0 } ?: dpToPx(72f)) + 24
            svScheduleContent.setPadding(0, topPad, 0, bottomPad)
        }

        // 3. Sidebar Nodes
        llSidebarNodes.removeAllViews()
        if (isExamWeek) {
            // 考试周时刻表：三场制 (09:00-11:00, 13:00-15:00, 15:30-17:30)
            for (session in ExamHelper.SESSIONS) {
                val nodeLayout = LinearLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        examSessionHeightPx
                    ).apply {
                        topMargin = marTopPx
                    }
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                }

                val tvNode = TextView(context).apply {
                    text = "场次${session.index}"
                    textSize = 10.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ContextCompat.getColor(context, R.color.primary))
                    gravity = Gravity.CENTER
                }
                nodeLayout.addView(tvNode)

                val tvTime = TextView(context).apply {
                    text = "${session.startTime}\n${session.endTime}"
                    textSize = 9.5f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    gravity = Gravity.CENTER
                    setLineSpacing(0f, 1.1f)
                }
                nodeLayout.addView(tvTime)

                llSidebarNodes.addView(nodeLayout)
            }
        } else {
            for (node in 1..table.nodes) {
                val nodeLayout = LinearLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        itemHeightPx
                    ).apply {
                        topMargin = marTopPx
                    }
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                }

                if (showDayParts && (node == 1 || node == 6 || node == eveningStartNode)) {
                    val tagText = when (node) {
                        1 -> "上午"
                        6 -> "下午"
                        else -> "晚上"
                    }
                    val tvTag = TextView(context).apply {
                        text = tagText
                        textSize = 8f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(ContextCompat.getColor(context, R.color.primary))
                        gravity = Gravity.CENTER
                        setPadding(0, 0, 0, dpToPx(1f))
                    }
                    nodeLayout.addView(tvTag)
                }

                val tvNode = TextView(context).apply {
                    text = node.toString()
                    textSize = if (showDayParts && (node == 1 || node == 6 || node == eveningStartNode)) 11f else 11.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    gravity = Gravity.CENTER
                }
                nodeLayout.addView(tvNode)

                if (table.showTime && node <= times.size) {
                    val timeItem = times[node - 1]
                    val tvTime = TextView(context).apply {
                        text = "${timeItem.startTime}\n${timeItem.endTime}"
                        textSize = 8.5f
                        setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                        gravity = Gravity.CENTER
                        setLineSpacing(0f, 0.95f)
                    }
                    nodeLayout.addView(tvTime)
                }

                llSidebarNodes.addView(nodeLayout)
            }
        }

        // 4. Week Columns and Course Cards
        llWeekColumnsContainer.removeAllViews()

        val totalDays = 7
        for (i in 0 until totalDays) {
            val dayNumber = if (table.sundayFirst) (if (i == 0) 7 else i) else (i + 1)
            val fullDateStr = CourseUtils.getFullDateForWeekDay(table.startDate, week, i, table.sundayFirst)
            val resolved = CourseAdjustmentResolver.resolve(db, table.id, fullDateStr, week, dayNumber, allCourses)

            val dayExams = if (isExamWeek) {
                cachedExams.filter { it.getDateString() == fullDateStr }
            } else {
                emptyList()
            }

            val hasContent = resolved.isSwapped || resolved.courses.isNotEmpty() || dayExams.isNotEmpty()
            if (!table.showSat && dayNumber == 6 && !hasContent) continue
            if (!table.showSun && dayNumber == 7 && !hasContent) continue

            val dayColumn = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = dpToPx(1f)
                    marginEnd = dpToPx(1f)
                }
            }

            if (resolved.isHolidayOff) {
                val tvHoliday = TextView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dpToPx(90f)
                    }
                    text = "🎉\n${resolved.holidayName}\n放假停课"
                    textSize = 11.5f
                    gravity = Gravity.CENTER
                    setTextColor(0xFF888888.toInt())
                    setLineSpacing(0f, 1.2f)
                }
                dayColumn.addView(tvHoliday)
                llWeekColumnsContainer.addView(dayColumn)
                continue
            }

            if (isExamWeek) {
                for (exam in dayExams) {
                    val sessionIdx = exam.getSessionIndex().coerceIn(1, 3)
                    val cardTopMargin = (sessionIdx - 1) * (examSessionHeightPx + marTopPx) + marTopPx

                    val examCard = TextView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            examSessionHeightPx
                        ).apply {
                            topMargin = cardTopMargin
                        }
                        textSize = 10f
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
                        gravity = Gravity.CENTER
                        setLineSpacing(0f, 1.15f)

                        val bgDrawable = (ContextCompat.getDrawable(context, R.drawable.course_item_bg)!!.mutate()) as GradientDrawable
                        bgDrawable.setStroke(dpToPx(1.5f), 0xFF1565C0.toInt())
                        bgDrawable.setColor(0xEE1E88E5.toInt())
                        background = bgDrawable
                        setTextColor(Color.WHITE)

                        val loc = if (exam.location.isNotBlank()) "@${exam.location}" else "@考场待定"
                        val seat = if (exam.seatNumber.isNotBlank()) "座号:${exam.seatNumber}" else exam.examMethod.ifBlank { "考试" }
                        val timeStr = exam.getTimeRangeString()

                        text = "📝 ${exam.courseName}\n$loc\n$seat\n$timeStr"

                        setOnClickListener {
                            showExamDetailDialog(exam)
                        }
                    }
                    dayColumn.addView(examCard)
                }
                llWeekColumnsContainer.addView(dayColumn)
                continue
            }

            if (showDayParts) {
                // Subtle evening tint background
                val eveningTop = afternoonEndNode * (itemHeightPx + marTopPx) + marTopPx
                val eveningHeight = (table.nodes - afternoonEndNode) * (itemHeightPx + marTopPx)
                if (eveningHeight > 0) {
                    val eveningBg = View(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            eveningHeight
                        ).apply {
                            topMargin = eveningTop
                        }
                        setBackgroundColor(0x06000000.toInt())
                    }
                    dayColumn.addView(eveningBg)
                }
            }

            val dayCourses = resolved.courses

            // Partition into active courses and inactive courses
            val activeCourses = if (resolved.isSwapped) {
                dayCourses
            } else {
                dayCourses.filter { course ->
                    (week >= course.startWeek && week <= course.endWeek) &&
                    (course.type == 0 || (course.type == 1 && week % 2 != 0) || (course.type == 2 && week % 2 == 0))
                }
            }

            val displayList = mutableListOf<Pair<CourseBean, Boolean>>()
            val occupiedNodes = mutableSetOf<Int>()

            // 1. Add all active courses first (they take highest priority for this week)
            for (c in activeCourses) {
                displayList.add(Pair(c, true))
                for (n in c.startNode until (c.startNode + c.step)) {
                    occupiedNodes.add(n)
                }
            }

            // 2. Add non-current week courses without overlapping, prioritizing the course closest in time
            if (table.showOtherWeekCourse && !resolved.isSwapped) {
                val inactiveCourses = dayCourses.filter { !activeCourses.contains(it) }

                // Lower score means closer in time to the current displayed week
                fun getDistanceScore(c: CourseBean): Double {
                    return when {
                        // Alternate week (单双周) within valid range
                        week in c.startWeek..c.endWeek -> 0.5
                        // Upcoming in future weeks
                        c.startWeek > week -> (c.startWeek - week).toDouble()
                        // Past ended weeks: penalty so upcoming courses are preferred
                        else -> (week - c.endWeek).toDouble() + 50.0
                    }
                }

                // Sort candidates: closest distance first, then earlier startWeek, then longer step, then id
                val sortedCandidates = inactiveCourses.sortedWith(
                    compareBy(
                        { getDistanceScore(it) },
                        { it.startWeek },
                        { -it.step },
                        { it.id }
                    )
                )

                for (c in sortedCandidates) {
                    val courseNodes = c.startNode until (c.startNode + c.step)
                    // Only add if none of this course's section nodes are already occupied
                    val hasOverlap = courseNodes.any { it in occupiedNodes }
                    if (!hasOverlap) {
                        displayList.add(Pair(c, false))
                        occupiedNodes.addAll(courseNodes)
                    }
                }
            }

            for ((course, isWeekActive) in displayList) {
                val cardHeight = itemHeightPx * course.step + marTopPx * (course.step - 1)
                val cardTopMargin = (course.startNode - 1) * (itemHeightPx + marTopPx) + marTopPx

                val courseCard = TextView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        cardHeight
                    ).apply {
                        topMargin = cardTopMargin
                    }
                    textSize = table.itemTextSize.toFloat()
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dpToPx(4f), dpToPx(4f), dpToPx(4f), dpToPx(4f))
                    gravity = Gravity.CENTER
                    setLineSpacing(0f, 1.1f)

                    val bgDrawable = (ContextCompat.getDrawable(context, R.drawable.course_item_bg)!!.mutate()) as GradientDrawable
                    bgDrawable.setStroke(dpToPx(1.5f), table.strokeColor)

                    val alphaHex = String.format("%02X", (255 * (table.itemAlpha.coerceIn(0, 100) / 100f)).toInt())

                    if (isWeekActive) {
                        setTextColor(table.courseTextColor)
                        try {
                            val baseHex = course.color.removePrefix("#")
                            val colorWithAlpha = Color.parseColor("#$alphaHex$baseHex")
                            bgDrawable.setColor(colorWithAlpha)
                        } catch (e: Exception) {
                            bgDrawable.setColor(Color.parseColor(course.color))
                        }
                    } else {
                        // Non-current week
                        setTextColor(0xFF555555.toInt())
                        alpha = 0.65f
                        val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                        val inactiveHex = if (isDark) "252830" else "CCCCCC"
                        bgDrawable.setColor(Color.parseColor("#$alphaHex$inactiveHex"))
                    }
                    background = bgDrawable

                    val textBuilder = StringBuilder(course.courseName)
                    val teacher = course.teacher?.trim()
                    if (!teacher.isNullOrBlank()) {
                        textBuilder.append("\n$teacher")
                    }
                    val room = course.room?.trim()
                    if (!room.isNullOrBlank()) {
                        textBuilder.append("\n@$room")
                    }

                    if (resolved.isSwapped) {
                        textBuilder.append("\n[${resolved.swapRemark ?: "调休"}]")
                    } else if (!isWeekActive) {
                        when (course.type) {
                            1 -> textBuilder.append("\n单周[非本周]")
                            2 -> textBuilder.append("\n双周[非本周]")
                            else -> textBuilder.append("\n[非本周]")
                        }
                    }

                    text = textBuilder.toString()

                    setOnClickListener {
                        val dialog = CourseDetailDialog.newInstance(course)
                        dialog.setOnCourseChangedListener {
                            refresh()
                        }
                        dialog.show(parentFragmentManager, "CourseDetailDialog")
                    }
                }

                dayColumn.addView(courseCard)
            }

            llWeekColumnsContainer.addView(dayColumn)
        }

        // 5. Full-width Day-part Divider Lines (Morning/Afternoon and Afternoon/Evening)
        // Remove existing dividers from flScheduleContent first
        for (idx in flScheduleContent.childCount - 1 downTo 0) {
            val child = flScheduleContent.getChildAt(idx)
            if (child.id == R.id.flScheduleContent || child == view?.findViewById(R.id.llContentContainer)) {
                continue
            }
            if (child.tag == "day_part_divider") {
                flScheduleContent.removeViewAt(idx)
            }
        }

        if (showDayParts) {
            val primaryColor = ContextCompat.getColor(context, R.color.primary)
            val dividerColor = (primaryColor and 0x00FFFFFF) or (0xB0 shl 24)
            val dividerHeightPx = dpToPx(1.5f).coerceAtLeast(2)

            // Noon Divider (between morning and afternoon)
            val noonY = morningEndNode * (itemHeightPx + marTopPx) + (marTopPx - dividerHeightPx) / 2
            val noonLine = View(context).apply {
                tag = "day_part_divider"
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dividerHeightPx
                ).apply {
                    topMargin = noonY
                }
                setBackgroundColor(dividerColor)
            }
            flScheduleContent.addView(noonLine)

            // Evening Divider (between afternoon and evening)
            val eveningY = afternoonEndNode * (itemHeightPx + marTopPx) + (marTopPx - dividerHeightPx) / 2
            val eveningLine = View(context).apply {
                tag = "day_part_divider"
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dividerHeightPx
                ).apply {
                    topMargin = eveningY
                }
                setBackgroundColor(dividerColor)
            }
            flScheduleContent.addView(eveningLine)
        }
    }

    private fun showExamDetailDialog(exam: ExamItem) {
        val context = context ?: return
        val sb = StringBuilder()
        sb.append("📅 考试时间：").append(exam.examTime).append("\n\n")
        sb.append("🏫 考场地点：").append(exam.location.ifBlank { "待定" })
        if (exam.building.isNotBlank() && !exam.location.contains(exam.building)) {
            sb.append(" (").append(exam.building).append(")")
        }
        sb.append("\n\n")
        sb.append("🪑 考试座号：").append(exam.seatNumber.ifBlank { "未指定" }).append("\n\n")
        if (exam.examName.isNotBlank()) {
            sb.append("📌 考试类别：").append(exam.examName).append("\n\n")
        }
        sb.append("📋 考核方式：").append(exam.examMethod.ifBlank { "笔试(闭卷)" })
        if (exam.examNature.isNotBlank()) {
            sb.append(" · ").append(exam.examNature)
        }
        sb.append("\n\n")
        sb.append("🎓 课程学分：").append(exam.credit).append(" 学分")
        if (exam.remarks.isNotBlank()) {
            sb.append("\n\n💡 备注：").append(exam.remarks)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(exam.courseName)
            .setMessage(sb.toString())
            .setPositiveButton("我知道了", null)
            .show()
    }

    private fun dpToPx(dp: Float): Int {
        val metrics = resources.displayMetrics
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, metrics).toInt()
    }

    companion object {
        const val ARG_WEEK = "arg_week"
        const val ARG_TABLE_ID = "arg_table_id"

        fun newInstance(week: Int, tableId: Int = -1): ScheduleWeekFragment {
            return ScheduleWeekFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_WEEK, week)
                    putInt(ARG_TABLE_ID, tableId)
                }
            }
        }
    }
}
