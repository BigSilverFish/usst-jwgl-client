package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.util.Base64
import android.util.Log
import okio.ByteString.Companion.toByteString
import com.google.gson.Gson
import cn.edu.usst.jwgl.data.local.AccountVault
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.local.RollcallSubmissionStore
import cn.edu.usst.jwgl.data.model.*
import cn.edu.usst.jwgl.util.ChaoXingRiskControlException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 超星学习通 (Chaoxing / Fanya) 核心网络客户端
 *
 * 核心技术实现：
 * 1. 机构 SSO 直连：基于上理机构代码 fid=207，采用与官方学习通一致的 AES-128-CBC 加密规范换票；
 * 2. 账号安全联动：与系统底层的硬件加密保险箱 AccountVault 无缝桥接，实现零人工感知的全静默单点登录；
 * 3. 动态会话探活：实时监控会话状态，失效自动恢复；
 * 4. 纯原生无依赖：基于 OkHttp 与 Android 原生 javax.crypto，零体积膨胀，极致轻量。
 */
class ChaoXingClient(private val context: Context) {

    private val appContext = context.applicationContext
    private val prefs = ChaoXingPreferences(appContext)
    private val gson = Gson()
    internal class CookieOwner(val account: String, val persistResponse: Boolean = true) {
        var acceptedCookies: String? = null
    }
    private fun Request.Builder.forAccount(account: String, persistResponse: Boolean = true) =
        tag(CookieOwner::class.java, CookieOwner(account, persistResponse))

    companion object {
        private const val TAG = "ChaoXingClient"
        private const val MAX_DDL_PROBE_COUNT = 3
        private val sessionRequests = SharedRequest<Result<Boolean>>(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        )
        private val verifiedSessions = SessionValidationWindow(now = { android.os.SystemClock.elapsedRealtime() })
        @Volatile
        private var sharedOkHttpClient: OkHttpClient? = null

        private fun getOrCreateSharedClient(appContext: Context): OkHttpClient {
            return sharedOkHttpClient ?: synchronized(this) {
                sharedOkHttpClient ?: buildSharedClient(appContext).also { sharedOkHttpClient = it }
            }
        }

        private fun buildSharedClient(appContext: Context): OkHttpClient {
            val sharedPrefs = ChaoXingPreferences(appContext)
            val dispatcher = okhttp3.Dispatcher().apply {
                maxRequests = 4
                maxRequestsPerHost = 2
            }
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .addInterceptor { chain ->
                    val orig = chain.request()
                    val owner = orig.tag(CookieOwner::class.java) ?: CookieOwner(AuthPreferences(appContext).getStudentId())
                    val withDefaults = ChaoXingRequestGuard.applyDefaultHeaders(orig)
                    val reqBuilder = withDefaults.newBuilder()
                        .tag(CookieOwner::class.java, owner)

                    val host = orig.url.host.lowercase()
                    val isChaoXing = ChaoXingRequestGuard.isChaoXingHost(host)

                    if (isChaoXing) {
                        ChaoXingRequestGuard.checkCircuitOrThrow(appContext)
                        ChaoXingRequestGuard.awaitPacingSlot()
                        if (owner.persistResponse && owner.account.isNotBlank()) {
                            val cookies = sharedPrefs.getCookies(owner.account)
                            if (!cookies.isNullOrBlank() && orig.header("Cookie") == null) {
                                reqBuilder.header("Cookie", cookies)
                            }
                        }
                    }

                    val request = reqBuilder.build()
                    val expectedCookies = request.header("Cookie")
                    val resp = chain.proceed(request)

                    if (isChaoXing) {
                        val riskSignal = ChaoXingRequestGuard.inspectResponseForRisk(resp)
                        if (riskSignal != null) {
                            val until = ChaoXingRequestGuard.tripCircuit(appContext, riskSignal)
                            resp.close()
                            throw ChaoXingRiskControlException(
                                until,
                                ChaoXingRequestGuard.buildCooldownMessage(until, riskSignal.reason)
                            )
                        }
                    }

                    // 监听响应中的 Set-Cookie 并智能合并
                    val responseHost = resp.request.url.host.lowercase()
                    if (isChaoXing && owner.persistResponse && !chain.call().isCanceled() && resp.isSuccessful &&
                        ChaoXingRequestGuard.isChaoXingHost(responseHost)) {
                        val setCookies = resp.headers("Set-Cookie")
                        if (setCookies.isNotEmpty()) {
                            owner.acceptedCookies = mergeAndSaveCookiesInternal(sharedPrefs, setCookies, owner.account, expectedCookies)
                        }
                    }

                    resp
                }
                .build()
        }

        private fun mergeAndSaveCookiesInternal(
            sharedPrefs: ChaoXingPreferences,
            newCookies: List<String>,
            account: String,
            expectedCookies: String?
        ): String? = synchronized(ChaoXingPreferences.sessionLock) {
            if (account.isBlank() || expectedCookies.isNullOrBlank() || sharedPrefs.getCookies(account) != expectedCookies) return@synchronized null
            val currentUid = sharedPrefs.getUid(account).orEmpty()
            if (currentUid.isBlank()) return@synchronized null
            val cookieMap = LinkedHashMap<String, String>()

            val pairs = expectedCookies.split(";")
            for (p in pairs) {
                val trimmed = p.trim()
                val idx = trimmed.indexOf('=')
                if (idx > 0) {
                    cookieMap[trimmed.substring(0, idx).trim()] = trimmed.substring(idx + 1).trim()
                }
            }

            var newUid: String? = null
            for (item in newCookies) {
                val part = item.split(";")[0].trim()
                val idx = part.indexOf('=')
                if (idx > 0) {
                    val k = part.substring(0, idx).trim()
                    val v = part.substring(idx + 1).trim()
                    cookieMap[k] = v
                    if (k.equals("UID", ignoreCase = true) || k.equals("_uid", ignoreCase = true)) {
                        newUid = v
                    }
                }
            }

            val merged = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (!newUid.isNullOrBlank() && newUid != currentUid) return@synchronized null

            sharedPrefs.saveLoginSession(
                cookies = merged,
                uid = if (!newUid.isNullOrBlank()) newUid else currentUid,
                studentNo = account,
                schoolId = USST_FID,
                schoolName = USST_SCHOOL_NAME
            )
            merged
        }

        // 超星常用端点
        const val USST_FID = "207"
        const val USST_SCHOOL_NAME = "上海理工大学"
        const val PASSPORT_URL = "https://passport2.chaoxing.com"
        const val FANYA_LOGIN_URL = "$PASSPORT_URL/fanyalogin"
        const val COURSE_DATA_URL = "https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1"
        const val USST_FANYA_URL = "https://usst.fanya.chaoxing.com"
        const val NOTICE_LIST_URL = "https://notice.chaoxing.com/pc/notice/getNoticeList"
        const val STU_WORK_URL = "https://mooc1-api.chaoxing.com/work/stu-work"
        const val ACTIVE_LIST_URL = "https://mobilelearn.chaoxing.com/v2/apis/active/student/activelist"
        const val PRE_SIGN_URL = "https://mobilelearn.chaoxing.com/newsign/preSign"
        const val SUBMIT_SIGN_URL = "https://mobilelearn.chaoxing.com/pptSign/stuSignajax"

        // 超星官方 AES-128-CBC 密钥与初始向量
        private const val AES_KEY_STRING = "u2oh6Vu^HWe4_AES"

        /**
         * 解析各种可能格式的超星作业截止时间
         */
        fun parseDdlMillis(timeStr: String?): Long? {
            if (timeStr.isNullOrBlank()) return null
            val raw = ChaoXingCourseDetailClient.cleanDeadline(timeStr)
            if (raw.isBlank()) return null

            // 1. 优先解析相对倒计时格式 (如 "剩余19小时23分钟", "剩 1 天 2 小时", "剩 45 分钟")
            val relativeMatch = Regex("""(?:剩余|剩)\s*(?:(\d+)\s*天)?\s*(?:(\d+)\s*小时)?\s*(?:(\d+)\s*分(?:钟)?)?""").find(raw)
            if (relativeMatch != null) {
                val dStr = relativeMatch.groupValues[1]
                val hStr = relativeMatch.groupValues[2]
                val mStr = relativeMatch.groupValues[3]
                if (dStr.isNotEmpty() || hStr.isNotEmpty() || mStr.isNotEmpty()) {
                    val days = dStr.toLongOrNull() ?: 0L
                    val hours = hStr.toLongOrNull() ?: 0L
                    val minutes = mStr.toLongOrNull() ?: 0L
                    val totalMs = (days * 24 * 3600 + hours * 3600 + minutes * 60) * 1000L
                    if (totalMs > 0) {
                        return System.currentTimeMillis() + totalMs
                    }
                }
            }

            val hasYear = raw.matches(Regex("""^\d{4}[-/].*"""))
            val formats = if (hasYear) {
                listOf(
                    SimpleDateFormat("yyyy-M-d H:m:s", Locale.CHINA),
                    SimpleDateFormat("yyyy-M-d H:m", Locale.CHINA),
                    SimpleDateFormat("yyyy-M-d", Locale.CHINA),
                    SimpleDateFormat("yyyy/M/d H:m:s", Locale.CHINA),
                    SimpleDateFormat("yyyy/M/d H:m", Locale.CHINA),
                    SimpleDateFormat("yyyy/M/d", Locale.CHINA)
                )
            } else {
                listOf(
                    SimpleDateFormat("M-d H:m:s", Locale.CHINA),
                    SimpleDateFormat("M-d H:m", Locale.CHINA),
                    SimpleDateFormat("M-d", Locale.CHINA),
                    SimpleDateFormat("M/d H:m", Locale.CHINA),
                    SimpleDateFormat("M/d", Locale.CHINA)
                )
            }

            for (sdf in formats) {
                try {
                    val d = sdf.parse(raw) ?: continue
                    val cal = java.util.Calendar.getInstance()
                    val curYear = cal.get(java.util.Calendar.YEAR)
                    val curMonth = cal.get(java.util.Calendar.MONTH) // 0..11

                    val parsedCal = java.util.Calendar.getInstance().apply { time = d }
                    val parsedMonth = parsedCal.get(java.util.Calendar.MONTH)

                    var targetYear = curYear
                    if (!hasYear) {
                        if (curMonth == 11 && parsedMonth == 0) {
                            targetYear += 1 // 12月看明年1月的作业
                        } else if (curMonth == 0 && parsedMonth == 11) {
                            targetYear -= 1 // 1月看去年12月的作业
                        }
                        parsedCal.set(java.util.Calendar.YEAR, targetYear)
                    }
                    if (!raw.contains(":")) {
                        parsedCal.set(java.util.Calendar.HOUR_OF_DAY, 23)
                        parsedCal.set(java.util.Calendar.MINUTE, 59)
                        parsedCal.set(java.util.Calendar.SECOND, 59)
                        parsedCal.set(java.util.Calendar.MILLISECOND, 999)
                    }
                    return parsedCal.timeInMillis
                } catch (_: Exception) {}
            }
            return null
        }

        /**
         * 超星客户端用户名与密码加密算法 (AES-128-CBC, PKCS5Padding, Base64)
         */
        fun encryptAes(content: String): String {
            try {
                val keyBytes = AES_KEY_STRING.toByteArray(StandardCharsets.UTF_8)
                val secretKey = SecretKeySpec(keyBytes, "AES")
                val ivSpec = IvParameterSpec(keyBytes)

                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)

                val encryptedBytes = cipher.doFinal(content.toByteArray(StandardCharsets.UTF_8))
                return encodeBase64Compat(encryptedBytes)
            } catch (e: Exception) {
                Log.e(TAG, "encryptAes error", e)
                throw RuntimeException("超星凭据加密失败", e)
            }
        }

        private fun encodeBase64Compat(bytes: ByteArray): String {
            return try {
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } catch (_: Throwable) {
                // JVM 单元测试环境 fallback
                bytes.toByteString().base64()
            }
        }

        internal fun hasHiddenPhotoFlag(preHtml: String): Boolean =
            Regex("""<input[^>]*id=["']ifPhoto["'][^>]*value=["']1["']|<input[^>]*value=["']1["'][^>]*id=["']ifPhoto["']""")
                .containsMatchIn(preHtml)

        /**
         * 综合结构化字段、名称提示、预签到隐藏表单标志、最终 URL 与可见正文判定签到类型，
         * 避免直接扫描原始 HTML 脚本模板中的 "photo" 导致扫码/二维码签到被误判为拍照签到。
         */
        internal fun resolveSignTypeDisplayName(
            otherId: Int,
            initialHintName: String = "",
            ifPhoto: Boolean = false,
            preHtml: String = "",
            signCode: String? = null,
            cleanBody: String = "",
            finalUrl: String = "",
            locationText: String? = null,
            lat: Double? = null
        ): String {
            val isPhotoByConfig = ifPhoto || hasHiddenPhotoFlag(preHtml)

            return when {
                otherId == 2 || initialHintName.contains("二维码") || initialHintName.contains("扫码") -> "二维码签到"
                otherId == 3 || initialHintName.contains("手势") -> "手势签到"
                otherId == 4 || initialHintName.contains("位置") -> "位置签到"
                isPhotoByConfig || initialHintName.contains("拍照") -> "拍照签到"
                !signCode.isNullOrBlank() || initialHintName.contains("签到码") || initialHintName.contains("口令") -> "口令签到"
                otherId == 5 -> {
                    if (cleanBody.contains("拍照") || finalUrl.contains("photo", ignoreCase = true)) "拍照签到" else "口令签到"
                }
                finalUrl.contains("gesture", ignoreCase = true) || cleanBody.contains("手势") -> "手势签到"
                finalUrl.contains("qr", ignoreCase = true) || cleanBody.contains("二维码") || cleanBody.contains("扫码") -> "二维码签到"
                finalUrl.contains("pcForCode", ignoreCase = true) || cleanBody.contains("签到码") || cleanBody.contains("口令") -> "口令签到"
                !locationText.isNullOrBlank() || lat != null || finalUrl.contains("location", ignoreCase = true) || cleanBody.contains("位置签到") || cleanBody.contains("获取位置") -> "位置签到"
                finalUrl.contains("photo", ignoreCase = true) || cleanBody.contains("拍照") || cleanBody.contains("上传照片") -> "拍照签到"
                else -> "普通签到"
            }
        }
    }

    private val okHttpClient: OkHttpClient = getOrCreateSharedClient(appContext)

    private val submissionClient = okHttpClient.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .addNetworkInterceptor(SingleNetworkAttemptInterceptor)
        .build()

    fun isLoggedIn(): Boolean = prefs.isLoggedIn()

    fun getUserInfo(): ChaoXingUserInfo = prefs.getUserInfo()

    fun getOkHttpClient(): OkHttpClient = okHttpClient

    /**
     * 1. 机构账号直连极速登录 (上海理工大学 fid=207)
     */
    suspend fun loginWithInstitution(
        studentId: String,
        password: String,
        schoolId: String = USST_FID
    ): Result<ChaoXingLoginResponse> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initiating ChaoXing institutional login for $studentId (schoolId=$schoolId)...")

            val encUname = encryptAes(studentId.trim())
            val encPwd = encryptAes(password.trim())

            val formBody = FormBody.Builder()
                .add("fid", schoolId)
                .add("uname", encUname)
                .add("password", encPwd)
                .add("refer", "https://i.chaoxing.com")
                .add("t", "true")
                .add("forbidotherlogin", "0")
                .add("validate", "")
                .add("doubleFactorLogin", "0")
                .add("independentId", "0")
                .build()

            val req = Request.Builder()
                .url(FANYA_LOGIN_URL)
                .forAccount(studentId, persistResponse = false)
                .post(formBody)
                .header("Host", "passport2.chaoxing.com")
                .header("Referer", "$PASSPORT_URL/login.jsp?fid=$schoolId")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                Log.e(TAG, "ChaoXing login request failed with code: ${resp.code}, body: $body")
                return@withContext Result.failure(Exception("超星网络连接异常 (${resp.code})"))
            }

            Log.d(TAG, "ChaoXing login response: $body")

            val loginResp = try {
                gson.fromJson(body, ChaoXingLoginResponse::class.java)
            } catch (e: Exception) {
                // 部分旧版本返回非标准格式时做兼容解析
                val json = JSONObject(body)
                ChaoXingLoginResponse(
                    status = json.optBoolean("status", false),
                    msg = json.optString("msg2").ifEmpty { json.optString("msg") },
                    mes = json.optString("mes"),
                    url = json.optString("url")
                )
            }

            if (loginResp.status) {
                // 收集 Set-Cookie 中的 UID 或从参数提取
                val cookies = resp.headers("Set-Cookie")
                var uid = ""
                for (c in cookies) {
                    val p = c.split(";")[0]
                    if (p.startsWith("UID=") || p.startsWith("_uid=")) {
                        uid = p.substringAfter("=")
                        break
                    }
                }

                // 提取拼接 Cookie
                val cookieMap = LinkedHashMap<String, String>()
                for (item in cookies) {
                    val part = item.split(";")[0].trim()
                    val idx = part.indexOf('=')
                    if (idx > 0) {
                        cookieMap[part.substring(0, idx).trim()] = part.substring(idx + 1).trim()
                    }
                }
                val fullCookies = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }

                prefs.saveLoginSession(
                    cookies = fullCookies,
                    uid = uid,
                    studentNo = studentId,
                    schoolId = schoolId,
                    schoolName = USST_SCHOOL_NAME
                )

                Log.i(TAG, "ChaoXing login successful for $studentId, uid: $uid")
                Result.success(loginResp)
            } else {
                val errorMsg = loginResp.msg?.ifBlank { null }
                    ?: loginResp.mes?.ifBlank { null }
                    ?: "超星登录失败，请核对学号或密码"
                Log.w(TAG, "ChaoXing login failed: $errorMsg")
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "loginWithInstitution unexpected error", e)
            Result.failure(e)
        }
    }

    /**
     * 2.1 基于上海理工大学统一身份认证 (CAS SSO) 换票登录 (参考 extrisha/litang)
     * 利用教务已有 CASTGC 或保存的学校账号密码，走 ids6 -> tyrzfw 跳转链直接换取超星登录态，无需单独输入超星密码
     */
    suspend fun loginWithCasSso(
        forceEntry: Boolean = false,
        targetStudentId: String? = null
    ): Result<ChaoXingLoginResponse> = withContext(Dispatchers.IO) {
        val ssoResult = ChaoXingSsoClient.loginWithCas(context, forceEntry, targetStudentId)
        if (ssoResult.isSuccess) {
            Result.success(
                ChaoXingLoginResponse(
                    status = true,
                    msg = "SSO登录成功",
                    url = "https://i.chaoxing.com"
                )
            )
        } else {
            Result.failure(ssoResult.exceptionOrNull() ?: Exception("超星统一身份认证失败"))
        }
    }

    /**
     * 2. 全静默单点登录 (优先走 USST CAS SSO，失败时自动联动 AccountVault 解密本地密码换票)
     */
    suspend fun silentLoginChaoXing(studentId: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ChaoXingRequestGuard.checkCircuitOrThrow(appContext)
        } catch (e: ChaoXingRiskControlException) {
            return@withContext Result.failure(e)
        }

        val targetSid = if (!studentId.isNullOrBlank()) studentId.trim() else {
            try {
                AuthPreferences(context).getStudentId().trim()
            } catch (_: Exception) {
                ""
            }
        }

        if (targetSid.isBlank()) {
            return@withContext Result.failure(Exception("无法获取当前学生学号"))
        }

        // 1. 优先通过学校统一身份认证 (CAS SSO) 换票登录，显式绑定目标学号
        val ssoRes = loginWithCasSso(targetStudentId = targetSid)
        if (ssoRes.isSuccess) {
            Log.i(TAG, "silentLoginChaoXing succeeded via USST CAS SSO for $targetSid")
            return@withContext Result.success(true)
        }
        Log.w(TAG, "silentLoginChaoXing: CAS SSO failed, trying fallback to institutional password login...", ssoRes.exceptionOrNull())

        val pwd = try {
            AccountVault.getInstance(context).getAccountPassword(targetSid)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve account password from vault for $targetSid", e)
            ""
        }

        if (pwd.isBlank()) {
            return@withContext Result.failure(Exception("本地安全保险箱未存储此账号凭证"))
        }

        val loginRes = loginWithInstitution(targetSid, pwd, USST_FID)
        if (loginRes.isSuccess) {
            Result.success(true)
        } else {
            Result.failure(loginRes.exceptionOrNull() ?: Exception("超星静默登录失败"))
        }
    }

    /**
     * 3. 会话有效性探活检测 (基于 backclazzdata 课程数据接口)
     */
    suspend fun checkSessionValid(studentId: String? = null): Boolean = withContext(Dispatchers.IO) {
        val account = studentId ?: AuthPreferences(context).getStudentId()
        val probe = probeSession(account).getOrNull()
        probe?.valid == true && probe.cookies == prefs.getCookies(account)
    }

    private data class SessionProbe(val valid: Boolean, val cookies: String)

    private suspend fun probeSession(account: String): Result<SessionProbe> = withContext(Dispatchers.IO) {
        val cookies = prefs.getCookies(account)
        if (account.isBlank() || cookies.isNullOrBlank()) return@withContext Result.failure(Exception("缺少明确的学习通账号或会话"))

        try {
            val owner = CookieOwner(account)
            val req = Request.Builder()
                .url(COURSE_DATA_URL)
                .tag(CookieOwner::class.java, owner)
                .header("Cookie", cookies)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .get()
                .build()

            val resp = okHttpClient.newCall(req).awaitBuffered().response(req)
            val body = resp.body?.string() ?: ""
            val checkedCookies = owner.acceptedCookies ?: cookies

            if (resp.code == 401 || resp.code == 403) return@withContext Result.success(SessionProbe(false, checkedCookies))
            if (!resp.isSuccessful || body.isBlank()) {
                return@withContext Result.failure(java.io.IOException("学习通会话探活暂时失败 (${resp.code})"))
            }

            // 若返回未登录页面或跳转 html
            if (body.contains("login") && (body.contains("<html") || body.contains("<!DOCTYPE"))) {
                return@withContext Result.success(SessionProbe(false, checkedCookies))
            }

            // A failed or unknown schema must not become a verified session or force an unrelated login.
            val json = JSONObject(body)
            val result = json.optInt("result", -1)
            when {
                result == 1 && json.optJSONArray("channelList") != null -> Result.success(SessionProbe(true, checkedCookies))
                result == 0 -> Result.success(SessionProbe(false, checkedCookies))
                else -> Result.failure(Exception("学习通探活响应格式无法识别，已保留原会话"))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "checkSessionValid failed", e)
            Result.failure(e)
        }
    }

    /**
     * 4. 确保当前账号拥有有效的超星 Session
     * 策略：本地有效Session探活 -> 失败则按需静默重登恢复
     */
    suspend fun ensureSession(
        studentId: String? = null,
        allowSilentLogin: Boolean = true
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            ChaoXingRequestGuard.checkCircuitOrThrow(appContext)
        } catch (e: ChaoXingRiskControlException) {
            return@withContext Result.failure(e)
        }
        val sid = studentId ?: prefs.getStudentNo().ifBlank {
            try {
                AuthPreferences(context).getStudentId()
            } catch (_: Exception) {
                ""
            }
        }
        if (sid.isBlank()) return@withContext Result.failure(Exception("请先选择学习通账号"))

        val cookies = prefs.getCookies(sid)
        sessionRequests.await("$sid/$cookies/$allowSilentLogin") {
            ensureSessionForAccount(sid, allowSilentLogin)
        }
    }

    private suspend fun ensureSessionForAccount(sid: String, allowSilentLogin: Boolean): Result<Boolean> {
        try {
            ChaoXingRequestGuard.checkCircuitOrThrow(appContext)
        } catch (e: ChaoXingRiskControlException) {
            return Result.failure(e)
        }
        var failedCookies = prefs.getCookies(sid)
        // 1. 同一账号和 Cookie 的成功探活在短期内复用。
        if (prefs.isLoggedIn(sid)) {
            val lastTime = prefs.getLastLoginTime(sid)
            val isRecent = System.currentTimeMillis() - lastTime in 0 until 15 * 60_000L
            val cookies = prefs.getCookies(sid)
            if (isRecent || verifiedSessions.contains(sid, cookies)) {
                return Result.success(true)
            }
            val probe = probeSession(sid).getOrElse { return Result.failure(it) }
            if (probe.cookies != prefs.getCookies(sid)) return Result.failure(Exception("学习通会话已更新，请重新读取"))
            failedCookies = probe.cookies
            if (probe.valid) {
                verifiedSessions.record(sid, probe.cookies)
                return Result.success(true)
            }
        }

        if (!allowSilentLogin) {
            return Result.failure(Exception("超星学习通会话未登录或已过期"))
        }

        // 2. 会话无效或已过期，触发静默登录
        if (sid != AuthPreferences(context).getStudentId()) return Result.failure(Exception("账号已切换，请为目标账号重新授权"))
        Log.i(TAG, "ChaoXing session expired or missing for $sid, executing silent login...")
        val silentRes = silentLoginChaoXing(sid)
        if (silentRes.isSuccess) {
            val refreshedCookies = prefs.getCookies(sid)
            if (refreshedCookies.isNullOrBlank() || refreshedCookies == failedCookies) {
                return Result.failure(Exception("尚未确认目标账号的新会话，请重新授权"))
            }
            verifiedSessions.record(sid, refreshedCookies)
            return Result.success(true)
        }

        // 3. 静默登录失败处理：区分临时网络抖动与凭证失效
        val ex = silentRes.exceptionOrNull()
        val isNetworkError = ex is java.io.IOException || ex?.message?.contains("connection closed", ignoreCase = true) == true
        if (isNetworkError && prefs.isLoggedIn(sid)) {
            Log.w(TAG, "ChaoXing silent login encountered network issue, retaining existing session and cached data.")
            return Result.failure(ex ?: java.io.IOException("学习通授权暂时失败"))
        }

        // 仅在明确凭证失效且非网络中断时清理 Session
        if (!isNetworkError) synchronized(ChaoXingPreferences.sessionLock) {
            if (prefs.getCookies(sid) == failedCookies) {
                Log.w(TAG, "ChaoXing credentials rejected. Clearing session state for $sid.")
                prefs.clearSession(sid)
            }
        }
        return Result.failure(ex ?: Exception("超星学习通未登录"))
    }

    /**
     * 5. 获取当前学期超星在修课程列表 (拉取与清洗)
     */
    suspend fun fetchCourses(studentId: String? = null): Result<List<ChaoXingCourse>> = withContext(Dispatchers.IO) {
        val account = studentId ?: AuthPreferences(context).getStudentId()
        val ensureRes = ensureSession(account)
        if (ensureRes.isFailure) {
            return@withContext Result.failure(ensureRes.exceptionOrNull() ?: Exception("超星登录状态失效"))
        }

        val cookies = prefs.getCookies(account) ?: return@withContext Result.failure(Exception("Cookie 为空"))

        try {
            val req = Request.Builder()
                .url(COURSE_DATA_URL)
                .forAccount(account)
                .header("Cookie", cookies)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .get()
                .build()

            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("获取超星课程失败 (${resp.code})"))
            }

            val parsedResp = gson.fromJson(body, ChaoXingClazzDataResponse::class.java)
            if (parsedResp?.result != 1 || parsedResp.channelList == null) {
                return@withContext Result.failure(Exception("超星课程响应未确认成功或格式无法识别，已保留原缓存"))
            }
            val courseList = mutableListOf<ChaoXingCourse>()

            val defaultUid = prefs.getUid(account)
            parsedResp.channelList?.forEach { channel ->
                val content = channel.content ?: return@forEach
                val classId = content.id ?: ""
                val cpi = (channel.cpi ?: "").ifBlank { defaultUid }
                val courseDataList = content.course?.data

                if (!courseDataList.isNullOrEmpty()) {
                    for (data in courseDataList) {
                        val courseId = data.id ?: continue
                        val courseName = (data.name ?: content.name ?: "未知课程").trim()
                        val teacher = (data.teacherfactor ?: "").trim()
                        val cover = data.imageurl ?: ""

                        courseList.add(
                            ChaoXingCourse(
                                courseId = courseId,
                                classId = classId,
                                courseName = courseName,
                                teacherName = teacher,
                                coverUrl = cover,
                                schoolName = USST_SCHOOL_NAME,
                                rawJson = "",
                                cpi = cpi ?: ""
                            )
                        )
                    }
                } else {
                    val directName = (content.name ?: "").trim()
                    if (directName.isNotBlank() && classId.isNotBlank()) {
                        courseList.add(
                            ChaoXingCourse(
                                courseId = classId,
                                classId = classId,
                                courseName = directName,
                                teacherName = "",
                                coverUrl = "",
                                schoolName = USST_SCHOOL_NAME,
                                rawJson = "",
                                cpi = cpi ?: ""
                            )
                        )
                    }
                }
            }

            if (parsedResp.channelList.isNotEmpty() && courseList.isEmpty()) {
                return@withContext Result.failure(Exception("超星课程条目格式无法识别，已保留原缓存"))
            }
            prefs.saveCachedCourses(courseList, account)
            Log.i(TAG, "Successfully fetched ${courseList.size} ChaoXing courses for $account")
            Result.success(courseList)
        } catch (e: Exception) {
            Log.e(TAG, "fetchCourses unexpected error", e)
            Result.failure(e)
        }
    }

    /**
     * 6. 拉取超星全局未完成/待办作业列表 (转换为 UnifiedTodo)
     */
    suspend fun fetchTodos(
        studentId: String? = null,
        resolveMissingDeadlines: Boolean = false
    ): Result<List<UnifiedTodo>> = withContext(Dispatchers.IO) {
        val account = studentId ?: AuthPreferences(context).getStudentId()
        val ensureRes = ensureSession(account)
        if (ensureRes.isFailure) {
            return@withContext Result.failure(ensureRes.exceptionOrNull() ?: Exception("超星登录状态失效"))
        }

        val cookies = prefs.getCookies(account) ?: return@withContext Result.failure(Exception("Cookie 为空"))

        try {
            val cachedHtml = ChaoXingRequestGuard.getCachedStuWorkHtml(account)
            val html = if (!cachedHtml.isNullOrBlank()) {
                cachedHtml
            } else {
                val req = Request.Builder()
                    .url(STU_WORK_URL)
                    .forAccount(account)
                    .header("Cookie", cookies)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .get()
                    .build()

                val resp = okHttpClient.newCall(req).execute()
                val fetchedHtml = resp.body?.string() ?: ""
                if (!resp.isSuccessful || fetchedHtml.isBlank()) {
                    return@withContext Result.failure(Exception("获取超星待办失败 (${resp.code})，已保留原缓存"))
                }
                ChaoXingRequestGuard.putCachedStuWorkHtml(account, fetchedHtml)
                fetchedHtml
            }

            val todoList = mutableListOf<UnifiedTodo>()
            if (html.isNotBlank()) {
                val doc = Jsoup.parse(html)
                val items = doc.select("ul.stu-work-list > li, .work-list > li, ul > li[data], li[data], li[onclick*='goTask']")
                val recognizedContainer = doc.select("ul.stu-work-list, .work-list").isNotEmpty()
                val emptyMessage = listOf("暂无作业", "暂无待办", "暂无未完成作业", "没有待完成的作业", "暂无相关作业")
                    .any { doc.text().contains(it) }
                if (doc.select("input[type=password], form[action*=login]").isNotEmpty() ||
                    (items.isEmpty() && !recognizedContainer && !emptyMessage)) {
                    return@withContext Result.failure(Exception("超星待办页面未确认成功或格式无法识别，已保留原缓存"))
                }
                var recognizedItems = 0

                for (el in items) {
                    var title = el.select("div[role='option'] > p, p.title, .title, .item-title, h3").firstOrNull()?.text()?.trim().orEmpty()
                    if (title.isBlank()) {
                        val aria = el.select("[aria-label]").attr("aria-label").ifBlank { el.attr("aria-label") }
                        if (aria.isNotBlank()) {
                            title = aria.split(";").first().trim()
                        }
                    }
                    if (title.isBlank()) {
                        title = el.select("p").firstOrNull()?.text()?.trim().orEmpty()
                    }
                    if (title.isBlank()) continue
                    recognizedItems++

                    var courseName = el.select(".course-name, .course, span.course-name").text().trim()
                    if (courseName.isBlank()) {
                        val spans = el.select("div[role='option'] > span, span")
                        for (sp in spans) {
                            val txt = sp.text().trim()
                            if (txt.startsWith("《") || txt.endsWith("》")) {
                                courseName = txt
                                break
                            }
                        }
                    }
                    val cleanCourse = courseName
                        .replace("《", "")
                        .replace("》", "")
                        .replace("...", "")
                        .replace("…", "")
                        .replace(Regex("""^\(.*?\)"""), "")
                        .trim()
                    if (courseName.isBlank()) courseName = "超星课程"
                    val finalCourseName = cleanCourse.ifBlank { "超星课程" }

                    var timeStr = el.select(".time, .end-time, .status-time, span.time").text().trim()
                    if (timeStr.isBlank()) {
                        val aria = el.select("[aria-label]").attr("aria-label").ifBlank { el.attr("aria-label") }
                        timeStr = ChaoXingCourseDetailClient.extractDeadlineFromText(el.text(), el.html(), aria)
                    }
                    var ddlMillis = parseDdlMillis(timeStr)
                    val statusText = el.select(".status, .state, span.status").text().trim()
                    val isSub = statusText.contains("已交") || statusText.contains("已完成") || statusText.contains("待批改")

                    // 过滤已提交的作业，聚焦未交与待办
                    if (isSub) continue

                    val isItemLocked = el.select(".icon-zy-g").isNotEmpty() ||
                            el.html().contains("icon-zy-g") ||
                            el.html().contains("task-work-gray") ||
                            statusText.contains("已截止") ||
                            statusText.contains("已过期") ||
                            statusText.contains("已逾期") ||
                            statusText.contains("已结束") ||
                            (ddlMillis != null && ddlMillis < System.currentTimeMillis())

                    // 核心过滤：过滤掉已过期的作业，不进入待办
                    if (isItemLocked) continue

                    val dataUrl = el.attr("data").ifBlank { el.select("a").attr("href") }.replace("&amp;", "&")
                    val workId = Regex("""(?:workId|workid|taskrefId)=([0-9]+)""").find(dataUrl)?.groupValues?.get(1)
                        ?: Regex("""[0-9]+""").find(el.attr("id"))?.value
                        ?: System.currentTimeMillis().toString()
                    val courseId = Regex("""(?:courseId|courseid)=([0-9]+)""").find(dataUrl)?.groupValues?.get(1) ?: "0"
                    val classId = Regex("""(?:classId|clazzId|clazzid)=([0-9]+)""").find(dataUrl)?.groupValues?.get(1)

                    if (ddlMillis == null) {
                        val cacheMgr = cn.edu.usst.jwgl.data.local.TronClassDataCacheManager.getInstance(context)
                        val cachedDetail = cacheMgr.getCachedChaoXingHomeworkDetail(workId)
                        if (cachedDetail != null && !cachedDetail.deadline.isNullOrBlank()) {
                            ddlMillis = parseDdlMillis(cachedDetail.deadline)
                        }
                    }
                    if (ddlMillis == null) {
                        val prevTodo = prefs.getCachedTodos(account).find { it.originalTodoId == workId }
                        if (prevTodo?.deadlineMillis != null) {
                            ddlMillis = prevTodo.deadlineMillis
                        }
                    }

                    // 若解析出的截止时间已早于当前时间，直接过滤
                    if (ddlMillis != null && ddlMillis < System.currentTimeMillis()) continue

                    todoList.add(
                        UnifiedTodo(
                            unifiedId = "cx_work_${workId}",
                            platform = LearningPlatform.CHAOXING,
                            originalTodoId = workId,
                            courseId = "cx_${courseId}_${classId ?: "0"}",
                            originalCourseId = courseId,
                            originalClassId = classId,
                            courseName = finalCourseName,
                            cleanCourseName = finalCourseName,
                            title = title,
                            cleanTitle = title,
                            deadlineDate = ddlMillis?.let { Date(it) },
                            deadlineMillis = ddlMillis,
                            type = "homework",
                            typeDisplayName = "作业",
                            isSubmitted = false,
                            isLocked = false,
                            detailUrl = dataUrl
                        )
                    )
                }
                if (items.isNotEmpty() && recognizedItems == 0) {
                    return@withContext Result.failure(Exception("超星待办条目格式无法识别，已保留原缓存"))
                }

                // 仅在显式要求时对缺失截止时间的作业进行顺序、限量的轻量探测（默认关闭，防止首页同步请求风暴）
                val needDdlItems = if (resolveMissingDeadlines) {
                    todoList.filter { it.deadlineMillis == null && !it.detailUrl.isNullOrBlank() }
                        .take(MAX_DDL_PROBE_COUNT)
                } else {
                    emptyList()
                }
                if (needDdlItems.isNotEmpty()) {
                    val cpi = prefs.getUid(account).orEmpty()
                    val resolved = mutableListOf<Triple<String, Long?, Boolean>>()
                    for (todo in needDdlItems) {
                        if (ChaoXingRequestGuard.isCircuitOpen(appContext)) break
                        try {
                            var targetUrl = if (todo.detailUrl!!.startsWith("http")) todo.detailUrl!! else "https://mooc1.chaoxing.com${todo.detailUrl}"
                            targetUrl = targetUrl.replace("&amp;", "&")
                            if (cpi.isNotBlank() && !targetUrl.contains("cpi=")) {
                                targetUrl += if (targetUrl.contains("?")) "&cpi=$cpi" else "?cpi=$cpi"
                            }
                            val reqDetail = Request.Builder()
                                .url(targetUrl)
                                .forAccount(account)
                                .header("Cookie", cookies)
                                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                                .get()
                                .build()
                            val respDetail = okHttpClient.newCall(reqDetail).execute()
                            val detailHtml = respDetail.body?.string().orEmpty()
                            var isDetailExpired = detailHtml.contains("task-work-gray") ||
                                    detailHtml.contains("已截止") ||
                                    detailHtml.contains("已结束") ||
                                    detailHtml.contains("已逾期")
                            var extractedDdl = ChaoXingCourseDetailClient.extractDeadlineFromText(detailHtml, detailHtml)

                            // 若一级引导页未直接包含截止时间，深入解析二级答题/查看详情页
                            if (extractedDdl.isBlank() && !isDetailExpired && !ChaoXingRequestGuard.isCircuitOpen(appContext)) {
                                val lookMatch = Regex("""(?:location\.origin\s*\+\s*_CP_\s*\+\s*['"]|href=['"]|url\s*=\s*['"]|location\.href\s*=\s*['"]|window\.location(?:\.href)?\s*=\s*['"])(/work/phone/(?:selectWorkQuestion|doHomeWork)[^'"]+)""").find(detailHtml)
                                    ?: Regex("""['"](/work/phone/(?:selectWorkQuestion|doHomeWork)[^'"]+)""").find(detailHtml)
                                val subPath = lookMatch?.groupValues?.get(1)?.trim()?.replace("&amp;", "&").orEmpty()
                                if (subPath.isNotBlank()) {
                                    val hostBase = if (targetUrl.contains("mooc1-api.chaoxing.com")) {
                                        "https://mooc1-api.chaoxing.com/mooc-ans"
                                    } else {
                                        "https://mooc1.chaoxing.com/mooc-ans"
                                    }
                                    val subUrl = if (subPath.startsWith("http")) subPath else hostBase + (if (subPath.startsWith("/")) subPath else "/$subPath")
                                    try {
                                        val reqSub = Request.Builder()
                                            .url(subUrl)
                                            .forAccount(account)
                                            .header("Cookie", cookies)
                                            .header("Referer", targetUrl)
                                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                                            .get()
                                            .build()
                                        val respSub = okHttpClient.newCall(reqSub).execute()
                                        val subHtml = respSub.body?.string().orEmpty()
                                        if (subHtml.isNotBlank()) {
                                            val ddlSub = ChaoXingCourseDetailClient.extractDeadlineFromText(subHtml, subHtml)
                                            if (ddlSub.isNotBlank()) {
                                                extractedDdl = ddlSub
                                            }
                                            if (subHtml.contains("已截止") || subHtml.contains("已结束") || subHtml.contains("已逾期")) {
                                                isDetailExpired = true
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            }

                            val parsed = if (extractedDdl.isNotBlank()) parseDdlMillis(extractedDdl) else null
                            resolved.add(Triple(todo.unifiedId, parsed, isDetailExpired))
                        } catch (e: ChaoXingRiskControlException) {
                            Log.w(TAG, "DDL probe halted by risk control: ${e.message}")
                            break
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to resolve deadline for ${todo.title}: ${e.message}")
                        }
                    }
                    for ((unifiedId, parsedMillis, isDetailExpired) in resolved) {
                        val idx = todoList.indexOfFirst { it.unifiedId == unifiedId }
                        if (idx >= 0) {
                            if (isDetailExpired || (parsedMillis != null && parsedMillis < System.currentTimeMillis())) {
                                // 探测出已过期，直接移出待办作业
                                todoList.removeAt(idx)
                            } else if (parsedMillis != null) {
                                val t = todoList[idx]
                                todoList[idx] = t.copy(
                                    deadlineDate = Date(parsedMillis),
                                    deadlineMillis = parsedMillis,
                                    isLocked = false
                                )
                            }
                        }
                    }
                }
            }

            // 过滤掉所有已过期或已锁定的作业，去重并按截止时间升序排序（临期的在前）
            val activeTodos = UnifiedTodo.deduplicateTodos(todoList)
                .filterNot { it.isOverdue() || it.isLocked || it.isSubmitted }
                .toMutableList()
            activeTodos.sortBy { it.deadlineMillis ?: Long.MAX_VALUE }
            prefs.saveCachedTodos(activeTodos, account)
            Log.i(TAG, "Successfully fetched ${activeTodos.size} ChaoXing uncompleted active todos for $account")
            Result.success(activeTodos)
        } catch (e: Exception) {
            Log.e(TAG, "fetchTodos error", e)
            Result.failure(e)
        }
    }

    /**
     * 7. 拉取超星课程通知与系统公告 (转换为 UnifiedBulletin)
     */
    suspend fun fetchNotices(studentId: String? = null): Result<List<UnifiedBulletin>> = withContext(Dispatchers.IO) {
        val account = studentId ?: AuthPreferences(context).getStudentId()
        val ensureRes = ensureSession(account)
        if (ensureRes.isFailure) {
            return@withContext Result.failure(ensureRes.exceptionOrNull() ?: Exception("超星登录状态失效"))
        }

        val cookies = prefs.getCookies(account) ?: return@withContext Result.failure(Exception("Cookie 为空"))

        try {
            val form = FormBody.Builder()
                .add("type", "0")
                .add("sort", "0")
                .build()

            val req = Request.Builder()
                .url(NOTICE_LIST_URL)
                .forAccount(account)
                .post(form)
                .header("Cookie", cookies)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .build()

            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("拉取超星通知失败 (${resp.code})"))
            }

            val parsed = gson.fromJson(body, ChaoXingNoticeResponse::class.java)
            if (parsed?.result != 1 || parsed.notices?.list == null) {
                return@withContext Result.failure(Exception("超星通知响应未确认成功或格式无法识别，已保留原缓存"))
            }
            val bulletinList = mutableListOf<UnifiedBulletin>()

            parsed.notices?.list?.forEach { notice ->
                val title = (notice.title ?: "课程通知").trim()
                val rawContent = notice.content ?: ""
                val clean = Jsoup.parse(rawContent).text().trim().ifBlank { title }
                val timeStr = if (notice.completeTime > 0) {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(notice.completeTime))
                } else ""

                bulletinList.add(
                    UnifiedBulletin(
                        unifiedId = "cx_notice_${notice.id}",
                        platform = LearningPlatform.CHAOXING,
                        originalBulletinId = notice.id.toString(),
                        courseId = "cx_${notice.sourceId}",
                        originalCourseId = notice.sourceId.toString(),
                        courseName = notice.sourceName ?: "学习通通知",
                        title = title,
                        content = rawContent,
                        cleanContent = clean,
                        publishTimeText = timeStr,
                        publishMillis = notice.completeTime,
                        imageUrls = emptyList()
                    )
                )
            }

            // 按发布时间倒序排列
            bulletinList.sortByDescending { it.publishMillis }
            prefs.saveCachedNotices(bulletinList, account)
            Log.i(TAG, "Successfully fetched ${bulletinList.size} ChaoXing notices for $account")
            Result.success(bulletinList)
        } catch (e: Exception) {
            Log.e(TAG, "fetchNotices error", e)
            Result.failure(e)
        }
    }

    /**
     * 8. 并发探测多门课程中当前正在进行中的签到活动
     */
    private val signService by lazy {
        ChaoXingSignService(
            context = context,
            prefs = prefs,
            gson = gson,
            okHttpClient = okHttpClient,
            submissionClient = submissionClient
        )
    }

    suspend fun fetchActiveSignsForCourses(
        courses: List<ChaoXingCourse>,
        studentId: String? = null,
        maxCourses: Int = 12
    ): Result<List<Pair<ChaoXingCourse, ChaoXingActiveItem>>> =
        signService.fetchActiveSignsForCourses(courses, studentId, maxCourses)

    suspend fun fetchSignDetail(
        courseId: String,
        classId: String,
        activeId: Long,
        studentId: String? = null,
        needConfigProbe: Boolean = true,
        initialOtherId: Int = -1,
        initialHintName: String = ""
    ): Result<cn.edu.usst.jwgl.data.model.ChaoXingSignDetail> =
        signService.fetchSignDetail(
            courseId, classId, activeId, studentId, needConfigProbe, initialOtherId, initialHintName
        )

    suspend fun submitSign(
        courseId: String,
        classId: String,
        activeId: Long,
        signType: String = "普通签到",
        enc: String? = null,
        signCode: String? = null,
        objectId: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        address: String? = null,
        studentId: String? = null,
        allowRejectedRetry: Boolean = false
    ): Result<ChaoXingSignResult> =
        signService.submitSign(
            courseId, classId, activeId, signType, enc, signCode, objectId,
            latitude, longitude, address, studentId, allowRejectedRetry
        )

    suspend fun fetchPPTActiveInfo(
        activeId: Long,
        studentId: String? = null
    ): Result<String?> =
        signService.fetchPPTActiveInfo(activeId, studentId)

    suspend fun uploadSignPhoto(
        photoFile: File,
        studentId: String? = null
    ): Result<String> =
        signService.uploadSignPhoto(photoFile, studentId)

    /**
     * 10. 外部 Cookie 注入 (用于 WebView 网页端 SSO 完成后的凭据无缝回流)
     */
    fun injectCookies(cookies: String, studentId: String? = null) {
        if (cookies.isBlank()) return
        val sid = studentId?.trim()?.takeIf { it.isNotBlank() } ?: try {
            AuthPreferences(context).getStudentId().trim()
        } catch (_: Exception) {
            ""
        }
        if (sid.isBlank()) return
        var uid = ""
        val pairs = cookies.split(";")
        for (p in pairs) {
            val trimmed = p.trim()
            if (trimmed.startsWith("UID=") || trimmed.startsWith("_uid=")) {
                uid = trimmed.substringAfter("=")
                break
            }
        }
        val conflictOwner = prefs.findConflictingStudentForUid(sid, uid)
        if (conflictOwner != null) {
            Log.w(TAG, "Blocked injecting ChaoXing cookies: uid=$uid already belongs to $conflictOwner, target=$sid")
            return
        }
        verifiedSessions.invalidate(sid)
        prefs.saveLoginSession(
            cookies = cookies,
            uid = uid,
            studentNo = sid,
            schoolId = USST_FID,
            schoolName = USST_SCHOOL_NAME
        )
        Log.i(TAG, "Successfully injected external ChaoXing cookies for $sid, uid: $uid")
    }

    /**
     * 10. 拉取超星指定课程的历史签到记录 (包含学生考勤状态探测)
     */
    suspend fun fetchCourseSignHistory(
        courseId: String,
        classId: String,
        studentId: String? = null
    ): Result<List<UnifiedStudentRollcall>> =
        signService.fetchCourseSignHistory(courseId, classId, studentId)

    /**
     * 清理当前账号的超星登录态
     */
    fun logout(studentId: String? = null) {
        val target = studentId?.trim()?.takeIf { it.isNotBlank() } ?: AuthPreferences.currentStudentId(context)
        if (target.isNotBlank()) {
            verifiedSessions.invalidate(target)
        }
        prefs.clearSession(studentId)
        ChaoXingRequestGuard.clearStuWorkCache(studentId)
    }

    fun invalidateVerifiedSession(studentId: String? = null) {
        val target = studentId?.trim()?.takeIf { it.isNotBlank() } ?: AuthPreferences.currentStudentId(context)
        if (target.isNotBlank()) {
            verifiedSessions.invalidate(target)
        }
    }
}
