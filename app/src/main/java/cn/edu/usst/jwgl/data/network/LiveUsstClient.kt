package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.model.LiveCameraConstant
import cn.edu.usst.jwgl.data.model.LiveCourseRecord
import cn.edu.usst.jwgl.data.model.LiveDeviceViewDto
import cn.edu.usst.jwgl.util.CookieSyncHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * 智慧录播系统 (live.usst.edu.cn) 客户端
 * 提供直播间元数据检索、FLV低延迟机位直连与机位降级匹配
 */
class LiveUsstClient(private val context: Context) {

    companion object {
        private const val TAG = "LiveUsstClient"
        const val BASE_URL = "https://live.usst.edu.cn/jy-application-resourcemanage"
        private const val MOBILE_UI_REFERER = "https://live.usst.edu.cn/jy-mobile-resourcemanage-ui/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private const val AUTHORIZE_URL =
            "https://live.usst.edu.cn/jy-application-resourcemanage/oauth2/authorize?json=0&returnUri=https%3A%2F%2Flive.usst.edu.cn%2Fjy-mobile-resourcemanage-ui%2F%23%2FliveList%3Ftype%3Dcas"

        @Volatile
        private var cachedJwtToken: String? = null
        @Volatile
        private var tokenExpireTimeMillis: Long = 0L
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    if (cookies.isEmpty()) return
                    val cookieManager = CookieManager.getInstance()
                    cookies.forEach { cookie ->
                        cookieManager.setCookie(url.toString(), cookie.toString())
                    }
                    cookieManager.flush()
                    JwglClient.addCookies(cookies)
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val merged = LinkedHashMap<String, Cookie>()

                    val cookieStr = CookieManager.getInstance().getCookie(url.toString()) ?: ""
                    if (cookieStr.isNotBlank()) {
                        cookieStr.split(";").forEach { item ->
                            val parts = item.trim().split("=", limit = 2)
                            if (parts.size == 2) {
                                val name = parts[0].trim()
                                val value = parts[1].trim()
                                if (name.isNotEmpty()) {
                                    Cookie.Builder()
                                        .domain(url.host)
                                        .path("/")
                                        .name(name)
                                        .value(value)
                                        .build()
                                        .let { merged[name] = it }
                                }
                            }
                        }
                    }

                    // 合并 JwglClient 内存 CookieJar（覆盖或补充后台 CAS 换票得到的会话 Cookie）
                    JwglClient.getCookies()
                        .filter { it.matches(url) }
                        .forEach { cookie ->
                            merged[cookie.name] = cookie
                        }

                    return merged.values.toList()
                }
            })
            .build()
    }

    /**
     * 设置或注入 Jwt-Token
     */
    fun setJwtToken(token: String, validDurationMillis: Long = 2 * 3600 * 1000L) {
        cachedJwtToken = token
        tokenExpireTimeMillis = System.currentTimeMillis() + validDurationMillis
    }

    fun clearCachedToken() {
        cachedJwtToken = null
        tokenExpireTimeMillis = 0L
    }

    /**
     * 获取有效的 Jwt-Token
     */
    suspend fun ensureJwtToken(forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!forceRefresh && !cachedJwtToken.isNullOrBlank() && System.currentTimeMillis() < tokenExpireTimeMillis) {
            return@withContext cachedJwtToken
        }

        if (forceRefresh) {
            clearCachedToken()
        }

        // 先将教务/CAS 会话同步至 WebView CookieManager
        CookieSyncHelper.syncJwglCookiesToWebView()

        // 1. 快速路径：如果已有 live.usst.edu.cn 有效会话 Cookie，直接调用 /oauth2/token 换取 jwt_token
        fetchTokenFromEndpoint()?.let { token ->
            setJwtToken(token)
            Log.i(TAG, "Obtained jwt_token directly from /oauth2/token (${token.take(8)}...)")
            return@withContext token
        }

        // 2. 使用已保存凭据走 CAS SSO 静默授权进入 live.usst.edu.cn
        val authPrefs = AuthPreferences(context)
        val studentId = authPrefs.getStudentId()
        val password = authPrefs.getPassword()
        if (studentId.isNotBlank() && password.isNotBlank()) {
            val preAuth = JwglClient.preAuthCasService(AUTHORIZE_URL, studentId, password)
            CookieSyncHelper.syncJwglCookiesToWebView()
            preAuth.onSuccess { finalUrl ->
                Log.d(TAG, "CAS pre-auth for live finished, finalUrl=$finalUrl")
                extractTokenFromUrl(finalUrl)?.let { token ->
                    setJwtToken(token)
                    Log.i(TAG, "Extracted jwt_token from CAS finalUrl (${token.take(8)}...)")
                    return@withContext token
                }
            }.onFailure { err ->
                Log.w(TAG, "CAS pre-auth for live failed: ${err.message}")
            }
        } else {
            // 无保存密码时（例如仅 WebView SSO 登录），尝试沿既有 Cookie 走 OAuth2 重定向
            followAuthorizeRedirectsForToken()?.let { token ->
                setJwtToken(token)
                Log.i(TAG, "Extracted jwt_token from OAuth2 redirects (${token.take(8)}...)")
                return@withContext token
            }
        }

        // 3. 与前端 CasLogin 对齐：触发 authority/me 后再请求 /oauth2/token
        probeAuthorityMe()
        fetchTokenFromEndpoint()?.let { token ->
            setJwtToken(token)
            Log.i(TAG, "Obtained jwt_token from /oauth2/token after auth (${token.take(8)}...)")
            return@withContext token
        }

        Log.w(TAG, "Unable to obtain jwt_token for live.usst.edu.cn")
        cachedJwtToken
    }

    private fun fetchTokenFromEndpoint(): String? {
        return try {
            val req = Request.Builder()
                .url("$BASE_URL/oauth2/token")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", MOBILE_UI_REFERER)
                .get()
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || body.isBlank()) {
                    Log.d(TAG, "/oauth2/token returned HTTP ${resp.code}")
                    return null
                }
                extractTokenFromJson(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error calling /oauth2/token: ${e.message}")
            null
        }
    }

    private fun extractTokenFromJson(body: String): String? {
        return try {
            val obj = JSONObject(body)
            val resultObj = obj.optJSONObject("result")
            val dataObj = obj.optJSONObject("data")
            listOfNotNull(
                resultObj?.optString("jwt_token"),
                dataObj?.optString("jwt_token"),
                obj.optString("jwt_token"),
                resultObj?.optString("token"),
                dataObj?.optString("token")
            ).firstOrNull { it.isNotBlank() && it != "null" }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse /oauth2/token JSON: ${e.message}")
            null
        }
    }

    private fun followAuthorizeRedirectsForToken(): String? {
        return try {
            val clientNoRedirect = okHttpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            var currentUrl = AUTHORIZE_URL
            var hops = 0
            while (hops < 12) {
                hops++
                val req = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", USER_AGENT)
                    .build()

                clientNoRedirect.newCall(req).execute().use { resp ->
                    val code = resp.code
                    val location = resp.header("Location")
                    if (location.isNullOrBlank()) {
                        return null
                    }
                    val resolvedUrl = resp.request.url.resolve(location)?.toString() ?: location
                    val tokenFromUrl = extractTokenFromUrl(resolvedUrl)
                    if (!tokenFromUrl.isNullOrBlank()) {
                        return tokenFromUrl
                    }
                    currentUrl = resolvedUrl
                    if (code !in 300..399) {
                        return null
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error following authorize redirects: ${e.message}")
            null
        }
    }

    private fun probeAuthorityMe() {
        try {
            val req = Request.Builder()
                .url("https://live.usst.edu.cn/cloud-rbac/authority/me")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", MOBILE_UI_REFERER)
                .get()
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                Log.d(TAG, "/cloud-rbac/authority/me HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            Log.d(TAG, "/cloud-rbac/authority/me skipped: ${e.message}")
        }
    }

    private fun extractTokenFromUrl(url: String): String? {
        try {
            val uri = Uri.parse(url)
            val queryToken = uri.getQueryParameter("jwt_token")
            if (!queryToken.isNullOrBlank()) return queryToken

            val fragment = uri.fragment ?: ""
            if (fragment.contains("jwt_token=")) {
                val fUri = Uri.parse("https://dummy/?" + fragment.substringAfter("?"))
                val fToken = fUri.getQueryParameter("jwt_token")
                if (!fToken.isNullOrBlank()) return fToken
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing url for token: $url", e)
        }
        return null
    }

    private fun resolveTodayTimeRange(): Pair<String, String> {
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = tz }
        var nowMillis = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url("$BASE_URL/mobile/getServerDateTime")
                .header("User-Agent", USER_AGENT)
                .header("Referer", MOBILE_UI_REFERER)
                .get()
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val ts = LiveUsstJsonParser.parseServerDateTimeMillis(body)
                    if (ts != null) {
                        nowMillis = ts
                    }
                }
            }
        } catch (_: Exception) {
        }
        val dateStr = dayFormat.format(Date(nowMillis))
        return "$dateStr 00:00:00" to "$dateStr 23:59:59"
    }

    /**
     * 获取今日（或当前正在进行）的课程直播列表
     */
    suspend fun fetchOngoingLiveCourses(keyword: String = ""): Result<List<LiveCourseRecord>> =
        withContext(Dispatchers.IO) {
            try {
                val (beginTime, endTime) = resolveTodayTimeRange()
                var token = ensureJwtToken()
                if (token.isNullOrBlank()) {
                    return@withContext Result.failure(IOException("无法获取智慧录播登录令牌 (Jwt-Token)"))
                }

                var attempt = 0
                while (attempt < 2) {
                    attempt++
                    val urlBuilder = Uri.parse("$BASE_URL/mobile/union/vod_live_new").buildUpon()
                        .appendQueryParameter("courStatus", "0")
                        .appendQueryParameter("unionName", keyword.trim())
                        .appendQueryParameter("courBeginTime", beginTime)
                        .appendQueryParameter("courEndTime", endTime)
                        .appendQueryParameter("page.pageIndex", "1")
                        .appendQueryParameter("page.pageSize", "50")
                        .appendQueryParameter("page.orders[0].asc", "true")
                        .appendQueryParameter("page.orders[0].field", "courBeginTime")

                    val req = Request.Builder()
                        .url(urlBuilder.build().toString())
                        .header("User-Agent", USER_AGENT)
                        .header("Referer", MOBILE_UI_REFERER)
                        .header("Jwt-Token", token!!)
                        .get()
                        .build()

                    okHttpClient.newCall(req).execute().use { resp ->
                        if (resp.code == 401 && attempt == 1) {
                            Log.w(TAG, "vod_live_new returned 401, refreshing Jwt-Token and retrying...")
                            token = ensureJwtToken(forceRefresh = true)
                            if (token.isNullOrBlank()) {
                                return@withContext Result.failure(IOException("智慧录播登录凭证已失效 (HTTP 401)"))
                            }
                            return@use
                        }
                        if (!resp.isSuccessful) {
                            return@withContext Result.failure(IOException("查询直播列表失败: HTTP ${resp.code}"))
                        }

                        val body = resp.body?.string().orEmpty()
                        val searchResp = LiveUsstJsonParser.parseUnionSearchResponse(body)
                        val records = searchResp.data?.records ?: emptyList()
                        val now = System.currentTimeMillis()
                        val sorted = records.sortedByDescending { it.isCurrentlyLive(now) }
                        Log.i(TAG, "fetchOngoingLiveCourses(keyword='$keyword') returned ${sorted.size} records")
                        return@withContext Result.success(sorted)
                    }
                }
                Result.failure(IOException("查询直播列表失败: 鉴权重试后仍未通过"))
            } catch (e: Exception) {
                Log.e(TAG, "fetchOngoingLiveCourses failed: ${e.message}", e)
                Result.failure(e)
            }
        }

    /**
     * 查询指定课程当前正在直播的记录
     */
    suspend fun searchLiveCourse(
        courseName: String,
        classroom: String? = null
    ): Result<LiveCourseRecord?> = withContext(Dispatchers.IO) {
        val primaryRes = fetchOngoingLiveCourses(courseName)
        if (primaryRes.isFailure) {
            return@withContext Result.failure(primaryRes.exceptionOrNull() ?: IOException("查询直播失败"))
        }

        var records = primaryRes.getOrDefault(emptyList())
        if (records.isEmpty() && courseName.isNotBlank()) {
            // 服务端 unionName 精确度较高时，回退拉取今日全部直播再做本地模糊匹配
            val allRes = fetchOngoingLiveCourses("")
            if (allRes.isSuccess) {
                records = allRes.getOrDefault(emptyList())
            }
        }

        if (records.isEmpty()) {
            Log.d(TAG, "No live records found for course: $courseName")
            return@withContext Result.success(null)
        }

        val normalizedCourse = normalizeForMatch(courseName)
        val normalizedRoom = normalizeForMatch(classroom)
        val now = System.currentTimeMillis()

        val matched = records.firstOrNull { record ->
            val recName = normalizeForMatch(record.subjName)
            val recRoom = normalizeForMatch(record.clroName)
            val nameMatch = normalizedCourse.isEmpty() || recName.contains(normalizedCourse) || normalizedCourse.contains(recName)
            val roomMatch = normalizedRoom.isEmpty() || recRoom.contains(normalizedRoom) || normalizedRoom.contains(recRoom)
            nameMatch && roomMatch && record.isCurrentlyLive(now)
        } ?: records.firstOrNull { record ->
            val recName = normalizeForMatch(record.subjName)
            val nameMatch = normalizedCourse.isEmpty() || recName.contains(normalizedCourse) || normalizedCourse.contains(recName)
            nameMatch && record.isCurrentlyLive(now)
        } ?: records.firstOrNull { record ->
            val recName = normalizeForMatch(record.subjName)
            normalizedCourse.isNotEmpty() && (recName.contains(normalizedCourse) || normalizedCourse.contains(recName))
        }

        Log.i(TAG, "searchLiveCourse('$courseName', '$classroom') -> matched=${matched?.id} (${matched?.subjName} / ${matched?.clroName})")
        Result.success(matched)
    }

    private fun normalizeForMatch(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.lowercase(Locale.ROOT)
            .replace(Regex("[\\s\\-_()（）\\[\\]【】·]"), "")
    }

    /**
     * 获取直播间的各机位播放列表 (playType: 1=WEBRTC, 2=FLV, 3=HLS)
     */
    suspend fun fetchLiveVideoInfos(courseId: Long): Result<List<LiveDeviceViewDto>> = withContext(Dispatchers.IO) {
        try {
            var token = ensureJwtToken()
            if (token.isNullOrBlank()) {
                return@withContext Result.failure(IOException("无法获取智慧录播登录令牌 (Jwt-Token)"))
            }

            val url = "$BASE_URL/v1/course_vod_videoinfos?courseId=$courseId&playType=2"
            var attempt = 0
            while (attempt < 2) {
                attempt++
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", MOBILE_UI_REFERER)
                    .header("Jwt-Token", token!!)
                    .get()
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.code == 401 && attempt == 1) {
                        Log.w(TAG, "course_vod_videoinfos returned 401, refreshing Jwt-Token and retrying...")
                        token = ensureJwtToken(forceRefresh = true)
                        if (token.isNullOrBlank()) {
                            return@withContext Result.failure(IOException("智慧录播登录凭证已失效 (HTTP 401)"))
                        }
                        return@use
                    }
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IOException("获取直播机位失败: HTTP ${resp.code}"))
                    }

                    val body = resp.body?.string().orEmpty()
                    val infoResp = LiveUsstJsonParser.parseVideoInfoResponse(body)
                    val data = infoResp.data
                    val serverErr = data?.errorMsg?.takeIf { it.isNotBlank() }
                        ?: infoResp?.errorMsg?.takeIf { it.isNotBlank() }
                    if (serverErr != null) {
                        return@withContext Result.failure(IOException(serverErr))
                    }
                    if (data?.lvcrLiveStatus == 0) {
                        return@withContext Result.failure(IOException("暂无权限观看或当前直播未开启"))
                    }

                    val views = data?.courseDeviceViewDtoList ?: emptyList()
                    val summary = views.joinToString { v ->
                        val parsedUri = v.chanNameMainPlayUrl?.let { runCatching { Uri.parse(it) }.getOrNull() }
                        "${v.deviViewNum}:${v.viewName}(status=${v.deviceStatus},url=${parsedUri?.scheme}://${parsedUri?.host})"
                    }
                    Log.i(TAG, "fetchLiveVideoInfos(courseId=$courseId) -> ${views.size} views: [$summary]")
                    return@withContext Result.success(views)
                }
            }
            Result.failure(IOException("获取直播机位失败: 鉴权重试后仍未通过"))
        } catch (e: Exception) {
            Log.e(TAG, "fetchLiveVideoInfos failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 机位智能降级选择算法
     * 1. 优先：机位 5 (PPT采集卡大屏)
     * 2. 无则降级：合成通道 (7) / 电影模式 (6) + 教师视角 (2/1)
     * 3. 严格过滤无投影的学生视角 (3/4)
     */
    fun selectOptimalCandidateViews(views: List<LiveDeviceViewDto>): List<LiveDeviceViewDto> {
        if (views.isEmpty()) return emptyList()

        // 1. 检查是否存在课件 PPT 采集卡
        val pptView = views.firstOrNull {
            it.deviViewNum == LiveCameraConstant.VIEW_PPT && !it.chanNameMainPlayUrl.isNullOrBlank()
        }
        if (pptView != null) {
            Log.i(TAG, "Found dedicated PPT stream (viewNum=5)")
            return listOf(pptView)
        }

        // 2. 无 PPT 采集卡时，按优先级降级选择备用机位
        val candidates = mutableListOf<LiveDeviceViewDto>()

        // 导播合成画面 (7 / 6)
        val compositeView = views.firstOrNull {
            (it.deviViewNum == LiveCameraConstant.VIEW_COMPOSITE || it.deviViewNum == LiveCameraConstant.VIEW_MOVIE) &&
                    !it.chanNameMainPlayUrl.isNullOrBlank()
        }
        compositeView?.let { candidates.add(it) }

        // 教师特写或全景机位 (2 / 1)
        val teacherView = views.firstOrNull {
            (it.deviViewNum == LiveCameraConstant.VIEW_TEACHER_2 || it.deviViewNum == LiveCameraConstant.VIEW_TEACHER_1) &&
                    !it.chanNameMainPlayUrl.isNullOrBlank()
        }
        teacherView?.let { candidates.add(it) }

        Log.i(TAG, "No PPT stream found, fallback to candidate views: ${candidates.map { it.viewName }}")
        return candidates
    }
}
