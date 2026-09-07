package cn.edu.usst.jwgl.ui.view

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import cn.edu.usst.jwgl.util.SemesterHelper
import java.util.Calendar

class TimetableHeaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val sidebarWidthDp = 38
    private val weekdays = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    private val dayViews = ArrayList<LinearLayout>()
    private var hasWeekend = false

    private var week1MondayStr: String? = null
    private var selectedWeek: Int = 1

    private val isNightMode: Boolean
        get() = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        buildHeader()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildHeader()
    }

    fun setDateInfo(week1MondayStr: String?, week: Int) {
        this.week1MondayStr = week1MondayStr
        this.selectedWeek = week
        buildHeader()
    }

    private fun buildHeader() {
        removeAllViews()
        dayViews.clear()

        val bgCol = if (isNightMode) Color.parseColor("#121316") else Color.parseColor("#FFFFFF")
        val sidebarBgCol = if (isNightMode) Color.parseColor("#1B1C20") else Color.parseColor("#FAFAFA")
        val sepCol = if (isNightMode) Color.parseColor("#2E333D") else Color.parseColor("#EAECF0")
        val monthTvColor = if (isNightMode) Color.parseColor("#FFB4AB") else Color.parseColor("#8C1D27")
        val todayPillBg = if (isNightMode) Color.parseColor("#4A1B20") else Color.parseColor("#FFDAD8")
        val todayTextColor = if (isNightMode) Color.parseColor("#FFB4AB") else Color.parseColor("#8C1D27")
        val normalDayTextColor = if (isNightMode) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
        val dateTextColor = if (isNightMode) Color.parseColor("#94A3B8") else Color.parseColor("#64748B")

        setBackgroundColor(bgCol)

        // Calculate specific dates for the 7 days of the selected week
        val weekDates = SemesterHelper.getDatesForWeek(week1MondayStr, selectedWeek)
        val headerMonth = (weekDates[0].get(Calendar.MONTH) + 1).toString() + "月"

        // 1. Month Box (Aligns with sidebar)
        val monthBox = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(dp2px(sidebarWidthDp), LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER
            setBackgroundColor(sidebarBgCol)
        }

        val monthTv = TextView(context).apply {
            text = headerMonth
            textSize = 12f
            setTextColor(monthTvColor)
            paint.isFakeBoldText = true
            gravity = Gravity.CENTER
        }
        monthBox.addView(monthTv)
        addView(monthBox)

        // 2. Separator line after sidebar
        val sidebarSep = View(context).apply {
            layoutParams = LayoutParams(dp2px(1), LayoutParams.MATCH_PARENT)
            setBackgroundColor(sepCol)
        }
        addView(sidebarSep)

        // 3. Days Container (Takes remaining width)
        val todayCal = SemesterHelper.getToday()
        val daysContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            setBackgroundColor(bgCol)
        }

        for (i in 0..6) {
            val dayCol = LinearLayout(context).apply {
                orientation = VERTICAL
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                gravity = Gravity.CENTER
                setPadding(dp2px(1), dp2px(2), dp2px(1), dp2px(2))
            }

            val dayCal = weekDates[i]
            val isToday = SemesterHelper.isSameDay(dayCal, todayCal)
            val dateStr = "${dayCal.get(Calendar.MONTH) + 1}/${dayCal.get(Calendar.DAY_OF_MONTH)}"

            val dayPill = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp2px(4), dp2px(3), dp2px(4), dp2px(3))
                if (isToday) {
                    background = GradientDrawable().apply {
                        setColor(todayPillBg)
                        cornerRadius = dp2px(10).toFloat()
                    }
                }
            }

            val weekTv = TextView(context).apply {
                text = weekdays[i]
                textSize = 11.5f
                gravity = Gravity.CENTER
                setTextColor(if (isToday) todayTextColor else normalDayTextColor)
                if (isToday) paint.isFakeBoldText = true
            }
            dayPill.addView(weekTv)

            val dateTv = TextView(context).apply {
                text = if (isToday) "$dateStr·今" else dateStr
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(if (isToday) todayTextColor else dateTextColor)
                if (isToday) paint.isFakeBoldText = true
            }
            dayPill.addView(dateTv)

            dayCol.addView(dayPill)
            dayViews.add(dayCol)
            daysContainer.addView(dayCol)

            if (i >= 5 && !hasWeekend) {
                dayCol.visibility = View.GONE
            }
        }

        addView(daysContainer)
    }

    fun setWeekendVisible(visible: Boolean) {
        if (hasWeekend != visible) {
            hasWeekend = visible
            for (i in 5..6) {
                if (i < dayViews.size) {
                    dayViews[i].visibility = if (visible) View.VISIBLE else View.GONE
                }
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
