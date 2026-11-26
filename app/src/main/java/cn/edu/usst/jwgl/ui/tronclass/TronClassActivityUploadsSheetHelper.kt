package cn.edu.usst.jwgl.ui.tronclass

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.TronClassCacheManager
import cn.edu.usst.jwgl.data.local.TronClassDownloadManager
import cn.edu.usst.jwgl.data.local.TronClassDownloadedItem
import cn.edu.usst.jwgl.data.model.TronClassActivityItem
import cn.edu.usst.jwgl.data.model.TronClassUpload
import cn.edu.usst.jwgl.data.model.formatTronClassFileSize
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.DialogTronclassActivityUploadsBinding
import cn.edu.usst.jwgl.databinding.ItemChaoxingAttachmentCardBinding
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 畅课（TronClass）单节多文件课件/附件列表底部弹窗辅助器
 */
internal object TronClassActivityUploadsSheetHelper {

    private data class UploadRowHolder(
        val upload: TronClassUpload,
        val binding: ItemChaoxingAttachmentCardBinding
    )

    fun showActivityUploadsSheet(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        client: TronClassClient,
        cacheManager: TronClassCacheManager,
        activityItem: TronClassActivityItem,
        courseId: Long,
        courseName: String,
        onCacheStateChanged: () -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val inflater = LayoutInflater.from(context)
        val sheetBinding = DialogTronclassActivityUploadsBinding.inflate(inflater)
        dialog.setContentView(sheetBinding.root)

        TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, context)

        sheetBinding.tvActivityTitle.text = activityItem.getCleanTitle()
        val descText = TronClassHtmlHelper.getCleanText(activityItem.data?.description)
        if (descText.isNotBlank()) {
            sheetBinding.tvActivityDescription.visibility = View.VISIBLE
            sheetBinding.tvActivityDescription.text = descText
        }

        sheetBinding.btnClose.setOnClickListener { dialog.dismiss() }

        val uploads = activityItem.getAllUploads().toMutableList()
        if (uploads.isNotEmpty()) {
            bindUploadsList(
                context = context,
                lifecycleOwner = lifecycleOwner,
                inflater = inflater,
                sheetBinding = sheetBinding,
                client = client,
                cacheManager = cacheManager,
                activityItem = activityItem,
                uploads = uploads,
                courseId = courseId,
                courseName = courseName,
                onCacheStateChanged = onCacheStateChanged
            )
            dialog.show()
            return
        }

        // 若列表接口未携带 uploads，尝试拉取活动详情并提取内嵌附件兜底
        sheetBinding.tvActivitySubtitle.text = "正在加载附件列表..."
        sheetBinding.progressBarLoading.visibility = View.VISIBLE
        dialog.show()

        lifecycleOwner.lifecycleScope.launch {
            val detailRes = withContext(Dispatchers.IO) { client.fetchActivityDetail(activityItem.id) }
            if (!dialog.isShowing) return@launch
            sheetBinding.progressBarLoading.visibility = View.GONE

            val detail = detailRes.getOrNull()
            val detailDesc = TronClassHtmlHelper.getCleanText(detail?.data?.description)
            if (detailDesc.isNotBlank() && sheetBinding.tvActivityDescription.visibility != View.VISIBLE) {
                sheetBinding.tvActivityDescription.visibility = View.VISIBLE
                sheetBinding.tvActivityDescription.text = detailDesc
            }

            val resolved = mutableListOf<TronClassUpload>()
            detail?.getAllUploads()?.let { resolved.addAll(it) }
            TronClassHtmlHelper.extractEmbeddedUploads(detail?.data?.description).forEach { emb ->
                if (resolved.none { it.id == emb.id }) resolved.add(emb)
            }

            if (resolved.isEmpty()) {
                sheetBinding.tvActivitySubtitle.text = "暂无附件文件"
                sheetBinding.tvEmptyUploads.visibility = View.VISIBLE
            } else {
                bindUploadsList(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    inflater = inflater,
                    sheetBinding = sheetBinding,
                    client = client,
                    cacheManager = cacheManager,
                    activityItem = activityItem,
                    uploads = resolved,
                    courseId = courseId,
                    courseName = courseName,
                    onCacheStateChanged = onCacheStateChanged
                )
            }
        }
    }

    private fun bindUploadsList(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        inflater: LayoutInflater,
        sheetBinding: DialogTronclassActivityUploadsBinding,
        client: TronClassClient,
        cacheManager: TronClassCacheManager,
        activityItem: TronClassActivityItem,
        uploads: List<TronClassUpload>,
        courseId: Long,
        courseName: String,
        onCacheStateChanged: () -> Unit
    ) {
        sheetBinding.tvEmptyUploads.visibility = View.GONE
        sheetBinding.llUploadsContainer.removeAllViews()

        val rowHolders = mutableListOf<UploadRowHolder>()

        fun refreshSummaryAndBatchButton() {
            val totalCount = uploads.size
            val totalSize = uploads.sumOf { it.size.coerceAtLeast(0L) }
            val cachedCount = uploads.count { cacheManager.isCached(it.id, it.name) }
            val uncachedDownloadable = uploads.filter {
                !isVideoUpload(it) && !cacheManager.isCached(it.id, it.name)
            }

            val parts = mutableListOf("共 $totalCount 个文件")
            if (totalSize > 0L) {
                parts.add("总计 ${formatTronClassFileSize(totalSize)}")
            }
            if (cachedCount > 0) {
                parts.add("已缓存 $cachedCount/$totalCount")
            }
            sheetBinding.tvActivitySubtitle.text = parts.joinToString(" · ")

            if (uncachedDownloadable.size > 1) {
                sheetBinding.btnDownloadAll.visibility = View.VISIBLE
                sheetBinding.btnDownloadAll.text = "全部下载（剩 ${uncachedDownloadable.size} 个）"
                sheetBinding.btnDownloadAll.setOnClickListener {
                    downloadAllUncached(
                        context = context,
                        lifecycleOwner = lifecycleOwner,
                        client = client,
                        cacheManager = cacheManager,
                        activityItem = activityItem,
                        targets = uncachedDownloadable,
                        rowHolders = rowHolders,
                        courseId = courseId,
                        courseName = courseName,
                        onFinished = {
                            refreshSummaryAndBatchButton()
                            onCacheStateChanged()
                        }
                    )
                }
            } else {
                sheetBinding.btnDownloadAll.visibility = View.GONE
            }
        }

        for (upload in uploads) {
            val rowBinding = ItemChaoxingAttachmentCardBinding.inflate(inflater, sheetBinding.llUploadsContainer, false)
            val holder = UploadRowHolder(upload, rowBinding)
            rowHolders.add(holder)

            bindUploadRow(
                context = context,
                lifecycleOwner = lifecycleOwner,
                holder = holder,
                client = client,
                cacheManager = cacheManager,
                activityItem = activityItem,
                courseId = courseId,
                courseName = courseName,
                onRowStateChanged = {
                    refreshSummaryAndBatchButton()
                    onCacheStateChanged()
                }
            )
            sheetBinding.llUploadsContainer.addView(rowBinding.root)
        }

        refreshSummaryAndBatchButton()
    }

    private fun bindUploadRow(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        holder: UploadRowHolder,
        client: TronClassClient,
        cacheManager: TronClassCacheManager,
        activityItem: TronClassActivityItem,
        courseId: Long,
        courseName: String,
        onRowStateChanged: () -> Unit
    ) {
        val upload = holder.upload
        val rowBinding = holder.binding
        val safeName = upload.name?.trim().takeIf { !it.isNullOrBlank() } ?: "附件_${upload.id}"
        rowBinding.tvAttachmentName.text = safeName

        val isVideo = isVideoUpload(upload)
        val isImage = TronClassImageHelper.isImageUrl(upload.name)
        val ext = upload.getExtension()

        when {
            isVideo -> {
                rowBinding.ivAttachmentIcon.setImageResource(R.drawable.ic_live_video)
                rowBinding.ivAttachmentIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))
            }
            ext == "pdf" -> {
                rowBinding.ivAttachmentIcon.setImageResource(R.drawable.ic_file_pdf)
                rowBinding.ivAttachmentIcon.setColorFilter(ContextCompat.getColor(context, R.color.error))
            }
            isImage -> {
                rowBinding.ivAttachmentIcon.setImageResource(R.drawable.ic_file_doc)
                rowBinding.ivAttachmentIcon.setColorFilter(Color.parseColor("#059669"))
            }
            else -> {
                rowBinding.ivAttachmentIcon.setImageResource(R.drawable.ic_file_doc)
                rowBinding.ivAttachmentIcon.setColorFilter(ContextCompat.getColor(context, R.color.primary))
            }
        }

        updateRowActionState(context, holder, cacheManager)

        val clickAction = View.OnClickListener {
            handleSingleUploadClick(
                context = context,
                lifecycleOwner = lifecycleOwner,
                holder = holder,
                client = client,
                cacheManager = cacheManager,
                activityItem = activityItem,
                courseId = courseId,
                courseName = courseName,
                onRowStateChanged = onRowStateChanged
            )
        }
        rowBinding.btnAttachmentAction.setOnClickListener(clickAction)
        rowBinding.root.setOnClickListener(clickAction)

        if (isImage) {
            rowBinding.root.setOnLongClickListener { v ->
                val existing = cacheManager.getExistingFile(upload.id, upload.name)
                val urlToUse = if (existing != null) "file://${existing.absolutePath}" else "https://1906.usst.edu.cn/api/uploads/${upload.id}"
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                TronClassImageHelper.showImageOptionsDialog(
                    context = context,
                    imageUrl = urlToUse,
                    title = safeName,
                    courseId = courseId,
                    courseName = courseName
                ) {
                    TronClassImageHelper.showFullScreenPreview(
                        context = context,
                        imageUrl = urlToUse,
                        title = safeName,
                        courseId = courseId,
                        courseName = courseName
                    )
                }
                true
            }
        }
    }

    private fun updateRowActionState(
        context: Context,
        holder: UploadRowHolder,
        cacheManager: TronClassCacheManager
    ) {
        val upload = holder.upload
        val rowBinding = holder.binding
        val isVideo = isVideoUpload(upload)
        val isImage = TronClassImageHelper.isImageUrl(upload.name)
        val isCached = cacheManager.isCached(upload.id, upload.name)
        val sizeStr = upload.getFormattedSize()

        when {
            isVideo -> {
                rowBinding.tvAttachmentSize.text = if (upload.size > 0) "$sizeStr · 在线视频" else "在线视频"
                rowBinding.btnAttachmentAction.text = "播放"
                rowBinding.btnAttachmentAction.setIconResource(R.drawable.ic_play_arrow)
            }
            isImage -> {
                rowBinding.tvAttachmentSize.text = when {
                    isCached && upload.size > 0 -> "$sizeStr · 已缓存"
                    isCached -> "已缓存"
                    upload.size > 0 -> "$sizeStr · 点击预览"
                    else -> "点击预览"
                }
                rowBinding.btnAttachmentAction.text = "预览"
                rowBinding.btnAttachmentAction.setIconResource(R.drawable.ic_file_doc)
            }
            isCached -> {
                rowBinding.tvAttachmentSize.text = if (upload.size > 0) "$sizeStr · 已缓存" else "已缓存"
                rowBinding.btnAttachmentAction.text = "打开"
                rowBinding.btnAttachmentAction.setIconResource(R.drawable.ic_file_doc)
            }
            else -> {
                rowBinding.tvAttachmentSize.text = if (upload.size > 0) "$sizeStr · 点击下载" else "点击下载"
                rowBinding.btnAttachmentAction.text = "下载"
                rowBinding.btnAttachmentAction.setIconResource(R.drawable.ic_download)
            }
        }
    }

    private fun handleSingleUploadClick(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        holder: UploadRowHolder,
        client: TronClassClient,
        cacheManager: TronClassCacheManager,
        activityItem: TronClassActivityItem,
        courseId: Long,
        courseName: String,
        onRowStateChanged: () -> Unit
    ) {
        val upload = holder.upload
        val safeName = upload.name?.trim().takeIf { !it.isNullOrBlank() } ?: activityItem.getCleanTitle()

        if (isVideoUpload(upload)) {
            TronClassVideoPlayerActivity.start(
                context = context,
                activityId = activityItem.id,
                courseId = courseId,
                title = safeName,
                courseName = courseName,
                uploadId = upload.id,
                uploadName = upload.name
            )
            return
        }

        val cachedFile = cacheManager.getExistingFile(upload.id, upload.name)
            ?.takeIf { it.exists() && it.length() > 0 }

        if (TronClassImageHelper.isImageUrl(upload.name)) {
            lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                client.logActivityRead(activityItem.id, upload.id)
            }
            val fileUrl = if (cachedFile != null) "file://${cachedFile.absolutePath}" else "https://1906.usst.edu.cn/api/uploads/${upload.id}"
            TronClassImageHelper.showFullScreenPreview(
                context = context,
                imageUrl = fileUrl,
                title = safeName,
                courseId = courseId,
                courseName = courseName
            )
            return
        }

        if (cachedFile != null) {
            lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                client.logActivityRead(activityItem.id, upload.id)
            }
            cacheManager.openFile(cachedFile)
            return
        }

        val destFile = cacheManager.getFile(upload.id, upload.name)
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(context).apply {
            setTitle("正在下载课件")
            setMessage("$safeName\n准备开始...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = client.downloadAttachment(upload.id, destFile) { progressPercent ->
                (context as? Activity)?.runOnUiThread {
                    progressDialog.progress = progressPercent
                    progressDialog.setMessage("$safeName\n已下载: $progressPercent%")
                }
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (result.isSuccess) {
                    recordDownloadAndRead(
                        context = context,
                        lifecycleOwner = lifecycleOwner,
                        client = client,
                        upload = upload,
                        destFile = destFile,
                        activityId = activityItem.id,
                        courseId = courseId,
                        courseName = courseName
                    )
                    updateRowActionState(context, holder, cacheManager)
                    onRowStateChanged()
                    cacheManager.openFile(destFile)
                } else {
                    val err = result.exceptionOrNull()?.message ?: "网络连接超时"
                    Toast.makeText(context, "下载失败: $err", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun downloadAllUncached(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        client: TronClassClient,
        cacheManager: TronClassCacheManager,
        activityItem: TronClassActivityItem,
        targets: List<TronClassUpload>,
        rowHolders: List<UploadRowHolder>,
        courseId: Long,
        courseName: String,
        onFinished: () -> Unit
    ) {
        if (targets.isEmpty()) return

        var downloadJob: Job? = null
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(context).apply {
            setTitle("批量下载课件")
            setMessage("准备下载 ${targets.size} 个文件...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            setButton(DialogInterface.BUTTON_NEGATIVE, "取消") { d, _ ->
                downloadJob?.cancel()
                d.dismiss()
            }
            show()
        }

        downloadJob = lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            var failCount = 0
            val total = targets.size

            try {
                for ((index, upload) in targets.withIndex()) {
                    val safeName = upload.name?.trim().takeIf { !it.isNullOrBlank() } ?: "附件_${upload.id}"
                    val destFile = cacheManager.getFile(upload.id, upload.name)

                    withContext(Dispatchers.Main) {
                        progressDialog.progress = 0
                        progressDialog.setMessage("正在下载 (${index + 1}/$total)\n$safeName · 0%")
                    }

                    val res = client.downloadAttachment(upload.id, destFile) { percent ->
                        (context as? Activity)?.runOnUiThread {
                            progressDialog.progress = percent
                            progressDialog.setMessage("正在下载 (${index + 1}/$total)\n$safeName · $percent%")
                        }
                    }

                    if (res.isSuccess) {
                        successCount++
                        withContext(Dispatchers.Main) {
                            recordDownloadAndRead(
                                context = context,
                                lifecycleOwner = lifecycleOwner,
                                client = client,
                                upload = upload,
                                destFile = destFile,
                                activityId = activityItem.id,
                                courseId = courseId,
                                courseName = courseName
                            )
                            rowHolders.find { it.upload.id == upload.id }?.let {
                                updateRowActionState(context, it, cacheManager)
                            }
                            onFinished()
                        }
                    } else {
                        failCount++
                    }
                }

                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    val msg = when {
                        failCount == 0 -> "已全部下载完成（共 $successCount 个文件）"
                        successCount == 0 -> "下载失败，请检查网络后重试"
                        else -> "下载完成：成功 $successCount 个，失败 $failCount 个"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                    onFinished()
                }
            } catch (_: CancellationException) {
                withContext(Dispatchers.Main) {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    Toast.makeText(context, "已取消批量下载（已完成 $successCount/$total）", Toast.LENGTH_SHORT).show()
                    onFinished()
                }
            }
        }
    }

    private fun recordDownloadAndRead(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        client: TronClassClient,
        upload: TronClassUpload,
        destFile: java.io.File,
        activityId: Long,
        courseId: Long,
        courseName: String
    ) {
        TronClassDownloadManager.saveDownloadedItem(
            context,
            TronClassDownloadedItem(
                fileId = upload.id.toString(),
                fileName = upload.name ?: "课件",
                courseId = courseId,
                courseName = courseName,
                filePath = destFile.absolutePath,
                fileSize = if (destFile.length() > 0) destFile.length() else upload.size
            )
        )
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            client.logActivityRead(activityId, upload.id)
        }
    }

    private fun isVideoUpload(upload: TronClassUpload): Boolean {
        if (upload.type.equals("video", ignoreCase = true)) return true
        val ext = upload.getExtension().lowercase(Locale.ROOT)
        return ext in setOf("mp4", "mov", "mkv", "avi", "flv", "wmv", "m4v")
    }
}
