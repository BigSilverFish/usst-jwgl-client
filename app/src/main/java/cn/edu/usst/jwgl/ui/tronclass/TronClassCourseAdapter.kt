package cn.edu.usst.jwgl.ui.tronclass

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.data.model.TronClassCourse
import cn.edu.usst.jwgl.databinding.ItemTronclassCourseBinding

class TronClassCourseAdapter(
    private var items: List<TronClassCourse> = emptyList(),
    private val onItemClick: (TronClassCourse) -> Unit
) : RecyclerView.Adapter<TronClassCourseAdapter.CourseViewHolder>() {

    fun updateData(newItems: List<TronClassCourse>) {
        items = newItems
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

        fun bind(course: TronClassCourse) {
            val cleanName = course.getCleanName()
            binding.tvCourseTitle.text = cleanName

            val firstChar = cleanName.firstOrNull { it.isLetterOrDigit() }?.toString() ?: "课"
            binding.tvCourseAvatar.text = firstChar

            val dept = course.department?.name ?: ""
            val code = course.courseCode ?: ""
            val sub = when {
                dept.isNotBlank() && code.isNotBlank() -> "$code · $dept"
                code.isNotBlank() -> code
                dept.isNotBlank() -> dept
                else -> "课程代码未标明"
            }
            binding.tvCourseSub.text = sub

            binding.root.setOnClickListener {
                onItemClick(course)
            }
        }
    }
}
