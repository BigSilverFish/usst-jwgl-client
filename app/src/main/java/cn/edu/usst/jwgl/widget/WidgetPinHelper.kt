package cn.edu.usst.jwgl.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 桌面微件固定（Pin）与系统权限引导工具类。
 */
object WidgetPinHelper {

    fun pinSpecificWidget(context: Context, providerClass: Class<*>, widgetName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val appWidgetManager = context.getSystemService(AppWidgetManager::class.java)
            val provider = ComponentName(context, providerClass)
            var pinSent = false
            if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported) {
                val successIntent = Intent(context, providerClass).apply {
                    action = if (providerClass == TodayScheduleWidgetProvider::class.java) {
                        TodayScheduleWidgetProvider.ACTION_REFRESH_WIDGET
                    } else {
                        NextCourseWidgetProvider.ACTION_REFRESH_NEXT_COURSE
                    }
                }
                val successPendingIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    successIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                pinSent = appWidgetManager.requestPinAppWidget(provider, null, successPendingIntent)
            }
            if (!pinSent) {
                showWidgetPinGuidanceDialog(context, false, widgetName, providerClass)
            }
        } else {
            showWidgetPinGuidanceDialog(context, false, widgetName, providerClass)
        }
    }

    fun showWidgetPinGuidanceDialog(
        context: Context,
        autoPinTriggered: Boolean,
        widgetName: String = "今日课表",
        providerClass: Class<*>? = null
    ) {
        val msg = StringBuilder()
        if (autoPinTriggered) {
            msg.append("已向系统发送添加【$widgetName】桌面微件请求！\n\n")
        } else {
            msg.append("当前手机桌面启动器未支持自动添加微件。\n\n")
        }
        msg.append("📌【部分定制系统注意事项】\n")
            .append("1. 部分系统默认会静默拦截第三方应用的微件添加请求，常表现为点击后界面没有任何反应。\n")
            .append("2. 如未弹出系统添加确认框，请点击下方【前往权限设置】开启本应用的“桌面快捷方式”或“添加桌面小部件”权限。\n\n")
            .append("💡【推荐：桌面手动添加】\n")
            .append("👉 返回手机桌面 -> 双指捏合或长按空白处 -> 点击底部【微件 / 小组件 / 插件】 -> 搜索或下滑找到【上理教务】的【$widgetName】拖拽到桌面即可！")

        MaterialAlertDialogBuilder(context)
            .setTitle("添加${widgetName}小组件")
            .setMessage(msg.toString())
            .setPositiveButton("我知道了", null)
            .setNeutralButton("前往权限设置") { _, _ ->
                openAppPermissionSettings(context)
            }
            .setNegativeButton("再次尝试自动添加") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && providerClass != null) {
                    val appWidgetManager = context.getSystemService(AppWidgetManager::class.java)
                    val provider = ComponentName(context, providerClass)
                    if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported) {
                        val successIntent = Intent(context, providerClass).apply {
                            action = if (providerClass == TodayScheduleWidgetProvider::class.java) {
                                TodayScheduleWidgetProvider.ACTION_REFRESH_WIDGET
                            } else {
                                NextCourseWidgetProvider.ACTION_REFRESH_NEXT_COURSE
                            }
                        }
                        val successPendingIntent = PendingIntent.getBroadcast(
                            context,
                            0,
                            successIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        appWidgetManager.requestPinAppWidget(provider, null, successPendingIntent)
                        Toast.makeText(context, "已再次触发系统微件添加请求", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }

    private fun openAppPermissionSettings(context: Context) {
        try {
            val miuiIntent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                putExtra("extra_pkgname", context.packageName)
            }
            context.startActivity(miuiIntent)
            return
        } catch (_: Exception) {}

        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "请前往手机“设置 - 应用设置”开启桌面权限", Toast.LENGTH_SHORT).show()
        }
    }
}
