package cn.edu.usst.jwgl.data.local

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
    val mimeType: String = ""
) {
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
        return fileName.substringAfterLast('.', "").lowercase()
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

        return list.filter { File(it.filePath).exists() }.sortedByDescending { it.downloadTime }
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
            val file = File(item.filePath)
            if (file.exists()) {
                file.delete()
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
                val f = File(item.filePath)
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
        saveList(context, emptyList())
    }

    fun getTotalCacheSize(context: Context): Long {
        return getDownloadedItems(context).sumOf { it.fileSize }
    }

    fun openFile(context: Context, item: TronClassDownloadedItem) {
        try {
            val file = File(item.filePath)
            if (!file.exists()) {
                Toast.makeText(context, "文件不存在或已被移动", Toast.LENGTH_SHORT).show()
                return
            }

            val ext = item.getExtension()
            var mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: item.mimeType
            if (mime.isBlank()) {
                mime = "*/*"
            }

            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "打开课件: ${item.fileName}"))
        } catch (e: Exception) {
            Toast.makeText(context, "未找到可打开此格式的应用: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareFile(context: Context, item: TronClassDownloadedItem) {
        try {
            val file = File(item.filePath)
            if (!file.exists()) {
                Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val ext = item.getExtension()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(shareIntent, "分享课件: ${item.fileName}"))
        } catch (e: Exception) {
            Toast.makeText(context, "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveList(context: Context, list: List<TronClassDownloadedItem>) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putString(KEY_FILES, gson.toJson(list)).apply()
    }
}
