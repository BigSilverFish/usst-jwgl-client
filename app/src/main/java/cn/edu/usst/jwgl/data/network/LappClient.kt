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
        "综合楼"
    )

    private val KNOWN_CLASSROOMS_BY_BUILDING: Map<String, List<String>> = mapOf(
        "第一教学楼" to listOf(
            "一教100", "一教101", "一教105", "一教109", "一教110", "一教113", "一教116", "一教117", "一教118", "一教119",
            "一教120", "一教121", "一教123", "一教124", "一教126", "一教128", "一教129", "一教130", "一教131", "一教133",
            "一教134", "一教137", "一教138", "一教140", "一教144", "一教146", "一教201", "一教205", "一教209", "一教210",
            "一教213", "一教214", "一教215", "一教216", "一教217", "一教218", "一教219", "一教220", "一教223", "一教225",
            "一教226", "一教229", "一教230", "一教232", "一教234", "一教235", "一教237", "一教238", "一教240", "一教242",
            "一教244", "一教301", "一教305", "一教309", "一教310", "一教313", "一教314", "一教315", "一教317", "一教318",
            "一教319", "一教320", "一教323", "一教325", "一教326", "一教329", "一教330", "一教332", "一教334", "一教335",
            "一教337", "一教338", "一教340", "一教344", "一教401", "一教405", "一教410", "一教413", "一教417", "一教420"
        ),
        "第三教学楼" to listOf(
            "三教101", "三教102", "三教103", "三教105", "三教106", "三教110", "三教113", "三教117", "三教201", "三教202",
            "三教203", "三教205", "三教206", "三教210", "三教213", "三教217", "三教301", "三教302", "三教303", "三教305",
            "三教306", "三教310", "三教313", "三教317", "三教401", "三教402", "三教403", "三教405", "三教406", "三教410",
            "三教413", "三教417", "三教501", "三教502", "三教503", "三教505", "三教506", "三教510", "三教513", "三教601",
            "三教602", "三教605"
        ),
        "第四教学楼" to listOf(
            "四教101", "四教102", "四教103", "四教104", "四教107", "四教115", "四教203", "四教205", "四教207", "四教301",
            "四教303", "四教307"
        ),
        "卓越楼" to listOf(
            "卓越楼118", "卓越楼122", "卓越楼123", "卓越楼127", "卓越楼201", "卓越楼202", "卓越楼203", "卓越楼204", "卓越楼205",
            "卓越楼301", "卓越楼302", "卓越楼303", "卓越楼304", "卓越楼305", "卓越楼401", "卓越楼402", "卓越楼403", "卓越楼404",
            "卓越楼501", "卓越楼502"
        ),
        "国合楼" to listOf(
            "国合楼101", "国合楼102", "国合楼103", "国合楼104", "国合楼105", "国合楼106", "国合楼201", "国合楼202", "国合楼203",
            "国合楼204", "国合楼205", "国合楼301", "国合楼302", "国合楼303", "国合楼304", "国合楼305", "国合楼306", "国合楼401",
            "国合楼402", "国合楼403", "国合楼404", "国合楼405", "国合楼406", "国合楼501", "国合楼502", "国合楼503", "国合楼504"
        ),
        "综合楼" to listOf(
            "综合楼A区101", "综合楼A区102", "综合楼A区201", "综合楼A区202", "综合楼B区101", "综合楼B区104", "综合楼B区105",
            "综合楼B区108", "综合楼B区201", "综合楼B区204", "综合楼B区205", "综合楼B区208", "综合楼B区301", "综合楼B区304",
            "综合楼B区305", "综合楼C区101", "综合楼C区102", "综合楼C区201", "综合楼C区202", "综合楼D区101", "综合楼D区106",
            "综合楼D区201", "综合楼D区206"
        )
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

    private fun getFullCacheFile(context: Context, xn: String, xq: String): java.io.File {
        val safeXn = xn.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val safeXq = xq.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return java.io.File(context.cacheDir, "full_school_schedule_${safeXn}_${safeXq}.json")
    }

    fun getFullSchoolScheduleCached(context: Context, xn: String, xq: String): Pair<Long, List<LappCourseItem>>? {
        val key = "${xn.trim()}_${xq.trim()}"
        fullScheduleMemoryCache[key]?.let { return it }

        val file = getFullCacheFile(context, xn, xq)
        if (!file.exists() || file.length() == 0L) return null

        return try {
            val jsonStr = file.readText(Charsets.UTF_8)
            val root = JSONObject(jsonStr)
            val ts = root.optLong("time", file.lastModified())
            val itemsJson = root.optString("items", "[]")
            val type = object : com.google.gson.reflect.TypeToken<List<LappCourseItem>>() {}.type
            val list: List<LappCourseItem> = gson.fromJson(itemsJson, type) ?: emptyList()
            val pair = Pair(ts, list)
            fullScheduleMemoryCache[key] = pair
            pair
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read full schedule file cache for $key", e)
            null
        }
    }

    private fun saveFullSchoolSchedule(context: Context, xn: String, xq: String, list: List<LappCourseItem>): Long {
        val key = "${xn.trim()}_${xq.trim()}"
        val now = System.currentTimeMillis()
        fullScheduleMemoryCache[key] = Pair(now, list)
        try {
            val file = getFullCacheFile(context, xn, xq)
            val root = JSONObject().apply {
                put("time", now)
                put("items", gson.toJson(list))
            }
            file.writeText(root.toString(), Charsets.UTF_8)
            Log.i(TAG, "Saved full schedule cache to ${file.absolutePath} (${list.size} courses)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write full schedule file cache for $key", e)
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
                    page = page,
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
            val pageResult = fetchSchedulePage(context, xn, xq, week, xy, zj, jc, kcmc, kcdm, lsmc, page, rows)
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
                    val occupied = occupiedMap[room]
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
