package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import cn.edu.usst.jwgl.data.local.AccountVault
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
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

    companion object {
        private const val TAG = "ChaoXingClient"

        // 超星常用端点
        const val USST_FID = "207"
        const val USST_SCHOOL_NAME = "上海理工大学"
        const val PASSPORT_URL = "https://passport2.chaoxing.com"
        const val FANYA_LOGIN_URL = "$PASSPORT_URL/fanyalogin"
        const val COURSE_DATA_URL = "http://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1"
        const val USST_FANYA_URL = "http://usst.fanya.chaoxing.com"
        const val NOTICE_LIST_URL = "https://notice.chaoxing.com/pc/notice/getNoticeList"
        const val STU_WORK_URL = "http://mooc1-api.chaoxing.com/work/stu-work"
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
            val raw = timeStr.trim().replace("截止时间：", "").replace("截止时间:", "").replace("截止:", "").trim()
            val formats = listOf(
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA),
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA),
                SimpleDateFormat("MM-dd HH:mm", Locale.CHINA),
                SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.CHINA)
            )
            for (sdf in formats) {
                try {
                    val d = sdf.parse(raw) ?: continue
                    if (!raw.contains("202")) {
                        val cal = java.util.Calendar.getInstance()
                        val curYear = cal.get(java.util.Calendar.YEAR)
                        cal.time = d
                        cal.set(java.util.Calendar.YEAR, curYear)
                        return cal.timeInMillis
                    }
                    return d.time
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
                java.util.Base64.getEncoder().encodeToString(bytes)
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
            val reqBuilder = orig.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .header("Accept-Language", "zh-CN,zh;q=0.9")

            val host = orig.url.host.lowercase()
            val isChaoXing = host.endsWith(".chaoxing.com") || host == "chaoxing.com"

            if (isChaoXing) {
                val cookies = prefs.getCookies()
                if (!cookies.isNullOrBlank() && orig.header("Cookie") == null) {
                    reqBuilder.header("Cookie", cookies)
                }
            }

            val resp = chain.proceed(reqBuilder.build())

            // 监听响应中的 Set-Cookie 并智能合并
            if (isChaoXing) {
                val setCookies = resp.headers("Set-Cookie")
                if (setCookies.isNotEmpty()) {
                    mergeAndSaveCookies(setCookies)
                }
            }

            resp
        }
        .build()

    fun isLoggedIn(): Boolean = prefs.isLoggedIn()

    fun getUserInfo(): ChaoXingUserInfo = prefs.getUserInfo()

    fun getOkHttpClient(): OkHttpClient = okHttpClient

    /**
     * 合并并保存响应中的 Set-Cookie
     */
    @Synchronized
    private fun mergeAndSaveCookies(newCookies: List<String>) {
        val currentCookieStr = prefs.getCookies() ?: ""
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
        val currentUid = prefs.getUid() ?: newUid ?: ""
        val currentStudentNo = prefs.getStudentNo()

        prefs.saveLoginSession(
            cookies = merged,
            uid = if (!newUid.isNullOrBlank()) newUid else currentUid,
            studentNo = currentStudentNo,
            userName = prefs.getUserName(),
            schoolId = USST_FID,
            schoolName = USST_SCHOOL_NAME
        )
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
     * 2. 全静默单点登录 (自动联动 AccountVault 解密本地密码换票)
     */
    suspend fun silentLoginChaoXing(studentId: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
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
        val cookies = prefs.getCookies(studentId)
        if (cookies.isNullOrBlank()) return@withContext false

        try {
            val req = Request.Builder()
                .url(COURSE_DATA_URL)
                .header("Cookie", cookies)
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .get()
                .build()

            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful || body.isBlank()) return@withContext false

            // 若返回未登录页面或跳转 html
            if (body.contains("login") && (body.contains("<html") || body.contains("<!DOCTYPE"))) {
                return@withContext false
            }

            // 成功返回包含 result 或 channelList
            val json = JSONObject(body)
            val result = json.optInt("result", -1)
            result == 1 || json.has("channelList")
        } catch (e: Exception) {
            Log.w(TAG, "checkSessionValid failed", e)
            false
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

        // 1. 如果已有 Cookie 且最后验证在 30 分钟内，先做快速探活
        if (prefs.isLoggedIn(sid)) {
            val lastTime = prefs.getLastLoginTime(sid)
            val isRecent = System.currentTimeMillis() - lastTime < 30 * 60 * 1000L
            if (isRecent || checkSessionValid(sid)) {
                return@withContext Result.success(true)
            }
        }

        // 2. 会话无效或已过期，触发静默登录
        Log.i(TAG, "ChaoXing session expired or missing for $sid, executing silent login...")
        silentLoginChaoXing(sid)
    }

    /**
     * 5. 获取当前学期超星在修课程列表 (拉取与清洗)
     */
    suspend fun fetchCourses(studentId: String? = null): Result<List<ChaoXingCourse>> = withContext(Dispatchers.IO) {
        val ensureRes = ensureSession(studentId)
        if (ensureRes.isFailure) {
            return@withContext Result.failure(ensureRes.exceptionOrNull() ?: Exception("超星登录状态失效"))
        }

        val cookies = prefs.getCookies(studentId) ?: return@withContext Result.failure(Exception("Cookie 为空"))

        try {
            val req = Request.Builder()
                .url(COURSE_DATA_URL)
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
            val courseList = mutableListOf<ChaoXingCourse>()

            parsedResp.channelList?.forEach { channel ->
                val content = channel.content ?: return@forEach
                val classId = content.id ?: ""
                val courseDataList = content.course?.data ?: return@forEach

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
                            rawJson = ""
                        )
                    )
                }
            }

            Log.i(TAG, "Successfully fetched ${courseList.size} ChaoXing courses for $studentId")
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
        val ensureRes = ensureSession(studentId)
        if (ensureRes.isFailure) {
            return@withContext Result.failure(ensureRes.exceptionOrNull() ?: Exception("超星登录状态失效"))
        }

        val cookies = prefs.getCookies(studentId) ?: return@withContext Result.failure(Exception("Cookie 为空"))

        try {
            val req = Request.Builder()
                .url(STU_WORK_URL)
                .header("Cookie", cookies)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            val resp = okHttpClient.newCall(req).execute()
            val html = resp.body?.string() ?: ""

            val todoList = mutableListOf<UnifiedTodo>()
            if (resp.isSuccessful && html.isNotBlank()) {
                val doc = Jsoup.parse(html)
                val items = doc.select("ul.stu-work-list > li, .work-list > li, ul > li[data]")

                for (el in items) {
                    val title = el.select(".title, .item-title, p.title, h3").text().trim()
                    if (title.isBlank()) continue

                    val courseName = el.select(".course-name, .course, span.course-name").text().trim()
                        .ifBlank { "超星课程" }
                    val timeStr = el.select(".time, .end-time, .status-time, span.time").text().trim()
                    val ddlMillis = parseDdlMillis(timeStr)
                    val statusText = el.select(".status, .state, span.status").text().trim()
                    val isSub = statusText.contains("已交") || statusText.contains("已完成") || statusText.contains("待批改")

                    // 过滤已提交的作业，聚焦未交与待办
                    if (isSub) continue

                    val dataUrl = el.attr("data").ifBlank { el.select("a").attr("href") }
                    val workId = Regex("""(?:workId|workid)=([0-9]+)""").find(dataUrl)?.groupValues?.get(1)
                        ?: System.currentTimeMillis().toString()
                    val courseId = Regex("""(?:courseId|courseid)=([0-9]+)""").find(dataUrl)?.groupValues?.get(1) ?: "0"
                    val classId = Regex("""(?:classId|clazzId|clazzid)=([0-9]+)""").find(dataUrl)?.groupValues?.get(1)

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
                            isLocked = ddlMillis != null && ddlMillis < System.currentTimeMillis(),
                            detailUrl = dataUrl
                        )
                    )
                }
            }

            // 按截止时间升序排序（临期的在前）
            todoList.sortBy { it.deadlineMillis ?: Long.MAX_VALUE }
            Log.i(TAG, "Successfully fetched ${todoList.size} ChaoXing uncompleted todos for $studentId")
            Result.success(todoList)
        } catch (e: Exception) {
            Log.e(TAG, "fetchTodos error", e)
            Result.failure(e)
        }
    }

    /**
     * 7. 拉取超星课程通知与系统公告 (转换为 UnifiedBulletin)
     */
    suspend fun fetchNotices(studentId: String? = null): Result<List<UnifiedBulletin>> = withContext(Dispatchers.IO) {
        val ensureRes = ensureSession(studentId)
        if (ensureRes.isFailure) {
            return@withContext Result.failure(ensureRes.exceptionOrNull() ?: Exception("超星登录状态失效"))
        }

        val cookies = prefs.getCookies(studentId) ?: return@withContext Result.failure(Exception("Cookie 为空"))

        try {
            val form = FormBody.Builder()
                .add("type", "0")
                .add("sort", "0")
                .build()

            val req = Request.Builder()
                .url(NOTICE_LIST_URL)
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
            Log.i(TAG, "Successfully fetched ${bulletinList.size} ChaoXing notices for $studentId")
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
        val cookies = prefs.getCookies(studentId) ?: return@withContext Result.failure(Exception("Cookie 为空"))

        try {
            coroutineScope {
                val results = courses.map { course ->
                    async {
                        try {
                            val url = "$ACTIVE_LIST_URL?courseId=${course.courseId}&classId=${course.classId}&fid=0&showNotStartedActive=0&_=${System.currentTimeMillis()}"
                            val req = Request.Builder()
                                .url(url)
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

                Result.success(results)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchActiveSignsForCourses error", e)
            Result.failure(e)
        }
    }

    /**
     * 9. 提交超星课堂签到 (预签到 -> 正式签到两步安全执行)
     * 支持：普通签到、二维码动态 enc 签到、位置经纬度签到
     */
    suspend fun submitSign(
        courseId: String,
        classId: String,
        activeId: Long,
        signType: String = "普通签到",
        enc: String? = null,
        latitude: Double? = null,
        longitude: Double? = null,
        address: String? = null,
        studentId: String? = null
    ): Result<ChaoXingSignResult> = withContext(Dispatchers.IO) {
        val cookies = prefs.getCookies(studentId) ?: return@withContext Result.failure(Exception("Cookie 为空"))
        val uid = prefs.getUid(studentId) ?: ""

        try {
            // 第一步：预签到初始化 (Pre-Sign)
            val preUrl = "$PRE_SIGN_URL?courseId=$courseId&classId=$classId&activePrimaryId=$activeId&uid=$uid&general=1&sys=1&ls=1&appType=15&ut=s"
            val preReq = Request.Builder()
                .url(preUrl)
                .header("Cookie", cookies)
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 com.ssreader.ChaoXingStudy")
                .get()
                .build()

            val preResp = okHttpClient.newCall(preReq).execute()
            preResp.body?.close()

            // 第二步：正式提交签到 (Submit Ajax)
            val submitBuilder = java.lang.StringBuilder("$SUBMIT_SIGN_URL?activeId=$activeId&appType=15&ifTiJiao=1")
            if (!enc.isNullOrBlank()) {
                submitBuilder.append("&enc=").append(java.net.URLEncoder.encode(enc, "UTF-8"))
            }
            if (latitude != null && longitude != null) {
                submitBuilder.append("&latitude=").append(latitude)
                    .append("&longitude=").append(longitude)
                    .append("&address=").append(java.net.URLEncoder.encode(address ?: "上海理工大学", "UTF-8"))
            }

            val submitReq = Request.Builder()
                .url(submitBuilder.toString())
                .header("Cookie", cookies)
                .header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148 com.ssreader.ChaoXingStudy")
                .get()
                .build()

            val submitResp = okHttpClient.newCall(submitReq).execute()
            val respText = submitResp.body?.string()?.trim() ?: ""

            val isSuccess = respText.contains("success", ignoreCase = true) ||
                            respText.contains("成功") ||
                            respText.contains("您已签到过了")

            val msg = if (isSuccess) {
                if (respText.contains("您已签到过了")) "您已完成过该签到" else "超星签到成功"
            } else {
                respText.ifBlank { "签到提交失败，请重试" }
            }

            Log.i(TAG, "submitSign activeId=$activeId, result: $msg")
            Result.success(
                ChaoXingSignResult(
                    isSuccess = isSuccess,
                    message = msg,
                    activeId = activeId,
                    signType = signType
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "submitSign failed", e)
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
     * 清理当前账号的超星登录态
     */
    fun logout(studentId: String? = null) {
        prefs.clearSession(studentId)
    }
}
