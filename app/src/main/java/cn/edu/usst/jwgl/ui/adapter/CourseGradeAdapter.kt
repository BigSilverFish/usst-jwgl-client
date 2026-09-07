package cn.edu.usst.jwgl.ui.adapter

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.CourseGrade
import cn.edu.usst.jwgl.databinding.ItemCourseGradeBinding

class CourseGradeAdapter : RecyclerView.Adapter<CourseGradeAdapter.ViewHolder>() {

    private val items = mutableListOf<CourseGrade>()

    fun submitList(newItems: List<CourseGrade>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCourseGradeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(private val binding: ItemCourseGradeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: CourseGrade) {
            val context = binding.root.context

            binding.tvCourseName.text = item.courseName.ifEmpty { "未命名课程" }
            binding.tvCourseCode.text = if (item.courseId.isNotEmpty()) item.courseId else "未知代码"
            binding.tvExamType.text = if (item.examType.isNotEmpty()) item.examType else "考试"
            binding.tvCourseCredit.text = "${item.credit} 学分"
            binding.tvSemesterTitle.text = item.semesterTitle.ifEmpty { "其他学期" }

            val (bgColor, textColor) = getScoreTierColors(context, item.overallScore)
            val scoreText = if (item.overallScore.isNotEmpty()) {
                if (item.overallScore.toDoubleOrNull() != null) "${item.overallScore}分" else item.overallScore
            } else "--"
            binding.tvOverallScore.text = scoreText
            binding.tvOverallScore.setTextColor(textColor)

            binding.tvGpa.text = String.format("GPA %.2f", item.gpa)
            binding.tvGpa.setTextColor(textColor)

            val badgeDrawable = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 24f // 8dp
            }
            binding.layoutScoreBadge.background = badgeDrawable

            val psRatioStr = if (item.regularRatio.isNotEmpty() && item.regularRatio != "0") " (${item.regularRatio}%)" else ""
            val psScoreStr = if (item.regularScore.isNotEmpty()) "${item.regularScore}分" else "--"
            binding.tvRegularScore.text = "平时: $psScoreStr$psRatioStr"

            val qmRatioStr = if (item.finalRatio.isNotEmpty() && item.finalRatio != "0") " (${item.finalRatio}%)" else ""
            val qmScoreStr = if (item.finalScore.isNotEmpty()) "${item.finalScore}分" else "--"
            binding.tvFinalScore.text = "期末: $qmScoreStr$qmRatioStr"
        }

        private fun getScoreTierColors(context: Context, scoreStr: String): Pair<Int, Int> {
            val score = scoreStr.toDoubleOrNull()
            return when {
                score != null -> when {
                    score < 60.0 -> Pair(ContextCompat.getColor(context, R.color.grade_red_bg), ContextCompat.getColor(context, R.color.grade_red_text))
                    score < 70.0 -> Pair(ContextCompat.getColor(context, R.color.grade_orange_bg), ContextCompat.getColor(context, R.color.grade_orange_text))
                    score < 80.0 -> Pair(ContextCompat.getColor(context, R.color.grade_purple_bg), ContextCompat.getColor(context, R.color.grade_purple_text))
                    score < 90.0 -> Pair(ContextCompat.getColor(context, R.color.grade_blue_bg), ContextCompat.getColor(context, R.color.grade_blue_text))
                    else -> Pair(ContextCompat.getColor(context, R.color.grade_green_bg), ContextCompat.getColor(context, R.color.grade_green_text))
                }
                scoreStr.contains("不及格") || scoreStr.contains("不合格") -> Pair(ContextCompat.getColor(context, R.color.grade_red_bg), ContextCompat.getColor(context, R.color.grade_red_text))
                scoreStr.contains("及格") || scoreStr.contains("合格") -> Pair(ContextCompat.getColor(context, R.color.grade_orange_bg), ContextCompat.getColor(context, R.color.grade_orange_text))
                scoreStr.contains("中等") -> Pair(ContextCompat.getColor(context, R.color.grade_purple_bg), ContextCompat.getColor(context, R.color.grade_purple_text))
                scoreStr.contains("良好") -> Pair(ContextCompat.getColor(context, R.color.grade_blue_bg), ContextCompat.getColor(context, R.color.grade_blue_text))
                scoreStr.contains("优秀") -> Pair(ContextCompat.getColor(context, R.color.grade_green_bg), ContextCompat.getColor(context, R.color.grade_green_text))
                else -> Pair(ContextCompat.getColor(context, R.color.surface_variant), ContextCompat.getColor(context, R.color.text_primary))
            }
        }
    }
}
