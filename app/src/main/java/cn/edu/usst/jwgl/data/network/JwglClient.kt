package cn.edu.usst.jwgl.data.network

import android.util.Log
import cn.edu.usst.jwgl.data.model.ExamItem
import cn.edu.usst.jwgl.data.model.StudentProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

object JwglClient {
    private const val TAG = "JwglClient"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val ENTRY_URL = "https://jwgl.usst.edu.cn/sso/jziotlogin"
    private const val PROFILE_URL = "https://jwgl.usst.edu.cn/jwglxt/xsxxxggl/xsgrxxwh_cxXsgrxx.html?gnmkdm=N100801"
    private const val QUICK_INFO_URL = "https://jwgl.usst.edu.cn/jwglxt/xtgl/index_cxYhxxIndex.html?xt=jw&localeKey=zh_CN&gnmkdm=index"
    private const val GRADE_URL = "https://jwgl.usst.edu.cn/jwglxt/cjcx/cjcx_cxXsfxcjIndex.html?doType=query"
    private const val TIMETABLE_URL = "https://jwgl.usst.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151"
    private const val EXAM_URL = "https://jwgl.usst.edu.cn/jwglxt/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105"

    private val cookieJar = MemoryCookieJar()

    // Deterministic redirect handler for CAS SSO multi-domain chains
    private val redirectInterceptor = Interceptor { chain ->
        var request = chain.request()
        var response = chain.proceed(request)
        var redirectCount = 0

        while (response.isRedirect && redirectCount < 20) {
            redirectCount++
            val location = response.header("Location")
            if (location.isNullOrEmpty()) {
                Log.w(TAG, "Redirect $redirectCount has no Location header")
                break
            }

            var resolvedUrl = response.request.url.resolve(location)
            if (resolvedUrl == null) {
                Log.e(TAG, "Cannot resolve redirect location: $location relative to ${response.request.url}")
                break
            }

            // Upgrade http to https for USST internal systems
            if (resolvedUrl.scheme == "http" && resolvedUrl.host.endsWith("usst.edu.cn")) {
                resolvedUrl = resolvedUrl.newBuilder().scheme("https").build()
            }

            Log.d(TAG, "Hop $redirectCount: [${response.code}] ${request.method} -> $resolvedUrl")

            val newRequestBuilder = request.newBuilder().url(resolvedUrl)
            if (response.code == 301 || response.code == 302 || response.code == 303) {
                newRequestBuilder.get()
            }
            request = newRequestBuilder.build()
            response.close()
            response = chain.proceed(request)
        }
        response
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(false) // Disable OkHttp's internal follower to let our interceptor handle all hops
        .followSslRedirects(false)
        .addInterceptor(redirectInterceptor)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun clearSession() {
        cookieJar.clear()
    }

    fun getCookies(): List<okhttp3.Cookie> = cookieJar.getAllCookies()

    fun hasValidSession(): Boolean {
        val cookies = cookieJar.getAllCookies()
        return cookies.any { it.name.contains("JSESSIONID", ignoreCase = true) || it.name.contains("CASTGC", ignoreCase = true) }
    }

    suspend fun login(studentId: String, password: String): Result<StudentProfile> = withContext(Dispatchers.IO) {
        try {
            clearSession()
            Log.d(TAG, "--- Starting login flow for $studentId ---")

            // 1. Visit entry URL to initiate CAS SSO session
            val entryReq = Request.Builder()
                .url(ENTRY_URL)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val entryResp = client.newCall(entryReq).execute()
            val casUrl = entryResp.request.url.toString()
            val casHtml = entryResp.body?.string() ?: ""
            Log.d(TAG, "Entry reached CAS page: $casUrl (HTML len: ${casHtml.length})")

            // 2. Parse CAS hidden fields
            val doc = Jsoup.parse(casHtml)
            val lt = doc.select("input[name=lt]").`val`()
            val execution = doc.select("input[name=execution]").`val`()
            val eventId = doc.select("input[name=_eventId]").`val`().ifEmpty { "submit" }

            Log.d(TAG, "Parsed CAS tokens: lt=$lt, execution=$execution, _eventId=$eventId")

            if (lt.isEmpty() || execution.isEmpty()) {
                return@withContext Result.failure(IOException("无法解析 CAS 认证令牌 (lt=$lt, execution=$execution)"))
            }

            // 3. Post credentials to CAS SSO
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
                .url(casUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", casUrl)
                .post(formBody)
                .build()

            val loginResp = client.newCall(loginReq).execute()
            val finalUrl = loginResp.request.url.toString()
            val respCode = loginResp.code
            val respBody = loginResp.body?.string() ?: ""
            Log.d(TAG, "Login finished. Status: $respCode, Final URL: $finalUrl")

            // Check if we arrived in jwglxt
            if (!finalUrl.contains("jwglxt") && !finalUrl.contains("index_initMenu")) {
                val errDoc = Jsoup.parse(respBody)
                val errMsg = errDoc.select("#showErrorTip, .auth_error, #msg, .error-message").text().trim()
                val failReason = if (errMsg.isNotEmpty()) {
                    errMsg
                } else if (finalUrl.contains("authserver/login")) {
                    "账号或密码错误，请核对后重试"
                } else {
                    "登录失败，停留在未知地址: $finalUrl"
                }
                Log.e(TAG, "Login check failed: $failReason")
                return@withContext Result.failure(IOException(failReason))
            }

            // 4. Fetch full student profile
            Log.d(TAG, "Fetching student profile...")
            val profile = fetchStudentProfile(studentId)
            Log.d(TAG, "Fetched profile successfully: $profile")
            Result.success(profile)
        } catch (e: Exception) {
            Log.e(TAG, "Login exception", e)
            Result.failure(e)
        }
    }

    suspend fun fetchStudentProfile(fallbackStudentId: String = ""): StudentProfile = withContext(Dispatchers.IO) {
        val profileReq = Request.Builder()
            .url(PROFILE_URL)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://jwgl.usst.edu.cn/jwglxt/xtgl/index_initMenu.html")
            .get()
            .build()

        val resp = client.newCall(profileReq).execute()
        val html = resp.body?.string() ?: ""
        Log.d(TAG, "Profile response len: ${html.length}")
        val doc = Jsoup.parse(html)

        fun extractFieldValue(labelText: String): String {
            val labelElem = doc.select("label:matchesOwn(^\\s*${Regex.escape(labelText)}[：:]?\\s*$)").first()
            if (labelElem != null) {
                val formGroup = labelElem.closest(".form-group")
                if (formGroup != null) {
                    val p = formGroup.select("p.form-control-static, span.form-control-static, input").first()
                    if (p != null) {
                        return if (p.tagName() == "input") p.`val`().trim() else p.text().trim()
                    }
                }
            }
            return ""
        }

        var studentId = extractFieldValue("学号")
        var name = extractFieldValue("姓名")
        val pinyin = extractFieldValue("姓名拼音")
        val gender = extractFieldValue("性别")
        var college = extractFieldValue("学院名称")
        val major = extractFieldValue("专业名称")
        var className = extractFieldValue("班级名称")
        val grade = extractFieldValue("年级")
        val status = extractFieldValue("学籍状态").ifEmpty { "在读" }
        val duration = extractFieldValue("学制").ifEmpty { "4" }
        val educationLevel = extractFieldValue("培养层次").ifEmpty { "本科" }
        val studentCategory = extractFieldValue("学生类别")
        val phone = extractFieldValue("手机号码")

        // If primary profile is incomplete, complement with quick dashboard info
        if (name.isEmpty() || college.isEmpty()) {
            Log.d(TAG, "Profile name/college empty, fetching quick info...")
            val quickReq = Request.Builder()
                .url(QUICK_INFO_URL)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://jwgl.usst.edu.cn/jwglxt/xtgl/index_initMenu.html")
                .get()
                .build()

            val quickResp = client.newCall(quickReq).execute()
            val quickHtml = quickResp.body?.string() ?: ""
            val quickDoc = Jsoup.parse(quickHtml)

            val heading = quickDoc.select("h4.media-heading").text().trim()
            if (heading.isNotEmpty()) {
                val parsedName = heading.split("\\s+".toRegex()).firstOrNull() ?: ""
                if (name.isEmpty()) name = parsedName
            }
            val deptClass = quickDoc.select("div.media-body p").text().trim()
            if (deptClass.isNotEmpty()) {
                val parts = deptClass.split("\\s+".toRegex())
                if (college.isEmpty() && parts.isNotEmpty()) college = parts[0]
                if (className.isEmpty() && parts.size > 1) className = parts[1]
            }
        }

        if (studentId.isEmpty()) {
            studentId = fallbackStudentId
        }

        StudentProfile(
            studentId = studentId,
            name = name,
            pinyin = pinyin,
            gender = gender,
            college = college,
            major = major,
            className = className,
            grade = grade,
            status = status,
            durationYears = duration,
            educationLevel = educationLevel,
            studentCategory = studentCategory,
            phone = phone
        )
    }

    suspend fun fetchGrades(context: android.content.Context? = null): Result<cn.edu.usst.jwgl.data.model.GradeReport> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching grades from $GRADE_URL...")
            val formBody = FormBody.Builder()
                .add("xnm", "")
                .add("xqm", "")
                .add("_search", "false")
                .add("nd", System.currentTimeMillis().toString())
                .add("queryModel.showCount", "500")
                .add("queryModel.currentPage", "1")
                .add("queryModel.sortName", "")
                .add("queryModel.sortOrder", "asc")
                .build()

            val req = Request.Builder()
                .url(GRADE_URL)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://jwgl.usst.edu.cn/jwglxt/xtgl/index_initMenu.html")
                .post(formBody)
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            Log.d(TAG, "Grades response status: ${resp.code}, len: ${body.length}")

            if (body.contains("authserver") || !body.trimStart().startsWith("{")) {
                if (context != null) {
                    val auth = cn.edu.usst.jwgl.data.local.AuthPreferences(context)
                    val id = auth.getStudentId()
                    val pwd = auth.getPassword()
                    if (id.isNotEmpty() && pwd.isNotEmpty()) {
                        Log.d(TAG, "Session expired, attempting silent re-login for grades...")
                        val loginRes = login(id, pwd)
                        if (loginRes.isSuccess) {
                            return@withContext fetchGrades(null)
                        }
                    }
                }
                return@withContext Result.failure(IOException("未获取到有效的成绩数据，会话可能已失效"))
            }

            val json = org.json.JSONObject(body)
            val itemsArray = json.optJSONArray("items") ?: org.json.JSONArray()

            val allCourses = mutableListOf<cn.edu.usst.jwgl.data.model.CourseGrade>()
            var totalCredit = 0.0
            var totalScoreWeighted = 0.0
            var totalGpaWeighted = 0.0
            var validScoreCredits = 0.0

            val semesterGroups = linkedMapOf<String, MutableList<cn.edu.usst.jwgl.data.model.CourseGrade>>()

            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.optJSONObject(i) ?: continue
                val courseName = item.optString("kcmc", "")
                val courseId = item.optString("kch", "")
                val examType = item.optString("khfsmc", "").trim()
                val credit = item.optString("xf", "0").toDoubleOrNull() ?: 0.0
                val regularScore = item.optString("pscj", "")
                val regularRatio = item.optString("psbl", "")
                val finalScore = item.optString("qmcj", "")
                val finalRatio = item.optString("qmbl", "")
                val overallScore = item.optString("bfzcj", item.optString("cj", ""))
                val gpa = item.optString("jd", "0").toDoubleOrNull() ?: 0.0
                val yearName = item.optString("xnmmc", "")
                val semesterName = item.optString("xqmmc", "")
                val semTitle = if (yearName.isNotEmpty() && semesterName.isNotEmpty()) {
                    "${yearName}学年 第${semesterName}学期"
                } else {
                    "其他学期"
                }

                val scoreVal = overallScore.toDoubleOrNull() ?: 0.0
                totalCredit += credit
                if (credit > 0.0) {
                    totalScoreWeighted += scoreVal * credit
                    totalGpaWeighted += gpa * credit
                    validScoreCredits += credit
                }

                val courseGrade = cn.edu.usst.jwgl.data.model.CourseGrade(
                    courseId = courseId,
                    courseName = courseName,
                    credit = credit,
                    regularScore = regularScore,
                    regularRatio = regularRatio,
                    finalScore = finalScore,
                    finalRatio = finalRatio,
                    overallScore = overallScore,
                    gpa = gpa,
                    yearName = yearName,
                    semesterName = semesterName,
                    semesterTitle = semTitle,
                    examType = examType
                )
                allCourses.add(courseGrade)

                val semList = semesterGroups.getOrPut(semTitle) { mutableListOf() }
                semList.add(courseGrade)
            }

            val cumulativeWeightedScore = if (validScoreCredits > 0) totalScoreWeighted / validScoreCredits else 0.0
            val cumulativeGpa = if (validScoreCredits > 0) totalGpaWeighted / validScoreCredits else 0.0

            val semesterSummaries = semesterGroups.map { (semTitle, courses) ->
                var semCredits = 0.0
                var semScoreWeighted = 0.0
                var semGpaWeighted = 0.0
                for (c in courses) {
                    semCredits += c.credit
                    val sVal = c.overallScore.toDoubleOrNull() ?: 0.0
                    semScoreWeighted += sVal * c.credit
                    semGpaWeighted += c.gpa * c.credit
                }
                val semAvgScore = if (semCredits > 0) semScoreWeighted / semCredits else 0.0
                val semAvgGpa = if (semCredits > 0) semGpaWeighted / semCredits else 0.0

                cn.edu.usst.jwgl.data.model.SemesterGradeSummary(
                    semesterTitle = semTitle,
                    totalCredits = semCredits,
                    weightedAverageScore = semAvgScore,
                    weightedGpa = semAvgGpa,
                    courses = courses
                )
            }.sortedByDescending { it.semesterTitle }

            val report = cn.edu.usst.jwgl.data.model.GradeReport(
                totalCredits = totalCredit,
                cumulativeWeightedScore = cumulativeWeightedScore,
                cumulativeGpa = cumulativeGpa,
                semesters = semesterSummaries
            )

            Log.d(TAG, "Processed grade report: credits=$totalCredit, avgScore=$cumulativeWeightedScore, gpa=$cumulativeGpa, semesters=${semesterSummaries.size}")
            Result.success(report)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching grades", e)
            Result.failure(e)
        }
    }

    suspend fun fetchTimetable(
        xnm: String = "2025",
        xqm: String = "3",
        context: android.content.Context? = null
    ): Result<cn.edu.usst.jwgl.data.model.TimetableData> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching timetable for xnm=$xnm, xqm=$xqm from $TIMETABLE_URL...")
            val formBody = FormBody.Builder()
                .add("xnm", xnm)
                .add("xqm", xqm)
                .build()

            val req = Request.Builder()
                .url(TIMETABLE_URL)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://jwgl.usst.edu.cn/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151")
                .post(formBody)
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            Log.d(TAG, "Timetable response status: ${resp.code}, len: ${body.length}")

            if (!body.trimStart().startsWith("{")) {
                if (context != null) {
                    val auth = cn.edu.usst.jwgl.data.local.AuthPreferences(context)
                    val id = auth.getStudentId()
                    val pwd = auth.getPassword()
                    if (id.isNotEmpty() && pwd.isNotEmpty()) {
                        Log.d(TAG, "Session expired, attempting silent re-login for timetable...")
                        val loginRes = login(id, pwd)
                        if (loginRes.isSuccess) {
                            return@withContext fetchTimetable(xnm, xqm, null)
                        }
                    }
                }
                return@withContext Result.failure(IOException("未获取到有效的课表数据，会话可能已失效"))
            }

            val json = org.json.JSONObject(body)
            val kbList = json.optJSONArray("kbList") ?: org.json.JSONArray()
            val xsxx = json.optJSONObject("xsxx")

            val studentName = xsxx?.optString("XM", "") ?: ""
            val className = xsxx?.optString("BJMC", "") ?: ""
            val yearName = xsxx?.optString("XNMC", "$xnm-${(xnm.toIntOrNull() ?: 2025) + 1}")
            val semName = xsxx?.optString("XQMMC", if (xqm == "3") "1" else if (xqm == "12") "2" else xqm)
            val semTitle = "${yearName}学年 第${semName}学期"

            val courses = mutableListOf<cn.edu.usst.jwgl.data.model.CourseItem>()
            val courseColorMap = mutableMapOf<String, Int>()
            var nextColor = 0

            for (i in 0 until kbList.length()) {
                val item = kbList.optJSONObject(i) ?: continue
                val courseName = item.optString("kcmc", "").trim()
                if (courseName.isEmpty()) continue

                val teacher = item.optString("xm", "").trim()
                val classroom = item.optString("cdmc", "").trim()
                val dayOfWeek = item.optString("xqj", "1").toIntOrNull() ?: 1
                val jcs = item.optString("jcs", "1-2").trim()
                val zcd = item.optString("zcd", "").trim()
                val credit = item.optString("xf", "0").toDoubleOrNull() ?: 0.0
                val courseType = item.optString("kcxz", "").trim()
                val courseCode = item.optString("kch", item.optString("kch_id", "")).trim()

                var examType = item.optString("khfsmc", item.optString("khfs", "")).trim()
                if (examType.isEmpty()) {
                    examType = if (courseType.contains("考查") || courseType.contains("设计") || courseType.contains("实践") || courseName.contains("设计") || courseName.contains("实验")) "考查" else "考试"
                }

                // Parse sections (e.g. "6-7" -> start=6, step=2; "6-8" -> start=6, step=3)
                val jcParts = jcs.split("-")
                val startSection = jcParts.firstOrNull()?.toIntOrNull() ?: 1
                val endSection = jcParts.lastOrNull()?.toIntOrNull() ?: startSection
                val step = (endSection - startSection + 1).coerceIn(1, 6)

                val slotId = if (courseCode.isNotEmpty()) {
                    "${courseCode}_${dayOfWeek}_${startSection}_${step}"
                } else {
                    "slot_${courseName.hashCode().let { kotlin.math.abs(it) }}_${dayOfWeek}_${startSection}_${step}"
                }

                // Parse weeks (e.g. "3-18周", "1-16周(单)", "7-15周")
                val weeks = parseWeeks(zcd)

                val colorIdx = courseColorMap.getOrPut(courseName) {
                    val c = nextColor
                    nextColor++
                    c
                }

                courses.add(
                    cn.edu.usst.jwgl.data.model.CourseItem(
                        id = slotId,
                        name = courseName,
                        teacher = teacher,
                        classroom = classroom,
                        dayOfWeek = dayOfWeek,
                        startSection = startSection,
                        step = step,
                        rawSections = jcs,
                        weeks = weeks,
                        rawWeeks = zcd,
                        credit = credit,
                        courseType = courseType,
                        colorIndex = colorIdx,
                        courseCode = courseCode,
                        examType = examType
                    )
                )
            }

            val timetableData = cn.edu.usst.jwgl.data.model.TimetableData(
                academicYear = xnm,
                semester = xqm,
                semesterTitle = semTitle,
                studentName = studentName,
                className = className,
                courses = courses,
                currentWeek = cn.edu.usst.jwgl.data.remote.RemoteConfigManager.getCurrentWeek()
            )
            Log.d(TAG, "Successfully parsed ${courses.size} timetable courses for $semTitle")
            Result.success(timetableData)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching timetable", e)
            Result.failure(e)
        }
    }

    fun parseWeeks(zcd: String): List<Int> {
        val result = mutableSetOf<Int>()
        if (zcd.isEmpty()) return emptyList()
        val segments = zcd.split(",", "，")
        for (seg in segments) {
            val isSingle = seg.contains("单")
            val isDouble = seg.contains("双")
            val clean = seg.replace("[^0-9-]".toRegex(), "")
            if (clean.contains("-")) {
                val parts = clean.split("-")
                val start = parts.getOrNull(0)?.toIntOrNull() ?: continue
                val end = parts.getOrNull(1)?.toIntOrNull() ?: start
                for (w in start..end) {
                    if (isSingle && w % 2 == 0) continue
                    if (isDouble && w % 2 != 0) continue
                    result.add(w)
                }
            } else {
                clean.toIntOrNull()?.let { result.add(it) }
            }
        }
        return result.sorted()
    }

    /**
     * 查询学生考试日程列表
     * @param xnm 学年，如 "2026"，空字符串表示所有或当前
     * @param xqm 学期，如 "3" (第1学期) / "12" (第2学期)，空字符串表示所有
     */
    suspend fun fetchExams(
        xnm: String = "",
        xqm: String = "",
        context: android.content.Context? = null
    ): Result<List<ExamItem>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching exams for xnm=$xnm, xqm=$xqm from $EXAM_URL...")
            val formBody = FormBody.Builder()
                .add("xnm", xnm)
                .add("xqm", xqm)
                .add("ksmcdm", "")
                .add("kspmc", "")
                .add("_search", "false")
                .add("nd", System.currentTimeMillis().toString())
                .add("queryModel.showCount", "500")
                .add("queryModel.currentPage", "1")
                .add("queryModel.sortName", "kssj")
                .add("queryModel.sortOrder", "asc")
                .add("time", "0")
                .build()

            val req = Request.Builder()
                .url(EXAM_URL)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://jwgl.usst.edu.cn/jwglxt/kwgl/kscx_cxXsksxxIndex.html?gnmkdm=N358105")
                .post(formBody)
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            Log.d(TAG, "Exams response status: ${resp.code}, len: ${body.length}")

            // 会话失效检测与静默自动重连
            if (body.contains("authserver") || !body.trimStart().startsWith("{")) {
                if (context != null) {
                    val auth = cn.edu.usst.jwgl.data.local.AuthPreferences(context)
                    val id = auth.getStudentId()
                    val pwd = auth.getPassword()
                    if (id.isNotEmpty() && pwd.isNotEmpty()) {
                        Log.d(TAG, "Session expired, attempting silent re-login for exams...")
                        val loginRes = login(id, pwd)
                        if (loginRes.isSuccess) {
                            return@withContext fetchExams(xnm, xqm, null)
                        }
                    }
                }
                return@withContext Result.failure(IOException("未获取到有效的考试数据，会话可能已失效"))
            }

            val json = org.json.JSONObject(body)
            val itemsArray = json.optJSONArray("items") ?: org.json.JSONArray()
            val examList = mutableListOf<ExamItem>()

            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.optJSONObject(i) ?: continue
                val courseName = item.optString("kcmc", "").trim()
                if (courseName.isEmpty()) continue

                val courseCode = item.optString("kch", item.optString("kch_id", "")).trim()
                val examName = item.optString("ksmc", "").trim()
                val examTime = item.optString("kssj", item.optString("kssj_str", "")).trim()
                val location = item.optString("cdmc", item.optString("jsmc", "")).trim()
                val building = item.optString("jzwmc", "").trim()
                val seatNumber = item.optString("zwh", item.optString("zw", "")).trim()
                val session = item.optString("ccmc", item.optString("kccc", "")).trim()
                val examNature = item.optString("ksxz", "").trim()
                val examMethod = item.optString("khfs", item.optString("ksfs", "")).trim()
                val credit = item.optString("xf", "0").toDoubleOrNull() ?: 0.0
                val yearName = item.optString("xnmmc", "")
                val semesterName = item.optString("xqmmc", "")
                val remarks = item.optString("bz", "").trim()

                val semTitle = if (yearName.isNotEmpty() && semesterName.isNotEmpty()) {
                    "${yearName}学年 第${semesterName}学期"
                } else {
                    ""
                }

                examList.add(
                    ExamItem(
                        courseName = courseName,
                        courseCode = courseCode,
                        examName = examName,
                        examTime = examTime,
                        location = location,
                        building = building,
                        seatNumber = seatNumber,
                        session = session,
                        examNature = examNature,
                        examMethod = examMethod,
                        credit = credit,
                        yearName = yearName,
                        semesterName = semesterName,
                        semesterTitle = semTitle,
                        remarks = remarks
                    )
                )
            }

            Log.d(TAG, "Successfully parsed ${examList.size} exam items")
            Result.success(examList)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching exams", e)
            Result.failure(e)
        }
    }

    suspend fun downloadGradeDocument(
        context: android.content.Context,
        docType: cn.edu.usst.jwgl.data.model.GradeDocumentType,
        destinationFile: java.io.File,
        studentId: String = ""
    ): Result<java.io.File> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Requesting grade document download: ${docType.displayName}")
            val effectiveStudentId = studentId.ifEmpty {
                val auth = cn.edu.usst.jwgl.data.local.AuthPreferences(context)
                auth.getStudentId()
            }

            if (docType == cn.edu.usst.jwgl.data.model.GradeDocumentType.CHINESE_TRANSCRIPT) {
                val cacheManager = cn.edu.usst.jwgl.data.local.DataCacheManager(context)
                val profile = cacheManager.getProfile() ?: cn.edu.usst.jwgl.data.model.StudentProfile(studentId = effectiveStudentId)
                val grades = cacheManager.getGrades() ?: fetchGrades(context).getOrNull()
                val generatedFile = cn.edu.usst.jwgl.util.DocumentPdfGenerator.generate(
                    context = context,
                    docType = docType,
                    profile = profile,
                    grades = grades,
                    destinationFile = destinationFile
                )
                return@withContext Result.success(generatedFile)
            }

            // 1. Determine endpoints and form parameters according to document type
            // USST (code 10252) uses two distinct endpoints:
            // - N109835 for official certificates (weighted average score, ranking)
            // - N558020 for academic transcripts (grades archive)
            val isCertificate = when (docType) {
                cn.edu.usst.jwgl.data.model.GradeDocumentType.CHINESE_WEIGHTED_SCORE,
                cn.edu.usst.jwgl.data.model.GradeDocumentType.ENGLISH_WEIGHTED_SCORE,
                cn.edu.usst.jwgl.data.model.GradeDocumentType.RANKING_CERTIFICATE -> true
                else -> false
            }

            val endpoint = if (isCertificate) {
                "/bysxxcx/xscjzbdy_dyList.html?gnmkdm=N109835"
            } else {
                "/bysxxcx/xscjzbdy_dyList.html?gnmkdm=N558020"
            }

            val referer = if (isCertificate) {
                "https://jwgl.usst.edu.cn/jwglxt/xszsdy/xszsdy_cxXszsdyIndex.html?gnmkdm=N109835"
            } else {
                "https://jwgl.usst.edu.cn/jwglxt/bysxxcx/xscjzbdy_cxXscjzbdyIndex.html?gnmkdm=N558020"
            }

            val formParams = mutableMapOf(
                "xh_id" to effectiveStudentId,
                "ids" to effectiveStudentId,
                "dyfs" to "1",
                "wjlx" to "pdf"
            )

            when (docType) {
                cn.edu.usst.jwgl.data.model.GradeDocumentType.CHINESE_WEIGHTED_SCORE -> {
                    formParams["gsdygx"] = "10252-xsxxwh-jqpjfzm"
                    formParams["lx"] = "xsxxwh"
                }
                cn.edu.usst.jwgl.data.model.GradeDocumentType.ENGLISH_WEIGHTED_SCORE -> {
                    formParams["gsdygx"] = "10252-xsxxwh-ywjqpjfzm"
                    formParams["lx"] = "xsxxwh"
                }
                cn.edu.usst.jwgl.data.model.GradeDocumentType.RANKING_CERTIFICATE -> {
                    formParams["gsdygx"] = "10252-xsxxwh-zypmzm"
                    formParams["lx"] = "xsxxwh"
                }
                cn.edu.usst.jwgl.data.model.GradeDocumentType.ENGLISH_TRANSCRIPT -> {
                    formParams["gsdygx"] = "10252-yw-gdcjd"
                    formParams["cjdylx"] = "2"
                    formParams["sfgz"] = "1"
                    formParams["whetherTheProfessionalShows"] = "1"
                    formParams["whetherTheClassIsDisplayed"] = "1"
                    formParams["whetherTheCreditsAreDisplayed"] = "1"
                    formParams["whetherTheGradePointIsDisplayed"] = "1"
                    formParams["whetherTheIDNumberIsDisplayed"] = "1"
                    formParams["showAllGradeControl"] = "1"
                    formParams["sfzx"] = "1"
                    formParams["sfby_dm"] = "0"
                }
                cn.edu.usst.jwgl.data.model.GradeDocumentType.CHINESE_TRANSCRIPT -> {
                    // Handled above
                }
            }

            // 2. Perform POST request
            val formBuilder = FormBody.Builder()
            for ((k, v) in formParams) {
                formBuilder.add(k, v)
            }

            val request = Request.Builder()
                .url("https://jwgl.usst.edu.cn/jwglxt$endpoint")
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .header("Accept", "application/pdf,application/octet-stream,text/html,application/json,*/*")
                .post(formBuilder.build())
                .build()

            var response = client.newCall(request).execute()
            var bodyBytes = response.body?.bytes()

            fun isValidPdfResponse(bytes: ByteArray?): Boolean {
                if (bytes == null || bytes.isEmpty()) return false
                if (bytes.size > 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()) {
                    return true
                }
                val str = String(bytes, Charsets.UTF_8)
                return str.contains(".pdf") && !str.contains("<html", ignoreCase = true)
            }

            // 3. Check for session expiration / re-login if response was not a PDF or PDF link
            if (!isValidPdfResponse(bodyBytes)) {
                val auth = cn.edu.usst.jwgl.data.local.AuthPreferences(context)
                val id = auth.getStudentId().ifEmpty { effectiveStudentId }
                val pwd = auth.getPassword()
                if (id.isNotEmpty() && pwd.isNotEmpty()) {
                    Log.d(TAG, "Response was not a PDF (redirected to login or session expired), re-authenticating...")
                    val lRes = login(id, pwd)
                    if (lRes.isSuccess) {
                        response = client.newCall(request).execute()
                        bodyBytes = response.body?.bytes()
                    }
                }
            }

            if (bodyBytes != null && bodyBytes.isNotEmpty()) {
                // If the response is directly a binary PDF (starts with "%PDF")
                if (bodyBytes.size > 4 && bodyBytes[0] == '%'.code.toByte() && bodyBytes[1] == 'P'.code.toByte() && bodyBytes[2] == 'D'.code.toByte() && bodyBytes[3] == 'F'.code.toByte()) {
                    destinationFile.outputStream().use { it.write(bodyBytes) }
                    Log.d(TAG, "Successfully downloaded binary PDF from server: ${destinationFile.absolutePath} (${bodyBytes.size} bytes)")
                    return@withContext Result.success(destinationFile)
                }

                // If the server returns a path (e.g. "\/jwglxt\/templete\/scorePrint\/\/sign_...pdf#成功")
                val resString = String(bodyBytes, Charsets.UTF_8).trim()
                Log.d(TAG, "Server response for document generation: $resString")
                if (resString.contains(".pdf")) {
                    var remoteFilePath = ""
                    if (resString.startsWith("{")) {
                        try {
                            val jsonObj = org.json.JSONObject(resString)
                            remoteFilePath = jsonObj.optString("filePath", jsonObj.optString("url", ""))
                        } catch (_: Exception) {}
                    }
                    if (remoteFilePath.isEmpty()) {
                        val clean = resString.trim('"', '\'', ' ', '\r', '\n')
                        val part = clean.split("#")[0].replace("\\/", "/")
                        if (part.contains(".pdf")) {
                            remoteFilePath = part
                        }
                    }

                    if (remoteFilePath.isNotEmpty()) {
                        val normalizedPath = if (remoteFilePath.startsWith("/")) remoteFilePath else "/$remoteFilePath"
                        val fullUrl = if (remoteFilePath.startsWith("http")) remoteFilePath else "https://jwgl.usst.edu.cn$normalizedPath"
                        Log.d(TAG, "Fetching official PDF from: $fullUrl")
                        kotlinx.coroutines.delay(800) // Allow server report compiler to finish writing file
                        val getReq = Request.Builder()
                            .url(fullUrl)
                            .header("User-Agent", USER_AGENT)
                            .header("Referer", referer)
                            .build()
                        val getResp = client.newCall(getReq).execute()
                        val pdfBytes = getResp.body?.bytes()
                        if (pdfBytes != null && pdfBytes.size > 1000 && pdfBytes[0] == '%'.code.toByte() && pdfBytes[1] == 'P'.code.toByte()) {
                            destinationFile.outputStream().use { it.write(pdfBytes) }
                            Log.d(TAG, "Successfully downloaded official USST PDF: ${destinationFile.absolutePath} (${pdfBytes.size} bytes)")
                            return@withContext Result.success(destinationFile)
                        } else {
                            Log.w(TAG, "Official PDF fetch returned non-PDF or empty: size=${pdfBytes?.size}")
                        }
                    }
                }
            }

            // Fallback: If server did not directly output raw PDF binary (e.g. outside campus network or print window closed),
            // generate standard PDF document using Android's native PdfDocument
            val cacheManager = cn.edu.usst.jwgl.data.local.DataCacheManager(context)
            val profile = cacheManager.getProfile() ?: cn.edu.usst.jwgl.data.model.StudentProfile(studentId = effectiveStudentId)
            val grades = cacheManager.getGrades() ?: fetchGrades(context).getOrNull()

            val generatedFile = cn.edu.usst.jwgl.util.DocumentPdfGenerator.generate(
                context = context,
                docType = docType,
                profile = profile,
                grades = grades,
                destinationFile = destinationFile
            )

            Result.success(generatedFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading grade document", e)
            Result.failure(e)
        }
    }
}

