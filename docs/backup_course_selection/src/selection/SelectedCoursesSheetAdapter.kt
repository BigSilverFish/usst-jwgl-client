package cn.edu.usst.jwgl.ui.selection

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.data.model.SelectedCourseItem
import cn.edu.usst.jwgl.databinding.ItemSelectedCourseSheetBinding

class SelectedCoursesSheetAdapter(
    private val onDropClicked: (SelectedCourseItem) -> Unit
) : ListAdapter<SelectedCourseItem, SelectedCoursesSheetAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSelectedCourseSheetBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemSelectedCourseSheetBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SelectedCourseItem) {
            binding.tvSheetCourseName.text = item.courseName
            binding.tvSheetCredit.text = String.format("%.1f 学分", item.credit)
            binding.tvSheetCourseCode.text = "代码: ${item.courseId}"
            binding.tvSheetTeacher.text = "教师: ${item.teacherInfo.ifEmpty { "待定" }}"
            var cleanedClass = item.className.trim()
            while (cleanedClass.startsWith("((") && cleanedClass.endsWith("))")) {
                cleanedClass = cleanedClass.removePrefix("(").removeSuffix(")")
            }
            binding.tvSheetClassName.text = if (cleanedClass.isNotEmpty() && cleanedClass != "()") "教学班: $cleanedClass" else "教学班: 默认班级"
            binding.tvSheetSchedule.text = item.scheduleTime.ifEmpty { "时间待定" }
            binding.tvSheetLocation.text = item.location.ifEmpty { "地点待定" }

            binding.btnSheetDrop.setOnClickListener {
                onDropClicked(item)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<SelectedCourseItem>() {
        override fun areItemsTheSame(oldItem: SelectedCourseItem, newItem: SelectedCourseItem): Boolean {
            return oldItem.jxbId == newItem.jxbId || (oldItem.courseId == newItem.courseId && oldItem.doJxbId == newItem.doJxbId)
        }

        override fun areContentsTheSame(oldItem: SelectedCourseItem, newItem: SelectedCourseItem): Boolean {
            return oldItem == newItem
        }
    }
}
