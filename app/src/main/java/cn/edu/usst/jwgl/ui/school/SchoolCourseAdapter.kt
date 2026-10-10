package cn.edu.usst.jwgl.ui.school

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.MergedSchoolCourse

class SchoolCourseAdapter(
    private val list: List<MergedSchoolCourse>,
    private val onSyllabusClick: ((MergedSchoolCourse) -> Unit)? = null,
    private val onAddClick: (MergedSchoolCourse) -> Unit
) : RecyclerView.Adapter<SchoolCourseAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvCourseName: TextView = view.findViewById(R.id.tvCourseName)
        val tvLevelBadge: TextView = view.findViewById(R.id.tvLevelBadge)
        val tvCourseTypeBadge: TextView = view.findViewById(R.id.tvCourseTypeBadge)
        val tvCreditBadge: TextView = view.findViewById(R.id.tvCreditBadge)
        val tvTeacher: TextView = view.findViewById(R.id.tvTeacher)
        val tvCollege: TextView = view.findViewById(R.id.tvCollege)
        val tvClassCode: TextView = view.findViewById(R.id.tvClassCode)
        val llSlotsContainer: LinearLayout = view.findViewById(R.id.llSlotsContainer)
        val tvSlotSummary: TextView = view.findViewById(R.id.tvSlotSummary)
        val btnViewSyllabus: MaterialButton = view.findViewById(R.id.btnViewSyllabus)
        val btnAdd: MaterialButton = view.findViewById(R.id.btnAddCourse)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_school_course, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density

        holder.tvCourseName.text = item.kcmc.ifEmpty { "未命名课程" }

        // 区分本研课程：
        // 本科课程班级号格式为 (xxxx-xxxx-x)-xxxxxxxx-xx
        // 研究生课程班级号格式为 xxxxxxxx-xxxxx-xxx班
        if (item.isGraduate) {
            holder.tvLevelBadge.text = "研究生"
            holder.tvLevelBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.grade_purple_bg))
            holder.tvLevelBadge.setTextColor(ContextCompat.getColor(context, R.color.grade_purple_text))
        } else {
            holder.tvLevelBadge.text = "本科"
            holder.tvLevelBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.grade_blue_bg))
            holder.tvLevelBadge.setTextColor(ContextCompat.getColor(context, R.color.grade_blue_text))
        }

        val categoryText = item.category.ifEmpty { "待定" }
        holder.tvCourseTypeBadge.text = categoryText
        when (categoryText) {
            "理论" -> {
                holder.tvCourseTypeBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.grade_blue_bg))
                holder.tvCourseTypeBadge.setTextColor(ContextCompat.getColor(context, R.color.grade_blue_text))
            }
            "实践" -> {
                holder.tvCourseTypeBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.grade_green_bg))
                holder.tvCourseTypeBadge.setTextColor(ContextCompat.getColor(context, R.color.grade_green_text))
            }
            else -> {
                holder.tvCourseTypeBadge.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.badge_bg))
                holder.tvCourseTypeBadge.setTextColor(ContextCompat.getColor(context, R.color.badge_text))
            }
        }

        val creditInfo = StringBuilder()
        if (item.xf.isNotEmpty()) creditInfo.append("${item.xf}学分")
        if (item.xs.isNotEmpty()) {
            if (creditInfo.isNotEmpty()) creditInfo.append(" · ")
            creditInfo.append("${item.xs}学时")
        }
        holder.tvCreditBadge.text = creditInfo.toString()
        holder.tvCreditBadge.visibility = if (creditInfo.isNotEmpty()) View.VISIBLE else View.GONE

        val teacherTitle = if (item.lszc.isNotEmpty() && item.lszc != "无") " (${item.lszc})" else ""
        holder.tvTeacher.text = "教师：${item.lsmc.ifEmpty { "待定" }}$teacherTitle"
        holder.tvCollege.text = item.xy.ifEmpty { "全校" }

        // 课程代码已体现在班级号中，因此去掉单列出来的课程代码，直接显示班级号
        val classText = if (item.bjmc.isNotEmpty()) {
            "班级: ${item.bjmc}"
        } else if (item.bjdm.isNotEmpty()) {
            "班级: ${item.bjdm}"
        } else {
            ""
        }
        holder.tvClassCode.text = classText
        holder.tvClassCode.visibility = if (classText.isNotEmpty()) View.VISIBLE else View.GONE

        // Populate Merged Time Slots
        holder.llSlotsContainer.removeAllViews()
        val slots = item.slots

        if (slots.isEmpty()) {
            val emptyTv = TextView(context).apply {
                text = "暂无具体上课时段"
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }
            holder.llSlotsContainer.addView(emptyTv)
        } else {
            for ((index, slot) in slots.withIndex()) {
                val slotRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val padV = (4 * density).toInt()
                    setPadding(0, padV, 0, padV)
                }

                // Slot badge (e.g. "周二 3-4节")
                val tvSlotBadge = TextView(context).apply {
                    text = "${slot.formattedDayOfWeek} ${slot.formattedSections}"
                    textSize = 12f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(ContextCompat.getColor(context, R.color.primary))
                    setBackgroundResource(R.drawable.badge_bg)
                    val padH = (6 * density).toInt()
                    val padBadgeV = (2 * density).toInt()
                    setPadding(padH, padBadgeV, padH, padBadgeV)
                }
                slotRow.addView(tvSlotBadge)

                // Time, Classroom, and Weeks
                val tvSlotDetails = TextView(context).apply {
                    val timeStr = if (slot.formattedTime.isNotEmpty()) " (${slot.formattedTime})" else ""
                    val roomStr = slot.jsdd.ifEmpty { "待定地点" }
                    text = "$timeStr @$roomStr · ${slot.formattedWeeks}"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                    val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (8 * density).toInt()
                    }
                    layoutParams = lp
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }
                slotRow.addView(tvSlotDetails)

                holder.llSlotsContainer.addView(slotRow)
            }
        }

        // Summary text and button
        holder.tvSlotSummary.text = if (slots.size > 1) "共 ${slots.size} 个不同上课时段" else "共 1 个上课时段"
        holder.btnAdd.text = if (slots.size > 1) "+ 添至课表 (${slots.size}时段)" else "+ 添至课表"

        holder.btnViewSyllabus.setOnClickListener {
            onSyllabusClick?.invoke(item)
        }

        holder.btnAdd.setOnClickListener {
            onAddClick(item)
        }
    }

    override fun getItemCount(): Int = list.size
}
