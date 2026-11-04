package cn.edu.usst.jwgl.ui.tronclass

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.LearningPlatform
import cn.edu.usst.jwgl.data.model.UnifiedStudentRollcall
import cn.edu.usst.jwgl.databinding.ItemTronclassRollcallHistoryBinding

class TronClassRollcallHistoryAdapter :
    ListAdapter<UnifiedStudentRollcall, TronClassRollcallHistoryAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTronclassRollcallHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemTronclassRollcallHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: UnifiedStudentRollcall) {
            val context = binding.root.context

            binding.tvRollcallTitle.text = item.title
            binding.tvRollcallTypeBadge.text = item.typeDisplayName
            binding.tvRollcallTime.text = item.timeText

            // 平台来源 Badge (畅课 / 学习通)
            binding.tvRollcallPlatformBadge.visibility = View.VISIBLE
            if (item.platform == LearningPlatform.CHAOXING) {
                binding.tvRollcallPlatformBadge.text = "学习通"
                binding.tvRollcallPlatformBadge.backgroundTintList =
                    ColorStateList.valueOf(Color.parseColor("#FFF7ED"))
                binding.tvRollcallPlatformBadge.setTextColor(Color.parseColor("#EA580C"))
            } else {
                binding.tvRollcallPlatformBadge.text = "畅课"
                binding.tvRollcallPlatformBadge.backgroundTintList =
                    ColorStateList.valueOf(Color.parseColor("#FDF2F2"))
                binding.tvRollcallPlatformBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))
            }

            // 状态 Badge 颜色与文本配置
            binding.tvRollcallStatusBadge.text = item.statusDisplayName

            when {
                item.isAttended() -> {
                    binding.tvRollcallStatusBadge.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                    binding.tvRollcallStatusBadge.setTextColor(Color.parseColor("#2E7D32"))
                    binding.layoutIconBg.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                    binding.ivRollcallTypeIcon.imageTintList =
                        ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                }
                item.isLateOrEarly() -> {
                    binding.tvRollcallStatusBadge.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#FFF3E0"))
                    binding.tvRollcallStatusBadge.setTextColor(Color.parseColor("#E65100"))
                    binding.layoutIconBg.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#FFF3E0"))
                    binding.ivRollcallTypeIcon.imageTintList =
                        ColorStateList.valueOf(Color.parseColor("#E65100"))
                }
                item.isAbsent() -> {
                    binding.tvRollcallStatusBadge.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#FFEBEE"))
                    binding.tvRollcallStatusBadge.setTextColor(Color.parseColor("#C62828"))
                    binding.layoutIconBg.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#FFEBEE"))
                    binding.ivRollcallTypeIcon.imageTintList =
                        ColorStateList.valueOf(Color.parseColor("#C62828"))
                }
                item.isLeave() -> {
                    binding.tvRollcallStatusBadge.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#E3F2FD"))
                    binding.tvRollcallStatusBadge.setTextColor(Color.parseColor("#1565C0"))
                    binding.layoutIconBg.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#E3F2FD"))
                    binding.ivRollcallTypeIcon.imageTintList =
                        ColorStateList.valueOf(Color.parseColor("#1565C0"))
                }
                item.isInProgress() -> {
                    binding.tvRollcallStatusBadge.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#F3E8FF"))
                    binding.tvRollcallStatusBadge.setTextColor(Color.parseColor("#7C3AED"))
                    binding.layoutIconBg.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#F3E8FF"))
                    binding.ivRollcallTypeIcon.imageTintList =
                        ColorStateList.valueOf(Color.parseColor("#7C3AED"))
                }
                else -> {
                    val primaryColor = ContextCompat.getColor(context, R.color.primary)
                    binding.tvRollcallStatusBadge.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#EFF6FF"))
                    binding.tvRollcallStatusBadge.setTextColor(primaryColor)
                    binding.layoutIconBg.backgroundTintList =
                        ColorStateList.valueOf(Color.parseColor("#EFF6FF"))
                    binding.ivRollcallTypeIcon.imageTintList =
                        ColorStateList.valueOf(primaryColor)
                }
            }

            // 根据类型调整图标 (位置/雷达、手势、拍照、通用)
            val type = item.typeDisplayName
            when {
                type.contains("雷达") || type.contains("位置") -> {
                    binding.ivRollcallTypeIcon.setImageResource(R.drawable.ic_location)
                }
                type.contains("手势") -> {
                    binding.ivRollcallTypeIcon.setImageResource(R.drawable.ic_gesture)
                }
                type.contains("拍照") || type.contains("照片") -> {
                    binding.ivRollcallTypeIcon.setImageResource(R.drawable.ic_camera)
                }
                else -> {
                    binding.ivRollcallTypeIcon.setImageResource(R.drawable.ic_rollcall_history)
                }
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<UnifiedStudentRollcall>() {
        override fun areItemsTheSame(
            oldItem: UnifiedStudentRollcall,
            newItem: UnifiedStudentRollcall
        ): Boolean = oldItem.unifiedId == newItem.unifiedId

        override fun areContentsTheSame(
            oldItem: UnifiedStudentRollcall,
            newItem: UnifiedStudentRollcall
        ): Boolean = oldItem == newItem
    }
}
