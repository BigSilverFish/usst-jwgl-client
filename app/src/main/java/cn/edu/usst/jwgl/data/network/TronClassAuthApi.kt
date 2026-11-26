package cn.edu.usst.jwgl.data.network

import android.util.Log
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
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
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
import java.util.concurrent.TimeUnit

internal class TronClassAuthApi(
    private val executor: TronClassSessionExecutor
) {
    companion object {
        private const val TAG = "TronClassAuthApi"
        private val verifiedSessions = java.util.concurrent.ConcurrentHashMap<String, String>()

        internal fun markSessionVerified(account: String, sessionId: String) {
            if (account.isNotBlank() && sessionId.isNotBlank()) {
                verifiedSessions[account] = sessionId
            }
        }

        internal fun isSessionVerified(account: String, sessionId: String): Boolean {
            return account.isNotBlank() && sessionId.isNotBlank() && verifiedSessions[account] == sessionId
        }

        internal fun invalidateVerifiedSession(account: String) {
            if (account.isNotBlank()) {
                verifiedSessions.remove(account)
            }
        }

        internal fun isSessionOwnerMatching(expectedStudentId: String, meResponseBody: String): Boolean {
            if (expectedStudentId.isBlank()) return true
            return try {
                val root = com.google.gson.JsonParser.parseString(meResponseBody)
                if (!root.isJsonObject) return false
                val json = root.asJsonObject
                fun optStr(obj: com.google.gson.JsonObject?, key: String): String {
                    val el = obj?.get(key) ?: return ""
                    return if (el.isJsonPrimitive) el.asString.trim() else ""
                }
                val userObj = json.get("user")?.takeIf { it.isJsonObject }?.asJsonObject
                val actualUserNo = listOf(
                    optStr(json, "user_no"),
                    optStr(json, "student_no"),
                    optStr(userObj, "user_no"),
                    optStr(userObj, "student_no")
                ).firstOrNull { it.isNotBlank() && it != "null" } ?: ""
                if (actualUserNo.isBlank()) true
                else actualUserNo.equals(expectedStudentId.trim(), ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }
    }

    private val context get() = executor.context
    private val prefs get() = executor.prefs
    private val okHttpClient get() = executor.okHttpClient
    private val authHttpClient get() = executor.authHttpClient

    private fun accountKey() = executor.accountKey()
    private suspend fun executeRequest(request: Request, expectedAccount: String? = null) = executor.executeRequest(request, expectedAccount)
    private suspend fun refreshSessionOnce(account: String, previousSession: String?) = executor.refreshSessionOnce(account, previousSession)
    private fun getOAuthAuthUrl() = TronClassClient.getOAuthAuthUrl()

    suspend fun loginWithAuthCode(authCode: String, studentId: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Exchanging auth code for tokens (studentId=$studentId)...")
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

            val tokenResp = authHttpClient.newCall(tokenReq).awaitBuffered().response(tokenReq)
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

            exchangeAccessTokenForSession(accessToken, refreshToken, studentId)
        } catch (e: Exception) {
            Log.e(TAG, "loginWithAuthCode failed", e)
            Result.failure(e)
        }
    }

    /**
     * 2. 将 Access Token 提交至 TronClass 换取真正的 sessionID
     */
    suspend fun exchangeAccessTokenForSession(
        accessToken: String,
        refreshToken: String? = null,
        studentId: String? = null
    ): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Exchanging access token for TronClass session (studentId=$studentId)...")
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

                val resp = authHttpClient.newCall(req).awaitBuffered().response(req)
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
                val serverUserNo = if (json.has("user_no")) json.optString("user_no", "") else (userObj?.optString("user_no", "") ?: "")
                val userEmail = if (json.has("email")) json.optString("email", "") else (userObj?.optString("email", "") ?: "")

                if (false) {
                // 切勿在查明 /api/users/me 前预先用目标 studentId 覆盖真实 user_no
                }

                // 若基础信息不完整，尝试从 /api/users/me 获取详情补充
                var finalUserId = userId
                var finalUserName = userName
                var finalUserNo = serverUserNo.takeIf { it.isNotBlank() && it != "null" } ?: ""
                var finalUserEmail = userEmail
                if (finalUserNo.isBlank() || finalUserId == 0L || finalUserName.isBlank()) {
                    try {
                        val meReq = Request.Builder()
                            .url("$BASE_URL/api/users/me")
                            .header("Accept", "application/json")
                            .header("x-session-id", sessionId)
                            .header("Cookie", "session_id=$sessionId")
                            .build()
                        val meResp = authHttpClient.newCall(meReq).awaitBuffered().response(meReq)
                        if (meResp.isSuccessful) {
                            val meJson = JSONObject(meResp.body?.string() ?: "")
                            if (finalUserId == 0L) finalUserId = meJson.optLong("id", 0L)
                            if (finalUserName.isBlank()) finalUserName = meJson.optString("name", "")
                            if (finalUserNo.isBlank()) finalUserNo = meJson.optString("user_no", "").takeIf { it != "null" } ?: ""
                            if (finalUserEmail.isBlank()) finalUserEmail = meJson.optString("email", "")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch user profile via /users/me", e)
                    }
                }

                if (!studentId.isNullOrBlank() && finalUserNo.isNotBlank() &&
                    !finalUserNo.trim().equals(studentId.trim(), ignoreCase = true)
                ) {
                    Log.w(TAG, "Session identity mismatch during exchange: expected=$studentId, actual=$finalUserNo")
                    return@withContext Result.failure(Exception("畅课返回会话身份与目标学号不匹配"))
                }

                if (finalUserNo.isBlank()) {
                    finalUserNo = if (!studentId.isNullOrBlank()) studentId else AuthPreferences(context).getStudentId().trim()
                }

                prefs.saveSession(
                    sessionId = sessionId,
                    refreshToken = refreshToken,
                    userId = finalUserId,
                    userName = if (finalUserName.isNotBlank()) finalUserName else null,
                    userNo = if (finalUserNo.isNotBlank()) finalUserNo else null,
                    userEmail = if (finalUserEmail.isNotBlank()) finalUserEmail else null,
                    studentId = studentId
                )

                val verifiedOwner = studentId?.takeIf { it.isNotBlank() } ?: finalUserNo
                markSessionVerified(verifiedOwner, sessionId)
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
    suspend fun refreshSession(studentId: String? = null): Result<Boolean> {
        val account = studentId ?: accountKey()
        return refreshSessionOnce(account, prefs.getSessionId(account))
    }

    suspend fun refreshSessionInternal(studentId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val refreshToken = prefs.getRefreshToken(studentId)
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

            val tokenResp = authHttpClient.newCall(tokenReq).awaitBuffered().response(tokenReq)
            val tokenBody = tokenResp.body?.string() ?: ""
            if (!tokenResp.isSuccessful) {
                Log.w(TAG, "Refresh token failed: ${tokenResp.code} $tokenBody")
                return@withContext Result.failure(Exception("凭证刷新失败"))
            }

            val tokenJson = JSONObject(tokenBody)
            val newAccessToken = tokenJson.optString("access_token")
            val newRefreshToken = tokenJson.optString("refresh_token", refreshToken)

            exchangeAccessTokenForSession(newAccessToken, newRefreshToken, studentId)
        } catch (e: Exception) {
            Log.e(TAG, "refreshSession failed", e)
            Result.failure(e)
        }
    }

    /**
     * 4. 获取待办作业与测验列表 (/api/todos)
     */

    suspend fun silentLoginTronClass(studentId: String, password: String): Result<Boolean> = withContext(Dispatchers.IO) {
        if (studentId.isBlank() || password.isBlank()) {
            return@withContext Result.failure(Exception("学号或密码为空"))
        }
        try {
            Log.i(TAG, "Starting silent TronClass SSO login for $studentId...")
            val cookieStore = java.util.concurrent.ConcurrentHashMap<String, String>()
            val tempClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .cookieJar(object : okhttp3.CookieJar {
                    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                        for (c in cookies) {
                            cookieStore["${c.domain}:${c.name}"] = c.value
                        }
                    }
                    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                        val list = mutableListOf<okhttp3.Cookie>()
                        for ((k, v) in cookieStore) {
                            val (domain, name) = k.split(":", limit = 2)
                            if (url.host.contains(domain) || domain.contains(url.host)) {
                                list.add(okhttp3.Cookie.Builder().domain(url.host).name(name).value(v).build())
                            }
                        }
                        return list
                    }
                })
                .build()

            // 1. 发起 OAuth 授权请求
            val authUrl = getOAuthAuthUrl()
            val initReq = Request.Builder()
                .url(authUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 16; TronClass/2.16.1)")
                .get()
                .build()

            val initResp = tempClient.newCall(initReq).execute()
            val finalUrl = initResp.request.url.toString()
            val html = initResp.body?.string() ?: ""

            var authCode = initResp.request.url.queryParameter("code")

            // 2. 如果重定向到了 CAS 统一身份认证登录页，解析隐藏表单并提交账号密码
            if (authCode.isNullOrBlank() && finalUrl.contains("authserver/login")) {
                val doc = org.jsoup.Jsoup.parse(html)
                val lt = doc.select("input[name=lt]").`val`()
                val execution = doc.select("input[name=execution]").`val`()
                val eventId = doc.select("input[name=_eventId]").`val`().ifEmpty { "submit" }

                if (lt.isEmpty() || execution.isEmpty()) {
                    return@withContext Result.failure(Exception("无法解析统一认证 CAS 令牌"))
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

                val loginReq = Request.Builder()
                    .url(finalUrl)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 16; TronClass/2.16.1)")
                    .header("Referer", finalUrl)
                    .post(formBody)
                    .build()

                val loginResp = tempClient.newCall(loginReq).execute()
                val afterLoginUrl = loginResp.request.url.toString()
                val afterHtml = loginResp.body?.string() ?: ""

                authCode = loginResp.request.url.queryParameter("code")

                if (authCode.isNullOrBlank() && afterLoginUrl.contains("authserver/login")) {
                    val errDoc = org.jsoup.Jsoup.parse(afterHtml)
                    val errMsg = errDoc.select("#showErrorTip, .auth_error, #msg, .error-message").text().trim()
                    return@withContext Result.failure(Exception(errMsg.ifBlank { "统一认证账号或密码错误" }))
                }
            }

            if (authCode.isNullOrBlank()) {
                return@withContext Result.failure(Exception("未能截获一网畅学 OAuth 授权码，请确认网络状态"))
            }

            // 3. 换取 Token 并保存该账号独立的 Session
            loginWithAuthCode(authCode, studentId = studentId)
        } catch (e: Exception) {
            Log.e(TAG, "silentLoginTronClass error for $studentId", e)
            Result.failure(e)
        }
    }

    /**
     * 9.4 确保指定账号拥有有效的一网畅学 Session
     * 策略：本地有效Session探活 -> RefreshToken静默刷新 -> 硬件解密密码全静默SSO登录
     */
    suspend fun ensureSessionForAccount(studentId: String): Result<String> = withContext(Dispatchers.IO) {
        val sid = prefs.getSessionId(studentId)
        if (!sid.isNullOrBlank()) {
            try {
                val testReq = Request.Builder()
                    .url("$BASE_URL/api/users/me")
                    .header("Accept", "application/json")
                    .header("x-session-id", sid)
                    .header("Cookie", "session_id=$sid")
                    .get()
                    .build()
                val testResp = authHttpClient.newCall(testReq).awaitBuffered().response(testReq)
                val isOk = testResp.isSuccessful
                val body = testResp.body?.string().orEmpty()
                testResp.close()
                if (isOk) {
                    if (isSessionOwnerMatching(studentId, body)) {
                        markSessionVerified(studentId, sid)
                        return@withContext Result.success(sid)
                    } else {
                        Log.w(TAG, "Session owner mismatch for $studentId, clearing polluted session")
                        invalidateVerifiedSession(studentId)
                        prefs.setSessionId(null, studentId)
                    }
                }
            } catch (_: Exception) {}
        }

        // 尝试使用 RefreshToken 刷新
        val rf = prefs.getRefreshToken(studentId)
        if (!rf.isNullOrBlank()) {
            val refreshRes = refreshSession(studentId)
            if (refreshRes.isSuccess) {
                val refreshedSid = prefs.getSessionId(studentId)
                if (!refreshedSid.isNullOrBlank()) {
                    return@withContext Result.success(refreshedSid)
                }
            }
        }

        // 尝试使用保险箱中的密码进行全自动静默登录
        val vault = cn.edu.usst.jwgl.data.local.AccountVault.getInstance(context)
        val pwd = vault.getAccountPassword(studentId)
        if (pwd.isNotBlank()) {
            val loginRes = silentLoginTronClass(studentId, pwd)
            if (loginRes.isSuccess) {
                val newSid = prefs.getSessionId(studentId)
                if (!newSid.isNullOrBlank()) {
                    return@withContext Result.success(newSid)
                }
            } else {
                return@withContext Result.failure(loginRes.exceptionOrNull() ?: Exception("静默登录失败"))
            }
        }

        Result.failure(Exception("账号未保存有效畅课凭据且未保存密码"))
    }

    /**
     * 9.5 一键为所有已保存账号提交【数字口令】签到
     */
}
