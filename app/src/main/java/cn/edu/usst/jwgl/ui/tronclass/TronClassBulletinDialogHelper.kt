package cn.edu.usst.jwgl.ui.tronclass

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.TronClassCacheManager
import cn.edu.usst.jwgl.data.local.TronClassDownloadManager
import cn.edu.usst.jwgl.data.local.TronClassDownloadedItem
import cn.edu.usst.jwgl.data.model.TronClassBulletin
import cn.edu.usst.jwgl.data.model.TronClassUpload
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.databinding.DialogTronclassBulletinDetailBinding
import cn.edu.usst.jwgl.databinding.ItemTronclassAttachmentBinding
import cn.edu.usst.jwgl.util.TabletLayoutHelper
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TronClass 课程公告详情与附件下载统一弹窗帮助类
 * 供课程详情页与一网畅学主页公告 Tab 统一复用
 */
object TronClassBulletinDialogHelper {

    fun showBulletinDetailDialog(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        client: TronClassClient,
        bulletin: TronClassBulletin,
        courseId: Long = bulletin.courseId,
        courseName: String = bulletin.courseName ?: ""
    ) {
        val dialog = BottomSheetDialog(context)
        val layoutInflater = LayoutInflater.from(context)
        val dialogBinding = DialogTronclassBulletinDetailBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        TabletLayoutHelper.setupBottomSheetForLargeScreen(dialog, context)

        val behavior = BottomSheetBehavior.from(dialogBinding.root.parent as View)
        behavior.skipCollapsed = true
        behavior.state = BottomSheetBehavior.STATE_EXPANDED

        dialogBinding.tvDetailTitle.text = bulletin.title ?: "课程公告"

        // 1. 格式化发布时间与课程来源
        val formattedDate = TronClassHtmlHelper.formatIsoDate(bulletin.createdAt)
        val dateText = if (formattedDate.isNotBlank()) formattedDate else (bulletin.createdAt ?: "")
        val cName = if (courseName.isNotBlank()) courseName else (bulletin.courseName ?: "")
        dialogBinding.tvDetailMeta.text = if (cName.isNotBlank()) "$cName · $dateText" else dateText

        // 2. 适配深浅色模式，WebView 设置透明底色，加载主题适配的 HTML
        dialogBinding.webViewContent.setBackgroundColor(Color.TRANSPARENT)
        val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val rawContent = bulletin.content ?: "<p>暂无公告内容</p>"
        val htmlData = TronClassHtmlHelper.buildThemedHtmlDocument(bulletin.title ?: "课程公告", rawContent, isDarkMode)

        dialogBinding.webViewContent.settings.javaScriptEnabled = false
        dialogBinding.webViewContent.loadDataWithBaseURL("https://1906.usst.edu.cn", htmlData, "text/html", "utf-8", null)

        val cacheManager = TronClassCacheManager(context)

        // 3. 收集并展示附件
        val allUploads = mutableListOf<TronClassUpload>()
        allUploads.addAll(bulletin.getAllAttachments())
        val embedded = TronClassHtmlHelper.extractEmbeddedUploads(bulletin.content)
        for (emb in embedded) {
            if (allUploads.none { it.id == emb.id }) {
                allUploads.add(emb)
            }
        }

        renderBulletinAttachments(
            context,
            lifecycleOwner,
            layoutInflater,
            dialogBinding,
            allUploads,
            cacheManager,
            client,
            courseId,
            cName
        )

        // 若当前列表返回的模型中附件为空，异步调用详情接口获取可能存在的 uploads / attachments
        if (bulletin.id > 0 && courseId > 0) {
            lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val detailRes = client.fetchBulletinDetail(bulletin.id, courseId)
                if (detailRes.isSuccess) {
                    val detail = detailRes.getOrNull()
                    val detailUploads = detail?.getAllAttachments() ?: emptyList()
                    if (detailUploads.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            var added = false
                            for (up in detailUploads) {
                                if (allUploads.none { it.id == up.id }) {
                                    allUploads.add(up)
                                    added = true
                                }
                            }
                            if (added) {
                                renderBulletinAttachments(
                                    context,
                                    lifecycleOwner,
                                    layoutInflater,
                                    dialogBinding,
                                    allUploads,
                                    cacheManager,
                                    client,
                                    courseId,
                                    cName
                                )
                            }
                        }
                    }
                }
            }
        }

        // 4. 支持在 WebView 中点击超链接形式的附件进行直接下载
        dialogBinding.webViewContent.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url?.toString() ?: return false
                val uploadId = Regex("""/(?:uploads|attachments)/(\d+)""").find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()
                if (uploadId != null) {
                    val upload = allUploads.find { it.id == uploadId }
                        ?: TronClassUpload(id = uploadId, name = url.substringAfterLast("/").substringBefore("?").ifBlank { "附件_$uploadId" })
                    startDownload(context, lifecycleOwner, client, cacheManager, courseId, cName, upload)
                    return true
                }
                return false
            }
        }

        dialogBinding.btnDetailClose.setOnClickListener { dialog.dismiss() }
        dialogBinding.btnConfirm.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun renderBulletinAttachments(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        layoutInflater: LayoutInflater,
        dialogBinding: DialogTronclassBulletinDetailBinding,
        uploads: List<TronClassUpload>,
        cacheManager: TronClassCacheManager,
        client: TronClassClient,
        courseId: Long,
        courseName: String
    ) {
        if (uploads.isEmpty()) {
            dialogBinding.layoutAttachmentsSection.visibility = View.GONE
            return
        }

        dialogBinding.layoutAttachmentsSection.visibility = View.VISIBLE
        dialogBinding.tvAttachmentsHeader.text = "📎 附件下载 (${uploads.size})"
        dialogBinding.llAttachmentsContainer.removeAllViews()

        for (upload in uploads) {
            val itemBinding = ItemTronclassAttachmentBinding.inflate(layoutInflater, dialogBinding.llAttachmentsContainer, false)

            val safeName = upload.name ?: "附件_${upload.id}"
            itemBinding.tvFileName.text = safeName

            // 区分文件图标
            val ext = upload.getExtension()
            if (ext == "pdf") {
                itemBinding.ivFileIcon.setImageResource(R.drawable.ic_file_pdf)
            } else {
                itemBinding.ivFileIcon.setImageResource(R.drawable.ic_file_doc)
            }

            val sizeStr = upload.getFormattedSize()
            val isCached = cacheManager.isCached(upload.id, upload.name)

            if (isCached) {
                itemBinding.tvFileSize.text = if (upload.size > 0) "$sizeStr · 已缓存到本地" else "已缓存到本地"
                itemBinding.btnFileAction.text = "打开"
            } else {
                itemBinding.tvFileSize.text = if (upload.size > 0) "$sizeStr · 点击下载" else "点击下载"
                itemBinding.btnFileAction.text = "下载"
            }

            fun doAction() {
                val existing = cacheManager.getExistingFile(upload.id, upload.name)
                if (existing != null) {
                    cacheManager.openFile(existing)
                } else {
                    startDownload(context, lifecycleOwner, client, cacheManager, courseId, courseName, upload) {
                        itemBinding.tvFileSize.text = if (upload.size > 0) "$sizeStr · 已缓存到本地" else "已缓存到本地"
                        itemBinding.btnFileAction.text = "打开"
                    }
                }
            }

            itemBinding.btnFileAction.setOnClickListener { doAction() }
            itemBinding.root.setOnClickListener { doAction() }

            dialogBinding.llAttachmentsContainer.addView(itemBinding.root)
        }
    }

    private fun startDownload(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        client: TronClassClient,
        cacheManager: TronClassCacheManager,
        courseId: Long,
        courseName: String,
        upload: TronClassUpload,
        onCompleted: (() -> Unit)? = null
    ) {
        val destFile = cacheManager.getFile(upload.id, upload.name)

        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(context).apply {
            setTitle("正在下载公告附件")
            setMessage((upload.name ?: "文件") + "\n准备开始...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = client.downloadAttachment(upload.id, destFile) { progressPercent ->
                (context as? Activity)?.runOnUiThread {
                    progressDialog.progress = progressPercent
                    progressDialog.setMessage((upload.name ?: "文件") + "\n已下载: $progressPercent%")
                }
            }

            withContext(Dispatchers.Main) {
                progressDialog.dismiss()
                if (result.isSuccess) {
                    TronClassDownloadManager.saveDownloadedItem(
                        context,
                        TronClassDownloadedItem(
                            fileId = upload.id.toString(),
                            fileName = upload.name ?: "公告附件",
                            courseId = courseId,
                            courseName = courseName,
                            filePath = destFile.absolutePath,
                            fileSize = if (destFile.length() > 0) destFile.length() else upload.size
                        )
                    )

                    Toast.makeText(context, "下载成功！已保存至本地", Toast.LENGTH_SHORT).show()
                    onCompleted?.invoke()

                    MaterialAlertDialogBuilder(context)
                        .setTitle("附件下载完毕")
                        .setMessage("文件保存在：\n" + destFile.absolutePath + "\n\n是否立即用系统应用打开？")
                        .setPositiveButton("立即打开") { _, _ ->
                            cacheManager.openFile(destFile)
                        }
                        .setNegativeButton("稍后查看", null)
                        .show()
                } else {
                    val err = result.exceptionOrNull()?.message ?: "网络连接超时"
                    Toast.makeText(context, "下载失败: $err", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
