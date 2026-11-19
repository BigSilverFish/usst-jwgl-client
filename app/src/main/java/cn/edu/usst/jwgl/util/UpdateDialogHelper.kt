package cn.edu.usst.jwgl.util

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import cn.edu.usst.jwgl.data.model.AppVersionInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.lang.ref.WeakReference

object UpdateDialogHelper {

    private var dismissedVersionCodeInSession: Int = -1
    private var activeDialogRef: WeakReference<AlertDialog>? = null

    /**
     * Shows the version update dialog with a direct APK download option and a release webpage option.
     *
     * @param activity The hosting activity
     * @param versionInfo The app version information from remote config
     * @param forceShow If true (e.g. user manually clicked "Check for Updates"), bypasses session dismissal check
     */
    fun showUpdateDialog(
        activity: Activity,
        versionInfo: AppVersionInfo,
        forceShow: Boolean = false
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        // Avoid stacking duplicate update dialogs on the same activity/session
        val existingDialog = activeDialogRef?.get()
        if (existingDialog != null && existingDialog.isShowing) {
            return
        }

        // If the user already tapped "Later" for this version during the current app session, don't nag unless forced
        if (!forceShow && !versionInfo.forceUpdate && versionInfo.versionCode <= dismissedVersionCodeInSession) {
            return
        }

        val notes = if (versionInfo.releaseNotes.isNotBlank()) "\n\n更新说明：\n${versionInfo.releaseNotes}" else ""
        val directApkUrl = versionInfo.getDirectApkUrl()
        val webReleaseUrl = versionInfo.downloadUrl

        val dialogBuilder = MaterialAlertDialogBuilder(activity)
            .setTitle("发现新版本 v${versionInfo.versionName}")
            .setMessage("检测到新版本发布 (发布日期: ${versionInfo.releaseDate})$notes")
            .setPositiveButton("直链直接下载") { _, _ ->
                if (directApkUrl.isNotBlank()) {
                    if (versionInfo.sha256.isBlank()) {
                        Toast.makeText(activity, "未提供 SHA-256 安全校验值，为保障安全无法应用内安装，正在打开 Release 网页...", Toast.LENGTH_LONG).show()
                        if (webReleaseUrl.isNotBlank()) {
                            openWebUrl(activity, webReleaseUrl)
                        }
                    } else {
                        ApkDownloadHelper.downloadAndInstall(activity, directApkUrl, versionInfo.versionName, versionInfo.sha256)
                    }
                } else if (webReleaseUrl.isNotBlank()) {
                    openWebUrl(activity, webReleaseUrl)
                } else {
                    Toast.makeText(activity, "暂无直接下载链接", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("前往 Release 网页") { _, _ ->
                if (webReleaseUrl.isNotBlank()) {
                    openWebUrl(activity, webReleaseUrl)
                } else {
                    Toast.makeText(activity, "暂无网页链接", Toast.LENGTH_SHORT).show()
                }
            }

        if (!versionInfo.forceUpdate) {
            dialogBuilder.setNegativeButton("稍后再说") { dialog, _ ->
                dismissedVersionCodeInSession = versionInfo.versionCode
                dialog.dismiss()
            }
        } else {
            dialogBuilder.setCancelable(false)
        }

        try {
            val dialog = dialogBuilder.show()
            activeDialogRef = WeakReference(dialog)
            dialog.setOnDismissListener {
                if (activeDialogRef?.get() === dialog) {
                    activeDialogRef = null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun openWebUrl(activity: Activity, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, "无法打开浏览器，请确认已安装浏览器应用", Toast.LENGTH_SHORT).show()
        }
    }
}
