package cn.edu.usst.jwgl.data.local

import android.content.Context
import android.content.SharedPreferences

class AuthPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREF_NAME = "usst_jwgl_auth_prefs"
        private const val KEY_STUDENT_ID = "pref_student_id"
        private const val KEY_PASSWORD = "pref_password"
        private const val KEY_AUTO_LOGIN = "pref_auto_login"
        private const val KEY_DISCLAIMER_AGREED = "pref_disclaimer_agreed"
    }

    fun hasAgreedDisclaimer(): Boolean = prefs.getBoolean(KEY_DISCLAIMER_AGREED, false)

    fun setDisclaimerAgreed(agreed: Boolean) {
        prefs.edit().putBoolean(KEY_DISCLAIMER_AGREED, agreed).apply()
    }

    fun saveCredentials(studentId: String, password: String, autoLogin: Boolean) {
        prefs.edit()
            .putString(KEY_STUDENT_ID, studentId)
            .putString(KEY_PASSWORD, password)
            .putBoolean(KEY_AUTO_LOGIN, autoLogin)
            .apply()
    }

    fun getStudentId(): String = prefs.getString(KEY_STUDENT_ID, "") ?: ""

    fun getPassword(): String = prefs.getString(KEY_PASSWORD, "") ?: ""

    fun isAutoLoginEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_LOGIN, false)

    fun clearAutoLogin() {
        prefs.edit().putBoolean(KEY_AUTO_LOGIN, false).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
