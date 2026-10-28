package cn.edu.usst.jwgl.ui.tronclass

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.TronClassDiscussionPost
import cn.edu.usst.jwgl.databinding.ItemTronclassDiscussionReplyBinding

class TronClassDiscussionReplyAdapter(
    private val onLikeClick: (TronClassDiscussionPost) -> Unit
) : ListAdapter<TronClassDiscussionPost, TronClassDiscussionReplyAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTronclassDiscussionReplyBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemTronclassDiscussionReplyBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(post: TronClassDiscussionPost) {
            val author = post.getAuthorName()
            binding.tvReplyAuthor.text = author

            // 头像首字
            val avatarChar = author.replace("(我)", "").trim().take(1).ifBlank { "同" }
            binding.tvReplyAvatar.text = avatarChar

            // 角色徽章
            if (post.isSelf) {
                binding.tvReplyRoleBadge.visibility = View.VISIBLE
                binding.tvReplyRoleBadge.text = "我"
                binding.tvReplyRoleBadge.setTextColor(ContextCompat.getColor(binding.root.context, R.color.primary))
            } else if (author.contains("教师") || author.contains("老师")) {
                binding.tvReplyRoleBadge.visibility = View.VISIBLE
                binding.tvReplyRoleBadge.text = "教师"
                binding.tvReplyRoleBadge.setTextColor(Color.parseColor("#7B1FA2"))
            } else {
                binding.tvReplyRoleBadge.visibility = View.GONE
            }

            binding.tvReplyTime.text = post.getFormattedTime()

            // 正文富文本或纯文本
            val rawContent = post.content ?: ""
            if (rawContent.contains("<") && rawContent.contains(">")) {
                binding.tvReplyContent.text = TronClassHtmlHelper.htmlToPlainText(rawContent).trim()
            } else {
                binding.tvReplyContent.text = rawContent.trim()
            }

            // 点赞数与状态
            binding.tvLikesCount.text = post.likesCount.toString()
            if (post.isLiked) {
                binding.ivLikeIcon.setColorFilter(ContextCompat.getColor(binding.root.context, R.color.primary))
                binding.tvLikesCount.setTextColor(ContextCompat.getColor(binding.root.context, R.color.primary))
            } else {
                binding.ivLikeIcon.setColorFilter(ContextCompat.getColor(binding.root.context, R.color.text_tertiary))
                binding.tvLikesCount.setTextColor(ContextCompat.getColor(binding.root.context, R.color.text_tertiary))
            }

            binding.btnLikeReply.setOnClickListener {
                post.isLiked = !post.isLiked
                if (post.isLiked) post.likesCount++ else if (post.likesCount > 0) post.likesCount--
                val pos = adapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    notifyItemChanged(pos)
                }
                onLikeClick(post)
            }
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<TronClassDiscussionPost>() {
        override fun areItemsTheSame(oldItem: TronClassDiscussionPost, newItem: TronClassDiscussionPost): Boolean {
            return if (oldItem.id > 0 && newItem.id > 0) {
                oldItem.id == newItem.id
            } else {
                oldItem.content == newItem.content && oldItem.createdAt == newItem.createdAt
            }
        }

        override fun areContentsTheSame(oldItem: TronClassDiscussionPost, newItem: TronClassDiscussionPost): Boolean {
            return oldItem == newItem
        }
    }
}
