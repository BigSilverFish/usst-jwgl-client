package cn.edu.usst.jwgl.ui.tronclass

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
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
            val author = discussion.getAuthorName()
            binding.tvAuthorName.text = author
            binding.tvAuthorAvatar.text = author.firstOrNull()?.toString() ?: "同"

            val timeStr = discussion.getFormattedTime()
            binding.tvPostTime.text = timeStr.ifBlank { "最近" }

            binding.tvDiscussionTitle.text = discussion.getCleanTitle()

            // 讨论正文纯文本摘要
            val rawContent = discussion.content
            if (!rawContent.isNullOrBlank()) {
                val cleanContent = TronClassHtmlHelper.htmlToPlainText(rawContent).trim()
                if (cleanContent.isNotBlank()) {
                    binding.tvDiscussionContent.visibility = View.VISIBLE
                    binding.tvDiscussionContent.text = cleanContent
                } else {
                    binding.tvDiscussionContent.visibility = View.GONE
                }
            } else {
                binding.tvDiscussionContent.visibility = View.GONE
            }

            binding.tvRepliesCount.text = "${discussion.repliesCount} 条回复"
            binding.tvViewsCount.text = "${discussion.viewsCount} 次浏览"

            binding.root.setOnClickListener {
                onDiscussionClick(discussion)
            }
        }
    }
}
