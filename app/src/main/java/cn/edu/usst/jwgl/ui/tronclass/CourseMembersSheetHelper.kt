package cn.edu.usst.jwgl.ui.tronclass

import android.app.ProgressDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import cn.edu.usst.jwgl.data.model.ChaoXingChapter
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.local.TronClassDownloadManager
import cn.edu.usst.jwgl.data.local.TronClassDownloadedItem
import cn.edu.usst.jwgl.data.model.ChaoXingAttachmentKind
import cn.edu.usst.jwgl.data.model.ChaoXingChapterUnit
import cn.edu.usst.jwgl.data.model.ChaoXingCourse
import cn.edu.usst.jwgl.data.model.ChaoXingCourseAttachment
import cn.edu.usst.jwgl.data.model.TronClassActivityItem
import cn.edu.usst.jwgl.data.network.ChaoXingClient
import cn.edu.usst.jwgl.data.network.ChaoXingCourseDetailClient
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.DialogTronclassClassMembersBinding
import cn.edu.usst.jwgl.ui.JwglWebActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 课程班级成员 BottomSheet 与超星章节课件弹窗辅助器
 */
internal class CourseMembersSheetHelper(
    private val fragment: TronClassCourseDetailFragment,
    private val client: TronClassClient,
    private val chaoXingDetailClient: ChaoXingCourseDetailClient,
    private val courseIdProvider: () -> Long,
    private val courseNameProvider: () -> String,
    private val chaoXingCourseProvider: () -> ChaoXingCourse?,
    private val chaoXingChapterUnitsProvider: () -> List<ChaoXingChapterUnit>
) {
    private val context: Context? get() = fragment.context
    private val courseId get() = courseIdProvider()
    private val courseName get() = courseNameProvider()
    private val chaoXingCourse get() = chaoXingCourseProvider()
    private val rawChaoXingCourse get() = fragment.rawChaoXingCourse
    private var activeMemberDialogBinding
        get() = fragment.activeMemberDialogBinding
        set(value) { fragment.activeMemberDialogBinding = value }
    private var cachedMembers
        get() = fragment.cachedMembers
        set(value) { fragment.cachedMembers = value }
    private var activeMemberAdapter
        get() = fragment.activeMemberAdapter
        set(value) { fragment.activeMemberAdapter = value }
    private val chaoXingChapterUnits get() = chaoXingChapterUnitsProvider()

    fun showClassMembersBottomSheet() {
        val ctx = context ?: return
        val isPad = TabletLayoutHelper.isTabletLandscape(ctx)

        val dialog = BottomSheetDialog(ctx)
        val dialogBinding = DialogTronclassClassMembersBinding.inflate(fragment.layoutInflater)
        dialog.setContentView(dialogBinding.root)

        activeMemberDialogBinding = dialogBinding

        dialogBinding.tvMemberCountBadge.text = "共 ${cachedMembers.size} 人"

        val memberAdapter = TronClassMemberAdapter()
        activeMemberAdapter = memberAdapter
        memberAdapter.updateData(cachedMembers)

        dialogBinding.rvDialogMembers.layoutManager = if (isPad) GridLayoutManager(ctx, 2) else LinearLayoutManager(ctx)
        dialogBinding.rvDialogMembers.adapter = memberAdapter

        dialogBinding.layoutEmptyMembers.visibility = if (cachedMembers.isEmpty()) View.VISIBLE else View.GONE
        dialogBinding.tvEmptyMembersText.text = "暂无班级成员"

        dialogBinding.etMemberSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString() ?: ""
                memberAdapter.filter(q)
                if (memberAdapter.itemCount == 0) {
                    dialogBinding.layoutEmptyMembers.visibility = View.VISIBLE
                    dialogBinding.tvEmptyMembersText.text = if (q.isBlank()) "暂无班级成员" else "未找到与 \"$q\" 相关的成员"
                } else {
                    dialogBinding.layoutEmptyMembers.visibility = View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        dialogBinding.btnCloseDialog.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            activeMemberAdapter = null
            activeMemberDialogBinding = null
        }

        // 大屏平板模式宽度居中优化
        TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, ctx)

        // 默认让弹窗展开良好高度
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        dialog.show()

        // 触发按需成员拉取（避免进课程详情页时提前请求；已有本地缓存时在首次打开弹窗时静默刷新一次）
        fragment.loadMembersIfNeeded()
    }

    /**
     * 查看课堂讨论议题导读说明弹窗 (针对教师发布的 forum 活动说明卡片)
     */

    fun showChaoXingChapterBottomSheet(act: TronClassActivityItem) {
        val ctx = context ?: return
        val chapter = act.extraChaoXingChapter ?: ChaoXingChapter(
            id = act.id.toString(),
            title = act.getCleanTitle(),
            completed = act.completeness == "complete" || act.completeness == "full"
        )
        val cxCourse = act.extraChaoXingCourse ?: rawChaoXingCourse ?: return

        val dialog = BottomSheetDialog(ctx)
        val view = fragment.layoutInflater.inflate(R.layout.dialog_chaoxing_chapter_attachments, null)
        dialog.setContentView(view)

        val tvTitle = view.findViewById<TextView>(R.id.tvChapterTitle)
        val tvSub = view.findViewById<TextView>(R.id.tvChapterSub)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBarLoading)
        val llAttachments = view.findViewById<LinearLayout>(R.id.llAttachmentsContainer)
        val tvEmpty = view.findViewById<TextView>(R.id.tvEmptyAttachments)

        tvTitle.text = chapter.title
        tvSub.text = if (chapter.number.isNotBlank()) "${chapter.number} · ${cxCourse.courseName}" else cxCourse.courseName

        dialog.show()

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            llAttachments.visibility = View.GONE
            tvEmpty.visibility = View.GONE

            val res = chaoXingDetailClient.fetchAttachments(cxCourse, chapter.id)
            progressBar.visibility = View.GONE

            if (res.isSuccess) {
                val attachments = res.getOrDefault(emptyList())
                if (attachments.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "本章节暂无已上传的课件或附件"
                } else {
                    llAttachments.visibility = View.VISIBLE
                    llAttachments.removeAllViews()
                    for (attachment in attachments) {
                        val itemView = fragment.layoutInflater.inflate(R.layout.item_chaoxing_attachment_card, llAttachments, false)
                        val ivIcon = itemView.findViewById<ImageView>(R.id.ivAttachmentIcon)
                        val tvName = itemView.findViewById<TextView>(R.id.tvAttachmentName)
                        val tvSize = itemView.findViewById<TextView>(R.id.tvAttachmentSize)
                        val btnAction = itemView.findViewById<MaterialButton>(R.id.btnAttachmentAction)

                        tvName.text = attachment.name
                        tvSize.text = attachment.getSubtitleSummary()

                        when (attachment.resolveKind()) {
                            ChaoXingAttachmentKind.VIDEO -> {
                                ivIcon.setImageResource(R.drawable.ic_live_video)
                                ivIcon.setColorFilter(Color.parseColor("#EA580C"))
                                btnAction.text = "播放"
                                btnAction.setIconResource(R.drawable.ic_play_arrow)
                            }
                            ChaoXingAttachmentKind.AUDIO -> {
                                ivIcon.setImageResource(R.drawable.ic_file_doc)
                                ivIcon.setColorFilter(Color.parseColor("#7C3AED"))
                                btnAction.text = "下载"
                                btnAction.setIconResource(R.drawable.ic_download)
                            }
                            ChaoXingAttachmentKind.IMAGE -> {
                                ivIcon.setImageResource(R.drawable.ic_file_doc)
                                ivIcon.setColorFilter(Color.parseColor("#059669"))
                                btnAction.text = "预览/保存"
                                btnAction.setIconResource(R.drawable.ic_download)
                            }
                            ChaoXingAttachmentKind.PDF -> {
                                ivIcon.setImageResource(R.drawable.ic_file_pdf)
                                ivIcon.setColorFilter(ContextCompat.getColor(ctx, R.color.error))
                                btnAction.text = "预览"
                                btnAction.setIconResource(R.drawable.ic_file_doc)
                            }
                            ChaoXingAttachmentKind.PPT -> {
                                ivIcon.setImageResource(R.drawable.ic_file_doc)
                                ivIcon.setColorFilter(Color.parseColor("#F57C00"))
                                btnAction.text = "预览/下载"
                                btnAction.setIconResource(R.drawable.ic_download)
                            }
                            ChaoXingAttachmentKind.WORD -> {
                                ivIcon.setImageResource(R.drawable.ic_file_doc)
                                ivIcon.setColorFilter(Color.parseColor("#1976D2"))
                                btnAction.text = "预览/下载"
                                btnAction.setIconResource(R.drawable.ic_download)
                            }
                            ChaoXingAttachmentKind.EXCEL -> {
                                ivIcon.setImageResource(R.drawable.ic_file_doc)
                                ivIcon.setColorFilter(Color.parseColor("#0D9488"))
                                btnAction.text = "预览/下载"
                                btnAction.setIconResource(R.drawable.ic_download)
                            }
                            ChaoXingAttachmentKind.ARCHIVE -> {
                                ivIcon.setImageResource(R.drawable.ic_file_doc)
                                ivIcon.setColorFilter(Color.parseColor("#6D28D9"))
                                btnAction.text = "下载"
                                btnAction.setIconResource(R.drawable.ic_download)
                            }
                            ChaoXingAttachmentKind.DOCUMENT -> {
                                ivIcon.setImageResource(R.drawable.ic_file_doc)
                                ivIcon.setColorFilter(ContextCompat.getColor(ctx, R.color.primary))
                                btnAction.text = "预览/下载"
                                btnAction.setIconResource(R.drawable.ic_download)
                            }
                            ChaoXingAttachmentKind.OTHER -> {
                                ivIcon.setImageResource(R.drawable.ic_file_doc)
                                ivIcon.setColorFilter(ContextCompat.getColor(ctx, R.color.primary))
                                btnAction.text = "下载"
                                btnAction.setIconResource(R.drawable.ic_download)
                            }
                        }

                        btnAction.setOnClickListener {
                            handleChaoXingAttachmentClick(attachment)
                        }
                        itemView.setOnClickListener {
                            handleChaoXingAttachmentClick(attachment)
                        }
                        llAttachments.addView(itemView)
                    }
                }
            } else {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "课件附件加载失败，点击重试"
                tvEmpty.setOnClickListener {
                    showChaoXingChapterBottomSheet(act)
                }
            }
        }
    }

    fun handleChaoXingAttachmentClick(attachment: ChaoXingCourseAttachment) {
        ChaoXingAttachmentActionHelper.handleAttachmentClick(
            fragment = fragment,
            chaoXingDetailClient = chaoXingDetailClient,
            attachment = attachment,
            courseId = courseId,
            courseName = courseName
        )
    }

}
