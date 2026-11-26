package cn.edu.usst.jwgl.data.network

import android.util.Log
import cn.edu.usst.jwgl.data.network.TronClassClient.RadarProbeResult
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.RollcallSubmissionStore
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.model.*
import cn.edu.usst.jwgl.data.network.TronClassClient.Companion.AUTH_SERVER_URL
import cn.edu.usst.jwgl.data.network.TronClassClient.Companion.BASE_URL
import cn.edu.usst.jwgl.data.network.TronClassClient.Companion.OAUTH_CLIENT_ID
import cn.edu.usst.jwgl.data.network.TronClassClient.Companion.OAUTH_REDIRECT_URI
import cn.edu.usst.jwgl.util.AppAuthExpiredException
import cn.edu.usst.jwgl.util.AppNetworkException
import cn.edu.usst.jwgl.util.CoordinateUtils
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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

    private fun orderedVaultAccounts(): List<AccountProfile> {
        val vault = cn.edu.usst.jwgl.data.local.AccountVault.getInstance(context)
        val currId = AuthPreferences.currentStudentId(context)
        return MultiAccountRollcallResult.orderAccountsPrimaryFirst(vault.getAccounts(), currId)
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
                                RollcallNumberCodeLookup.coerceNumberCodeString(item.optString(key))
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

    /** Reads the known target only. A returned candidate is never submitted by this method. */
    suspend fun lookupNumberRollcallCode(rollcall: TronClassRollcall): NumberCodeLookupResult = withContext(Dispatchers.IO) {
        fun unavailable(reason: NumberCodeLookupFailure, message: String) = NumberCodeLookupResult.Unavailable(reason, message)
        val account = accountKey()
        val effectiveRollcallId = rollcall.getEffectiveId()
        if (account.isBlank() || effectiveRollcallId <= 0 || rollcall.getNormalizedType() != "number") {
            return@withContext unavailable(NumberCodeLookupFailure.ContextMissing, "请先选择明确的数字签到活动和课程")
        }

        // 1. 若入参对象本身已携带有效的 4 位口令（如来自列表预拉取），直接命中返回
        val immediateCode = RollcallNumberCodeLookup.coerceNumberCodeString(rollcall.numberCode)
        if (immediateCode != null) {
            Log.i(TAG, "lookupNumberRollcallCode: immediately found from rollcall cache: $immediateCode")
            return@withContext NumberCodeLookupResult.Found(immediateCode, effectiveRollcallId, rollcall.courseId)
        }

        try {
            // 2. 从当前进行中列表辅助核实并补全 courseId（若入参缺少）
            val ongoing = fetchOngoingRollcalls(account).getOrDefault(emptyList())
            val ongoingMatches = ongoing.filter { it.getEffectiveId() == effectiveRollcallId && it.getNormalizedType() == "number" && !it.isExpired }
            val matchedTarget = ongoingMatches.firstOrNull()
            if (matchedTarget != null) {
                val codeFromOngoing = RollcallNumberCodeLookup.coerceNumberCodeString(matchedTarget.numberCode)
                if (codeFromOngoing != null) {
                    Log.i(TAG, "lookupNumberRollcallCode: found from ongoing rollcalls: $codeFromOngoing")
                    return@withContext NumberCodeLookupResult.Found(codeFromOngoing, effectiveRollcallId, matchedTarget.courseId)
                }
            }

            var targetForLookup = if (rollcall.courseId > 0) rollcall else (matchedTarget ?: rollcall)
            if (targetForLookup.courseId <= 0) {
                // 如果仍然缺少课程 ID，尝试从 /api/rollcall/{id}/lite 中嗅探并补齐
                val liteInfo = getRollcallInfoLite(effectiveRollcallId).getOrNull()
                val discoveredCourseId = liteInfo?.optLong("course_id", 0L)
                    ?.takeIf { it > 0 } ?: liteInfo?.optLong("courseId", 0L) ?: 0L
                if (discoveredCourseId > 0) {
                    targetForLookup = targetForLookup.copy(courseId = discoveredCourseId)
                }
            }

            if (targetForLookup.courseId <= 0) {
                return@withContext unavailable(NumberCodeLookupFailure.ContextMissing, "缺少明确的课程信息")
            }

            // 3. 通道一：查询学生出勤详情接口 GET /api/rollcall/{id}/student_rollcalls
            val request = Request.Builder().url("$BASE_URL/api/rollcall/$effectiveRollcallId/student_rollcalls").get().build()
            val primaryResult = executeRequest(request, account).use { response ->
                when (response.code) {
                    401 -> unavailable(NumberCodeLookupFailure.SessionExpired, "登录已失效，请重新授权")
                    403 -> unavailable(NumberCodeLookupFailure.Forbidden, "当前账号无权读取本活动数字码")
                    in 200..299 -> RollcallNumberCodeLookup.parse(response.body?.string().orEmpty(), targetForLookup)
                    else -> unavailable(NumberCodeLookupFailure.NetworkError, "获取数字码失败 (${response.code})")
                }
            }

            if (primaryResult is NumberCodeLookupResult.Found) {
                Log.i(TAG, "lookupNumberRollcallCode: found via student_rollcalls: ${primaryResult.code}")
                return@withContext primaryResult
            }

            // 4. 通道二（Fallback 嗅探）：若通道一未返回口令，尝试从 /api/rollcall/{id}/lite 读取
            val fallbackReq = Request.Builder().url("$BASE_URL/api/rollcall/$effectiveRollcallId/lite").get().build()
            val fallbackResult = executeRequest(fallbackReq, account).use { response ->
                if (response.isSuccessful) {
                    RollcallNumberCodeLookup.parse(response.body?.string().orEmpty(), targetForLookup)
                } else null
            }
            if (fallbackResult is NumberCodeLookupResult.Found) {
                Log.i(TAG, "lookupNumberRollcallCode: found via lite details: ${fallbackResult.code}")
                return@withContext fallbackResult
            }

            currentCoroutineContext().ensureActive()
            if (account != accountKey()) throw CancellationException("Account changed during number code lookup")
            primaryResult
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            unavailable(NumberCodeLookupFailure.NetworkError, "无法读取数字码，请检查网络")
        }
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


    /**
     * 向指定经纬度坐标发送雷达测距探针
     * 若在范围内，返回 InRange；若超出范围且返回了与教师距离，返回 Distance(m)；否则返回 Error
     */
    suspend fun probeRadarCoordinate(
        rollcallId: Long,
        latitude: Double,
        longitude: Double,
        customSessionId: String? = null,
        customDeviceId: String? = null,
        customAccountKey: String? = null
    ): RadarProbeResult = withContext(Dispatchers.IO) {
        val capturedAccount = customAccountKey ?: accountKey()
        if (capturedAccount.isBlank() || rollcallId <= 0L) {
            return@withContext RadarProbeResult.Error("账号或活动无效")
        }
        val session = customSessionId ?: prefs.getSessionId(capturedAccount)
        if (session.isNullOrBlank()) {
            return@withContext RadarProbeResult.Error("未登录或会话失效")
        }
        try {
            val url = "$BASE_URL/api/rollcall/$rollcallId/answer?api_version=1.76"
            val targetDeviceId = customDeviceId ?: prefs.getDeviceId(capturedAccount)
            val payload = JSONObject().apply {
                put("deviceId", targetDeviceId)
                put("latitude", latitude)
                put("longitude", longitude)
                put("accuracy", 60.0)
                put("altitude", 10.0)
                put("radarSignal", JSONObject.NULL)
            }
            val reqBuilder = Request.Builder()
                .url(url)
                .header("x-session-id", session)
                .header("Cookie", "session_id=$session")
                .put(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))

            val resp = executeRequest(reqBuilder.build(), capturedAccount)
            val code = resp.code
            val body = resp.body?.string().orEmpty()
            val parsed = parseRollcallWriteResult(code, body)

            if (parsed.result.success || parsed.state == "confirmed") {
                val verified = verifyRollcallAttendance(rollcallId, capturedAccount, session, capturedAccount)
                if (verified == RollcallVerificationState.NOT_CONFIRMED) {
                    RollcallSubmissionStore(context).invalidateConfirmed(capturedAccount, rollcallId)
                    return@withContext RadarProbeResult.Error("服务端花名册未检测到本人签到记录，请重新签到")
                }
                RollcallSubmissionStore(context).record(capturedAccount, rollcallId, "confirmed")
                return@withContext RadarProbeResult.InRange(parsed.result)
            }

            val dist = parsed.result.distance
            if (dist != null && dist >= 0.0) {
                return@withContext RadarProbeResult.Distance(dist)
            }

            return@withContext RadarProbeResult.Error(parsed.result.message.ifBlank { "探针未返回距离 (HTTP $code)" })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext RadarProbeResult.Error(e.message ?: "探针网络请求异常")
        }
    }

    /**
     * 自动执行三点定位反解并完成签到
     */
    suspend fun solveTrilaterationAndAnswer(
        rollcallId: Long,
        classroom: String? = null,
        courseName: String? = null,
        customSessionId: String? = null,
        customDeviceId: String? = null,
        customAccountKey: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): Result<RollcallSubmitResult> = withContext(Dispatchers.IO) {
        val capturedAccount = customAccountKey ?: accountKey()
        onProgress?.invoke("正在获取校园基准三角锚点...")
        val anchors = cn.edu.usst.jwgl.util.TrilaterationSolver.getCampusAnchors(classroom, courseName)
        val observations = mutableListOf<cn.edu.usst.jwgl.util.TrilaterationSolver.AnchorObservation>()

        for ((idx, anchor) in anchors.withIndex()) {
            onProgress?.invoke("正在探测第 ${idx + 1}/3 点: 【${anchor.name}】...")
            val probe = probeRadarCoordinate(
                rollcallId = rollcallId,
                latitude = anchor.latitude,
                longitude = anchor.longitude,
                customSessionId = customSessionId,
                customDeviceId = customDeviceId,
                customAccountKey = capturedAccount
            )
            when (probe) {
                is RadarProbeResult.InRange -> {
                    onProgress?.invoke("锚点【${anchor.name}】直接在签到范围内，秒签成功！")
                    return@withContext Result.success(probe.submitResult)
                }
                is RadarProbeResult.Distance -> {
                    Log.i(TAG, "Anchor ${anchor.name} distance: ${probe.distanceMeters}m")
                    observations.add(
                        cn.edu.usst.jwgl.util.TrilaterationSolver.AnchorObservation(
                            latitude = anchor.latitude,
                            longitude = anchor.longitude,
                            distanceMeters = probe.distanceMeters,
                            name = anchor.name
                        )
                    )
                }
                is RadarProbeResult.Error -> {
                    Log.w(TAG, "Anchor ${anchor.name} probe error: ${probe.message}")
                }
            }
        }

        if (observations.size < 3) {
            val msg = "三点测距不足 (仅获取 ${observations.size}/3 组有效距离)，无法精确解算"
            onProgress?.invoke(msg)
            return@withContext Result.failure(Exception(msg))
        }

        onProgress?.invoke("已获取 3 组测距数据，正在三边测量非线性解算教师坐标...")
        val solved = cn.edu.usst.jwgl.util.TrilaterationSolver.solve(observations)
            ?: return@withContext Result.failure(Exception("三点几何反解矩阵退化，解算失败"))
        if (!cn.edu.usst.jwgl.util.TrilaterationSolver.isAcceptableSolution(solved, observations)) {
            return@withContext Result.failure(Exception("三点反解残差过大 (±${solved.residualRmse.toInt()}m)，已放弃自动提交"))
        }

        Log.i(TAG, "Trilateration solved teacher location: lat=${solved.latitude}, lng=${solved.longitude}, rmse=${solved.residualRmse}m")
        onProgress?.invoke(String.format(Locale.CHINA, "解算完成！拟合误差: ±%.1fm，正在提交高精度签到...", solved.residualRmse))

        val finalRes = answerRadarRollcall(
            rollcallId = rollcallId,
            latitude = solved.latitude,
            longitude = solved.longitude,
            accuracy = 5.0,
            customSessionId = customSessionId,
            customDeviceId = customDeviceId,
            customAccountKey = capturedAccount
        )
        finalRes
    }

    /**
     * 批量三点定位签到（单次解算，全员复用提交；未能收敛时使用 fallback 坐标或教学楼兜底）
     */
    suspend fun batchAnswerTrilaterationRollcall(
        rollcallId: Long,
        classroom: String? = null,
        courseName: String? = null,
        fallbackLatitude: Double? = null,
        fallbackLongitude: Double? = null,
        fallbackAccuracy: Double = 15.0,
        onProgress: ((String) -> Unit)? = null
    ): List<MultiAccountRollcallResult> = withContext(Dispatchers.IO) {
        val accounts = orderedVaultAccounts()
        if (accounts.isEmpty()) {
            val currId = AuthPreferences(context).getStudentId()
            val single = solveTrilaterationAndAnswer(rollcallId, classroom, courseName, onProgress = onProgress)
            val sRes = single.getOrNull()
            if (sRes?.success == true) {
                return@withContext listOf(
                    MultiAccountRollcallResult(
                        studentId = currId,
                        studentName = "当前用户",
                        success = true,
                        message = sRes.message,
                        statusName = sRes.statusName ?: ""
                    )
                )
            }
            if (fallbackLatitude != null && fallbackLongitude != null) {
                onProgress?.invoke("三点定位未成功，降级为预设/本机定位签到...")
                val fb = answerRadarRollcall(rollcallId, fallbackLatitude, fallbackLongitude, fallbackAccuracy)
                val fbRes = fb.getOrNull()
                return@withContext listOf(
                    MultiAccountRollcallResult(
                        studentId = currId,
                        studentName = "当前用户",
                        success = fbRes?.success == true,
                        message = fbRes?.message ?: (fb.exceptionOrNull()?.message ?: "未知结果"),
                        statusName = fbRes?.statusName ?: ""
                    )
                )
            }
            return@withContext listOf(
                MultiAccountRollcallResult(
                    studentId = currId,
                    studentName = "当前用户",
                    success = false,
                    message = sRes?.message ?: (single.exceptionOrNull()?.message ?: "未知结果"),
                    statusName = sRes?.statusName ?: ""
                )
            )
        }

        var solvedCoord: Pair<Double, Double>? = null
        val primaryAcc = accounts.first()
        val primarySessionRes = ensureSessionForAccount(primaryAcc.studentId)
        val primarySid = primarySessionRes.getOrNull()

        if (!primarySid.isNullOrBlank()) {
            onProgress?.invoke("主账号正在进行校园三点测距反解...")
            val anchors = cn.edu.usst.jwgl.util.TrilaterationSolver.getCampusAnchors(classroom, courseName)
            val observations = mutableListOf<cn.edu.usst.jwgl.util.TrilaterationSolver.AnchorObservation>()
            for (anchor in anchors) {
                val probe = probeRadarCoordinate(
                    rollcallId = rollcallId,
                    latitude = anchor.latitude,
                    longitude = anchor.longitude,
                    customSessionId = primarySid,
                    customDeviceId = prefs.getDeviceId(primaryAcc.studentId),
                    customAccountKey = primaryAcc.studentId
                )
                if (probe is RadarProbeResult.InRange) {
                    solvedCoord = Pair(anchor.latitude, anchor.longitude)
                    break
                } else if (probe is RadarProbeResult.Distance) {
                    observations.add(
                        cn.edu.usst.jwgl.util.TrilaterationSolver.AnchorObservation(
                            anchor.latitude, anchor.longitude, probe.distanceMeters, anchor.name
                        )
                    )
                }
            }
            if (solvedCoord == null && observations.size >= 3) {
                val s = cn.edu.usst.jwgl.util.TrilaterationSolver.solve(observations)
                if (s != null) {
                    solvedCoord = Pair(s.latitude, s.longitude)
                }
            }
        }

        if (solvedCoord == null) {
            if (fallbackLatitude != null && fallbackLongitude != null) {
                onProgress?.invoke("三点定位测距反解未能收敛，降级为本机/预设坐标批量签到")
                return@withContext batchAnswerRadarRollcall(rollcallId, fallbackLatitude, fallbackLongitude, fallbackAccuracy)
            }
            onProgress?.invoke("三点定位测距反解未能收敛，降级为常规教学楼批量签到")
            val building = CoordinateUtils.findMatchingBuilding(classroom)
                ?: CoordinateUtils.findMatchingBuilding(courseName)
                ?: CoordinateUtils.USST_BUILDINGS.first()
            return@withContext batchAnswerRadarRollcall(rollcallId, building.latitude, building.longitude)
        }

        val (targetLat, targetLng) = solvedCoord
        onProgress?.invoke("三点反解坐标成功，正在为所有账号分发独立设备码签到...")
        batchAnswerRadarRollcall(rollcallId, targetLat, targetLng, accuracy = 5.0)
    }

    /**
     * 先按优先级尝试常规候选坐标（教师基准 -> 本机定位 -> 匹配教学楼），命中即止；
     * 若因超出范围失败且服务端返回有效 distance，则复用满足几何间距的观测点进入三点定位回退（缺几点补测几点）。
     */
    suspend fun batchAnswerRadarWithTrilaterationFallback(
        rollcallId: Long,
        primaryCandidates: List<TronClassClient.RadarCandidatePoint>,
        classroom: String? = null,
        courseName: String? = null,
        allowTrilaterationFallback: Boolean = true,
        onProgress: ((String) -> Unit)? = null
    ): List<MultiAccountRollcallResult> = withContext(Dispatchers.IO) {
        val accounts = orderedVaultAccounts()
        val currId = AuthPreferences(context).getStudentId()
        val primaryAcc = accounts.find { it.studentId == currId } ?: accounts.firstOrNull()
        val primaryId = primaryAcc?.studentId ?: currId
        val primaryName = primaryAcc?.studentName?.ifBlank { primaryAcc.studentId } ?: "当前用户"

        var primarySid: String? = null
        var primaryDevId: String? = null
        var primaryCustomKey: String? = null
        if (primaryAcc != null) {
            val sessionRes = ensureSessionForAccount(primaryAcc.studentId)
            val sid = sessionRes.getOrNull()
            if (sid.isNullOrBlank()) {
                val err = sessionRes.exceptionOrNull()?.message ?: "登录失效"
                return@withContext listOf(
                    MultiAccountRollcallResult(primaryAcc.studentId, primaryName, false, "畅课授权失败: $err")
                )
            }
            primarySid = sid
            primaryDevId = prefs.getDeviceId(primaryAcc.studentId)
            primaryCustomKey = primaryAcc.studentId
        }

        val store = RollcallSubmissionStore(context)
        val observations = mutableListOf<cn.edu.usst.jwgl.util.TrilaterationSolver.AnchorObservation>()
        var sawOutOfScopeDistance = false
        var winningCoord: Triple<Double, Double, Double?>? = null
        var primaryResult: RollcallSubmitResult? = null
        var lastErrorMsg: String? = null

        // Phase 1: 按优先级依次尝试常规候选点位，首次命中即结束；超距且返回距离则收入几何观测池
        for (cand in primaryCandidates) {
            onProgress?.invoke("正在尝试【${cand.label}】雷达签到...")
            val res = answerRadarRollcall(
                rollcallId = rollcallId,
                latitude = cand.latitude,
                longitude = cand.longitude,
                accuracy = cand.accuracy,
                customSessionId = primarySid,
                customDeviceId = primaryDevId,
                customAccountKey = primaryCustomKey
            )
            val sRes = res.getOrNull()
            if (sRes?.success == true) {
                winningCoord = Triple(cand.latitude, cand.longitude, cand.accuracy)
                primaryResult = sRes
                break
            }

            lastErrorMsg = sRes?.message ?: res.exceptionOrNull()?.message
            val storeState = if (primaryId.isNotBlank()) store.state(primaryId, rollcallId) else null
            if (storeState == "unknown" || storeState == "confirmed") {
                primaryResult = sRes
                break
            }

            val dist = sRes?.distance
            if (dist != null && dist >= 0.0) {
                sawOutOfScopeDistance = true
                cn.edu.usst.jwgl.util.TrilaterationSolver.tryAddObservation(
                    observations,
                    cn.edu.usst.jwgl.util.TrilaterationSolver.AnchorObservation(
                        latitude = cand.latitude,
                        longitude = cand.longitude,
                        distanceMeters = dist,
                        name = cand.label
                    )
                )
            } else {
                // 非超距拒绝（如点名结束、需要蓝牙信标、无权限等），不触发三点定位回退
                break
            }
        }

        // Phase 2: 条件触发三点定位回退（复用前置观测点，只补测缺失的几何最优锚点）
        val stateBeforeFallback = if (primaryId.isNotBlank()) store.state(primaryId, rollcallId) else null
        if (winningCoord == null &&
            allowTrilaterationFallback &&
            stateBeforeFallback != "unknown" &&
            stateBeforeFallback != "confirmed" &&
            (sawOutOfScopeDistance || primaryCandidates.isEmpty())
        ) {
            val candidateAnchors = cn.edu.usst.jwgl.util.TrilaterationSolver.getCandidateAnchors(classroom, courseName)
            val usedAnchorIds = mutableSetOf<String>()
            var extraProbes = 0

            while (observations.size < 3 && extraProbes < 3) {
                val anchor = cn.edu.usst.jwgl.util.TrilaterationSolver.selectNextBestAnchor(
                    observations = observations,
                    candidates = candidateAnchors,
                    excludedIds = usedAnchorIds
                ) ?: break
                usedAnchorIds.add(anchor.id)
                extraProbes++
                onProgress?.invoke("常规位置超出范围，补测第 $extraProbes 个最优锚点【${anchor.name}】(${observations.size + 1}/3)...")

                val res = answerRadarRollcall(
                    rollcallId = rollcallId,
                    latitude = anchor.latitude,
                    longitude = anchor.longitude,
                    accuracy = 60.0,
                    customSessionId = primarySid,
                    customDeviceId = primaryDevId,
                    customAccountKey = primaryCustomKey
                )
                val sRes = res.getOrNull()
                if (sRes?.success == true) {
                    winningCoord = Triple(anchor.latitude, anchor.longitude, 60.0)
                    primaryResult = sRes
                    break
                }

                lastErrorMsg = sRes?.message ?: res.exceptionOrNull()?.message
                val storeState = if (primaryId.isNotBlank()) store.state(primaryId, rollcallId) else null
                if (storeState == "unknown" || storeState == "confirmed") {
                    primaryResult = sRes
                    break
                }

                val dist = sRes?.distance
                if (dist != null && dist >= 0.0) {
                    cn.edu.usst.jwgl.util.TrilaterationSolver.tryAddObservation(
                        observations,
                        cn.edu.usst.jwgl.util.TrilaterationSolver.AnchorObservation(
                            latitude = anchor.latitude,
                            longitude = anchor.longitude,
                            distanceMeters = dist,
                            name = anchor.name
                        )
                    )
                } else {
                    break
                }
            }

            val stateAfterProbes = if (primaryId.isNotBlank()) store.state(primaryId, rollcallId) else null
            if (winningCoord == null &&
                observations.size >= 3 &&
                stateAfterProbes != "unknown" &&
                stateAfterProbes != "confirmed"
            ) {
                val solved = cn.edu.usst.jwgl.util.TrilaterationSolver.solve(observations)
                if (cn.edu.usst.jwgl.util.TrilaterationSolver.isAcceptableSolution(solved, observations)) {
                    val target = solved!!
                    Log.i(TAG, "Trilateration fallback solved (obs=${observations.size}, extraProbes=$extraProbes, rmse=${target.residualRmse}m)")
                    onProgress?.invoke(String.format(Locale.CHINA, "三点反解收敛 (误差 ±%.1fm)，正在提交签到...", target.residualRmse))
                    val finalRes = answerRadarRollcall(
                        rollcallId = rollcallId,
                        latitude = target.latitude,
                        longitude = target.longitude,
                        accuracy = 5.0,
                        customSessionId = primarySid,
                        customDeviceId = primaryDevId,
                        customAccountKey = primaryCustomKey
                    )
                    val sRes = finalRes.getOrNull()
                    if (sRes?.success == true) {
                        winningCoord = Triple(target.latitude, target.longitude, 5.0)
                        primaryResult = sRes
                    } else {
                        primaryResult = sRes
                        lastErrorMsg = sRes?.message ?: finalRes.exceptionOrNull()?.message
                    }
                } else {
                    Log.w(TAG, "Trilateration fallback solution rejected: solved=$solved, obsCount=${observations.size}")
                    if (lastErrorMsg.isNullOrBlank()) {
                        lastErrorMsg = "三点定位反解残差过大，已放弃自动提交"
                    }
                }
            }
        }

        // Phase 3: 汇总主账号结果并在确认有效坐标后同步分发其余账号
        val primaryOutcome = MultiAccountRollcallResult(
            studentId = primaryId,
            studentName = primaryName,
            success = winningCoord != null,
            message = primaryResult?.message ?: lastErrorMsg ?: "签到未成功",
            statusName = primaryResult?.statusName ?: ""
        )

        if (accounts.isEmpty() || winningCoord == null) {
            return@withContext listOf(primaryOutcome)
        }

        val (winLat, winLng, winAcc) = winningCoord
        val results = mutableListOf(primaryOutcome)
        for (acc in accounts) {
            if (acc.studentId == primaryId) continue
            val nameDisplay = acc.studentName.ifBlank { acc.studentId }
            val sessionRes = ensureSessionForAccount(acc.studentId)
            val sid = sessionRes.getOrNull()
            if (sid.isNullOrBlank()) {
                val err = sessionRes.exceptionOrNull()?.message ?: "登录失效"
                results.add(MultiAccountRollcallResult(acc.studentId, nameDisplay, false, "畅课授权失败: $err"))
                continue
            }
            val devId = prefs.getDeviceId(acc.studentId)
            val res = answerRadarRollcall(
                rollcallId = rollcallId,
                latitude = winLat,
                longitude = winLng,
                accuracy = winAcc,
                customSessionId = sid,
                customDeviceId = devId,
                customAccountKey = acc.studentId
            )
            val s = res.getOrNull()
            if (s != null) {
                results.add(MultiAccountRollcallResult(acc.studentId, nameDisplay, s.success, s.message, s.statusName ?: ""))
            } else {
                val errMsg = res.exceptionOrNull()?.message ?: "签到请求失败"
                results.add(MultiAccountRollcallResult(acc.studentId, nameDisplay, false, errMsg))
            }
        }
        results
    }

    /**
     * 9.3 纯后台静默完成指定账号的 CAS SSO -> Keycloak OAuth -> TronClass 凭据授权
     */

    suspend fun batchAnswerNumberRollcall(
        rollcallId: Long,
        numberCode: String
    ): List<MultiAccountRollcallResult> = withContext(Dispatchers.IO) {
        val accounts = orderedVaultAccounts()
        if (accounts.isEmpty()) {
            val currId = AuthPreferences(context).getStudentId()
            val single = answerNumberRollcall(rollcallId, numberCode)
            val sRes = single.getOrNull()
            return@withContext listOf(
                MultiAccountRollcallResult(
                    studentId = currId,
                    studentName = "当前用户",
                    success = sRes?.success == true,
                    message = sRes?.message ?: "未知结果",
                    statusName = sRes?.statusName ?: ""
                )
            )
        }

        val results = mutableListOf<MultiAccountRollcallResult>()
        for (acc in accounts) {
            val nameDisplay = acc.studentName.ifBlank { acc.studentId }
            val sessionRes = ensureSessionForAccount(acc.studentId)
            val sid = sessionRes.getOrNull()
            if (sid.isNullOrBlank()) {
                val err = sessionRes.exceptionOrNull()?.message ?: "登录失效"
                results.add(MultiAccountRollcallResult(acc.studentId, nameDisplay, false, "畅课授权失败: $err"))
                continue
            }
            val devId = prefs.getDeviceId(acc.studentId)
            val res = answerNumberRollcall(rollcallId, numberCode, customSessionId = sid, customDeviceId = devId, customAccountKey = acc.studentId)
            val s = res.getOrNull()
            if (s != null) {
                results.add(MultiAccountRollcallResult(acc.studentId, nameDisplay, s.success, s.message, s.statusName ?: ""))
            } else {
                val errMsg = res.exceptionOrNull()?.message ?: "签到请求失败"
                results.add(MultiAccountRollcallResult(acc.studentId, nameDisplay, false, errMsg))
            }
        }
        results
    }

    /**
     * 9.6 一键为所有已保存账号提交【二维码扫码】签到
     */
    suspend fun batchAnswerQRRollcall(
        rollcallId: Long,
        qrTokenData: String
    ): List<MultiAccountRollcallResult> = withContext(Dispatchers.IO) {
        val accounts = orderedVaultAccounts()
        if (accounts.isEmpty()) {
            val currId = AuthPreferences(context).getStudentId()
            val single = answerQRRollcall(rollcallId, qrTokenData)
            val sRes = single.getOrNull()
            return@withContext listOf(
                MultiAccountRollcallResult(
                    studentId = currId,
                    studentName = "当前用户",
                    success = sRes?.success == true,
                    message = sRes?.message ?: (single.exceptionOrNull()?.message ?: "未知结果"),
                    statusName = sRes?.statusName ?: ""
                )
            )
        }

        suspend fun submitSingleQrAccount(acc: AccountProfile, fastPathForPrimary: Boolean): MultiAccountRollcallResult {
            val nameDisplay = acc.studentName.ifBlank { acc.studentId }
            val sid = if (fastPathForPrimary) {
                prefs.getSessionId(acc.studentId)?.takeIf {
                    it.isNotBlank() && TronClassAuthApi.isSessionVerified(acc.studentId, it)
                } ?: ensureSessionForAccount(acc.studentId).getOrElse { err ->
                    return MultiAccountRollcallResult(acc.studentId, nameDisplay, false, "畅课授权失败: ${err.message ?: "登录失效"}")
                }
            } else {
                ensureSessionForAccount(acc.studentId).getOrElse { err ->
                    return MultiAccountRollcallResult(acc.studentId, nameDisplay, false, "畅课授权失败: ${err.message ?: "登录失效"}")
                }
            }
            val devId = prefs.getDeviceId(acc.studentId)
            val res = answerQRRollcall(
                rollcallId = rollcallId,
                qrTokenData = qrTokenData,
                customSessionId = sid,
                customDeviceId = devId,
                customAccountKey = acc.studentId
            )
            val s = res.getOrNull()
            return if (s != null) {
                MultiAccountRollcallResult(acc.studentId, nameDisplay, s.success, s.message, s.statusName ?: "")
            } else {
                val errMsg = res.exceptionOrNull()?.message ?: "签到请求失败"
                MultiAccountRollcallResult(acc.studentId, nameDisplay, false, errMsg)
            }
        }

        val primaryResult = submitSingleQrAccount(accounts.first(), fastPathForPrimary = true)
        if (accounts.size == 1) {
            return@withContext listOf(primaryResult)
        }

        val semaphore = Semaphore(3)
        val otherResults = coroutineScope {
            accounts.drop(1).map { acc ->
                async {
                    semaphore.withPermit {
                        submitSingleQrAccount(acc, fastPathForPrimary = false)
                    }
                }
            }.awaitAll()
        }
        listOf(primaryResult) + otherResults
    }

    /**
     * 9.7 一键为所有已保存账号提交【雷达定位】签到
     */
    suspend fun batchAnswerRadarRollcall(
        rollcallId: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Double? = null
    ): List<MultiAccountRollcallResult> = withContext(Dispatchers.IO) {
        val accounts = orderedVaultAccounts()
        if (accounts.isEmpty()) {
            val currId = AuthPreferences(context).getStudentId()
            val single = answerRadarRollcall(rollcallId, latitude, longitude, accuracy)
            val sRes = single.getOrNull()
            return@withContext listOf(
                MultiAccountRollcallResult(
                    studentId = currId,
                    studentName = "当前用户",
                    success = sRes?.success == true,
                    message = sRes?.message ?: "未知结果",
                    statusName = sRes?.statusName ?: ""
                )
            )
        }

        val results = mutableListOf<MultiAccountRollcallResult>()
        for (acc in accounts) {
            val nameDisplay = acc.studentName.ifBlank { acc.studentId }
            val sessionRes = ensureSessionForAccount(acc.studentId)
            val sid = sessionRes.getOrNull()
            if (sid.isNullOrBlank()) {
                val err = sessionRes.exceptionOrNull()?.message ?: "登录失效"
                results.add(MultiAccountRollcallResult(acc.studentId, nameDisplay, false, "畅课授权失败: $err"))
                continue
            }
            val devId = prefs.getDeviceId(acc.studentId)
            val res = answerRadarRollcall(rollcallId, latitude, longitude, accuracy, customSessionId = sid, customDeviceId = devId, customAccountKey = acc.studentId)
            val s = res.getOrNull()
            if (s != null) {
                results.add(MultiAccountRollcallResult(acc.studentId, nameDisplay, s.success, s.message, s.statusName ?: ""))
            } else {
                val errMsg = res.exceptionOrNull()?.message ?: "签到请求失败"
                results.add(MultiAccountRollcallResult(acc.studentId, nameDisplay, false, errMsg))
            }
        }
        results
    }

    /**
     * 9.7 一键为所有已保存账号提交【自主点名】签到
     */
    suspend fun batchAnswerSelfRegistrationRollcall(
        rollcallId: Long
    ): List<MultiAccountRollcallResult> = withContext(Dispatchers.IO) {
        val accounts = orderedVaultAccounts()
        if (accounts.isEmpty()) {
            val currId = AuthPreferences(context).getStudentId()
            val single = answerSelfRegistrationRollcall(rollcallId)
            val sRes = single.getOrNull()
            return@withContext listOf(
                MultiAccountRollcallResult(
                    studentId = currId,
                    studentName = "当前用户",
                    success = sRes?.success == true,
                    message = sRes?.message ?: "未知结果",
                    statusName = sRes?.statusName ?: ""
                )
            )
        }

        val results = mutableListOf<MultiAccountRollcallResult>()
        for (acc in accounts) {
            val nameDisplay = acc.studentName.ifBlank { acc.studentId }
            val sessionRes = ensureSessionForAccount(acc.studentId)
            val sid = sessionRes.getOrNull()
            if (sid.isNullOrBlank()) {
                val err = sessionRes.exceptionOrNull()?.message ?: "登录失效"
                results.add(MultiAccountRollcallResult(acc.studentId, nameDisplay, false, "畅课授权失败: $err"))
                continue
            }
            val devId = prefs.getDeviceId(acc.studentId)
            val res = answerSelfRegistrationRollcall(rollcallId, customSessionId = sid, customDeviceId = devId, customAccountKey = acc.studentId)
            val s = res.getOrNull()
            if (s != null) {
                results.add(MultiAccountRollcallResult(acc.studentId, nameDisplay, s.success, s.message, s.statusName ?: ""))
            } else {
                val errMsg = res.exceptionOrNull()?.message ?: "签到请求失败"
                results.add(MultiAccountRollcallResult(acc.studentId, nameDisplay, false, errMsg))
            }
        }
        results
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
     * 权威花名册二次查验闭环（recheck_on_call_fine）
     * 查询 GET /api/rollcall/{rollcallId}/student_rollcalls，核验本人学号是否已出勤
     */
    suspend fun recheckOnCallFine(
        rollcallId: Long,
        studentId: String,
        customSessionId: String? = null,
        expectedAccount: String? = null
    ): Boolean = verifyRollcallAttendance(
        rollcallId = rollcallId,
        studentId = studentId,
        customSessionId = customSessionId,
        expectedAccount = expectedAccount
    ) == RollcallVerificationState.CONFIRMED

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
