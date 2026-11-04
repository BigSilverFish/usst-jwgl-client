package cn.edu.usst.jwgl.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {
    private const val PREF_NAME = "usst_theme_pref"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_THEME_MIGRATION_V3 = "theme_migration_v3"

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    fun getThemeMode(context: Context): Int {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        // 自动纠偏：如果历史版本遗留了被篡改的 MODE_LIGHT 且未显式确认，自动重置为跟随系统
        if (!sp.getBoolean(KEY_THEME_MIGRATION_V3, false)) {
            val current = sp.getInt(KEY_THEME_MODE, MODE_SYSTEM)
            if (current == MODE_LIGHT) {
                sp.edit().putInt(KEY_THEME_MODE, MODE_SYSTEM).putBoolean(KEY_THEME_MIGRATION_V3, true).apply()
                return MODE_SYSTEM
            }
            sp.edit().putBoolean(KEY_THEME_MIGRATION_V3, true).apply()
        }
        return sp.getInt(KEY_THEME_MODE, MODE_SYSTEM)
    }

    fun setThemeMode(context: Context, mode: Int) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putInt(KEY_THEME_MODE, mode)
            .putBoolean(KEY_THEME_MIGRATION_V3, true)
            .apply()
        applyTheme(mode)
    }

    fun applySavedTheme(context: Context) {
        val mode = getThemeMode(context)
        applyTheme(mode)
    }

    fun applyTheme(mode: Int) {
        val nightMode = when (mode) {
            MODE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            MODE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    fun isNightMode(context: Context): Boolean {
        val mode = getThemeMode(context)
        return when (mode) {
            MODE_LIGHT -> false
            MODE_DARK -> true
            else -> (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun getThemeModeName(mode: Int): String {
        return when (mode) {
            MODE_LIGHT -> "浅色模式"
            MODE_DARK -> "深色模式"
            else -> "跟随系统"
        }
    }
}
