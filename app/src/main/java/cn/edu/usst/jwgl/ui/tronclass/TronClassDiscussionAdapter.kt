package cn.edu.usst.jwgl.ui.tronclass

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.TronClassDiscussion
import cn.edu.usst.jwgl.databinding.ItemTronclassDiscussionBinding

class TronClassDiscussionAdapter(
    private var discussions: List<TronClassDiscussion> = emptyList(),
    private val onDiscussionClick: (TronClassDiscussion) -> Unit
) : RecyclerView.Adapter<TronClassDiscussionAdapter.DiscussionViewHolder>() {

    fun updateData(newDiscussions: List<TronClassDiscussion>) {
        discussions = newDiscussions
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DiscussionViewHolder {
        val binding = ItemTronclassDiscussionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DiscussionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DiscussionViewHolder, position: Int) {
        holder.bind(discussions[position])
    }

    override fun getItemCount(): Int = discussions.size

    inner class DiscussionViewHolder(private val binding: ItemTronclassDiscussionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(discussion: TronClassDiscussion) {
            val context = binding.root.context
            val act = discussion.activityItem

            val author = discussion.getAuthorName()
            binding.tvAuthorName.text = if (act != null) "课堂专题讨论" else author
            binding.tvAuthorAvatar.text = if (act != null) "讨" else (author.firstOrNull()?.toString() ?: "同")

            val timeStr = discussion.getFormattedTime()
            binding.tvPostTime.text = timeStr.ifBlank { "近期" }

            binding.tvDiscussionTitle.text = discussion.getCleanTitle()

            // 讨论状态徽章 (特别是针对 forum 活动的学习完成状态)
            if (act != null) {
                binding.tvDiscussionStatusBadge.visibility = View.VISIBLE
                when (act.completeness) {
                    "full", "complete" -> {
                        binding.tvDiscussionStatusBadge.text = "已学完"
                        binding.tvDiscussionStatusBadge.setBackgroundColor(Color.parseColor("#E8F5E9"))
                        binding.tvDiscussionStatusBadge.setTextColor(Color.parseColor("#2E7D32"))
                    }
                    "part" -> {
                        binding.tvDiscussionStatusBadge.text = "学习中"
                        binding.tvDiscussionStatusBadge.setBackgroundColor(Color.parseColor("#FFF8E1"))
                        binding.tvDiscussionStatusBadge.setTextColor(Color.parseColor("#F57F17"))
                    }
                    else -> {
                        binding.tvDiscussionStatusBadge.text = "开放讨论"
                        binding.tvDiscussionStatusBadge.setBackgroundColor(Color.parseColor("#F3E5F5"))
                        binding.tvDiscussionStatusBadge.setTextColor(Color.parseColor("#7B1FA2"))
                    }
                }
            } else {
                binding.tvDiscussionStatusBadge.visibility = View.GONE
            }

            // 讨论正文纯文本摘要
            val rawContent = discussion.content ?: act?.data?.description
            if (!rawContent.isNullOrBlank()) {
                val cleanContent = TronClassHtmlHelper.htmlToPlainText(rawContent).trim()
                if (cleanContent.isNotBlank()) {
                    binding.tvDiscussionContent.visibility = View.VISIBLE
                    binding.tvDiscussionContent.text = cleanContent
                } else {
                    binding.tvDiscussionContent.visibility = View.GONE
                }
            } else if (act != null) {
                binding.tvDiscussionContent.visibility = View.VISIBLE
                binding.tvDiscussionContent.text = "本讨论包含课程专属议题，点击即可查看讨论说明并参与交流。"
            } else {
                binding.tvDiscussionContent.visibility = View.GONE
            }

            // 统计指标行
            if (act != null) {
                binding.tvRepliesCount.text = "活动讨论区"
                binding.tvStatsDot.visibility = View.VISIBLE
                binding.tvViewsCount.visibility = View.VISIBLE
                binding.tvViewsCount.text = "点击查看详情"
            } else {
                binding.tvRepliesCount.text = "${discussion.repliesCount} 条回复"
                binding.tvStatsDot.visibility = View.VISIBLE
                binding.tvViewsCount.visibility = View.VISIBLE
                binding.tvViewsCount.text = "${discussion.viewsCount} 次浏览"
            }

            binding.root.setOnClickListener {
                onDiscussionClick(discussion)
            }
        }
    }
}
