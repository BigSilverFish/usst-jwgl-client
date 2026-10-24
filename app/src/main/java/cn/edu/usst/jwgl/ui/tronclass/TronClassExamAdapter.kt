package cn.edu.usst.jwgl.ui.tronclass

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.TronClassExam
import cn.edu.usst.jwgl.databinding.ItemTronclassExamBinding

class TronClassExamAdapter(
    private var exams: List<TronClassExam> = emptyList(),
    private val onExamClick: (TronClassExam) -> Unit
) : RecyclerView.Adapter<TronClassExamAdapter.ExamViewHolder>() {

    fun updateData(newExams: List<TronClassExam>) {
        exams = newExams
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExamViewHolder {
        val binding = ItemTronclassExamBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ExamViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ExamViewHolder, position: Int) {
        holder.bind(exams[position])
    }

    override fun getItemCount(): Int = exams.size

    inner class ExamViewHolder(private val binding: ItemTronclassExamBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(exam: TronClassExam) {
            val context = binding.root.context
            binding.tvExamTitle.text = exam.getCleanTitle()

            // Status Badge
            val sc = exam.score
            if (sc != null && !sc.isNaN()) {
                val scoreStr = if (sc % 1.0 == 0.0) "${sc.toInt()} 分" else "$sc 分"
                binding.tvExamStatusBadge.text = scoreStr
                binding.tvExamStatusBadge.setBackgroundColor(Color.parseColor("#E8F5E9"))
                binding.tvExamStatusBadge.setTextColor(Color.parseColor("#2E7D32"))
            } else if (exam.submittedTimes != null && exam.submittedTimes!! > 0) {
                binding.tvExamStatusBadge.text = "已提交(${exam.submittedTimes}次)"
                binding.tvExamStatusBadge.setBackgroundColor(Color.parseColor("#E3F2FD"))
                binding.tvExamStatusBadge.setTextColor(Color.parseColor("#1565C0"))
            } else if (exam.isClosed) {
                binding.tvExamStatusBadge.text = "已截止"
                binding.tvExamStatusBadge.setBackgroundColor(Color.parseColor("#EEEEEE"))
                binding.tvExamStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            } else if (exam.isStarted) {
                binding.tvExamStatusBadge.text = "进行中"
                binding.tvExamStatusBadge.setBackgroundColor(Color.parseColor("#FFF3E0"))
                binding.tvExamStatusBadge.setTextColor(Color.parseColor("#E65100"))
            } else {
                binding.tvExamStatusBadge.text = "未开始"
                binding.tvExamStatusBadge.setBackgroundColor(Color.parseColor("#F5F5F5"))
                binding.tvExamStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            }

            // Sub info: 时长、题量、总分
            val parts = mutableListOf<String>()
            val limitStr = exam.getFormattedLimitTime()
            parts.add(limitStr)

            if (exam.subjectsCount != null && exam.subjectsCount!! > 0) {
                parts.add("${exam.subjectsCount} 题")
            }
            if (exam.totalPoints != null && exam.totalPoints!! > 0) {
                val pt = if (exam.totalPoints!! % 1.0 == 0.0) exam.totalPoints!!.toInt().toString() else exam.totalPoints.toString()
                parts.add("总分 $pt")
            }

            binding.tvExamSubInfo.text = parts.joinToString(" · ")

            // 起止时间
            val timeRange = exam.getFormattedTimeRange()
            if (timeRange.isNotBlank()) {
                binding.tvExamTimeRange.visibility = android.view.View.VISIBLE
                binding.tvExamTimeRange.text = timeRange
            } else {
                binding.tvExamTimeRange.visibility = android.view.View.GONE
            }

            binding.root.setOnClickListener {
                onExamClick(exam)
            }
        }
    }
}
