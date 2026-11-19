package cn.edu.usst.jwgl

import android.app.Application
import android.content.Context
import cn.edu.usst.jwgl.data.remote.RemoteConfigManager
import cn.edu.usst.jwgl.util.NotificationDiagnosticHelper
import cn.edu.usst.jwgl.util.ThemeManager

class UsstApplication : Application() {
    companion object {
        private const val TAG = "UsstApplication"
    }

    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
        migrateIncompatibleCachesOnUpgrade()
        // Apply saved theme mode early before activities inflate
        ThemeManager.applySavedTheme(this)
        RemoteConfigManager.init(this)

        // 预先注册所有重要提醒通知渠道（高优先级、声音、横幅、震动）
        try {
            NotificationDiagnosticHelper.initNotificationChannels(this)
        } catch (_: Exception) {
        }
    }

    private fun migrateIncompatibleCachesOnUpgrade() {
        try {
            val prefs = getSharedPreferences("usst_app_upgrade", Context.MODE_PRIVATE)
            val lastCode = prefs.getInt("last_version_code", 0)
            val currentCode = BuildConfig.VERSION_CODE
            if (lastCode < currentCode) {
                // 跨版本升级时清理易受模型变更影响的畅课旧版结构缓存，保留登录凭据与课表数据
                try {
                    cn.edu.usst.jwgl.data.local.TronClassPreferences(this).clearLearningCache()
                } catch (_: Exception) {
                }
                prefs.edit().putInt("last_version_code", currentCode).apply()
            }
        } catch (_: Exception) {
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e(TAG, "FATAL CRASH on thread ${thread.name}: ${throwable.message}", throwable)
            try {
                val prefs = getSharedPreferences("usst_crash_logs", Context.MODE_PRIVATE)
                val crashMsg = "${System.currentTimeMillis()}|${thread.name}|${throwable.javaClass.simpleName}: ${throwable.message}\n${android.util.Log.getStackTraceString(throwable)}"
                prefs.edit().putString("last_crash", crashMsg).commit()
            } catch (e: Exception) {
                // Ignore
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
