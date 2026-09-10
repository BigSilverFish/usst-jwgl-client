package cn.edu.usst.jwgl.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import cn.edu.usst.jwgl.data.model.AppConfig
import cn.edu.usst.jwgl.data.model.AppVersionInfo
import cn.edu.usst.jwgl.data.model.SemesterRemoteConfig
import cn.edu.usst.jwgl.util.SemesterHelper
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import java.util.concurrent.TimeUnit

object RemoteConfigManager {
    private const val TAG = "RemoteConfigManager"
    private const val PREF_NAME = "usst_remote_config"
    private const val KEY_CACHED_JSON = "cached_config_json"
    private const val KEY_LAST_SYNC_TIME = "last_sync_time"
    private const val KEY_LAST_DAILY_SYNC_DATE = "last_daily_sync_date"

    // Primary & backup config URLs (hosted on BigSilverFish/usst-jwgl-client GitHub repository)
    private val CONFIG_URLS = listOf(
        "https://ghproxy.net/https://raw.githubusercontent.com/BigSilverFish/usst-jwgl-client/main/app_config.json",
        "https://fastly.jsdelivr.net/gh/BigSilverFish/usst-jwgl-client@main/app_config.json",
        "https://cdn.jsdelivr.net/gh/BigSilverFish/usst-jwgl-client@main/app_config.json",
        "https://raw.githubusercontent.com/BigSilverFish/usst-jwgl-client/main/app_config.json"
    )

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private var cachedConfig: AppConfig = AppConfig()
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CACHED_JSON, null)
        if (!json.isNullOrEmpty()) {
            try {
                cachedConfig = gson.fromJson(json, AppConfig::class.java)
                Log.d(TAG, "Loaded config from local cache: week1Monday=${cachedConfig.semesterConfig.week1Monday}, latestVersion=${cachedConfig.appVersion.versionName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse cached config, using default", e)
                cachedConfig = AppConfig()
            }
        } else {
            cachedConfig = AppConfig()
        }
        isInitialized = true
    }

    suspend fun fetchConfig(context: Context): Result<AppConfig> = withContext(Dispatchers.IO) {
        init(context)
        var lastError: Exception? = null
        val timestamp = System.currentTimeMillis()

        for (baseUrl in CONFIG_URLS) {
            val urlWithNoCache = if (baseUrl.contains("?")) "$baseUrl&_t=$timestamp" else "$baseUrl?_t=$timestamp"
            try {
                val request = Request.Builder()
                    .url(urlWithNoCache)
                    .header("User-Agent", "USST-JWGL-Android")
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()
                    if (!bodyStr.isNullOrEmpty()) {
                        try {
                            val newConfig = gson.fromJson(bodyStr, AppConfig::class.java)
                            if (newConfig != null && newConfig.semesters.isNotEmpty()) {
                                cachedConfig = newConfig
                                saveToCache(context, bodyStr)
                                Log.d(TAG, "Successfully fetched and cached remote config from $baseUrl")
                                return@withContext Result.success(newConfig)
                            }
                        } catch (parseEx: Exception) {
                            Log.e(TAG, "JSON Syntax error in remote config from $baseUrl: ${parseEx.message}", parseEx)
                            lastError = parseEx
                            // Continue trying other URLs or fail
                        }
                    }
                } else {
                    Log.w(TAG, "HTTP ${response.code} from $baseUrl")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch remote config from $baseUrl: ${e.message}")
                lastError = e
            }
        }
        // If all remote URLs fail or parsing failed, return error or fallback
        if (lastError != null) {
            Log.e(TAG, "All remote config sources failed. Last error: ${lastError.message}")
            return@withContext Result.failure(lastError)
        }
        Result.success(cachedConfig)
    }

    private fun saveToCache(context: Context, json: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CACHED_JSON, json)
            .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            .apply()
    }

    fun getConfig(): AppConfig = cachedConfig

    fun getSemesterConfig(): SemesterRemoteConfig = cachedConfig.semesterConfig

    fun getLatestVersion(): AppVersionInfo = cachedConfig.appVersion

    fun isUpdateAvailable(currentVersionCode: Int): Boolean {
        return cachedConfig.appVersion.versionCode > currentVersionCode
    }

    fun getCurrentWeek(cal: Calendar = SemesterHelper.getToday()): Int {
        val semConfig = cachedConfig.semesterConfig
        return SemesterHelper.calculateCurrentWeek(
            week1MondayStr = semConfig.week1Monday,
            totalWeeks = semConfig.totalWeeks,
            now = cal
        )
    }

    fun getLastSyncTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
    }

    /**
     * Checks whether today's first-launch sync has already been performed.
     * Returns true if today has NOT performed the sync yet.
     */
    fun shouldPerformDailySync(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val lastDate = prefs.getString(KEY_LAST_DAILY_SYNC_DATE, null)
        val today = getTodayDateString()
        return lastDate != today
    }

    /**
     * Marks today's daily sync as completed.
     */
    fun markDailySyncCompleted(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_DAILY_SYNC_DATE, getTodayDateString())
            .apply()
    }

    /**
     * Returns the date string of the last daily sync (e.g. "2026-09-10"), or null if never synced.
     */
    fun getLastDailySyncDate(context: Context): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_DAILY_SYNC_DATE, null)
    }

    fun getTodayDateString(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return sdf.format(java.util.Date())
    }

    /**
     * Returns schedule adjustments active for the specified date string (YYYY-MM-DD)
     */
    fun getAdjustmentsForDate(dateStr: String): List<cn.edu.usst.jwgl.data.model.ScheduleAdjustment> {
        if (dateStr.isBlank()) return emptyList()
        return cachedConfig.adjustments.filter { adj ->
            when (adj.type) {
                "HOLIDAY_OFF" -> {
                    adj.dates.contains(dateStr) ||
                    (adj.dateRange != null && dateStr >= adj.dateRange.start && dateStr <= adj.dateRange.end)
                }
                "SWAP_WEEKDAY" -> {
                    adj.date == dateStr
                }
                else -> false
            }
        }
    }

    fun getAdjustments(): List<cn.edu.usst.jwgl.data.model.ScheduleAdjustment> = cachedConfig.adjustments

    fun getSemesters(): List<cn.edu.usst.jwgl.data.model.SemesterConfigItem> = cachedConfig.semesters

    /**
     * Calibrates all local tables' startDate and maxWeek according to remote semesters list
     * Returns true if any table was updated
     */
    fun calibrateTablesAndSync(context: Context, config: AppConfig): Boolean {
        var anyChanged = false
        val db = cn.edu.usst.jwgl.data.wakeup.AppDatabase.getDatabase(context)
        val localTables = db.tableDao.getAllTables()

        for (remoteSem in config.semesters) {
            val semId = remoteSem.semesterId.trim()
            val semTitle = remoteSem.semesterTitle.trim()
            val parts = semId.split("-")
            val acadYear = if (parts.size >= 2) "${parts[0]}-${parts[1]}" else parts.getOrNull(0) ?: ""
            val semNum = if (parts.size >= 3) parts[2] else ""

            for (table in localTables) {
                val matches = (semId.isNotBlank() && table.tableName.contains(semId)) ||
                              (semTitle.isNotBlank() && table.tableName.contains(semTitle)) ||
                              (acadYear.isNotBlank() && semNum.isNotBlank() && table.tableName.contains(acadYear) && table.tableName.contains("第${semNum}学期"))

                if (matches) {
                    var tableChanged = false
                    if (remoteSem.startDate.isNotBlank() && table.startDate != remoteSem.startDate) {
                        Log.i(TAG, "Calibrating ${table.tableName} startDate from ${table.startDate} to ${remoteSem.startDate}")
                        table.startDate = remoteSem.startDate
                        tableChanged = true
                    }
                    if (remoteSem.maxWeek > 0 && table.maxWeek != remoteSem.maxWeek) {
                        Log.i(TAG, "Calibrating ${table.tableName} maxWeek from ${table.maxWeek} to ${remoteSem.maxWeek}")
                        table.maxWeek = remoteSem.maxWeek
                        tableChanged = true
                    }
                    if (tableChanged) {
                        db.tableDao.updateTable(table)
                        anyChanged = true
                    }
                }
            }
        }
        return anyChanged
    }

    /**
     * For manual or debug override of config
     */
    fun updateConfigManually(context: Context, newConfig: AppConfig) {
        cachedConfig = newConfig
        saveToCache(context, gson.toJson(newConfig))
    }
}
