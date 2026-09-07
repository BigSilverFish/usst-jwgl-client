package cn.edu.usst.jwgl.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {
    private const val PREF_NAME = "usst_theme_pref"
    private const val KEY_THEME_MODE = "theme_mode"

    const val MODE_SYSTEM = 0
    const val MODE_LIGHT = 1
    const val MODE_DARK = 2

    fun getThemeMode(context: Context): Int {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_THEME_MODE, MODE_SYSTEM)
    }

    fun setThemeMode(context: Context, mode: Int) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_THEME_MODE, mode).apply()
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

    fun getThemeModeName(mode: Int): String {
        return when (mode) {
            MODE_LIGHT -> "浅色模式"
            MODE_DARK -> "深色模式"
            else -> "跟随系统"
        }
    }
}
