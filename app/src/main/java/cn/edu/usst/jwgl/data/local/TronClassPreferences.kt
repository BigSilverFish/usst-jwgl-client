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

class TronClassPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences = createSecurePrefs(context)
    private val gson = Gson()

    init {
        migratePlaintextTokensIfNeeded()
    }

    private fun migratePlaintextTokensIfNeeded() {
        if (prefs.contains(KEY_SESSION_ID) || prefs.contains(KEY_REFRESH_TOKEN)) {
            val oldSessionId = prefs.getString(KEY_SESSION_ID, null)
            val oldRefreshToken = prefs.getString(KEY_REFRESH_TOKEN, null)
            val oldUserId = prefs.getLong(KEY_USER_ID, 0L)
            val oldUserName = prefs.getString(KEY_USER_NAME, null)
            val oldUserNo = prefs.getString(KEY_USER_NO, null)
            val oldUserEmail = prefs.getString(KEY_USER_EMAIL, null)

            securePrefs.edit().apply {
                if (!oldSessionId.isNullOrBlank()) putString(KEY_SESSION_ID, oldSessionId)
                if (!oldRefreshToken.isNullOrBlank()) putString(KEY_REFRESH_TOKEN, oldRefreshToken)
                if (oldUserId > 0) putLong(KEY_USER_ID, oldUserId)
                if (!oldUserName.isNullOrBlank()) putString(KEY_USER_NAME, oldUserName)
                if (!oldUserNo.isNullOrBlank()) putString(KEY_USER_NO, oldUserNo)
                if (!oldUserEmail.isNullOrBlank()) putString(KEY_USER_EMAIL, oldUserEmail)
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

        private val volatileMemoryFallback = InMemorySharedPreferences()

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

    fun isLoggedIn(): Boolean {
        val sid = getSessionId()
        return !sid.isNullOrBlank()
    }

    fun getSessionId(): String? = securePrefs.getString(KEY_SESSION_ID, null)

    fun setSessionId(sessionId: String?) {
        securePrefs.edit().putString(KEY_SESSION_ID, sessionId).apply()
    }

    fun getRefreshToken(): String? = securePrefs.getString(KEY_REFRESH_TOKEN, null)

    fun setRefreshToken(token: String?) {
        securePrefs.edit().putString(KEY_REFRESH_TOKEN, token).apply()
    }

    fun getUserId(): Long = securePrefs.getLong(KEY_USER_ID, 0L)

    fun setUserId(userId: Long) {
        securePrefs.edit().putLong(KEY_USER_ID, userId).apply()
    }

    fun getUserName(): String? = securePrefs.getString(KEY_USER_NAME, null)

    fun setUserName(name: String?) {
        securePrefs.edit().putString(KEY_USER_NAME, name).apply()
    }

    fun getUserNo(): String? = securePrefs.getString(KEY_USER_NO, null)

    fun setUserNo(userNo: String?) {
        securePrefs.edit().putString(KEY_USER_NO, userNo).apply()
    }

    fun getUserEmail(): String? = securePrefs.getString(KEY_USER_EMAIL, null)

    fun setUserEmail(email: String?) {
        securePrefs.edit().putString(KEY_USER_EMAIL, email).apply()
    }

    fun getDeviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun saveSession(
        sessionId: String,
        refreshToken: String? = null,
        userId: Long = 0L,
        userName: String? = null,
        userNo: String? = null,
        userEmail: String? = null
    ) {
        securePrefs.edit()
            .putString(KEY_SESSION_ID, sessionId)
            .apply {
                if (refreshToken != null) putString(KEY_REFRESH_TOKEN, refreshToken)
                if (userId > 0) putLong(KEY_USER_ID, userId)
                if (userName != null) putString(KEY_USER_NAME, userName)
                if (userNo != null) putString(KEY_USER_NO, userNo)
                if (userEmail != null) putString(KEY_USER_EMAIL, userEmail)
            }
            .apply()
    }

    fun clearSession() {
        securePrefs.edit()
            .remove(KEY_SESSION_ID)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_NO)
            .remove(KEY_USER_EMAIL)
            .apply()

        prefs.edit()
            .remove(KEY_SESSION_ID)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_NO)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_CACHED_TODOS)
            .remove(KEY_CACHED_COURSES)
            .apply()
    }

    fun getLastSyncTime(): Long = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)

    fun setLastSyncTime(time: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SYNC_TIME, time).apply()
    }

    fun saveCachedTodos(todos: List<TronClassTodo>) {
        val json = gson.toJson(todos)
        prefs.edit().putString(KEY_CACHED_TODOS, json).apply()
    }

    fun getCachedTodos(): List<TronClassTodo> {
        val json = prefs.getString(KEY_CACHED_TODOS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<TronClassTodo>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCachedCourses(courses: List<TronClassCourse>) {
        val json = gson.toJson(courses)
        prefs.edit().putString(KEY_CACHED_COURSES, json).apply()
    }

    fun getCachedCourses(): List<TronClassCourse> {
        val json = prefs.getString(KEY_CACHED_COURSES, null) ?: return emptyList()
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
}
