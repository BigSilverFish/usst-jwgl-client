package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import cn.edu.usst.jwgl.data.network.TronClassClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

object TronClassImageLoader {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val activeJobs = ConcurrentHashMap<ImageView, Job>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var okHttpClient: OkHttpClient = OkHttpClient.Builder().build()

    fun initClient(client: OkHttpClient) {
        this.okHttpClient = client
    }

    /**
     * 异步加载图片到指定的 ImageView
     */
    fun load(
        imageView: ImageView,
        url: String,
        context: Context,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
        onSuccess: ((Bitmap) -> Unit)? = null
    ) {
        val cached = memoryCache.get(url)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            imageView.visibility = View.VISIBLE
            onSuccess?.invoke(cached)
            return
        }

        activeJobs[imageView]?.cancel()
        imageView.tag = url

        val job = CoroutineScope(Dispatchers.IO).launch {
            val bitmap = getOrFetchBitmap(context, url, targetWidth, targetHeight)
            if (bitmap != null) {
                memoryCache.put(url, bitmap)
                withContext(Dispatchers.Main) {
                    if (imageView.tag == url) {
                        imageView.setImageBitmap(bitmap)
                        imageView.visibility = View.VISIBLE
                        onSuccess?.invoke(bitmap)
                    }
                }
            }
        }
        activeJobs[imageView] = job
    }

    suspend fun getOrFetchBitmap(
        context: Context,
        url: String,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cached = memoryCache.get(url)
        if (cached != null) return@withContext cached

        val diskFile = getDiskCacheFile(context, url)
        if (diskFile.exists() && diskFile.length() > 0) {
            val b = decodeSampledBitmap(diskFile.absolutePath, targetWidth, targetHeight)
            if (b != null) {
                memoryCache.put(url, b)
                return@withContext b
            }
        }

        // 从公网 / 畅课服务器拉取
        try {
            val tcClient = TronClassClient(context)
            val reqBuilder = Request.Builder().url(url)
            val uri = runCatching { Uri.parse(url) }.getOrNull()
            val host = uri?.host?.lowercase() ?: ""
            val isHttps = "https".equals(uri?.scheme, ignoreCase = true)
            val isTrustedHost = host == "1906.usst.edu.cn" ||
                                host.endsWith(".usst.edu.cn") ||
                                host.endsWith(".tronclass.com.cn") ||
                                host == "tronclass.com.cn"

            if (isHttps && isTrustedHost) {
                val sessionId = tcClient.getSessionId()
                if (!sessionId.isNullOrBlank()) {
                    reqBuilder.header("x-session-id", sessionId)
                    reqBuilder.header("Cookie", "session=$sessionId; x-session-id=$sessionId")
                }
                reqBuilder.header("Referer", "https://1906.usst.edu.cn/")
            }
            reqBuilder.header("User-Agent", "Mozilla/5.0 (Linux; Android; TronClass)")

            val resp = okHttpClient.newCall(reqBuilder.build()).execute()
            if (!resp.isSuccessful) return@withContext null

            val bytes = resp.body?.bytes() ?: return@withContext null

            // 写入本地缓存文件
            try {
                diskFile.parentFile?.mkdirs()
                FileOutputStream(diskFile).use { it.write(bytes) }
            } catch (e: Exception) {
                // Ignore cache write error
            }

            val b = decodeSampledBitmapFromByteArray(bytes, targetWidth, targetHeight)
            if (b != null) {
                memoryCache.put(url, b)
            }
            return@withContext b
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeSampledBitmap(filePath: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        if (reqWidth <= 0 || reqHeight <= 0) {
            return BitmapFactory.decodeFile(filePath)
        }
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(filePath, options)

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(filePath, options)
    }

    private fun decodeSampledBitmapFromByteArray(data: ByteArray, reqWidth: Int, reqHeight: Int): Bitmap? {
        if (reqWidth <= 0 || reqHeight <= 0) {
            return BitmapFactory.decodeByteArray(data, 0, data.size)
        }
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun getDiskCacheFile(context: Context, url: String): File {
        val cacheDir = File(context.cacheDir, "tronclass_images")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val hash = md5(url)
        return File(cacheDir, "$hash.img")
    }

    private fun md5(str: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digested = md.digest(str.toByteArray())
        return digested.joinToString("") { "%02x".format(it) }
    }
}
