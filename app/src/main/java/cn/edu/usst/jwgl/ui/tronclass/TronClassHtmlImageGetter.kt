package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.Html
import android.util.Log
import android.util.LruCache
import android.widget.TextView
import cn.edu.usst.jwgl.data.network.TronClassClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 专用于 TronClass 富文本 HTML 题干与选项中 <img> 标签的异步图片加载器。
 * 内置多级缓存（LruCache 内存缓存 + 本地磁盘缓存），支持自适应大屏缩放，
 * 彻底消除 Android 原生 Html.fromHtml 无法加载图片导致的未知图标方块。
 */
class TronClassHtmlImageGetter(
    private val textView: TextView,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) : Html.ImageGetter {

    companion object {
        private const val TAG = "TronClassImageGetter"

        // 内存缓存：使用应用最大内存的 1/8
        private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        private val cacheSize = (maxMemory / 8).coerceAtLeast(1024 * 8)
        private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }
    }

    override fun getDrawable(source: String?): Drawable {
        if (source.isNullOrBlank()) {
            return ColorDrawable(Color.TRANSPARENT).apply { setBounds(0, 0, 1, 1) }
        }

        val context = textView.context
        val fullUrl = resolveFullUrl(source)

        // 1. 尝试从内存缓存获取 (0ms 瞬时返回)
        val memBitmap = memoryCache.get(fullUrl)
        if (memBitmap != null && !memBitmap.isRecycled) {
            return createSizedDrawable(context, memBitmap)
        }

        // 2. 尝试从磁盘缓存获取
        val diskFile = getDiskCacheFile(context, fullUrl)
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                val diskBitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (diskBitmap != null) {
                    memoryCache.put(fullUrl, diskBitmap)
                    return createSizedDrawable(context, diskBitmap)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to decode disk cache for $fullUrl", e)
            }
        }

        // 3. 首次加载：返回占位 Drawable，并启动协程异步下载
        val density = context.resources.displayMetrics.density
        val placeholder = ColorDrawable(Color.parseColor("#F0F2F5")).apply {
            setBounds(0, 0, (120 * density).toInt(), (60 * density).toInt())
        }

        scope.launch(Dispatchers.IO) {
            try {
                val client = TronClassClient(context).getOkHttpClient()
                val req = Request.Builder()
                    .url(fullUrl)
                    .header("User-Agent", "Mozilla/5.0 USST-TronClass-Android")
                    .build()

                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body ?: return@launch
                    val bytes = body.bytes()
                    if (bytes.isNotEmpty()) {
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            memoryCache.put(fullUrl, bmp)
                            try {
                                diskFile.parentFile?.mkdirs()
                                diskFile.writeBytes(bytes)
                            } catch (_: Throwable) {}

                            withContext(Dispatchers.Main) {
                                // 检查当前 TextView 是否仍然绑定该内容 (防 RecyclerView 复用错位)
                                val currentTag = textView.tag as? String
                                if (currentTag != null) {
                                    val newSpanned = Html.fromHtml(
                                        currentTag,
                                        Html.FROM_HTML_MODE_LEGACY,
                                        this@TronClassHtmlImageGetter,
                                        null
                                    )
                                    textView.text = newSpanned
                                    textView.requestLayout()
                                    textView.invalidate()
                                }
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error fetching rich content image from $fullUrl", e)
            }
        }

        return placeholder
    }

    private fun resolveFullUrl(source: String): String {
        return when {
            source.startsWith("http://") || source.startsWith("https://") -> source
            source.startsWith("/") -> "${TronClassClient.BASE_URL}$source"
            else -> "${TronClassClient.BASE_URL}/$source"
        }
    }

    private fun createSizedDrawable(context: Context, bitmap: Bitmap): Drawable {
        val res = context.resources
        val density = res.displayMetrics.density

        // 测算 TextView 可用宽度 (平板大屏优化)
        val viewWidth = textView.width
        val maxAvailableWidth = if (viewWidth > 0) {
            viewWidth - textView.paddingLeft - textView.paddingRight
        } else {
            // 预估宽度：屏幕宽度减去外边距 (如平板大屏上留出合理边距)
            (res.displayMetrics.widthPixels - (80 * density).toInt()).coerceAtLeast(300)
        }

        val origW = bitmap.width
        val origH = bitmap.height

        val (targetWidth, targetHeight) = if (maxAvailableWidth > 0 && origW > maxAvailableWidth) {
            // 超过最大可用宽度时等比缩小，避免超出屏幕
            val scale = maxAvailableWidth.toFloat() / origW
            Pair(maxAvailableWidth, (origH * scale).toInt())
        } else {
            // 原图适配 (若原图较小则按屏幕密度或原尺寸展示)
            val scaledW = (origW * density / 2).toInt().coerceAtLeast(origW)
            if (scaledW > maxAvailableWidth && maxAvailableWidth > 0) {
                val scale = maxAvailableWidth.toFloat() / origW
                Pair(maxAvailableWidth, (origH * scale).toInt())
            } else {
                Pair(scaledW, (origH * (scaledW.toFloat() / origW)).toInt())
            }
        }

        return BitmapDrawable(res, bitmap).apply {
            setBounds(0, 0, targetWidth.coerceAtLeast(1), targetHeight.coerceAtLeast(1))
        }
    }

    private fun getDiskCacheFile(context: Context, url: String): File {
        val hash = md5(url)
        val dir = File(context.cacheDir, "tc_rich_images")
        return File(dir, "$hash.img")
    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
