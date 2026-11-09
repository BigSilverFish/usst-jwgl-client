package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import cn.edu.usst.jwgl.data.model.LiveCameraConstant
import cn.edu.usst.jwgl.data.model.LiveCourseRecord
import cn.edu.usst.jwgl.data.model.LiveDeviceViewDto
import cn.edu.usst.jwgl.data.model.LiveUnionSearchResponse
import cn.edu.usst.jwgl.data.model.LiveVideoInfoResponse
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 智慧录播系统 (live.usst.edu.cn) 客户端
 * 提供直播间元数据检索、FLV低延迟机位直连与机位降级匹配
 */
class LiveUsstClient(private val context: Context) {

    companion object {
        private const val TAG = "LiveUsstClient"
        const val BASE_URL = "https://live.usst.edu.cn/jy-application-resourcemanage"
        private const val AUTHORIZE_URL =
            "https://live.usst.edu.cn/jy-application-resourcemanage/oauth2/authorize?json=0&returnUri=https%3A%2F%2Flive.usst.edu.cn%2Fjy-mobile-resourcemanage-ui%2F%23%2FliveList%3Ftype%3Dcas"

        @Volatile
        private var cachedJwtToken: String? = null
        @Volatile
        private var tokenExpireTimeMillis: Long = 0L
    }

    private val gson = Gson()

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    val cookieManager = CookieManager.getInstance()
                    cookies.forEach { cookie ->
                        cookieManager.setCookie(url.toString(), cookie.toString())
                    }
                    cookieManager.flush()
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val cookieManager = CookieManager.getInstance()
                    val cookieStr = cookieManager.getCookie(url.toString()) ?: ""
                    if (cookieStr.isBlank()) return emptyList()
                    val result = mutableListOf<Cookie>()
                    cookieStr.split(";").forEach { item ->
                        val parts = item.trim().split("=", limit = 2)
                        if (parts.size == 2) {
                            Cookie.Builder()
                                .domain(url.host)
                                .path("/")
                                .name(parts[0].trim())
                                .value(parts[1].trim())
                                .build()
                                .let { result.add(it) }
                        }
                    }
                    return result
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

    /**
     * 获取有效的 Jwt-Token
     */
    suspend fun ensureJwtToken(): String? = withContext(Dispatchers.IO) {
        if (!cachedJwtToken.isNullOrBlank() && System.currentTimeMillis() < tokenExpireTimeMillis) {
            return@withContext cachedJwtToken
        }

        try {
            // 利用 CookieJar 请求授权重定向链，抓取重定向 URL 里的 jwt_token
            val clientNoRedirect = okHttpClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()

            var currentUrl = AUTHORIZE_URL
            var hops = 0
            while (hops < 6) {
                hops++
                val req = Request.Builder()
                    .url(currentUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")
                    .build()

                val resp = clientNoRedirect.newCall(req).execute()
                val code = resp.code
                val location = resp.header("Location")
                resp.close()

                if (location.isNullOrBlank()) {
                    break
                }

                val resolvedUrl = resp.request.url.resolve(location)?.toString() ?: location
                // 检查 location 是否带 jwt_token
                val tokenFromUrl = extractTokenFromUrl(resolvedUrl)
                if (!tokenFromUrl.isNullOrBlank()) {
                    setJwtToken(tokenFromUrl)
                    Log.i(TAG, "Successfully extracted jwt_token from redirect url: ${tokenFromUrl.take(10)}...")
                    return@withContext tokenFromUrl
                }

                currentUrl = resolvedUrl
                if (code !in 300..399) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve jwt_token automatically: ${e.message}")
        }

        cachedJwtToken
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

    /**
     * 查询指定课程当前正在直播的记录
     */
    suspend fun searchLiveCourse(
        courseName: String,
        classroom: String? = null
    ): Result<LiveCourseRecord?> = withContext(Dispatchers.IO) {
        try {
            val token = ensureJwtToken()
            val urlBuilder = (BASE_URL + "/mobile/union/vod_live_new").let { Uri.parse(it).buildUpon() }
                .appendQueryParameter("courStatus", "0") // 0 代表直播
                .appendQueryParameter("unionName", courseName.trim())
                .appendQueryParameter("page.pageIndex", "1")
                .appendQueryParameter("page.pageSize", "10")
                .appendQueryParameter("page.orders[0].asc", "true")
                .appendQueryParameter("page.orders[0].field", "courBeginTime")

            val reqBuilder = Request.Builder()
                .url(urlBuilder.build().toString())
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")

            if (!token.isNullOrBlank()) {
                reqBuilder.header("Jwt-Token", token)
            }

            val resp = okHttpClient.newCall(reqBuilder.build()).execute()
            if (!resp.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP error code: ${resp.code}"))
            }

            val body = resp.body?.string() ?: ""
            val searchResp = gson.fromJson(body, LiveUnionSearchResponse::class.java)
            val records = searchResp.data?.records ?: emptyList()

            if (records.isEmpty()) {
                Log.d(TAG, "No live records found for course: $courseName")
                return@withContext Result.success(null)
            }

            // 智能筛选：优先匹配正在直播中且教室相符的记录
            val matched = records.firstOrNull { record ->
                val nameMatch = record.subjName?.contains(courseName) == true || courseName.contains(record.subjName ?: "")
                val roomMatch = classroom.isNullOrBlank() || record.clroName?.contains(classroom) == true || classroom.contains(record.clroName ?: "")
                nameMatch && roomMatch && record.isCurrentlyLive()
            } ?: records.firstOrNull { it.isCurrentlyLive() } ?: records.firstOrNull()

            Result.success(matched)
        } catch (e: Exception) {
            Log.e(TAG, "searchLiveCourse failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * 获取直播间的各机位播放列表 (playType = flv)
     */
    suspend fun fetchLiveVideoInfos(courseId: Long): Result<List<LiveDeviceViewDto>> = withContext(Dispatchers.IO) {
        try {
            val token = ensureJwtToken()
            val url = "$BASE_URL/v1/course_vod_videoinfos?courseId=$courseId&playType=flv"

            val reqBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile)")

            if (!token.isNullOrBlank()) {
                reqBuilder.header("Jwt-Token", token)
            }

            val resp = okHttpClient.newCall(reqBuilder.build()).execute()
            if (!resp.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP error code: ${resp.code}"))
            }

            val body = resp.body?.string() ?: ""
            val infoResp = gson.fromJson(body, LiveVideoInfoResponse::class.java)
            val views = infoResp.data?.courseDeviceViewDtoList ?: emptyList()

            Result.success(views)
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
