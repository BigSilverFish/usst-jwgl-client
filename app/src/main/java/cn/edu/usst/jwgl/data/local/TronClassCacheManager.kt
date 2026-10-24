package cn.edu.usst.jwgl.data.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

class TronClassCacheManager(private val context: Context) {

    fun getCacheDir(): File {
        return File(context.filesDir, "Download/TronClass").apply {
            if (!exists()) mkdirs()
        }
    }

    fun sanitizeFileName(rawName: String): String {
        return rawName.replace(Regex("""[\\/:*?"<>|]"""), "_").trim()
    }

    fun getFile(uploadId: Long, rawName: String): File {
        val cleanName = sanitizeFileName(rawName)
        return File(getCacheDir(), "${uploadId}_$cleanName")
    }

    fun getLegacyFile(rawName: String): File {
        val cleanName = sanitizeFileName(rawName)
        return File(getCacheDir(), cleanName)
    }

    fun isCached(uploadId: Long, rawName: String): Boolean {
        val f1 = getFile(uploadId, rawName)
        if (f1.exists() && f1.length() > 0) return true
        val f2 = getLegacyFile(rawName)
        return f2.exists() && f2.length() > 0
    }

    fun getExistingFile(uploadId: Long, rawName: String): File? {
        val f1 = getFile(uploadId, rawName)
        if (f1.exists() && f1.length() > 0) return f1
        val f2 = getLegacyFile(rawName)
        if (f2.exists() && f2.length() > 0) return f2
        return null
    }

    fun getCachedFiles(): List<File> {
        val dir = getCacheDir()
        return dir.listFiles()?.filter { it.isFile && it.length() > 0 } ?: emptyList()
    }

    fun getCachedFileCount(): Int = getCachedFiles().size

    fun getTotalCacheSizeBytes(): Long {
        return getCachedFiles().sumOf { it.length() }
    }

    fun getFormattedCacheSize(): String {
        val size = getTotalCacheSizeBytes()
        if (size <= 0) return "0 MB"
        val kb = size / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.CHINA, "%.2f MB", mb)
            kb >= 1.0 -> String.format(Locale.CHINA, "%.1f KB", kb)
            else -> "$size B"
        }
    }

    fun clearCache(): Long {
        val size = getTotalCacheSizeBytes()
        getCachedFiles().forEach { runCatching { it.delete() } }
        return size
    }

    fun openFile(file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "文件不存在或已被删除", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, file)

            val ext = file.extension.lowercase()
            val mime = when (ext) {
                "pdf" -> "application/pdf"
                "ppt" -> "application/vnd.ms-powerpoint"
                "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                "doc" -> "application/msword"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "xls" -> "application/vnd.ms-excel"
                "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                "mp4" -> "video/mp4"
                "zip" -> "application/zip"
                "txt" -> "text/plain"
                else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "选择打开方式: ${file.name}"))
        } catch (e: Exception) {
            Toast.makeText(context, "未找到能够打开该文件的应用: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
