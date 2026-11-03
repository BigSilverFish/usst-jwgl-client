package cn.edu.usst.jwgl.ui.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.databinding.ActivityPdfViewerBinding
import cn.edu.usst.jwgl.databinding.ItemPdfPageBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * 原生全屏 PDF 阅读器（In-App Native PDF Viewer）
 * 基于 Android 原生 [PdfRenderer] 构建，零第三方依赖，支持分页连贯垂直滚动、双指/双击自由缩放、
 * 异步光栅化高清渲染、页码快速指示器与单触沉浸式全屏切换。
 */
class PdfViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPdfViewerBinding

    private var localPdfFile: File? = null
    private var isTemporaryDownload: Boolean = false
    private var documentTitle: String = ""

    private var parcelFileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private val rendererLock = Any()

    private var totalPages: Int = 0
    private var pageAspectRatios: FloatArray = FloatArray(0)

    private lateinit var bitmapCache: LruCache<Int, Bitmap>
    private lateinit var pageAdapter: PdfPageAdapter

    private val hideIndicatorHandler = Handler(Looper.getMainLooper())
    private val hideIndicatorRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            binding.tvPageIndicator.animate().alpha(0f).setDuration(250).withEndAction {
                binding.tvPageIndicator.visibility = View.GONE
            }.start()
        }
    }

    private var isToolbarVisible = true

    /**
     * 规范化更新当前文档标题，同步至 Activity Title、SupportActionBar 及 Toolbar
     */
    private fun updateDocumentTitle(titleText: String) {
        val clean = PdfViewerUtils.cleanFileName(titleText)
        if (clean.isNotBlank()) {
            documentTitle = clean
            title = clean
            supportActionBar?.title = clean
            binding.toolbarPdf.title = clean
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        if (documentTitle.isNotBlank()) {
            supportActionBar?.title = documentTitle
            binding.toolbarPdf.title = documentTitle
        }
    }

    override fun onTitleChanged(title: CharSequence?, color: Int) {
        super.onTitleChanged(title, color)
        if (documentTitle.isNotBlank()) {
            supportActionBar?.title = documentTitle
            binding.toolbarPdf.title = documentTitle
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()
        setupToolbar()
        setupBitmapCache()

        var rawPath = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        val dataUri = intent.data
        if (rawPath.isBlank() && dataUri != null) {
            rawPath = dataUri.toString()
        }

        // 初始标题解析
        val initialTitle = PdfViewerUtils.resolveTitle(
            explicitTitle = intent.getStringExtra(EXTRA_TITLE),
            rawPath = rawPath
        )
        updateDocumentTitle(initialTitle)
        binding.toolbarPdf.subtitle = "正在加载..."

        if (rawPath.isBlank()) {
            showError("未提供有效的 PDF 文件路径")
            return
        }

        initPdfSource(rawPath)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootPdfViewer) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            binding.appBarLayout.setPadding(0, statusBar.top, 0, 0)
            binding.rvPdfPages.setPadding(
                0,
                statusBar.top + dpToPx(56f),
                0,
                navBar.bottom + dpToPx(16f)
            )
            (binding.tvPageIndicator.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)?.apply {
                bottomMargin = navBar.bottom + dpToPx(24f)
            }
            insets
        }
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbarPdf)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        binding.toolbarPdf.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupBitmapCache() {
        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = (maxMemoryKb / 8).coerceIn(16 * 1024, 64 * 1024)
        bitmapCache = object : LruCache<Int, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: Int, value: Bitmap): Int {
                return value.byteCount / 1024
            }
        }
    }

    private fun initPdfSource(pathOrUrl: String) {
        if (pathOrUrl.startsWith("http://", ignoreCase = true) || pathOrUrl.startsWith("https://", ignoreCase = true)) {
            downloadRemotePdf(pathOrUrl)
        } else if (pathOrUrl.startsWith("content://", ignoreCase = true)) {
            openContentUriPdf(Uri.parse(pathOrUrl))
        } else {
            val file = if (pathOrUrl.startsWith("file://", ignoreCase = true)) {
                File(Uri.parse(pathOrUrl).path.orEmpty())
            } else {
                File(pathOrUrl)
            }
            if (!file.exists() || !file.canRead()) {
                showError("找不到指定的本地文件或无法读取:\n${file.absolutePath}")
                return
            }
            openLocalPdf(file)
        }
    }

    private fun openContentUriPdf(uri: Uri) {
        binding.pbPdfLoading.visibility = View.VISIBLE
        binding.toolbarPdf.subtitle = "正在加载文档..."

        // 尝试从 ContentResolver 查询真实文件名并及时更新标题
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val displayName = cursor.getString(nameIndex)
                        if (!displayName.isNullOrBlank() && PdfViewerUtils.isGenericTitle(documentTitle)) {
                            updateDocumentTitle(displayName)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tempDir = File(cacheDir, "pdf_preview").apply { if (!exists()) mkdirs() }
                val destFile = File(tempDir, "temp_${System.currentTimeMillis()}.pdf")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw Exception("无法从 URI 读取数据流")

                isTemporaryDownload = true
                withContext(Dispatchers.Main) {
                    openLocalPdf(destFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("读取文档失败:\n${e.message}")
                }
            }
        }
    }

    private fun downloadRemotePdf(url: String) {
        binding.pbPdfLoading.visibility = View.VISIBLE
        binding.toolbarPdf.subtitle = "正在下载网络文档..."

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder().build()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    throw Exception("网络请求失败 (HTTP ${response.code})")
                }

                // 优先从 HTTP Content-Disposition 响应头提取真实文件名
                val disposition = response.header("Content-Disposition")
                val serverName = PdfViewerUtils.parseContentDispositionFileName(disposition)
                val resolvedTitle = if (!serverName.isNullOrBlank() && !PdfViewerUtils.isGenericTitle(serverName)) {
                    serverName
                } else if (PdfViewerUtils.isGenericTitle(documentTitle)) {
                    PdfViewerUtils.cleanFileName(url)
                } else {
                    documentTitle
                }

                withContext(Dispatchers.Main) {
                    updateDocumentTitle(resolvedTitle)
                }

                val tempDir = File(cacheDir, "pdf_preview").apply { if (!exists()) mkdirs() }
                val safeName = documentTitle.replace(Regex("""[\\/:*?"<>|]"""), "_").let {
                    if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf"
                }
                val destFile = File(tempDir, "temp_${System.currentTimeMillis()}_$safeName")

                val body = response.body ?: throw Exception("响应正文为空")
                body.byteStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }

                isTemporaryDownload = true
                withContext(Dispatchers.Main) {
                    openLocalPdf(destFile)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("下载网络文档失败:\n${e.message}")
                }
            }
        }
    }

    private fun openLocalPdf(file: File) {
        localPdfFile = file
        if (PdfViewerUtils.isGenericTitle(documentTitle) && file.name.isNotBlank()) {
            updateDocumentTitle(file.name)
        }
        binding.pbPdfLoading.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                parcelFileDescriptor = pfd
                val renderer = PdfRenderer(pfd)
                pdfRenderer = renderer
                totalPages = renderer.pageCount

                if (totalPages <= 0) {
                    throw Exception("该文档包含 0 个可渲染页面")
                }

                // 快速预扫描各页面宽高比（仅读取字典元数据，无需栅格化）
                val aspectRatios = FloatArray(totalPages)
                synchronized(rendererLock) {
                    for (i in 0 until totalPages) {
                        val page = renderer.openPage(i)
                        aspectRatios[i] = page.height.toFloat() / page.width.toFloat().coerceAtLeast(1f)
                        page.close()
                    }
                }
                pageAspectRatios = aspectRatios

                withContext(Dispatchers.Main) {
                    binding.pbPdfLoading.visibility = View.GONE
                    binding.toolbarPdf.subtitle = "共 $totalPages 页"
                    setupRecyclerView()
                    showPageIndicator(1)
                }
            } catch (e: SecurityException) {
                withContext(Dispatchers.Main) {
                    showError("该 PDF 文件受密码保护或由于安全限制无法在应用内解析")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("打开 PDF 失败: ${e.message}")
                }
            }
        }
    }

    private fun setupRecyclerView() {
        val layoutManager = LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)
        binding.rvPdfPages.layoutManager = layoutManager
        pageAdapter = PdfPageAdapter()
        binding.rvPdfPages.adapter = pageAdapter

        binding.rvPdfPages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val firstPos = layoutManager.findFirstVisibleItemPosition()
                if (firstPos != RecyclerView.NO_POSITION) {
                    showPageIndicator(firstPos + 1)
                }
            }
        })
    }

    private fun showPageIndicator(currentPage: Int) {
        binding.tvPageIndicator.text = "$currentPage / $totalPages"
        binding.tvPageIndicator.visibility = View.VISIBLE
        binding.tvPageIndicator.animate().cancel()
        binding.tvPageIndicator.alpha = 1f

        hideIndicatorHandler.removeCallbacks(hideIndicatorRunnable)
        hideIndicatorHandler.postDelayed(hideIndicatorRunnable, 2500)
    }

    private fun toggleFullscreen() {
        isToolbarVisible = !isToolbarVisible
        if (isToolbarVisible) {
            binding.appBarLayout.animate().translationY(0f).setDuration(200).start()
            showPageIndicator(
                (binding.rvPdfPages.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition()?.plus(1) ?: 1
            )
        } else {
            binding.appBarLayout.animate().translationY(-binding.appBarLayout.height.toFloat()).setDuration(200).start()
            binding.tvPageIndicator.visibility = View.GONE
        }
    }

    private fun showError(message: String) {
        binding.pbPdfLoading.visibility = View.GONE
        binding.rvPdfPages.visibility = View.GONE
        binding.tvPageIndicator.visibility = View.GONE
        binding.llPdfError.visibility = View.VISIBLE
        binding.tvPdfErrorMessage.text = message
        binding.toolbarPdf.subtitle = "打开失败"

        binding.btnOpenWithExternal.setOnClickListener {
            openWithExternalViewer()
        }
    }

    private fun openWithExternalViewer() {
        val file = localPdfFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "文件未就绪，无法唤起外部阅读器", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val authority = "${packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(this, authority, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(Intent.createChooser(intent, "选择打开方式: ${file.name}"))
        } catch (e: Exception) {
            Toast.makeText(this, "未找到能够打开 PDF 的外部应用: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sharePdf() {
        val file = localPdfFile
        if (file == null || !file.exists()) {
            Toast.makeText(this, "文件未就绪，无法分享", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val authority = "${packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(this, authority, file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, documentTitle)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "分享文档: ${file.name}"))
        } catch (e: Exception) {
            Toast.makeText(this, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_pdf_viewer, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_share -> {
                sharePdf()
                true
            }
            R.id.action_open_external -> {
                openWithExternalViewer()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideIndicatorHandler.removeCallbacksAndMessages(null)
        lifecycleScope.coroutineContext.cancelChildren()

        bitmapCache.evictAll()

        try {
            synchronized(rendererLock) {
                pdfRenderer?.close()
                pdfRenderer = null
            }
        } catch (_: Exception) {}

        try {
            parcelFileDescriptor?.close()
            parcelFileDescriptor = null
        } catch (_: Exception) {}

        if (isTemporaryDownload) {
            try {
                localPdfFile?.delete()
            } catch (_: Exception) {}
        }
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // =========================================================================
    // RecyclerView Adapter
    // =========================================================================

    private inner class PdfPageAdapter : RecyclerView.Adapter<PdfPageViewHolder>() {

        override fun getItemCount(): Int = totalPages

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PdfPageViewHolder {
            val itemBinding = ItemPdfPageBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return PdfPageViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: PdfPageViewHolder, position: Int) {
            holder.bind(position)
        }

        override fun onViewRecycled(holder: PdfPageViewHolder) {
            super.onViewRecycled(holder)
            holder.cancelRender()
        }
    }

    private inner class PdfPageViewHolder(
        val itemBinding: ItemPdfPageBinding
    ) : RecyclerView.ViewHolder(itemBinding.root) {

        private var renderJob: Job? = null

        fun bind(position: Int) {
            itemBinding.tvPageNumberBadge.text = "${position + 1} / $totalPages"
            itemBinding.tvPageNumberBadge.visibility = View.VISIBLE

            // 单击页面切换工具栏全屏显示
            itemBinding.ivPdfPage.onSingleTapListener = {
                toggleFullscreen()
            }

            // 精准预设各页对应的高宽比占位尺寸，杜绝垂直滑动中的跳变
            val ratio = if (position in pageAspectRatios.indices) pageAspectRatios[position] else 1.414f
            val screenWidth = resources.displayMetrics.widthPixels - dpToPx(16f)
            val placeholderHeight = (screenWidth * ratio).toInt().coerceAtLeast(dpToPx(200f))

            itemBinding.cardPage.layoutParams = itemBinding.cardPage.layoutParams.apply {
                height = placeholderHeight
            }

            val cached = bitmapCache.get(position)
            if (cached != null && !cached.isRecycled) {
                itemBinding.pbPageLoading.visibility = View.GONE
                itemBinding.ivPdfPage.setImageBitmap(cached)
                return
            }

            // 缓存未命中：显示菊花并异步高画质光栅化渲染
            itemBinding.ivPdfPage.setImageDrawable(null)
            itemBinding.pbPageLoading.visibility = View.VISIBLE

            cancelRender()
            renderJob = lifecycleScope.launch(Dispatchers.IO) {
                val renderer = pdfRenderer ?: return@launch
                // 超高清目标分辨率（以屏幕宽度 1.5 倍渲染，放大 2.5 倍依然字体锐利）
                val renderWidth = (resources.displayMetrics.widthPixels * 1.5f).toInt().coerceIn(720, 2400)
                val safeRatio = if (ratio > 0.05f) ratio else 1.414f
                val renderHeight = (renderWidth * safeRatio).toInt().coerceIn(100, 8000)

                try {
                    val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE) // PDF 默认透明背景需绘制纯白底色

                    synchronized(rendererLock) {
                        if (!isFinishing && !isDestroyed && pdfRenderer != null) {
                            val page = renderer.openPage(position)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            page.close()
                        }
                    }
                    bitmapCache.put(position, bitmap)

                    withContext(Dispatchers.Main) {
                        if (adapterPosition == position) {
                            itemBinding.pbPageLoading.visibility = View.GONE
                            itemBinding.ivPdfPage.setImageBitmap(bitmap)
                        }
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        withContext(Dispatchers.Main) {
                            if (adapterPosition == position) {
                                itemBinding.pbPageLoading.visibility = View.GONE
                            }
                        }
                    }
                } catch (oom: OutOfMemoryError) {
                    bitmapCache.evictAll()
                    withContext(Dispatchers.Main) {
                        if (adapterPosition == position) {
                            itemBinding.pbPageLoading.visibility = View.GONE
                        }
                    }
                }
            }
        }

        fun cancelRender() {
            renderJob?.cancel()
            renderJob = null
        }
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_TITLE = "extra_title"

        /**
         * 启动应用内原生 PDF 阅读器
         * @param context 上下文
         * @param filePath 本地文件绝对路径或远程 http/https 链接
         * @param title 文档显示标题（可选）
         */
        fun start(context: Context, filePath: String, title: String? = null) {
            val intent = Intent(context, PdfViewerActivity::class.java).apply {
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra(EXTRA_TITLE, title)
            }
            context.startActivity(intent)
        }
    }
}
