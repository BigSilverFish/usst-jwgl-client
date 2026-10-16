package cn.edu.usst.jwgl.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import cn.edu.usst.jwgl.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object ApkDownloadHelper {

    private val httpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private var activeCall: Call? = null
    private var downloadJob: Job? = null
    var pendingInstallApkFile: File? = null

    /**
     * Downloads an APK directly within the app, showing progress, and invokes PackageInstaller upon completion.
     */
    fun downloadAndInstall(
        activity: Activity,
        downloadUrl: String,
        versionName: String
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        if (downloadUrl.isBlank()) {
            Toast.makeText(activity, "下载直链为空", Toast.LENGTH_SHORT).show()
            return
        }

        // Inflate custom progress dialog layout
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_apk_download, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvApkDownloadTitle)
        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvApkDownloadSubtitle)
        val progressBar = dialogView.findViewById<LinearProgressIndicator>(R.id.progressBarApkDownload)
        val tvBytes = dialogView.findViewById<TextView>(R.id.tvApkDownloadBytes)
        val tvPercent = dialogView.findViewById<TextView>(R.id.tvApkDownloadPercent)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelApkDownload)

        tvTitle.text = "正在下载新版本 v$versionName"
        tvSubtitle.text = "通过直链下载中，请稍候..."

        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.show()

        val downloadDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir
        val safeVersion = versionName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val apkFile = File(downloadDir, "usst_jwgl_v${safeVersion}.apk")
        val tempFile = File(downloadDir, "usst_jwgl_v${safeVersion}.apk.tmp")

        btnCancel.setOnClickListener {
            cancelDownload(tempFile)
            dialog.dismiss()
            Toast.makeText(activity, "已取消更新下载", Toast.LENGTH_SHORT).show()
        }

        downloadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url(downloadUrl)
                        .header("User-Agent", "USST-Jwgl-Android-Client")
                        .build()

                    val call = httpClient.newCall(request)
                    activeCall = call
                    val response = call.execute()

                    if (!response.isSuccessful) {
                        throw IOException("HTTP ${response.code}: ${response.message}")
                    }

                    val body = response.body ?: throw IOException("Empty response body")
                    val totalBytes = body.contentLength()

                    if (tempFile.exists()) tempFile.delete()

                    var downloadedBytes = 0L
                    var lastUpdateMillis = 0L

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                ensureActive()
                                output.write(buffer, 0, read)
                                downloadedBytes += read

                                val now = System.currentTimeMillis()
                                if (now - lastUpdateMillis >= 80 || downloadedBytes == totalBytes) {
                                    lastUpdateMillis = now
                                    val percent = if (totalBytes > 0) {
                                        ((downloadedBytes * 100) / totalBytes).toInt()
                                    } else {
                                        0
                                    }

                                    withContext(Dispatchers.Main) {
                                        if (totalBytes > 0) {
                                            progressBar.isIndeterminate = false
                                            progressBar.progress = percent
                                            tvPercent.text = "$percent%"
                                            tvBytes.text = "${formatFileSize(downloadedBytes)} / ${formatFileSize(totalBytes)}"
                                        } else {
                                            progressBar.isIndeterminate = true
                                            tvPercent.text = ""
                                            tvBytes.text = "已下载: ${formatFileSize(downloadedBytes)}"
                                        }
                                    }
                                }
                            }
                            output.flush()
                        }
                    }

                    if (tempFile.exists()) {
                        if (apkFile.exists()) apkFile.delete()
                        tempFile.renameTo(apkFile)
                    }
                }

                dialog.dismiss()
                if (apkFile.exists() && apkFile.length() > 0) {
                    pendingInstallApkFile = apkFile
                    Toast.makeText(activity, "下载完成，正在唤起安装程序...", Toast.LENGTH_SHORT).show()
                    installApk(activity, apkFile)
                } else {
                    Toast.makeText(activity, "下载文件校验失败", Toast.LENGTH_SHORT).show()
                }

            } catch (e: CancellationException) {
                // User cancelled
                cancelDownload(tempFile)
                dialog.dismiss()
            } catch (e: Exception) {
                cancelDownload(tempFile)
                dialog.dismiss()
                Toast.makeText(activity, "下载更新失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                activeCall = null
                downloadJob = null
            }
        }
    }

    private fun cancelDownload(tempFile: File) {
        try {
            activeCall?.cancel()
            downloadJob?.cancel()
            if (tempFile.exists()) tempFile.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Installs the downloaded APK via FileProvider and PackageInstaller.
     */
    fun installApk(activity: Activity, apkFile: File) {
        if (!apkFile.exists() || apkFile.length() <= 0) {
            Toast.makeText(activity, "安装包不存在或损坏", Toast.LENGTH_SHORT).show()
            return
        }

        pendingInstallApkFile = apkFile

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("需要开启安装未知应用权限")
                    .setMessage("系统安全限制：为完成新版本自动更新，请在接下来的设置中开启“允许安装未知应用”。开启后返回本应用即可继续安装。")
                    .setPositiveButton("去开启") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                data = Uri.parse("package:${activity.packageName}")
                            }
                            activity.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(activity, "无法打开设置页面: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return
            }
        }

        try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }

            activity.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(activity, "无法唤起安装器: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 * 1024 -> String.format(java.util.Locale.CHINA, "%.1f GB", bytes.toDouble() / (1024 * 1024 * 1024))
            bytes >= 1024 * 1024 -> String.format(java.util.Locale.CHINA, "%.1f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format(java.util.Locale.CHINA, "%.1f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }
}
