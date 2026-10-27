package cn.edu.usst.jwgl.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import cn.edu.usst.jwgl.data.model.TronClassCourse
import cn.edu.usst.jwgl.data.model.TronClassTodo
import java.util.UUID

class TronClassPreferences(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences = getOrCreateSecurePrefs(context)
    private val gson = Gson()

    init {
        migratePlaintextTokensIfNeeded()
    }

    private fun getAccountPrefix(studentId: String? = null): String {
        val sid = if (!studentId.isNullOrBlank()) studentId else {
            try {
                AuthPreferences(context).getStudentId()
            } catch (_: Exception) {
                ""
            }
        }
        return if (sid.isNotBlank()) "${sid}_" else ""
    }

    private fun migratePlaintextTokensIfNeeded() {
        if (prefs.contains(KEY_SESSION_ID) || prefs.contains(KEY_REFRESH_TOKEN)) {
            val oldSessionId = prefs.getString(KEY_SESSION_ID, null)
            val oldRefreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
            val oldUserId = prefs.getLong(KEY_USER_ID, 0L)
            val oldUserName = prefs.getString(KEY_USER_NAME, null)
            val oldUserNo = prefs.getString(KEY_USER_NO, null)
            val oldUserEmail = prefs.getString(KEY_USER_EMAIL, null)

            val prefix = getAccountPrefix(oldUserNo)

            securePrefs.edit().apply {
                if (!oldSessionId.isNullOrBlank()) putString(prefix + KEY_SESSION_ID, oldSessionId)
                if (!oldRefreshToken.isNullOrBlank()) putString(prefix + KEY_REFRESH_TOKEN, oldRefreshToken)
                if (oldUserId > 0) putLong(prefix + KEY_USER_ID, oldUserId)
                if (!oldUserName.isNullOrBlank()) putString(prefix + KEY_USER_NAME, oldUserName)
                if (!oldUserNo.isNullOrBlank()) putString(prefix + KEY_USER_NO, oldUserNo)
                if (!oldUserEmail.isNullOrBlank()) putString(prefix + KEY_USER_EMAIL, oldUserEmail)
            }.apply()

            prefs.edit()
                .remove(KEY_SESSION_ID)
                .remove(KEY_REFRESH_TOKEN)
                .remove(KEY_USER_ID)
                .remove(KEY_USER_NAME)
                .remove(KEY_USER_NO)
                .remove(KEY_USER_EMAIL)
                .apply()
        }
    }

    companion object {
        private const val TAG = "TronClassPreferences"
        private const val PREF_NAME = "usst_tronclass_prefs"
        private const val SECURE_PREF_NAME = "usst_tronclass_secure_prefs"
        private const val KEY_SESSION_ID = "tc_session_id"
        private const val KEY_REFRESH_TOKEN = "tc_refresh_token"
        private const val KEY_USER_ID = "tc_user_id"
        private const val KEY_USER_NAME = "tc_user_name"
        private const val KEY_USER_NO = "tc_user_no"
        private const val KEY_USER_EMAIL = "tc_user_email"
        private const val KEY_DEVICE_ID = "tc_device_id"
        private const val KEY_LAST_SYNC_TIME = "tc_last_sync_time"
        private const val KEY_CACHED_TODOS = "tc_cached_todos"
        private const val KEY_CACHED_COURSES = "tc_cached_courses"
        private const val KEY_DDL_24H_ENABLED = "tc_ddl_24h_enabled"
        private const val KEY_DDL_URGENT_ENABLED = "tc_ddl_urgent_enabled"
        private const val KEY_DDL_URGENT_HOURS = "tc_ddl_urgent_hours"
        private const val KEY_ROLLCALL_MONITOR_ENABLED = "tc_rollcall_monitor_enabled"
        private const val KEY_ROLLCALL_HANDLED_IDS = "tc_rollcall_handled_ids"

        private val volatileMemoryFallback = InMemorySharedPreferences()

        @Volatile
        private var cachedSecurePrefs: SharedPreferences? = null

        @Volatile
        private var defaultInstance: TronClassPreferences? = null

        fun getInstance(context: Context): TronClassPreferences {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: TronClassPreferences(context.applicationContext).also { defaultInstance = it }
            }
        }

        private fun getOrCreateSecurePrefs(context: Context): SharedPreferences {
            return cachedSecurePrefs ?: synchronized(this) {
                cachedSecurePrefs ?: createSecurePrefs(context.applicationContext).also { cachedSecurePrefs = it }
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

    fun isLoggedIn(studentId: String? = null): Boolean {
        val sid = getSessionId(studentId)
        return !sid.isNullOrBlank()
    }

    fun getSessionId(studentId: String? = null): String? {
        val prefix = getAccountPrefix(studentId)
        val key = prefix + KEY_SESSION_ID
        var sid = securePrefs.getString(key, null)
        if (sid.isNullOrBlank() && prefix.isNotEmpty()) {
            // 兼容老版本未分桶的 Key
            sid = securePrefs.getString(KEY_SESSION_ID, null)
            if (!sid.isNullOrBlank()) {
                securePrefs.edit().putString(key, sid).apply()
            }
        }
        return sid
    }

    fun setSessionId(sessionId: String?, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        securePrefs.edit().putString(prefix + KEY_SESSION_ID, sessionId).apply()
    }

    fun getRefreshToken(studentId: String? = null): String? {
        val prefix = getAccountPrefix(studentId)
        val key = prefix + KEY_REFRESH_TOKEN
        var token = securePrefs.getString(key, null)
        if (token.isNullOrBlank() && prefix.isNotEmpty()) {
            token = securePrefs.getString(KEY_REFRESH_TOKEN, null)
            if (!token.isNullOrBlank()) {
                securePrefs.edit().putString(key, token).apply()
            }
        }
        return token
    }

    fun setRefreshToken(token: String?, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        securePrefs.edit().putString(prefix + KEY_REFRESH_TOKEN, token).apply()
    }

    fun getUserId(studentId: String? = null): Long {
        val prefix = getAccountPrefix(studentId)
        val key = prefix + KEY_USER_ID
        val uid = securePrefs.getLong(key, 0L)
        if (uid <= 0L && prefix.isNotEmpty()) {
            val legacy = securePrefs.getLong(KEY_USER_ID, 0L)
            if (legacy > 0L) {
                securePrefs.edit().putLong(key, legacy).apply()
                return legacy
            }
        }
        return uid
    }

    fun setUserId(userId: Long, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        securePrefs.edit().putLong(prefix + KEY_USER_ID, userId).apply()
    }

    fun getUserName(studentId: String? = null): String? {
        val prefix = getAccountPrefix(studentId)
        val key = prefix + KEY_USER_NAME
        var name = securePrefs.getString(key, null)
        if (name.isNullOrBlank() && prefix.isNotEmpty()) {
            name = securePrefs.getString(KEY_USER_NAME, null)
            if (!name.isNullOrBlank()) {
                securePrefs.edit().putString(key, name).apply()
            }
        }
        return name
    }

    fun setUserName(name: String?, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        securePrefs.edit().putString(prefix + KEY_USER_NAME, name).apply()
    }

    fun getUserNo(studentId: String? = null): String? {
        val prefix = getAccountPrefix(studentId)
        val key = prefix + KEY_USER_NO
        var userNo = securePrefs.getString(key, null)
        if (userNo.isNullOrBlank() && prefix.isNotEmpty()) {
            userNo = securePrefs.getString(KEY_USER_NO, null)
            if (!userNo.isNullOrBlank()) {
                securePrefs.edit().putString(key, userNo).apply()
            }
        }
        return userNo
    }

    fun setUserNo(userNo: String?, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        securePrefs.edit().putString(prefix + KEY_USER_NO, userNo).apply()
    }

    fun getUserEmail(studentId: String? = null): String? {
        val prefix = getAccountPrefix(studentId)
        val key = prefix + KEY_USER_EMAIL
        var email = securePrefs.getString(key, null)
        if (email.isNullOrBlank() && prefix.isNotEmpty()) {
            email = securePrefs.getString(KEY_USER_EMAIL, null)
            if (!email.isNullOrBlank()) {
                securePrefs.edit().putString(key, email).apply()
            }
        }
        return email
    }

    fun setUserEmail(email: String?, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        securePrefs.edit().putString(prefix + KEY_USER_EMAIL, email).apply()
    }

    /**
     * 关键特性：获取或生成指定账号专属的设备机器码 (deviceId)。
     * 每一个学号对应一个永久固定的随机 UUID，服务端判定为两台完全独立的设备，
     * 彻底破除“一台设备一门课只能签一人”的限制。
     */
    fun getDeviceId(studentId: String? = null): String {
        val prefix = getAccountPrefix(studentId)
        val key = prefix + KEY_DEVICE_ID
        var id = prefs.getString(key, null)
        if (id.isNullOrBlank()) {
            // 向下兼容：如果旧全局 key 存在，且是当前账号，则继承
            if (prefix.isNotEmpty() && prefs.contains(KEY_DEVICE_ID)) {
                id = prefs.getString(KEY_DEVICE_ID, null)
                prefs.edit().remove(KEY_DEVICE_ID).apply()
            }
            if (id.isNullOrBlank()) {
                id = UUID.randomUUID().toString()
            }
            prefs.edit().putString(key, id).apply()
        }
        return id
    }

    fun saveSession(
        sessionId: String,
        refreshToken: String? = null,
        userId: Long = 0L,
        userName: String? = null,
        userNo: String? = null,
        userEmail: String? = null,
        studentId: String? = null
    ) {
        val sid = if (!studentId.isNullOrBlank()) studentId else (userNo ?: "")
        val prefix = getAccountPrefix(sid)

        securePrefs.edit().apply {
            putString(prefix + KEY_SESSION_ID, sessionId)
            if (refreshToken != null) putString(prefix + KEY_REFRESH_TOKEN, refreshToken)
            if (userId > 0) putLong(prefix + KEY_USER_ID, userId)
            if (userName != null) putString(prefix + KEY_USER_NAME, userName)
            if (userNo != null) putString(prefix + KEY_USER_NO, userNo)
            if (userEmail != null) putString(prefix + KEY_USER_EMAIL, userEmail)
        }.apply()
    }

    fun clearSession(studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        securePrefs.edit().apply {
            remove(prefix + KEY_SESSION_ID)
            remove(prefix + KEY_REFRESH_TOKEN)
            remove(prefix + KEY_USER_ID)
            remove(prefix + KEY_USER_NAME)
            remove(prefix + KEY_USER_NO)
            remove(prefix + KEY_USER_EMAIL)
            if (prefix.isEmpty()) {
                remove(KEY_SESSION_ID)
                remove(KEY_REFRESH_TOKEN)
                remove(KEY_USER_ID)
                remove(KEY_USER_NAME)
                remove(KEY_USER_NO)
                remove(KEY_USER_EMAIL)
            }
        }.apply()

        prefs.edit().apply {
            remove(prefix + KEY_SESSION_ID)
            remove(prefix + KEY_REFRESH_TOKEN)
            remove(prefix + KEY_USER_ID)
            remove(prefix + KEY_USER_NAME)
            remove(prefix + KEY_USER_NO)
            remove(prefix + KEY_USER_EMAIL)
            remove(prefix + KEY_CACHED_TODOS)
            remove(prefix + KEY_CACHED_COURSES)
            if (prefix.isEmpty()) {
                remove(KEY_SESSION_ID)
                remove(KEY_REFRESH_TOKEN)
                remove(KEY_USER_ID)
                remove(KEY_USER_NAME)
                remove(KEY_USER_NO)
                remove(KEY_USER_EMAIL)
                remove(KEY_CACHED_TODOS)
                remove(KEY_CACHED_COURSES)
            }
        }.apply()
    }

    fun getLastSyncTime(): Long = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)

    fun setLastSyncTime(time: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, time).apply()
    }

    fun saveCachedTodos(todos: List<TronClassTodo>, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        val json = gson.toJson(todos)
        prefs.edit().putString(prefix + KEY_CACHED_TODOS, json).apply()
    }

    fun getCachedTodos(studentId: String? = null): List<TronClassTodo> {
        val prefix = getAccountPrefix(studentId)
        val json = prefs.getString(prefix + KEY_CACHED_TODOS, null) ?: prefs.getString(KEY_CACHED_TODOS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassTodo>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedCourses(courses: List<TronClassCourse>, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        val json = gson.toJson(courses)
        prefs.edit().putString(prefix + KEY_CACHED_COURSES, json).apply()
    }

    fun getCachedCourses(studentId: String? = null): List<TronClassCourse> {
        val prefix = getAccountPrefix(studentId)
        val json = prefs.getString(prefix + KEY_CACHED_COURSES, null) ?: prefs.getString(KEY_CACHED_COURSES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassCourse>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isDdlReminder24hEnabled(): Boolean = prefs.getBoolean(KEY_DDL_24H_ENABLED, true)

    fun setDdlReminder24hEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DDL_24H_ENABLED, enabled).apply()
    }

    fun isDdlReminderUrgentEnabled(): Boolean = prefs.getBoolean(KEY_DDL_URGENT_ENABLED, true)

    fun setDdlReminderUrgentEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DDL_URGENT_ENABLED, enabled).apply()
    }

    fun getDdlReminderUrgentHours(): Int = prefs.getInt(KEY_DDL_URGENT_HOURS, 2)

    fun setDdlReminderUrgentHours(hours: Int) {
        val clamped = hours.coerceIn(1, 3)
        prefs.edit().putInt(KEY_DDL_URGENT_HOURS, clamped).apply()
    }

    fun isAnyDdlReminderEnabled(): Boolean = isDdlReminder24hEnabled() || isDdlReminderUrgentEnabled()

    /**
     * 上课时后台签到监听总开关
     */
    fun isRollcallMonitorEnabled(): Boolean = prefs.getBoolean(KEY_ROLLCALL_MONITOR_ENABLED, false)

    fun setRollcallMonitorEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ROLLCALL_MONITOR_ENABLED, enabled).apply()
    }

    /**
     * 判断指定签到事件是否已处理过（24小时内有效，避免重复弹窗与重复提交）
     */
    fun isRollcallHandled(rollcallId: Long): Boolean {
        if (rollcallId <= 0L) return false
        val now = System.currentTimeMillis()
        val set = prefs.getStringSet(KEY_ROLLCALL_HANDLED_IDS, emptySet()) ?: emptySet()
        for (item in set) {
            val parts = item.split(":")
            val id = parts.getOrNull(0)?.toLongOrNull() ?: continue
            val time = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            if (id == rollcallId && (now - time) < 24 * 3600 * 1000L) {
                return true
            }
        }
        return false
    }

    /**
     * 记录已处理过的签到事件
     */
    fun markRollcallHandled(rollcallId: Long) {
        if (rollcallId <= 0L) return
        val now = System.currentTimeMillis()
        val oldSet = prefs.getStringSet(KEY_ROLLCALL_HANDLED_IDS, emptySet()) ?: emptySet()
        val newSet = mutableSetOf<String>()
        for (item in oldSet) {
            val parts = item.split(":")
            val time = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            if ((now - time) < 24 * 3600 * 1000L) {
                newSet.add(item)
            }
        }
        newSet.add("$rollcallId:$now")
        prefs.edit().putStringSet(KEY_ROLLCALL_HANDLED_IDS, newSet).apply()
    }
}
