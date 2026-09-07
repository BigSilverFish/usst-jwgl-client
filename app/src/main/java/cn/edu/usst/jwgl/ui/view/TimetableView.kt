package cn.edu.usst.jwgl.ui.view

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import cn.edu.usst.jwgl.data.model.CourseItem

class TimetableView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    val sidebarWidthDp = 38
    private val sectionHeightDp = 58
    private val breakHeightDp = 22

    // Material 3 Tonal Palette for course cards: (Container, Text, Stroke)
    data class M3CardColor(val bg: Int, val text: Int, val stroke: Int)

    companion object {
        // Light Mode Tonal Palette
        val m3PaletteLight = arrayOf(
            M3CardColor(0xFFE0E7FF.toInt(), 0xFF1E293B.toInt(), 0xFFC7D2FE.toInt()), // Indigo
            M3CardColor(0xFFDCFCE7.toInt(), 0xFF064E3B.toInt(), 0xFFBBF7D0.toInt()), // Emerald
            M3CardColor(0xFFE0F2FE.toInt(), 0xFF0C4A6E.toInt(), 0xFFBAE6FD.toInt()), // Sky
            M3CardColor(0xFFFEF3C7.toInt(), 0xFF78350F.toInt(), 0xFFFDE68A.toInt()), // Amber
            M3CardColor(0xFFF3E8FF.toInt(), 0xFF581C87.toInt(), 0xFFE9D5FF.toInt()), // Purple
            M3CardColor(0xFFFFE4E6.toInt(), 0xFF881337.toInt(), 0xFFFECDD3.toInt()), // Rose
            M3CardColor(0xFFCCFBF1.toInt(), 0xFF134E4A.toInt(), 0xFF99F6E4.toInt()), // Teal
            M3CardColor(0xFFFFEDD5.toInt(), 0xFF7C2D12.toInt(), 0xFFFED7AA.toInt()), // Orange
            M3CardColor(0xFFDBEAFE.toInt(), 0xFF1E3A8A.toInt(), 0xFFBFDBFE.toInt()), // Blue
            M3CardColor(0xFFECFCCB.toInt(), 0xFF365314.toInt(), 0xFFD9F99D.toInt()), // Lime
            M3CardColor(0xFFFCE7F3.toInt(), 0xFF701A75.toInt(), 0xFFFBCFE8.toInt()), // Fuchsia
            M3CardColor(0xFFCFFAFE.toInt(), 0xFF164E63.toInt(), 0xFFA5F3FC.toInt()), // Cyan
            M3CardColor(0xFFFFDAD8.toInt(), 0xFF3B080E.toInt(), 0xFFFFB3B4.toInt()), // Crimson
            M3CardColor(0xFFEDE9FE.toInt(), 0xFF4C1D95.toInt(), 0xFFDDD6FE.toInt())  // Violet
        )

        // Dark Mode M3 Tonal Palette (Comfortable saturated dark containers, high contrast text)
        val m3PaletteDark = arrayOf(
            M3CardColor(0xFF263352.toInt(), 0xFFC7D2FE.toInt(), 0xFF374977.toInt()), // Indigo
            M3CardColor(0xFF133E2B.toInt(), 0xFFBBF7D0.toInt(), 0xFF1F5C40.toInt()), // Emerald
            M3CardColor(0xFF133B4F.toInt(), 0xFFBAE6FD.toInt(), 0xFF1E5875.toInt()), // Sky
            M3CardColor(0xFF422F10.toInt(), 0xFFFDE68A.toInt(), 0xFF634618.toInt()), // Amber
            M3CardColor(0xFF3B1E54.toInt(), 0xFFE9D5FF.toInt(), 0xFF582D7D.toInt()), // Purple
            M3CardColor(0xFF4A1824.toInt(), 0xFFFECDD3.toInt(), 0xFF6F2336.toInt()), // Rose
            M3CardColor(0xFF123B37.toInt(), 0xFF99F6E4.toInt(), 0xFF1B5953.toInt()), // Teal
            M3CardColor(0xFF442313.toInt(), 0xFFFED7AA.toInt(), 0xFF66341D.toInt()), // Orange
            M3CardColor(0xFF172E54.toInt(), 0xFFBFDBFE.toInt(), 0xFF23447B.toInt()), // Blue
            M3CardColor(0xFF2B3D14.toInt(), 0xFFD9F99D.toInt(), 0xFF405C1E.toInt()), // Lime
            M3CardColor(0xFF451940.toInt(), 0xFFFBCFE8.toInt(), 0xFF66255E.toInt()), // Fuchsia
            M3CardColor(0xFF133B44.toInt(), 0xFFA5F3FC.toInt(), 0xFF1C5764.toInt()), // Cyan
            M3CardColor(0xFF4A1B20.toInt(), 0xFFFFDAD8.toInt(), 0xFF6E2830.toInt()), // Crimson
            M3CardColor(0xFF2F1D54.toInt(), 0xFFDDD6FE.toInt(), 0xFF462C7D.toInt())  // Violet
        )

        fun getCardColor(index: Int, isNight: Boolean): M3CardColor {
            val palette = if (isNight) m3PaletteDark else m3PaletteLight
            return palette[index % palette.size]
        }
    }

    private val isNightMode: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private val nonCurrentWeekColor: M3CardColor
        get() = if (isNightMode) {
            M3CardColor(0xFF1C1E22.toInt(), 0xFF64748B.toInt(), 0xFF282B32.toInt())
        } else {
            M3CardColor(0xFFF1F5F9.toInt(), 0xFF94A3B8.toInt(), 0xFFE2E8F0.toInt())
        }

    // Official USST 13 Sections Timetable
    private val sectionTimes = arrayOf(
        Pair("08:00", "08:40"), // 1
        Pair("08:45", "09:25"), // 2
        Pair("09:45", "10:25"), // 3
        Pair("10:30", "11:10"), // 4
        Pair("11:15", "11:55"), // 5
        Pair("13:00", "13:40"), // 6
        Pair("13:45", "14:25"), // 7
        Pair("14:45", "15:25"), // 8
        Pair("15:30", "16:10"), // 9
        Pair("16:15", "16:55"), // 10
        Pair("18:00", "18:40"), // 11
        Pair("18:45", "19:25"), // 12
        Pair("19:30", "20:10")  // 13
    )

    private var allCourses: List<CourseItem> = emptyList()
    var selectedWeek: Int = 3
        private set
    var fontScale: Float = 1.0f
        private set
    var showOnlyCurrentWeek: Boolean = true
        private set
    var highlightDayOfWeek: Int? = null
        private set

    var onCourseClickListener: ((CourseItem, List<CourseItem>) -> Unit)? = null
    var onWeekendVisibilityChanged: ((Boolean) -> Unit)? = null

    fun setHighlightDayOfWeek(dayOfWeek: Int?) {
        this.highlightDayOfWeek = dayOfWeek
        renderGrid()
    }

    private val sidebarLayout: LinearLayout
    private val sidebarSep: View
    private val gridContainer: FrameLayout

    init {
        orientation = HORIZONTAL

        sidebarLayout = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(dp2px(sidebarWidthDp), LayoutParams.WRAP_CONTENT)
        }

        sidebarSep = View(context).apply {
            layoutParams = LayoutParams(dp2px(1), LayoutParams.MATCH_PARENT)
        }

        val totalGridHeight = dp2px(sectionHeightDp * 13 + breakHeightDp * 2)
        gridContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(0, totalGridHeight, 1f)
        }

        applyThemeColors()
        buildSidebar()

        addView(sidebarLayout)
        addView(sidebarSep)
        addView(gridContainer)
    }

    private fun applyThemeColors() {
        val bgCol = if (isNightMode) Color.parseColor("#121316") else Color.parseColor("#FFFFFF")
        val sidebarBgCol = if (isNightMode) Color.parseColor("#1B1C20") else Color.parseColor("#FAFAFA")
        val sepCol = if (isNightMode) Color.parseColor("#2E333D") else Color.parseColor("#EAECF0")

        setBackgroundColor(bgCol)
        sidebarLayout.setBackgroundColor(sidebarBgCol)
        sidebarSep.setBackgroundColor(sepCol)
        gridContainer.setBackgroundColor(bgCol)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyThemeColors()
        buildSidebar()
        renderGrid()
    }

    private fun buildSidebar() {
        sidebarLayout.removeAllViews()

        val breakBg = if (isNightMode) "#24262C" else "#F2F4F7"
        val breakText = if (isNightMode) "#94A3B8" else "#475467"
        val numColor = if (isNightMode) Color.parseColor("#F1F5F9") else Color.parseColor("#344054")
        val timeColor = if (isNightMode) Color.parseColor("#94A3B8") else Color.parseColor("#98A2B3")

        for (i in 1..13) {
            // Lunch break
            if (i == 6) {
                sidebarLayout.addView(createBreakSidebarView("午休", "11:55", breakBg, breakText))
            }
            // Dinner break
            if (i == 11) {
                sidebarLayout.addView(createBreakSidebarView("晚休", "16:55", breakBg, breakText))
            }

            val sectionBox = LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp2px(sectionHeightDp))
                gravity = Gravity.CENTER
                setPadding(0, dp2px(2), 0, dp2px(2))
            }

            // Morning / Afternoon / Evening period tag
            if (i == 1 || i == 6 || i == 11) {
                val groupBadge = TextView(context).apply {
                    text = when (i) {
                        1 -> "上午"
                        6 -> "下午"
                        else -> "晚上"
                    }
                    textSize = 8f
                    setTextColor(when (i) {
                        1 -> if (isNightMode) Color.parseColor("#FFB4AB") else Color.parseColor("#8C1D27")
                        6 -> if (isNightMode) Color.parseColor("#93C5FD") else Color.parseColor("#175CD3")
                        else -> if (isNightMode) Color.parseColor("#FDBA74") else Color.parseColor("#7A271A")
                    })
                    paint.isFakeBoldText = true
                }
                sectionBox.addView(groupBadge)
            }

            val numTv = TextView(context).apply {
                text = i.toString()
                textSize = 12f
                setTextColor(numColor)
                paint.isFakeBoldText = true
            }
            sectionBox.addView(numTv)

            val timePair = sectionTimes.getOrElse(i - 1) { Pair("", "") }
            val timeTv = TextView(context).apply {
                text = "${timePair.first}\n${timePair.second}"
                textSize = 7.5f
                setTextColor(timeColor)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 0.9f)
            }
            sectionBox.addView(timeTv)

            sidebarLayout.addView(sectionBox)
        }
    }

    private fun createBreakSidebarView(title: String, time: String, bgColor: String, textColor: String): View {
        return LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp2px(breakHeightDp))
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor(bgColor))
            addView(TextView(context).apply {
                text = title
                textSize = 8.5f
                setTextColor(Color.parseColor(textColor))
                gravity = Gravity.CENTER
                paint.isFakeBoldText = true
            })
        }
    }

    fun setCourses(courses: List<CourseItem>, week: Int = 3) {
        this.allCourses = courses
        this.selectedWeek = week
        notifyWeekendState()
        renderGrid()
    }

    fun setWeek(week: Int) {
        this.selectedWeek = week
        notifyWeekendState()
        renderGrid()
    }

    fun setFontScale(scale: Float) {
        this.fontScale = scale
        renderGrid()
    }

    fun setShowOnlyCurrentWeek(onlyCurrentWeek: Boolean) {
        this.showOnlyCurrentWeek = onlyCurrentWeek
        notifyWeekendState()
        renderGrid()
    }

    private fun notifyWeekendState() {
        val hasWeekend = if (showOnlyCurrentWeek) {
            allCourses.any { it.dayOfWeek in 6..7 && it.isInWeek(selectedWeek) }
        } else {
            allCourses.any { it.dayOfWeek in 6..7 }
        }
        onWeekendVisibilityChanged?.invoke(hasWeekend)
    }

    private fun getSectionTop(s: Int): Int {
        val sClamped = s.coerceIn(1, 13)
        val secH = dp2px(sectionHeightDp)
        val breakH = dp2px(breakHeightDp)
        val base = (sClamped - 1) * secH
        return when {
            sClamped <= 5 -> base
            sClamped <= 10 -> base + breakH
            else -> base + breakH * 2
        }
    }

    private fun renderGrid() {
        if (gridContainer.width <= 0) {
            post { renderGrid() }
            return
        }

        gridContainer.removeAllViews()

        val hasWeekendCourses = allCourses.any { it.dayOfWeek in 6..7 }
        val columnCount = if (hasWeekendCourses) 7 else 5

        val secH = dp2px(sectionHeightDp)
        val breakH = dp2px(breakHeightDp)

        val dividerLineColor = if (isNightMode) Color.parseColor("#20232A") else Color.parseColor("#F2F4F7")
        val breakBgColor = if (isNightMode) Color.parseColor("#1B1C20") else Color.parseColor("#F8F9FA")
        val breakTextColor = if (isNightMode) Color.parseColor("#94A3B8") else Color.parseColor("#667085")

        // 1. Draw subtle horizontal divider lines for each section
        for (i in 1..13) {
            val topPos = getSectionTop(i) + secH
            val line = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp2px(1)).apply {
                    topMargin = topPos - dp2px(1)
                }
                setBackgroundColor(dividerLineColor)
            }
            gridContainer.addView(line)
        }

        // 2. Draw Lunch Break Divider (between section 5 and 6)
        val lunchTop = 5 * secH
        val lunchDivider = LinearLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, breakH).apply {
                topMargin = lunchTop
            }
            setBackgroundColor(breakBgColor)
            gravity = Gravity.CENTER
            addView(TextView(context).apply {
                text = "— 午 休  11:55 ～ 13:00 —"
                textSize = 9.5f
                setTextColor(breakTextColor)
                paint.isFakeBoldText = true
            })
        }
        gridContainer.addView(lunchDivider)

        // 3. Draw Dinner Break Divider (between section 10 and 11)
        val dinnerTop = 10 * secH + breakH
        val dinnerDivider = LinearLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, breakH).apply {
                topMargin = dinnerTop
            }
            setBackgroundColor(breakBgColor)
            gravity = Gravity.CENTER
            addView(TextView(context).apply {
                text = "— 晚 休  16:55 ～ 18:00 —"
                textSize = 9.5f
                setTextColor(breakTextColor)
                paint.isFakeBoldText = true
            })
        }
        gridContainer.addView(dinnerDivider)

        val totalWidth = gridContainer.width
        val colWidth = totalWidth / columnCount

        // 4. Highlight column for today if applicable
        highlightDayOfWeek?.let { day ->
            if (day in 1..columnCount) {
                val highlightLeft = (day - 1) * colWidth
                val highlightBg = View(context).apply {
                    val highlightColor = if (isNightMode) {
                        Color.argb(22, 255, 180, 171)
                    } else {
                        Color.argb(16, 140, 29, 39)
                    }
                    setBackgroundColor(highlightColor)
                    layoutParams = FrameLayout.LayoutParams(colWidth, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                        leftMargin = highlightLeft
                    }
                }
                gridContainer.addView(highlightBg)
            }
        }

        // 5. Filter courses to display:
        val currentWeekCourses = allCourses.filter { it.isInWeek(selectedWeek) }
        val coursesToRender = mutableListOf<Pair<CourseItem, Boolean>>() // Pair(Course, isCurrentWeek)

        for (c in currentWeekCourses) {
            coursesToRender.add(Pair(c, true))
        }

        if (!showOnlyCurrentWeek) {
            // Only consider courses that have not started yet (min week > selectedWeek)
            // and strictly exclude courses that have already ended (max week < selectedWeek)
            val upcomingCourses = allCourses
                .filter { oc ->
                    val minWeek = oc.weeks.minOrNull() ?: 0
                    val maxWeek = oc.weeks.maxOrNull() ?: 0
                    !oc.isInWeek(selectedWeek) && minWeek > selectedWeek && maxWeek >= selectedWeek
                }
                .sortedBy { it.weeks.minOrNull() ?: 0 }

            for (oc in upcomingCourses) {
                val ocStart = oc.startSection
                val ocEnd = oc.startSection + oc.step - 1

                val hasCollision = coursesToRender.any { (existing, _) ->
                    if (existing.dayOfWeek != oc.dayOfWeek) false
                    else {
                        val exStart = existing.startSection
                        val exEnd = existing.startSection + existing.step - 1
                        maxOf(ocStart, exStart) <= minOf(ocEnd, exEnd)
                    }
                }

                if (!hasCollision) {
                    coursesToRender.add(Pair(oc, false))
                }
            }
        }

        val dayGroups = coursesToRender.groupBy { it.first.dayOfWeek }

            for ((dayOfWeek, dayCourseList) in dayGroups) {
                if (!hasWeekendCourses && dayOfWeek > 5) continue
                if (dayOfWeek < 1 || dayOfWeek > columnCount) continue

                for (item in dayCourseList) {
                    val course = item.first
                    val isCurrentWeek = item.second

                    val startSection = course.startSection.coerceIn(1, 13)
                    val step = course.step.coerceIn(1, 14 - startSection)
                    val endSection = (startSection + step - 1).coerceIn(1, 13)

                    val cardTop = getSectionTop(startSection)
                    val cardBottom = getSectionTop(endSection) + secH
                    val cardHeight = cardBottom - cardTop

                    val overlapping = dayCourseList.filter { other ->
                        val oStart = other.first.startSection.coerceIn(1, 13)
                        val oEnd = (oStart + other.first.step - 1).coerceIn(1, 13)
                        maxOf(startSection, oStart) <= minOf(endSection, oEnd)
                    }

                    val overlapCount = overlapping.size
                    val overlapIndex = overlapping.indexOf(item).coerceAtLeast(0)

                    val itemWidth = colWidth / overlapCount
                    val itemLeft = (dayOfWeek - 1) * colWidth + overlapIndex * itemWidth

                    val margin = dp2px(2)
                    val paletteItem = if (isCurrentWeek) {
                        getCardColor(course.colorIndex, isNightMode)
                    } else {
                        nonCurrentWeekColor
                    }

                    val cardView = FrameLayout(context).apply {
                        val shape = GradientDrawable().apply {
                            setColor(paletteItem.bg)
                            cornerRadius = dp2px(10).toFloat()
                            setStroke(dp2px(1), paletteItem.stroke)
                        }
                        val rippleColor = if (isNightMode) {
                            ColorStateList.valueOf(Color.argb(40, 255, 255, 255))
                        } else {
                            ColorStateList.valueOf(Color.argb(40, 0, 0, 0))
                        }
                        background = RippleDrawable(rippleColor, shape, shape)
                        isClickable = true
                        isFocusable = true

                        layoutParams = FrameLayout.LayoutParams(itemWidth - margin * 2, cardHeight - margin * 2).apply {
                            leftMargin = itemLeft + margin
                            topMargin = cardTop + margin
                        }
                    }

                    val contentLayout = LinearLayout(context).apply {
                        orientation = VERTICAL
                        setPadding(dp2px(4), dp2px(4), dp2px(4), dp2px(4))
                        gravity = Gravity.CENTER_HORIZONTAL
                    }

                    // 1. Course Name
                    val nameTv = TextView(context).apply {
                        text = (if (!isCurrentWeek) "[非本周] " else "") + course.name
                        textSize = 10.5f * fontScale
                        setTextColor(paletteItem.text)
                        paint.isFakeBoldText = true
                        maxLines = 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER
                        setLineSpacing(0f, 0.95f)
                    }
                    contentLayout.addView(nameTv)

                    // 2. Classroom
                    if (course.classroom.isNotEmpty()) {
                        val roomTv = TextView(context).apply {
                            text = "@" + course.classroom
                            textSize = 9f * fontScale
                            setTextColor(paletteItem.text)
                            alpha = if (isNightMode) 0.9f else 0.85f
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            gravity = Gravity.CENTER
                            setPadding(0, dp2px(2), 0, 0)
                        }
                        contentLayout.addView(roomTv)
                    }

                    // 3. Teacher Name
                    if (course.teacher.isNotEmpty()) {
                        val teacherTv = TextView(context).apply {
                            text = course.teacher
                            textSize = 8.5f * fontScale
                            setTextColor(paletteItem.text)
                            alpha = if (isNightMode) 0.8f else 0.75f
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            gravity = Gravity.CENTER
                            setPadding(0, dp2px(1), 0, 0)
                        }
                        contentLayout.addView(teacherTv)
                    }

                    cardView.addView(contentLayout)

                    cardView.setOnClickListener {
                        val allInThisSlot = allCourses.filter { c ->
                            c.dayOfWeek == course.dayOfWeek &&
                            maxOf(c.startSection, course.startSection) <= minOf(c.startSection + c.step - 1, endSection)
                        }
                        onCourseClickListener?.invoke(course, allInThisSlot)
                    }

                    gridContainer.addView(cardView)
                }
            }
        }

    private fun dp2px(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}
