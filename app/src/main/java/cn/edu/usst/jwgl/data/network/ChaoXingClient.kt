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

    private val prefs = ChaoXingPreferences(context)
    private val gson = Gson()
    private class CookieOwner(val account: String, val persistResponse: Boolean = true) {
        var acceptedCookies: String? = null
    }
    private fun Request.Builder.forAccount(account: String, persistResponse: Boolean = true) =
        tag(CookieOwner::class.java, CookieOwner(account, persistResponse))

    companion object {
        private const val TAG = "ChaoXingClient"
        private val sessionRequests = SharedRequest<Result<Boolean>>(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        )
        private val verifiedSessions = SessionValidationWindow(now = { android.os.SystemClock.elapsedRealtime() })

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
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val orig = chain.request()
            val owner = orig.tag(CookieOwner::class.java) ?: CookieOwner(AuthPreferences(context).getStudentId())
            val reqBuilder = orig.newBuilder()
                .tag(CookieOwner::class.java, owner)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")

            val host = orig.url.host.lowercase()
            val isChaoXing = host.endsWith(".chaoxing.com") || host == "chaoxing.com"

            if (isChaoXing && owner.persistResponse && owner.account.isNotBlank()) {
                val cookies = prefs.getCookies(owner.account)
                if (!cookies.isNullOrBlank() && orig.header("Cookie") == null) {
                    reqBuilder.header("Cookie", cookies)
                }
            }

            val request = reqBuilder.build()
            val expectedCookies = request.header("Cookie")
            val resp = chain.proceed(request)

            // 监听响应中的 Set-Cookie 并智能合并
            val responseHost = resp.request.url.host.lowercase()
            if (isChaoXing && owner.persistResponse && !chain.call().isCanceled() && resp.isSuccessful &&
                (responseHost.endsWith(".chaoxing.com") || responseHost == "chaoxing.com")) {
                val setCookies = resp.headers("Set-Cookie")
                if (setCookies.isNotEmpty()) {
                    owner.acceptedCookies = mergeAndSaveCookies(setCookies, owner.account, expectedCookies)
                }
            }

            resp
        }
        .build()

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
     * 合并并保存响应中的 Set-Cookie
     */
    private fun mergeAndSaveCookies(newCookies: List<String>, account: String, expectedCookies: String?): String? =
        synchronized(ChaoXingPreferences.sessionLock) {
        if (account.isBlank() || expectedCookies.isNullOrBlank() || prefs.getCookies(account) != expectedCookies) return@synchronized null
        val currentCookieStr = expectedCookies
        val currentUid = prefs.getUid(account).orEmpty()
        if (currentUid.isBlank()) return@synchronized null
        val cookieMap = LinkedHashMap<String, String>()

        // 解析当前已有 Cookie
        if (currentCookieStr.isNotBlank()) {
            val pairs = currentCookieStr.split(";")
            for (p in pairs) {
                val trimmed = p.trim()
                val idx = trimmed.indexOf('=')
                if (idx > 0) {
                    val k = trimmed.substring(0, idx).trim()
                    val v = trimmed.substring(idx + 1).trim()
                    cookieMap[k] = v
                }
            }
        }

        // 合并新收到的 Set-Cookie
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

        prefs.saveLoginSession(
            cookies = merged,
            uid = if (!newUid.isNullOrBlank()) newUid else currentUid,
            studentNo = account,
            schoolId = USST_FID,
            schoolName = USST_SCHOOL_NAME
        )
        merged
    }

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
    suspend fun loginWithCasSso(forceEntry: Boolean = false): Result<ChaoXingLoginResponse> = withContext(Dispatchers.IO) {
        val ssoResult = ChaoXingSsoClient.loginWithCas(context, forceEntry)
        if (ssoResult.isSuccess) {
            val userInfo = prefs.getUserInfo()
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
        // 1. 优先通过学校统一身份认证 (CAS SSO) 换票登录 (与 extrisha/litang 完全一致)
        val ssoRes = loginWithCasSso()
        if (ssoRes.isSuccess) {
            Log.i(TAG, "silentLoginChaoXing succeeded via USST CAS SSO")
            return@withContext Result.success(true)
        }
        Log.w(TAG, "silentLoginChaoXing: CAS SSO failed, trying fallback to institutional password login...", ssoRes.exceptionOrNull())

        val targetSid = if (!studentId.isNullOrBlank()) studentId else {
            try {
                AuthPreferences(context).getStudentId()
            } catch (_: Exception) {
                ""
            }
        }

        if (targetSid.isBlank()) {
            return@withContext Result.failure(Exception("无法获取当前学生学号"))
        }

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
     * 策略：本地有效Session探活 -> 失败则静默重登恢复
     */
    suspend fun ensureSession(studentId: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        val sid = studentId ?: prefs.getStudentNo().ifBlank {
            try {
                AuthPreferences(context).getStudentId()
            } catch (_: Exception) {
                ""
            }
        }
        if (sid.isBlank()) return@withContext Result.failure(Exception("请先选择学习通账号"))

        val cookies = prefs.getCookies(sid)
        sessionRequests.await("$sid/$cookies") { ensureSessionForAccount(sid) }
    }

    private suspend fun ensureSessionForAccount(sid: String): Result<Boolean> {
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
    suspend fun fetchTodos(studentId: String? = null): Result<List<UnifiedTodo>> = withContext(Dispatchers.IO) {
        val account = studentId ?: AuthPreferences(context).getStudentId()
        val ensureRes = ensureSession(account)
        if (ensureRes.isFailure) {
            return@withContext Result.failure(ensureRes.exceptionOrNull() ?: Exception("超星登录状态失效"))
        }

        val cookies = prefs.getCookies(account) ?: return@withContext Result.failure(Exception("Cookie 为空"))

        try {
            val req = Request.Builder()
                .url(STU_WORK_URL)
                .forAccount(account)
                .header("Cookie", cookies)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            val resp = okHttpClient.newCall(req).execute()
            val html = resp.body?.string() ?: ""
            if (!resp.isSuccessful || html.isBlank()) {
                return@withContext Result.failure(Exception("获取超星待办失败 (${resp.code})，已保留原缓存"))
            }

            val todoList = mutableListOf<UnifiedTodo>()
            if (resp.isSuccessful && html.isNotBlank()) {
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
                    if (courseName.isBlank()) courseName = "超星课程"

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

                    val dataUrl = el.attr("data").ifBlank { el.select("a").attr("href") }
                    val workId = Regex("""(?:workId|workid)=([0-9]+)""").find(dataUrl)?.groupValues?.get(1)
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
                            courseName = courseName,
                            cleanCourseName = courseName,
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

                // 针对未提交且未解析出截止时间的作业，并发进行轻量探测
                val needDdlItems = todoList.filter { it.deadlineMillis == null && !it.detailUrl.isNullOrBlank() }
                if (needDdlItems.isNotEmpty()) {
                    val cpi = prefs.getUid(account).orEmpty()
                    coroutineScope {
                        val ddlTasks = needDdlItems.map { todo ->
                            async {
                                try {
                                    var targetUrl = if (todo.detailUrl!!.startsWith("http")) todo.detailUrl!! else "https://mooc1.chaoxing.com${todo.detailUrl}"
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
                                    val isDetailExpired = detailHtml.contains("task-work-gray") ||
                                            detailHtml.contains("已截止") ||
                                            detailHtml.contains("已结束") ||
                                            detailHtml.contains("已逾期")
                                    val extractedDdl = ChaoXingCourseDetailClient.extractDeadlineFromText(detailHtml, detailHtml)
                                    val parsed = if (extractedDdl.isNotBlank()) parseDdlMillis(extractedDdl) else null
                                    Triple(todo.unifiedId, parsed, isDetailExpired)
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to resolve deadline for ${todo.title}: ${e.message}")
                                    null
                                }
                            }
                        }
                        val resolved = ddlTasks.awaitAll().filterNotNull()
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
            }

            // 过滤掉所有已过期或已锁定的作业，按截止时间升序排序（临期的在前）
            val activeTodos = todoList.filterNot { it.isOverdue() || it.isLocked || it.isSubmitted }
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
    suspend fun fetchActiveSignsForCourses(
        courses: List<ChaoXingCourse>,
        studentId: String? = null
    ): Result<List<Pair<ChaoXingCourse, ChaoXingActiveItem>>> = withContext(Dispatchers.IO) {
        val account = studentId ?: AuthPreferences(context).getStudentId()
        val cookies = prefs.getCookies(account) ?: return@withContext Result.failure(Exception("Cookie 为空"))

        try {
            coroutineScope {
                val results = courses.map { course ->
                    async {
                        try {
                            val url = "$ACTIVE_LIST_URL?courseId=${course.courseId}&classId=${course.classId}&fid=0&showNotStartedActive=0&_=${System.currentTimeMillis()}"
                            val req = Request.Builder()
                                .url(url)
                                .forAccount(account)
                                .header("Cookie", cookies)
                                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 com.ssreader.ChaoXingStudy")
                                .get()
                                .build()

                            val resp = okHttpClient.newCall(req).execute()
                            val body = resp.body?.string() ?: ""
                            if (!resp.isSuccessful || body.isBlank()) return@async emptyList<Pair<ChaoXingCourse, ChaoXingActiveItem>>()

                            val parsed = gson.fromJson(body, ChaoXingActiveListResponse::class.java)
                            val activeSigns = parsed.data?.activeList?.filter { it.isSignActivity() && it.isActive() } ?: emptyList()
                            activeSigns.map { Pair(course, it) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Check active sign failed for course: ${course.courseName}", e)
                            emptyList<Pair<ChaoXingCourse, ChaoXingActiveItem>>()
                        }
                    }
                }.awaitAll().flatten()

                // 对探测到的签到活动进行深度配置探测与精准类型解析
                val enrichedResults = results.map { pair ->
                    async {
                        val course = pair.first
                        val item = pair.second
                        val detailRes = fetchSignDetail(
                            courseId = course.courseId,
                            classId = course.classId,
                            activeId = item.id,
                            studentId = account,
                            initialOtherId = item.otherId
                        )
                        if (detailRes.isSuccess) {
                            item.signDetail = detailRes.getOrNull()
                        }
                        pair
                    }
                }.awaitAll()

                Result.success(enrichedResults)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchActiveSignsForCourses error", e)
            Result.failure(e)
        }
    }

    /**
     * 8.1 深度探测超星签到活动配置与真实签到类型
     * 双通道聚合 getPPTActiveInfo 与 preSign 判定：
     * 3 -> 手势签到 (自动获取手势密码 signcode)
     * 5 / ifPhoto -> 拍照签到
     * 带有 signcode / 签到码 -> 口令签到
     * 4 / 位置配置 -> 位置签到
     * 2 -> 二维码签到
     * 普通签到
     */
    suspend fun fetchSignDetail(
        courseId: String,
        classId: String,
        activeId: Long,
        studentId: String? = null,
        needConfigProbe: Boolean = true,
        initialOtherId: Int = -1
    ): Result<cn.edu.usst.jwgl.data.model.ChaoXingSignDetail> = withContext(Dispatchers.IO) {
        val account = studentId ?: AuthPreferences(context).getStudentId()
        val cookies = prefs.getCookies(account) ?: return@withContext Result.failure(Exception("Cookie 为空"))
        val uid = prefs.getUid(account) ?: ""

        try {
            var signCode: String? = null
            var otherId = if (initialOtherId >= 0) initialOtherId else -1
            var ifPhoto = false
            var locationText: String? = null
            var lat: Double? = null
            var lng: Double? = null
            var rawJson: String? = null

            // 1. 请求 getPPTActiveInfo 接口获取配置 (进行中签到或需要提取口令时执行)
            if (needConfigProbe) {
                try {
                    val infoUrl = "https://mobilelearn.chaoxing.com/v2/apis/active/getPPTActiveInfo?activeId=$activeId"
                    val infoReq = Request.Builder()
                        .url(infoUrl)
                        .forAccount(account)
                        .header("Cookie", cookies)
                        .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 com.ssreader.ChaoXingStudy")
                        .get()
                        .build()
                    val infoResp = okHttpClient.newCall(infoReq).execute()
                    val infoBody = infoResp.body?.string().orEmpty()
                    if (infoResp.isSuccessful && infoBody.isNotBlank()) {
                        rawJson = infoBody
                        val json = org.json.JSONObject(infoBody)
                        val data = json.optJSONObject("data")
                        if (data != null) {
                            signCode = data.optString("signcode", data.optString("signCode", "")).trim().takeIf { it.isNotBlank() }
                            otherId = data.optInt("otherId", -1).let {
                                if (it == -1) data.optString("otherId").toIntOrNull() ?: -1 else it
                            }
                            ifPhoto = data.optInt("ifPhoto", 0) == 1 || data.optString("ifPhoto") == "1"
                            locationText = data.optString("locationText", "").trim().takeIf { it.isNotBlank() }
                            val latVal = data.optDouble("locationLatitude", Double.NaN)
                            if (!latVal.isNaN()) lat = latVal
                            val lngVal = data.optDouble("locationLongitude", Double.NaN)
                            if (!lngVal.isNaN()) lng = lngVal
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getPPTActiveInfo failed: ${e.message}")
                }
            }

            // 2. 请求 preSign 预签到接口进行学生真实出勤状态与落地页分析
            var isAlreadySigned = false
            var isLate = false
            var isLeave = false
            var isExpired = false
            var statusText: String? = null
            var preHtml = ""
            var finalUrl = ""

            try {
                val preUrl = "$PRE_SIGN_URL?courseId=$courseId&classId=$classId&activePrimaryId=$activeId&uid=$uid&general=1&sys=1&ls=1&appType=15&ut=s"
                val preReq = Request.Builder()
                    .url(preUrl)
                    .forAccount(account)
                    .header("Cookie", cookies)
                    .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 com.ssreader.ChaoXingStudy")
                    .get()
                    .build()
                val preResp = okHttpClient.newCall(preReq).execute()
                finalUrl = preResp.request.url.toString()
                preHtml = preResp.body?.string().orEmpty()

                // 使用 Jsoup 提取超星预签到落地页中向学生真实渲染的文本（过滤掉模板隐藏的 DOM/CSS）
                val bodyText = if (preHtml.contains("<body")) preHtml.substringAfter("<body").substringBefore("</body>") else preHtml
                val cleanBody = Jsoup.parse(bodyText).text()

                // 超星预签到页面的多维度状态权威判据:
                // 1. 缺勤 / 未参与: 页面可见文本明确包含 "缺勤" 或 "已过教师设置的截止时间" 等
                val isAbsent = cleanBody.contains("缺勤") ||
                        cleanBody.contains("未签到") ||
                        cleanBody.contains("已过教师设置的截止时间") ||
                        cleanBody.contains("下次早点哦") ||
                        cleanBody.contains("签到已结束") ||
                        cleanBody.contains("活动已结束") ||
                        preHtml.contains("zsign_embarrassed")

                if (cleanBody.contains("迟到")) {
                    isLate = true
                }
                if (cleanBody.contains("请假") || cleanBody.contains("早退")) {
                    isLeave = true
                }

                // 2. 出勤成功: 页面文本明确显示 "签到成功" / "代签" / "您已签到过了" 等 (且绝不能为缺勤/迟到/请假)
                val hasSuccessMarker = !isAbsent && !isLate && !isLeave && (
                    cleanBody.contains("签到成功") ||
                    cleanBody.contains("代签") ||
                    cleanBody.contains("您已签到过了") ||
                    cleanBody.contains("已签到") ||
                    finalUrl.contains("success") ||
                    finalUrl.contains("tiaozhuanSuc")
                )

                if (hasSuccessMarker) {
                    isAlreadySigned = true
                } else if (isAbsent) {
                    isExpired = true
                    statusText = if (cleanBody.contains("缺勤")) "缺勤" else "未参与"
                }
            } catch (e: Exception) {
                Log.w(TAG, "preSign probe failed: ${e.message}")
            }

            // 3. 综合多维度精确判定签到类型 (结合 activeList.otherId 与 preSign 页面特征)
            val typeDisplayName = when {
                otherId == 3 || preHtml.contains("gesture") || preHtml.contains("手势") || finalUrl.contains("gesture") -> "手势签到"
                otherId == 5 || ifPhoto || preHtml.contains("photo") || preHtml.contains("拍照") || preHtml.contains("照片") || finalUrl.contains("photo") -> "拍照签到"
                !signCode.isNullOrBlank() || preHtml.contains("pcForCode") || preHtml.contains("签到码") || preHtml.contains("口令") || finalUrl.contains("pcForCode") -> "口令签到"
                otherId == 2 || preHtml.contains("二维码") || finalUrl.contains("qr") -> "二维码签到"
                otherId == 4 || !locationText.isNullOrBlank() || lat != null || preHtml.contains("map.baidu.com") || preHtml.contains("BMap") || preHtml.contains("位置") || finalUrl.contains("location") -> "位置签到"
                else -> "普通签到"
            }

            val detail = cn.edu.usst.jwgl.data.model.ChaoXingSignDetail(
                activeId = activeId,
                otherId = if (otherId >= 0) otherId else 0,
                signTypeDisplayName = typeDisplayName,
                signCode = signCode,
                ifPhoto = ifPhoto,
                locationText = locationText,
                latitude = lat,
                longitude = lng,
                isAlreadySigned = isAlreadySigned,
                isLate = isLate,
                isLeave = isLeave,
                isExpired = isExpired,
                statusText = statusText,
                rawJson = rawJson
            )

            Log.i(TAG, "fetchSignDetail activeId=$activeId -> type: $typeDisplayName, signCode: $signCode, isAlreadySigned: $isAlreadySigned")
            Result.success(detail)
        } catch (e: Exception) {
            Log.e(TAG, "fetchSignDetail unexpected error", e)
            Result.failure(e)
        }
    }

    /**
     * 9. 提交超星课堂签到 (预签到 -> 正式签到两步安全执行)
     * 支持：普通签到、二维码动态 enc 签到、位置经纬度签到、手势九宫格签到 (signCode)、拍照上传签到 (objectId)
     */
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
        studentId: String? = null
    ): Result<ChaoXingSignResult> {
        val account = studentId ?: AuthPreferences(context).getStudentId()
        return withContext(Dispatchers.IO) {
        val cookies = prefs.getCookies(account) ?: return@withContext Result.failure(Exception("Cookie 为空"))
        val uid = prefs.getUid(account) ?: ""
        if (account.isBlank() || activeId <= 0L || courseId.isBlank() || classId.isBlank() || uid.isBlank() ||
            ((latitude == null) != (longitude == null)) ||
            (latitude != null && (!latitude.isFinite() || latitude !in -90.0..90.0)) ||
            (longitude != null && (!longitude.isFinite() || longitude !in -180.0..180.0))) {
            return@withContext Result.failure(IllegalArgumentException("缺少明确的签到账号、活动、课程或有效位置"))
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (studentId == null && account != AuthPreferences(context).getStudentId()) throw kotlinx.coroutines.CancellationException("Account changed before attendance write")
        val store = RollcallSubmissionStore(context, "chaoxing")
        if (!store.claim(account, activeId)) {
            val state = store.state(account, activeId)
            if (state == null) return@withContext Result.failure(Exception("无法持久化签到保护记录，本次未发送"))
            return@withContext Result.success(ChaoXingSignResult(state == "confirmed",
                if (state == "confirmed") "本机已保存本活动的确认签到结果" else "本活动已有提交记录，请查看学习通官方状态；不会再次发送", activeId, signType))
        }

        try {
            // 第一步：预签到初始化 (Pre-Sign)
            val preOwner = CookieOwner(account)
            val preUrl = "$PRE_SIGN_URL?courseId=$courseId&classId=$classId&activePrimaryId=$activeId&uid=$uid&general=1&sys=1&ls=1&appType=15&ut=s"
            val preReq = Request.Builder()
                .url(preUrl)
                .tag(CookieOwner::class.java, preOwner)
                .tag(SingleNetworkAttempt::class.java, SingleNetworkAttempt())
                .header("Cookie", cookies)
                .header("Cache-Control", "no-store")
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 com.ssreader.ChaoXingStudy")
                .get()
                .build()

            val preResp = submissionClient.newCall(preReq).awaitBuffered().response(preReq)
            preResp.use {
                if (!it.isSuccessful && it.code !in 300..399) {
                    val outcome = parseChaoXingSubmission(it.code, it.body?.string().orEmpty(), activeId, signType)
                    store.record(account, activeId, outcome.state)
                    return@withContext Result.success(outcome.result)
                }
            }
            val submissionCookies = preOwner.acceptedCookies ?: cookies
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if ((studentId == null && account != AuthPreferences(context).getStudentId()) ||
                prefs.getCookies(account) != submissionCookies || prefs.getUid(account) != uid) {
                return@withContext Result.success(ChaoXingSignResult(false, "账号或会话已更新，本次已停止，请查看官方签到状态", activeId, signType))
            }

            // 第二步：正式提交签到 (Submit Ajax)
            val submitBuilder = java.lang.StringBuilder("$SUBMIT_SIGN_URL?activeId=$activeId&appType=15&ifTiJiao=1")
            
            // 签到口令 / 手势密码 / 二维码 enc 参数适配
            val effectiveCode = signCode?.trim().takeIf { !it.isNullOrBlank() }
                ?: enc?.trim().takeIf { !it.isNullOrBlank() }
            if (!effectiveCode.isNullOrBlank()) {
                val encodedCode = java.net.URLEncoder.encode(effectiveCode, "UTF-8")
                submitBuilder.append("&signCode=").append(encodedCode)
                submitBuilder.append("&enc=").append(encodedCode)
            }

            // 拍照签到 objectId 参数适配
            if (!objectId.isNullOrBlank()) {
                submitBuilder.append("&objectId=").append(java.net.URLEncoder.encode(objectId.trim(), "UTF-8"))
                submitBuilder.append("&ifPhoto=1")
            }

            // 位置经纬度参数适配
            if (latitude != null && longitude != null) {
                submitBuilder.append("&latitude=").append(latitude)
                    .append("&longitude=").append(longitude)
                    .append("&address=").append(java.net.URLEncoder.encode(address ?: "上海理工大学", "UTF-8"))
            }

            val submitReq = Request.Builder()
                .url(submitBuilder.toString())
                .forAccount(account)
                .tag(SingleNetworkAttempt::class.java, SingleNetworkAttempt())
                .header("Cookie", submissionCookies)
                .header("Cache-Control", "no-store")
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 com.ssreader.ChaoXingStudy")
                .get()
                .build()

            val outcome = submissionClient.newCall(submitReq).awaitBuffered().response(submitReq).use {
                parseChaoXingSubmission(it.code, it.body?.string().orEmpty(), activeId, signType)
            }
            store.record(account, activeId, outcome.state)
            if (studentId == null && account != AuthPreferences(context).getStudentId()) throw kotlinx.coroutines.CancellationException("Account changed while submitting attendance")
            Result.success(outcome.result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "submitSign failed", e)
            Result.success(ChaoXingSignResult(false, "签到结果尚未确认，请查看学习通官方记录；不会再次发送", activeId, signType))
        }
        }
    }

    /**
     * 9.1 获取签到活动的详细配置（如教师端预设的 signCode 手势/数字口令）
     * GET https://mobilelearn.chaoxing.com/v2/apis/active/getPPTActiveInfo?activeId={activeId}
     */
    suspend fun fetchPPTActiveInfo(
        activeId: Long,
        studentId: String? = null
    ): Result<String?> = withContext(Dispatchers.IO) {
        val account = studentId ?: AuthPreferences(context).getStudentId()
        val cookies = prefs.getCookies(account) ?: return@withContext Result.failure(Exception("Cookie 为空"))
        try {
            val url = "https://mobilelearn.chaoxing.com/v2/apis/active/getPPTActiveInfo?activeId=$activeId"
            val req = Request.Builder()
                .url(url)
                .forAccount(account)
                .header("Cookie", cookies)
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 com.ssreader.ChaoXingStudy")
                .get()
                .build()
            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            Log.e("CHAOXING_DEBUG", "fetchCourseSignHistory activelist: $body")
            if (!resp.isSuccessful || body.isBlank()) {
                return@withContext Result.success(null)
            }
            val json = JSONObject(body)
            val data = json.optJSONObject("data")
            val code = data?.optString("signcode", data.optString("signCode", ""))?.trim()?.takeIf { it.isNotBlank() }
            Result.success(code)
        } catch (e: Exception) {
            Log.w(TAG, "fetchPPTActiveInfo failed: ${e.message}")
            Result.success(null)
        }
    }

    /**
     * 9.2 上传拍照签到图片至超星云盘，换取全局资源标识 objectId
     */
    suspend fun uploadSignPhoto(
        photoFile: File,
        studentId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val account = studentId ?: AuthPreferences(context).getStudentId()
        val cookies = prefs.getCookies(account) ?: return@withContext Result.failure(Exception("Cookie 为空"))
        val puid = prefs.getUid(account) ?: run {
            Regex("""(?:^|;\s*)(?:UID|_uid)=(\d+)""").find(cookies)?.groupValues?.get(1).orEmpty()
        }

        try {
            // 1. 获取上传 Token
            val tokenReq = Request.Builder()
                .url("https://pan-yz.chaoxing.com/api/token/uservalid")
                .forAccount(account)
                .header("Cookie", cookies)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .get()
                .build()

            val tokenResp = okHttpClient.newCall(tokenReq).execute()
            val tokenBody = tokenResp.body?.string().orEmpty()
            val tokenJson = try { JSONObject(tokenBody) } catch (_: Exception) {
                return@withContext Result.failure(Exception("获取超星网盘 Token 失败: $tokenBody"))
            }

            val token = tokenJson.optString("_token")
            if (token.isBlank()) {
                return@withContext Result.failure(Exception("超星网盘上传 Token 为空"))
            }

            // 2. 构造 Multipart 提交图片
            val mediaType = "image/jpeg".toMediaTypeOrNull()
            val reqBody = photoFile.asRequestBody(mediaType)
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("puid", puid)
                .addFormDataPart("_token", token)
                .addFormDataPart("file", photoFile.name, reqBody)
                .build()

            val uploadReq = Request.Builder()
                .url("https://pan-yz.chaoxing.com/upload")
                .forAccount(account)
                .header("Cookie", cookies)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .post(multipartBody)
                .build()

            val uploadResp = okHttpClient.newCall(uploadReq).execute()
            val uploadBody = uploadResp.body?.string().orEmpty()
            val uploadJson = try { JSONObject(uploadBody) } catch (_: Exception) {
                return@withContext Result.failure(Exception("上传响应非 JSON: $uploadBody"))
            }

            val objectId = if (uploadJson.optBoolean("result") || uploadJson.has("objectId")) {
                uploadJson.optString("objectId").ifBlank {
                    uploadJson.optJSONObject("data")?.optString("objectId").orEmpty()
                }
            } else ""

            if (objectId.isNotBlank()) {
                Log.i(TAG, "uploadSignPhoto success: objectId=$objectId")
                Result.success(objectId)
            } else {
                val errMsg = uploadJson.optString("msg", "上传失败，未返回 objectId")
                Result.failure(Exception(errMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadSignPhoto error", e)
            Result.failure(e)
        }
    }

    /**
     * 10. 外部 Cookie 注入 (用于 WebView 网页端 SSO 完成后的凭据无缝回流)
     */
    fun injectCookies(cookies: String, studentId: String? = null) {
        if (cookies.isBlank()) return
        val sid = studentId ?: try {
            AuthPreferences(context).getStudentId()
        } catch (_: Exception) {
            ""
        }
        var uid = ""
        val pairs = cookies.split(";")
        for (p in pairs) {
            val trimmed = p.trim()
            if (trimmed.startsWith("UID=") || trimmed.startsWith("_uid=")) {
                uid = trimmed.substringAfter("=")
                break
            }
        }
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
    ): Result<List<UnifiedStudentRollcall>> = withContext(Dispatchers.IO) {
        val account = studentId ?: AuthPreferences(context).getStudentId()
        val cookies = prefs.getCookies(account) ?: return@withContext Result.failure(Exception("Cookie 为空"))

        try {
            val url = "$ACTIVE_LIST_URL?courseId=$courseId&classId=$classId&fid=0&showNotStartedActive=0&_=${System.currentTimeMillis()}"
            val req = Request.Builder()
                .url(url)
                .forAccount(account)
                .header("Cookie", cookies)
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 com.ssreader.ChaoXingStudy")
                .get()
                .build()

            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful || body.isBlank()) {
                return@withContext Result.failure(Exception("拉取超星活动列表失败 (${resp.code})"))
            }

            val parsed = gson.fromJson(body, ChaoXingActiveListResponse::class.java)
            val allSignActivities = parsed.data?.activeList?.filter { it.isSignActivity() } ?: emptyList()

            if (allSignActivities.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            // 对所有签到活动通过 preSign 并发获取权威个人出勤结果 (已签到出勤 / 迟到 / 缺勤 / 未参与)
            // 已结束活动无需探测口令配置 (needConfigProbe = false)，秒级并行响应
            coroutineScope {
                val rollcallList = allSignActivities.map { item ->
                    async {
                        val detail = fetchSignDetail(
                            courseId = courseId,
                            classId = classId,
                            activeId = item.id,
                            studentId = account,
                            needConfigProbe = item.isActive(),
                            initialOtherId = item.otherId
                        ).getOrNull()
                        UnifiedStudentRollcall.fromChaoXing(item, detail)
                    }
                }.awaitAll()

                // 按签到发起时间倒序排列
                val sorted = rollcallList.sortedByDescending { it.timeMillis }
                Result.success(sorted)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchCourseSignHistory error for course $courseId", e)
            Result.failure(e)
        }
    }

    /**
     * 清理当前账号的超星登录态
     */
    fun logout(studentId: String? = null) {
        prefs.clearSession(studentId)
    }
}
