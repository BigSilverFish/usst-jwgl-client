package cn.edu.usst.jwgl.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import cn.edu.usst.jwgl.data.model.TronClassCourse
import cn.edu.usst.jwgl.data.model.TronClassTodo
import java.util.UUID

class TronClassPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val PREF_NAME = "usst_tronclass_prefs"
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
    }

    fun isLoggedIn(): Boolean {
        val sid = getSessionId()
        return !sid.isNullOrBlank()
    }

    fun getSessionId(): String? = prefs.getString(KEY_SESSION_ID, null)

    fun setSessionId(sessionId: String?) {
        prefs.edit().putString(KEY_SESSION_ID, sessionId).apply()
    }

    fun getRefreshToken(): String? = prefs.getString(KEY_REFRESH_TOKEN, null)

    fun setRefreshToken(token: String?) {
        prefs.edit().putString(KEY_REFRESH_TOKEN, token).apply()
    }

    fun getUserId(): Long = prefs.getLong(KEY_USER_ID, 0L)

    fun setUserId(userId: Long) {
        prefs.edit().putLong(KEY_USER_ID, userId).apply()
    }

    fun getUserName(): String? = prefs.getString(KEY_USER_NAME, null)

    fun setUserName(name: String?) {
        prefs.edit().putString(KEY_USER_NAME, name).apply()
    }

    fun getUserNo(): String? = prefs.getString(KEY_USER_NO, null)

    fun setUserNo(userNo: String?) {
        prefs.edit().putString(KEY_USER_NO, userNo).apply()
    }

    fun getUserEmail(): String? = prefs.getString(KEY_USER_EMAIL, null)

    fun setUserEmail(email: String?) {
        prefs.edit().putString(KEY_USER_EMAIL, email).apply()
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
        prefs.edit()
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
}
