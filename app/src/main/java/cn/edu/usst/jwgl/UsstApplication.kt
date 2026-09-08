package cn.edu.usst.jwgl

import android.app.Application
import android.content.Context
import cn.edu.usst.jwgl.data.remote.RemoteConfigManager
import cn.edu.usst.jwgl.util.ThemeManager

class UsstApplication : Application() {
    companion object {
        private const val TAG = "UsstApplication"
    }

    override fun onCreate() {
        super.onCreate()
        setupCrashHandler()
        // Apply saved theme mode early before activities inflate
        ThemeManager.applySavedTheme(this)
        RemoteConfigManager.init(this)
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
