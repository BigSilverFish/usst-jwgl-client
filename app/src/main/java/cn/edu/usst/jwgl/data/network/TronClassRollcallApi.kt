package cn.edu.usst.jwgl.data.network

import android.util.Log
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.RollcallSubmissionStore
import cn.edu.usst.jwgl.data.model.*
import cn.edu.usst.jwgl.data.network.TronClassClient.Companion.BASE_URL
import cn.edu.usst.jwgl.util.AppAuthExpiredException
import cn.edu.usst.jwgl.util.AppNetworkException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal class TronClassRollcallApi(
    private val executor: TronClassSessionExecutor,
    private val authApi: TronClassAuthApi
) {
    companion object {
        private const val TAG = "TronClassRollcallApi"
    }

    private val context get() = executor.context
    private val prefs get() = executor.prefs
    private val gson get() = executor.gson
    private val okHttpClient get() = executor.okHttpClient
    private val submissionClient get() = executor.submissionClient

    private fun accountKey() = executor.accountKey()
    private suspend fun executeRequest(request: Request, expectedAccount: String? = null) = executor.executeRequest(request, expectedAccount)
    private suspend fun refreshSessionOnce(account: String, previousSession: String?) = executor.refreshSessionOnce(account, previousSession)
    private suspend fun ensureSessionForAccount(studentId: String) = authApi.ensureSessionForAccount(studentId)
    private suspend fun refreshSession(studentId: String? = null) = authApi.refreshSession(studentId)
    private fun getUserNo(account: String? = null): String {
        val target = account?.takeIf { it.isNotBlank() } ?: accountKey()
        return prefs.getUserNo(target)?.takeIf { it.isNotBlank() }
            ?: target.ifBlank { cn.edu.usst.jwgl.data.local.DataCacheManager(context).getProfile()?.studentId ?: "" }
    }

    suspend fun fetchOngoingRollcalls(expectedAccount: String? = null): Result<List<TronClassRollcall>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/radar/rollcalls?api_version=1.1.0"
            val req = Request.Builder().url(url).get().build()
            val resp = executeRequest(req, expectedAccount)
            val body = resp.body?.string() ?: ""

            if (resp.code == 401) {
                return@withContext Result.failure(AppAuthExpiredException("一网畅学登录已失效，请重新授权登录"))
            }

            if (!resp.isSuccessful) {
                return@withContext Result.failure(AppNetworkException(resp.code, "查询签到状态失败 (${resp.code})", url = url))
            }

            val json = JSONObject(body)
            val arr = json.optJSONArray("rollcalls") ?: throw org.json.JSONException("Missing rollcalls array")
            val list = mutableListOf<TronClassRollcall>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val isNumber = item.optBoolean("is_number", false)
                    val isRadar = item.optBoolean("is_radar", false)
                    val isSelfRegistration = item.optBoolean("is_self_registration", false)
                    val ids = listOf("id", "rollcallId", "rollcall_id").filter { item.has(it) && !item.isNull(it) }
                        .map { item.optString(it).toLongOrNull()?.takeIf { id -> id > 0L }
                            ?: throw org.json.JSONException("Invalid activity ID") }.distinct()
                    if (ids.size != 1) throw org.json.JSONException("Missing or conflicting activity IDs")
                    val courseIds = listOf("courseId", "course_id").filter { item.has(it) && !item.isNull(it) }
                        .map { item.optString(it).toLongOrNull()?.takeIf { id -> id > 0L }
                            ?: throw org.json.JSONException("Invalid course ID") }.distinct()
                    if (courseIds.size > 1) throw org.json.JSONException("Conflicting course IDs")
                    val rawType = item.optString("type", item.optString("source", "")).lowercase(Locale.ROOT).trim()
                    val resolvedType = when {
                        isNumber || rawType == "number" -> "number"
                        isRadar || rawType == "radar" || rawType == "roomis" -> "radar"
                        isSelfRegistration || rawType == "self_registration" -> "self_registration"
                        rawType == "qr" || rawType == "qr_code" || rawType == "qr_rollcall" -> "qr_code"
                        else -> "unknown"
                    }
                    val courseTitle = item.optString("course_title",
                        item.optString("courseName",
                            item.optString("course_name",
                                item.optString("title", null))))
                    val rLat = if (item.has("latitude") && !item.isNull("latitude")) item.optDouble("latitude") else null
                    val rLng = if (item.has("longitude") && !item.isNull("longitude")) item.optDouble("longitude") else null
                    val rScope = if (item.has("scope") && !item.isNull("scope")) item.optDouble("scope") else null
                    val rClassroom = item.optString("classroom", null)
                    val rawNumberCode = listOf("number_code", "numberCode", "code")
                        .firstNotNullOfOrNull { key ->
                            if (item.has(key) && !item.isNull(key)) {
                                item.optString(key)?.trim()?.takeIf { it.matches(Regex("^[0-9]{4}$")) }
                            } else null
                        }

                    val r = TronClassRollcall(
                        id = ids.single(),
                        rollcallId = ids.single(),
                        courseId = courseIds.singleOrNull() ?: 0L,
                        courseName = courseTitle,
                        type = resolvedType,
                        status = item.optString("status", item.optString("rollcall_status", null)),
                        studentStatus = listOf("student_status", "student_rollcall_status")
                            .firstNotNullOfOrNull { key -> item.optString(key).takeIf { it.isNotBlank() && it != "null" } },
                        source = item.optString("source", null),
                        isNumber = isNumber,
                        isRadar = isRadar,
                        isSelfRegistration = isSelfRegistration,
                        latitude = rLat,
                        longitude = rLng,
                        scope = rScope,
                        classroom = rClassroom,
                        numberCode = rawNumberCode,
                        createdAt = item.optString("created_at").takeIf { it.isNotBlank() && it != "null" },
                        rollcallTime = item.optString("rollcall_time").takeIf { it.isNotBlank() && it != "null" },
                        isExpired = item.optBoolean("is_expired", false)
                    )
                    list.add(r)
                }
            }

            Result.success(list)
        } catch (e: CancellationException) {
            throw e
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
    suspend fun answerQRRollcall(
        rollcallId: Long,
        qrTokenData: String,
        customSessionId: String? = null,
        customDeviceId: String? = null,
        customAccountKey: String? = null,
        allowRejectedRetry: Boolean = true
    ): Result<RollcallSubmitResult> {
        val capturedAccount = customAccountKey ?: accountKey()
        return withContext(Dispatchers.IO) {
            if (rollcallId <= 0 || qrTokenData.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("请先选择有效活动并重新扫描二维码"))
            }
            try {
                val url = "$BASE_URL/api/rollcall/$rollcallId/answer_qr_rollcall"
                val targetDeviceId = customDeviceId ?: prefs.getDeviceId(capturedAccount)
                val payload = JSONObject().apply {
                    put("data", qrTokenData)
                    put("deviceId", targetDeviceId)
                }

                val reqBuilder = Request.Builder()
                    .url(url)
                    .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

                if (!customSessionId.isNullOrBlank()) {
                    reqBuilder.header("x-session-id", customSessionId)
                    reqBuilder.header("Cookie", "session_id=$customSessionId")
                }

                val req = reqBuilder.build()
                submitProtectedRollcall(
                    req,
                    rollcallId,
                    customSessionId,
                    customAccountKey,
                    capturedAccount,
                    allowRejectedRetry = allowRejectedRetry
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "answerQRRollcall error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 9. 提交数字口令签到
     * PUT /api/rollcall/{rollcallId}/answer_number_rollcall
     * Body: { "numberCode": "1234", "deviceId": deviceId }
     */
    suspend fun answerNumberRollcall(
        rollcallId: Long,
        numberCode: String,
        customSessionId: String? = null,
        customDeviceId: String? = null,
        customAccountKey: String? = null
    ): Result<RollcallSubmitResult> {
        val capturedAccount = customAccountKey ?: accountKey()
        return withContext(Dispatchers.IO) {
            try {
                val targetRollcallId = rollcallId
                if (targetRollcallId <= 0L || !numberCode.matches(Regex("[0-9]{4}"))) {
                    return@withContext Result.failure(IllegalArgumentException("必须选择明确的数字签到活动并输入四位数字"))
                }
                val url = "$BASE_URL/api/rollcall/$targetRollcallId/answer_number_rollcall"
                val targetDeviceId = customDeviceId ?: prefs.getDeviceId(capturedAccount)
                val payload = JSONObject().apply {
                    put("numberCode", numberCode.trim())
                    put("deviceId", targetDeviceId)
                }

                val reqBuilder = Request.Builder()
                    .url(url)
                    .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

                if (!customSessionId.isNullOrBlank()) {
                    reqBuilder.header("x-session-id", customSessionId)
                    reqBuilder.header("Cookie", "session_id=$customSessionId")
                }

                val req = reqBuilder.build()
                submitProtectedRollcall(req, targetRollcallId, customSessionId, customAccountKey, capturedAccount)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "answerNumberRollcall error", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 9.2 提交学生自主点名
     * PUT /api/rollcall/{rollcallId}/answer_self_registration_rollcall
     * Body: { "deviceId": deviceId }
     */
    suspend fun answerSelfRegistrationRollcall(
        rollcallId: Long,
        customSessionId: String? = null,
        customDeviceId: String? = null,
        customAccountKey: String? = null
    ): Result<RollcallSubmitResult> {
        val capturedAccount = customAccountKey ?: accountKey()
        return withContext(Dispatchers.IO) {
            try {
                if (rollcallId <= 0L) {
                    return@withContext Result.failure(IllegalArgumentException("请先选择明确的自主签到活动"))
                }
                val url = "$BASE_URL/api/rollcall/$rollcallId/answer_self_registration_rollcall"
                val targetDeviceId = customDeviceId ?: prefs.getDeviceId(capturedAccount)
                val payload = JSONObject().apply {
                    put("deviceId", targetDeviceId)
                }

                val reqBuilder = Request.Builder()
                    .url(url)
                    .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

                if (!customSessionId.isNullOrBlank()) {
                    reqBuilder.header("x-session-id", customSessionId)
                    reqBuilder.header("Cookie", "session_id=$customSessionId")
                }

                val req = reqBuilder.build()
                submitProtectedRollcall(req, rollcallId, customSessionId, customAccountKey, capturedAccount)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "answerSelfRegistrationRollcall error", e)
                Result.failure(e)
            }
        }
    }

    private suspend fun submitProtectedRollcall(
        request: Request, rollcallId: Long, customSessionId: String?, customAccountKey: String?, capturedAccount: String,
        allowRejectedRetry: Boolean = false
    ): Result<RollcallSubmitResult> {
        val account = capturedAccount
        if (account.isBlank() || (!customSessionId.isNullOrBlank() && customAccountKey.isNullOrBlank())) {
            return Result.failure(IllegalArgumentException("无法确认本次签到账号"))
        }
        var session = customSessionId ?: prefs.getSessionId(account)?.takeIf { it.isNotBlank() }
        if (customSessionId.isNullOrBlank() && !session.isNullOrBlank() && !TronClassAuthApi.isSessionVerified(account, session)) {
            session = ensureSessionForAccount(account).getOrNull()
        } else if (session.isNullOrBlank()) {
            session = ensureSessionForAccount(account).getOrNull()
        }
        if (session.isNullOrBlank()) return Result.failure(AppAuthExpiredException())
        val body = request.body ?: return Result.failure(IllegalArgumentException("缺少签到内容"))
        val authBuilder = request.newBuilder().header("x-session-id", session)
        if (request.header("Cookie") == null) {
            authBuilder.header("Cookie", "session_id=$session")
        }
        val authenticated = authBuilder.method(request.method, SingleAttemptRequestBody(body)).build()
        currentCoroutineContext().ensureActive()
        if (customAccountKey == null && account != accountKey()) throw CancellationException("Account changed before submission")
        val store = RollcallSubmissionStore(context)
        var claimed = store.claim(account, rollcallId, allowRejectedRetry)
        if (!claimed && store.state(account, rollcallId) == "confirmed") {
            val check = verifyRollcallAttendance(rollcallId, account, session, account)
            if (check == RollcallVerificationState.NOT_CONFIRMED) {
                store.invalidateConfirmed(account, rollcallId)
                claimed = store.claim(account, rollcallId, allowRejectedRetry)
            }
        }
        if (!claimed) {
            val state = store.state(account, rollcallId)
            if (state == null) return Result.failure(Exception("无法持久化签到保护记录，本次未发送"))
            val confirmed = state == "confirmed"
            return recoverUnknownRollcall(account, rollcallId, Result.success(RollcallSubmitResult(confirmed,
                if (confirmed) "本机已保存本活动的确认签到结果" else "本活动已有提交记录，请查询官方状态；为避免消耗错误次数不会再次发送")))
        }
        var activeSession = session
        val result = try {
            var response = executeRequest(authenticated)
            if (response.code == 401) {
                response.close()
                var refreshedSession = if (customAccountKey == null) {
                    if (refreshSessionOnce(account, session).getOrDefault(false)) {
                        prefs.getSessionId(account)?.takeIf { it.isNotBlank() && it != session }
                    } else null
                } else {
                    refreshSession(account)
                    prefs.getSessionId(account)?.takeIf { it.isNotBlank() && it != session }
                }
                if (refreshedSession.isNullOrBlank()) {
                    val pwd = cn.edu.usst.jwgl.data.local.AccountVault.getInstance(context).getAccountPassword(account)
                    if (pwd.isNotBlank() && authApi.silentLoginTronClass(account, pwd).isSuccess) {
                        refreshedSession = prefs.getSessionId(account)?.takeIf { it.isNotBlank() && it != session }
                    }
                }
                if (!refreshedSession.isNullOrBlank()) {
                    activeSession = refreshedSession
                    val retriedReq = request.newBuilder()
                        .header("x-session-id", refreshedSession)
                        .header("Cookie", "session_id=$refreshedSession")
                        .method(request.method, SingleAttemptRequestBody(body))
                        .build()
                    response = executeRequest(retriedReq)
                } else {
                    store.record(account, rollcallId, "rejected")
                    return Result.success(RollcallSubmitResult(false, "一网畅学登录已失效，请重新授权登录"))
                }
            }
            val parsed = response.use { parseRollcallWriteResult(it.code, it.body?.string().orEmpty()) }
            if (parsed.result.success) {
                var verified = verifyRollcallAttendance(rollcallId, account, activeSession, account)
                if (verified == RollcallVerificationState.NOT_CONFIRMED) {
                    kotlinx.coroutines.delay(600L)
                    verified = verifyRollcallAttendance(rollcallId, account, activeSession, account)
                }
                if (verified == RollcallVerificationState.NOT_CONFIRMED) {
                    store.invalidateConfirmed(account, rollcallId)
                    Result.success(RollcallSubmitResult(false, "服务端花名册未检测到本人签到记录，请重新签到"))
                } else {
                    store.record(account, rollcallId, "confirmed")
                    Result.success(parsed.result)
                }
            } else {
                store.record(account, rollcallId, parsed.state)
                Result.success(parsed.result)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The durable unknown claim is intentionally retained across process death and timeouts.
            Result.success(RollcallSubmitResult(false, "请求结果未知，请查询官方签到记录；不会再次发送"))
        }
        return recoverUnknownRollcall(account, rollcallId, result)
    }

    private suspend fun recoverUnknownRollcall(
        account: String, rollcallId: Long, result: Result<RollcallSubmitResult>,
        retryDelayMs: Long = 1200L
    ): Result<RollcallSubmitResult> {
        if (result.getOrNull()?.success == true || account != accountKey() ||
            RollcallSubmissionStore(context).state(account, rollcallId) != "unknown") return result
        val target = fetchOngoingRollcalls(account).getOrNull()?.singleOrNull {
            it.getEffectiveId() == rollcallId && it.courseId > 0L
        } ?: return result
        val firstRecord = queryRollcallAttendance(target, account).getOrNull()
        if (firstRecord?.isAttended() == true || firstRecord?.isLateOrEarly() == true) {
            return Result.success(RollcallSubmitResult(true, "官方记录已确认：${firstRecord.getStatusDisplayName()}"))
        }
        if (retryDelayMs > 0L) {
            kotlinx.coroutines.delay(retryDelayMs)
            val secondRecord = queryRollcallAttendance(target, account).getOrNull()
            if (secondRecord?.isAttended() == true || secondRecord?.isLateOrEarly() == true) {
                return Result.success(RollcallSubmitResult(true, "官方记录已确认：${secondRecord.getStatusDisplayName()}"))
            }
        }
        return result
    }

    /** Read-only recovery for a write whose result is unknown. Absence never enables another write. */
    suspend fun queryRollcallAttendance(rollcall: TronClassRollcall, expectedAccount: String? = null): Result<TronClassStudentRollcall?> {
        val account = expectedAccount ?: accountKey()
        if (account != accountKey()) throw CancellationException("Account changed before checking attendance")
        if (account.isBlank() || rollcall.courseId <= 0L || rollcall.getEffectiveId() <= 0L) {
            return Result.failure(IllegalArgumentException("缺少明确的账号、课程或活动"))
        }
        return withContext(Dispatchers.IO) {
            val records = fetchCourseStudentRollcalls(rollcall.courseId, account).getOrElse {
                if (it is CancellationException) throw it
                return@withContext Result.failure(it)
            }
            if (account != accountKey()) throw CancellationException("Account changed while checking attendance")
            val matches = records.filter { it.getEffectiveId() == rollcall.getEffectiveId() }
            if (matches.size > 1) return@withContext Result.failure(Exception("本活动记录不唯一，请查看官方平台"))
            val record = matches.singleOrNull()
            if (record?.isAttended() == true || record?.isLateOrEarly() == true) {
                RollcallSubmissionStore(context).record(account, rollcall.getEffectiveId(), "confirmed")
            }
            Result.success(record)
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
            val resp = executeRequest(req)
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
        accuracy: Double? = null,
        customSessionId: String? = null,
        customDeviceId: String? = null,
        customAccountKey: String? = null
    ): Result<RollcallSubmitResult> {
        val capturedAccount = customAccountKey ?: accountKey()
        return withContext(Dispatchers.IO) {
        try {
            if (capturedAccount.isBlank() || rollcallId <= 0L || !latitude.isFinite() || !longitude.isFinite() ||
                latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || (accuracy != null && (!accuracy.isFinite() || accuracy < 0.0))) {
                return@withContext Result.failure(IllegalArgumentException("签到活动或位置无效"))
            }
            val url = "$BASE_URL/api/rollcall/$rollcallId/answer?api_version=1.76"
            val targetDeviceId = customDeviceId ?: prefs.getDeviceId(capturedAccount)
            val payload = JSONObject().apply {
                put("deviceId", targetDeviceId)
                put("latitude", latitude)
                put("longitude", longitude)
                if (accuracy != null) put("accuracy", accuracy)
                put("altitude", 10.0)
                put("radarSignal", JSONObject.NULL)
            }

            Log.i(TAG, "Submitting radar rollcall: rollcallId=$rollcallId, lat=***, lng=***, devId=***, hasCustomSession=${!customSessionId.isNullOrBlank()}")

            val reqBuilder = Request.Builder()
                .url(url)
                .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

            if (!customSessionId.isNullOrBlank()) {
                reqBuilder.header("x-session-id", customSessionId)
                reqBuilder.header("Cookie", "session_id=$customSessionId")
            }

            // Only an explicit new radar action may retry a definite rejection; unknown writes remain blocked.
            submitProtectedRollcall(reqBuilder.build(), rollcallId, customSessionId, customAccountKey,
                capturedAccount, allowRejectedRetry = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "answerRadarRollcall error", e)
            Result.failure(e)
        }
        }
    }


    suspend fun executeRawAuthenticatedRequest(
        method: String,
        url: String,
        jsonBody: String? = null,
        customSessionId: String? = null,
        expectedAccount: String? = null
    ): Result<Pair<Int, String>> = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder().url(url)
            if (method.equals("PUT", ignoreCase = true)) {
                val body = (jsonBody ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType())
                reqBuilder.put(body)
            } else {
                reqBuilder.get()
            }
            if (!customSessionId.isNullOrBlank()) {
                reqBuilder.header("x-session-id", customSessionId)
                reqBuilder.header("Cookie", "session_id=$customSessionId")
            }
            val resp = executeRequest(reqBuilder.build(), expectedAccount)
            resp.use { r ->
                Result.success(r.code to r.body?.string().orEmpty())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyRollcallAttendance(
        rollcallId: Long,
        studentId: String,
        customSessionId: String? = null,
        expectedAccount: String? = null,
        courseIdHint: Long = 0L
    ): RollcallVerificationState = withContext(Dispatchers.IO) {
        if (rollcallId <= 0L || studentId.isBlank()) return@withContext RollcallVerificationState.UNAVAILABLE
        val account = expectedAccount ?: accountKey()
        try {
            val url = "$BASE_URL/api/rollcall/$rollcallId/student_rollcalls"
            val reqBuilder = Request.Builder().url(url).get()
            if (!customSessionId.isNullOrBlank()) {
                reqBuilder.header("x-session-id", customSessionId)
                reqBuilder.header("Cookie", "session_id=$customSessionId")
            }
            val resp = if (!customSessionId.isNullOrBlank()) {
                okHttpClient.newCall(reqBuilder.build()).awaitBuffered().response(reqBuilder.build())
            } else {
                executeRequest(reqBuilder.build(), account)
            }
            val body = resp.use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
            val parsed = parseStudentRollcallsVerification(body, studentId)
            if (parsed == RollcallVerificationState.CONFIRMED) {
                RollcallSubmissionStore(context).record(account, rollcallId, "confirmed")
                return@withContext RollcallVerificationState.CONFIRMED
            }
            if (parsed == RollcallVerificationState.NOT_CONFIRMED) {
                return@withContext RollcallVerificationState.NOT_CONFIRMED
            }

            if (courseIdHint > 0L) {
                val records = fetchCourseStudentRollcalls(courseIdHint, account).getOrNull()
                val match = records?.singleOrNull { it.getEffectiveId() == rollcallId }
                if (match != null) {
                    if (match.isAttended() || match.isLateOrEarly()) {
                        RollcallSubmissionStore(context).record(account, rollcallId, "confirmed")
                        return@withContext RollcallVerificationState.CONFIRMED
                    }
                    if (match.isAbsent()) {
                        return@withContext RollcallVerificationState.NOT_CONFIRMED
                    }
                }
            }
            RollcallVerificationState.UNAVAILABLE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "verifyRollcallAttendance error for rollcall $rollcallId, student $studentId: ${e.message}")
            RollcallVerificationState.UNAVAILABLE
        }
    }

    /**
     * 查询指定点名的实时出勤人数与全班统计
     * GET /api/rollcall/{rollcallId}/student_rollcalls
     */
    suspend fun fetchRollcallAttendanceStats(
        rollcallId: Long,
        expectedAccount: String? = null
    ): Result<cn.edu.usst.jwgl.data.model.RollcallAttendanceStats> = withContext(Dispatchers.IO) {
        if (rollcallId <= 0L) return@withContext Result.failure(IllegalArgumentException("点名 ID 无效"))
        val account = expectedAccount ?: accountKey()
        try {
            val url = "$BASE_URL/api/rollcall/$rollcallId/student_rollcalls"
            val req = Request.Builder().url(url).get().build()
            val resp = executeRequest(req, account)
            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("获取出勤统计失败 (${resp.code})"))
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return@withContext Result.failure(Exception("响应为空"))

            val json = JSONObject(body)
            val arr = json.optJSONArray("student_rollcalls") ?: json.optJSONArray("data")
            var presentCount = 0
            var lateCount = 0
            var absentCount = 0
            val totalCount = arr?.length() ?: 0
            var myStatus: String? = null
            val currentStudentId = getUserNo(account)

            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val entry = arr.getJSONObject(i)
                    val uNo = entry.optString("user_no", entry.optString("user_id", entry.optString("student_no", "")))
                    val eStatus = entry.optString("rollcall_status", entry.optString("student_rollcall_status", entry.optString("status", ""))).lowercase().trim()
                    when {
                        eStatus == "on_call_fine" || eStatus == "on_call" || eStatus == "present" || eStatus == "attended" -> presentCount++
                        eStatus == "on_call_arrive_late" || eStatus == "late" -> lateCount++
                        eStatus == "absent" -> absentCount++
                    }
                    if (currentStudentId.isNotBlank() && uNo.equals(currentStudentId, ignoreCase = true)) {
                        myStatus = eStatus
                    }
                }
            }

            val stats = cn.edu.usst.jwgl.data.model.RollcallAttendanceStats(
                rollcallId = rollcallId,
                presentCount = presentCount,
                totalCount = totalCount,
                lateCount = lateCount,
                absentCount = absentCount,
                myStatus = myStatus
            )
            Result.success(stats)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "fetchRollcallAttendanceStats error for rollcall $rollcallId: ${e.message}")
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

    suspend fun fetchCourseStudentRollcalls(courseId: Long, expectedAccount: String? = null): Result<List<TronClassStudentRollcall>> = withContext(Dispatchers.IO) {
        val account = expectedAccount ?: accountKey()
        if (account.isBlank() || courseId <= 0L) return@withContext Result.failure(IllegalArgumentException("缺少明确的账号或课程"))
        try {
            var uid = prefs.getUserId(account)
            if (uid <= 0L) {
                // 尝试从 /api/users/me 补全用户 ID
                try {
                    val meReq = Request.Builder()
                        .url("$BASE_URL/api/users/me")
                        .build()
                    val meResp = executeRequest(meReq, account)
                    if (meResp.isSuccessful) {
                        val meJson = JSONObject(meResp.body?.string() ?: "")
                        uid = meJson.optLong("id", 0L)
                        if (uid > 0L) {
                            prefs.setUserId(uid, account)
                        }
                    } else if (meResp.code == 401) {
                        return@withContext Result.failure(AppAuthExpiredException())
                    } else if (meResp.code == 403) {
                        return@withContext Result.failure(AppNetworkException(403, "当前账号无权读取签到记录"))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to auto-resolve user ID via /api/users/me", e)
                }
            }

            if (uid <= 0L) {
                Log.w(TAG, "fetchCourseStudentRollcalls aborted: user ID is missing")
                return@withContext Result.failure(Exception("无法获取当前用户信息，请重新登录一网畅学"))
            }

            val url = "$BASE_URL/api/course/$courseId/student/$uid/rollcalls"
            val req = Request.Builder().url(url).build()
            val resp = executeRequest(req, account)
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                Log.w(TAG, "fetchCourseStudentRollcalls HTTP ${resp.code} for course $courseId: ${body.take(200)}")
                return@withContext Result.failure(if (resp.code == 401) AppAuthExpiredException()
                    else AppNetworkException(resp.code, "获取签到历史失败 (${resp.code})", url = url))
            }

            val list = mutableListOf<TronClassStudentRollcall>()
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(parseStudentRollcallJson(obj))
                }
            } else if (trimmed.startsWith("{")) {
                val json = JSONObject(trimmed)
                val arr = json.optJSONArray("rollcalls") ?: json.optJSONArray("data")
                    ?: throw org.json.JSONException("Missing rollcall history array")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list.add(parseStudentRollcallJson(obj))
                    }
                }
            } else throw org.json.JSONException("Invalid rollcall history response")

            // 按时间从新到旧倒序排序
            list.sortByDescending { it.rollcallTime ?: it.createdAt ?: "" }

            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchCourseStudentRollcalls failed for course $courseId", e)
            Result.failure(e)
        }
    }

    private fun parseStudentRollcallJson(obj: JSONObject): TronClassStudentRollcall {
        val activityKeys = listOf("rollcall_id", "rollcallId").filter { obj.has(it) && !obj.isNull(it) }
            .ifEmpty { listOf("id") }
        val activityIds = activityKeys.map {
            obj.optString(it).toLongOrNull()?.takeIf { value -> value > 0L }
                ?: throw org.json.JSONException("Invalid history activity ID")
        }.distinct()
        if (activityIds.size != 1) throw org.json.JSONException("Conflicting history activity IDs")
        val rollcallId = activityIds.single()
        val id = obj.optLong("id", rollcallId)
        val title = obj.optString("title").takeIf { it.isNotBlank() }
        val rollcallTime = obj.optString("rollcall_time", obj.optString("rollcallTime", obj.optString("created_at", ""))).takeIf { it.isNotBlank() }
        val createdAt = obj.optString("created_at").takeIf { it.isNotBlank() }
        val type = obj.optString("type").takeIf { it.isNotBlank() }
        val isRadar = obj.optBoolean("is_radar", false)
        val isNumber = obj.optBoolean("is_number", false)
        val studentStatus = obj.optString("student_status", obj.optString("statusType", "")).takeIf { it.isNotBlank() }
        val status = obj.optString("status").takeIf { it.isNotBlank() }
        val rollcallStatus = obj.optString("rollcall_status", obj.optString("rollcallStatus", "")).takeIf { it.isNotBlank() }

        return TronClassStudentRollcall(
            rollcallId = rollcallId,
            id = id,
            title = title,
            rollcallTime = rollcallTime,
            createdAt = createdAt,
            type = type,
            isRadar = isRadar,
            isNumber = isNumber,
            studentStatus = studentStatus,
            status = status,
            rollcallStatus = rollcallStatus
        )
    }

}
