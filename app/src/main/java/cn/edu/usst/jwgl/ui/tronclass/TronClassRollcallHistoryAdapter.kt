package cn.edu.usst.jwgl.ui.tronclass

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.TronClassStudentRollcall
import cn.edu.usst.jwgl.databinding.ItemTronclassRollcallHistoryBinding

class TronClassRollcallHistoryAdapter :
    ListAdapter<TronClassStudentRollcall, TronClassRollcallHistoryAdapter.ViewHolder>(DiffCallback) {

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

        fun bind(item: TronClassStudentRollcall) {
            val context = binding.root.context

            binding.tvRollcallTitle.text = item.getDisplayTitle()
            binding.tvRollcallTypeBadge.text = item.getTypeDisplayName()
            binding.tvRollcallTime.text = item.getFormattedTime()

            // 状态 Badge 颜色与文本配置
            val statusName = item.getStatusDisplayName()
            binding.tvRollcallStatusBadge.text = statusName

            when {
                item.isAttended() -> {
                    binding.tvRollcallStatusBadge.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                    binding.tvRollcallStatusBadge.setTextColor(Color.parseColor("#2E7D32"))
                    binding.layoutIconBg.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                    binding.ivRollcallTypeIcon.imageTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#2E7D32"))
                }
                item.isLateOrEarly() -> {
                    binding.tvRollcallStatusBadge.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#FFF3E0"))
                    binding.tvRollcallStatusBadge.setTextColor(Color.parseColor("#E65100"))
                    binding.layoutIconBg.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#FFF3E0"))
                    binding.ivRollcallTypeIcon.imageTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#E65100"))
                }
                item.isAbsent() -> {
                    binding.tvRollcallStatusBadge.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#FFEBEE"))
                    binding.tvRollcallStatusBadge.setTextColor(Color.parseColor("#C62828"))
                    binding.layoutIconBg.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#FFEBEE"))
                    binding.ivRollcallTypeIcon.imageTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#C62828"))
                }
                item.isLeave() -> {
                    binding.tvRollcallStatusBadge.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#E3F2FD"))
                    binding.tvRollcallStatusBadge.setTextColor(Color.parseColor("#1565C0"))
                    binding.layoutIconBg.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#E3F2FD"))
                    binding.ivRollcallTypeIcon.imageTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#1565C0"))
                }
                else -> {
                    val primaryColor = ContextCompat.getColor(context, R.color.primary)
                    binding.tvRollcallStatusBadge.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#EFF6FF"))
                    binding.tvRollcallStatusBadge.setTextColor(primaryColor)
                    binding.layoutIconBg.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#EFF6FF"))
                    binding.ivRollcallTypeIcon.imageTintList =
                        android.content.res.ColorStateList.valueOf(primaryColor)
                }
            }

            // 根据类型调整图标
            if (item.getNormalizedType() == "radar") {
                binding.ivRollcallTypeIcon.setImageResource(R.drawable.ic_location)
            } else {
                binding.ivRollcallTypeIcon.setImageResource(R.drawable.ic_rollcall_history)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<TronClassStudentRollcall>() {
        override fun areItemsTheSame(
            oldItem: TronClassStudentRollcall,
            newItem: TronClassStudentRollcall
        ): Boolean = oldItem.getEffectiveId() == newItem.getEffectiveId()

        override fun areContentsTheSame(
            oldItem: TronClassStudentRollcall,
            newItem: TronClassStudentRollcall
        ): Boolean = oldItem == newItem
    }
}
