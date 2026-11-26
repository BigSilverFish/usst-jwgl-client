package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import cn.edu.usst.jwgl.data.network.TronClassClient
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.WeakHashMap
import kotlin.coroutines.coroutineContext

object TronClassImageLoader {

    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val jobLock = Any()
    private val activeJobs = WeakHashMap<ImageView, Job>()
    private val detachListeners = WeakHashMap<ImageView, View.OnAttachStateChangeListener>()

    private val exceptionHandler = CoroutineExceptionHandler { _, e ->
        android.util.Log.e("TronClassImageLoader", "Unhandled image loading error", e)
    }
    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    private var okHttpClient: OkHttpClient = OkHttpClient.Builder().build()

    fun initClient(client: OkHttpClient) {
        this.okHttpClient = client
    }

    internal fun buildMemoryCacheKey(url: String, targetWidth: Int, targetHeight: Int): String {
        return if (targetWidth > 0 && targetHeight > 0) {
            "$url#${targetWidth}x$targetHeight"
        } else {
            "$url#orig"
        }
    }

    /**
     * 取消指定 ImageView 上正在进行的图片加载任务并解绑附着监听，避免视图复用错位或内存泄漏
     */
    fun cancel(imageView: ImageView) {
        val (job, listener) = synchronized(jobLock) {
            activeJobs.remove(imageView) to detachListeners.remove(imageView)
        }
        job?.cancel()
        if (listener != null) {
            imageView.removeOnAttachStateChangeListener(listener)
        }
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
        val cacheKey = buildMemoryCacheKey(url, targetWidth, targetHeight)
        cancel(imageView)
        imageView.tag = cacheKey

        val cached = memoryCache.get(cacheKey)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            imageView.visibility = View.VISIBLE
            onSuccess?.invoke(cached)
            return
        }

        val appContext = context.applicationContext
        val imageViewRef = WeakReference(imageView)
        val detachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                cancel(v as ImageView)
            }
        }

        val job = loaderScope.launch(start = CoroutineStart.LAZY) {
            val currentJob = coroutineContext[Job]
            try {
                val bitmap = getOrFetchBitmap(appContext, url, targetWidth, targetHeight)
                if (bitmap != null) {
                    withContext(Dispatchers.Main) {
                        val view = imageViewRef.get()
                        if (view != null && view.tag == cacheKey) {
                            view.setImageBitmap(bitmap)
                            view.visibility = View.VISIBLE
                            onSuccess?.invoke(bitmap)
                        }
                    }
                }
            } finally {
                val view = imageViewRef.get()
                val listenerToRemove = synchronized(jobLock) {
                    if (view != null && activeJobs[view] === currentJob) {
                        activeJobs.remove(view)
                        detachListeners.remove(view)
                    } else {
                        null
                    }
                }
                if (view != null && listenerToRemove != null) {
                    withContext(Dispatchers.Main + NonCancellable) {
                        view.removeOnAttachStateChangeListener(listenerToRemove)
                    }
                }
            }
        }

        synchronized(jobLock) {
            activeJobs[imageView] = job
            detachListeners[imageView] = detachListener
        }
        imageView.addOnAttachStateChangeListener(detachListener)
        job.start()
    }

    suspend fun getOrFetchBitmap(
        context: Context,
        url: String,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = buildMemoryCacheKey(url, targetWidth, targetHeight)
        val cached = memoryCache.get(cacheKey)
        if (cached != null) return@withContext cached

        val bytes = getOrFetchImageBytes(context, url) ?: return@withContext null
        val b = decodeSampledBitmapFromByteArray(bytes, targetWidth, targetHeight)
        if (b != null) {
            memoryCache.put(cacheKey, b)
        }
        return@withContext b
    }

    suspend fun getOrFetchImageBytes(
        context: Context,
        url: String
    ): ByteArray? = withContext(Dispatchers.IO) {
        val diskFile = getDiskCacheFile(context, url)
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                return@withContext diskFile.readBytes()
            } catch (e: Exception) {
                // fall through to re-fetch
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

            okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null

                val bytes = resp.body?.bytes() ?: return@withContext null

                // 写入本地缓存文件
                try {
                    diskFile.parentFile?.mkdirs()
                    FileOutputStream(diskFile).use { it.write(bytes) }
                } catch (e: Exception) {
                    // Ignore cache write error
                }

                return@withContext bytes
            }
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
        return calculateInSampleSize(options.outWidth, options.outHeight, reqWidth, reqHeight)
    }

    internal fun calculateInSampleSize(rawWidth: Int, rawHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        if (reqWidth <= 0 || reqHeight <= 0) return 1
        var inSampleSize = 1

        if (rawHeight > reqHeight || rawWidth > reqWidth) {
            val halfHeight: Int = rawHeight / 2
            val halfWidth: Int = rawWidth / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun getDiskCacheFile(context: Context, url: String): File {
        val cacheDir = File(context.cacheDir, "tronclass_images")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val hash = md5(url)
        return File(cacheDir, "$hash.img")
    }

    fun md5(str: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digested = md.digest(str.toByteArray())
        return digested.joinToString("") { "%02x".format(it) }
    }
}
