package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.network.SharedRequest
import cn.edu.usst.jwgl.data.network.TronClassTransport
import cn.edu.usst.jwgl.data.network.awaitBuffered
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.WeakHashMap

object TronClassImageLoader {
    private val memoryCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8 / 1024).toInt()) {
        override fun sizeOf(key: String, bitmap: Bitmap) = maxOf(1, bitmap.byteCount / 1024)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = SharedRequest<ByteArray?>(scope)
    private val bitmaps = SharedRequest<Bitmap?>(scope)
    private data class Binding(val job: Job, val listener: View.OnAttachStateChangeListener)
    // Accessed on Main. The job also holds only a weak reference to its target.
    private val bindings = WeakHashMap<ImageView, Binding>()
    private var okHttpClient: OkHttpClient = TronClassTransport.client

    fun initClient(client: OkHttpClient) { okHttpClient = client }

    fun clear(imageView: ImageView) {
        bindings.remove(imageView)?.let {
            imageView.removeOnAttachStateChangeListener(it.listener)
            it.job.cancel()
        }
        imageView.tag = null
        imageView.setImageDrawable(null)
    }

    fun load(imageView: ImageView, url: String, context: Context, targetWidth: Int = 0,
             targetHeight: Int = 0, onSuccess: ((Bitmap) -> Unit)? = null) {
        clear(imageView)
        val app = context.applicationContext
        val account = AuthPreferences.currentStudentId(app)
        val width = targetWidth.takeIf { it > 0 } ?: imageView.width.takeIf { it > 0 }
            ?: imageView.layoutParams?.width?.takeIf { it > 0 } ?: app.resources.displayMetrics.widthPixels
        val height = targetHeight.takeIf { it > 0 } ?: imageView.height.takeIf { it > 0 } ?: width
        val key = "$account\n$url\n${width}x$height"
        val token = Any()
        imageView.tag = token
        memoryCache.get(key)?.let {
            imageView.setImageBitmap(it)
            imageView.visibility = View.VISIBLE
            onSuccess?.invoke(it)
            return
        }
        val target = WeakReference(imageView)
        var detached = false
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (!detached) return
                v.removeOnAttachStateChangeListener(this)
                if (v.tag === token) load(v as ImageView, url, app, width, height, onSuccess)
            }
            override fun onViewDetachedFromWindow(v: View) {
                if (v.tag !== token) {
                    v.removeOnAttachStateChangeListener(this)
                    return
                }
                detached = true
                bindings.remove(v as ImageView)?.job?.cancel()
                // The view owns this listener, so a RecyclerView cached holder can resume
                // without a rebind. clear()/a new load changes the token and invalidates it.
                v.setImageDrawable(null)
            }
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val bitmap = getOrFetchBitmap(app, url, width, height) ?: return@launch
                withContext(Dispatchers.Main.immediate) {
                    val view = target.get()
                    if (view?.tag === token && account == AuthPreferences.currentStudentId(app)) {
                        view.setImageBitmap(bitmap)
                        view.visibility = View.VISIBLE
                        onSuccess?.invoke(bitmap)
                    }
                }
            } finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    target.get()?.let { view ->
                        if (view.tag === token) {
                            bindings.remove(view)
                            if (!detached) view.removeOnAttachStateChangeListener(listener)
                        }
                    }
                }
            }
        }
        bindings[imageView] = Binding(job, listener)
        imageView.addOnAttachStateChangeListener(listener)
        job.start()
    }

    suspend fun getOrFetchBitmap(context: Context, url: String, targetWidth: Int = 0,
                                targetHeight: Int = 0): Bitmap? = withContext(Dispatchers.IO) {
        val account = AuthPreferences.currentStudentId(context)
        val key = "$account\n$url\n${targetWidth}x$targetHeight"
        memoryCache.get(key) ?: bitmaps.await(key) {
            val bytes = fetchImageBytes(context.applicationContext, url, account) ?: return@await null
            currentCoroutineContext().ensureActive()
            val options = BitmapFactory.Options()
            if (targetWidth > 0 && targetHeight > 0) {
                options.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                var sample = 1
                while (options.outWidth / (sample * 2) >= targetWidth && options.outHeight / (sample * 2) >= targetHeight) sample *= 2
                options.inSampleSize = sample
                options.inJustDecodeBounds = false
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { memoryCache.put(key, it) }
        }
    }

    suspend fun getOrFetchImageBytes(context: Context, url: String): ByteArray? =
        fetchImageBytes(context.applicationContext, url, AuthPreferences.currentStudentId(context))

    private suspend fun fetchImageBytes(context: Context, url: String, account: String): ByteArray? = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        downloads.await("$account\n$url") {
            val file = diskFile(app, url, account)
            if (file.isFile && file.length() > 0) {
                runCatching { file.readBytes() }.getOrNull()?.let { return@await it }
            }
            try {
                val builder = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android; TronClass)")
                val uri = Uri.parse(url)
                val host = uri.host?.lowercase().orEmpty()
                if (uri.scheme.equals("https", true) && (host.endsWith(".usst.edu.cn") || host == "1906.usst.edu.cn" ||
                        host.endsWith(".tronclass.com.cn") || host == "tronclass.com.cn")) {
                    TronClassPreferences(app).getSessionId(account)?.takeIf { it.isNotBlank() }?.let {
                        builder.header("x-session-id", it).header("Cookie", "session=$it; x-session-id=$it")
                    }
                    builder.header("Referer", "https://1906.usst.edu.cn/")
                }
                val response = okHttpClient.newCall(builder.build()).awaitBuffered()
                if (response.code !in 200..299) return@await null
                currentCoroutineContext().ensureActive()
                runCatching { file.writeBytes(response.bytes) }
                response.bytes
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { null }
        }
    }

    fun getDiskCacheFile(context: Context, url: String): File =
        diskFile(context, url, AuthPreferences.currentStudentId(context))

    private fun diskFile(context: Context, url: String, account: String): File {
        val directory = File(context.cacheDir, "tronclass_images").apply { mkdirs() }
        return File(directory, "${md5("$account\n$url")}.img")
    }

    fun md5(str: String): String = MessageDigest.getInstance("MD5").digest(str.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
