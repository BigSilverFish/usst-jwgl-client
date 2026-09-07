package cn.edu.usst.jwgl

import android.app.Application
import cn.edu.usst.jwgl.data.remote.RemoteConfigManager
import cn.edu.usst.jwgl.util.ThemeManager

class UsstApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Apply saved theme mode early before activities inflate
        ThemeManager.applySavedTheme(this)
        RemoteConfigManager.init(this)
    }
}
