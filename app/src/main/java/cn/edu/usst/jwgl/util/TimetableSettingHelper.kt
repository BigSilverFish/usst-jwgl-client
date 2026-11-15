package cn.edu.usst.jwgl.util

import android.content.Context

object TimetableSettingHelper {
    private const val PREF_NAME = "usst_timetable_pref"
    private const val KEY_DAY_PART_DIVIDERS = "day_part_dividers_enabled"
    private const val KEY_NAV_MODE = "phone_nav_mode" // 0 = 侧栏模式 (默认), 1 = 底栏模式
    private const val KEY_DOUBLE_TAP_DRAWER = "double_tap_timetable_open_drawer"
    private const val KEY_DRAWER_PROFILE_HEADER = "drawer_profile_header_enabled"
    private const val KEY_LAST_ACTIVE_TABLE_ID = "last_active_table_id"
    private const val KEY_LAST_ACTIVE_TABLE_NAME = "last_active_table_name"
    private const val KEY_LAST_ACTIVE_MAX_WEEK = "last_active_max_week"
    private const val KEY_LAST_ACTIVE_START_DATE = "last_active_start_date"
    private const val KEY_HAS_MIGRATED_LEGACY = "has_migrated_legacy_timetables"

    const val NAV_MODE_DRAWER = 0
    const val NAV_MODE_BOTTOM = 1

    private fun getAccountPrefix(context: Context, studentId: String? = null): String {
        val sid = if (!studentId.isNullOrBlank()) studentId else {
            try {
                cn.edu.usst.jwgl.data.local.AuthPreferences.currentStudentId(context)
            } catch (_: Exception) {
                ""
            }
        }
        return if (sid.isNotBlank()) "${sid}_" else ""
    }

    fun saveLastActiveTableInfo(context: Context, tableId: Int, tableName: String, maxWeek: Int, startDate: String, studentId: String? = null) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val prefix = getAccountPrefix(context, studentId)
        sp.edit()
            .putInt(prefix + KEY_LAST_ACTIVE_TABLE_ID, tableId)
            .putString(prefix + KEY_LAST_ACTIVE_TABLE_NAME, tableName)
            .putInt(prefix + KEY_LAST_ACTIVE_MAX_WEEK, maxWeek)
            .putString(prefix + KEY_LAST_ACTIVE_START_DATE, startDate)
            .apply()
    }

    fun getLastActiveTableId(context: Context, studentId: String? = null): Int {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val prefix = getAccountPrefix(context, studentId)
        if (sp.contains(prefix + KEY_LAST_ACTIVE_TABLE_ID)) {
            return sp.getInt(prefix + KEY_LAST_ACTIVE_TABLE_ID, -1)
        }
        return sp.getInt(KEY_LAST_ACTIVE_TABLE_ID, -1)
    }

    fun getLastActiveTableName(context: Context, studentId: String? = null): String {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val prefix = getAccountPrefix(context, studentId)
        if (sp.contains(prefix + KEY_LAST_ACTIVE_TABLE_NAME)) {
            return sp.getString(prefix + KEY_LAST_ACTIVE_TABLE_NAME, "") ?: ""
        }
        return sp.getString(KEY_LAST_ACTIVE_TABLE_NAME, "") ?: ""
    }

    fun getLastActiveMaxWeek(context: Context, studentId: String? = null): Int {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val prefix = getAccountPrefix(context, studentId)
        if (sp.contains(prefix + KEY_LAST_ACTIVE_MAX_WEEK)) {
            return sp.getInt(prefix + KEY_LAST_ACTIVE_MAX_WEEK, 25)
        }
        return sp.getInt(KEY_LAST_ACTIVE_MAX_WEEK, 25)
    }

    fun getLastActiveStartDate(context: Context, studentId: String? = null): String {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val prefix = getAccountPrefix(context, studentId)
        if (sp.contains(prefix + KEY_LAST_ACTIVE_START_DATE)) {
            return sp.getString(prefix + KEY_LAST_ACTIVE_START_DATE, "") ?: ""
        }
        return sp.getString(KEY_LAST_ACTIVE_START_DATE, "") ?: ""
    }

    fun hasMigratedLegacyTimetables(context: Context, studentId: String? = null): Boolean {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val prefix = getAccountPrefix(context, studentId)
        if (sp.contains(prefix + KEY_HAS_MIGRATED_LEGACY)) {
            return sp.getBoolean(prefix + KEY_HAS_MIGRATED_LEGACY, false)
        }
        return sp.getBoolean(KEY_HAS_MIGRATED_LEGACY, false)
    }

    fun setHasMigratedLegacyTimetables(context: Context, migrated: Boolean, studentId: String? = null) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val prefix = getAccountPrefix(context, studentId)
        sp.edit().putBoolean(prefix + KEY_HAS_MIGRATED_LEGACY, migrated).apply()
    }

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