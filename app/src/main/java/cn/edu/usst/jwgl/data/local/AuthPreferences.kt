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
        private const val KEY_MIGRATED = "pref_migrated_to_encrypted"

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
                Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back", e)
                context.getSharedPreferences(SECURE_PREF_NAME, Context.MODE_PRIVATE)
            }
        }
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
        if (!oldPassword.isNullOrEmpty()) {
            securePrefs.edit().putString(KEY_PASSWORD, oldPassword).apply()
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

    fun saveCredentials(studentId: String, password: String, autoLogin: Boolean) {
        prefs.edit()
            .putString(KEY_STUDENT_ID, studentId)
            .putBoolean(KEY_AUTO_LOGIN, autoLogin)
            .apply()
        securePrefs.edit()
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    fun getStudentId(): String = prefs.getString(KEY_STUDENT_ID, "") ?: ""

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
