package cn.edu.usst.jwgl.util

import android.content.Context

object TimetableSettingHelper {
    private const val PREF_NAME = "usst_timetable_pref"
    private const val KEY_DAY_PART_DIVIDERS = "day_part_dividers_enabled"
    private const val KEY_NAV_MODE = "phone_nav_mode" // 0 = 侧栏模式 (默认), 1 = 底栏模式
    private const val KEY_DOUBLE_TAP_DRAWER = "double_tap_timetable_open_drawer"
    private const val KEY_DRAWER_PROFILE_HEADER = "drawer_profile_header_enabled"

    const val NAV_MODE_DRAWER = 0
    const val NAV_MODE_BOTTOM = 1

    fun isDrawerProfileHeaderEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_DRAWER_PROFILE_HEADER, true)
    }

    fun setDrawerProfileHeaderEnabled(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_DRAWER_PROFILE_HEADER, enabled).apply()
    }

    fun isDayPartDividersEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_DAY_PART_DIVIDERS, true)
    }

    fun setDayPartDividersEnabled(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_DAY_PART_DIVIDERS, enabled).apply()
    }

    fun getPhoneNavMode(context: Context): Int {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_NAV_MODE, NAV_MODE_DRAWER)
    }

    fun setPhoneNavMode(context: Context, mode: Int) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_NAV_MODE, mode).apply()
    }

    fun getPhoneNavModeName(mode: Int): String {
        return if (mode == NAV_MODE_BOTTOM) "底栏模式 (底部四栏导航)" else "侧栏模式 (左侧抽屉导航)"
    }

    fun isDoubleTapOpenDrawerEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_DOUBLE_TAP_DRAWER, true)
    }

    fun setDoubleTapOpenDrawerEnabled(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_DOUBLE_TAP_DRAWER, enabled).apply()
    }
}