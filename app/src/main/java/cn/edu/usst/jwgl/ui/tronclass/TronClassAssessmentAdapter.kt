package cn.edu.usst.jwgl.ui.tronclass

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.TronClassActivityItem
import cn.edu.usst.jwgl.data.model.TronClassExam
import cn.edu.usst.jwgl.databinding.ItemTronclassAssessmentBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

sealed class TronClassAssessmentItem {
    data class Homework(val activity: TronClassActivityItem) : TronClassAssessmentItem()
    data class Exam(val exam: TronClassExam) : TronClassAssessmentItem()
}

class TronClassAssessmentAdapter(
    private var items: List<TronClassAssessmentItem> = emptyList(),
    private val onItemClick: (TronClassAssessmentItem) -> Unit
) : RecyclerView.Adapter<TronClassAssessmentAdapter.AssessmentViewHolder>() {

    fun updateData(newItems: List<TronClassAssessmentItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssessmentViewHolder {
        val binding = ItemTronclassAssessmentBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return AssessmentViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AssessmentViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class AssessmentViewHolder(private val binding: ItemTronclassAssessmentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: TronClassAssessmentItem) {
            val context = binding.root.context

            when (item) {
                is TronClassAssessmentItem.Homework -> {
                    val act = item.activity
                    binding.ivTypeIcon.setImageResource(R.drawable.ic_homework)
                    binding.tvTypeBadge.text = "作业"
                    binding.tvTypeBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
                    binding.tvTitle.text = act.getCleanTitle()

                    // 作业完成与截止状态判断
                    val isCompleted = act.completeness == "full" || act.completeness == "complete"
                    val isExpired = isDateExpired(act.endTime)

                    if (isCompleted) {
                        binding.tvStatusBadge.text = "已提交"
                        binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#E8F5E9"))
                        binding.tvStatusBadge.setTextColor(Color.parseColor("#2E7D32"))
                    } else if (isExpired) {
                        binding.tvStatusBadge.text = "已截止"
                        binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#EEEEEE"))
                        binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                    } else {
                        binding.tvStatusBadge.text = "待提交"
                        binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#FFF3E0"))
                        binding.tvStatusBadge.setTextColor(Color.parseColor("#E65100"))
                    }

                    // 截止时间与附件提示
                    val parts = mutableListOf<String>()
                    val endFmt = formatIsoDate(act.endTime)
                    if (endFmt.isNotBlank()) {
                        parts.add("截止: $endFmt")
                    }
                    val uploadCount = act.uploads?.size ?: 0
                    if (uploadCount > 0) {
                        parts.add("$uploadCount 个附件")
                    }
                    binding.tvSubInfo.text = if (parts.isNotEmpty()) parts.joinToString(" · ") else "无明确截止时间"

                    val startFmt = formatIsoDate(act.startTime)
                    if (startFmt.isNotBlank() && endFmt.isNotBlank()) {
                        binding.tvTimeRange.visibility = View.VISIBLE
                        binding.tvTimeRange.text = "开放: $startFmt 至 $endFmt"
                    } else {
                        binding.tvTimeRange.visibility = View.GONE
                    }
                }

                is TronClassAssessmentItem.Exam -> {
                    val exam = item.exam
                    binding.ivTypeIcon.setImageResource(R.drawable.ic_exam)
                    binding.tvTypeBadge.text = "测验"
                    binding.tvTypeBadge.setTextColor(Color.parseColor("#8E24AA"))
                    binding.tvTitle.text = exam.getCleanTitle()

                    // 测验状态 Badge
                    val sc = exam.score
                    if (sc != null && !sc.isNaN()) {
                        val scoreStr = if (sc % 1.0 == 0.0) "${sc.toInt()} 分" else "$sc 分"
                        binding.tvStatusBadge.text = scoreStr
                        binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#E8F5E9"))
                        binding.tvStatusBadge.setTextColor(Color.parseColor("#2E7D32"))
                    } else if ((exam.submittedTimes ?: 0) > 0) {
                        binding.tvStatusBadge.text = "已提交(${exam.submittedTimes}次)"
                        binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#E3F2FD"))
                        binding.tvStatusBadge.setTextColor(Color.parseColor("#1565C0"))
                    } else if (exam.isClosed) {
                        binding.tvStatusBadge.text = "已截止"
                        binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#EEEEEE"))
                        binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                    } else if (exam.isStarted) {
                        binding.tvStatusBadge.text = "进行中"
                        binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#FFF3E0"))
                        binding.tvStatusBadge.setTextColor(Color.parseColor("#E65100"))
                    } else {
                        binding.tvStatusBadge.text = "未开始"
                        binding.tvStatusBadge.setBackgroundColor(Color.parseColor("#F5F5F5"))
                        binding.tvStatusBadge.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    }

                    // 时长、题量、总分
                    val parts = mutableListOf<String>()
                    val limitStr = exam.getFormattedLimitTime()
                    parts.add(limitStr)
                    val subjCount = exam.subjectsCount ?: 0
                    if (subjCount > 0) {
                        parts.add("$subjCount 题")
                    }
                    val totalPts = exam.totalPoints
                    if (totalPts != null && totalPts > 0) {
                        val pt = if (totalPts % 1.0 == 0.0) totalPts.toInt().toString() else totalPts.toString()
                        parts.add("总分 $pt")
                    }
                    binding.tvSubInfo.text = parts.joinToString(" · ")

                    // 起止时间
                    val timeRange = exam.getFormattedTimeRange()
                    if (timeRange.isNotBlank()) {
                        binding.tvTimeRange.visibility = View.VISIBLE
                        binding.tvTimeRange.text = timeRange
                    } else {
                        binding.tvTimeRange.visibility = View.GONE
                    }
                }
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }

        private fun isDateExpired(dateStr: String?): Boolean {
            if (dateStr.isNullOrBlank()) return false
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val clean = dateStr.substringBefore("Z").substringBefore("+").substringBefore(".")
                val date = sdf.parse(clean) ?: return false
                date.before(Date())
            } catch (e: Exception) {
                false
            }
        }

        private fun formatIsoDate(isoString: String?): String {
            if (isoString.isNullOrBlank()) return ""
            return try {
                TronClassHtmlHelper.formatIsoDate(isoString)
            } catch (e: Exception) {
                isoString
            }
        }
    }
}
