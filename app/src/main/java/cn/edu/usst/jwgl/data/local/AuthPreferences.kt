package cn.edu.usst.jwgl.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class AuthPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val securePrefs: SharedPreferences = createSecurePrefs(context)

    companion object {
        private const val TAG = "AuthPreferences"
        private const val PREF_NAME = "usst_jwgl_auth_prefs"
        private const val SECURE_PREF_NAME = "usst_jwgl_secure_prefs"
        private const val KEY_STUDENT_ID = "pref_student_id"
        private const val KEY_PASSWORD = "pref_password"
        private const val KEY_AUTO_LOGIN = "pref_auto_login"
        private const val KEY_DISCLAIMER_AGREED = "pref_disclaimer_agreed"
        private const val KEY_INITIAL_SYNC_COMPLETED = "pref_initial_sync_completed"
        private const val KEY_MIGRATED = "pref_migrated_to_encrypted"

        private val volatileMemoryFallback = InMemorySharedPreferences()

        private fun createSecurePrefs(context: Context): SharedPreferences {
            return try {
                createEncryptedPrefsInternal(context)
            } catch (firstEx: Exception) {
                Log.w(TAG, "EncryptedSharedPreferences init failed, resetting corrupted keyset and retrying", firstEx)
                try {
                    context.deleteSharedPreferences(SECURE_PREF_NAME)
                    createEncryptedPrefsInternal(context)
                } catch (retryEx: Exception) {
                    Log.e(TAG, "Hardware keystore unavailable after reset; using volatile RAM-only store (never persisted to disk)", retryEx)
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

    /**
     * Volatile RAM-only SharedPreferences used only when Android Keystore is permanently broken on the device.
     * Ensures credentials are NEVER written to disk in plaintext.
     */
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

    init {
        migrateFromPlaintext()
    }

    /**
     * One-time migration: moves any plaintext password from the old prefs
     * into the encrypted store, then removes the plaintext copy.
     */
    private fun migrateFromPlaintext() {
        if (prefs.getBoolean(KEY_MIGRATED, false)) return
        val oldPassword = prefs.getString(KEY_PASSWORD, null)
        val oldStudentId = prefs.getString(KEY_STUDENT_ID, null)
        if (!oldPassword.isNullOrEmpty()) {
            securePrefs.edit().putString(KEY_PASSWORD, oldPassword).apply()
        }
        if (!oldStudentId.isNullOrEmpty()) {
            securePrefs.edit().putString(KEY_STUDENT_ID, oldStudentId).apply()
        }
        prefs.edit()
            .remove(KEY_PASSWORD)
            .putBoolean(KEY_MIGRATED, true)
            .apply()
    }

    fun hasAgreedDisclaimer(): Boolean = prefs.getBoolean(KEY_DISCLAIMER_AGREED, false)

    fun setDisclaimerAgreed(agreed: Boolean) {
        prefs.edit().putBoolean(KEY_DISCLAIMER_AGREED, agreed).apply()
    }

    fun hasInitialSyncCompleted(): Boolean = prefs.getBoolean(KEY_INITIAL_SYNC_COMPLETED, false)

    fun setInitialSyncCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_INITIAL_SYNC_COMPLETED, completed).apply()
    }

    fun saveCredentials(studentId: String, password: String, autoLogin: Boolean) {
        prefs.edit()
            .putString(KEY_STUDENT_ID, studentId)
            .putBoolean(KEY_AUTO_LOGIN, autoLogin)
            .apply()
        securePrefs.edit()
            .putString(KEY_STUDENT_ID, studentId)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun getStudentId(): String {
        val secureId = securePrefs.getString(KEY_STUDENT_ID, null)
        if (!secureId.isNullOrEmpty()) return secureId
        return prefs.getString(KEY_STUDENT_ID, "") ?: ""
    }

    fun getPassword(): String = securePrefs.getString(KEY_PASSWORD, "") ?: ""

    fun isAutoLoginEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_LOGIN, false)

    fun clearAutoLogin() {
        prefs.edit().putBoolean(KEY_AUTO_LOGIN, false).apply()
    }


    fun clearAll() {
        prefs.edit().clear().apply()
        securePrefs.edit().clear().apply()
    }
}
