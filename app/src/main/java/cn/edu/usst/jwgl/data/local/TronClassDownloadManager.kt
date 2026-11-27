package cn.edu.usst.jwgl.data.local

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.DecimalFormat

data class TronClassDownloadedItem(
    val fileId: String,
    val fileName: String,
    val courseId: Long,
    val courseName: String,
    val filePath: String,
    val fileSize: Long,
    val downloadTime: Long = System.currentTimeMillis(),
    val mimeType: String? = "",
    val storageLocation: String? = ""
) {
    fun getSafeMimeType(): String = mimeType.orEmpty()

    fun getSafeStorageLocation(): String = storageLocation.orEmpty()

    fun isContentUri(): Boolean = (filePath as String?)?.startsWith("content://", ignoreCase = true) == true

    fun getFormattedSize(): String {
        if (fileSize <= 0) return "未知大小"
        val units = arrayOf("B", "KB", "MB", "GB")
        var size = fileSize.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return "${DecimalFormat("#.##").format(size)} ${units[unitIndex]}"
    }

    fun getExtension(): String {
        return (fileName ?: "").substringAfterLast('.', "").lowercase()
    }
}

object TronClassDownloadManager {

    private const val PREF_NAME = "tronclass_downloads"
    private const val KEY_FILES = "downloaded_files"
    private val gson = Gson()

    fun getDownloadedItems(context: Context): List<TronClassDownloadedItem> {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = sp.getString(KEY_FILES, null)
        val list: MutableList<TronClassDownloadedItem> = try {
            if (json != null) {
                val type = object : TypeToken<List<TronClassDownloadedItem>>() {}.type
                gson.fromJson<List<TronClassDownloadedItem>>(json, type)?.toMutableList() ?: mutableListOf()
            } else {
                mutableListOf()
            }
        } catch (e: Exception) {
            mutableListOf()
        }

        val cacheDir = File(context.filesDir, "Download/TronClass")
        if (cacheDir.exists() && cacheDir.isDirectory) {
            val diskFiles = cacheDir.listFiles() ?: emptyArray()
            val existingPaths = list.map { it.filePath }.toSet()
            for (f in diskFiles) {
                if (f.isFile && f.length() > 0 && f.absolutePath !in existingPaths) {
                    val rawName = f.name
                    val cleanDisplayName = if (rawName.contains("_")) rawName.substringAfter("_") else rawName
                    list.add(
                        TronClassDownloadedItem(
                            fileId = if (rawName.contains("_")) rawName.substringBefore("_") else "0",
                            fileName = cleanDisplayName,
                            courseId = 0L,
                            courseName = "本地已缓存课件",
                            filePath = f.absolutePath,
                            fileSize = f.length(),
                            downloadTime = f.lastModified()
                        )
                    )
                }
            }
        }

        return list.filter {
            val safePath = (it.filePath as String?).orEmpty()
            if (safePath.isBlank()) {
                false
            } else if (it.isContentUri()) {
                CoursewareStorageManager.isUriReadable(context, safePath)
            } else {
                File(safePath).exists()
            }
        }.sortedByDescending { it.downloadTime }
    }

    fun findByFileId(context: Context, fileId: String): TronClassDownloadedItem? {
        if (fileId.isBlank()) return null
        return getDownloadedItems(context).firstOrNull { it.fileId == fileId }
    }

    @Synchronized
    fun saveDownloadedItem(context: Context, item: TronClassDownloadedItem) {
        val current = getDownloadedItems(context).toMutableList()
        current.removeAll { it.filePath == item.filePath || (it.fileId == item.fileId && it.fileId.isNotBlank()) }
        current.add(0, item)
        saveList(context, current)
    }

    @Synchronized
    fun deleteItem(context: Context, item: TronClassDownloadedItem): Boolean {
        try {
            if (item.isContentUri()) {
                CoursewareStorageManager.deleteUri(context, item.filePath)
            } else {
                val file = File(item.filePath)
                if (file.exists()) {
                    file.delete()
                }
            }
            val current = getDownloadedItems(context).toMutableList()
            current.removeAll { it.filePath == item.filePath }
            saveList(context, current)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    @Synchronized
    fun clearAll(context: Context) {
        val items = getDownloadedItems(context)
        for (item in items) {
            try {
                if (item.isContentUri()) {
                    CoursewareStorageManager.deleteUri(context, item.filePath)
                } else {
                    val f = File(item.filePath)
                    if (f.exists()) f.delete()
                }
            } catch (_: Exception) {}
        }
        saveList(context, emptyList())
    }

    fun getTotalCacheSize(context: Context): Long {
        return getDownloadedItems(context).sumOf { it.fileSize }
    }

    private fun resolveItemUri(context: Context, item: TronClassDownloadedItem): Uri? {
        if (item.isContentUri()) {
            return if (CoursewareStorageManager.isUriReadable(context, item.filePath)) {
                Uri.parse(item.filePath)
            } else null
        }
        val file = File(item.filePath)
        if (!file.exists()) return null
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun openFile(context: Context, item: TronClassDownloadedItem) {
        try {
            val isPdf = item.getExtension().equals("pdf", ignoreCase = true) ||
                (!item.isContentUri() && File(item.filePath).extension.equals("pdf", ignoreCase = true))

            val exists = if (item.isContentUri()) {
                CoursewareStorageManager.isUriReadable(context, item.filePath)
            } else {
                File(item.filePath).exists()
            }
            if (!exists) {
                Toast.makeText(context, "文件不存在或已被移动", Toast.LENGTH_SHORT).show()
                return
            }

            if (isPdf) {
                cn.edu.usst.jwgl.ui.pdf.PdfViewerActivity.start(
                    context,
                    item.filePath,
                    item.fileName,
                    item.courseName
                )
                return
            }

            val ext = item.getExtension()
            var mime = MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext) ?: item.getSafeMimeType()
            if (mime.isBlank()) {
                mime = CoursewareStorageManager.resolveMimeType(item.fileName)
            }

            val uri: Uri = resolveItemUri(context, item) ?: run {
                Toast.makeText(context, "文件不存在或无法读取", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                clipData = ClipData.newRawUri(item.fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "打开课件: ${item.fileName}"))
        } catch (e: Exception) {
            Toast.makeText(context, "未找到可打开此格式的应用，请安装相应格式的查看器", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareFile(context: Context, item: TronClassDownloadedItem) {
        try {
            val uri = resolveItemUri(context, item)
            if (uri == null) {
                Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
                return
            }

            val ext = item.getExtension()
            val mime = MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
                ?: item.getSafeMimeType().ifBlank { CoursewareStorageManager.resolveMimeType(item.fileName) }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, item.fileName)
                clipData = ClipData.newRawUri(item.fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(shareIntent, "分享课件: ${item.fileName}"))
        } catch (e: Exception) {
            Toast.makeText(context, "未找到支持分享此文件的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveList(context: Context, list: List<TronClassDownloadedItem>) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_FILES, gson.toJson(list)).apply()
    }
}
