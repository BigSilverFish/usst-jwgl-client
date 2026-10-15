package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.util.Log
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.model.LappCourseItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

data class LappTerm(
    val schoolYear: String,
    val term: String,
    val beginDate: String,
    val endDate: String,
    val isCurrent: Boolean
) {
    val displayTitle: String
        get() = "$schoolYear 学年第 ${if (term == "1") "一" else "二"} 学期${if (isCurrent) " (当前)" else ""}"
}

data class FreeClassroomInfo(
    val name: String,
    val building: String,
    val isFree: Boolean,
    val occupiedCourse: String = "",
    val occupiedTeacher: String = ""
)

object LappClient {
    private const val TAG = "LappClient"
    private const val BASE_URL = "https://lapp.usst.edu.cn"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val cookieStore = CopyOnWriteArrayList<Cookie>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                cookieStore.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                cookieStore.add(c)
                Log.d(TAG, "Saved Lapp cookie: ${c.name} domain=${c.domain}")
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val valid = mutableListOf<Cookie>()
            for (c in cookieStore) {
                if (c.expiresAt < now) {
                    cookieStore.remove(c)
                } else if (c.matches(url)) {
                    valid.add(c)
                }
            }
            return valid
        }
    }

    private val httpsRedirectInterceptor = okhttp3.Interceptor { chain ->
        var request = chain.request()
        var response = chain.proceed(request)
        var redirectCount = 0
        while (response.isRedirect && redirectCount < 20) {
            redirectCount++
            val location = response.header("Location") ?: break
            var resolvedUrl = response.request.url.resolve(location) ?: break
            if (resolvedUrl.scheme == "http" && (resolvedUrl.host == "usst.edu.cn" || resolvedUrl.host.endsWith(".usst.edu.cn"))) {
                resolvedUrl = resolvedUrl.newBuilder().scheme("https").build()
            }
            val newReqBuilder = request.newBuilder().url(resolvedUrl)
            if (response.code == 301 || response.code == 302 || response.code == 303) {
                newReqBuilder.get()
            }
            request = newReqBuilder.build()
            response.close()
            response = chain.proceed(request)
        }
        response
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(httpsRedirectInterceptor)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    /**
     * 预置全校主要教学楼已知教室清单 (由全校排课大数据库聚类生成)
     */
val ALL_KNOWN_BUILDINGS = listOf(
        "全部教学楼",
        "第一教学楼",
        "第三教学楼",
        "第四教学楼",
        "卓越楼",
        "国合楼",
        "综合楼",
        "博学楼",
        "乐学楼",
        "勤学楼",
    )

    fun normalizeClassroomName(name: String): String {
        return name.trim()
            .replace("卓越工程大楼", "卓越楼")
            .replace("第一教学楼", "一教")
            .replace("第三教学楼", "三教")
            .replace("第四教学楼", "四教")
            .replace("综合A", "综合楼A区")
            .replace("综合B", "综合楼B区")
            .replace("综合C", "综合楼C区")
            .replace("综合D", "综合楼D区")
    }

    private val KNOWN_CLASSROOMS_BY_BUILDING: Map<String, List<String>> = mapOf(
        "第一教学楼" to listOf(
            "一教100", "一教101", "一教105", "一教109", "一教112", "一教113", "一教116", "一教117", "一教119", "一教120",
            "一教121", "一教129", "一教130", "一教132", "一教133", "一教136", "一教137", "一教139", "一教144", "一教146",
            "一教150", "一教200", "一教201", "一教205", "一教209", "一教212", "一教213", "一教216", "一教217", "一教219",
            "一教220", "一教221", "一教229", "一教230", "一教233", "一教236", "一教237", "一教239", "一教244", "一教246",
            "一教250", "一教300", "一教301", "一教305", "一教309", "一教312", "一教313", "一教316", "一教317", "一教319",
            "一教320", "一教329", "一教330", "一教332", "一教333", "一教336", "一教337", "一教339", "一教344", "一教346",
            "一教350", "一教400", "一教401", "一教405", "一教409", "一教412", "一教413", "一教416", "一教417", "一教419",
            "一教420", "一教421", "一教429", "一教430", "一教432", "一教433", "一教436", "一教437", "一教439", "一教444",
            "一教446", "一教450", "一教503", "一教506", "一教507", "一教510", "一教511", "一教513", "一教514", "一教515",
            "一教518", "一教519", "一教523", "一教524", "一教526", "一教527", "一教530", "一教531", "一教535"
        ),
        "第三教学楼" to listOf(
            "三教106", "三教110", "三教113", "三教117", "三教206", "三教209", "三教210", "三教213", "三教217", "三教221",
            "三教306", "三教309", "三教310", "三教313", "三教317", "三教321", "三教401", "三教405", "三教406", "三教409",
            "三教410", "三教413", "三教417", "三教421", "三教501", "三教505", "三教506", "三教509", "三教510", "三教513",
            "三教517", "三教521", "三教601", "三教605", "三教606", "三教609", "三教610", "三教613", "三教617", "三教621",
            "三教705", "三教706", "三教709", "三教710", "三教713", "三教717", "三教721"
        ),
        "第四教学楼" to listOf(
            "四教102", "四教103", "四教104", "四教105", "四教106", "四教107", "四教110", "四教111", "四教114", "四教115",
            "四教118", "四教119", "四教125", "四教129", "四教203", "四教204", "四教205", "四教206", "四教207", "四教208",
            "四教210", "四教211", "四教215", "四教217", "四教218", "四教219", "四教221", "四教222", "四教223", "四教227",
            "四教300", "四教303", "四教304", "四教307", "四教308", "四教309", "四教315", "四教319", "四教323", "四教400",
            "四教403", "四教404", "四教406", "四教407", "四教408", "四教409", "四教415", "四教419", "四教423", "四教502",
            "四教503", "四教504", "四教507", "四教508", "四教509", "四教510", "四教519", "四教520", "四教529"
        ),
        "卓越楼" to listOf(
            "卓越楼117", "卓越楼118", "卓越楼122", "卓越楼123", "卓越楼127", "卓越楼131", "卓越楼135", "卓越楼221", "卓越楼228", "卓越楼229",
            "卓越楼233", "卓越楼237", "卓越楼241", "卓越楼328", "卓越楼329", "卓越楼333", "卓越楼334", "卓越楼335", "卓越楼337", "卓越楼341",
            "卓越楼425", "卓越楼427", "卓越楼429", "卓越楼430", "卓越楼431", "卓越楼433", "卓越楼437", "卓越楼527", "卓越楼528", "卓越楼531",
            "卓越楼532", "卓越楼535", "卓越楼539"
        ),
        "国合楼" to listOf(
            "国合楼106", "国合楼110", "国合楼113", "国合楼114", "国合楼118", "国合楼202", "国合楼204", "国合楼206", "国合楼208", "国合楼210",
            "国合楼212", "国合楼214", "国合楼302", "国合楼304", "国合楼306", "国合楼308", "国合楼309", "国合楼310", "国合楼312", "国合楼313",
            "国合楼314", "国合楼317", "国合楼321", "国合楼325", "国合楼329", "国合楼402", "国合楼404", "国合楼406", "国合楼408", "国合楼410",
            "国合楼412", "国合楼421", "国合楼425", "国合楼429"
        ),
        "综合楼" to listOf(
            "综合楼A区101", "综合楼A区106", "综合楼A区108", "综合楼A区110", "综合楼A区112", "综合楼A区204", "综合楼A区206", "综合楼A区207", "综合楼B区101", "综合楼B区104",
            "综合楼B区105", "综合楼B区108", "综合楼B区201", "综合楼B区202", "综合楼B区204", "综合楼B区205", "综合楼B区208", "综合楼B区301", "综合楼B区304", "综合楼B区305",
            "综合楼B区308", "综合楼B区401", "综合楼B区402", "综合楼B区404", "综合楼B区405", "综合楼B区408", "综合楼B区411", "综合楼C区110", "综合楼C区317", "综合楼C区319",
            "综合楼D区101", "综合楼D区102", "综合楼D区103", "综合楼D区105", "综合楼D区106", "综合楼D区109", "综合楼D区201", "综合楼D区202", "综合楼D区203", "综合楼D区205",
            "综合楼D区206", "综合楼D区209", "综合楼D区301", "综合楼D区302", "综合楼D区303", "综合楼D区305", "综合楼D区306", "综合楼D区309", "综合楼D区402", "综合楼D区405",
            "综合楼D区406", "综合楼D区409", "综合楼D区410", "综合楼D区412", "综合楼D区502", "综合楼D区505", "综合楼D区506", "综合楼D区509", "综合楼D区510", "综合楼D区512"
        ),
        "博学楼" to listOf(
            "博学楼109", "博学楼111", "博学楼112", "博学楼113", "博学楼115", "博学楼117", "博学楼208", "博学楼210", "博学楼215", "博学楼216",
            "博学楼217", "博学楼219", "博学楼221", "博学楼308", "博学楼310", "博学楼311", "博学楼313", "博学楼315", "博学楼316", "博学楼317",
            "博学楼319", "博学楼321", "博学楼408", "博学楼410", "博学楼413", "博学楼415", "博学楼416", "博学楼418", "博学楼420", "博学楼421"
        ),
        "乐学楼" to listOf(
            "乐学楼102", "乐学楼106", "乐学楼109", "乐学楼111", "乐学楼114", "乐学楼115", "乐学楼202", "乐学楼205", "乐学楼208", "乐学楼210",
            "乐学楼212", "乐学楼214", "乐学楼215", "乐学楼302", "乐学楼309", "乐学楼315", "乐学楼316", "乐学楼402", "乐学楼404", "乐学楼406",
            "乐学楼409", "乐学楼416"
        ),
        "勤学楼" to listOf(
            "勤学楼101", "勤学楼103", "勤学楼107", "勤学楼201", "勤学楼203", "勤学楼207", "勤学楼301", "勤学楼303", "勤学楼307", "勤学楼402",
            "勤学楼404", "勤学楼406"
        ),
    )

    /**
     * 同步 JwglClient 中的已登录 SSO 凭证
     */
    fun syncCookiesFromJwgl(jwglCookies: List<Cookie>) {
        for (c in jwglCookies) {
            cookieStore.removeAll { it.name == c.name && it.domain == c.domain }
            cookieStore.add(c)
        }
    }

    /**
     * 确保具备 lapp.usst.edu.cn 的有效登录会话
     */
    suspend fun ensureSession(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. 优先尝试把 JwglClient 的 cookie 导入过来
            syncCookiesFromJwgl(JwglClient.getCookies())

            // 2. 访问首页以激活 lapp.usst.edu.cn 的 JSESSIONID
            val indexReq = Request.Builder()
                .url("$BASE_URL/_web/_customizes/usst/schoolTimetable/mobile/permit/pc/pub/index.html")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val indexResp = client.newCall(indexReq).execute()
            val finalUrl = indexResp.request.url.toString()
            val html = indexResp.body?.string() ?: ""

            // 如果已经停留在 index.html 且有 JSESSIONID，验证快速接口
            if (finalUrl.contains("schoolTimetable") && hasLappSession()) {
                return@withContext true
            }

            // 3. 如果跳转到 CAS 登录页，则执行 CAS 表单免密自动登录
            if (finalUrl.contains("authserver/login")) {
                val authPrefs = AuthPreferences(context)
                val studentId = authPrefs.getStudentId()
                val password = authPrefs.getPassword()
                if (studentId.isEmpty() || password.isEmpty()) {
                    Log.w(TAG, "No stored credentials for CAS login")
                    return@withContext false
                }

                val doc = Jsoup.parse(html)
                val lt = doc.select("input[name=lt]").`val`()
                val execution = doc.select("input[name=execution]").`val`()
                val eventId = doc.select("input[name=_eventId]").`val`().ifEmpty { "submit" }

                if (lt.isEmpty() || execution.isEmpty()) {
                    Log.e(TAG, "Failed to parse CAS tokens from lapp redirect")
                    return@withContext false
                }

                val formBody = FormBody.Builder()
                    .add("username", studentId.trim())
                    .add("password", password.trim())
                    .add("lt", lt)
                    .add("dllt", "userNamePasswordLogin")
                    .add("execution", execution)
                    .add("_eventId", eventId)
                    .add("rmShown", "1")
                    .build()

                val casReq = Request.Builder()
                    .url(finalUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", finalUrl)
                    .post(formBody)
                    .build()

                val casResp = client.newCall(casReq).execute()
                val postFinalUrl = casResp.request.url.toString()
                casResp.body?.string() // consume

                Log.d(TAG, "CAS login completed, landed on host: ${casResp.request.url.host}")
                return@withContext hasLappSession() || postFinalUrl.contains("lapp.usst.edu.cn")
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "ensureSession error", e)
            false
        }
    }

    private fun hasLappSession(): Boolean {
        return cookieStore.any { it.name.equals("JSESSIONID", ignoreCase = true) && (it.domain.contains("lapp") || it.domain.endsWith("usst.edu.cn")) }
    }

    private const val PREF_LAPP_CACHE = "usst_lapp_schedule_cache"
    private val gson = com.google.gson.Gson()
    private val memoryScheduleCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, List<LappCourseItem>>>()

    @Volatile
    var lastQueryFromCache: Boolean = false
        private set

    @Volatile
    var lastQueryCacheTime: Long = 0L
        private set

    fun buildScheduleCacheKey(
        xn: String = "",
        xq: String = "",
        week: Int = -1,
        xy: String = "",
        zj: Int = -1,
        jc: String = "-1",
        kcmc: String = "",
        kcdm: String = "",
        lsmc: String = "",
        page: Int = 0,
        rows: Int = 50
    ): String {
        return "sched_${xn.trim()}_${xq.trim()}_w${week}_xy${xy.trim()}_zj${zj}_jc${jc.trim()}_kc${kcmc.trim()}_dm${kcdm.trim()}_ls${lsmc.trim()}_p${page}_r${rows}"
    }

    private fun readScheduleCache(context: Context, key: String): Pair<Long, Pair<Int, List<LappCourseItem>>>? {
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
            val type = object : com.google.gson.reflect.TypeToken<List<LappCourseItem>>() {}.type
            val list: List<LappCourseItem> = gson.fromJson(itemsJson, type) ?: emptyList()
            val pair = Pair(total, list)
            memoryScheduleCache[key] = pair
            Pair(ts, pair)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read Lapp cache for $key", e)
            null
        }
    }

    private fun writeScheduleCache(context: Context, key: String, total: Int, list: List<LappCourseItem>): Long {
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

    fun formatCacheTag(timestamp: Long = lastQueryCacheTime, fromCache: Boolean = lastQueryFromCache): String {
        if (timestamp <= 0L) return ""
        if (!fromCache) return "刚刚同步"
        val diff = System.currentTimeMillis() - timestamp
        if (diff < 60_000L) return "缓存 · 刚刚更新"
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return "缓存 · ${sdf.format(java.util.Date(timestamp))}"
    }

    /**
     * 判断某门课程在目标教学周是否处于开课/活跃状态
     */
    fun isCourseActiveInWeek(c: LappCourseItem, targetWeek: Int): Boolean {
        if (targetWeek <= 0) return true
        val list = c.weekList
        if (list.isNotEmpty()) {
            return list.contains(targetWeek)
        }
        if (c.qsz in 1..30 && c.jsz >= c.qsz) {
            return targetWeek in c.qsz..c.jsz
        }
        if (c.zc.isNotBlank()) {
            val parsed = c.zc.split(",").mapNotNull { it.trim().toIntOrNull() }
            if (parsed.isNotEmpty()) {
                return parsed.contains(targetWeek)
            }
        }
        return false
    }

    /**
     * 解析节次范围参数（支持 1-2, 3-5, 6-7, 8-9, 10-12 等格式）
     */
    fun getQuerySectionRange(queryJc: String): IntRange? {
        if (queryJc == "-1" || queryJc.isBlank()) return null
        return when (queryJc) {
            "1-2", "01-02" -> 1..2
            "3-5", "03-05" -> 3..5
            "6-7", "06-07" -> 6..7
            "8-9", "8-10", "08-09", "08-10" -> 8..10
            "10-12", "11-13", "10-13" -> 11..13
            else -> {
                val parts = queryJc.split("-")
                val start = parts[0].trim().toIntOrNull() ?: return null
                val end = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: start
                start..end
            }
        }
    }

    /**
     * 判断课程是否与指定查询节次发生重叠冲突
     */
    fun matchesSection(c: LappCourseItem, queryJc: String): Boolean {
        val qRange = getQuerySectionRange(queryJc) ?: return true
        val cStart = c.jc
        val cEnd = if (c.jccd > 0) c.jc + c.jccd - 1 else c.jc
        return maxOf(qRange.first, cStart) <= minOf(qRange.last, cEnd)
    }

    // 全校课表全量缓存（内存 + 本地文件持久化）
    private val fullScheduleMemoryCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<LappCourseItem>>>()

    private fun getFullEncryptedCacheFile(context: Context, xn: String, xq: String): java.io.File {
        val safeXn = xn.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeXq = xq.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return java.io.File(context.cacheDir, "full_school_schedule_${safeXn}_${safeXq}.enc")
    }

    private fun getLegacyCacheFile(context: Context, xn: String, xq: String): java.io.File {
        val safeXn = xn.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeXq = xq.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return java.io.File(context.cacheDir, "full_school_schedule_${safeXn}_${safeXq}.json")
    }

    fun getFullSchoolScheduleCached(context: Context, xn: String, xq: String): Pair<Long, List<LappCourseItem>>? {
        val key = "${xn.trim()}_${xq.trim()}"
        fullScheduleMemoryCache[key]?.let { return it }

        // 1. 优先读取 AES-256-GCM 硬件加密缓存文件 (.enc)
        val encFile = getFullEncryptedCacheFile(context, xn, xq)
        if (encFile.exists() && encFile.length() > 0L) {
            try {
                val jsonStr = cn.edu.usst.jwgl.data.local.ScheduleCacheCrypto.readAndDecrypt(context, encFile)
                if (!jsonStr.isNullOrBlank()) {
                    val root = JSONObject(jsonStr)
                    val ts = root.optLong("time", encFile.lastModified())
                    val itemsJson = root.optString("items", "[]")
                    val type = object : com.google.gson.reflect.TypeToken<List<LappCourseItem>>() {}.type
                    val list: List<LappCourseItem> = gson.fromJson(itemsJson, type) ?: emptyList()
                    if (list.isNotEmpty()) {
                        val pair = Pair(ts, list)
                        fullScheduleMemoryCache[key] = pair
                        return pair
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read encrypted full schedule cache for $key", e)
            }
        }

        // 2. 兼容读取旧版明文格式缓存 (.json)，并自动静默迁移升级为加密文件
        val legacyFile = getLegacyCacheFile(context, xn, xq)
        if (legacyFile.exists() && legacyFile.length() > 0L) {
            try {
                val jsonStr = legacyFile.readText(Charsets.UTF_8)
                val root = JSONObject(jsonStr)
                val ts = root.optLong("time", legacyFile.lastModified())
                val itemsJson = root.optString("items", "[]")
                val type = object : com.google.gson.reflect.TypeToken<List<LappCourseItem>>() {}.type
                val list: List<LappCourseItem> = gson.fromJson(itemsJson, type) ?: emptyList()
                if (list.isNotEmpty()) {
                    saveFullSchoolSchedule(context, xn, xq, list)
                    legacyFile.delete()
                    val pair = Pair(ts, list)
                    fullScheduleMemoryCache[key] = pair
                    return pair
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read legacy full schedule file cache for $key", e)
            }
        }

        // 3. 本地磁盘均无缓存时（冷启动/非校园网），检测并加载 APK 内置镜像包（Assets）
        val safeXn = xn.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeXq = xq.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val presetAssetName = "preset_schedule_${safeXn}_${safeXq}.json"
        try {
            val assetManager = context.assets
            val hasAsset = try {
                assetManager.open(presetAssetName).close()
                true
            } catch (_: Exception) { false }

            if (hasAsset) {
                Log.i(TAG, "Found built-in schedule preset asset: $presetAssetName, initializing encrypted cache...")
                val assetJson = assetManager.open(presetAssetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val type = object : com.google.gson.reflect.TypeToken<List<LappCourseItem>>() {}.type
                val list: List<LappCourseItem> = gson.fromJson(assetJson, type) ?: emptyList()
                if (list.isNotEmpty()) {
                    val savedTime = saveFullSchoolSchedule(context, xn, xq, list)
                    val pair = Pair(savedTime, list)
                    fullScheduleMemoryCache[key] = pair
                    Log.i(TAG, "Successfully initialized and encrypted $key schedule from preset asset (${list.size} courses)")
                    return pair
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load preset schedule asset $presetAssetName", e)
        }

        return null
    }

    private fun saveFullSchoolSchedule(context: Context, xn: String, xq: String, list: List<LappCourseItem>): Long {
        val key = "${xn.trim()}_${xq.trim()}"
        val now = System.currentTimeMillis()
        fullScheduleMemoryCache[key] = Pair(now, list)
        try {
            val encFile = getFullEncryptedCacheFile(context, xn, xq)
            val root = JSONObject().apply {
                put("time", now)
                put("items", gson.toJson(list))
            }
            cn.edu.usst.jwgl.data.local.ScheduleCacheCrypto.encryptAndWrite(context, encFile, root.toString())
            Log.i(TAG, "Saved encrypted full schedule cache to ${encFile.absolutePath} (${list.size} courses)")
            // 删除旧的明文文件（若存在）
            val legacyFile = getLegacyCacheFile(context, xn, xq)
            if (legacyFile.exists()) legacyFile.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write encrypted full schedule cache for $key", e)
        }
        return now
    }

    /**
     * 获取或拉取全校课表全量缓存（初次加载时全量分页拉取并缓存至本地）
     */
    suspend fun getOrFetchFullSchoolSchedule(
        context: Context,
        xn: String,
        xq: String,
        forceRefresh: Boolean = false
    ): Result<List<LappCourseItem>> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            getFullSchoolScheduleCached(context, xn, xq)?.let { (ts, list) ->
                lastQueryFromCache = true
                lastQueryCacheTime = ts
                return@withContext Result.success(list)
            }
        }

        try {
            ensureSession(context)
            val allCourses = mutableListOf<LappCourseItem>()
            var page = 0
            val rows = 1000
            var total = 0

            do {
                val pageResult = fetchSchedulePage(
                    context = context,
                    xn = xn,
                    xq = xq,
                    week = -1,
                    xy = "",
                    zj = -1,
                    jc = "-1",
                    kcmc = "",
                    kcdm = "",
                    lsmc = "",
                    page = page * rows,
                    rows = rows
                )
                if (pageResult.isFailure) {
                    if (allCourses.isNotEmpty()) break
                    getFullSchoolScheduleCached(context, xn, xq)?.let { (ts, list) ->
                        lastQueryFromCache = true
                        lastQueryCacheTime = ts
                        return@withContext Result.success(list)
                    }
                    return@withContext Result.failure(pageResult.exceptionOrNull() ?: Exception("全量课表获取失败"))
                }
                val (t, pageItems) = pageResult.getOrThrow()
                total = t
                allCourses.addAll(pageItems)
                page++
                if (pageItems.isEmpty() || allCourses.size >= total) break
            } while (page * rows < total && page < 20)

            if (allCourses.isNotEmpty()) {
                val savedTs = saveFullSchoolSchedule(context, xn, xq, allCourses)
                lastQueryFromCache = false
                lastQueryCacheTime = savedTs
                Result.success(allCourses)
            } else {
                val fallback = getFullSchoolScheduleCached(context, xn, xq)
                if (fallback != null) {
                    Result.success(fallback.second)
                } else {
                    Result.failure(Exception("未能拉取到全校课表数据"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getOrFetchFullSchoolSchedule error", e)
            val fallback = getFullSchoolScheduleCached(context, xn, xq)
            if (fallback != null) {
                lastQueryFromCache = true
                lastQueryCacheTime = fallback.first
                Result.success(fallback.second)
            } else {
                Result.failure(e)
            }
        }
    }

    private suspend fun fetchSchedulePage(
        context: Context,
        xn: String = "",
        xq: String = "",
        week: Int = -1,
        xy: String = "",
        zj: Int = -1,
        jc: String = "-1",
        kcmc: String = "",
        kcdm: String = "",
        lsmc: String = "",
        page: Int = 0,
        rows: Int = 1000
    ): Result<Pair<Int, List<LappCourseItem>>> = withContext(Dispatchers.IO) {
        try {
            ensureSession(context)

            var urlBuilder = StringBuilder("$BASE_URL/_web/_lightapp/schedule/mobile/school/queryClassSchedule.rst?")
            if (xn.isNotEmpty()) urlBuilder.append("xn=").append(URLEncoder.encode(xn, "UTF-8")).append("&")
            if (xq.isNotEmpty()) urlBuilder.append("xq=").append(URLEncoder.encode(xq, "UTF-8")).append("&")
            urlBuilder.append("week=").append(week).append("&")
            if (xy.isNotEmpty()) urlBuilder.append("xy=").append(URLEncoder.encode(xy, "UTF-8")).append("&")
            urlBuilder.append("zj=").append(zj).append("&")
            urlBuilder.append("jc=").append(URLEncoder.encode(jc, "UTF-8")).append("&")
            if (kcmc.isNotEmpty()) urlBuilder.append("kcmc=").append(URLEncoder.encode(kcmc, "UTF-8")).append("&")
            if (kcdm.isNotEmpty()) urlBuilder.append("kcdm=").append(URLEncoder.encode(kcdm, "UTF-8")).append("&")
            if (lsmc.isNotEmpty()) urlBuilder.append("lsmc=").append(URLEncoder.encode(lsmc, "UTF-8")).append("&")
            urlBuilder.append("page=").append(page).append("&")
            urlBuilder.append("rows=").append(rows)

            val requestUrl = urlBuilder.toString()
            val req = Request.Builder()
                .url(requestUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$BASE_URL/_web/_customizes/usst/schoolTimetable/mobile/permit/pc/pub/index.html")
                .get()
                .build()

            var resp = client.newCall(req).execute()
            var body = resp.body?.string() ?: ""

            if (body.contains("用户未登录")) {
                ensureSession(context)
                resp = client.newCall(req).execute()
                body = resp.body?.string() ?: ""
            }

            val json = JSONObject(body)
            if (json.optInt("resultCode", -1) != 0) {
                val err = json.optString("errorMsg", "查询失败")
                return@withContext Result.failure(Exception(err))
            }

            val resultObj = json.optJSONObject("result") ?: JSONObject()
            val total = resultObj.optInt("total", 0)
            val dataArr = resultObj.optJSONArray("data") ?: org.json.JSONArray()

            val list = mutableListOf<LappCourseItem>()
            for (i in 0 until dataArr.length()) {
                val item = dataArr.getJSONObject(i)
                list.add(
                    LappCourseItem(
                        id = item.optString("id"),
                        bjdm = item.optString("bjdm"),
                        bjmc = item.optString("bjmc"),
                        kcdm = item.optString("kcdm"),
                        kcmc = item.optString("kcmc"),
                        kclx = item.optString("kclx"),
                        xf = item.optString("xf"),
                        xs = item.optString("xs"),
                        xy = item.optString("xy"),
                        lsmc = item.optString("lsmc"),
                        lsgh = item.optString("lsgh"),
                        lszc = item.optString("lszc"),
                        zj = item.optInt("zj", 1),
                        jc = item.optInt("jc", 1),
                        jccd = item.optInt("jccd", 2),
                        classTimeBegin = item.optString("classTimeBegin"),
                        classTimeEnd = item.optString("classTimeEnd"),
                        jsdd = item.optString("jsdd"),
                        jsbh = item.optString("jsbh"),
                        qsz = item.optInt("qsz", 1),
                        jsz = item.optInt("jsz", 18),
                        zc = item.optString("zc"),
                        xn = item.optString("xn"),
                        xq = item.optString("xq")
                    )
                )
            }
            Result.success(Pair(total, list))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 查询全校课表开课信息（优先使用全量/本地持久缓存，forceRefresh=true 时强制从网络拉取并更新缓存）
     */
    suspend fun queryClassSchedule(
        context: Context,
        xn: String = "",
        xq: String = "",
        week: Int = -1,
        xy: String = "",
        zj: Int = -1,
        jc: String = "-1",
        kcmc: String = "",
        kcdm: String = "",
        lsmc: String = "",
        page: Int = 0,
        rows: Int = 50,
        forceRefresh: Boolean = false
    ): Result<Pair<Int, List<LappCourseItem>>> = withContext(Dispatchers.IO) {
        // 1. 优先检查是否存在全校课表全量缓存，若存在直接在内存中秒级检索
        val cachedFullPair = getFullSchoolScheduleCached(context, xn, xq)
        if (!forceRefresh && cachedFullPair != null && cachedFullPair.second.isNotEmpty()) {
            val (ts, allList) = cachedFullPair
            var filtered = allList.asSequence()
            if (week > 0) {
                filtered = filtered.filter { isCourseActiveInWeek(it, week) }
            }
            if (xy.isNotEmpty()) {
                filtered = filtered.filter { it.xy.contains(xy) || xy.contains(it.xy) }
            }
            if (zj > 0) {
                filtered = filtered.filter { it.zj == zj }
            }
            if (jc != "-1" && jc.isNotEmpty()) {
                filtered = filtered.filter { matchesSection(it, jc) }
            }
            if (kcmc.isNotEmpty()) {
                filtered = filtered.filter { it.kcmc.contains(kcmc, ignoreCase = true) }
            }
            if (kcdm.isNotEmpty()) {
                filtered = filtered.filter { it.kcdm.contains(kcdm, ignoreCase = true) || it.bjmc.contains(kcdm, ignoreCase = true) }
            }
            if (lsmc.isNotEmpty()) {
                filtered = filtered.filter { it.lsmc.contains(lsmc, ignoreCase = true) }
            }
            val list = filtered.toList()
            lastQueryFromCache = true
            lastQueryCacheTime = ts
            Log.i(TAG, "queryClassSchedule HIT full schedule in-memory filter: ${list.size} results")
            return@withContext Result.success(Pair(list.size, list))
        }

        val cacheKey = buildScheduleCacheKey(xn, xq, week, xy, zj, jc, kcmc, kcdm, lsmc, page, rows)
        val sharedSlotKey = if (xy.isEmpty() && kcmc.isEmpty() && kcdm.isEmpty() && lsmc.isEmpty() && zj > 0 && jc != "-1" && page == 0) {
            buildScheduleCacheKey(xn, xq, -1, "", zj, jc, "", "", "", 0, 500)
        } else null

        if (!forceRefresh) {
            readScheduleCache(context, cacheKey)?.let { (ts, cachedPair) ->
                Log.i(TAG, "queryClassSchedule HIT local cache: $cacheKey (${cachedPair.second.size} items)")
                lastQueryFromCache = true
                lastQueryCacheTime = ts
                return@withContext Result.success(cachedPair)
            }
            if (sharedSlotKey != null && sharedSlotKey != cacheKey) {
                readScheduleCache(context, sharedSlotKey)?.let { (ts, cachedPair) ->
                    Log.i(TAG, "queryClassSchedule HIT shared slot cache: $sharedSlotKey (${cachedPair.second.size} items)")
                    lastQueryFromCache = true
                    lastQueryCacheTime = ts
                    return@withContext Result.success(cachedPair)
                }
            }
        }

        try {
            val pageResult = fetchSchedulePage(context, xn, xq, week, xy, zj, jc, kcmc, kcdm, lsmc, page = page * rows, rows = rows)
            if (pageResult.isFailure) {
                return@withContext Result.failure(pageResult.exceptionOrNull() ?: Exception("查询失败"))
            }
            val (total, list) = pageResult.getOrThrow()
            val savedTime = writeScheduleCache(context, cacheKey, total, list)
            lastQueryFromCache = false
            lastQueryCacheTime = savedTime
            Result.success(Pair(total, list))
        } catch (e: Exception) {
            Log.e(TAG, "queryClassSchedule error", e)
            Result.failure(e)
        }
    }

    /**
     * 查询学年学期列表
     */
    suspend fun inquiryTerms(context: Context): Result<List<LappTerm>> = withContext(Dispatchers.IO) {
        try {
            ensureSession(context)
            val url = "$BASE_URL/_web/_lightapp/schedule/mobile/termAndWeek/inquiryTerms.rst?pageIndex=0&rows=100"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            Log.i(TAG, "inquiryTerms raw: ${body.take(300)}")
            val json = JSONObject(body)
            val dataArr = json.optJSONObject("result")?.optJSONArray("data") ?: org.json.JSONArray()

            val list = mutableListOf<LappTerm>()
            for (i in 0 until dataArr.length()) {
                val item = dataArr.getJSONObject(i)
                val term = LappTerm(
                    schoolYear = item.optString("schoolYear"),
                    term = item.optString("term"),
                    beginDate = item.optString("beginDate"),
                    endDate = item.optString("endDate"),
                    isCurrent = item.optString("sfdqxq") == "1"
                )
                list.add(term)
                Log.i(TAG, "LappTerm: year=${term.schoolYear}, term=${term.term}, current=${term.isCurrent}")
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "inquiryTerms error", e)
            Result.failure(e)
        }
    }

    /**
     * 查询开课学院列表（支持本地缓存）
     */
    suspend fun queryColleges(context: Context, forceRefresh: Boolean = false): Result<List<String>> = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREF_LAPP_CACHE, Context.MODE_PRIVATE)
        if (!forceRefresh) {
            val cachedJson = prefs.getString("cached_colleges", null)
            if (!cachedJson.isNullOrEmpty()) {
                try {
                    val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                    val cachedList: List<String>? = gson.fromJson(cachedJson, type)
                    if (!cachedList.isNullOrEmpty()) {
                        return@withContext Result.success(cachedList)
                    }
                } catch (_: Exception) {}
            }
        }
        try {
            ensureSession(context)
            val url = "$BASE_URL/_web/_lightapp/schedule/mobile/school/queryClassXy.rst"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            val json = JSONObject(body)
            val dataArr = json.optJSONObject("result")?.optJSONArray("data") ?: org.json.JSONArray()

            val list = mutableListOf<String>()
            for (i in 0 until dataArr.length()) {
                val xy = dataArr.getJSONObject(i).optString("xy").trim()
                if (xy.isNotEmpty() && !list.contains(xy)) {
                    list.add(xy)
                }
            }
            if (list.isNotEmpty()) {
                prefs.edit().putString("cached_colleges", gson.toJson(list)).apply()
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "queryColleges error", e)
            Result.failure(e)
        }
    }

    /**
     * 全部已知教室的全局定义索引（保证按编号从小到大严格升序展示）
     */
    val ALL_KNOWN_CLASSROOMS_ORDER: Map<String, Int> = KNOWN_CLASSROOMS_BY_BUILDING.values
        .flatten()
        .withIndex()
        .associate { it.value to it.index }

    /**
     * 空教室计算：通过指定周次、星期、节次，获取被占用的教室，对比教学楼已知教室得出空闲教室
     * 优先走全校课表全量缓存，且严格剔除非本周次（如仅1-4周或单双周不重叠）的开课记录
     */
    suspend fun queryFreeClassrooms(
        context: Context,
        xn: String,
        xq: String,
        week: Int,
        zj: Int,
        jc: String,
        building: String,
        forceRefresh: Boolean = false
    ): Result<List<FreeClassroomInfo>> = withContext(Dispatchers.IO) {
        try {
            val targetWeek = if (week > 0) week else 1
            val occupiedMap = mutableMapOf<String, LappCourseItem>()

            // 1. 优先拉取或读取全校课表全量缓存进行秒级精确匹配
            val fullResult = getOrFetchFullSchoolSchedule(context, xn, xq, forceRefresh)
            val allCourses = fullResult.getOrNull()

            if (!allCourses.isNullOrEmpty()) {
                val matchedCourses = allCourses.filter { c ->
                    (zj <= 0 || c.zj == zj) &&
                    matchesSection(c, jc) &&
                    isCourseActiveInWeek(c, targetWeek)
                }
                for (c in matchedCourses) {
                    if (c.jsdd.isNotEmpty()) {
                        occupiedMap[c.jsdd] = c
                        occupiedMap[normalizeClassroomName(c.jsdd)] = c
                    }
                }
            } else {
                // 2. 降级备用：单时段网络查询，同时严格按 targetWeek 和 jc 过滤
                val schedResult = queryClassSchedule(
                    context = context,
                    xn = xn,
                    xq = xq,
                    week = targetWeek,
                    zj = zj,
                    jc = jc,
                    rows = 500,
                    forceRefresh = forceRefresh
                )

                if (schedResult.isFailure) {
                    return@withContext Result.failure(schedResult.exceptionOrNull() ?: Exception("查询失败"))
                }

                val (_, courses) = schedResult.getOrNull() ?: Pair(0, emptyList())
                for (c in courses) {
                    if (c.jsdd.isNotEmpty() && isCourseActiveInWeek(c, targetWeek) && matchesSection(c, jc)) {
                        occupiedMap[c.jsdd] = c
                        occupiedMap[normalizeClassroomName(c.jsdd)] = c
                    }
                }
            }

            // 过滤教学楼
            val targetBuildings = if (building == "全部教学楼" || building.isEmpty()) {
                KNOWN_CLASSROOMS_BY_BUILDING.keys
            } else {
                KNOWN_CLASSROOMS_BY_BUILDING.keys.filter { it.contains(building) || building.contains(it) }
            }

            val resultList = mutableListOf<FreeClassroomInfo>()
            for (bName in targetBuildings) {
                val roomList = KNOWN_CLASSROOMS_BY_BUILDING[bName] ?: continue
                for (room in roomList) {
                    val occupied = occupiedMap[room] ?: occupiedMap[normalizeClassroomName(room)]
                    if (occupied != null) {
                        resultList.add(
                            FreeClassroomInfo(
                                name = room,
                                building = bName,
                                isFree = false,
                                occupiedCourse = occupied.kcmc,
                                occupiedTeacher = occupied.lsmc
                            )
                        )
                    } else {
                        resultList.add(
                            FreeClassroomInfo(
                                name = room,
                                building = bName,
                                isFree = true
                            )
                        )
                    }
                }
            }

            // 按已知教室编号字典顺序升序排列
            resultList.sortBy { ALL_KNOWN_CLASSROOMS_ORDER[it.name] ?: 9999 }
            Result.success(resultList)
        } catch (e: Exception) {
            Log.e(TAG, "queryFreeClassrooms error", e)
            Result.failure(e)
        }
    }
}
