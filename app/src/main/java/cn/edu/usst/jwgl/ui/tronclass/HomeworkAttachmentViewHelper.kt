package cn.edu.usst.jwgl.ui.tronclass

import android.app.Dialog
import android.app.ProgressDialog
import android.content.Context
import cn.edu.usst.jwgl.data.local.TronClassCacheManager
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.TronClassDownloadManager
import cn.edu.usst.jwgl.data.local.TronClassDownloadedItem
import cn.edu.usst.jwgl.data.model.ChaoXingAttachmentKind
import cn.edu.usst.jwgl.data.model.ChaoXingCourseAttachment
import cn.edu.usst.jwgl.data.model.TronClassUpload
import cn.edu.usst.jwgl.data.network.ChaoXingCourseDetailClient
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.ItemTronclassAttachmentBinding
import cn.edu.usst.jwgl.ui.JwglWebActivity
import cn.edu.usst.jwgl.ui.common.ZoomableImageView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 作业附件条目、要求图片与全屏图片预览视图辅助器
 */
internal class HomeworkAttachmentViewHelper(
    private val fragment: Fragment,
    private val client: TronClassClient,
    private val chaoXingDetailClient: ChaoXingCourseDetailClient,
    private val courseIdProvider: () -> Long,
    private val activityIdProvider: () -> Long,
    private val courseNameProvider: () -> String,
    private val titleProvider: () -> String,
    private val cacheManager: TronClassCacheManager,
    private val chaoXingWorkIdProvider: () -> String?
) {
    private val context: Context? get() = fragment.context
    private val viewLifecycleOwner get() = fragment.viewLifecycleOwner
    private val courseId get() = courseIdProvider()
    private val activityId get() = activityIdProvider()
    private val courseNameText get() = courseNameProvider()
    private val titleText get() = titleProvider()
    private val chaoXingWork get() = chaoXingWorkIdProvider()?.let { id -> object { val workId = id } }

    private fun dpToPx(dp: Int): Int = (dp * fragment.resources.displayMetrics.density).toInt()

    fun createChaoXingAttachmentItemView(attachment: ChaoXingCourseAttachment): View {
        val itemBinding = ItemTronclassAttachmentBinding.inflate(fragment.layoutInflater, null, false)
        itemBinding.tvFileName.text = attachment.name
        itemBinding.tvFileSize.text = "${attachment.getSubtitleSummary()} · 超星附件"
        itemBinding.btnFileAction.text = when (attachment.resolveKind()) {
            ChaoXingAttachmentKind.VIDEO -> "播放"
            ChaoXingAttachmentKind.IMAGE, ChaoXingAttachmentKind.PDF -> "预览"
            ChaoXingAttachmentKind.AUDIO, ChaoXingAttachmentKind.ARCHIVE, ChaoXingAttachmentKind.OTHER -> "下载"
            else -> "查看/下载"
        }
        itemBinding.btnFileRemove.visibility = View.GONE

        itemBinding.btnFileAction.setOnClickListener {
            handleChaoXingAttachmentAction(attachment)
        }
        itemBinding.root.setOnClickListener {
            handleChaoXingAttachmentAction(attachment)
        }
        return itemBinding.root
    }

    fun handleChaoXingAttachmentAction(attachment: ChaoXingCourseAttachment) {
        ChaoXingAttachmentActionHelper.handleAttachmentClick(
            fragment = fragment,
            chaoXingDetailClient = chaoXingDetailClient,
            attachment = attachment,
            courseId = courseId,
            courseName = courseNameText
        )
    }

    fun createAttachmentItemView(upload: TronClassUpload, isDeletable: Boolean, onRemove: (() -> Unit)? = null): View {
        val itemBinding = ItemTronclassAttachmentBinding.inflate(fragment.layoutInflater, null, false)

        itemBinding.tvFileName.text = upload.name
        val sizeStr = upload.getFormattedSize()
        val isCached = cacheManager.isCached(upload.id, upload.name)

        if (isCached) {
            itemBinding.tvFileSize.text = "$sizeStr · 已缓存到本地"
            itemBinding.btnFileAction.text = "打开"
        } else {
            itemBinding.tvFileSize.text = "$sizeStr · 点击下载"
            itemBinding.btnFileAction.text = "下载"
        }

        itemBinding.btnFileAction.setOnClickListener {
            handleAttachmentAction(upload, itemBinding)
        }

        if (isDeletable && onRemove != null) {
            itemBinding.btnFileRemove.visibility = View.VISIBLE
            itemBinding.btnFileRemove.setOnClickListener { onRemove() }
        } else {
            itemBinding.btnFileRemove.visibility = View.GONE
        }

        return itemBinding.root
    }

    fun handleAttachmentAction(upload: TronClassUpload, itemBinding: ItemTronclassAttachmentBinding) {
        val ctx = fragment.context ?: return
        if (cacheManager.openCachedUpload(upload.id, upload.name)) {
            return
        }

        val destFile = cacheManager.getFile(upload.id, upload.name)
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(fragment.requireContext()).apply {
            setTitle("下载文件")
            setMessage("${upload.name}\n正在下载...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val res = client.downloadAttachment(upload.id, destFile) { percent ->
                progressDialog.progress = percent
            }

            if (res.isSuccess) {
                val persistResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    cn.edu.usst.jwgl.data.local.CoursewareStorageManager.persistDownloadedCourseware(
                        context = ctx,
                        sourceFile = destFile,
                        uploadId = upload.id,
                        displayName = upload.name ?: "附件",
                        courseId = courseId,
                        courseName = courseNameText,
                        removeSourceAfterExport = true
                    )
                }
                progressDialog.dismiss()
                itemBinding.tvFileSize.text = "${upload.getFormattedSize()} · 已缓存到本地"
                itemBinding.btnFileAction.text = "打开"
                val toastText = persistResult.warningMessage ?: "已下载至：${persistResult.locationLabel}"
                Toast.makeText(ctx, toastText, Toast.LENGTH_SHORT).show()
                cacheManager.openPersistResult(persistResult, upload.name ?: "附件", courseNameText)
            } else {
                progressDialog.dismiss()
                val err = res.exceptionOrNull()?.message ?: "下载失败"
                Toast.makeText(ctx, err, Toast.LENGTH_LONG).show()
            }
        }
    }


    fun createRequirementImageView(imgUrl: String): View {
        val ctx = fragment.requireContext()
        val card = com.google.android.material.card.MaterialCardView(fragment.requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(10)
                bottomMargin = dpToPx(6)
            }
            radius = dpToPx(10).toFloat()
            cardElevation = dpToPx(2).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(fragment.requireContext(), R.color.surface_variant))
            strokeWidth = 0
            isClickable = true
            isFocusable = true
        }

        val imageView = ImageView(fragment.requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
        }

        card.addView(imageView)

        TronClassImageLoader.load(imageView, imgUrl, fragment.requireContext())

        card.setOnClickListener {
            showFullScreenImagePreview(imgUrl)
        }

        card.setOnLongClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            TronClassImageHelper.showImageOptionsDialog(
                fragment.requireContext(),
                imgUrl,
                title = titleText.ifBlank { "作业附图" },
                courseId = courseId,
                courseName = courseNameText
            ) {
                showFullScreenImagePreview(imgUrl)
            }
            true
        }

        return card
    }

    fun showFullScreenImagePreview(imageUrl: String) {
        val ctx = fragment.context ?: return
        TronClassImageHelper.showFullScreenPreview(
            fragment.requireContext(),
            imageUrl,
            title = titleText.ifBlank { "作业附图" },
            courseId = courseId,
            courseName = courseNameText
        )
    }

}
