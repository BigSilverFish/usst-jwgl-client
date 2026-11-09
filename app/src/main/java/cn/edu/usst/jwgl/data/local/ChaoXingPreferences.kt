package cn.edu.usst.jwgl.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import cn.edu.usst.jwgl.data.model.ChaoXingCourse
import cn.edu.usst.jwgl.data.model.ChaoXingUserInfo
import cn.edu.usst.jwgl.data.model.UnifiedBulletin
import cn.edu.usst.jwgl.data.model.UnifiedTodo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 超星学习通本地存储偏好 (硬件级加密存储 Cookie 与用户状态)
 */
class ChaoXingPreferences(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences = getOrCreateSecurePrefs(context)
    private val gson = Gson()

    companion object {
        private const val TAG = "ChaoXingPreferences"
        private const val PREF_NAME = "usst_chaoxing_prefs"
        private const val SECURE_PREF_NAME = "usst_chaoxing_secure_prefs"

        private const val KEY_COOKIES = "cx_cookies"
        private const val KEY_UID = "cx_uid"
        private const val KEY_USER_NAME = "cx_user_name"
        private const val KEY_STUDENT_NO = "cx_student_no"
        private const val KEY_SCHOOL_ID = "cx_school_id"
        private const val KEY_SCHOOL_NAME = "cx_school_name"
        private const val KEY_LAST_LOGIN_TIME = "cx_last_login_time"
        private const val KEY_IS_LOGGED_IN = "cx_is_logged_in"
        private const val KEY_CACHED_COURSES = "cx_cached_courses"
        private const val KEY_CACHED_TODOS = "cx_cached_todos"
        private const val KEY_CACHED_NOTICES = "cx_cached_notices"
        private const val KEY_CUSTOM_SEMESTERS = "cx_custom_semesters"
        internal val sessionLock = Any()

        @Volatile
        private var memoryFallbackInstance: SharedPreferences? = null

        @Volatile
        private var cachedSecurePrefs: SharedPreferences? = null

        private val volatileMemoryFallback: SharedPreferences
            get() = memoryFallbackInstance ?: synchronized(this) {
                memoryFallbackInstance ?: InMemorySharedPreferences().also { memoryFallbackInstance = it }
            }

        private fun getOrCreateSecurePrefs(context: Context): SharedPreferences {
            return cachedSecurePrefs ?: synchronized(this) {
                cachedSecurePrefs ?: createSecurePrefs(context.applicationContext).also {
                    // Keep retrying a failed keystore on later instances, as before.
                    if (it !== memoryFallbackInstance) cachedSecurePrefs = it
                }
            }
        }

        private fun createSecurePrefs(context: Context): SharedPreferences {
            return try {
                createEncryptedPrefsInternal(context)
            } catch (firstEx: Exception) {
                Log.w(TAG, "EncryptedSharedPreferences init failed, resetting keyset and retrying", firstEx)
                try {
                    context.deleteSharedPreferences(SECURE_PREF_NAME)
                    createEncryptedPrefsInternal(context)
                } catch (retryEx: Exception) {
                    Log.e(TAG, "Hardware keystore unavailable; using volatile RAM-only store", retryEx)
                    volatileMemoryFallback
                }
            }
        }

        private fun createEncryptedPrefsInternal(context: Context): SharedPreferences {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            return EncryptedSharedPreferences.create(
                SECURE_PREF_NAME,
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    private fun getAccountPrefix(studentId: String? = null): String {
        val sid = if (!studentId.isNullOrBlank()) studentId else {
            try {
                AuthPreferences.currentStudentId(context)
            } catch (_: Exception) {
                ""
            }
        }
        return if (sid.isNotBlank()) "${sid}_" else ""
    }

    /**
     * 保存超星登录成功后的 Cookies 与用户信息
     */
    fun saveLoginSession(
        cookies: String,
        uid: String,
        studentNo: String,
        userName: String? = null,
        schoolId: String = "207",
        schoolName: String = "上海理工大学"
    ) = synchronized(sessionLock) {
        val prefix = getAccountPrefix(studentNo)
        securePrefs.edit().apply {
            putString(prefix + KEY_COOKIES, cookies)
            putString(prefix + KEY_UID, uid)
            putString(prefix + KEY_STUDENT_NO, studentNo)
            if (!userName.isNullOrBlank()) {
                putString(prefix + KEY_USER_NAME, userName)
            }
            putString(prefix + KEY_SCHOOL_ID, schoolId)
            putString(prefix + KEY_SCHOOL_NAME, schoolName)
            putLong(prefix + KEY_LAST_LOGIN_TIME, System.currentTimeMillis())
            putBoolean(prefix + KEY_IS_LOGGED_IN, true)
        }.apply()
    }

    fun getCookies(studentId: String? = null): String? {
        val prefix = getAccountPrefix(studentId)
        return securePrefs.getString(prefix + KEY_COOKIES, null)
    }

    fun getUid(studentId: String? = null): String? {
        val prefix = getAccountPrefix(studentId)
        return securePrefs.getString(prefix + KEY_UID, null)
    }

    fun getUserName(studentId: String? = null): String {
        val prefix = getAccountPrefix(studentId)
        val name = securePrefs.getString(prefix + KEY_USER_NAME, null)
        if (!name.isNullOrBlank()) return name
        val gName = cn.edu.usst.jwgl.data.local.DataCacheManager(context).getProfile()?.name?.trim()
        if (!gName.isNullOrBlank()) return gName
        return "同学"
    }

    fun getStudentNo(studentId: String? = null): String {
        val prefix = getAccountPrefix(studentId)
        val no = securePrefs.getString(prefix + KEY_STUDENT_NO, null)
        if (!no.isNullOrBlank()) return no
        return studentId ?: ""
    }

    fun isLoggedIn(studentId: String? = null): Boolean {
        val prefix = getAccountPrefix(studentId)
        val isLogged = securePrefs.getBoolean(prefix + KEY_IS_LOGGED_IN, false)
        val cookies = getCookies(studentId)
        val uid = getUid(studentId)
        return isLogged && !cookies.isNullOrBlank() && !uid.isNullOrBlank() && uid != "0"
    }

    fun getLastLoginTime(studentId: String? = null): Long {
        val prefix = getAccountPrefix(studentId)
        return securePrefs.getLong(prefix + KEY_LAST_LOGIN_TIME, 0L)
    }

    fun clearSession(studentId: String? = null) = synchronized(sessionLock) {
        val prefix = getAccountPrefix(studentId)
        securePrefs.edit().apply {
            remove(prefix + KEY_COOKIES)
            remove(prefix + KEY_UID)
            remove(prefix + KEY_IS_LOGGED_IN)
            remove(prefix + KEY_LAST_LOGIN_TIME)
        }.apply()
    }

    fun saveCachedCourses(courses: List<ChaoXingCourse>, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        if (prefix.isEmpty()) return
        try {
            val json = gson.toJson(courses)
            prefs.edit()
                .putString(prefix + KEY_CACHED_COURSES, json)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveCachedCourses error", e)
        }
    }

    fun getCachedCourses(studentId: String? = null): List<ChaoXingCourse> {
        val prefix = getAccountPrefix(studentId)
        if (prefix.isEmpty()) return emptyList()
        // Legacy global snapshots have no recorded owner; never assign them to another account.
        val json = prefs.getString(prefix + KEY_CACHED_COURSES, null)
            ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ChaoXingCourse>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "getCachedCourses error", e)
            emptyList()
        }
    }

    /**
     * 获取指定超星课程的手动设置学期
     */
    fun getCustomSemester(courseId: String, classId: String? = null, studentId: String? = null): String? {
        val all = getAllCustomSemesters(studentId)
        if (!classId.isNullOrBlank()) {
            all["${courseId}_${classId}"]?.let { return it }
        }
        return all[courseId]
    }

    /**
     * 手动为超星课程设置归属学期 (传 null 或空字符串表示恢复自动推断)
     */
    fun setCustomSemester(courseId: String, classId: String? = null, semesterCode: String?, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        if (prefix.isEmpty()) return
        val all = getAllCustomSemesters(studentId).toMutableMap()
        val keyWithClass = if (!classId.isNullOrBlank()) "${courseId}_${classId}" else null
        if (semesterCode.isNullOrBlank()) {
            if (keyWithClass != null) all.remove(keyWithClass)
            all.remove(courseId)
        } else {
            if (keyWithClass != null) all[keyWithClass] = semesterCode.trim()
            all[courseId] = semesterCode.trim()
        }
        try {
            val json = gson.toJson(all)
            prefs.edit().putString(prefix + KEY_CUSTOM_SEMESTERS, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "setCustomSemester error", e)
        }
    }

    /**
     * 获取当前账号所有手动设置的超星课程学期字典 (courseId/key -> semesterCode)
     */
    fun getAllCustomSemesters(studentId: String? = null): Map<String, String> {
        val prefix = getAccountPrefix(studentId)
        if (prefix.isEmpty()) return emptyMap()
        val json = prefs.getString(prefix + KEY_CUSTOM_SEMESTERS, null)
            ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "getAllCustomSemesters error", e)
            emptyMap()
        }
    }

    fun saveCachedTodos(todos: List<UnifiedTodo>, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        if (prefix.isEmpty()) return
        try {
            val deduped = UnifiedTodo.deduplicateTodos(todos)
            val json = gson.toJson(deduped)
            prefs.edit()
                .putString(prefix + KEY_CACHED_TODOS, json)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveCachedTodos error", e)
        }
    }

    fun getCachedTodos(studentId: String? = null): List<UnifiedTodo> {
        val prefix = getAccountPrefix(studentId)
        if (prefix.isEmpty()) return emptyList()
        val json = prefs.getString(prefix + KEY_CACHED_TODOS, null)
            ?: return emptyList()
        return try {
            val type = object : TypeToken<List<UnifiedTodo>>() {}.type
            val list = gson.fromJson<List<UnifiedTodo>>(json, type) ?: emptyList()
            UnifiedTodo.deduplicateTodos(list)
        } catch (e: Exception) {
            Log.e(TAG, "getCachedTodos error", e)
            emptyList()
        }
    }

    fun saveCachedNotices(notices: List<UnifiedBulletin>, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        if (prefix.isEmpty()) return
        try {
            val json = gson.toJson(notices)
            prefs.edit()
                .putString(prefix + KEY_CACHED_NOTICES, json)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "saveCachedNotices error", e)
        }
    }

    fun getCachedNotices(studentId: String? = null): List<UnifiedBulletin> {
        val prefix = getAccountPrefix(studentId)
        if (prefix.isEmpty()) return emptyList()
        val json = prefs.getString(prefix + KEY_CACHED_NOTICES, null)
            ?: return emptyList()
        return try {
            val type = object : TypeToken<List<UnifiedBulletin>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "getCachedNotices error", e)
            emptyList()
        }
    }

    fun getUserInfo(studentId: String? = null): ChaoXingUserInfo {
        return ChaoXingUserInfo(
            uid = getUid(studentId) ?: "",
            name = getUserName(studentId),
            studentNo = getStudentNo(studentId),
            schoolId = securePrefs.getString(getAccountPrefix(studentId) + KEY_SCHOOL_ID, "207") ?: "207",
            schoolName = securePrefs.getString(getAccountPrefix(studentId) + KEY_SCHOOL_NAME, "上海理工大学") ?: "上海理工大学"
        )
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = java.util.concurrent.ConcurrentHashMap<String, Any>()
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            (map[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = key != null && map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { if (key != null) pending[key] = values?.toSet() }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { if (key != null) pending[key] = null }
            override fun clear(): SharedPreferences.Editor = apply { clearFlag = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearFlag) map.clear()
                for ((k, v) in pending) {
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
