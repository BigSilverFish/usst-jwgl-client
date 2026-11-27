package cn.edu.usst.jwgl.ui.tronclass

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.TronClassActivityCategory
import cn.edu.usst.jwgl.data.model.TronClassActivityItem
import cn.edu.usst.jwgl.data.model.TronClassModule
import cn.edu.usst.jwgl.data.model.TronClassUpload
import cn.edu.usst.jwgl.databinding.ItemTronclassActivityBinding
import cn.edu.usst.jwgl.databinding.ItemTronclassModuleHeaderBinding
import cn.edu.usst.jwgl.databinding.ItemTronclassProgressHeaderBinding
import cn.edu.usst.jwgl.util.DeveloperModeManager

sealed class CourseDetailUiItem {
    data class ProgressHeader(
        val completedCount: Int,
        val totalCount: Int,
        val percent: Int,
        val showAutoBrush: Boolean = true
    ) : CourseDetailUiItem()
    data class ModuleHeader(val module: TronClassModule, val count: Int) : CourseDetailUiItem()
    data class ActivityItem(val activity: TronClassActivityItem) : CourseDetailUiItem()
}

class TronClassCourseDetailAdapter(
    private var items: List<CourseDetailUiItem> = emptyList(),
    private val onActivityClick: (TronClassActivityItem) -> Unit,
    private val onDownloadClick: (TronClassActivityItem) -> Unit,
    private val onAutoBrushClick: () -> Unit = {},
    private val getCachedUploadCount: (TronClassActivityItem) -> Int = { 0 }
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
            val isDev = DeveloperModeManager.isDeveloperMode(binding.root.context)
            binding.layoutAutoBrushRow.visibility = if (isDev && header.showAutoBrush) View.VISIBLE else View.GONE

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
            val allUploads = act.getAllUploads()
            val upload = allUploads.firstOrNull()
            val hasMultiple = allUploads.size > 1

            binding.tvActivityTitle.text = act.getCleanTitle()

            // 重置类型徽章样式，防止 RecyclerView 复用导致色块残留
            binding.tvTypeBadge.setBackgroundResource(R.drawable.bg_badge_rounded)
            binding.tvTypeBadge.backgroundTintList = null
            binding.tvTypeBadge.setTextColor(ContextCompat.getColor(context, R.color.primary))

            // 1. 学习进度状态徽章 (Completion Status)
            binding.tvCompletionBadge.setBackgroundResource(R.drawable.bg_badge_rounded)
            binding.tvCompletionBadge.backgroundTintList = null
            when (act.completeness) {
                "full", "complete" -> {
                    binding.tvCompletionBadge.visibility = View.VISIBLE
                    binding.tvCompletionBadge.text = "已学完"
                    binding.tvCompletionBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                    binding.tvCompletionBadge.setTextColor(Color.parseColor("#2E7D32"))
                }
                "part" -> {
                    binding.tvCompletionBadge.visibility = View.VISIBLE
                    binding.tvCompletionBadge.text = "学习中"
                    binding.tvCompletionBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF8E1"))
                    binding.tvCompletionBadge.setTextColor(Color.parseColor("#F57F17"))
                }
                else -> {
                    binding.tvCompletionBadge.visibility = View.GONE
                }
            }

            // 2. 类型分支
            when (act.getCategory()) {
                TronClassActivityCategory.MATERIAL -> {
                    if (hasMultiple) {
                        binding.tvTypeBadge.text = "${allUploads.size}个文件"
                        binding.ivActivityIcon.setImageResource(R.drawable.ic_layers)
                        binding.ivActivityIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))

                        binding.btnAction.visibility = View.VISIBLE
                        bindMultiUploadActionButton(act, allUploads)
                    } else {
                        val ext = upload?.getExtension() ?: "文档"
                        binding.tvTypeBadge.text = ext.uppercase()

                        if (ext == "pdf") {
                            binding.ivActivityIcon.setImageResource(R.drawable.ic_file_pdf)
                            binding.ivActivityIcon.setColorFilter(ContextCompat.getColor(context, R.color.error))
                        } else {
                            binding.ivActivityIcon.setImageResource(R.drawable.ic_file_doc)
                            binding.ivActivityIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))
                        }

                        binding.btnAction.visibility = View.VISIBLE
                        bindDownloadActionButton(act, upload, upload?.getFormattedSize() ?: "课件资料")
                    }
                }
                TronClassActivityCategory.ONLINE_VIDEO -> {
                    binding.ivActivityIcon.setImageResource(R.drawable.ic_live_video)
                    binding.ivActivityIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))
                    binding.btnAction.visibility = View.VISIBLE

                    val normalized = act.getNormalizedType()
                    val baseLabel = when (normalized) {
                        "audio" -> "音频"
                        "slide", "micro_course" -> "微课"
                        else -> "视频"
                    }
                    if (hasMultiple) {
                        binding.tvTypeBadge.text = "$baseLabel×${allUploads.size}"
                        val totalSizeText = if (act.getTotalUploadSize() > 0L) " · ${act.getFormattedTotalUploadSize()}" else ""
                        binding.tvActivityMeta.text = "共 ${allUploads.size} 个视频/文件$totalSizeText"
                        binding.btnAction.text = "查看 (${allUploads.size})"
                        binding.btnAction.setIconResource(R.drawable.ic_chevron_right)
                    } else {
                        binding.tvTypeBadge.text = baseLabel
                        binding.tvActivityMeta.text = upload?.getFormattedSize() ?: "在线视频"
                        binding.btnAction.text = "看课"
                        binding.btnAction.setIconResource(R.drawable.ic_play_arrow)
                    }
                    binding.btnAction.setOnClickListener {
                        onActivityClick(act)
                    }
                }
                TronClassActivityCategory.LIVE -> {
                    binding.tvTypeBadge.text = "直播"
                    binding.tvTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FCE4EC"))
                    binding.tvTypeBadge.setTextColor(Color.parseColor("#C2185B"))
                    binding.ivActivityIcon.setImageResource(R.drawable.ic_live_video)
                    binding.ivActivityIcon.setColorFilter(Color.parseColor("#C2185B"))

                    val schedule = act.getFormattedScheduleTime()
                    binding.tvActivityMeta.text = when {
                        hasMultiple -> "共 ${allUploads.size} 个回放/文件"
                        schedule.isNotBlank() -> "$schedule · 课堂直播"
                        else -> "课程直播 / 回放"
                    }
                    binding.btnAction.visibility = View.VISIBLE
                    binding.btnAction.text = if (hasMultiple) "查看 (${allUploads.size})" else "观看"
                    binding.btnAction.setIconResource(if (hasMultiple) R.drawable.ic_chevron_right else R.drawable.ic_play_arrow)
                    binding.btnAction.setOnClickListener {
                        onActivityClick(act)
                    }
                }
                TronClassActivityCategory.RECORDED_LESSON -> {
                    binding.tvTypeBadge.text = "录播教材"
                    binding.tvTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E0F2FE"))
                    binding.tvTypeBadge.setTextColor(Color.parseColor("#0369A1"))
                    binding.ivActivityIcon.setImageResource(R.drawable.ic_live_video)
                    binding.ivActivityIcon.setColorFilter(Color.parseColor("#0284C7"))
                    binding.btnAction.visibility = View.VISIBLE

                    if (hasMultiple) {
                        val totalSizeText = if (act.getTotalUploadSize() > 0L) " · ${act.getFormattedTotalUploadSize()}" else ""
                        binding.tvActivityMeta.text = "共 ${allUploads.size} 个录播/文件$totalSizeText"
                        binding.btnAction.text = "查看 (${allUploads.size})"
                        binding.btnAction.setIconResource(R.drawable.ic_chevron_right)
                    } else {
                        val schedule = act.getFormattedScheduleTime()
                        binding.tvActivityMeta.text = when {
                            upload != null && upload.size > 0L -> upload.getFormattedSize()
                            schedule.isNotBlank() -> "$schedule · 录播回放"
                            else -> "课堂实录回放"
                        }
                        binding.btnAction.text = "播放"
                        binding.btnAction.setIconResource(R.drawable.ic_play_arrow)
                    }
                    binding.btnAction.setOnClickListener {
                        onActivityClick(act)
                    }
                }
                TronClassActivityCategory.INTERACTION -> {
                    binding.tvTypeBadge.text = "互动"
                    binding.tvTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF3E0"))
                    binding.tvTypeBadge.setTextColor(Color.parseColor("#E65100"))
                    binding.ivActivityIcon.setImageResource(R.drawable.ic_forum)
                    binding.ivActivityIcon.setColorFilter(Color.parseColor("#E65100"))

                    val schedule = act.getFormattedScheduleTime()
                    val label = act.getInteractionTypeLabel()
                    binding.tvActivityMeta.text = if (schedule.isNotBlank()) "$label · $schedule" else label
                    binding.btnAction.visibility = View.VISIBLE
                    binding.btnAction.text = "参与"
                    binding.btnAction.setIconResource(R.drawable.ic_chevron_right)
                    binding.btnAction.setOnClickListener {
                        onActivityClick(act)
                    }
                }
                TronClassActivityCategory.HOMEWORK -> {
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
                TronClassActivityCategory.EXAM -> {
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
                TronClassActivityCategory.FORUM -> {
                    binding.tvTypeBadge.text = "讨论"
                    binding.tvTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F3E5F5"))
                    binding.tvTypeBadge.setTextColor(Color.parseColor("#7B1FA2"))
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
                TronClassActivityCategory.CHAOXING_CHAPTER -> {
                    binding.tvTypeBadge.text = "学习通"
                    binding.tvTypeBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFEDD5"))
                    binding.tvTypeBadge.setTextColor(Color.parseColor("#C2410C"))
                    binding.tvActivityMeta.text = if (act.completeness == "complete" || act.completeness == "full") "已学完" else "查看课件/视频"
                    binding.ivActivityIcon.setImageResource(R.drawable.ic_file_doc)
                    binding.ivActivityIcon.setColorFilter(Color.parseColor("#EA580C"))

                    binding.btnAction.visibility = View.VISIBLE
                    binding.btnAction.text = "课件"
                    binding.btnAction.setIconResource(R.drawable.ic_chevron_right)
                    binding.btnAction.setOnClickListener {
                        onActivityClick(act)
                    }
                }
                TronClassActivityCategory.WEB_LINK -> {
                    binding.tvTypeBadge.text = "链接"
                    binding.tvActivityMeta.text = "外部学习链接"
                    binding.ivActivityIcon.setImageResource(R.drawable.ic_globe)
                    binding.ivActivityIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))

                    binding.btnAction.visibility = View.VISIBLE
                    binding.btnAction.text = "打开"
                    binding.btnAction.setIconResource(R.drawable.ic_chevron_right)
                    binding.btnAction.setOnClickListener {
                        onActivityClick(act)
                    }
                }
                TronClassActivityCategory.PAGE -> {
                    binding.tvTypeBadge.text = "图文"
                    binding.tvActivityMeta.text = "图文教材"
                    binding.ivActivityIcon.setImageResource(R.drawable.ic_file_doc)
                    binding.ivActivityIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))

                    binding.btnAction.visibility = View.VISIBLE
                    binding.btnAction.text = "查看"
                    binding.btnAction.setIconResource(R.drawable.ic_chevron_right)
                    binding.btnAction.setOnClickListener {
                        onActivityClick(act)
                    }
                }
                TronClassActivityCategory.OTHER -> {
                    if (hasMultiple) {
                        binding.tvTypeBadge.text = "${allUploads.size}个文件"
                        binding.ivActivityIcon.setImageResource(R.drawable.ic_layers)
                        binding.ivActivityIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))

                        binding.btnAction.visibility = View.VISIBLE
                        bindMultiUploadActionButton(act, allUploads)
                    } else {
                        binding.tvTypeBadge.text = act.type?.takeIf { it.isNotBlank() } ?: "活动"
                        binding.ivActivityIcon.setImageResource(R.drawable.ic_file_doc)
                        binding.ivActivityIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))

                        binding.btnAction.visibility = if (upload != null) View.VISIBLE else View.GONE
                        bindDownloadActionButton(act, upload, upload?.getFormattedSize() ?: "")
                    }
                }
            }

            binding.root.setOnClickListener {
                onActivityClick(act)
            }
        }

        private fun bindMultiUploadActionButton(
            act: TronClassActivityItem,
            uploads: List<TronClassUpload>
        ) {
            val total = uploads.size
            val cachedCount = getCachedUploadCount(act).coerceIn(0, total)
            binding.tvActivityMeta.text = when {
                cachedCount == total -> "共 $total 个文件 · 已全部缓存"
                cachedCount > 0 -> "共 $total 个文件 · 已缓存 $cachedCount/$total"
                act.getTotalUploadSize() > 0L -> "共 $total 个文件 · ${act.getFormattedTotalUploadSize()}"
                else -> "共 $total 个文件"
            }
            binding.btnAction.text = "查看 ($total)"
            binding.btnAction.setIconResource(R.drawable.ic_chevron_right)
            binding.btnAction.setOnClickListener {
                onDownloadClick(act)
            }
        }

        private fun bindDownloadActionButton(
            act: TronClassActivityItem,
            upload: TronClassUpload?,
            defaultMeta: String
        ) {
            val cached = upload != null && getCachedUploadCount(act) > 0
            binding.tvActivityMeta.text = when {
                cached && upload.size > 0 -> "${upload.getFormattedSize()} · 已缓存"
                cached -> "已缓存"
                else -> defaultMeta
            }
            if (cached) {
                binding.btnAction.text = "打开"
                binding.btnAction.setIconResource(R.drawable.ic_file_doc)
            } else {
                binding.btnAction.text = "下载"
                binding.btnAction.setIconResource(R.drawable.ic_download)
            }
            binding.btnAction.setOnClickListener {
                onDownloadClick(act)
            }
        }
    }
}
