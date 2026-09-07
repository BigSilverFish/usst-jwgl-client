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

    // Primary & backup config URLs (can be hosted on GitHub, Gitee, Cloudflare, etc.)
    private val CONFIG_URLS = listOf(
        "https://fastly.jsdelivr.net/gh/usst-app/config@main/app_config.json",
        "https://raw.githubusercontent.com/usst-app/config/main/app_config.json"
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
        for (url in CONFIG_URLS) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "USST-JWGL-Android")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()
                    if (!bodyStr.isNullOrEmpty()) {
                        val newConfig = gson.fromJson(bodyStr, AppConfig::class.java)
                        cachedConfig = newConfig
                        saveToCache(context, bodyStr)
                        Log.d(TAG, "Successfully fetched and cached remote config from $url")
                        return@withContext Result.success(newConfig)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch remote config from $url: ${e.message}")
            }
        }
        // If all remote URLs fail, return currently cached config (graceful fallback)
        Log.i(TAG, "Using fallback/cached config: week1=${cachedConfig.semesterConfig.week1Monday}")
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
     * For manual or debug override of config
     */
    fun updateConfigManually(context: Context, newConfig: AppConfig) {
        cachedConfig = newConfig
        saveToCache(context, gson.toJson(newConfig))
    }
}
