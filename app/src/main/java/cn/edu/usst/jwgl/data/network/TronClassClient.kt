package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import java.util.Locale
import cn.edu.usst.jwgl.data.model.RollcallSubmitResult
import cn.edu.usst.jwgl.data.model.TronClassActivityItem
import cn.edu.usst.jwgl.data.model.TronClassBulletin
import cn.edu.usst.jwgl.data.model.TronClassCourse
import cn.edu.usst.jwgl.data.model.TronClassModule
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.data.model.*
import cn.edu.usst.jwgl.data.model.TronClassUpload
import cn.edu.usst.jwgl.data.model.TronClassSubmission
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class TronClassClient(private val context: Context) {

    private val prefs = TronClassPreferences(context)
    private val gson = Gson()

    companion object {
        private const val TAG = "TronClassClient"
        const val BASE_URL = "https://1906.usst.edu.cn"
        const val AUTH_SERVER_URL = "https://1906.usst.edu.cn:8600/auth"
        const val OAUTH_CLIENT_ID = "TronClassApp"
        const val OAUTH_REDIRECT_URI = "https://mobile-download.tronclass.com.cn/callback.html"

        fun getOAuthAuthUrl(): String {
            val encodedRedirect = java.net.URLEncoder.encode(OAUTH_REDIRECT_URI, "UTF-8")
            return "$AUTH_SERVER_URL/realms/usst/protocol/openid-connect/auth" +
                    "?client_id=$OAUTH_CLIENT_ID" +
                    "&response_type=code" +
                    "&scope=openid" +
                    "&redirect_uri=$encodedRedirect"
        }
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val orig = chain.request()
            val reqBuilder = orig.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 16; TronClass/2.16.1)")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept-Language", "zh-CN,zh;q=0.9")

            val sessionId = prefs.getSessionId()
            val host = orig.url.host.lowercase()
            val isHttps = orig.isHttps
            val isTrustedHost = host == "1906.usst.edu.cn" ||
                                host.endsWith(".usst.edu.cn") ||
                                host.endsWith(".tronclass.com.cn") ||
                                host == "tronclass.com.cn"

            if (isHttps && isTrustedHost && !sessionId.isNullOrBlank() && !orig.headers.names().contains("x-session-id")) {
                reqBuilder.header("x-session-id", sessionId)
            }

            chain.proceed(reqBuilder.build())
        }
        .build()

    fun isLoggedIn(): Boolean = prefs.isLoggedIn()

    fun getSessionId(): String? = prefs.getSessionId()

    fun getOkHttpClient(): OkHttpClient = okHttpClient

    fun getUserName(): String {
        val tcName = prefs.getUserName()
        if (!tcName.isNullOrBlank() && tcName != "同学") return tcName
        val globalProfile = cn.edu.usst.jwgl.data.local.DataCacheManager(context).getProfile()
        val gName = globalProfile?.name?.trim()
        if (!gName.isNullOrBlank()) {
            return gName
        }
        return "同学"
    }

    fun getUserNo(): String {
        val tcNo = prefs.getUserNo()
        if (!tcNo.isNullOrBlank()) return tcNo
        val globalProfile = cn.edu.usst.jwgl.data.local.DataCacheManager(context).getProfile()
        return globalProfile?.studentId ?: ""
    }

    /**
     * 1. 使用 OAuth Authorization Code 换取 Access Token 并完成登录
     */
    suspend fun loginWithAuthCode(authCode: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Exchanging auth code for tokens...")
            val tokenUrl = "$AUTH_SERVER_URL/realms/usst/protocol/openid-connect/token"
            val formBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", OAUTH_CLIENT_ID)
                .add("code", authCode)
                .add("redirect_uri", OAUTH_REDIRECT_URI)
                .build()

            val tokenReq = Request.Builder()
                .url(tokenUrl)
                .post(formBody)
                .header("Accept", "application/json")
                .build()

            val tokenResp = okHttpClient.newCall(tokenReq).execute()
            val tokenBody = tokenResp.body?.string() ?: ""
            if (!tokenResp.isSuccessful) {
                Log.e(TAG, "Token exchange failed: ${tokenResp.code} $tokenBody")
                return@withContext Result.failure(Exception("换取认证Token失败 (${tokenResp.code})"))
            }

            val tokenJson = JSONObject(tokenBody)
            val accessToken = tokenJson.optString("access_token")
            val refreshToken = tokenJson.optString("refresh_token")

            if (accessToken.isBlank()) {
                return@withContext Result.failure(Exception("认证Token为空"))
            }

            exchangeAccessTokenForSession(accessToken, refreshToken)
        } catch (e: Exception) {
            Log.e(TAG, "loginWithAuthCode failed", e)
            Result.failure(e)
        }
    }

    /**
     * 2. 将 Access Token 提交至 TronClass 换取真正的 sessionID
     */
    suspend fun exchangeAccessTokenForSession(accessToken: String, refreshToken: String? = null): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Exchanging access token for TronClass session...")
                val loginUrl = "$BASE_URL/api/login?login=access_token"
                val payload = JSONObject().apply {
                    put("access_token", accessToken)
                    put("org_id", 1)
                }

                val req = Request.Builder()
                    .url(loginUrl)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("Accept", "application/json")
                    .build()

                val resp = okHttpClient.newCall(req).execute()
                val respBody = resp.body?.string() ?: ""

                if (!resp.isSuccessful) {
                    Log.e(TAG, "Session exchange failed: ${resp.code} $respBody")
                    return@withContext Result.failure(Exception("登录畅课失败 (${resp.code})"))
                }

                // 提取 sessionID
                var sessionId = resp.header("x-session-id")
                if (sessionId.isNullOrBlank()) {
                    for (header in resp.headers("Set-Cookie")) {
                        if (header.startsWith("session_id=")) {
                            sessionId = header.substringAfter("session_id=").substringBefore(";")
                            break
                        }
                    }
                }

                val json = JSONObject(respBody)
                Log.d(TAG, "Session exchange response: $respBody")
                if (sessionId.isNullOrBlank()) {
                    sessionId = json.optString("session_id", "")
                }

                if (sessionId.isBlank()) {
                    return@withContext Result.failure(Exception("未获取到有效 SessionID"))
                }

                // 解析用户信息
                val userObj = json.optJSONObject("user")
                val userId = json.optLong("user_id", json.optLong("id", userObj?.optLong("id", 0L) ?: 0L))
                val userName = if (json.has("name")) json.optString("name", "") else (userObj?.optString("name", "") ?: "")
                var userNo = if (json.has("user_no")) json.optString("user_no", "") else (userObj?.optString("user_no", "") ?: "")
                val userEmail = if (json.has("email")) json.optString("email", "") else (userObj?.optString("email", "") ?: "")

                if (userNo.isBlank()) {
                    userNo = AuthPreferences(context).getStudentId().trim()
                }

                // 若基础信息不完整，尝试从 /api/users/me 获取详情补充
                var finalUserId = userId
                var finalUserName = userName
                var finalUserNo = userNo
                var finalUserEmail = userEmail
                if (finalUserId == 0L || finalUserName.isBlank()) {
                    try {
                        val meReq = Request.Builder()
                            .url("$BASE_URL/api/users/me")
                            .header("Accept", "application/json")
                            .header("x-session-id", sessionId)
                            .header("Cookie", "session_id=$sessionId")
                            .build()
                        val meResp = okHttpClient.newCall(meReq).execute()
                        if (meResp.isSuccessful) {
                            val meJson = JSONObject(meResp.body?.string() ?: "")
                            if (finalUserId == 0L) finalUserId = meJson.optLong("id", 0L)
                            if (finalUserName.isBlank()) finalUserName = meJson.optString("name", "")
                            if (finalUserNo.isBlank()) finalUserNo = meJson.optString("user_no", "")
                            if (finalUserEmail.isBlank()) finalUserEmail = meJson.optString("email", "")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch user profile via /users/me", e)
                    }
                }

                prefs.saveSession(
                    sessionId = sessionId,
                    refreshToken = refreshToken,
                    userId = finalUserId,
                    userName = if (finalUserName.isNotBlank()) finalUserName else null,
                    userNo = if (finalUserNo.isNotBlank()) finalUserNo else null,
                    userEmail = if (finalUserEmail.isNotBlank()) finalUserEmail else null
                )

                Log.d(TAG, "TronClass session established successfully! User: $finalUserName ($finalUserNo)")
                Result.success(true)
            } catch (e: Exception) {
                Log.e(TAG, "exchangeAccessTokenForSession failed", e)
                Result.failure(e)
            }
        }

    /**
     * 3. 使用 RefreshToken 自动刷新 Session
     */
    suspend fun refreshSession(): Result<Boolean> = withContext(Dispatchers.IO) {
        val refreshToken = prefs.getRefreshToken()
        if (refreshToken.isNullOrBlank()) {
            return@withContext Result.failure(Exception("无有效刷新凭证，请重新登录"))
        }

        try {
            val tokenUrl = "$AUTH_SERVER_URL/realms/usst/protocol/openid-connect/token"
            val formBody = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", OAUTH_CLIENT_ID)
                .add("refresh_token", refreshToken)
                .build()

            val tokenReq = Request.Builder()
                .url(tokenUrl)
                .post(formBody)
                .header("Accept", "application/json")
                .build()

            val tokenResp = okHttpClient.newCall(tokenReq).execute()
            val tokenBody = tokenResp.body?.string() ?: ""
            if (!tokenResp.isSuccessful) {
                Log.w(TAG, "Refresh token failed: ${tokenResp.code} $tokenBody")
                return@withContext Result.failure(Exception("凭证刷新失败"))
            }

            val tokenJson = JSONObject(tokenBody)
            val newAccessToken = tokenJson.optString("access_token")
            val newRefreshToken = tokenJson.optString("refresh_token", refreshToken)

            exchangeAccessTokenForSession(newAccessToken, newRefreshToken)
        } catch (e: Exception) {
            Log.e(TAG, "refreshSession failed", e)
            Result.failure(e)
        }
    }

    /**
     * 4. 获取待办作业与测验列表 (/api/todos)
     */
    suspend fun fetchTodos(): Result<List<TronClassTodo>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/todos"
            val req = Request.Builder().url(url).get().build()
            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (resp.code == 401 || resp.code == 403) {
                // 尝试刷新 Token
                val refreshed = refreshSession().getOrDefault(false)
                if (refreshed) {
                    return@withContext fetchTodos()
                }
                return@withContext Result.failure(Exception("登录已失效，请重新登录"))
            }

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("获取待办列表失败 (${resp.code})"))
            }

            val json = JSONObject(body)
            val todoArray = json.optJSONArray("todo_list")
            val list = mutableListOf<TronClassTodo>()

            if (todoArray != null) {
                for (i in 0 until todoArray.length()) {
                    val item = todoArray.getJSONObject(i)
                    val todo = TronClassTodo(
                        id = item.optLong("id"),
                        title = item.optString("title", ""),
                        courseId = item.optLong("course_id"),
                        courseName = item.optString("course_name", ""),
                        courseCode = item.optString("course_code", null),
                        courseType = item.optInt("course_type", 1),
                        endTime = item.optString("end_time", null),
                        type = item.optString("type", "homework"),
                        isLocked = item.optBoolean("is_locked", false),
                        isStudent = item.optBoolean("is_student", true)
                    )
                    list.add(todo)
                }
            }

            // 按截止时间排序 (截止日期最近的排在最前)
            list.sortBy { it.getDeadlineDate()?.time ?: Long.MAX_VALUE }

            prefs.saveCachedTodos(list)
            prefs.setLastSyncTime()
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "fetchTodos error", e)
            val cached = prefs.getCachedTodos()
            if (cached.isNotEmpty()) {
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }

    /**
     * 6. 获取当前学期在读课程 (/api/my-courses)
     */
    suspend fun fetchMyCourses(): Result<List<TronClassCourse>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/my-courses"
            val req = Request.Builder().url(url).get().build()
            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("获取课程列表失败 (${resp.code})"))
            }

            val json = JSONObject(body)
            val courseArray = json.optJSONArray("courses")
            val list = mutableListOf<TronClassCourse>()

            if (courseArray != null) {
                for (i in 0 until courseArray.length()) {
                    val item = courseArray.getJSONObject(i)
                    val deptName = item.optJSONObject("department")?.optString("name", null)
                    val yearName = item.optJSONObject("academic_year")?.optString("name", null)

                    val instructorsList = mutableListOf<String>()
                    val instArray = item.optJSONArray("instructors") ?: item.optJSONArray("teachers")
                    if (instArray != null) {
                        for (j in 0 until instArray.length()) {
                            val iObj = instArray.optJSONObject(j)
                            val name = iObj?.optString("name") ?: instArray.optString(j)
                            if (!name.isNullOrBlank()) instructorsList.add(name.trim())
                        }
                    } else if (item.has("instructor_name")) {
                        val name = item.optString("instructor_name")
                        if (name.isNotBlank()) instructorsList.add(name.trim())
                    }

                    val course = TronClassCourse(
                        id = item.optLong("id"),
                        name = item.optString("name", ""),
                        courseCode = item.optString("course_code", null),
                        cover = item.optString("cover", null),
                        department = if (deptName != null) TronClassCourse.DepartmentInfo(deptName) else null,
                        academicYear = if (yearName != null) TronClassCourse.AcademicYearInfo(yearName) else null,
                        instructors = instructorsList,
                        teacher = instructorsList.firstOrNull()
                    )
                    list.add(course)
                }
            }

            prefs.saveCachedCourses(list)
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "fetchMyCourses error", e)
            val cached = prefs.getCachedCourses()
            if (cached.isNotEmpty()) {
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }

    /**
     * 7. 轮询是否有正在进行的课堂签到 (/api/radar/rollcalls)
     */
    suspend fun fetchOngoingRollcalls(): Result<List<TronClassRollcall>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/radar/rollcalls?api_version=1.1.0"
            val req = Request.Builder().url(url).get().build()
            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("查询签到状态失败 (${resp.code})"))
            }

            val json = JSONObject(body)
            val arr = json.optJSONArray("rollcalls")
            val list = mutableListOf<TronClassRollcall>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val isNumber = item.optBoolean("is_number", false)
                    val isRadar = item.optBoolean("is_radar", false)
                    val rawType = item.optString("type", item.optString("source", ""))
                    val resolvedType = when {
                        isNumber || rawType.contains("number") -> "number"
                        isRadar || rawType.contains("radar") || rawType.contains("roomis") -> "radar"
                        rawType == "self_registration" -> "self_registration"
                        rawType.contains("qr") -> "qr_code"
                        else -> "number"
                    }
                    val courseTitle = item.optString("course_title",
                        item.optString("courseName",
                            item.optString("course_name",
                                item.optString("title", null))))
                    val r = TronClassRollcall(
                        id = item.optLong("id", item.optLong("rollcallId", item.optLong("rollcall_id"))),
                        rollcallId = item.optLong("rollcallId", item.optLong("id", item.optLong("rollcall_id"))),
                        courseId = item.optLong("courseId", item.optLong("course_id")),
                        courseName = courseTitle,
                        type = resolvedType,
                        status = item.optString("status", item.optString("rollcall_status", null)),
                        source = item.optString("source", null),
                        isNumber = isNumber,
                        isRadar = isRadar
                    )
                    list.add(r)
                }
            }

            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "fetchOngoingRollcalls error", e)
            Result.failure(e)
        }
    }

    /**
     * 8. 提交二维码签到
     * PUT /api/rollcall/{rollcallId}/answer_qr_rollcall
     * Body: { "data": qrTokenData, "deviceId": deviceId }
     */
    suspend fun answerQRRollcall(rollcallId: Long, qrTokenData: String): Result<RollcallSubmitResult> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/api/rollcall/$rollcallId/answer_qr_rollcall"
                val payload = JSONObject().apply {
                    put("data", qrTokenData)
                    put("deviceId", prefs.getDeviceId())
                }

                val req = Request.Builder()
                    .url(url)
                    .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val resp = okHttpClient.newCall(req).execute()
                val body = resp.body?.string() ?: ""

                if (resp.isSuccessful) {
                    val json = JSONObject(body)
                    val status = json.optString("status", "on_call")
                    val statusName = json.optString("status_name", "已签到")
                    Result.success(RollcallSubmitResult(true, "签到成功！状态: $statusName", statusName))
                } else {
                    val errMsg = try {
                        JSONObject(body).optString("message", "签到失败 (${resp.code})")
                    } catch (e: Exception) {
                        "签到失败 (${resp.code})"
                    }
                    Result.success(RollcallSubmitResult(false, errMsg))
                }
            } catch (e: Exception) {
                Log.e(TAG, "answerQRRollcall error", e)
                Result.failure(e)
            }
        }

    /**
     * 9. 提交数字口令签到
     * PUT /api/rollcall/{rollcallId}/answer_number_rollcall
     * Body: { "numberCode": "1234", "deviceId": deviceId }
     */
    suspend fun answerNumberRollcall(rollcallId: Long, numberCode: String): Result<RollcallSubmitResult> =
        withContext(Dispatchers.IO) {
            try {
                var targetRollcallId = rollcallId
                if (targetRollcallId <= 0L) {
                    val ongoing = fetchOngoingRollcalls().getOrNull()
                    targetRollcallId = ongoing?.firstOrNull()?.getEffectiveId() ?: 0L
                }
                if (targetRollcallId <= 0L) {
                    return@withContext Result.failure(Exception("暂无正在签到的课程，请确认任课教师已在课堂开启签到"))
                }
                val url = "$BASE_URL/api/rollcall/$targetRollcallId/answer_number_rollcall"
                val payload = JSONObject().apply {
                    put("numberCode", numberCode.trim())
                    put("deviceId", prefs.getDeviceId())
                }

                val req = Request.Builder()
                    .url(url)
                    .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val resp = okHttpClient.newCall(req).execute()
                val body = resp.body?.string() ?: ""

                if (resp.isSuccessful) {
                    val json = JSONObject(body)
                    val statusName = json.optString("status_name", "已签到")
                    Result.success(RollcallSubmitResult(true, "口令签到成功！状态: $statusName", statusName))
                } else {
                    val errMsg = try {
                        JSONObject(body).optString("message", "口令错误或签到已结束 (${resp.code})")
                    } catch (e: Exception) {
                        "口令签到失败 (${resp.code})"
                    }
                    Result.success(RollcallSubmitResult(false, errMsg))
                }
            } catch (e: Exception) {
                Log.e(TAG, "answerNumberRollcall error", e)
                Result.failure(e)
            }
        }

    /**
     * 9.1 查询点名精简信息（检测是否启用了蓝牙信标等）
     * GET /api/rollcall/{rollcallId}/lite
     */
    suspend fun getRollcallInfoLite(rollcallId: Long): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/rollcall/$rollcallId/lite"
            val req = Request.Builder().url(url).get().build()
            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            if (resp.isSuccessful) {
                Result.success(JSONObject(body))
            } else {
                Result.failure(Exception("获取点名详情失败 (${resp.code})"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getRollcallInfoLite error", e)
            Result.failure(e)
        }
    }

    /**
     * 9.2 提交雷达点名（定位签到）
     * PUT /api/rollcall/{rollcallId}/answer?api_version=1.76
     * Body: { "deviceId": "...", "latitude": 31.xxx, "longitude": 121.xxx, "accuracy": 15.0, "altitude": 10.0, "radarSignal": null }
     */
    suspend fun answerRadarRollcall(
        rollcallId: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Double = 15.0
    ): Result<RollcallSubmitResult> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/rollcall/$rollcallId/answer?api_version=1.76"
            val payload = JSONObject().apply {
                put("deviceId", prefs.getDeviceId())
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", accuracy)
                put("altitude", 10.0)
                put("radarSignal", JSONObject.NULL)
            }

            Log.i(TAG, "Submitting radar rollcall: rollcallId=$rollcallId, lat=$latitude, lng=$longitude, devId=${prefs.getDeviceId()}")

            val req = Request.Builder()
                .url(url)
                .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            Log.i(TAG, "Radar rollcall response code=${resp.code}, body=$body")

            if (resp.isSuccessful) {
                val json = JSONObject(body)
                val statusName = json.optString("status_name", "已出勤")
                val distance = if (json.has("distance")) json.optDouble("distance") else null
                val distMsg = if (distance != null) "（距教师基准点约 ${distance.toInt()} 米）" else ""
                Result.success(RollcallSubmitResult(true, "雷达签到成功！状态: $statusName $distMsg", statusName, distance))
            } else {
                val jsonObj = try { JSONObject(body) } catch (e: Exception) { null }
                val errCode = jsonObj?.optString("errorCode", "") ?: ""
                val rawMsg = jsonObj?.optString("message", "") ?: ""

                val friendlyMsg = when {
                    errCode == "ROLLCALL_RADAR_OUT_OF_SCOPE" || rawMsg.contains("scope", ignoreCase = true) ->
                        "当前定位超出签到有效范围，请靠近上课教室或选择正确教学楼基准"
                    errCode == "RADAR_ROLLCALL_POSITION_INVALID" ->
                        "经纬度坐标为空或无效，请重新定位"
                    errCode == "ROLLCALL_DEVICE_ALREADY_IN_USE" ->
                        "该设备已为其他账号签过到，无法重复签到"
                    errCode == "RADAR_SIGNAL_INVALID_NO_SIGNAL" ->
                        "本堂课开启了蓝牙信标验证，需要教室蓝牙信号支持"
                    rawMsg.isNotBlank() -> rawMsg
                    resp.code == 404 -> "点名已结束或已关闭"
                    else -> "签到失败 (${resp.code})"
                }
                Result.success(RollcallSubmitResult(false, friendlyMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "answerRadarRollcall error", e)
            Result.failure(e)
        }
    }

    /**
     * 10. 解析扫码得到的二维码字符串
     * 深度对齐一网畅学官方协议：
     * 1. 短链格式: https://.../j?p=... 或 /scanner-jumper?p=... (以及 _p 参数)
     * 2. 官方 Base36 压缩算法 (4~rollcallId!3~data!0~courseId, 包含 \u0010 前缀)
     * 3. 标准 JSON 格式
     * 4. 任意带有 rollcallId/data 查询参数的链接
     */
    fun parseRollcallQr(rawQr: String): ParsedQrRollcall? {
        val trimmed = rawQr.trim()
        if (trimmed.isEmpty()) return null

        try {
            // 辅助函数：解码 Base36 数值或十进制数值（自动剥离 \u0010, \u001A 前缀）
            fun decodeBase36Number(raw: String): Long? {
                val clean = raw.trim()
                    .replace("", "")
                    .replace("", "")
                    .replace("", "")
                    .replace("%10", "")
                    .replace("%1a", "")
                    .replace("%1A", "")
                clean.toLongOrNull()?.let { return it }
                return try {
                    java.lang.Long.parseLong(clean, 36)
                } catch (e: Exception) {
                    null
                }
            }

            // 1. 如果是 URL，提取 p / _p 或直接参数
            var targetPayload = trimmed
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.contains("/j?p=") ||
                trimmed.contains("/scanner-jumper?")
            ) {
                try {
                    val uri = android.net.Uri.parse(trimmed)

                    // 优先检查 _p 参数 (直接是 JSON)
                    val pUnderscore = uri.getQueryParameter("_p")
                    if (!pUnderscore.isNullOrBlank()) {
                        targetPayload = pUnderscore
                    } else {
                        // 检查 p 参数 (Base36 紧凑编码)
                        val pParam = uri.getQueryParameter("p")
                        if (!pParam.isNullOrBlank()) {
                            targetPayload = pParam
                        } else {
                            // 检查常规查询参数
                            val idStr = uri.getQueryParameter("rollcall_id") ?: uri.getQueryParameter("rollcallId")
                            val dataStr = uri.getQueryParameter("data") ?: uri.getQueryParameter("qr_token")
                            val courseStr = uri.getQueryParameter("course_id") ?: uri.getQueryParameter("courseId")
                            val rId = idStr?.let { decodeBase36Number(it) }
                            if (rId != null && !dataStr.isNullOrBlank()) {
                                return ParsedQrRollcall(rId, dataStr, courseStr?.let { decodeBase36Number(it) })
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing URI: $trimmed", e)
                }
            }

            // 2. 检查是否为标准 JSON 格式 (例如通过 _p 传参或原生 JSON)
            if (targetPayload.startsWith("{") && targetPayload.endsWith("}")) {
                val obj = JSONObject(targetPayload)
                val idRaw = obj.optString("rollcallId", obj.optString("rollcall_id", ""))
                val rId = decodeBase36Number(idRaw) ?: obj.optLong("rollcallId", obj.optLong("rollcall_id", 0L))
                val data = obj.optString("data", obj.optString("qr_token", ""))
                val courseRaw = obj.optString("courseId", obj.optString("course_id", ""))
                val cId = decodeBase36Number(courseRaw) ?: obj.optLong("courseId", obj.optLong("course_id", 0L))

                if (rId > 0 && data.isNotBlank()) {
                    return ParsedQrRollcall(rId, data, if (cId > 0) cId else null)
                }
            }

            // 3. 官方 Base36 紧凑编码解析 (格式例如: "4~1xyz!3~token_data!0~course_id")
            if (targetPayload.contains("!") || targetPayload.contains("~")) {
                val keyMap = mapOf(
                    "0" to "courseId",
                    "1" to "activityId",
                    "2" to "activityType",
                    "3" to "data",
                    "4" to "rollcallId"
                )
                var rollcallId: Long? = null
                var qrData: String? = null
                var courseId: Long? = null

                val parts = targetPayload.split("!")
                for (part in parts) {
                    if (part.contains("~")) {
                        val kv = part.split("~", limit = 2)
                        val k = keyMap[kv[0]] ?: kv[0]
                        val v = kv[1]
                        when (k) {
                            "rollcallId" -> rollcallId = decodeBase36Number(v)
                            "data" -> qrData = v.replace(String(charArrayOf(30.toChar())), "~").replace(String(charArrayOf(31.toChar())), "!")
                            "courseId" -> courseId = decodeBase36Number(v)
                        }
                    }
                }

                if (rollcallId != null && !qrData.isNullOrBlank()) {
                    Log.i(TAG, "Successfully parsed TronClass QR: rollcallId=$rollcallId, courseId=$courseId, dataLen=${qrData.length}")
                    return ParsedQrRollcall(rollcallId, qrData, courseId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse rollcall QR string: $trimmed", e)
        }
        return null
    }

suspend fun fetchCourseModules(courseId: Long): Result<List<TronClassModule>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/courses/$courseId/modules"
            val req = Request.Builder().url(url).build()
            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                if (resp.code == 401 && refreshSession().isSuccess) {
                    return@withContext fetchCourseModules(courseId)
                }
                return@withContext Result.failure(Exception("è·åè¯¾ç¨ç« èå¤±è´¥: HTTP ${resp.code}"))
            }

            val json = JSONObject(body)
            val modulesArray = json.optJSONArray("modules") ?: org.json.JSONArray()
            val type = object : TypeToken<List<TronClassModule>>() {}.type
            val modules: List<TronClassModule> = gson.fromJson(modulesArray.toString(), type) ?: emptyList()
            Result.success(modules.sortedBy { it.sort })
        } catch (e: Exception) {
            Log.e(TAG, "fetchCourseModules error", e)
            Result.failure(e)
        }
    }

    /**
     * 12. è·åè¯¾ç¨ææå­¦ä¹ æ´»å¨/è¯¾ä»¶/ä½ä¸
     * GET /api/courses/{courseId}/activities
     */
    suspend fun fetchCourseActivities(courseId: Long): Result<List<TronClassActivityItem>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/courses/$courseId/activities"
            val req = Request.Builder().url(url).build()
            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                if (resp.code == 401 && refreshSession().isSuccess) {
                    return@withContext fetchCourseActivities(courseId)
                }
                return@withContext Result.failure(Exception("è·åè¯¾ç¨æ´»å¨å¤±è´¥: HTTP ${resp.code}"))
            }

            val json = JSONObject(body)
            val actArray = json.optJSONArray("activities") ?: org.json.JSONArray()
            val type = object : TypeToken<List<TronClassActivityItem>>() {}.type
            val items: List<TronClassActivityItem> = gson.fromJson(actArray.toString(), type) ?: emptyList()
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "fetchCourseActivities error", e)
            Result.failure(e)
        }
    }

    /**
     * 13. è·åè¯¾ç¨å¬ååè¡¨
     * GET /api/courses/{courseId}/bulletins
     */
    suspend fun fetchCourseBulletins(courseId: Long): Result<List<TronClassBulletin>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/courses/$courseId/bulletins"
            val req = Request.Builder().url(url).build()
            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                if (resp.code == 401 && refreshSession().isSuccess) {
                    return@withContext fetchCourseBulletins(courseId)
                }
                return@withContext Result.failure(Exception("è·åè¯¾ç¨å¬åå¤±è´¥: HTTP ${resp.code}"))
            }

            val json = JSONObject(body)
            val bullArray = json.optJSONArray("bulletins") ?: org.json.JSONArray()
            val type = object : TypeToken<List<TronClassBulletin>>() {}.type
            val items: List<TronClassBulletin> = gson.fromJson(bullArray.toString(), type) ?: emptyList()
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "fetchCourseBulletins error", e)
            Result.failure(e)
        }
    }

    /**
     * 14. è·åæ´»å¨è¯¦æ (åå«ä½ä¸è¯´æå¯ææ¬åè¯åè§å)
     * GET /api/activities/{activityId}
     */
    suspend fun fetchActivityDetail(activityId: Long): Result<TronClassActivityItem> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/activities/$activityId"
            val req = Request.Builder().url(url).build()
            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("è·åæ´»å¨è¯¦æå¤±è´¥: HTTP ${resp.code}"))
            }

            val item: TronClassActivityItem = gson.fromJson(body, TronClassActivityItem::class.java)
            Result.success(item)
        } catch (e: Exception) {
            Log.e(TAG, "fetchActivityDetail error", e)
            Result.failure(e)
        }
    }

    /**
     * 15. ä¸è½½è¯¾ä»¶/éä»¶å¹¶æä¾å®æ¶è¿åº¦åè°
     * GET /api/uploads/{uploadId}/blob
     */
    suspend fun downloadAttachment(
        uploadId: Long,
        destFile: File,
        onProgress: ((Int) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/uploads/$uploadId/blob"
            val req = Request.Builder().url(url).build()
            val resp = okHttpClient.newCall(req).execute()

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("ä¸è½½è¯¾ä»¶å¤±è´¥: HTTP ${resp.code}"))
            }

            val body = resp.body ?: return@withContext Result.failure(Exception("ååºä½ä¸ºç©º"))
            val totalBytes = body.contentLength()

            destFile.parentFile?.mkdirs()
            destFile.outputStream().use { fos ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var currentDownloaded: Long = 0
                    var lastPercent = -1

                    while (input.read(buffer).also { read = it } != -1) {
                        fos.write(buffer, 0, read)
                        currentDownloaded += read
                        if (totalBytes > 0) {
                            val percent = ((currentDownloaded * 100) / totalBytes).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress?.invoke(percent)
                            }
                        }
                    }
                    fos.flush()
                }
            }
            onProgress?.invoke(100)
            Result.success(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "downloadAttachment error", e)
            Result.failure(e)
        }
    }



suspend fun fetchSubmissionDetail(
        activityId: Long,
        userId: Long = getUserIdFromSession()
    ): Result<TronClassSubmission> = withContext(Dispatchers.IO) {
        try {
            val effectiveUserId = if (userId > 0L) userId else getUserIdFromSession()
            val url = "$BASE_URL/api/course/activities/$activityId/students/$effectiveUserId/submission"
            val req = Request.Builder().url(url).build()
            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                if (resp.code == 401 && refreshSession().isSuccess) {
                    return@withContext fetchSubmissionDetail(activityId, userId)
                }
                return@withContext Result.failure(Exception("è·åä½ä¸æäº¤è¯¦æå¤±è´¥: HTTP ${resp.code}"))
            }

            val sub: TronClassSubmission = gson.fromJson(body, TronClassSubmission::class.java)
            Result.success(sub)
        } catch (e: Exception) {
            Log.e(TAG, "fetchSubmissionDetail error", e)
            Result.failure(e)
        }
    }

    /**
     * 17. ä¸ä¼ ä½ä¸éä»¶æä»¶
     * æµç¨ï¼
     * 1) POST /api/uploads åå§åä¸ä¼ ï¼è·å upload_url å upload_id
     * 2) POST Multipart å° upload_url (å¸¦ x-session-id)
     */
    suspend fun uploadFile(
        file: File,
        fileName: String = file.name,
        parentType: String = "activity",
        onProgress: ((Int) -> Unit)? = null
    ): Result<TronClassUpload> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() == 0L) {
                return@withContext Result.failure(Exception("å¾ä¸ä¼ æä»¶ä¸å­å¨æåå®¹ä¸ºç©º"))
            }

            // Step 1: POST /api/uploads
            val initUrl = "$BASE_URL/api/uploads"
            val initPayload = JSONObject().apply {
                put("name", fileName)
                put("size", file.length())
                put("parent_type", parentType)
            }
            val initReq = Request.Builder()
                .url(initUrl)
                .post(initPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val initResp = okHttpClient.newCall(initReq).execute()
            val initBody = initResp.body?.string() ?: ""

            if (!initResp.isSuccessful) {
                if (initResp.code == 401 && refreshSession().isSuccess) {
                    return@withContext uploadFile(file, fileName, parentType, onProgress)
                }
                return@withContext Result.failure(Exception("åå»ºæä»¶ä¸ä¼ è®°å½å¤±è´¥: HTTP ${initResp.code}"))
            }

            val initJson = JSONObject(initBody)
            val uploadId = initJson.optLong("id")
            val uploadUrl = initJson.optString("upload_url", initJson.optString("url", ""))

            if (uploadId <= 0L || uploadUrl.isBlank()) {
                return@withContext Result.failure(Exception("æå¡å¨æªä¸åææä¸ä¼ å°å"))
            }

            Log.d(TAG, "Upload initialized: id=$uploadId, url=$uploadUrl")

            // Step 2: Multipart POST to uploadUrl
            val sessionId = prefs.getSessionId() ?: ""
            val progressBody = object : RequestBody() {
                override fun contentType(): okhttp3.MediaType? = "application/octet-stream".toMediaType()
                override fun contentLength(): Long = file.length()
                override fun writeTo(sink: BufferedSink) {
                    val total = file.length()
                    file.source().use { src ->
                        var written = 0L
                        var read: Long
                        val buf = okio.Buffer()
                        var lastPercent = -1
                        while (src.read(buf, 8192L).also { read = it } != -1L) {
                            sink.write(buf, read)
                            written += read
                            if (total > 0L) {
                                val p = ((written * 100) / total).toInt()
                                if (p != lastPercent) {
                                    lastPercent = p
                                    onProgress?.invoke(p)
                                }
                            }
                        }
                    }
                }
            }

            val multiBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", fileName, progressBody)
                .build()

            val upReq = Request.Builder()
                .url(uploadUrl)
                .header("x-session-id", sessionId)
                .post(multiBody)
                .build()

            val upResp = okHttpClient.newCall(upReq).execute()
            if (!upResp.isSuccessful) {
                return@withContext Result.failure(Exception("æä»¶äºè¿å¶ä¼ è¾å¤±è´¥: HTTP ${upResp.code}"))
            }

            onProgress?.invoke(100)
            val uploadItem = TronClassUpload(
                id = uploadId,
                name = fileName,
                size = file.length(),
                type = "document"
            )
            Log.d(TAG, "File uploaded successfully: $uploadItem")
            Result.success(uploadItem)
        } catch (e: Exception) {
            Log.e(TAG, "uploadFile error", e)
            Result.failure(e)
        }
    }

    /**
     * 18. æäº¤ä½ä¸ / éæ°æäº¤ä½ä¸
     * POST /api/course/activities/{activityId}/submissions
     * è¥éæ°æäº¤ï¼PUT /api/course/activities/{activityId}/submissions?retract=true
     */
    suspend fun submitHomework(
        activityId: Long,
        comment: String,
        uploads: List<TronClassUpload>,
        isResubmit: Boolean = false,
        submissionId: Long? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (isResubmit) {
                // 1. 若为重新提交，先自动执行撤回 (retract)，使作业恢复至可提交状态
                val retractUrl = "$BASE_URL/api/course/activities/$activityId/submissions?retract=true"
                val retractPayload = JSONObject().apply {
                    put("comment", comment)
                    put("is_draft", false)
                    val upArr = org.json.JSONArray()
                    for (u in uploads) {
                        val item = JSONObject().apply {
                            put("id", u.id)
                            put("name", u.name)
                        }
                        upArr.put(item)
                    }
                    put("uploads", upArr)
                    if (submissionId != null && submissionId > 0) {
                        put("submission_id", submissionId)
                    }
                }
                val retractReq = Request.Builder()
                    .url(retractUrl)
                    .put(retractPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                val retractResp = okHttpClient.newCall(retractReq).execute()
                Log.d(TAG, "submitHomework retract response code: ${retractResp.code}")
            }

            // 2. 发起正式提交 (POST /api/course/activities/{activityId}/submissions)
            val submitUrl = "$BASE_URL/api/course/activities/$activityId/submissions"
            val payload = JSONObject().apply {
                put("comment", comment)
                put("is_draft", false)
                val upArr = org.json.JSONArray()
                for (u in uploads) {
                    val item = JSONObject().apply {
                        put("id", u.id)
                        put("name", u.name)
                    }
                    upArr.put(item)
                }
                put("uploads", upArr)
            }

            val reqBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url(submitUrl).post(reqBody).build()

            val resp = okHttpClient.newCall(req).execute()
            val code = resp.code
            val body = resp.body?.string() ?: ""

            if (code == 200 || code == 201) {
                Log.d(TAG, "submitHomework success: HTTP $code")
                Result.success(true)
            } else {
                if (code == 401 && refreshSession().isSuccess) {
                    return@withContext submitHomework(activityId, comment, uploads, false, submissionId)
                }
                Result.failure(Exception("提交作业失败: HTTP $code $body"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "submitHomework error", e)
            Result.failure(e)
        }
    }


    data class ParsedQrRollcall(
        val rollcallId: Long,
        val qrData: String,
        val courseId: Long? = null
    )

    fun getUserIdFromSession(): Long {
        val prefId = prefs.getUserId()
        if (prefId > 0L) return prefId
        return 0L
    }

    /**
     * 获取课程成员名单（教师、助教、学生）
     */
    suspend fun fetchCourseMembers(courseId: Long): Result<List<TronClassMember>> = withContext(Dispatchers.IO) {
        try {
            val members = mutableListOf<TronClassMember>()
            val seenUserIds = mutableSetOf<Long>()

            // 1. 获取选课与教职名单 (/enrollments)
            val enrollUrl = "$BASE_URL/api/course/$courseId/enrollments"
            val enrollReq = Request.Builder().url(enrollUrl).build()
            val enrollResp = okHttpClient.newCall(enrollReq).execute()
            if (enrollResp.isSuccessful) {
                val enrollBody = enrollResp.body?.string() ?: ""
                val enrollJson = JSONObject(enrollBody)
                val enrollArray = enrollJson.optJSONArray("enrollments")
                if (enrollArray != null) {
                    for (i in 0 until enrollArray.length()) {
                        val item = enrollArray.getJSONObject(i)
                        val u = item.optJSONObject("user") ?: continue
                        val uid = u.optLong("id")
                        val name = u.optString("name", "未知")
                        val userNo = u.optString("user_no").takeIf { it.isNotBlank() }
                        val roles = item.optJSONArray("roles")
                        var role = "student"
                        var roleName = "学生"
                        if (roles != null) {
                            for (r in 0 until roles.length()) {
                                val ro = roles.optString(r)
                                if (ro == "instructor") {
                                    role = "instructor"
                                    roleName = "任课教师"
                                    break
                                } else if (ro == "ta") {
                                    role = "ta"
                                    roleName = "助教"
                                    break
                                }
                            }
                        }
                        seenUserIds.add(uid)
                        members.add(
                            TronClassMember(
                                id = uid,
                                name = name,
                                userNo = userNo,
                                role = role,
                                roleName = roleName
                            )
                        )
                    }
                }
            }

            // 2. 获取完整学生名单 (/students)，补充班级和学院信息
            val stuUrl = "$BASE_URL/api/course/$courseId/students"
            val stuReq = Request.Builder().url(stuUrl).build()
            val stuResp = okHttpClient.newCall(stuReq).execute()
            if (stuResp.isSuccessful) {
                val stuBody = stuResp.body?.string() ?: ""
                val stuJson = JSONObject(stuBody)
                val stuArray = stuJson.optJSONArray("students")
                if (stuArray != null) {
                    for (i in 0 until stuArray.length()) {
                        val s = stuArray.getJSONObject(i)
                        val uid = s.optLong("id")
                        val name = s.optString("name", "未知")
                        val userNo = s.optString("user_no").takeIf { it.isNotBlank() }
                        val dept = s.optJSONObject("department")?.optString("name")
                        val klass = s.optJSONObject("klass")?.optString("name")
                        val avatar = s.optString("avatar_small_url").takeIf { it.isNotBlank() }

                        val existingIdx = members.indexOfFirst { it.id == uid }
                        if (existingIdx >= 0) {
                            val cur = members[existingIdx]
                            members[existingIdx] = cur.copy(
                                department = dept ?: cur.department,
                                klass = klass ?: cur.klass,
                                avatarUrl = avatar ?: cur.avatarUrl
                            )
                        } else {
                            seenUserIds.add(uid)
                            members.add(
                                TronClassMember(
                                    id = uid,
                                    name = name,
                                    userNo = userNo,
                                    role = "student",
                                    roleName = "学生",
                                    department = dept,
                                    klass = klass,
                                    avatarUrl = avatar
                                )
                            )
                        }
                    }
                }
            }

            // 排序：教师排最前，其次助教，最后学生（按学号正序）
            members.sortWith(compareBy({
                when (it.role) {
                    "instructor" -> 0
                    "ta" -> 1
                    else -> 2
                }
            }, { it.userNo ?: "" }))

            Result.success(members)
        } catch (e: Exception) {
            Log.e(TAG, "fetchCourseMembers failed", e)
            Result.failure(e)
        }
    }

    /**
     * 获取用户在课程中的学习进度 (Activity Reads)
     */
    suspend fun fetchActivityReads(courseId: Long): Result<Map<Long, String>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/course/$courseId/activity-reads-for-user"
            val req = Request.Builder().url(url).build()
            val resp = okHttpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("获取学习进度失败: ${resp.code}"))
            }
            val body = resp.body?.string() ?: ""
            val json = JSONObject(body)
            val readsArray = json.optJSONArray("activity_reads")
            val map = mutableMapOf<Long, String>()
            if (readsArray != null) {
                for (i in 0 until readsArray.length()) {
                    val item = readsArray.getJSONObject(i)
                    val actId = item.optLong("activity_id")
                    val comp = item.optString("completeness", "complete")
                    if (actId > 0) {
                        map[actId] = comp
                    }
                }
            }
            Result.success(map)
        } catch (e: Exception) {
            Log.e(TAG, "fetchActivityReads failed", e)
            Result.failure(e)
        }
    }

    /**
     * 标记活动已读 / 已完成 (同步上报学习进度至一网畅学服务器)
     */
    suspend fun logActivityRead(activityId: Long, uploadId: Long = 0L): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/course/activities-read/$activityId"
            val json = JSONObject().apply {
                if (uploadId > 0) {
                    put("upload_id", uploadId)
                }
            }
            val req = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = okHttpClient.newCall(req).execute()
            val ok = resp.isSuccessful || resp.code == 201
            resp.close()
            Result.success(ok)
        } catch (e: Exception) {
            Log.e(TAG, "logActivityRead failed", e)
            Result.failure(e)
        }
    }

    /**
     * 针对视频活动分段并发/串行上报观看进度，确保达到 100% 观看判定 (completeness: "full")
     * TronClass 服务器单次上报时长限制为 120s
     */
    suspend fun reportVideoProgress(
        activityId: Long,
        durationSeconds: Double,
        uploadId: Long = 0L,
        onProgress: ((currentSec: Int, totalSec: Int) -> Unit)? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val totalSec = if (durationSeconds <= 0.0) 300 else durationSeconds.toInt()
            val chunkSize = 120
            var start = 0
            while (start < totalSec) {
                val end = (start + chunkSize).coerceAtMost(totalSec)
                val json = JSONObject().apply {
                    put("start", start)
                    put("end", end)
                    put("duration", totalSec)
                    if (uploadId > 0) {
                        put("upload_id", uploadId)
                    }
                }
                val url = "$BASE_URL/api/course/activities-read/$activityId"
                val req = Request.Builder()
                    .url(url)
                    .post(json.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val resp = okHttpClient.newCall(req).execute()
                resp.close()
                start = end
                onProgress?.invoke(start, totalSec)
                if (start < totalSec) {
                    kotlinx.coroutines.delay(80)
                }
            }
            // 最终 100% 确认上报
            val finalJson = JSONObject().apply {
                put("start", 0)
                put("end", totalSec)
                put("duration", totalSec)
                if (uploadId > 0) {
                    put("upload_id", uploadId)
                }
            }
            val finalReq = Request.Builder()
                .url("$BASE_URL/api/course/activities-read/$activityId")
                .post(finalJson.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val finalResp = okHttpClient.newCall(finalReq).execute()
            finalResp.close()
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "reportVideoProgress failed for act $activityId", e)
            Result.failure(e)
        }
    }

    /**
     * 获取课程测验与考试列表
     */
    suspend fun fetchCourseExams(courseId: Long): Result<List<TronClassExam>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/courses/$courseId/exams"
            val req = Request.Builder().url(url).build()
            val resp = okHttpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                val cached = TronClassDataCacheManager.getCourseExams(context, courseId)
                if (cached != null) return@withContext Result.success(cached)
                return@withContext Result.failure(Exception("获取测验列表失败: ${resp.code}"))
            }
            val body = resp.body?.string() ?: ""
            val json = JSONObject(body)
            val examsArray = json.optJSONArray("exams")
            val list = mutableListOf<TronClassExam>()
            if (examsArray != null) {
                for (i in 0 until examsArray.length()) {
                    val ex = examsArray.getJSONObject(i)
                    val exam = TronClassExam(
                        id = ex.optLong("id"),
                        title = ex.optString("title", "测验"),
                        type = ex.optString("type", "exam"),
                        isStarted = ex.optBoolean("is_started", false),
                        isClosed = ex.optBoolean("is_closed", false),
                        startTime = ex.optString("start_time").takeIf { it.isNotBlank() },
                        endTime = ex.optString("end_time").takeIf { it.isNotBlank() },
                        limitTime = if (ex.has("limit_time")) ex.optInt("limit_time") else null,
                        totalPoints = if (ex.has("total_points")) ex.optDouble("total_points") else null,
                        subjectsCount = if (ex.has("subjects_count")) ex.optInt("subjects_count") else null,
                        submittedTimes = ex.optInt("submitted_times", 0)
                    )
                    list.add(exam)
                }
            }

            // 并发高速查询每个考试的学生答卷得分与计分规则 (从原本顺序遍历 3~5 秒大幅缩减至 ~300ms)
            coroutineScope {
                list.map { exam ->
                    async {
                        try {
                            val subUrl = "$BASE_URL/api/exams/${exam.id}/submissions"
                            val subReq = Request.Builder().url(subUrl).build()
                            val subResp = okHttpClient.newCall(subReq).execute()
                            if (subResp.isSuccessful) {
                                val subBody = subResp.body?.string() ?: ""
                                val subJson = JSONObject(subBody)
                                val rule = subJson.optString("exam_score_rule").takeIf { it.isNotBlank() }
                                exam.scoreRule = rule

                                var calcScore: Double? = null
                                if (!subJson.isNull("exam_final_score")) {
                                    val v = subJson.optDouble("exam_final_score")
                                    if (!v.isNaN()) calcScore = v
                                }
                                if (calcScore == null && !subJson.isNull("exam_score")) {
                                    val v = subJson.optDouble("exam_score")
                                    if (!v.isNaN()) calcScore = v
                                }

                                val subsArray = subJson.optJSONArray("submissions")
                                if (subsArray != null && subsArray.length() > 0) {
                                    val scores = mutableListOf<Double>()
                                    for (k in 0 until subsArray.length()) {
                                        val sObj = subsArray.getJSONObject(k)
                                        if (!sObj.isNull("score")) {
                                            val sc = sObj.optDouble("score")
                                            if (!sc.isNaN()) scores.add(sc)
                                        }
                                    }
                                    if (calcScore == null && scores.isNotEmpty()) {
                                        calcScore = when (rule) {
                                            "latest" -> scores.lastOrNull()
                                            "average" -> scores.average()
                                            else -> scores.maxOrNull()
                                        }
                                    }
                                    if ((exam.submittedTimes ?: 0) < subsArray.length()) {
                                        exam.submittedTimes = subsArray.length()
                                    }
                                }
                                exam.score = calcScore
                            }
                        } catch (_: Exception) {}
                    }
                }.awaitAll()
            }

            TronClassDataCacheManager.saveCourseExams(context, courseId, list)
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "fetchCourseExams failed", e)
            val cached = TronClassDataCacheManager.getCourseExams(context, courseId)
            if (cached != null) return@withContext Result.success(cached)
            Result.failure(e)
        }
    }

    /**
     * 获取测验详情与做题/查卷信息（支持多次提交轮次切换）
     */
    suspend fun fetchExamDetail(examId: Long, targetSubmissionId: Long? = null): Result<TronClassExamDetail> = withContext(Dispatchers.IO) {
        try {
            // 1. 获取考试基本信息
            val examUrl = "$BASE_URL/api/exams/$examId"
            val examReq = Request.Builder().url(examUrl).build()
            val examResp = okHttpClient.newCall(examReq).execute()
            if (!examResp.isSuccessful) {
                val cached = TronClassDataCacheManager.getExamDetail(context, examId)
                if (cached != null) return@withContext Result.success(cached)
                return@withContext Result.failure(Exception("获取测验信息失败: ${examResp.code}"))
            }
            val examJson = JSONObject(examResp.body?.string() ?: "")
            val exam = TronClassExam(
                id = examJson.optLong("id"),
                title = examJson.optString("title", "测验"),
                type = examJson.optString("type", "exam"),
                isStarted = examJson.optBoolean("is_started", false),
                isClosed = examJson.optBoolean("is_closed", false),
                startTime = examJson.optString("start_time").takeIf { it.isNotBlank() },
                endTime = examJson.optString("end_time").takeIf { it.isNotBlank() },
                limitTime = if (examJson.has("limit_time")) examJson.optInt("limit_time") else null,
                totalPoints = if (examJson.has("total_points")) examJson.optDouble("total_points") else null,
                subjectsCount = if (examJson.has("subjects_count")) examJson.optInt("subjects_count") else null,
                submittedTimes = examJson.optInt("submitted_times", 0)
            )

            // 2. 获取历史提交列表 (/submissions)
            val subUrl = "$BASE_URL/api/exams/$examId/submissions"
            val subReq = Request.Builder().url(subUrl).build()
            val subResp = okHttpClient.newCall(subReq).execute()

            val submissionsList = mutableListOf<TronClassExamSubmission>()
            var scoreRule: String? = null
            var finalScore: Double? = null
            var chosenSubmissionId: Long? = targetSubmissionId

            if (subResp.isSuccessful) {
                val subBody = subResp.body?.string() ?: ""
                val subJson = JSONObject(subBody)
                scoreRule = subJson.optString("exam_score_rule").takeIf { it.isNotBlank() }
                exam.scoreRule = scoreRule

                if (!subJson.isNull("exam_final_score")) {
                    val sc = subJson.optDouble("exam_final_score")
                    if (!sc.isNaN()) finalScore = sc
                }
                if (finalScore == null && !subJson.isNull("exam_score")) {
                    val sc = subJson.optDouble("exam_score")
                    if (!sc.isNaN()) finalScore = sc
                }

                val subsArray = subJson.optJSONArray("submissions")
                if (subsArray != null && subsArray.length() > 0) {
                    val scores = mutableListOf<Double>()
                    for (i in 0 until subsArray.length()) {
                        val sObj = subsArray.getJSONObject(i)
                        val sId = sObj.optLong("id")
                        val sc = if (!sObj.isNull("score")) {
                            val v = sObj.optDouble("score")
                            if (!v.isNaN()) v else null
                        } else null
                        if (sc != null) scores.add(sc)
                        val submitTime = sObj.optString("submit_time").takeIf { it.isNotBlank() }
                        val scoreText = if (sc != null) "${String.format(Locale.CHINA, "%.1f", sc).trimEnd('0').trimEnd('.')} 分" else "待批改"
                        submissionsList.add(
                            TronClassExamSubmission(
                                id = sId,
                                attemptIndex = i + 1,
                                score = sc,
                                scoreText = scoreText,
                                submittedAt = submitTime,
                                submitMethodText = if (sObj.optBoolean("is_passed", false)) "及格" else null
                            )
                        )
                    }

                    if (finalScore == null && scores.isNotEmpty()) {
                        finalScore = when (scoreRule) {
                            "latest" -> scores.lastOrNull()
                            "average" -> scores.average()
                            else -> scores.maxOrNull()
                        }
                    }

                    if (chosenSubmissionId == null) {
                        chosenSubmissionId = submissionsList.lastOrNull()?.id
                    }
                    if ((exam.submittedTimes ?: 0) < submissionsList.size) {
                        exam.submittedTimes = submissionsList.size
                    }
                }
            }
            exam.score = finalScore

            val subjects = mutableListOf<TronClassSubject>()
            val myAnswers = mutableMapOf<String, Any>()
            val correctAnswers = mutableMapOf<String, Any>()
            val isCorrectMap = mutableMapOf<String, Boolean?>()
            val subjectScores = mutableMapOf<String, Double>()
            val subjectExplanations = mutableMapOf<String, String>()
            var currentScore: Double? = null

            if (chosenSubmissionId != null && chosenSubmissionId > 0) {
                val detailUrl = "$BASE_URL/api/exams/$examId/submissions/$chosenSubmissionId"
                val detailReq = Request.Builder().url(detailUrl).build()
                val detailResp = okHttpClient.newCall(detailReq).execute()
                if (detailResp.isSuccessful) {
                    val detBody = detailResp.body?.string() ?: ""
                    val detJson = JSONObject(detBody)
                    if (!detJson.isNull("score")) {
                        val cs = detJson.optDouble("score")
                        if (!cs.isNaN()) currentScore = cs
                    }

                    // 正误判定映射 correct_data: {"1234": true, "1235": false}
                    val corDataObj = detJson.optJSONObject("correct_data")
                    if (corDataObj != null) {
                        val keys = corDataObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            isCorrectMap[k] = corDataObj.optBoolean(k)
                        }
                    }

                    // 标准答案 correct_answers_data: {"correct_answers": [...]}
                    val corAnsDataObj = detJson.optJSONObject("correct_answers_data")
                    val corAnsList = corAnsDataObj?.optJSONArray("correct_answers")
                    if (corAnsList != null) {
                        for (i in 0 until corAnsList.length()) {
                            val ca = corAnsList.getJSONObject(i)
                            val sid = ca.optLong("subject_id").toString()

                            val optIds = ca.optJSONArray("answer_option_ids")
                            if (optIds != null) {
                                val list = mutableListOf<Long>()
                                for (j in 0 until optIds.length()) list.add(optIds.getLong(j))
                                correctAnswers[sid] = list
                            }
                            val blanks = ca.optJSONArray("correct_answers")
                            if (blanks != null) {
                                val list = mutableListOf<String>()
                                for (j in 0 until blanks.length()) {
                                    val bObj = blanks.getJSONObject(j)
                                    list.add(bObj.optString("content", ""))
                                }
                                correctAnswers[sid] = list
                            }
                            val exp = ca.optString("answer_explanation", "")
                            if (exp.isNotBlank()) {
                                subjectExplanations[sid] = exp
                                if (!correctAnswers.containsKey(sid)) {
                                    correctAnswers[sid] = exp
                                }
                            }
                        }
                    }

                    // 学生作答 submission_data: {"subjects": [...]}
                    val subDataObj = detJson.optJSONObject("submission_data")
                    val userSubjsList = subDataObj?.optJSONArray("subjects")
                    val userAnswersBySid = mutableMapOf<String, Any>()
                    val userCommentsBySid = mutableMapOf<String, String>()
                    if (userSubjsList != null) {
                        for (i in 0 until userSubjsList.length()) {
                            val us = userSubjsList.getJSONObject(i)
                            val sid = us.optLong("subject_id").toString()
                            if (!us.isNull("score")) {
                                val sc = us.optDouble("score")
                                if (!sc.isNaN()) subjectScores[sid] = sc
                            }
                            val comment = us.optString("teacher_comment", "")
                            if (comment.isNotBlank()) {
                                userCommentsBySid[sid] = comment
                            }

                            val optIds = us.optJSONArray("answer_option_ids")
                            if (optIds != null) {
                                val list = mutableListOf<Long>()
                                for (j in 0 until optIds.length()) list.add(optIds.getLong(j))
                                userAnswersBySid[sid] = list
                                myAnswers[sid] = list
                            }
                            val blanks = us.optJSONArray("answers")
                            if (blanks != null) {
                                val list = mutableListOf<String>()
                                for (j in 0 until blanks.length()) {
                                    val bObj = blanks.getJSONObject(j)
                                    list.add(bObj.optString("content", ""))
                                }
                                userAnswersBySid[sid] = list
                                myAnswers[sid] = list
                            }
                            val ansStr = us.optString("answer", "")
                            if (ansStr.isNotBlank()) {
                                userAnswersBySid[sid] = ansStr
                                myAnswers[sid] = ansStr
                            }
                        }
                    }

                    // 题目列表 subjects_data.subjects
                    val subjsData = detJson.optJSONObject("subjects_data")
                    val subjsList = subjsData?.optJSONArray("subjects")
                    if (subjsList != null) {
                        for (i in 0 until subjsList.length()) {
                            val s = subjsList.getJSONObject(i)
                            val sid = s.optLong("id")
                            val sidStr = sid.toString()
                            val sTitle = s.optString("title", "第 ${i + 1} 题")
                            val sDesc = s.optString("description", "").takeIf { it.isNotBlank() } ?: sTitle
                            val sType = s.optString("type", "single_selection")
                            val sPoint = if (!s.isNull("point")) {
                                val v = s.optDouble("point")
                                if (!v.isNaN()) v else null
                            } else null

                            val opts = mutableListOf<TronClassSubjectOption>()
                            val optsArray = s.optJSONArray("options")
                            if (optsArray != null) {
                                for (j in 0 until optsArray.length()) {
                                    val op = optsArray.getJSONObject(j)
                                    opts.add(TronClassSubjectOption(
                                        id = op.optLong("id"),
                                        content = op.optString("content", "")
                                    ))
                                }
                            }

                            val uScore = subjectScores[sidStr]
                            val isCor = isCorrectMap[sidStr] ?: (if (uScore != null && sPoint != null && sPoint > 0) uScore >= sPoint else null)

                            subjects.add(TronClassSubject(
                                id = sid,
                                title = sTitle,
                                type = sType,
                                point = sPoint,
                                options = opts,
                                myAnswer = userAnswersBySid[sidStr],
                                correctAnswer = correctAnswers[sidStr],
                                score = uScore,
                                explanation = subjectExplanations[sidStr] ?: s.optString("answer_explanation").takeIf { it.isNotBlank() },
                                description = sDesc,
                                userScore = uScore,
                                isCorrect = isCor,
                                teacherComment = userCommentsBySid[sidStr]
                            ))
                        }
                    }
                }
            } else {
                val sumUrl = "$BASE_URL/api/exams/$examId/subjects-summary"
                val sumReq = Request.Builder().url(sumUrl).build()
                val sumResp = okHttpClient.newCall(sumReq).execute()
                if (sumResp.isSuccessful) {
                    val sumJson = JSONObject(sumResp.body?.string() ?: "")
                    val sumArray = sumJson.optJSONArray("subjects")
                    if (sumArray != null) {
                        for (i in 0 until sumArray.length()) {
                            val s = sumArray.getJSONObject(i)
                            val sid = s.optLong("id")
                            val sType = s.optString("type", "single_selection")
                            val sPoint = if (!s.isNull("point")) {
                                val v = s.optDouble("point")
                                if (!v.isNaN()) v else null
                            } else null
                            subjects.add(TronClassSubject(
                                id = sid,
                                title = "第 ${i + 1} 题",
                                type = sType,
                                point = sPoint
                            ))
                        }
                    }
                }
            }

            val detail = TronClassExamDetail(
                exam = exam,
                submissions = submissionsList,
                selectedSubmissionId = chosenSubmissionId,
                scoreRule = scoreRule,
                finalScore = finalScore,
                currentScore = currentScore ?: finalScore,
                subjects = subjects,
                myAnswers = myAnswers,
                correctAnswers = correctAnswers,
                isCorrectMap = isCorrectMap,
                subjectScores = subjectScores,
                subjectExplanations = subjectExplanations,
                submissionId = chosenSubmissionId,
                score = currentScore ?: finalScore
            )
            TronClassDataCacheManager.saveExamDetail(context, examId, detail)
            Result.success(detail)
        } catch (e: Exception) {
            Log.e(TAG, "fetchExamDetail failed", e)
            val cached = TronClassDataCacheManager.getExamDetail(context, examId)
            if (cached != null) return@withContext Result.success(cached)
            Result.failure(e)
        }
    }

    /**
     * 获取视频/课件的在线流媒体播放 URL
     */
    fun getVideoStreamUrl(uploadId: Long): String = "$BASE_URL/api/uploads/$uploadId/blob"

}
