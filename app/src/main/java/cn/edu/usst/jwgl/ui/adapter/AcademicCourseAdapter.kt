package cn.edu.usst.jwgl.ui.adapter

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.AcademicCourse
import cn.edu.usst.jwgl.databinding.ItemAcademicCourseBinding

class AcademicCourseAdapter : ListAdapter<AcademicCourse, AcademicCourseAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AcademicCourse>() {
            override fun areItemsTheSame(oldItem: AcademicCourse, newItem: AcademicCourse): Boolean {
                return oldItem.code == newItem.code && oldItem.name == newItem.name
            }

            override fun areContentsTheSame(oldItem: AcademicCourse, newItem: AcademicCourse): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAcademicCourseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemAcademicCourseBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: AcademicCourse) {
            val context = binding.root.context

            binding.tvCourseName.text = item.name.ifEmpty { "未命名课程" }
            binding.tvCourseCode.text = item.code.ifEmpty { "--" }
            binding.tvCourseCredit.text = "${item.credits} 学分"

            if (item.term.isNotEmpty()) {
                binding.tvCourseTerm.visibility = View.VISIBLE
                binding.tvCourseTerm.text = item.term
            } else {
                binding.tvCourseTerm.visibility = View.GONE
            }

            binding.tvStatusBadge.text = item.statusLabel

            val (bgColor, textColor) = getStatusColors(context, item)
            val badgeDrawable = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = 24f // 8dp
            }
            binding.layoutScoreBadge.background = badgeDrawable
            binding.tvStatusBadge.setTextColor(textColor)

            if (item.score.isNotEmpty()) {
                binding.tvScoreDetail.visibility = View.VISIBLE
                val scoreText = if (item.score.toDoubleOrNull() != null) "${item.score}分" else item.score
                binding.tvScoreDetail.text = scoreText
                binding.tvScoreDetail.setTextColor(textColor)
            } else {
                binding.tvScoreDetail.visibility = View.GONE
            }
        }

        private fun getStatusColors(context: Context, item: AcademicCourse): Pair<Int, Int> {
            return when {
                item.isPassed -> Pair(
                    ContextCompat.getColor(context, R.color.grade_green_bg),
                    ContextCompat.getColor(context, R.color.grade_green_text)
                )
                item.isInProgress -> Pair(
                    ContextCompat.getColor(context, R.color.primary_container),
                    ContextCompat.getColor(context, R.color.primary)
                )
                item.isFailed -> Pair(
                    ContextCompat.getColor(context, R.color.grade_red_bg),
                    ContextCompat.getColor(context, R.color.grade_red_text)
                )
                item.isSubstituted -> Pair(
                    ContextCompat.getColor(context, R.color.grade_blue_bg),
                    ContextCompat.getColor(context, R.color.grade_blue_text)
                )
                item.isNotStarted -> Pair(
                    ContextCompat.getColor(context, R.color.surface_variant),
                    ContextCompat.getColor(context, R.color.text_secondary)
                )
                else -> Pair(
                    ContextCompat.getColor(context, R.color.surface_variant),
                    ContextCompat.getColor(context, R.color.text_tertiary)
                )
            }
        }
    }
}
