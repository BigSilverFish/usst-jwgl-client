package cn.edu.usst.jwgl.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import cn.edu.usst.jwgl.data.model.ChaoXingUserInfo

/**
 * 超星学习通本地存储偏好 (硬件级加密存储 Cookie 与用户状态)
 */
class ChaoXingPreferences(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences = getOrCreateSecurePrefs(context)

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

        @Volatile
        private var memoryFallbackInstance: SharedPreferences? = null

        private val volatileMemoryFallback: SharedPreferences
            get() = memoryFallbackInstance ?: synchronized(this) {
                memoryFallbackInstance ?: InMemorySharedPreferences().also { memoryFallbackInstance = it }
            }

        private fun getOrCreateSecurePrefs(context: Context): SharedPreferences {
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
                AuthPreferences(context).getStudentId()
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
    ) {
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
        return securePrefs.getBoolean(prefix + KEY_IS_LOGGED_IN, false) && !getCookies(studentId).isNullOrBlank()
    }

    fun getLastLoginTime(studentId: String? = null): Long {
        val prefix = getAccountPrefix(studentId)
        return securePrefs.getLong(prefix + KEY_LAST_LOGIN_TIME, 0L)
    }

    fun clearSession(studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        securePrefs.edit().apply {
            remove(prefix + KEY_COOKIES)
            remove(prefix + KEY_UID)
            remove(prefix + KEY_IS_LOGGED_IN)
            remove(prefix + KEY_LAST_LOGIN_TIME)
        }.apply()
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
