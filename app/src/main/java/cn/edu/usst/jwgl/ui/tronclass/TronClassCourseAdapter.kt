package cn.edu.usst.jwgl.ui.tronclass

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.LearningPlatform
import cn.edu.usst.jwgl.data.model.TronClassCourse
import cn.edu.usst.jwgl.data.model.UnifiedCourse
import cn.edu.usst.jwgl.databinding.ItemTronclassCourseBinding

class TronClassCourseAdapter(
    private val onTronClassClick: ((TronClassCourse) -> Unit)? = null
) : RecyclerView.Adapter<TronClassCourseAdapter.CourseViewHolder>() {

    private var items: List<UnifiedCourse> = emptyList()
    var isMultiPlatformMode: Boolean = false
    var onChaoXingClickListener: ((UnifiedCourse) -> Unit)? = null
    var onUnifiedClickListener: ((UnifiedCourse) -> Unit)? = null

    fun updateData(newItems: List<TronClassCourse>) {
        this.items = newItems.map { UnifiedCourse.fromTronClass(it) }
        this.isMultiPlatformMode = false
        notifyDataSetChanged()
    }

    fun updateUnifiedData(newItems: List<UnifiedCourse>, multiPlatform: Boolean = false) {
        this.items = newItems
        this.isMultiPlatformMode = multiPlatform
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseViewHolder {
        val binding = ItemTronclassCourseBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CourseViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CourseViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class CourseViewHolder(private val binding: ItemTronclassCourseBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(course: UnifiedCourse) {
            val context = binding.root.context

            // 1. 课程名称与平台徽标
            if (isMultiPlatformMode) {
                val badgeText = if (course.platform.isChaoXing) "【学习通】" else "【畅课】"
                val badgeColor = course.platform.getBrandColor()
                val ssb = SpannableStringBuilder(badgeText).apply {
                    setSpan(ForegroundColorSpan(badgeColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                ssb.append(course.cleanName)
                binding.tvCourseTitle.text = ssb
                binding.tvCourseAvatar.setTextColor(badgeColor)
            } else {
                binding.tvCourseTitle.text = course.cleanName
                binding.tvCourseAvatar.setTextColor(ContextCompat.getColor(context, R.color.primary))
            }

            // 2. 头像首字
            binding.tvCourseAvatar.text = course.getDisplayAvatarText()

            // 3. 智能副标题拼接 (教师 · 教学班 · 学院)
            val teacher = course.teacherName.trim()
            val dept = course.department?.trim() ?: ""
            val code = course.courseCode?.trim() ?: ""

            val sub = when {
                teacher.isNotBlank() && dept.isNotBlank() -> "$teacher · $dept"
                teacher.isNotBlank() && code.isNotBlank() -> "$teacher · $code"
                code.isNotBlank() && dept.isNotBlank() -> "$code · $dept"
                teacher.isNotBlank() -> teacher
                dept.isNotBlank() -> dept
                code.isNotBlank() -> code
                else -> "课程代码未标明"
            }
            binding.tvCourseSub.text = sub

            // 4. 双轨点击分发
            binding.root.setOnClickListener {
                if (onUnifiedClickListener != null) {
                    onUnifiedClickListener?.invoke(course)
                } else {
                    when (course.platform) {
                        LearningPlatform.TRONCLASS -> {
                            val raw = course.rawTronClassCourse ?: TronClassCourse(
                                id = course.originalCourseId.toLongOrNull() ?: 0L,
                                name = course.name,
                                courseCode = course.courseCode,
                                cover = course.coverUrl,
                                instructors = if (course.teacherName.isNotBlank()) listOf(course.teacherName) else emptyList()
                            )
                            onTronClassClick?.invoke(raw)
                        }
                        LearningPlatform.CHAOXING -> {
                            onChaoXingClickListener?.invoke(course)
                        }
                    }
                }
            }
        }
    }
}
