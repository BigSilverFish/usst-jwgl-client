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
    private val securePrefs: SharedPreferences by lazy { getOrCreateSecurePrefs(context) }
    private val gson = Gson()

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
        private const val KEY_ROLLCALL_AUTO_SIGN_ENABLED = "tc_rollcall_auto_sign_enabled"
        private const val KEY_ROLLCALL_AUTO_SIGN_DELAY_SECONDS = "tc_rollcall_auto_sign_delay_seconds"
        private const val KEY_ROLLCALL_WAIT_FOR_OTHERS = "tc_rollcall_wait_for_others"
        private const val KEY_ROLLCALL_AUTO_SIGN_STRATEGY = "tc_rollcall_auto_sign_strategy"
        private const val KEY_ROLLCALL_AUTO_SIGN_CUSTOM_SECONDS = "tc_rollcall_auto_sign_custom_seconds"
        private const val KEY_ROLLCALL_AUTO_SIGN_CUSTOM_RATIO = "tc_rollcall_auto_sign_custom_ratio"
        private const val KEY_ROLLCALL_POPUP_ENABLED = "tc_rollcall_popup_enabled"
        private const val KEY_ROLLCALL_HANDLED_IDS = "tc_rollcall_handled_ids"
        private const val KEY_CUSTOM_SEMESTERS = "tc_custom_semesters"
        private const val KEY_MANUAL_COURSE_BINDINGS = "tc_manual_course_bindings"
        private const val KEY_UNBOUND_COURSE_PAIRS = "tc_unbound_course_pairs"
        private const val KEY_DUAL_PLATFORM_MODE_ENABLED = "tc_dual_platform_mode_enabled"
        private const val KEY_DUAL_PLATFORM_RISK_ACK = "tc_dual_platform_risk_acknowledged"
        private const val KEY_LEGACY_TOKEN_MIGRATED = "tc_legacy_token_migrated"

        const val STRATEGY_NO_DELAY = "no_delay"
        const val STRATEGY_DELAY_10S = "delay_10s"
        const val STRATEGY_DELAY_30S = "delay_30s"
        const val STRATEGY_COUNT_1 = "count_1"
        const val STRATEGY_COUNT_10 = "count_10"
        const val STRATEGY_HALF_CLASS = "half_class"
        const val STRATEGY_CUSTOM_TIME = "custom_time"
        const val STRATEGY_CUSTOM_RATIO = "custom_ratio"

        const val DEFAULT_AUTO_SIGN_STRATEGY = STRATEGY_COUNT_10

        fun isHeadcountStrategy(strategy: String): Boolean {
            return strategy in setOf(
                STRATEGY_COUNT_1,
                STRATEGY_COUNT_10,
                STRATEGY_HALF_CLASS,
                STRATEGY_CUSTOM_RATIO
            )
        }

        private val volatileMemoryFallback = InMemorySharedPreferences()

        @Volatile
        private var cachedSecurePrefs: SharedPreferences? = null

        @Volatile
        private var tokenMigrationCheckedInProcess = false

        @Volatile
        private var defaultInstance: TronClassPreferences? = null
        private val learningSnapshots = mutableMapOf<String, Pair<List<*>, String>>()

        fun getInstance(context: Context): TronClassPreferences {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: TronClassPreferences(context.applicationContext).also { defaultInstance = it }
            }
        }

        private fun getOrCreateSecurePrefs(context: Context): SharedPreferences {
            val appCtx = context.applicationContext
            val secure = cachedSecurePrefs ?: synchronized(this) {
                cachedSecurePrefs ?: createSecurePrefs(appCtx).also { cachedSecurePrefs = it }
            }
            migratePlaintextTokensIfNeeded(appCtx, secure)
            return secure
        }

        private fun migratePlaintextTokensIfNeeded(context: Context, securePrefs: SharedPreferences) {
            if (tokenMigrationCheckedInProcess) return
            synchronized(this) {
                if (tokenMigrationCheckedInProcess) return
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                if (prefs.getBoolean(KEY_LEGACY_TOKEN_MIGRATED, false)) {
                    tokenMigrationCheckedInProcess = true
                    return
                }
                val legacyUserNo = prefs.getString(KEY_USER_NO, null) ?: securePrefs.getString(KEY_USER_NO, null)
                val legacySessionId = prefs.getString(KEY_SESSION_ID, null) ?: securePrefs.getString(KEY_SESSION_ID, null)

                if (!legacySessionId.isNullOrBlank()) {
                    val targetStudentId = legacyUserNo?.takeIf { it.isNotBlank() }
                        ?: try { AuthPreferences.currentStudentId(context) } catch (_: Exception) { "" }

                    if (targetStudentId.isNotBlank()) {
                        val prefix = "${targetStudentId}_"
                        val oldRefreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: securePrefs.getString(KEY_REFRESH_TOKEN, null)
                        val oldUserId = prefs.getLong(KEY_USER_ID, 0L).let { if (it > 0) it else securePrefs.getLong(KEY_USER_ID, 0L) }
                        val oldUserName = prefs.getString(KEY_USER_NAME, null) ?: securePrefs.getString(KEY_USER_NAME, null)
                        val oldUserEmail = prefs.getString(KEY_USER_EMAIL, null) ?: securePrefs.getString(KEY_USER_EMAIL, null)

                        securePrefs.edit().apply {
                            putString(prefix + KEY_SESSION_ID, legacySessionId)
                            if (!oldRefreshToken.isNullOrBlank()) putString(prefix + KEY_REFRESH_TOKEN, oldRefreshToken)
                            if (oldUserId > 0) putLong(prefix + KEY_USER_ID, oldUserId)
                            if (!oldUserName.isNullOrBlank()) putString(prefix + KEY_USER_NAME, oldUserName)
                            if (!legacyUserNo.isNullOrBlank()) putString(prefix + KEY_USER_NO, legacyUserNo)
                            if (!oldUserEmail.isNullOrBlank()) putString(prefix + KEY_USER_EMAIL, oldUserEmail)
                        }.apply()
                    }

                    // 彻底抹除全局无前缀遗留，防止跨账号读取污染
                    securePrefs.edit().apply {
                        remove(KEY_SESSION_ID)
                        remove(KEY_REFRESH_TOKEN)
                        remove(KEY_USER_ID)
                        remove(KEY_USER_NAME)
                        remove(KEY_USER_NO)
                        remove(KEY_USER_EMAIL)
                    }.apply()

                    prefs.edit().apply {
                        remove(KEY_SESSION_ID)
                        remove(KEY_REFRESH_TOKEN)
                        remove(KEY_USER_ID)
                        remove(KEY_USER_NAME)
                        remove(KEY_USER_NO)
                        remove(KEY_USER_EMAIL)
                    }.apply()
                }
                prefs.edit().putBoolean(KEY_LEGACY_TOKEN_MIGRATED, true).apply()
                tokenMigrationCheckedInProcess = true
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
        return securePrefs.getString(prefix + KEY_SESSION_ID, null)
    }

    fun setSessionId(sessionId: String?, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        securePrefs.edit().putString(prefix + KEY_SESSION_ID, sessionId).apply()
    }

    fun getRefreshToken(studentId: String? = null): String? {
        val prefix = getAccountPrefix(studentId)
        return securePrefs.getString(prefix + KEY_REFRESH_TOKEN, null)
    }

    fun setRefreshToken(token: String?, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        securePrefs.edit().putString(prefix + KEY_REFRESH_TOKEN, token).apply()
    }

    fun getUserId(studentId: String? = null): Long {
        val prefix = getAccountPrefix(studentId)
        return securePrefs.getLong(prefix + KEY_USER_ID, 0L)
    }

    fun setUserId(userId: Long, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        securePrefs.edit().putLong(prefix + KEY_USER_ID, userId).apply()
    }

    fun getUserName(studentId: String? = null): String? {
        val prefix = getAccountPrefix(studentId)
        return securePrefs.getString(prefix + KEY_USER_NAME, null)
    }

    fun setUserName(name: String?, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        securePrefs.edit().putString(prefix + KEY_USER_NAME, name).apply()
    }

    fun getUserNo(studentId: String? = null): String? {
        val prefix = getAccountPrefix(studentId)
        return securePrefs.getString(prefix + KEY_USER_NO, null)
    }

    fun setUserNo(userNo: String?, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        securePrefs.edit().putString(prefix + KEY_USER_NO, userNo).apply()
    }

    fun getUserEmail(studentId: String? = null): String? {
        val prefix = getAccountPrefix(studentId)
        return securePrefs.getString(prefix + KEY_USER_EMAIL, null)
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
        val curId = try { AuthPreferences.currentStudentId(context) } catch (_: Exception) { "" }
        val isCurrentOrEmpty = prefix.isEmpty() || (studentId == null || studentId == curId)
        securePrefs.edit().apply {
            remove(prefix + KEY_SESSION_ID)
            remove(prefix + KEY_REFRESH_TOKEN)
            remove(prefix + KEY_USER_ID)
            remove(prefix + KEY_USER_NAME)
            remove(prefix + KEY_USER_NO)
            remove(prefix + KEY_USER_EMAIL)
            if (isCurrentOrEmpty) {
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
            if (isCurrentOrEmpty) {
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
        saveLearningCache(KEY_CACHED_TODOS, todos.toList(), studentId)
    }

    fun getCachedTodos(studentId: String? = null): List<TronClassTodo> {
        return getLearningCache(KEY_CACHED_TODOS, studentId, object : TypeToken<List<TronClassTodo>>() {})
    }

    fun saveCachedCourses(courses: List<TronClassCourse>, studentId: String? = null) {
        saveLearningCache(KEY_CACHED_COURSES, courses.map { it.copy(instructors = it.instructors?.toList().orEmpty()) }, studentId)
    }

    fun getCachedCourses(studentId: String? = null): List<TronClassCourse> {
        return getLearningCache(KEY_CACHED_COURSES, studentId, object : TypeToken<List<TronClassCourse>>() {})
    }

    private fun <T> getLearningCache(resource: String, studentId: String?, type: TypeToken<List<T>>): List<T> = synchronized(learningSnapshots) {
        val key = getAccountPrefix(studentId) + resource
        val json = prefs.getString(key, null) ?: return@synchronized emptyList()
        learningSnapshots[key]?.takeIf { it.second == json }?.let {
            @Suppress("UNCHECKED_CAST")
            return@synchronized it.first as List<T>
        }
        try {
            val value: List<T> = gson.fromJson(json, type.type) ?: emptyList()
            learningSnapshots[key] = value to json
            value
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveLearningCache(resource: String, value: List<*>, studentId: String?) = synchronized(learningSnapshots) {
        val key = getAccountPrefix(studentId) + resource
        val previous = learningSnapshots[key]
        val stored = prefs.getString(key, null)
        val json = if (previous != null && previous.first == value && previous.second == stored) previous.second else gson.toJson(value)
        val editor = prefs.edit().putLong(key + "_time", System.currentTimeMillis())
        if (stored != json) editor.putString(key, json)
        editor.apply()
        learningSnapshots[key] = value to json
    }

    fun getTodosCacheTime(): Long = prefs.getLong(getAccountPrefix() + KEY_CACHED_TODOS + "_time", 0L)
    fun getCoursesCacheTime(): Long = prefs.getLong(getAccountPrefix() + KEY_CACHED_COURSES + "_time", 0L)

    private fun isLearningCacheKey(key: String): Boolean = listOf(KEY_CACHED_TODOS, KEY_CACHED_COURSES)
        .any { key.endsWith(it) || key.endsWith(it + "_time") }

    fun getLearningCacheSize(): Long = prefs.all.filterKeys(::isLearningCacheKey).values.sumOf {
        (it as? String)?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 8L
    }

    fun getLearningCacheCount(): Int = prefs.all.keys.count { isLearningCacheKey(it) && !it.endsWith("_time") }

    fun clearLearningCache() = synchronized(learningSnapshots) {
        val editor = prefs.edit()
        prefs.all.keys.filter(::isLearningCacheKey).forEach(editor::remove)
        editor.apply()
        learningSnapshots.clear()
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
     * 课堂签到自动提交开关（需在后台监听开启的前提下生效）
     */
    fun isRollcallAutoSignEnabled(): Boolean = prefs.getBoolean(KEY_ROLLCALL_AUTO_SIGN_ENABLED, true)

    fun setRollcallAutoSignEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ROLLCALL_AUTO_SIGN_ENABLED, enabled).apply()
    }

    /**
     * 自动签到延迟执行时长（秒）：自首次发现签到的延迟等待秒数
     */
    fun getRollcallAutoSignDelaySeconds(): Int {
        return when (getRollcallAutoSignStrategy()) {
            STRATEGY_NO_DELAY -> 0
            STRATEGY_DELAY_10S -> 10
            STRATEGY_DELAY_30S -> 30
            STRATEGY_CUSTOM_TIME -> getRollcallAutoSignCustomSeconds()
            else -> 0
        }
    }

    /**
     * 适用于无班级人数探测系统的有效延迟时长（如超星位置签到）：
     * 人数策略回退为温和延迟 10 秒
     */
    fun getRollcallAutoSignEffectiveDelaySeconds(): Int {
        return when (getRollcallAutoSignStrategy()) {
            STRATEGY_NO_DELAY -> 0
            STRATEGY_DELAY_10S -> 10
            STRATEGY_DELAY_30S -> 30
            STRATEGY_CUSTOM_TIME -> getRollcallAutoSignCustomSeconds()
            STRATEGY_COUNT_1 -> 5
            STRATEGY_COUNT_10 -> 10
            STRATEGY_HALF_CLASS -> 15
            STRATEGY_CUSTOM_RATIO -> 10
            else -> 10
        }
    }

    fun setRollcallAutoSignDelaySeconds(seconds: Int) {
        val clamped = seconds.coerceIn(0, 300)
        prefs.edit().putInt(KEY_ROLLCALL_AUTO_SIGN_DELAY_SECONDS, clamped).apply()
    }

    /**
     * 当前配置的自动签到策略标识（默认: count_10 即有10人签到时）
     */
    fun getRollcallAutoSignStrategy(): String {
        return prefs.getString(KEY_ROLLCALL_AUTO_SIGN_STRATEGY, null)
            ?: if (prefs.getBoolean(KEY_ROLLCALL_WAIT_FOR_OTHERS, false)) {
                DEFAULT_AUTO_SIGN_STRATEGY
            } else {
                DEFAULT_AUTO_SIGN_STRATEGY
            }
    }

    fun setRollcallAutoSignStrategy(strategy: String) {
        val isHeadcount = isHeadcountStrategy(strategy)
        prefs.edit()
            .putString(KEY_ROLLCALL_AUTO_SIGN_STRATEGY, strategy)
            .putBoolean(KEY_ROLLCALL_WAIT_FOR_OTHERS, isHeadcount)
            .apply()
    }

    fun getRollcallAutoSignStrategyIndex(): Int {
        return when (getRollcallAutoSignStrategy()) {
            STRATEGY_NO_DELAY -> 0
            STRATEGY_DELAY_10S -> 1
            STRATEGY_DELAY_30S -> 2
            STRATEGY_COUNT_1 -> 3
            STRATEGY_COUNT_10 -> 4
            STRATEGY_HALF_CLASS -> 5
            STRATEGY_CUSTOM_TIME -> 6
            STRATEGY_CUSTOM_RATIO -> 7
            else -> 4
        }
    }

    fun getRollcallAutoSignStrategyDisplayName(): String {
        return when (getRollcallAutoSignStrategy()) {
            STRATEGY_NO_DELAY -> "不延迟"
            STRATEGY_DELAY_10S -> "延迟10秒"
            STRATEGY_DELAY_30S -> "延迟30秒"
            STRATEGY_COUNT_1 -> "有1人签到时"
            STRATEGY_COUNT_10 -> "有10人签到时 (默认)"
            STRATEGY_HALF_CLASS -> "有一半人签到时"
            STRATEGY_CUSTOM_TIME -> "延迟${getRollcallAutoSignCustomSeconds()}秒"
            STRATEGY_CUSTOM_RATIO -> "有${getRollcallAutoSignCustomRatio()}%人签到时"
            else -> "有10人签到时 (默认)"
        }
    }

    fun getRollcallAutoSignCustomSeconds(): Int {
        return prefs.getInt(KEY_ROLLCALL_AUTO_SIGN_CUSTOM_SECONDS, 15).coerceIn(0, 300)
    }

    fun setRollcallAutoSignCustomSeconds(seconds: Int) {
        val clamped = seconds.coerceIn(0, 300)
        prefs.edit().putInt(KEY_ROLLCALL_AUTO_SIGN_CUSTOM_SECONDS, clamped).apply()
    }

    fun getRollcallAutoSignCustomRatio(): Int {
        return prefs.getInt(KEY_ROLLCALL_AUTO_SIGN_CUSTOM_RATIO, 30).coerceIn(1, 100)
    }

    fun setRollcallAutoSignCustomRatio(ratio: Int) {
        val clamped = ratio.coerceIn(1, 100)
        prefs.edit().putInt(KEY_ROLLCALL_AUTO_SIGN_CUSTOM_RATIO, clamped).apply()
    }

    /**
     * 是否在自动签到时等待有其他同学已签到后再开始尝试（跟随班级签到、防误判）
     */
    fun isRollcallWaitForOthersEnabled(): Boolean {
        return isHeadcountStrategy(getRollcallAutoSignStrategy())
    }

    fun setRollcallWaitForOthersEnabled(enabled: Boolean) {
        if (enabled) {
            setRollcallAutoSignStrategy(DEFAULT_AUTO_SIGN_STRATEGY)
        } else {
            if (isHeadcountStrategy(getRollcallAutoSignStrategy())) {
                setRollcallAutoSignStrategy(STRATEGY_DELAY_10S)
            }
        }
    }

    /**
     * 发现课堂签到时直接全屏/桌面弹窗强提醒（配合系统 Full-Screen Intent 及悬浮弹窗）
     */
    fun isRollcallPopupEnabled(): Boolean = prefs.getBoolean(KEY_ROLLCALL_POPUP_ENABLED, true)

    fun setRollcallPopupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ROLLCALL_POPUP_ENABLED, enabled).apply()
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

    /**
     * 双平台融合模式开关（默认为 false，即纯畅课模式）
     */
    fun isDualPlatformModeEnabled(): Boolean {
        return prefs.getBoolean(KEY_DUAL_PLATFORM_MODE_ENABLED, false)
    }

    fun setDualPlatformModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DUAL_PLATFORM_MODE_ENABLED, enabled).apply()
    }

    /**
     * 用户是否已在弹窗中手动输入确认开启双平台融合模式的风险告知
     */
    fun isDualPlatformRiskAcknowledged(): Boolean {
        return prefs.getBoolean(KEY_DUAL_PLATFORM_RISK_ACK, false)
    }

    fun setDualPlatformRiskAcknowledged(acknowledged: Boolean) {
        prefs.edit().putBoolean(KEY_DUAL_PLATFORM_RISK_ACK, acknowledged).apply()
    }

    /**
     * 获取指定畅课课程的手动设置学期
     */
    fun getCustomSemester(courseId: String, studentId: String? = null): String? {
        return getAllCustomSemesters(studentId)[courseId]
    }

    /**
     * 手动为畅课课程设置归属学期 (传 null 或空表示恢复默认)
     */
    fun setCustomSemester(courseId: String, semesterCode: String?, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        val all = getAllCustomSemesters(studentId).toMutableMap()
        if (semesterCode.isNullOrBlank()) {
            all.remove(courseId)
        } else {
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
     * 获取所有手动设置学期的畅课课程字典 (courseId -> semesterCode)
     */
    fun getAllCustomSemesters(studentId: String? = null): Map<String, String> {
        val prefix = getAccountPrefix(studentId)
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

    /**
     * 手动绑定畅课与超星课程 (tcCourseId -> cxCourseId/key)
     */
    fun bindCourses(tcCourseId: String, cxCourseId: String, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        val bindings = getAllManualBindings(studentId).toMutableMap()
        bindings[tcCourseId] = cxCourseId

        // 同时解除可能的黑名单屏蔽
        val unbounds = getUnboundPairs(studentId).toMutableSet()
        unbounds.remove("${tcCourseId}_${cxCourseId}")
        unbounds.remove(tcCourseId)
        unbounds.removeAll { it.startsWith("${tcCourseId}_") }

        try {
            val json = gson.toJson(bindings)
            prefs.edit()
                .putString(prefix + KEY_MANUAL_COURSE_BINDINGS, json)
                .putStringSet(prefix + KEY_UNBOUND_COURSE_PAIRS, unbounds)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "bindCourses error", e)
        }
    }

    /**
     * 手动解除两门课程的融合关联 (并记录黑名单，防止再次被算法自动融合)
     */
    fun unbindCourses(tcCourseId: String, cxCourseId: String? = null, cxClassId: String? = null, studentId: String? = null) {
        val prefix = getAccountPrefix(studentId)
        val bindings = getAllManualBindings(studentId).toMutableMap()
        val targetCx = cxCourseId?.ifBlank { null } ?: bindings[tcCourseId]
        bindings.remove(tcCourseId)

        val unbounds = getUnboundPairs(studentId).toMutableSet()
        if (!targetCx.isNullOrBlank()) {
            unbounds.add("${tcCourseId}_${targetCx}")
            if (!cxClassId.isNullOrBlank()) {
                unbounds.add("${tcCourseId}_${targetCx}_${cxClassId}")
            }
        } else {
            unbounds.add(tcCourseId)
        }

        try {
            val json = gson.toJson(bindings)
            prefs.edit()
                .putString(prefix + KEY_MANUAL_COURSE_BINDINGS, json)
                .putStringSet(prefix + KEY_UNBOUND_COURSE_PAIRS, unbounds)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "unbindCourses error", e)
        }
    }

    /**
     * 获取所有手动配对字典 (tcCourseId -> cxCourseId/key)
     */
    fun getAllManualBindings(studentId: String? = null): Map<String, String> {
        val prefix = getAccountPrefix(studentId)
        val json = prefs.getString(prefix + KEY_MANUAL_COURSE_BINDINGS, null)
            ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, String>>() {}.type
            gson.fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "getAllManualBindings error", e)
            emptyMap()
        }
    }

    /**
     * 获取用户手动拆散的黑名单集合 ("${tcCourseId}_${cxCourseId}")
     */
    fun getUnboundPairs(studentId: String? = null): Set<String> {
        val prefix = getAccountPrefix(studentId)
        return prefs.getStringSet(prefix + KEY_UNBOUND_COURSE_PAIRS, emptySet())
            ?: emptySet()
    }
}
