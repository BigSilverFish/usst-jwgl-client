package cn.edu.usst.jwgl.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import cn.edu.usst.jwgl.data.model.ExamItem
import cn.edu.usst.jwgl.data.model.GradeReport
import cn.edu.usst.jwgl.data.model.StudentProfile
import cn.edu.usst.jwgl.data.model.TimetableData
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataCacheManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences = createSecurePrefs(context)
    private val gson = Gson()

    companion object {
        private const val TAG = "DataCacheManager"
        private const val PREF_NAME = "usst_jwgl_data_cache"
        private const val SECURE_PREF_NAME = "usst_jwgl_secure_data_cache"

        private const val KEY_PROFILE_JSON = "cache_profile_json"
        private const val KEY_PROFILE_TIME = "cache_profile_time"

        private const val PREFIX_TIMETABLE_JSON = "cache_timetable_json_"
        private const val PREFIX_TIMETABLE_TIME = "cache_timetable_time_"

        private const val PREFIX_EXAM_JSON = "cache_exam_json_"
        private const val PREFIX_EXAM_TIME = "cache_exam_time_"

        private const val KEY_GRADES_JSON = "cache_grades_json"
        private const val KEY_GRADES_TIME = "cache_grades_time"

        private fun createSecurePrefs(context: Context): SharedPreferences {
            return try {
                val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
                EncryptedSharedPreferences.create(
                    SECURE_PREF_NAME,
                    masterKeyAlias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.w(TAG, "Hardware keystore securePrefs init failed, falling back to private prefs", e)
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    private fun getAccountPrefix(studentId: String? = null): String {
        val sid = if (!studentId.isNullOrBlank()) studentId else AuthPreferences(context).getStudentId()
        return if (sid.isNotBlank()) "${sid}_" else ""
    }

    // --- Profile Cache (Encrypted) ---
    fun saveProfile(profile: StudentProfile, studentId: String? = null) {
        val targetSid = if (!studentId.isNullOrBlank()) studentId else profile.studentId.ifBlank { AuthPreferences(context).getStudentId() }
        // 防御性保护：若传入档案的关键信息（姓名、学院、专业）均为空，且本地已有有效档案，坚决拒绝覆盖合法缓存
        if (profile.name.isBlank() && profile.college.isBlank() && profile.major.isBlank()) {
            val existing = getProfile(targetSid)
            if (existing != null && (existing.name.isNotBlank() || existing.college.isNotBlank() || existing.major.isNotBlank())) {
                Log.w(TAG, "Rejecting saving empty profile over existing valid profile")
                return
            }
        }

        val prefix = getAccountPrefix(targetSid)
        val keyJson = prefix + KEY_PROFILE_JSON
        val keyTime = prefix + KEY_PROFILE_TIME
        val json = gson.toJson(profile)

        securePrefs.edit()
            .putString(keyJson, json)
            .putLong(keyTime, System.currentTimeMillis())
            .apply()

        if (prefs !== securePrefs && prefs.contains(keyJson)) {
            prefs.edit().remove(keyJson).remove(keyTime).apply()
        }

        // 同步更新多账号保险箱元数据（姓名、学院、专业、班级）
        try {
            val vault = AccountVault.getInstance(context)
            val accId = if (targetSid.isNotBlank()) targetSid else vault.getActiveAccountId()
            if (accId.isNotBlank()) {
                val accounts = vault.getAccounts()
                val acc = accounts.find { it.studentId == accId }
                if (acc != null) {
                    var changed = false
                    if (profile.name.isNotBlank() && acc.studentName != profile.name) {
                        acc.studentName = profile.name
                        changed = true
                    }
                    if (profile.college.isNotBlank() && acc.college != profile.college) {
                        acc.college = profile.college
                        changed = true
                    }
                    if (profile.major.isNotBlank() && acc.major != profile.major) {
                        acc.major = profile.major
                        changed = true
                    }
                    if (profile.className.isNotBlank() && acc.className != profile.className) {
                        acc.className = profile.className
                        changed = true
                    }
                    if (changed) {
                        vault.updateAccountMetadata(acc)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync metadata to AccountVault", e)
        }
    }

    fun clearProfile(studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        val keyJson = prefix + KEY_PROFILE_JSON
        val keyTime = prefix + KEY_PROFILE_TIME
        securePrefs.edit().remove(keyJson).remove(keyTime).apply()
        if (prefs !== securePrefs) {
            prefs.edit().remove(keyJson).remove(keyTime).apply()
        }
    }

    fun getProfile(studentId: String? = null): StudentProfile? {
        val prefix = getAccountPrefix(studentId)
        val keyJson = prefix + KEY_PROFILE_JSON
        val keyTime = prefix + KEY_PROFILE_TIME

        var json = securePrefs.getString(keyJson, null)
        if (json.isNullOrEmpty() && prefs !== securePrefs) {
            json = prefs.getString(keyJson, null)
            if (!json.isNullOrEmpty()) {
                securePrefs.edit()
                    .putString(keyJson, json)
                    .putLong(keyTime, prefs.getLong(keyTime, System.currentTimeMillis()))
                    .apply()
                prefs.edit().remove(keyJson).remove(keyTime).apply()
            }
        }

        // 向下兼容：如果分桶未命中，尝试读取未分桶的旧缓存并迁移
        if (json.isNullOrEmpty()) {
            json = securePrefs.getString(KEY_PROFILE_JSON, null) ?: prefs.getString(KEY_PROFILE_JSON, null)
            if (!json.isNullOrEmpty()) {
                try {
                    val legacyProfile = gson.fromJson(json, StudentProfile::class.java)
                    val targetSid = if (!studentId.isNullOrBlank()) studentId else AuthPreferences(context).getStudentId()
                    // 仅当旧档案学号与目标学号匹配或无学号时予以继承
                    if (legacyProfile != null && (targetSid.isBlank() || legacyProfile.studentId.isBlank() || legacyProfile.studentId == targetSid)) {
                        securePrefs.edit()
                            .putString(keyJson, json)
                            .putLong(keyTime, securePrefs.getLong(KEY_PROFILE_TIME, System.currentTimeMillis()))
                            .apply()
                        return legacyProfile
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse legacy StudentProfile during migration", e)
                }
            }
        }

        if (json.isNullOrEmpty()) return null
        return try {
            gson.fromJson(json, StudentProfile::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached StudentProfile", e)
            null
        }
    }

    fun getProfileUpdateTime(studentId: String? = null): Long {
        val prefix = getAccountPrefix(studentId)
        val keyTime = prefix + KEY_PROFILE_TIME
        val t = securePrefs.getLong(keyTime, 0L)
        if (t > 0L) return t
        val p = prefs.getLong(keyTime, 0L)
        if (p > 0L) return p
        // 回退读取旧 key
        val oldT = securePrefs.getLong(KEY_PROFILE_TIME, 0L)
        if (oldT > 0L) return oldT
        return prefs.getLong(KEY_PROFILE_TIME, 0L)
    }

    // --- Timetable Cache ---
    fun saveTimetable(xnm: String, xqm: String, data: TimetableData, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        val baseKey = "${xnm}_${xqm}"
        val keyJson = prefix + PREFIX_TIMETABLE_JSON + baseKey
        val keyTime = prefix + PREFIX_TIMETABLE_TIME + baseKey
        val json = gson.toJson(data)
        prefs.edit()
            .putString(keyJson, json)
            .putLong(keyTime, System.currentTimeMillis())
            .apply()
    }

    fun getTimetable(xnm: String, xqm: String, studentId: String? = null): TimetableData? {
        val prefix = getAccountPrefix(studentId)
        val baseKey = "${xnm}_${xqm}"
        val keyJson = prefix + PREFIX_TIMETABLE_JSON + baseKey
        var json = prefs.getString(keyJson, null)

        // 向下兼容旧无前缀 key
        if (json == null) {
            val oldKeyJson = PREFIX_TIMETABLE_JSON + baseKey
            json = prefs.getString(oldKeyJson, null)
            if (json != null) {
                // 迁移到当前账号
                val keyTime = prefix + PREFIX_TIMETABLE_TIME + baseKey
                val oldTime = prefs.getLong(PREFIX_TIMETABLE_TIME + baseKey, System.currentTimeMillis())
                prefs.edit().putString(keyJson, json).putLong(keyTime, oldTime).apply()
            }
        }

        if (json == null) return null
        return try {
            gson.fromJson(json, TimetableData::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached TimetableData for key $keyJson", e)
            null
        }
    }

    fun getTimetableUpdateTime(xnm: String, xqm: String, studentId: String? = null): Long {
        val prefix = getAccountPrefix(studentId)
        val baseKey = "${xnm}_${xqm}"
        val keyTime = prefix + PREFIX_TIMETABLE_TIME + baseKey
        val t = prefs.getLong(keyTime, 0L)
        if (t > 0L) return t
        return prefs.getLong(PREFIX_TIMETABLE_TIME + baseKey, 0L)
    }

    fun getAllCachedTimetables(studentId: String? = null): List<TimetableData> {
        val prefix = getAccountPrefix(studentId)
        val targetPrefix = prefix + PREFIX_TIMETABLE_JSON
        val list = mutableListOf<TimetableData>()
        for ((k, v) in prefs.all) {
            if (k.startsWith(targetPrefix) && v is String) {
                try {
                    val data = gson.fromJson(v, TimetableData::class.java)
                    if (data != null) list.add(data)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse cached TimetableData for key $k", e)
                }
            }
        }
        // 若当前分桶为空且有未分桶旧数据，进行一次尝试
        if (list.isEmpty() && prefix.isNotEmpty()) {
            for ((k, v) in prefs.all) {
                if (k.startsWith(PREFIX_TIMETABLE_JSON) && !k.contains("_cache_timetable_json_") && v is String) {
                    try {
                        val data = gson.fromJson(v, TimetableData::class.java)
                        if (data != null) list.add(data)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse legacy cached TimetableData for key $k", e)
                    }
                }
            }
        }
        return list
    }

    // --- Grades Cache (Encrypted) ---
    fun saveGrades(report: GradeReport, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        val keyJson = prefix + KEY_GRADES_JSON
        val keyTime = prefix + KEY_GRADES_TIME
        val json = gson.toJson(report)
        securePrefs.edit()
            .putString(keyJson, json)
            .putLong(keyTime, System.currentTimeMillis())
            .apply()
        if (prefs !== securePrefs && prefs.contains(keyJson)) {
            prefs.edit().remove(keyJson).remove(keyTime).apply()
        }
    }

    fun getGrades(studentId: String? = null): GradeReport? {
        val prefix = getAccountPrefix(studentId)
        val keyJson = prefix + KEY_GRADES_JSON
        val keyTime = prefix + KEY_GRADES_TIME

        var json = securePrefs.getString(keyJson, null)
        if (json.isNullOrEmpty() && prefs !== securePrefs) {
            json = prefs.getString(keyJson, null)
            if (!json.isNullOrEmpty()) {
                securePrefs.edit()
                    .putString(keyJson, json)
                    .putLong(keyTime, prefs.getLong(keyTime, System.currentTimeMillis()))
                    .apply()
                prefs.edit().remove(keyJson).remove(keyTime).apply()
            }
        }

        // 向下兼容读取旧成绩缓存
        if (json.isNullOrEmpty()) {
            json = securePrefs.getString(KEY_GRADES_JSON, null) ?: prefs.getString(KEY_GRADES_JSON, null)
            if (!json.isNullOrEmpty()) {
                securePrefs.edit()
                    .putString(keyJson, json)
                    .putLong(keyTime, securePrefs.getLong(KEY_GRADES_TIME, System.currentTimeMillis()))
                    .apply()
            }
        }

        if (json.isNullOrEmpty()) return null
        return try {
            gson.fromJson(json, GradeReport::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached GradeReport", e)
            null
        }
    }

    fun getGradesUpdateTime(studentId: String? = null): Long {
        val prefix = getAccountPrefix(studentId)
        val keyTime = prefix + KEY_GRADES_TIME
        val t = securePrefs.getLong(keyTime, 0L)
        if (t > 0L) return t
        val p = prefs.getLong(keyTime, 0L)
        if (p > 0L) return p
        val oldT = securePrefs.getLong(KEY_GRADES_TIME, 0L)
        if (oldT > 0L) return oldT
        return prefs.getLong(KEY_GRADES_TIME, 0L)
    }

    // --- Exams Cache ---
    fun saveExams(xnm: String, xqm: String, exams: List<ExamItem>, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        val baseKey = "${xnm}_${xqm}"
        val keyJson = prefix + PREFIX_EXAM_JSON + baseKey
        val keyTime = prefix + PREFIX_EXAM_TIME + baseKey
        val json = gson.toJson(exams)
        prefs.edit()
            .putString(keyJson, json)
            .putLong(keyTime, System.currentTimeMillis())
            .apply()
    }

    fun getExams(xnm: String, xqm: String, studentId: String? = null): List<ExamItem> {
        val prefix = getAccountPrefix(studentId)
        val baseKey = "${xnm}_${xqm}"
        val keyJson = prefix + PREFIX_EXAM_JSON + baseKey
        var json = prefs.getString(keyJson, null)

        // 向下兼容
        if (json == null) {
            val oldKeyJson = PREFIX_EXAM_JSON + baseKey
            json = prefs.getString(oldKeyJson, null)
            if (json != null) {
                val keyTime = prefix + PREFIX_EXAM_TIME + baseKey
                val oldTime = prefs.getLong(PREFIX_EXAM_TIME + baseKey, System.currentTimeMillis())
                prefs.edit().putString(keyJson, json).putLong(keyTime, oldTime).apply()
            }
        }

        if (json == null) return emptyList()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<ExamItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse cached ExamItems for key $keyJson", e)
            emptyList()
        }
    }

    fun clearExams(studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        val editor = prefs.edit()
        for (k in prefs.all.keys) {
            if (k.startsWith(prefix + PREFIX_EXAM_JSON) || k.startsWith(prefix + PREFIX_EXAM_TIME)) {
                editor.remove(k)
            }
        }
        editor.apply()
    }

    fun getAllCachedExams(studentId: String? = null): List<ExamItem> {
        val prefix = getAccountPrefix(studentId)
        val targetPrefix = prefix + PREFIX_EXAM_JSON
        val all = prefs.all
        val list = mutableListOf<ExamItem>()
        for ((k, v) in all) {
            if (k.startsWith(targetPrefix) && v is String) {
                try {
                    val type = object : com.google.gson.reflect.TypeToken<List<ExamItem>>() {}.type
                    val items = gson.fromJson<List<ExamItem>>(v, type)
                    if (items != null) list.addAll(items)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse cached exam item for key $k", e)
                }
            }
        }
        if (list.isEmpty() && prefix.isNotEmpty()) {
            for ((k, v) in all) {
                if (k.startsWith(PREFIX_EXAM_JSON) && !k.contains("_cache_exam_json_") && v is String) {
                    try {
                        val type = object : com.google.gson.reflect.TypeToken<List<ExamItem>>() {}.type
                        val items = gson.fromJson<List<ExamItem>>(v, type)
                        if (items != null) list.addAll(items)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse legacy cached exam item for key $k", e)
                    }
                }
            }
        }
        return list.distinctBy { "${it.courseName}_${it.examTime}_${it.location}" }
            .filterNot { it.courseCode == "1001001" || it.courseCode == "1002002" }
    }

    // --- Format update time helper ---
    fun formatUpdateTime(timestamp: Long): String {
        if (timestamp <= 0L) return "尚未更新"
        val diff = System.currentTimeMillis() - timestamp
        if (diff < 60_000) return "刚刚更新"
        if (diff < 3600_000) return "${diff / 60_000}分钟前更新"
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return "更新于 " + sdf.format(Date(timestamp))
    }

    /**
     * 清理指定账号专属的本地缓存
     */
    fun clearAccountCache(studentId: String) {
        if (studentId.isBlank()) return
        val prefix = "${studentId}_"
        val pEditor = prefs.edit()
        for (k in prefs.all.keys) {
            if (k.startsWith(prefix)) {
                pEditor.remove(k)
            }
        }
        pEditor.apply()

        val sEditor = securePrefs.edit()
        for (k in securePrefs.all.keys) {
            if (k.startsWith(prefix)) {
                sEditor.remove(k)
            }
        }
        sEditor.apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        if (prefs !== securePrefs) {
            securePrefs.edit().clear().apply()
        }
    }
}
