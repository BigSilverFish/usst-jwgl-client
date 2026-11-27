package cn.edu.usst.jwgl.data.local

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 课程课件与附件自定义存储管理器
 * 兼容 Android 7 - Android 16+ (API 24 - 36+) 分区存储规则：
 * - INTERNAL: 应用私有目录 (filesDir/Download/TronClass)
 * - SYSTEM_DOWNLOADS: 系统下载目录 (API 29+ 通过 MediaStore.Downloads 直写，免权限弹窗)
 * - CUSTOM_SAF: 用户通过 ACTION_OPEN_DOCUMENT_TREE 授权的持久目录 (DocumentsContract)
 */
object CoursewareStorageManager {

    private const val TAG = "CoursewareStorage"
    private const val PREF_NAME = "courseware_storage_prefs"
    private const val KEY_MODE = "storage_mode"
    private const val KEY_CUSTOM_TREE_URI = "custom_tree_uri"
    private const val KEY_CUSTOM_DISPLAY_LABEL = "custom_display_label"
    private const val KEY_GROUP_BY_COURSE = "group_by_course"
    private const val SYSTEM_ROOT_FOLDER = "usst-jwgl-client"

    enum class StorageMode {
        INTERNAL,
        SYSTEM_DOWNLOADS,
        CUSTOM_SAF
    }

    data class PersistResult(
        val filePathOrUri: String,
        val finalFile: File?,
        val contentUri: Uri?,
        val fileSize: Long,
        val locationLabel: String,
        val warningMessage: String? = null
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getStorageMode(context: Context): StorageMode {
        val raw = prefs(context).getString(KEY_MODE, StorageMode.SYSTEM_DOWNLOADS.name)
        return try {
            StorageMode.valueOf(raw ?: StorageMode.SYSTEM_DOWNLOADS.name)
        } catch (_: Exception) {
            StorageMode.SYSTEM_DOWNLOADS
        }
    }

    fun setStorageMode(context: Context, mode: StorageMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
    }

    fun isGroupByCourseEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_GROUP_BY_COURSE, true)
    }

    fun setGroupByCourseEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GROUP_BY_COURSE, enabled).apply()
    }

    fun getCustomTreeUri(context: Context): Uri? {
        val raw = prefs(context).getString(KEY_CUSTOM_TREE_URI, null)?.trim()
        if (raw.isNullOrBlank()) return null
        return try {
            Uri.parse(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun getCustomDisplayName(context: Context): String {
        return prefs(context).getString(KEY_CUSTOM_DISPLAY_LABEL, null)
            ?.takeIf { it.isNotBlank() } ?: "未选择文件夹"
    }

    fun saveCustomTreeUri(context: Context, uri: Uri): String {
        val appCtx = context.applicationContext
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            appCtx.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: Exception) {
            Log.w(TAG, "takePersistableUriPermission failed: ${e.message}")
        }
        val label = formatTreeUriDisplayPath(uri.lastPathSegment ?: uri.toString())
        prefs(appCtx).edit()
            .putString(KEY_CUSTOM_TREE_URI, uri.toString())
            .putString(KEY_CUSTOM_DISPLAY_LABEL, label)
            .putString(KEY_MODE, StorageMode.CUSTOM_SAF.name)
            .apply()
        return label
    }

    fun hasValidCustomTreePermission(context: Context): Boolean {
        val appCtx = context.applicationContext
        val treeUri = getCustomTreeUri(appCtx) ?: return false
        val hasPersisted = try {
            appCtx.contentResolver.persistedUriPermissions.any {
                it.uri == treeUri && it.isReadPermission && it.isWritePermission
            }
        } catch (_: Exception) {
            false
        }
        if (!hasPersisted) return false
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            appCtx.contentResolver.query(
                docUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )?.use { cursor -> cursor.moveToFirst() } ?: false
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun getCurrentLocationSummary(context: Context): String {
        return when (getStorageMode(context)) {
            StorageMode.INTERNAL -> "应用内置目录"
            StorageMode.SYSTEM_DOWNLOADS -> "系统下载目录 (Download/$SYSTEM_ROOT_FOLDER)"
            StorageMode.CUSTOM_SAF -> if (hasValidCustomTreePermission(context)) {
                "自定义目录 (${getCustomDisplayName(context)})"
            } else {
                "自定义目录（授权已失效，点击重选）"
            }
        }
    }

    fun sanitizeSegment(raw: String?, fallback: String = "未命名"): String {
        val cleaned = (raw ?: "")
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""_+"""), "_")
            .trim()
            .trim('.')
            .trim()
        if (cleaned.isBlank()) return fallback
        return if (cleaned.length > 80) cleaned.substring(0, 80).trim().trim('.') else cleaned
    }

    fun buildSystemDownloadsRelativePath(courseName: String?, groupByCourse: Boolean): String {
        if (!groupByCourse || courseName.isNullOrBlank()) {
            return "Download/$SYSTEM_ROOT_FOLDER/"
        }
        val cleanCourse = sanitizeSegment(courseName, "未分类课程")
        return "Download/$SYSTEM_ROOT_FOLDER/$cleanCourse/"
    }

    fun formatTreeUriDisplayPath(rawSegment: String?): String {
        if (rawSegment.isNullOrBlank()) return "自定义文件夹"
        var decoded = rawSegment.trim()
        if (decoded.contains("%")) {
            decoded = try {
                URLDecoder.decode(decoded, StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                decoded
            }
        }
        val colonIdx = decoded.indexOf(':')
        if (colonIdx >= 0) {
            val volume = decoded.substring(0, colonIdx)
            val subPath = decoded.substring(colonIdx + 1).trim('/').trim()
            val rootLabel = if (volume.equals("primary", ignoreCase = true)) "内部存储" else "外部存储"
            return if (subPath.isNotBlank()) "$rootLabel/$subPath" else rootLabel
        }
        return decoded.trim('/').ifBlank { "自定义文件夹" }
    }

    fun resolveMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
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
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> MimeTypeMap.getSingleton()?.getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }
    }

    fun isUriReadable(context: Context, uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun deleteUri(context: Context, uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            val resolver = context.contentResolver
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(resolver, uri)
            } else {
                resolver.delete(uri, null, null) > 0
            }
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun persistDownloadedCourseware(
        context: Context,
        sourceFile: File,
        uploadId: Long,
        displayName: String,
        courseId: Long,
        courseName: String,
        removeSourceAfterExport: Boolean = true,
        customFileId: String? = null,
        mimeTypeOverride: String? = null
    ): PersistResult {
        val appCtx = context.applicationContext
        val safeFileName = sanitizeSegment(displayName, "课件")
        val mimeType = mimeTypeOverride?.takeIf { it.isNotBlank() } ?: resolveMimeType(safeFileName)
        val mode = getStorageMode(appCtx)

        val result: PersistResult = when (mode) {
            StorageMode.SYSTEM_DOWNLOADS -> {
                try {
                    exportToSystemDownloads(appCtx, sourceFile, safeFileName, courseName, mimeType)
                } catch (e: Exception) {
                    Log.w(TAG, "exportToSystemDownloads failed, fallback to internal", e)
                    persistToInternal(
                        appCtx,
                        sourceFile,
                        uploadId,
                        safeFileName,
                        warning = "系统下载目录写入失败，已自动保存至应用内置目录",
                        customFileId = customFileId
                    )
                }
            }
            StorageMode.CUSTOM_SAF -> {
                val safResult = exportToCustomSaf(appCtx, sourceFile, safeFileName, courseName, mimeType)
                safResult ?: persistToInternal(
                    appCtx,
                    sourceFile,
                    uploadId,
                    safeFileName,
                    warning = "自定义目录写入失败或授权已失效，已自动保存至应用内置目录",
                    customFileId = customFileId
                )
            }
            StorageMode.INTERNAL -> {
                persistToInternal(
                    appCtx,
                    sourceFile,
                    uploadId,
                    safeFileName,
                    warning = null,
                    customFileId = customFileId
                )
            }
        }

        if (removeSourceAfterExport &&
            sourceFile.exists() &&
            sourceFile.absolutePath != result.finalFile?.absolutePath
        ) {
            runCatching { sourceFile.delete() }
        }

        val effectiveId = customFileId?.takeIf { it.isNotBlank() }
            ?: if (uploadId > 0L) uploadId.toString() else "saved_${System.currentTimeMillis()}"
        TronClassDownloadManager.saveDownloadedItem(
            appCtx,
            TronClassDownloadedItem(
                fileId = effectiveId,
                fileName = safeFileName,
                courseId = courseId,
                courseName = courseName,
                filePath = result.filePathOrUri,
                fileSize = result.fileSize,
                mimeType = mimeType,
                storageLocation = result.locationLabel
            )
        )

        return result
    }

    private fun persistToInternal(
        context: Context,
        sourceFile: File,
        uploadId: Long,
        safeFileName: String,
        warning: String?,
        customFileId: String? = null
    ): PersistResult {
        val cacheManager = TronClassCacheManager(context)
        val targetFile = when {
            uploadId > 0L -> cacheManager.getFile(uploadId, safeFileName)
            !customFileId.isNullOrBlank() -> File(cacheManager.getCacheDir(), "${sanitizeSegment(customFileId, "cx")}_$safeFileName")
            else -> File(cacheManager.getCacheDir(), "${System.currentTimeMillis()}_$safeFileName")
        }
        if (sourceFile.absolutePath != targetFile.absolutePath) {
            targetFile.parentFile?.mkdirs()
            sourceFile.copyTo(targetFile, overwrite = true)
        }
        return PersistResult(
            filePathOrUri = targetFile.absolutePath,
            finalFile = targetFile,
            contentUri = null,
            fileSize = targetFile.length(),
            locationLabel = "应用内置目录",
            warningMessage = warning
        )
    }

    private fun exportToSystemDownloads(
        context: Context,
        sourceFile: File,
        safeFileName: String,
        courseName: String,
        mimeType: String
    ): PersistResult {
        val groupByCourse = isGroupByCourseEnabled(context)
        val relPath = buildSystemDownloadsRelativePath(courseName, groupByCourse)
        val locationLabel = relPath.trimEnd('/')

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            // 清理此前本应用在同路径下生成的同名文件，避免产生 (1)/(2) 副本
            try {
                resolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                    arrayOf(safeFileName, relPath)
                )
            } catch (_: Exception) {}

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeFileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert returned null")

            try {
                resolver.openOutputStream(uri)?.use { out ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                } ?: throw IllegalStateException("Unable to open MediaStore output stream")

                val doneValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }

            return PersistResult(
                filePathOrUri = uri.toString(),
                finalFile = null,
                contentUri = uri,
                fileSize = sourceFile.length(),
                locationLabel = locationLabel
            )
        } else {
            val subDirPath = relPath.removePrefix("Download/").trimEnd('/')
            val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val targetDir = File(baseDir, subDirPath).apply { if (!exists()) mkdirs() }
            val destFile = File(targetDir, safeFileName)
            sourceFile.copyTo(destFile, overwrite = true)
            return PersistResult(
                filePathOrUri = destFile.absolutePath,
                finalFile = destFile,
                contentUri = null,
                fileSize = destFile.length(),
                locationLabel = locationLabel
            )
        }
    }

    private fun exportToCustomSaf(
        context: Context,
        sourceFile: File,
        safeFileName: String,
        courseName: String,
        mimeType: String
    ): PersistResult? {
        if (!hasValidCustomTreePermission(context)) return null
        val treeUri = getCustomTreeUri(context) ?: return null
        val resolver = context.contentResolver

        return try {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val groupByCourse = isGroupByCourseEnabled(context) && courseName.isNotBlank()
            val cleanCourse = if (groupByCourse) sanitizeSegment(courseName, "未分类课程") else null

            val targetParentDocId = if (cleanCourse != null) {
                findOrCreateSubdirectory(resolver, treeUri, rootDocId, cleanCourse) ?: rootDocId
            } else {
                rootDocId
            }

            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, targetParentDocId)
            val existingFileUri = findChildDocument(resolver, treeUri, targetParentDocId, safeFileName, expectDir = false)
            val targetDocUri = if (existingFileUri != null) {
                val overwritten = try {
                    resolver.openOutputStream(existingFileUri, "wt")?.use { out ->
                        sourceFile.inputStream().use { input -> input.copyTo(out) }
                        true
                    } ?: false
                } catch (_: Exception) {
                    false
                }
                if (overwritten) {
                    existingFileUri
                } else {
                    runCatching { DocumentsContract.deleteDocument(resolver, existingFileUri) }
                    createAndWriteSafDocument(resolver, parentDocUri, mimeType, safeFileName, sourceFile)
                }
            } else {
                createAndWriteSafDocument(resolver, parentDocUri, mimeType, safeFileName, sourceFile)
            } ?: return null

            val baseLabel = getCustomDisplayName(context)
            val locationLabel = if (cleanCourse != null && targetParentDocId != rootDocId) {
                "$baseLabel/$cleanCourse"
            } else {
                baseLabel
            }

            PersistResult(
                filePathOrUri = targetDocUri.toString(),
                finalFile = null,
                contentUri = targetDocUri,
                fileSize = sourceFile.length(),
                locationLabel = locationLabel
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "exportToCustomSaf SecurityException", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "exportToCustomSaf failed", e)
            null
        }
    }

    private fun createAndWriteSafDocument(
        resolver: android.content.ContentResolver,
        parentDocUri: Uri,
        mimeType: String,
        displayName: String,
        sourceFile: File
    ): Uri? {
        val createdUri = DocumentsContract.createDocument(resolver, parentDocUri, mimeType, displayName)
            ?: return null
        return try {
            resolver.openOutputStream(createdUri, "w")?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("Unable to open SAF output stream")
            createdUri
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, createdUri) }
            throw e
        }
    }

    private fun findOrCreateSubdirectory(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        dirName: String
    ): String? {
        val existingUri = findChildDocument(resolver, treeUri, parentDocId, dirName, expectDir = true)
        if (existingUri != null) {
            return DocumentsContract.getDocumentId(existingUri)
        }
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)
        val created = DocumentsContract.createDocument(
            resolver,
            parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            dirName
        ) ?: return null
        return DocumentsContract.getDocumentId(created)
    }

    private fun findChildDocument(
        resolver: android.content.ContentResolver,
        treeUri: Uri,
        parentDocId: String,
        displayName: String,
        expectDir: Boolean
    ): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else null
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                if (name == displayName && isDir == expectDir && idCol >= 0) {
                    val docId = cursor.getString(idCol)
                    if (!docId.isNullOrBlank()) {
                        return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    }
                }
            }
        }
        return null
    }
}
