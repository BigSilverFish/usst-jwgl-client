package cn.edu.usst.jwgl.util

import android.content.Context

object TimetableSettingHelper {
    private const val PREF_NAME = "usst_timetable_pref"
    private const val KEY_DAY_PART_DIVIDERS = "day_part_dividers_enabled"

    fun isDayPartDividersEnabled(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_DAY_PART_DIVIDERS, true)
    }

    fun setDayPartDividersEnabled(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_DAY_PART_DIVIDERS, enabled).apply()
    }
}