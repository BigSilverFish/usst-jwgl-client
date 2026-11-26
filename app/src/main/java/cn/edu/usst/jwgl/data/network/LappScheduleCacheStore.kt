package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.util.Log
import cn.edu.usst.jwgl.data.local.ScheduleCacheCrypto
import cn.edu.usst.jwgl.data.model.LappCourseItem
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Lapp 全校课表与单次查询的内存/加密磁盘缓存管理器
 */
internal object LappScheduleCacheStore {
    private const val TAG = "LappScheduleCacheStore"
    const val PREF_LAPP_CACHE = "usst_lapp_schedule_cache"
    private val gson = Gson()

    val memoryScheduleCache = ConcurrentHashMap<String, Pair<Int, List<LappCourseItem>>>()
    val fullScheduleMemoryCache = ConcurrentHashMap<String, Pair<Long, List<LappCourseItem>>>()
    val fullScheduleReads = SharedRequest<List<LappCourseItem>>(CoroutineScope(SupervisorJob() + Dispatchers.IO))
    val verifiedFullSchedules = ConcurrentHashMap.newKeySet<String>()
    val failedFullSyncAt = ConcurrentHashMap<String, Long>()

    fun readScheduleCache(context: Context, key: String): Pair<Long, Pair<Int, List<LappCourseItem>>>? {
        val prefs = context.getSharedPreferences(PREF_LAPP_CACHE, Context.MODE_PRIVATE)
        val ts = prefs.getLong("time_$key", 0L)
        memoryScheduleCache[key]?.let { mem ->
            return Pair(if (ts > 0L) ts else System.currentTimeMillis(), mem)
        }
        val jsonStr = prefs.getString("data_$key", null) ?: return null
        return try {
            val root = JSONObject(jsonStr)
            val total = root.optInt("total", 0)
            val itemsJson = root.optString("items", "[]")
            val type = object : TypeToken<List<LappCourseItem>>() {}.type
            val list: List<LappCourseItem> = gson.fromJson(itemsJson, type) ?: emptyList()
            val pair = Pair(total, list)
            memoryScheduleCache[key] = pair
            Pair(ts, pair)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read Lapp cache for $key", e)
            null
        }
    }

    fun writeScheduleCache(context: Context, key: String, total: Int, list: List<LappCourseItem>): Long {
        val now = System.currentTimeMillis()
        memoryScheduleCache[key] = Pair(total, list)
        try {
            val root = JSONObject().apply {
                put("total", total)
                put("items", gson.toJson(list))
            }
            val prefs = context.getSharedPreferences(PREF_LAPP_CACHE, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("data_$key", root.toString())
                .putLong("time_$key", now)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write Lapp cache for $key", e)
        }
        return now
    }

    fun fullScheduleKey(xn: String, xq: String) = "${xn.trim()}_${xq.trim()}"

    private fun getFullEncryptedCacheFile(context: Context, xn: String, xq: String): File {
        val safeXn = xn.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeXq = xq.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(context.cacheDir, "full_school_schedule_${safeXn}_${safeXq}.enc")
    }

    private fun getLegacyCacheFile(context: Context, xn: String, xq: String): File {
        val safeXn = xn.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeXq = xq.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(context.cacheDir, "full_school_schedule_${safeXn}_${safeXq}.json")
    }

    private fun tryParseFullScheduleJson(jsonStr: String, fallbackTime: Long): Pair<Long, List<LappCourseItem>>? {
        val type = object : TypeToken<List<LappCourseItem>>() {}.type
        try {
            val root = JSONObject(jsonStr)
            val ts = root.optLong("time", fallbackTime)
            val itemsObj = root.opt("items")
            val list: List<LappCourseItem> = when (itemsObj) {
                is String -> gson.fromJson<List<LappCourseItem>>(itemsObj, type) ?: return null
                is JSONArray -> gson.fromJson<List<LappCourseItem>>(itemsObj.toString(), type) ?: return null
                else -> return null
            }
            if (list.isNotEmpty()) {
                return Pair(ts, list)
            }
        } catch (_: Exception) {}

        try {
            val list: List<LappCourseItem> = gson.fromJson(jsonStr, type) ?: emptyList()
            if (list.isNotEmpty()) {
                return Pair(fallbackTime, list)
            }
        } catch (_: Exception) {}

        return null
    }

    fun getFullSchoolScheduleCached(context: Context, xn: String, xq: String): Pair<Long, List<LappCourseItem>>? {
        val key = fullScheduleKey(xn, xq)
        fullScheduleMemoryCache[key]?.let { return it }

        synchronized(fullScheduleMemoryCache) {
            fullScheduleMemoryCache[key]?.let { return it }

            val safeXnUnderscore = xn.replace(Regex("[^a-zA-Z0-9]"), "_")
            val safeXnDash = xn.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val safeXq = xq.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val candidateAssetNames = listOf(
                "preset_schedule_${safeXnUnderscore}_${safeXq}.json",
                "preset_schedule_${safeXnDash}_${safeXq}.json"
            ).distinct()

            var presetPair: Pair<Long, List<LappCourseItem>>? = null
            for (assetName in candidateAssetNames) {
                try {
                    val assetJson = context.assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val parsed = tryParseFullScheduleJson(assetJson, System.currentTimeMillis())
                    if (parsed != null && parsed.second.isNotEmpty()) {
                        presetPair = parsed
                        Log.i(TAG, "Successfully loaded preset schedule asset: $assetName (${parsed.second.size} courses)")
                        break
                    }
                } catch (_: Exception) {}
            }

            val encFile = getFullEncryptedCacheFile(context, xn, xq)
            var localPair: Pair<Long, List<LappCourseItem>>? = null
            var locallyVerified = false
            if (encFile.exists() && encFile.length() > 0L) {
                try {
                    val jsonStr = ScheduleCacheCrypto.readAndDecrypt(context, encFile)
                    if (!jsonStr.isNullOrBlank()) {
                        localPair = tryParseFullScheduleJson(jsonStr, encFile.lastModified())
                        val metadata = JSONObject(jsonStr)
                        locallyVerified = metadata.optBoolean("complete", false) &&
                            metadata.optInt("expectedCount", -1) == localPair?.second?.size
                        if (locallyVerified) verifiedFullSchedules.add(key)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read encrypted full schedule cache for $key", e)
                }
            }

            if (localPair == null) {
                val legacyFile = getLegacyCacheFile(context, xn, xq)
                if (legacyFile.exists() && legacyFile.length() > 0L) {
                    try {
                        val jsonStr = legacyFile.readText(Charsets.UTF_8)
                        localPair = tryParseFullScheduleJson(jsonStr, legacyFile.lastModified())
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read legacy full schedule file cache for $key", e)
                    }
                }
            }

            val presetSize = presetPair?.second?.size ?: 0
            val localSize = localPair?.second?.size

            if (presetPair != null && shouldSelfHealWithPreset(localSize, locallyVerified, presetSize)) {
                Log.w(TAG, "Local cache is empty, incomplete or poisoned (localSize=$localSize vs presetSize=$presetSize, verified=$locallyVerified). Self-healing with built-in asset preset...")
                val savedTime = saveFullSchoolSchedule(context, xn, xq, presetPair.second, verified = true)
                val finalPair = Pair(savedTime, presetPair.second)
                fullScheduleMemoryCache[key] = finalPair
                return finalPair
            }

            if (localPair != null && localPair.second.isNotEmpty()) {
                if (locallyVerified || localPair.second.size >= 5000) {
                    verifiedFullSchedules.add(key)
                }
                fullScheduleMemoryCache[key] = localPair
                return localPair
            }

            if (presetPair != null && presetPair.second.isNotEmpty()) {
                val savedTime = saveFullSchoolSchedule(context, xn, xq, presetPair.second, verified = true)
                val finalPair = Pair(savedTime, presetPair.second)
                fullScheduleMemoryCache[key] = finalPair
                return finalPair
            }

            return null
        }
    }

    fun shouldSelfHealWithPreset(localSize: Int?, locallyVerified: Boolean, presetSize: Int): Boolean {
        if (presetSize <= 0) return false
        if (localSize == null || localSize <= 0) return true
        if (localSize < (presetSize * 0.5)) return true
        return !locallyVerified && (localSize < 5000 || localSize < (presetSize * 0.8))
    }

    fun shouldRejectUpstreamScheduleOverwrite(fetchedSize: Int, existingSize: Int): Boolean {
        if (fetchedSize <= 0) return true
        if (existingSize >= 5000 && fetchedSize < (existingSize * 0.5)) return true
        return false
    }

    fun saveFullSchoolSchedule(context: Context, xn: String, xq: String, list: List<LappCourseItem>, verified: Boolean = false): Long {
        val now = System.currentTimeMillis()
        if (list.isEmpty()) {
            Log.w(TAG, "Refusing to overwrite full schedule cache for ${xn.trim()}_${xq.trim()} with empty list")
            return now
        }
        val key = fullScheduleKey(xn, xq)
        fullScheduleMemoryCache[key] = Pair(now, list)
        val isTrulyVerified = verified || list.size >= 5000
        if (isTrulyVerified) verifiedFullSchedules.add(key) else verifiedFullSchedules.remove(key)
        try {
            val encFile = getFullEncryptedCacheFile(context, xn, xq)
            val root = JSONObject().apply {
                put("time", now)
                put("complete", isTrulyVerified)
                put("expectedCount", list.size)
                put("items", gson.toJson(list))
            }
            ScheduleCacheCrypto.encryptAndWrite(context, encFile, root.toString())
            Log.i(TAG, "Saved encrypted full schedule cache to ${encFile.absolutePath} (${list.size} courses)")
            val legacyFile = getLegacyCacheFile(context, xn, xq)
            if (legacyFile.exists()) legacyFile.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write encrypted full schedule cache for $key", e)
        }
        return now
    }
}
