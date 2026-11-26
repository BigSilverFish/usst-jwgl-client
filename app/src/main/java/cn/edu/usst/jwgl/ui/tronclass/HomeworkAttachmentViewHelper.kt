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
        val sizeStr = attachment.getFormattedSize()
        itemBinding.tvFileSize.text = if (sizeStr.isNotBlank()) "$sizeStr · 超星附件" else "超星附件"
        itemBinding.btnFileAction.text = "查看/下载"
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
        val ctx = fragment.context ?: return
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(ctx).apply {
            setMessage("正在解析「${attachment.name}」...")
            setCancelable(false)
            show()
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val res = chaoXingDetailClient.fetchYunFile(attachment.objectId)
            progress.dismiss()
            if (res.isSuccess) {
                val yun = res.getOrNull()
                val lowerName = attachment.name.lowercase(Locale.ROOT)
                val isVideo = attachment.isVideo || lowerName.endsWith(".mp4") || lowerName.endsWith(".flv") || lowerName.endsWith(".wmv") || lowerName.endsWith(".avi")
                val isImage = TronClassImageHelper.isImageUrl(attachment.name)

                if (isVideo) {
                    val stream = yun?.streamUrl ?: ""
                    if (stream.isNotBlank()) {
                        TronClassVideoPlayerActivity.start(
                            context = ctx,
                            title = attachment.name,
                            courseName = courseNameText,
                            videoUrl = stream
                        )
                    } else {
                        Toast.makeText(ctx, "该视频暂无可播放流地址", Toast.LENGTH_SHORT).show()
                    }
                } else if (isImage) {
                    val imgUrl = yun?.downloadUrl?.ifBlank { yun?.pdfUrl } ?: ""
                    if (imgUrl.isNotBlank()) {
                        TronClassImageHelper.showFullScreenPreview(
                            ctx,
                            imgUrl,
                            title = attachment.name,
                            courseName = courseNameText
                        )
                    } else {
                        Toast.makeText(ctx, "图片资源未就绪", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val pdfUrl = yun?.pdfUrl ?: ""
                    val downloadUrl = yun?.downloadUrl ?: ""
                    val targetUrl = pdfUrl.ifBlank { downloadUrl }

                    if (targetUrl.isNotBlank()) {
                        if (targetUrl.endsWith(".pdf", ignoreCase = true) || pdfUrl.isNotBlank() || attachment.name.endsWith(".pdf", ignoreCase = true)) {
                            cn.edu.usst.jwgl.ui.pdf.PdfViewerActivity.start(ctx, targetUrl, attachment.name)
                        } else {
                            JwglWebActivity.start(ctx, targetUrl, attachment.name)
                        }
                    } else {
                        Toast.makeText(ctx, "该附件暂无可下载直链", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(ctx, "解析附件失败，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        }
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
        val existing = cacheManager.getExistingFile(upload.id, upload.name)
        if (existing != null) {
            cacheManager.openFile(existing)
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
            progressDialog.dismiss()

            if (res.isSuccess) {
                TronClassDownloadManager.saveDownloadedItem(
                    fragment.requireContext(),
                    TronClassDownloadedItem(
                        fileId = upload.id.toString(),
                        fileName = upload.name ?: "附件",
                        courseId = courseId,
                        courseName = courseNameText,
                        filePath = destFile.absolutePath,
                        fileSize = if (destFile.length() > 0) destFile.length() else upload.size
                    )
                )
                itemBinding.tvFileSize.text = "${upload.getFormattedSize()} · 已缓存到本地"
                itemBinding.btnFileAction.text = "打开"
                Toast.makeText(fragment.requireContext(), "下载完成", Toast.LENGTH_SHORT).show()
                cacheManager.openFile(destFile)
            } else {
                val err = res.exceptionOrNull()?.message ?: "下载失败"
                Toast.makeText(fragment.requireContext(), err, Toast.LENGTH_LONG).show()
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
