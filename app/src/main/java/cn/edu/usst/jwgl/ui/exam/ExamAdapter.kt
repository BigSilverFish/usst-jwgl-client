package cn.edu.usst.jwgl.ui.exam

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.ExamItem
import cn.edu.usst.jwgl.util.CourseReminderManager

class ExamAdapter(
    private var examList: List<ExamItem> = emptyList(),
    private val onExamClick: ((ExamItem) -> Unit)? = null
) : RecyclerView.Adapter<ExamAdapter.ExamViewHolder>() {

    fun submitList(newList: List<ExamItem>) {
        this.examList = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExamViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_exam_card, parent, false)
        return ExamViewHolder(view)
    }

    override fun getItemCount(): Int = examList.size

    override fun onBindViewHolder(holder: ExamViewHolder, position: Int) {
        val item = examList[position]
        holder.bind(item)
        holder.itemView.setOnClickListener {
            onExamClick?.invoke(item)
        }
    }

    class ExamViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvCourseName = itemView.findViewById<TextView>(R.id.tvExamCourseName)
        private val tvNatureBadge = itemView.findViewById<TextView>(R.id.tvExamNatureBadge)
        private val tvTime = itemView.findViewById<TextView>(R.id.tvExamTime)
        private val tvCountdown = itemView.findViewById<TextView>(R.id.tvExamCountdown)
        private val tvLocation = itemView.findViewById<TextView>(R.id.tvExamLocation)
        private val tvSeat = itemView.findViewById<TextView>(R.id.tvExamSeat)
        private val tvReminderStatus = itemView.findViewById<TextView>(R.id.tvExamReminderStatus)
        private val tvMethod = itemView.findViewById<TextView>(R.id.tvExamMethod)

        fun bind(exam: ExamItem) {
            tvCourseName.text = exam.courseName

            val nature = exam.examNature.ifEmpty { exam.examName }.ifEmpty { "考试" }
            tvNatureBadge.text = nature
            when {
                nature.contains("期中") -> {
                    tvNatureBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F3E8FF"))
                    tvNatureBadge.setTextColor(Color.parseColor("#6B21A8"))
                }
                nature.contains("补考") || nature.contains("缓考") -> {
                    tvNatureBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FEF3C7"))
                    tvNatureBadge.setTextColor(Color.parseColor("#92400E"))
                }
                else -> {
                    tvNatureBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFDAD8"))
                    tvNatureBadge.setTextColor(Color.parseColor("#8C1D27"))
                }
            }

            val sessionStr = if (exam.session.isNotEmpty()) " (${exam.session})" else " (第${exam.getSessionIndex()}场)"
            tvTime.text = "📅 ${exam.examTime}$sessionStr"

            val countdown = exam.getCountdownString()
            tvCountdown.text = countdown
            if (countdown == "已结束") {
                tvCountdown.setTextColor(Color.parseColor("#98A2B3"))
                tvReminderStatus.text = "已结束"
                tvReminderStatus.setTextColor(Color.parseColor("#98A2B3"))
            } else {
                tvCountdown.setTextColor(Color.parseColor("#8C1D27"))
                val context = itemView.context
                if (CourseReminderManager.isExamReminderEnabled(context)) {
                    val adv = CourseReminderManager.formatAdvanceMinutes(CourseReminderManager.getExamReminderAdvanceMinutes(context))
                    tvReminderStatus.text = "🔔 考前 $adv 已设提醒"
                    tvReminderStatus.setTextColor(Color.parseColor("#14532D"))
                } else {
                    tvReminderStatus.text = "🔕 考前提醒未开启"
                    tvReminderStatus.setTextColor(Color.parseColor("#667085"))
                }
            }

            val locText = if (exam.building.isNotEmpty() || exam.location.isNotEmpty()) {
                "📍 考场：${exam.building} ${exam.location}".trim()
            } else {
                "📍 考场：待教务处排定"
            }
            tvLocation.text = locText

            val seatText = if (exam.seatNumber.isNotEmpty()) "💺 座位号: ${exam.seatNumber}" else "💺 座位待定"
            tvSeat.text = seatText

            val methodText = buildString {
                if (exam.examMethod.isNotEmpty()) append(exam.examMethod)
                if (exam.credit > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("${exam.credit}学分")
                }
            }
            tvMethod.text = methodText
            tvMethod.visibility = if (methodText.isEmpty()) View.GONE else View.VISIBLE
        }
    }
}
