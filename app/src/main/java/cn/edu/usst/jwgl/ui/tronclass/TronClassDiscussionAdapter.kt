package cn.edu.usst.jwgl.ui.tronclass

import android.content.res.ColorStateList
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
            val topic = discussion.topicDetail
            val isHeader = discussion.isCategoryHeader || (topic == null && act != null)

            // 重置样式防止 RecyclerView 复用导致颜色残留
            binding.tvAuthorAvatar.setBackgroundResource(R.drawable.bg_badge_rounded)
            binding.tvAuthorAvatar.backgroundTintList = null
            binding.tvAuthorAvatar.setTextColor(ContextCompat.getColor(context, R.color.primary))
            binding.tvDiscussionStatusBadge.setBackgroundResource(R.drawable.bg_badge_rounded)
            binding.tvDiscussionStatusBadge.backgroundTintList = null

            if (isHeader && act != null && act.isInteractionActivity()) {
                // 1A. 课堂互动活动卡片（问卷 / 投票 / 反馈 / 头脑风暴 / 教室互动等）
                binding.tvAuthorAvatar.text = "互"
                binding.tvAuthorAvatar.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF3E0"))
                binding.tvAuthorAvatar.setTextColor(Color.parseColor("#E65100"))

                val interactionLabel = act.getInteractionTypeLabel()
                binding.tvAuthorName.text = interactionLabel
                binding.tvPostTime.text = discussion.getFormattedTime().ifBlank { "课堂互动" }
                binding.tvDiscussionTitle.text = discussion.getCleanTitle()

                binding.tvDiscussionStatusBadge.visibility = View.VISIBLE
                when (act.completeness) {
                    "full", "complete" -> {
                        binding.tvDiscussionStatusBadge.text = "已完成"
                        binding.tvDiscussionStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                        binding.tvDiscussionStatusBadge.setTextColor(Color.parseColor("#2E7D32"))
                    }
                    "part" -> {
                        binding.tvDiscussionStatusBadge.text = "参与中"
                        binding.tvDiscussionStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF8E1"))
                        binding.tvDiscussionStatusBadge.setTextColor(Color.parseColor("#F57F17"))
                    }
                    else -> {
                        binding.tvDiscussionStatusBadge.text = "互动"
                        binding.tvDiscussionStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF3E0"))
                        binding.tvDiscussionStatusBadge.setTextColor(Color.parseColor("#E65100"))
                    }
                }

                val rawContent = discussion.content ?: act.data?.description
                val cleanContent = if (!rawContent.isNullOrBlank()) TronClassHtmlHelper.htmlToPlainText(rawContent).trim() else ""
                binding.tvDiscussionContent.visibility = View.VISIBLE
                binding.tvDiscussionContent.text = cleanContent.ifBlank {
                    "该活动为$interactionLabel，点击可查看说明或进入互动页面参与。"
                }

                binding.tvRepliesCount.text = interactionLabel
                binding.tvStatsDot.visibility = View.VISIBLE
                binding.tvViewsCount.visibility = View.VISIBLE
                binding.tvViewsCount.text = "点击进入互动"
            } else if (isHeader) {
                // 1B. 专题议题导读说明卡片
                binding.tvAuthorAvatar.text = "议"

                binding.tvAuthorName.text = "课堂专题讨论"
                binding.tvPostTime.text = discussion.getFormattedTime().ifBlank { "议题导读" }
                binding.tvDiscussionTitle.text = discussion.getCleanTitle()

                // 讨论状态徽章 (针对 forum 活动的学习完成状态)
                binding.tvDiscussionStatusBadge.visibility = View.VISIBLE
                if (act != null) {
                    when (act.completeness) {
                        "full", "complete" -> {
                            binding.tvDiscussionStatusBadge.text = "已学完"
                            binding.tvDiscussionStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E8F5E9"))
                            binding.tvDiscussionStatusBadge.setTextColor(Color.parseColor("#2E7D32"))
                        }
                        "part" -> {
                            binding.tvDiscussionStatusBadge.text = "学习中"
                            binding.tvDiscussionStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FFF8E1"))
                            binding.tvDiscussionStatusBadge.setTextColor(Color.parseColor("#F57F17"))
                        }
                        else -> {
                            binding.tvDiscussionStatusBadge.text = "讨论活动"
                            binding.tvDiscussionStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F3E5F5"))
                            binding.tvDiscussionStatusBadge.setTextColor(Color.parseColor("#7B1FA2"))
                        }
                    }
                } else {
                    binding.tvDiscussionStatusBadge.text = "专题导读"
                    binding.tvDiscussionStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F3E5F5"))
                    binding.tvDiscussionStatusBadge.setTextColor(Color.parseColor("#7B1FA2"))
                }

                // 正文描述
                val rawContent = discussion.content ?: act?.data?.description
                if (!rawContent.isNullOrBlank()) {
                    val cleanContent = TronClassHtmlHelper.htmlToPlainText(rawContent).trim()
                    if (cleanContent.isNotBlank()) {
                        binding.tvDiscussionContent.visibility = View.VISIBLE
                        binding.tvDiscussionContent.text = cleanContent
                    } else {
                        binding.tvDiscussionContent.visibility = View.GONE
                    }
                } else {
                    binding.tvDiscussionContent.visibility = View.VISIBLE
                    binding.tvDiscussionContent.text = "本讨论包含课程专属议题，点击即可查看讨论说明与要求并参与交流。"
                }

                binding.tvRepliesCount.text = "专题导读说明"
                binding.tvStatsDot.visibility = View.VISIBLE
                binding.tvViewsCount.visibility = View.VISIBLE
                binding.tvViewsCount.text = "点击查看详情"

            } else if (topic != null) {
                // 2. 同学或本人的具体讨论帖子
                val author = discussion.getAuthorName()
                binding.tvAuthorAvatar.text = author.firstOrNull()?.toString() ?: "同"

                if (discussion.isSelf) {
                    binding.tvAuthorName.text = "$author (我)"
                    binding.tvDiscussionStatusBadge.visibility = View.VISIBLE
                    binding.tvDiscussionStatusBadge.text = "我的发言"
                    binding.tvDiscussionStatusBadge.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F3E5F5"))
                    binding.tvDiscussionStatusBadge.setTextColor(Color.parseColor("#7B1FA2"))
                } else {
                    binding.tvAuthorName.text = author
                    binding.tvDiscussionStatusBadge.visibility = View.GONE
                }

                binding.tvPostTime.text = discussion.getFormattedTime().ifBlank { "近期" }
                binding.tvDiscussionTitle.text = discussion.getCleanTitle()

                val cleanContent = discussion.content?.let { TronClassHtmlHelper.htmlToPlainText(it).trim() } ?: ""
                if (cleanContent.isNotBlank()) {
                    binding.tvDiscussionContent.visibility = View.VISIBLE
                    binding.tvDiscussionContent.text = cleanContent
                } else {
                    binding.tvDiscussionContent.visibility = View.GONE
                }

                binding.tvRepliesCount.text = "${discussion.repliesCount} 条回复"
                binding.tvStatsDot.visibility = View.VISIBLE
                binding.tvViewsCount.visibility = View.VISIBLE
                binding.tvViewsCount.text = "${topic.likeCount} 人点赞"

            } else {
                // 3. 通用备用讨论帖子
                val author = discussion.getAuthorName()
                binding.tvAuthorName.text = author
                binding.tvAuthorAvatar.text = author.firstOrNull()?.toString() ?: "同"
                binding.tvDiscussionStatusBadge.visibility = View.GONE
                binding.tvPostTime.text = discussion.getFormattedTime().ifBlank { "近期" }
                binding.tvDiscussionTitle.text = discussion.getCleanTitle()

                val cleanContent = discussion.content?.let { TronClassHtmlHelper.htmlToPlainText(it).trim() } ?: ""
                if (cleanContent.isNotBlank()) {
                    binding.tvDiscussionContent.visibility = View.VISIBLE
                    binding.tvDiscussionContent.text = cleanContent
                } else {
                    binding.tvDiscussionContent.visibility = View.GONE
                }

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
