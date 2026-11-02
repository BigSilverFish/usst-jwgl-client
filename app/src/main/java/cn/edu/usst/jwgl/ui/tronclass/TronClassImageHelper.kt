package cn.edu.usst.jwgl.ui.tronclass

import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.TronClassDownloadManager
import cn.edu.usst.jwgl.data.local.TronClassDownloadedItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import cn.edu.usst.jwgl.ui.common.ZoomableImageView
import java.util.Locale

/**
 * 专为一网畅学打造的图片管理、多级手势缩放全屏预览与相册下载帮助类
 */
object TronClassImageHelper {

    private const val TAG = "TronClassImageHelper"
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 判断给定文件名或 URL 是否属于图片格式
     */
    fun isImageUrl(urlOrName: String?): Boolean {
        if (urlOrName.isNullOrBlank()) return false
        val clean = urlOrName.substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return clean.endsWith(".jpg") ||
                clean.endsWith(".jpeg") ||
                clean.endsWith(".png") ||
                clean.endsWith(".gif") ||
                clean.endsWith(".webp") ||
                clean.endsWith(".bmp")
    }

    /**
     * 根据图片字节流特征码（Magic Numbers）或 URL 猜测扩展名与 MIME 类型
     */
    fun detectImageFormat(bytes: ByteArray?, fallbackUrl: String? = null): Pair<String, String> {
        if (bytes != null && bytes.size >= 4) {
            // PNG: 89 50 4E 47
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) {
                return "png" to "image/png"
            }
            // JPEG: FF D8 FF
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
                return "jpg" to "image/jpeg"
            }
            // GIF: 47 49 46 38
            if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()) {
                return "gif" to "image/gif"
            }
            // WEBP: RIFF....WEBP
            if (bytes.size >= 12 &&
                bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
            ) {
                return "webp" to "image/webp"
            }
        }

        val clean = (fallbackUrl ?: "").substringBefore('?').substringBefore('#').lowercase(Locale.ROOT)
        return when {
            clean.endsWith(".png") -> "png" to "image/png"
            clean.endsWith(".gif") -> "gif" to "image/gif"
            clean.endsWith(".webp") -> "webp" to "image/webp"
            clean.endsWith(".bmp") -> "bmp" to "image/bmp"
            else -> "jpg" to "image/jpeg"
        }
    }

    /**
     * 将图片下载并保存至系统相册（Pictures/TronClass），通知媒体库刷新并同步登记到已下载管理列表
     */
    fun saveImageToGallery(
        context: Context,
        imageUrl: String,
        customFileName: String? = null,
        courseId: Long = 0L,
        courseName: String = "",
        onComplete: ((success: Boolean, message: String) -> Unit)? = null
    ) {
        val appCtx = context.applicationContext
        Toast.makeText(appCtx, "正在下载图片...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = TronClassImageLoader.getOrFetchImageBytes(appCtx, imageUrl)
                if (bytes == null || bytes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "下载图片失败，请检查网络连接", Toast.LENGTH_SHORT).show()
                        onComplete?.invoke(false, "下载失败")
                    }
                    return@launch
                }

                val (ext, mimeType) = detectImageFormat(bytes, imageUrl)
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val safeBaseName = if (!customFileName.isNullOrBlank()) {
                    customFileName.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
                } else {
                    "TronClass_$timeStamp"
                }
                val fileName = if (safeBaseName.endsWith(".$ext", ignoreCase = true)) safeBaseName else "$safeBaseName.$ext"

                var finalPath = ""
                var saveSuccess = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TronClass")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }

                    val resolver = appCtx.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { os ->
                            os.write(bytes)
                            os.flush()
                        }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)

                        saveSuccess = true
                        finalPath = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                            "TronClass/$fileName"
                        ).absolutePath
                    }
                } else {
                    // Android 9 及以下（例如 Nexus 9 等平板设备）
                    val publicPictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                    val targetDir = File(publicPictures, "TronClass").apply {
                        if (!exists()) mkdirs()
                    }

                    val destFile = if (targetDir.exists() && targetDir.canWrite()) {
                        File(targetDir, fileName)
                    } else {
                        val fallbackDir = appCtx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(appCtx.filesDir, "Pictures")
                        fallbackDir.mkdirs()
                        File(fallbackDir, fileName)
                    }

                    FileOutputStream(destFile).use { os ->
                        os.write(bytes)
                        os.flush()
                    }

                    MediaScannerConnection.scanFile(
                        appCtx,
                        arrayOf(destFile.absolutePath),
                        arrayOf(mimeType),
                        null
                    )
                    saveSuccess = true
                    finalPath = destFile.absolutePath
                }

                if (saveSuccess) {
                    // 同步加入已下载列表记录
                    val md5Key = TronClassImageLoader.md5(imageUrl)
                    TronClassDownloadManager.saveDownloadedItem(
                        appCtx,
                        TronClassDownloadedItem(
                            fileId = md5Key,
                            fileName = fileName,
                            courseId = courseId,
                            courseName = courseName.ifBlank { "图片资料" },
                            filePath = finalPath,
                            fileSize = bytes.size.toLong(),
                            mimeType = mimeType
                        )
                    )

                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "图片已保存至系统相册 (Pictures/TronClass)", Toast.LENGTH_SHORT).show()
                        onComplete?.invoke(true, finalPath)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "保存图片失败", Toast.LENGTH_SHORT).show()
                        onComplete?.invoke(false, "保存失败")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "saveImageToGallery failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "保存图片发生异常: ${e.message}", Toast.LENGTH_SHORT).show()
                    onComplete?.invoke(false, e.message ?: "未知异常")
                }
            }
        }
    }

    /**
     * 调起系统分享面板分享图片
     */
    fun shareImage(context: Context, imageUrl: String, title: String? = null) {
        val appCtx = context.applicationContext
        Toast.makeText(appCtx, "正在准备分享图片...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val bytes = TronClassImageLoader.getOrFetchImageBytes(appCtx, imageUrl)
                if (bytes == null || bytes.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(appCtx, "图片获取失败，无法分享", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val (ext, mimeType) = detectImageFormat(bytes, imageUrl)
                val shareDir = File(appCtx.cacheDir, "tronclass_images").apply { if (!exists()) mkdirs() }
                val shareFile = File(shareDir, "share_${TronClassImageLoader.md5(imageUrl)}.$ext")
                FileOutputStream(shareFile).use { it.write(bytes) }

                val authority = "${appCtx.packageName}.fileprovider"
                val contentUri: Uri = FileProvider.getUriForFile(appCtx, authority, shareFile)

                withContext(Dispatchers.Main) {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(Intent.EXTRA_STREAM, contentUri)
                        putExtra(Intent.EXTRA_SUBJECT, title ?: "畅课图片")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "分享图片"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "shareImage failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 长按图片时弹出的快捷操作菜单（保存到相册、分享、查看大图）
     */
    fun showImageOptionsDialog(
        context: Context,
        imageUrl: String,
        title: String? = null,
        courseId: Long = 0L,
        courseName: String = "",
        onPreview: (() -> Unit)? = null
    ) {
        val items = arrayOf("📥 保存图片到相册", "🔗 分享图片", "🔍 查看高清大图")
        MaterialAlertDialogBuilder(context)
            .setTitle(title?.ifBlank { "图片选项" } ?: "图片选项")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> saveImageToGallery(context, imageUrl, courseId = courseId, courseName = courseName)
                    1 -> shareImage(context, imageUrl, title)
                    2 -> {
                        if (onPreview != null) {
                            onPreview.invoke()
                        } else {
                            showFullScreenPreview(context, imageUrl, title, courseId, courseName)
                        }
                    }
                }
            }
            .show()
    }

    /**
     * 展示支持双指缩放、平移拖动、长按下载与顶部快捷按钮的沉浸式全屏画廊弹窗
     */
    fun showFullScreenPreview(
        context: Context,
        imageUrl: String,
        title: String? = null,
        courseId: Long = 0L,
        courseName: String = ""
    ) {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val rootFrame = FrameLayout(context).apply {
            setBackgroundColor(0xFF000000.toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. 手势缩放图片控件
        val zoomableImageView = ZoomableImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootFrame.addView(zoomableImageView)

        // 2. 加载进度菊花
        val progressBar = ProgressBar(context).apply {
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.CENTER
            }
        }
        rootFrame.addView(progressBar)

        // 3. 顶部半透明导航/操作栏
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(12), dp(28), dp(12), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
            }
        }

        // 返回/关闭按钮
        val btnClose = ImageButton(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener { dialog.dismiss() }
        }
        topBar.addView(btnClose)

        // 标题文本
        val tvTitle = TextView(context).apply {
            text = title?.ifBlank { "图片预览" } ?: "图片预览"
            setTextColor(Color.WHITE)
            textSize = 15f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(10), 0, dp(10), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topBar.addView(tvTitle)

        // 分享按钮
        val btnShare = ImageButton(context).apply {
            setImageResource(R.drawable.ic_share)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(6)
            }
            setOnClickListener {
                shareImage(context, imageUrl, title)
            }
        }
        topBar.addView(btnShare)

        // 保存/下载按钮
        val btnDownload = ImageButton(context).apply {
            setImageResource(R.drawable.ic_download)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener {
                saveImageToGallery(context, imageUrl, courseId = courseId, courseName = courseName)
            }
        }
        topBar.addView(btnDownload)

        rootFrame.addView(topBar)

        // 单击图片隐藏/显示顶部操作栏
        zoomableImageView.onSingleTapListener = {
            topBar.visibility = if (topBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // 长按图片弹出保存与分享弹窗
        zoomableImageView.onLongPressListener = {
            showImageOptionsDialog(context, imageUrl, title, courseId, courseName)
        }

        dialog.setContentView(rootFrame)
        dialog.show()

        // 异步加载原图并渲染
        TronClassImageLoader.load(zoomableImageView, imageUrl, context) {
            progressBar.visibility = View.GONE
        }
    }
}
