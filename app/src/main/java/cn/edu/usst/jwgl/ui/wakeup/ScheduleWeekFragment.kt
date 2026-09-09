package cn.edu.usst.jwgl.ui.wakeup

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
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
import cn.edu.usst.jwgl.data.wakeup.AppDatabase
import cn.edu.usst.jwgl.data.wakeup.CourseBean
import cn.edu.usst.jwgl.data.wakeup.CourseUtils
import cn.edu.usst.jwgl.data.wakeup.TableBean
import cn.edu.usst.jwgl.data.wakeup.TimeDetailBean

class ScheduleWeekFragment : Fragment() {

    private var week: Int = 1
    private var tableId: Int = -1

    private lateinit var tvMonthHeader: TextView
    private lateinit var llDayHeaderContainer: LinearLayout
    private lateinit var llSidebarNodes: LinearLayout
    private lateinit var llWeekColumnsContainer: LinearLayout

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
        tvMonthHeader = view.findViewById(R.id.tvMonthHeader)
        llDayHeaderContainer = view.findViewById(R.id.llDayHeaderContainer)
        llSidebarNodes = view.findViewById(R.id.llSidebarNodes)
        llWeekColumnsContainer = view.findViewById(R.id.llWeekColumnsContainer)
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
        val dateStrings = CourseUtils.getDateStringFromWeek(curWeek, week, table.sundayFirst)
        val todayWeekday = CourseUtils.getTodayWeekdayInt() // 1..7 (1=Mon..7=Sun)

        val itemHeightPx = dpToPx(table.itemHeight.toFloat())
        val marTopPx = dpToPx(2f)

        // 1. Month Header
        tvMonthHeader.text = "${dateStrings[0]}\n月"

        // 2. Day Headers
        llDayHeaderContainer.removeAllViews()
        val daysArray = if (table.sundayFirst) {
            arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        } else {
            arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        }

        val totalDays = 7
        for (i in 0 until totalDays) {
            val dayNumber = if (table.sundayFirst) (if (i == 0) 7 else i) else (i + 1)
            // Skip weekend if not shown
            if (!table.showSat && dayNumber == 6) continue
            if (!table.showSun && dayNumber == 7) continue

            val isToday = (dayNumber == todayWeekday && week == curWeek)

            val dayView = LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                if (isToday) {
                    background = ContextCompat.getDrawable(context, R.drawable.badge_bg)
                }
            }

            val tvDayName = TextView(context).apply {
                text = daysArray[i]
                textSize = 12f
                typeface = if (isToday) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (isToday) ContextCompat.getColor(context, R.color.primary) else ContextCompat.getColor(context, R.color.text_primary))
                gravity = Gravity.CENTER
            }

            val tvDayDate = TextView(context).apply {
                val dateStr = if (i + 1 < dateStrings.size) dateStrings[i + 1] else ""
                text = "$dateStr 日"
                textSize = 10.5f
                setTextColor(if (isToday) ContextCompat.getColor(context, R.color.primary) else ContextCompat.getColor(context, R.color.text_secondary))
                gravity = Gravity.CENTER
            }

            dayView.addView(tvDayName)
            dayView.addView(tvDayDate)
            llDayHeaderContainer.addView(dayView)
        }

        // 3. Sidebar Nodes
        llSidebarNodes.removeAllViews()
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

            val tvNode = TextView(context).apply {
                text = node.toString()
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                gravity = Gravity.CENTER
            }
            nodeLayout.addView(tvNode)

            if (table.showTime && node <= times.size) {
                val timeItem = times[node - 1]
                val tvTime = TextView(context).apply {
                    text = timeItem.startTime
                    textSize = 9.5f
                    setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                    gravity = Gravity.CENTER
                }
                nodeLayout.addView(tvTime)
            }

            llSidebarNodes.addView(nodeLayout)
        }

        // 4. Week Columns and Course Cards
        llWeekColumnsContainer.removeAllViews()

        for (i in 0 until totalDays) {
            val dayNumber = if (table.sundayFirst) (if (i == 0) 7 else i) else (i + 1)
            if (!table.showSat && dayNumber == 6) continue
            if (!table.showSun && dayNumber == 7) continue

            val dayColumn = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = dpToPx(1f)
                    marginEnd = dpToPx(1f)
                }
            }

            val dayCourses = allCourses.filter { it.day == dayNumber }

            // Partition into active courses and inactive courses
            val activeCourses = dayCourses.filter { course ->
                (week >= course.startWeek && week <= course.endWeek) &&
                (course.type == 0 || (course.type == 1 && week % 2 != 0) || (course.type == 2 && week % 2 == 0))
            }

            val displayList = mutableListOf<Pair<CourseBean, Boolean>>()
            for (c in activeCourses) {
                displayList.add(Pair(c, true))
            }

            if (table.showOtherWeekCourse) {
                val inactiveCourses = dayCourses.filter { !activeCourses.contains(it) }
                for (c in inactiveCourses) {
                    // Only show upcoming courses, do not show already ended courses
                    if (c.endWeek < week) continue

                    // Do not overlap an already active course slot
                    val cEnd = c.startNode + c.step - 1
                    val overlapsWithActive = activeCourses.any { a ->
                        val aEnd = a.startNode + a.step - 1
                        !(cEnd < a.startNode || c.startNode > aEnd)
                    }
                    if (!overlapsWithActive) {
                        displayList.add(Pair(c, false))
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
                        bgDrawable.setColor(Color.parseColor("#$alphaHex" + "CCCCCC"))
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

                    if (!isWeekActive) {
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
    }

    private fun dpToPx(dp: Float): Int {
        val metrics = resources.displayMetrics
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, metrics).toInt()
    }

    companion object {
        private const val ARG_WEEK = "arg_week"
        private const val ARG_TABLE_ID = "arg_table_id"

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
