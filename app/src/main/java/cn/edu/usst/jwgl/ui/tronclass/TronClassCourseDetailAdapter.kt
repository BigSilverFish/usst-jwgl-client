package cn.edu.usst.jwgl.ui.tronclass

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.TronClassActivityItem
import cn.edu.usst.jwgl.data.model.TronClassModule
import cn.edu.usst.jwgl.databinding.ItemTronclassActivityBinding
import cn.edu.usst.jwgl.databinding.ItemTronclassModuleHeaderBinding
import cn.edu.usst.jwgl.databinding.ItemTronclassProgressHeaderBinding

sealed class CourseDetailUiItem {
    data class ProgressHeader(val completedCount: Int, val totalCount: Int, val percent: Int) : CourseDetailUiItem()
    data class ModuleHeader(val module: TronClassModule, val count: Int) : CourseDetailUiItem()
    data class ActivityItem(val activity: TronClassActivityItem) : CourseDetailUiItem()
}

class TronClassCourseDetailAdapter(
    private var items: List<CourseDetailUiItem> = emptyList(),
    private val onActivityClick: (TronClassActivityItem) -> Unit,
    private val onDownloadClick: (TronClassActivityItem) -> Unit,
    private val onAutoBrushClick: () -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_PROGRESS = 0
        const val TYPE_MODULE = 1
        const val TYPE_ACTIVITY = 2
    }

    fun updateData(newItems: List<CourseDetailUiItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is CourseDetailUiItem.ProgressHeader -> TYPE_PROGRESS
            is CourseDetailUiItem.ModuleHeader -> TYPE_MODULE
            is CourseDetailUiItem.ActivityItem -> TYPE_ACTIVITY
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PROGRESS -> {
                val binding = ItemTronclassProgressHeaderBinding.inflate(inflater, parent, false)
                ProgressHeaderViewHolder(binding)
            }
            TYPE_MODULE -> {
                val binding = ItemTronclassModuleHeaderBinding.inflate(inflater, parent, false)
                ModuleHeaderViewHolder(binding)
            }
            else -> {
                val binding = ItemTronclassActivityBinding.inflate(inflater, parent, false)
                ActivityViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is CourseDetailUiItem.ProgressHeader -> (holder as ProgressHeaderViewHolder).bind(item)
            is CourseDetailUiItem.ModuleHeader -> (holder as ModuleHeaderViewHolder).bind(item)
            is CourseDetailUiItem.ActivityItem -> (holder as ActivityViewHolder).bind(item.activity)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class ProgressHeaderViewHolder(private val binding: ItemTronclassProgressHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(header: CourseDetailUiItem.ProgressHeader) {
            binding.progressLearning.progress = header.percent
            binding.tvProgressText.text = "已完成 ${header.percent}% (${header.completedCount} / ${header.totalCount} 项)"
            if (header.percent >= 100) {
                binding.btnAutoFastProgress.text = "已满额"
                binding.btnAutoFastProgress.isEnabled = false
            } else {
                binding.btnAutoFastProgress.text = "自动刷课"
                binding.btnAutoFastProgress.isEnabled = true
            }
            binding.btnAutoFastProgress.setOnClickListener {
                onAutoBrushClick()
            }
        }
    }

    inner class ModuleHeaderViewHolder(private val binding: ItemTronclassModuleHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CourseDetailUiItem.ModuleHeader) {
            binding.tvModuleName.text = item.module.getCleanName()
            binding.tvItemCount.text = "${item.count} 节"
        }
    }

    inner class ActivityViewHolder(private val binding: ItemTronclassActivityBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(act: TronClassActivityItem) {
            val context = binding.root.context
            val upload = act.getFirstUpload()

            binding.tvActivityTitle.text = act.getCleanTitle()

            // 1. 学习进度状态徽章 (Completion Status)
            when (act.completeness) {
                "full", "complete" -> {
                    binding.tvCompletionBadge.visibility = View.VISIBLE
                    binding.tvCompletionBadge.text = "已学完"
                    binding.tvCompletionBadge.setBackgroundColor(Color.parseColor("#E8F5E9"))
                    binding.tvCompletionBadge.setTextColor(Color.parseColor("#2E7D32"))
                }
                "part" -> {
                    binding.tvCompletionBadge.visibility = View.VISIBLE
                    binding.tvCompletionBadge.text = "学习中"
                    binding.tvCompletionBadge.setBackgroundColor(Color.parseColor("#FFF8E1"))
                    binding.tvCompletionBadge.setTextColor(Color.parseColor("#F57F17"))
                }
                else -> {
                    binding.tvCompletionBadge.visibility = View.GONE
                }
            }

            // 2. 类型分支
            when (act.type) {
                "material" -> {
                    val ext = upload?.getExtension() ?: "文档"
                    binding.tvTypeBadge.text = ext.uppercase()
                    binding.tvActivityMeta.text = upload?.getFormattedSize() ?: "课件资料"

                    if (ext == "pdf") {
                        binding.ivActivityIcon.setImageResource(R.drawable.ic_file_pdf)
                        binding.ivActivityIcon.setColorFilter(ContextCompat.getColor(context, R.color.error))
                    } else {
                        binding.ivActivityIcon.setImageResource(R.drawable.ic_file_doc)
                        binding.ivActivityIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))
                    }

                    binding.btnAction.visibility = View.VISIBLE
                    binding.btnAction.text = "下载"
                    binding.btnAction.setIconResource(R.drawable.ic_download)
                    binding.btnAction.setOnClickListener {
                        onDownloadClick(act)
                    }
                }
                "online_video" -> {
                    binding.tvTypeBadge.text = "视频"
                    binding.tvActivityMeta.text = upload?.getFormattedSize() ?: "在线视频"
                    binding.ivActivityIcon.setImageResource(R.drawable.ic_live_video)
                    binding.ivActivityIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))

                    binding.btnAction.visibility = View.VISIBLE
                    binding.btnAction.text = "看课"
                    binding.btnAction.setIconResource(R.drawable.ic_play_arrow)
                    binding.btnAction.setOnClickListener {
                        onActivityClick(act)
                    }
                }
                "homework" -> {
                    binding.tvTypeBadge.text = "作业"
                    binding.tvActivityMeta.text = if (!act.endTime.isNullOrBlank()) "点击提交" else "课堂作业"
                    binding.ivActivityIcon.setImageResource(R.drawable.ic_homework)
                    binding.ivActivityIcon.setColorFilter(ContextCompat.getColor(context, R.color.tertiary))

                    binding.btnAction.visibility = View.VISIBLE
                    binding.btnAction.text = "详情"
                    binding.btnAction.setIconResource(R.drawable.ic_chevron_right)
                    binding.btnAction.setOnClickListener {
                        onActivityClick(act)
                    }
                }
                "exam", "quiz" -> {
                    binding.tvTypeBadge.text = "测试"
                    binding.tvActivityMeta.text = "随堂测验"
                    binding.ivActivityIcon.setImageResource(R.drawable.ic_exam)
                    binding.ivActivityIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))

                    binding.btnAction.visibility = View.VISIBLE
                    binding.btnAction.text = "做题"
                    binding.btnAction.setIconResource(R.drawable.ic_chevron_right)
                    binding.btnAction.setOnClickListener {
                        onActivityClick(act)
                    }
                }
                "forum" -> {
                    binding.tvTypeBadge.text = "讨论"
                    binding.tvActivityMeta.text = "课堂专题讨论"
                    binding.ivActivityIcon.setImageResource(R.drawable.ic_forum)
                    binding.ivActivityIcon.setColorFilter(Color.parseColor("#7B1FA2"))

                    binding.btnAction.visibility = View.VISIBLE
                    binding.btnAction.text = "查看"
                    binding.btnAction.setIconResource(R.drawable.ic_chevron_right)
                    binding.btnAction.setOnClickListener {
                        onActivityClick(act)
                    }
                }
                else -> {
                    binding.tvTypeBadge.text = act.type
                    binding.tvActivityMeta.text = upload?.getFormattedSize() ?: ""
                    binding.ivActivityIcon.setImageResource(R.drawable.ic_file_doc)
                    binding.ivActivityIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))

                    binding.btnAction.visibility = if (upload != null) View.VISIBLE else View.GONE
                    binding.btnAction.setOnClickListener {
                        onDownloadClick(act)
                    }
                }
            }

            binding.root.setOnClickListener {
                onActivityClick(act)
            }
        }
    }
}
