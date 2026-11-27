package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.graphics.Color
import android.view.inputmethod.InputMethodManager
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import kotlinx.coroutines.withContext
import android.content.res.ColorStateList
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.model.TronClassActivityItem
import cn.edu.usst.jwgl.data.model.TronClassDiscussion
import cn.edu.usst.jwgl.data.model.TronClassTopicCategory
import cn.edu.usst.jwgl.data.model.TronClassTopicReply
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.DialogTronclassCreateTopicBinding
import cn.edu.usst.jwgl.databinding.DialogTronclassTopicDetailBinding
import cn.edu.usst.jwgl.databinding.ItemTronclassDiscussionReplyBinding
import cn.edu.usst.jwgl.ui.JwglWebActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 畅课课程讨论区详情、回复点赞与发新帖弹窗辅助器
 */
internal class CourseDiscussionDialogHelper(
    private val fragment: TronClassCourseDetailFragment,
    private val client: TronClassClient,
    private val courseIdProvider: () -> Long,
    private val courseNameProvider: () -> String,
    private val allActivitiesProvider: () -> List<TronClassActivityItem>,
    private val onRefreshCourseData: () -> Unit
) {
    private val courseId get() = courseIdProvider()
    private val courseName get() = courseNameProvider()
    private val context: Context? get() = fragment.context
    private val allActivities get() = allActivitiesProvider()
    private val cachedActivities get() = fragment.cachedActivities
    private val discussionAdapter get() = fragment.discussionAdapter
    private val cachedDiscussions get() = fragment.cachedDiscussions

    fun showDiscussionDetailDialog(discussion: TronClassDiscussion) {
        val ctx = context ?: return
        val act = discussion.activityItem
        val author = discussion.getAuthorName()
        val timeStr = discussion.getFormattedTime()
        val title = discussion.getCleanTitle()
        val rawContent = discussion.content ?: act?.data?.description
        val contentText = if (!rawContent.isNullOrBlank()) {
            TronClassHtmlHelper.htmlToPlainText(rawContent).trim()
        } else {
            "（发起方未附带详细说明文字）"
        }

        val isCompleted = act?.completeness == "full" || act?.completeness == "complete"
        val statusLine = if (act != null) {
            if (isCompleted) "【学习状态】已学完（进度已达成）\n" else "【学习状态】开放讨论中\n"
        } else ""

        val statsLine = if (act != null) {
            "类型: 课堂专题讨论活动\n"
        } else {
            "互动数据: ${discussion.repliesCount} 条回复 · ${discussion.viewsCount} 次浏览\n"
        }

        val msg = "${statusLine}发起方: $author\n" +
                "时间: ${if (timeStr.isNotBlank()) timeStr else "近期"}\n" +
                statsLine + "\n" +
                "【讨论要求与正文】\n$contentText"

        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("我知道了", null)
            .setNegativeButton("参与发帖") { _, _ ->
                showCreateTopicDialog(discussion.categoryId, act?.id)
            }

        if (act != null && !isCompleted) {
            builder.setNeutralButton("⚡ 标记已读并完成") { _, _ ->
                fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    client.logActivityRead(act.id)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "已同步完成该课堂讨论进度！", Toast.LENGTH_SHORT).show()
                        onRefreshCourseData()
                    }
                }
            }
        }

        builder.show()
    }

    fun showInteractionDetailDialog(
        act: TronClassActivityItem,
        onOpenUploads: ((TronClassActivityItem) -> Unit)? = null
    ) {
        val ctx = context ?: return
        val label = act.getInteractionTypeLabel()
        val schedule = act.getFormattedScheduleTime()
        val isCompleted = act.completeness == "full" || act.completeness == "complete"
        val statusText = when (act.completeness) {
            "full", "complete" -> "已完成"
            "part" -> "参与中"
            else -> "进行中"
        }
        val rawDesc = act.data?.description
        val contentText = if (!rawDesc.isNullOrBlank()) {
            TronClassHtmlHelper.htmlToPlainText(rawDesc).trim().ifBlank { "（无详细文字说明）" }
        } else {
            "（无详细文字说明，可直接进入官方互动页面参与）"
        }

        val sb = StringBuilder()
        sb.append("类型: ").append(label).append('\n')
        sb.append("状态: ").append(statusText).append('\n')
        if (schedule.isNotBlank()) {
            sb.append("时间: ").append(schedule).append('\n')
        }
        sb.append('\n').append("【活动说明】\n").append(contentText)

        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(act.getCleanTitle())
            .setMessage(sb.toString())
            .setPositiveButton("进入互动") { _, _ ->
                val url = "https://1906.usst.edu.cn/course/$courseId/learning-activity#/${act.id}"
                JwglWebActivity.start(ctx, url, act.getCleanTitle())
            }

        val uploads = act.getAllUploads()
        if (uploads.isNotEmpty() && onOpenUploads != null) {
            builder.setNegativeButton("查看附件 (${uploads.size})") { _, _ ->
                onOpenUploads(act)
            }
        } else {
            builder.setNegativeButton("关闭", null)
        }

        if (!isCompleted) {
            builder.setNeutralButton("标记已读") { _, _ ->
                fragment.viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    client.logActivityRead(act.id)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "已同步完成该课堂互动进度！", Toast.LENGTH_SHORT).show()
                        onRefreshCourseData()
                    }
                }
            }
        }

        builder.show()
    }

    /**
     * 查看同学发言与回帖详情 BottomSheet
     */
    fun showTopicDetailBottomSheet(discussion: TronClassDiscussion) {
        val ctx = context ?: return
        val topic = discussion.topicDetail ?: return

        val dialog = BottomSheetDialog(ctx)
        val dialogBinding = DialogTronclassTopicDetailBinding.inflate(fragment.layoutInflater)
        dialog.setContentView(dialogBinding.root)

        val authorName = discussion.getAuthorName()
        dialogBinding.tvDetailAuthorAvatar.text = authorName.firstOrNull()?.toString() ?: "同"
        dialogBinding.tvDetailAuthorName.text = if (discussion.isSelf) "$authorName (我)" else authorName
        dialogBinding.tvDetailAuthorBadge.visibility = if (discussion.isSelf) View.VISIBLE else View.GONE
        dialogBinding.tvDetailPostTime.text = discussion.getFormattedTime().ifBlank { "近期" }

        dialogBinding.btnDetailClose.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.tvDetailTopicTitle.text = discussion.getCleanTitle()
        val plainContent = topic.getCleanContent()
        dialogBinding.tvDetailTopicContent.text = plainContent.ifBlank { "（无正文描述）" }

        fun updateLikeButtonUi() {
            if (topic.currentUserLiked) {
                dialogBinding.btnDetailLike.text = "已赞 (${topic.likeCount})"
                dialogBinding.btnDetailLike.strokeColor = ColorStateList.valueOf(Color.parseColor("#E91E63"))
                dialogBinding.btnDetailLike.setTextColor(Color.parseColor("#E91E63"))
            } else {
                dialogBinding.btnDetailLike.text = "点赞 (${topic.likeCount})"
                dialogBinding.btnDetailLike.strokeColor = ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.primary))
                dialogBinding.btnDetailLike.setTextColor(ContextCompat.getColor(ctx, R.color.primary))
            }
        }
        updateLikeButtonUi()

        dialogBinding.btnDetailLike.setOnClickListener {
            fragment.viewLifecycleOwner.lifecycleScope.launch {
                val willLike = !topic.currentUserLiked
                val res = client.likeTopic(topic.id, willLike)
                if (res.isSuccess) {
                    topic.currentUserLiked = willLike
                    if (willLike) {
                        topic.likeCount++
                    } else {
                        topic.likeCount = (topic.likeCount - 1).coerceAtLeast(0)
                    }
                    discussion.viewsCount = topic.likeCount
                    updateLikeButtonUi()
                    discussionAdapter.notifyDataSetChanged()
                } else {
                    val msg = res.exceptionOrNull()?.message ?: "操作失败"
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }

        fun renderReplies() {
            val replies = topic.replies ?: emptyList()
            dialogBinding.tvDetailReplyCount.text = "${replies.size} 条回复"
            dialogBinding.llRepliesContainer.removeAllViews()

            if (replies.isEmpty()) {
                dialogBinding.tvEmptyReplies.visibility = View.VISIBLE
            } else {
                dialogBinding.tvEmptyReplies.visibility = View.GONE
                val currentUserId = TronClassPreferences.getInstance(ctx).getUserId()
                val currentUserName = TronClassPreferences.getInstance(ctx).getUserName()

                for (reply in replies) {
                    val replyBinding = ItemTronclassDiscussionReplyBinding.inflate(
                        fragment.layoutInflater, dialogBinding.llRepliesContainer, false
                    )

                    val replyAuthor = reply.getAuthorName()
                    replyBinding.tvReplyAuthor.text = replyAuthor
                    replyBinding.tvReplyAvatar.text = replyAuthor.firstOrNull()?.toString() ?: "同"
                    replyBinding.tvReplyTime.text = reply.getFormattedTime().ifBlank { "近期" }
                    replyBinding.tvReplyContent.text = reply.getCleanContent()

                    val isSelfReply = (currentUserId > 0L && reply.createdBy?.id == currentUserId) ||
                                     (!currentUserName.isNullOrBlank() && replyAuthor.contains(currentUserName))
                    replyBinding.tvReplyRoleBadge.visibility = if (isSelfReply) View.VISIBLE else View.GONE
                    replyBinding.tvLikesCount.text = "${reply.likeCount}"

                    dialogBinding.llRepliesContainer.addView(replyBinding.root)
                }
            }
        }
        renderReplies()

        // 提交回复
        dialogBinding.btnSubmitReply.setOnClickListener {
            val replyText = dialogBinding.etReplyInput.text?.toString()?.trim() ?: ""
            if (replyText.isBlank()) {
                Toast.makeText(ctx, "请输入回复内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialogBinding.btnSubmitReply.isEnabled = false
            dialogBinding.btnSubmitReply.text = "发送中..."

            fragment.viewLifecycleOwner.lifecycleScope.launch {
                val res = client.postTopicReply(topic.id, replyText)
                if (res.isSuccess) {
                    val newReply = res.getOrNull()
                    Toast.makeText(ctx, "回复成功！", Toast.LENGTH_SHORT).show()
                    dialogBinding.etReplyInput.setText("")

                    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(dialogBinding.etReplyInput.windowToken, 0)

                    if (newReply != null) {
                        val currentList = topic.replies?.toMutableList() ?: mutableListOf()
                        currentList.add(newReply)
                        topic.replies = currentList
                        topic.replyCount = currentList.size
                        discussion.repliesCount = currentList.size
                    } else {
                        topic.replyCount++
                        discussion.repliesCount = topic.replyCount
                    }

                    renderReplies()
                    discussionAdapter.notifyDataSetChanged()

                    dialogBinding.scrollViewTopicDetail.post {
                        dialogBinding.scrollViewTopicDetail.fullScroll(View.FOCUS_DOWN)
                    }
                } else {
                    val errMsg = res.exceptionOrNull()?.message ?: "回复失败"
                    Toast.makeText(ctx, errMsg, Toast.LENGTH_LONG).show()
                }
                dialogBinding.btnSubmitReply.isEnabled = true
                dialogBinding.btnSubmitReply.text = "回复"
            }
        }

        TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, ctx)

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.skipCollapsed = true
                val displayMetrics = fragment.resources.displayMetrics
                val targetHeight = (displayMetrics.heightPixels * 0.85).toInt()
                bottomSheet.layoutParams.height = targetHeight
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }

        dialog.show()
    }

    /**
     * 发表新讨论主题弹窗
     */
    fun showCreateTopicDialog(defaultCategoryId: Long? = null, defaultActivityId: Long? = null) {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx)
        val dialogBinding = DialogTronclassCreateTopicBinding.inflate(fragment.layoutInflater)
        dialog.setContentView(dialogBinding.root)

        var catId = defaultCategoryId ?: cachedDiscussions.firstOrNull { it.categoryId != null }?.categoryId
        var actId = defaultActivityId ?: cachedActivities.firstOrNull { it.type == "forum" }?.id

        val catName = cachedDiscussions.firstOrNull { it.isCategoryHeader }?.getCleanTitle()
            ?: cachedActivities.firstOrNull { it.type == "forum" }?.getCleanTitle()
            ?: courseName

        dialogBinding.tvTargetCategoryName.text = "议题板块：$catName"

        dialogBinding.btnCreateTopicClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnCancelCreateTopic.setOnClickListener { dialog.dismiss() }

        dialogBinding.btnSubmitNewTopic.setOnClickListener {
            val title = dialogBinding.etNewTopicTitle.text?.toString()?.trim() ?: ""
            val content = dialogBinding.etNewTopicContent.text?.toString()?.trim() ?: ""

            if (title.isBlank()) {
                Toast.makeText(ctx, "请输入讨论发言主题", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (content.isBlank()) {
                Toast.makeText(ctx, "请输入发言正文内容", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            dialogBinding.btnSubmitNewTopic.isEnabled = false
            dialogBinding.btnSubmitNewTopic.text = "正在发布..."

            fragment.viewLifecycleOwner.lifecycleScope.launch {
                var targetCatId = catId
                var targetActId = actId
                if (targetCatId == null || targetCatId <= 0) {
                    val catsRes = client.fetchCourseTopicCategories(courseId)
                    val firstCat = catsRes.getOrNull()?.firstOrNull()
                    if (firstCat != null) {
                        targetCatId = firstCat.id
                        if (targetActId == null) targetActId = firstCat.activityId
                    }
                }

                if (targetCatId == null || targetCatId <= 0) {
                    dialogBinding.btnSubmitNewTopic.isEnabled = true
                    dialogBinding.btnSubmitNewTopic.text = "发布讨论"
                    Toast.makeText(ctx, "未能获取当前课程讨论分类，请稍后重试", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val result = client.postNewTopic(targetCatId, title, content, targetActId)
                if (result.isSuccess) {
                    Toast.makeText(ctx, "讨论发表成功！", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onRefreshCourseData()
                } else {
                    dialogBinding.btnSubmitNewTopic.isEnabled = true
                    dialogBinding.btnSubmitNewTopic.text = "发布讨论"
                    val errMsg = result.exceptionOrNull()?.message ?: "发布失败"
                    Toast.makeText(ctx, errMsg, Toast.LENGTH_LONG).show()
                }
            }
        }

        TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, ctx)
        dialog.show()
    }

}
