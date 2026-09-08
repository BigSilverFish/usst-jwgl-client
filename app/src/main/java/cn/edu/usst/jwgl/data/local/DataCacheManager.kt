package cn.edu.usst.jwgl.data.local

import android.content.Context
import android.content.SharedPreferences
import cn.edu.usst.jwgl.data.model.GradeReport
import cn.edu.usst.jwgl.data.model.StudentProfile
import cn.edu.usst.jwgl.data.model.TimetableData
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataCacheManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREF_NAME = "usst_jwgl_data_cache"

        private const val KEY_PROFILE_JSON = "cache_profile_json"
        private const val KEY_PROFILE_TIME = "cache_profile_time"

        private const val PREFIX_TIMETABLE_JSON = "cache_timetable_json_"
        private const val PREFIX_TIMETABLE_TIME = "cache_timetable_time_"

        private const val KEY_GRADES_JSON = "cache_grades_json"
        private const val KEY_GRADES_TIME = "cache_grades_time"
    }

    // --- Profile Cache ---
    fun saveProfile(profile: StudentProfile) {
        val json = gson.toJson(profile)
        prefs.edit()
            .putString(KEY_PROFILE_JSON, json)
            .putLong(KEY_PROFILE_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getProfile(): StudentProfile? {
        val json = prefs.getString(KEY_PROFILE_JSON, null) ?: return null
        return try {
            gson.fromJson(json, StudentProfile::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun getProfileUpdateTime(): Long = prefs.getLong(KEY_PROFILE_TIME, 0L)

    // --- Timetable Cache ---
    fun saveTimetable(xnm: String, xqm: String, data: TimetableData) {
        val key = "${xnm}_${xqm}"
        val json = gson.toJson(data)
        prefs.edit()
            .putString(PREFIX_TIMETABLE_JSON + key, json)
            .putLong(PREFIX_TIMETABLE_TIME + key, System.currentTimeMillis())
            .apply()
    }

    fun getTimetable(xnm: String, xqm: String): TimetableData? {
        val key = "${xnm}_${xqm}"
        val json = prefs.getString(PREFIX_TIMETABLE_JSON + key, null) ?: return null
        return try {
            gson.fromJson(json, TimetableData::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun getTimetableUpdateTime(xnm: String, xqm: String): Long {
        val key = "${xnm}_${xqm}"
        return prefs.getLong(PREFIX_TIMETABLE_TIME + key, 0L)
    }

    fun getAllCachedTimetables(): List<TimetableData> {
        val list = mutableListOf<TimetableData>()
        for ((k, v) in prefs.all) {
            if (k.startsWith(PREFIX_TIMETABLE_JSON) && v is String) {
                try {
                    val data = gson.fromJson(v, TimetableData::class.java)
                    if (data != null) list.add(data)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        return list
    }

    // --- Grades Cache ---
    fun saveGrades(report: GradeReport) {
        val json = gson.toJson(report)
        prefs.edit()
            .putString(KEY_GRADES_JSON, json)
            .putLong(KEY_GRADES_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getGrades(): GradeReport? {
        val json = prefs.getString(KEY_GRADES_JSON, null) ?: return null
        return try {
            gson.fromJson(json, GradeReport::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun getGradesUpdateTime(): Long = prefs.getLong(KEY_GRADES_TIME, 0L)

    // --- Format update time helper ---
    fun formatUpdateTime(timestamp: Long): String {
        if (timestamp <= 0L) return "尚未更新"
        val diff = System.currentTimeMillis() - timestamp
        if (diff < 60_000) return "刚刚更新"
        if (diff < 3600_000) return "${diff / 60_000}分钟前更新"
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return "更新于 " + sdf.format(Date(timestamp))
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
