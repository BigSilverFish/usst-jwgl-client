package cn.edu.usst.jwgl.ui.tronclass

import android.app.ProgressDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.data.local.CoursewareStorageManager
import cn.edu.usst.jwgl.data.local.TronClassCacheManager
import cn.edu.usst.jwgl.data.model.ChaoXingAttachmentKind
import cn.edu.usst.jwgl.data.model.ChaoXingCourseAttachment
import cn.edu.usst.jwgl.data.network.ChaoXingCourseDetailClient
import cn.edu.usst.jwgl.ui.pdf.PdfViewerActivity
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * 超星章节与作业附件点击路由与原生下载辅助器
 */
internal object ChaoXingAttachmentActionHelper {

    fun handleAttachmentClick(
        fragment: Fragment,
        chaoXingDetailClient: ChaoXingCourseDetailClient,
        attachment: ChaoXingCourseAttachment,
        courseId: Long,
        courseName: String
    ) {
        val ctx = fragment.context ?: return
        @Suppress("DEPRECATION")
        val progress = ProgressDialog(ctx).apply {
            setMessage("正在解析「${attachment.name}」...")
            setCancelable(false)
            show()
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val res = try {
                chaoXingDetailClient.fetchYunFile(attachment.objectId)
            } finally {
                if (progress.isShowing) {
                    runCatching { progress.dismiss() }
                }
            }

            if (!fragment.isAdded) return@launch
            val activeCtx = fragment.context ?: return@launch

            if (res.isFailure) {
                Toast.makeText(activeCtx, "解析课件失败，请稍后重试", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val yun = res.getOrNull() ?: return@launch
            val effectiveName = yun.resolveBestFileName(attachment.name, attachment.resolveExtension())
            val effectiveExt = yun.fileExt.ifBlank { attachment.fileExt }
            val effectiveAttachment = attachment.copy(
                name = effectiveName,
                fileExt = effectiveExt
            )
            val kind = effectiveAttachment.resolveKind()

            when {
                kind == ChaoXingAttachmentKind.VIDEO || attachment.isVideo -> {
                    val streams = yun.getPreferredStreamUrls()
                    if (streams.isNotEmpty()) {
                        TronClassVideoPlayerActivity.start(
                            context = activeCtx,
                            title = effectiveName,
                            courseName = courseName,
                            videoUrl = streams.first(),
                            fallbackUrls = streams.drop(1),
                            mediaId = attachment.objectId,
                            referer = ChaoXingCourseDetailClient.CHAOXING_REFERER,
                            downloadUrl = yun.downloadUrl,
                            courseId = courseId
                        )
                    } else {
                        Toast.makeText(activeCtx, "该视频暂无可播放流地址", Toast.LENGTH_SHORT).show()
                    }
                }
                kind == ChaoXingAttachmentKind.IMAGE ||
                    TronClassImageHelper.isImageUrl(effectiveName) ||
                    TronClassImageHelper.isImageUrl(yun.downloadUrl) -> {
                    val imgUrl = yun.downloadUrl.ifBlank { yun.pdfUrl }
                    if (imgUrl.isNotBlank()) {
                        TronClassImageHelper.showFullScreenPreview(
                            activeCtx,
                            imgUrl,
                            title = effectiveName,
                            courseName = courseName
                        )
                    } else {
                        Toast.makeText(activeCtx, "图片资源未就绪", Toast.LENGTH_SHORT).show()
                    }
                }
                yun.pdfUrl.isNotBlank() ||
                    kind == ChaoXingAttachmentKind.PDF ||
                    yun.downloadUrl.substringBefore('?').endsWith(".pdf", ignoreCase = true) -> {
                    val targetPdfUrl = yun.pdfUrl.ifBlank { yun.downloadUrl }
                    if (targetPdfUrl.isNotBlank()) {
                        PdfViewerActivity.start(activeCtx, targetPdfUrl, effectiveName, courseName)
                    } else {
                        Toast.makeText(activeCtx, "该文档暂无可预览地址", Toast.LENGTH_SHORT).show()
                    }
                }
                yun.downloadUrl.isNotBlank() -> {
                    downloadAndOpenAttachment(
                        fragment = fragment,
                        ctx = activeCtx,
                        chaoXingDetailClient = chaoXingDetailClient,
                        objectId = attachment.objectId,
                        downloadUrl = yun.downloadUrl,
                        preferredName = effectiveName,
                        courseId = courseId,
                        courseName = courseName
                    )
                }
                else -> {
                    Toast.makeText(activeCtx, "该课件暂无可下载直链", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private suspend fun downloadAndOpenAttachment(
        fragment: Fragment,
        ctx: Context,
        chaoXingDetailClient: ChaoXingCourseDetailClient,
        objectId: String,
        downloadUrl: String,
        preferredName: String,
        courseId: Long,
        courseName: String
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        @Suppress("DEPRECATION")
        val downloadDialog = ProgressDialog(ctx).apply {
            setMessage("正在下载「$preferredName」...")
            setCancelable(false)
            show()
        }

        val dlRes = try {
            chaoXingDetailClient.downloadYunFile(
                url = downloadUrl,
                preferredFileName = preferredName,
                tempDir = File(ctx.cacheDir, "chaoxing_downloads")
            ) { pct ->
                mainHandler.post {
                    if (downloadDialog.isShowing) {
                        downloadDialog.setMessage("正在下载「$preferredName」... $pct%")
                    }
                }
            }
        } finally {
            if (downloadDialog.isShowing) {
                runCatching { downloadDialog.dismiss() }
            }
        }

        if (!fragment.isAdded) return
        val activeCtx = fragment.context ?: return

        if (dlRes.isFailure) {
            Toast.makeText(
                activeCtx,
                "下载失败: ${dlRes.exceptionOrNull()?.message ?: "请稍后重试"}",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val dl = dlRes.getOrThrow()
        val persisted = CoursewareStorageManager.persistDownloadedCourseware(
            context = activeCtx,
            sourceFile = dl.tempFile,
            uploadId = 0L,
            displayName = dl.resolvedFileName,
            courseId = courseId,
            courseName = courseName,
            removeSourceAfterExport = true,
            customFileId = "cx_$objectId",
            mimeTypeOverride = dl.mimeType
        )

        val saveMsg = persisted.warningMessage ?: "已保存至 ${persisted.locationLabel}"
        Toast.makeText(activeCtx, saveMsg, Toast.LENGTH_SHORT).show()

        val resolvedLower = dl.resolvedFileName.lowercase(Locale.ROOT)
        if (dl.mimeType == "application/pdf" || resolvedLower.endsWith(".pdf")) {
            PdfViewerActivity.start(activeCtx, persisted.filePathOrUri, dl.resolvedFileName, courseName)
        } else {
            TronClassCacheManager(activeCtx).openPersistResult(persisted, dl.resolvedFileName, courseName)
        }
    }
}
