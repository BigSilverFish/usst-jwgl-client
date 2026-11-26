package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.util.Log
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.model.LappCourseItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
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

    fun normalizeClassroomName(name: String): String =
        LappScheduleFilter.normalizeClassroomName(name)

    /**
     * 同步 JwglClient 中的已登录 SSO 凭证
     */
    fun syncCookiesFromJwgl(jwglCookies: List<Cookie>) {
        for (c in jwglCookies) {
            // 严禁将 jwgl/ids6 等非 lapp 专用的局部 JSESSIONID 覆盖进 LappClient，避免虚假判定 lapp 已登录
            if (c.name.equals("JSESSIONID", ignoreCase = true) && !c.domain.contains("lapp")) {
                continue
            }
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

    internal fun hasLappSession(): Boolean {
        return cookieStore.any { 
            it.name.equals("JSESSIONID", ignoreCase = true) && 
            (it.domain.contains("lapp") || it.domain.equals("lapp.usst.edu.cn", ignoreCase = true)) 
        }
    }

    internal fun clearCookies() {
        cookieStore.clear()
    }

    private val gson = com.google.gson.Gson()

    @Volatile
    var lastQueryFromCache: Boolean = false
        private set

    @Volatile
    var lastQueryCacheTime: Long = 0L
        private set

    @Volatile
    private var lastQueryFullCacheVerified: Boolean? = null

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

    private fun readScheduleCache(context: Context, key: String): Pair<Long, Pair<Int, List<LappCourseItem>>>? =
        LappScheduleCacheStore.readScheduleCache(context, key)

    private fun writeScheduleCache(context: Context, key: String, total: Int, list: List<LappCourseItem>): Long =
        LappScheduleCacheStore.writeScheduleCache(context, key, total, list)

    fun formatCacheTag(timestamp: Long = lastQueryCacheTime, fromCache: Boolean = lastQueryFromCache): String {
        if (timestamp <= 0L) return ""
        if (!fromCache) return "刚刚同步"
        if (lastQueryFullCacheVerified == false) return "缓存 · 完整性未核验"
        val diff = System.currentTimeMillis() - timestamp
        if (diff < 60_000L) return "缓存 · 刚刚更新"
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        return "缓存 · ${sdf.format(java.util.Date(timestamp))}"
    }

    fun isCourseActiveInWeek(c: LappCourseItem, targetWeek: Int): Boolean =
        LappScheduleFilter.isCourseActiveInWeek(c, targetWeek)

    fun getQuerySectionRange(queryJc: String): IntRange? =
        LappScheduleFilter.getQuerySectionRange(queryJc)

    fun matchesSection(c: LappCourseItem, queryJc: String): Boolean =
        LappScheduleFilter.matchesSection(c, queryJc)

    private val fullScheduleMemoryCache get() = LappScheduleCacheStore.fullScheduleMemoryCache
    private val fullScheduleReads get() = LappScheduleCacheStore.fullScheduleReads
    private val verifiedFullSchedules get() = LappScheduleCacheStore.verifiedFullSchedules
    private val failedFullSyncAt get() = LappScheduleCacheStore.failedFullSyncAt

    private fun fullScheduleKey(xn: String, xq: String) = LappScheduleCacheStore.fullScheduleKey(xn, xq)

    fun matchesCollege(courseXy: String, selectedXy: String): Boolean =
        LappScheduleFilter.matchesCollege(courseXy, selectedXy)

    fun normalizeKeyword(input: String): String =
        LappScheduleFilter.normalizeKeyword(input)

    fun getFullSchoolScheduleCached(context: Context, xn: String, xq: String): Pair<Long, List<LappCourseItem>>? =
        LappScheduleCacheStore.getFullSchoolScheduleCached(context, xn, xq)

    internal fun shouldSelfHealWithPreset(localSize: Int?, locallyVerified: Boolean, presetSize: Int): Boolean =
        LappScheduleCacheStore.shouldSelfHealWithPreset(localSize, locallyVerified, presetSize)

    internal fun shouldRejectUpstreamScheduleOverwrite(fetchedSize: Int, existingSize: Int): Boolean =
        LappScheduleCacheStore.shouldRejectUpstreamScheduleOverwrite(fetchedSize, existingSize)

    private fun saveFullSchoolSchedule(context: Context, xn: String, xq: String, list: List<LappCourseItem>, verified: Boolean = false): Long =
        LappScheduleCacheStore.saveFullSchoolSchedule(context, xn, xq, list, verified)

    /**
     * 获取或拉取全校课表全量缓存（初次加载时全量分页拉取并缓存至本地）
     */
    suspend fun getOrFetchFullSchoolSchedule(
        context: Context,
        xn: String,
        xq: String,
        forceRefresh: Boolean = false
    ): Result<List<LappCourseItem>> = withContext(Dispatchers.IO) {
        val termKey = fullScheduleKey(xn, xq)
        if (!forceRefresh) {
            getFullSchoolScheduleCached(context, xn, xq)?.let { (ts, list) ->
                val recentlyFailed = System.currentTimeMillis() - (failedFullSyncAt[termKey] ?: 0L) in 0..30_000L
                if (termKey in verifiedFullSchedules || recentlyFailed) {
                    lastQueryFromCache = true
                    lastQueryCacheTime = ts
                    lastQueryFullCacheVerified = termKey in verifiedFullSchedules
                    return@withContext Result.success(list)
                }
            }
        }

        try {
            ensureSession(context)
            val account = AuthPreferences(context).getStudentId()
            val allCourses = fullScheduleReads.await("${context.applicationContext.filesDir}|$account|$xn|$xq") {
                val complete = completeSchedulePages<LappCourseItem>(key = { it.id.takeIf(String::isNotBlank) ?: it }) { offset, rows ->
                    fetchSchedulePage(
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
                    page = offset,
                    rows = rows
                    ).getOrThrow()
                }
                val existingSize = getFullSchoolScheduleCached(context, xn, xq)?.second?.size ?: 0
                if (shouldRejectUpstreamScheduleOverwrite(complete.size, existingSize)) {
                    throw IllegalStateException("上游课表接口返回异常（${complete.size} 条），已保留本地缓存")
                }
                saveFullSchoolSchedule(context, xn, xq, complete, verified = true)
                failedFullSyncAt.remove(termKey)
                complete
            }
            lastQueryFromCache = false
            lastQueryFullCacheVerified = true
            lastQueryCacheTime = fullScheduleMemoryCache["${xn.trim()}_${xq.trim()}"]?.first ?: System.currentTimeMillis()
            Result.success(allCourses)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failedFullSyncAt[termKey] = System.currentTimeMillis()
            Log.e(TAG, "getOrFetchFullSchoolSchedule error", e)
            val fallback = getFullSchoolScheduleCached(context, xn, xq)
            if (fallback != null) {
                lastQueryFromCache = true
                lastQueryCacheTime = fallback.first
                lastQueryFullCacheVerified = termKey in verifiedFullSchedules
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

            val resultObj = json.optJSONObject("result") ?: error("课表响应缺少 result")
            val total = resultObj.opt("total")?.toString()?.toIntOrNull() ?: error("课表响应缺少有效总数")
            val dataArr = resultObj.optJSONArray("data") ?: error("课表响应缺少课程列表")

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
        lastQueryFullCacheVerified = null
        var cachedFullPair = getFullSchoolScheduleCached(context, xn, xq)
        val isCompositeSection = jc != "-1" && jc.contains("-")
        if (isCompositeSection || (cachedFullPair != null && (forceRefresh || fullScheduleKey(xn, xq) !in verifiedFullSchedules))) {
            val fullResult = getOrFetchFullSchoolSchedule(context, xn, xq, forceRefresh)
            cachedFullPair = getFullSchoolScheduleCached(context, xn, xq)
            if (isCompositeSection && cachedFullPair == null) {
                return@withContext Result.failure(fullResult.exceptionOrNull()
                    ?: Exception("节次区间查询需要完整课表，请联网同步后重试"))
            }
        }
        val filterFromFullCache = { pair: Pair<Long, List<LappCourseItem>> ->
            val (ts, allList) = pair
            var filtered = allList.asSequence()
            if (week > 0) {
                filtered = filtered.filter { isCourseActiveInWeek(it, week) }
            }
            if (xy.isNotEmpty()) {
                filtered = filtered.filter { matchesCollege(it.xy, xy) }
            }
            if (zj > 0) {
                filtered = filtered.filter { it.zj == zj }
            }
            if (jc != "-1" && jc.isNotEmpty()) {
                filtered = filtered.filter { matchesSection(it, jc) }
            }
            if (kcmc.isNotEmpty()) {
                val normKcmc = normalizeKeyword(kcmc)
                filtered = filtered.filter { normalizeKeyword(it.kcmc).contains(normKcmc) }
            }
            if (kcdm.isNotEmpty()) {
                val normKcdm = normalizeKeyword(kcdm)
                filtered = filtered.filter { it.kcdm.contains(normKcdm, ignoreCase = true) || it.bjmc.contains(normKcdm, ignoreCase = true) }
            }
            if (lsmc.isNotEmpty()) {
                val normLsmc = normalizeKeyword(lsmc)
                filtered = filtered.filter { it.lsmc.contains(normLsmc, ignoreCase = true) }
            }
            val list = filtered.toList()
            lastQueryFromCache = true
            lastQueryCacheTime = ts
            lastQueryFullCacheVerified = fullScheduleKey(xn, xq) in verifiedFullSchedules
            Log.i(TAG, "queryClassSchedule HIT full schedule in-memory filter: ${list.size} results")
            Pair(list.size, list)
        }

        val hasFullCache = cachedFullPair != null

        // 如果是多节次区间（如 "6-7"、"1-2" 等），服务端接口无法处理复合字符串，只能由本地全量缓存精确区间过滤
        if (hasFullCache) {
            val result = filterFromFullCache(cachedFullPair!!)
            return@withContext Result.success(result)
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
                // 网络失败，若本地有全量缓存，自动 Fallback 保证界面不为空
                if (hasFullCache) {
                    val fallback = filterFromFullCache(cachedFullPair!!)
                    Log.w(TAG, "fetchSchedulePage failed on forceRefresh, safely fallback to local cache: ${fallback.second.size} items")
                    return@withContext Result.success(fallback)
                }
                return@withContext Result.failure(pageResult.exceptionOrNull() ?: Exception("查询失败"))
            }
            val (total, list) = pageResult.getOrThrow()
            if (list.isEmpty() && hasFullCache) {
                // 服务端单时段接口未查到或参数不兼容，但本地全量数据有匹配结果，安全 Fallback
                val fallback = filterFromFullCache(cachedFullPair!!)
                if (fallback.second.isNotEmpty()) {
                    Log.i(TAG, "fetchSchedulePage returned 0 items, safely fallback to full schedule cache: ${fallback.second.size} items")
                    return@withContext Result.success(fallback)
                }
            }
            val savedTime = writeScheduleCache(context, cacheKey, total, list)
            lastQueryFromCache = false
            lastQueryCacheTime = savedTime
            Result.success(Pair(total, list))
        } catch (e: Exception) {
            Log.e(TAG, "queryClassSchedule error", e)
            if (hasFullCache) {
                val fallback = filterFromFullCache(cachedFullPair!!)
                return@withContext Result.success(fallback)
            }
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
        val prefs = context.getSharedPreferences(LappScheduleCacheStore.PREF_LAPP_CACHE, Context.MODE_PRIVATE)
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
                return@withContext Result.success(list)
            }
            val fallback = deriveCollegesFromLocalSchedule(context)
            if (fallback.isNotEmpty()) {
                return@withContext Result.success(fallback)
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "queryColleges error", e)
            val fallback = deriveCollegesFromLocalSchedule(context)
            if (fallback.isNotEmpty()) {
                Result.success(fallback)
            } else {
                Result.failure(e)
            }
        }
    }

    private fun deriveCollegesFromLocalSchedule(context: Context): List<String> {
        return try {
            val (curXnm, curXqm) = cn.edu.usst.jwgl.util.SemesterHelper.getCurrentSemester()
            val curYear = curXnm.toIntOrNull() ?: 2026
            val xn = "$curYear-${curYear + 1}"
            val xq = if (curXqm == "3") "1" else "2"
            val courses = getFullSchoolScheduleCached(context, xn, xq)?.second
                ?: fullScheduleMemoryCache.values.firstOrNull { it.second.isNotEmpty() }?.second
                ?: return emptyList()
            val aliasedChildren = LappScheduleFilter.COLLEGE_FILTER_ALIASES.flatMap { (parent, children) ->
                children.filter { it != parent }
            }.toSet()
            courses.asSequence()
                .map { it.xy.trim() }
                .filter { it.isNotEmpty() && it !in aliasedChildren }
                .distinct()
                .sorted()
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * 全部已知教室的全局定义索引（保证按编号从小到大严格升序展示）
     */
    val ALL_KNOWN_CLASSROOMS_ORDER: Map<String, Int> = LappScheduleFilter.KNOWN_CLASSROOMS_BY_BUILDING.values
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
            var coursesSource: List<LappCourseItem> = emptyList()

            // 1. 优先拉取或读取全校课表全量缓存进行秒级精确匹配
            val fullResult = getOrFetchFullSchoolSchedule(context, xn, xq, forceRefresh)
            val allCourses = fullResult.getOrNull()
            if (allCourses != null && fullScheduleKey(xn, xq) !in verifiedFullSchedules && allCourses.size < 5000) {
                return@withContext Result.failure(Exception("当前全校课表完整性未核验，暂不能准确判断空教室；请联网同步"))
            }
            if (allCourses != null && allCourses.size >= 5000) {
                verifiedFullSchedules.add(fullScheduleKey(xn, xq))
            }

            if (allCourses != null) {
                coursesSource = allCourses
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

                val (total, courses) = schedResult.getOrNull() ?: Pair(0, emptyList())
                if (total != courses.size) {
                    return@withContext Result.failure(Exception("时段课表尚未完整获取，暂不能准确判断空教室"))
                }
                coursesSource = courses
                for (c in courses) {
                    if (c.jsdd.isNotEmpty() && isCourseActiveInWeek(c, targetWeek) && matchesSection(c, jc)) {
                        occupiedMap[c.jsdd] = c
                        occupiedMap[normalizeClassroomName(c.jsdd)] = c
                    }
                }
            }

            // 动态发现全校排课数据中的教室，补充至对应教学楼集合中（CLS-02）
            val discoveredClassroomsByBuilding = mutableMapOf<String, MutableSet<String>>()
            for ((bName, rooms) in LappScheduleFilter.KNOWN_CLASSROOMS_BY_BUILDING) {
                discoveredClassroomsByBuilding[bName] = LinkedHashSet(rooms)
            }

            for (c in coursesSource) {
                val room = c.jsdd.trim()
                if (room.isNotBlank()) {
                    val targetB = when {
                        room.startsWith("一教") || room.contains("第一教学楼") -> "第一教学楼"
                        room.startsWith("二教") || room.contains("第二教学楼") -> "第二教学楼"
                        room.startsWith("三教") || room.contains("第三教学楼") -> "第三教学楼"
                        room.startsWith("四教") || room.contains("第四教学楼") -> "第四教学楼"
                        room.startsWith("五教") || room.contains("第五教学楼") -> "第五教学楼"
                        room.startsWith("六教") || room.contains("第六教学楼") -> "第六教学楼"
                        room.startsWith("七教") || room.contains("第七教学楼") -> "第七教学楼"
                        room.startsWith("八教") || room.contains("第八教学楼") -> "第八教学楼"
                        room.startsWith("基础") || room.contains("基础学院") -> "基础学院教学楼"
                        room.contains("先进") || room.contains("制造") -> "先进制造大楼"
                        room.contains("图文") || room.contains("信息") -> "图文信息中心"
                        room.contains("体育") || room.contains("活动") -> "学生活动中心与体育馆"
                        else -> null
                    }
                    if (targetB != null) {
                        discoveredClassroomsByBuilding.getOrPut(targetB) { LinkedHashSet() }.add(room)
                    }
                }
            }

            // 过滤教学楼
            val targetBuildings = if (building == "全部教学楼" || building.isEmpty()) {
                discoveredClassroomsByBuilding.keys
            } else {
                discoveredClassroomsByBuilding.keys.filter { it.contains(building) || building.contains(it) }
            }

            val resultList = mutableListOf<FreeClassroomInfo>()
            for (bName in targetBuildings) {
                val roomList = discoveredClassroomsByBuilding[bName] ?: continue
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
            resultList.sortBy { ALL_KNOWN_CLASSROOMS_ORDER[it.name] ?: (10000 + (it.name.hashCode() and 0x7fff)) }
            Result.success(resultList)
        } catch (e: Exception) {
            Log.e(TAG, "queryFreeClassrooms error", e)
            Result.failure(e)
        }
    }
}
