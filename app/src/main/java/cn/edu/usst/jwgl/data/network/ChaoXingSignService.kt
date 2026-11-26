package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.util.Log
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.local.RollcallSubmissionStore
import cn.edu.usst.jwgl.data.model.ChaoXingActiveItem
import cn.edu.usst.jwgl.data.model.ChaoXingActiveListResponse
import cn.edu.usst.jwgl.data.model.ChaoXingCourse
import cn.edu.usst.jwgl.data.model.ChaoXingSignDetail
import cn.edu.usst.jwgl.data.model.ChaoXingSignResult
import cn.edu.usst.jwgl.data.model.UnifiedStudentRollcall
import cn.edu.usst.jwgl.util.ChaoXingRiskControlException
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.net.URLEncoder

/**
 * 超星学习通课堂签到与考勤探测专职服务
 */
internal class ChaoXingSignService(
    private val context: Context,
    private val prefs: ChaoXingPreferences,
    private val gson: Gson,
    private val okHttpClient: OkHttpClient,
    private val submissionClient: OkHttpClient
) {
    companion object {
        private const val TAG = "ChaoXingSignService"
        private const val MOBILE_UA = ChaoXingRequestGuard.MOBILE_APP_UA
        private const val DEFAULT_MAX_COURSES = 12
        private const val MAX_SIGN_HISTORY_DETAIL_PROBES = 15
    }

    private fun Request.Builder.forAccount(account: String, persistResponse: Boolean = true) =
        tag(ChaoXingClient.CookieOwner::class.java, ChaoXingClient.CookieOwner(account, persistResponse))

    suspend fun fetchActiveSignsForCourses(
        courses: List<ChaoXingCourse>,
        studentId: String? = null,
        maxCourses: Int = DEFAULT_MAX_COURSES
    ): Result<List<Pair<ChaoXingCourse, ChaoXingActiveItem>>> = withContext(Dispatchers.IO) {
        val account = studentId ?: AuthPreferences(context).getStudentId()
        val cookies = prefs.getCookies(account) ?: return@withContext Result.failure(Exception("Cookie 为空"))

        try {
            ChaoXingRequestGuard.checkCircuitOrThrow(context)
            val targetCourses = courses
                .distinctBy { "${it.courseId}_${it.classId}" }
                .take(maxCourses.coerceAtLeast(1))

            val results = mutableListOf<Pair<ChaoXingCourse, ChaoXingActiveItem>>()
            for (course in targetCourses) {
                currentCoroutineContext().ensureActive()
                if (ChaoXingRequestGuard.isCircuitOpen(context)) break
                try {
                    val url = "${ChaoXingClient.ACTIVE_LIST_URL}?courseId=${course.courseId}&classId=${course.classId}&fid=0&showNotStartedActive=0&_=${System.currentTimeMillis()}"
                    val req = Request.Builder()
                        .url(url)
                        .forAccount(account)
                        .header("Cookie", cookies)
                        .header("User-Agent", MOBILE_UA)
                        .get()
                        .build()

                    val resp = okHttpClient.newCall(req).execute()
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful || body.isBlank()) continue

                    val parsed = gson.fromJson(body, ChaoXingActiveListResponse::class.java)
                    val activeSigns = parsed.data?.activeList?.filter { it.isSignActivity() && it.isActive() } ?: emptyList()
                    for (item in activeSigns) {
                        results.add(Pair(course, item))
                    }
                } catch (e: ChaoXingRiskControlException) {
                    if (results.isEmpty()) return@withContext Result.failure(e)
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Check active sign failed for course: ${course.courseName}", e)
                }
            }

            for (pair in results) {
                currentCoroutineContext().ensureActive()
                if (ChaoXingRequestGuard.isCircuitOpen(context)) break
                val course = pair.first
                val item = pair.second
                val hintName = "${item.nameOne.orEmpty()} ${item.nameTwo.orEmpty()}".trim()
                val detailRes = fetchSignDetail(
                    courseId = course.courseId,
                    classId = course.classId,
                    activeId = item.id,
                    studentId = account,
                    initialOtherId = item.otherId,
                    initialHintName = hintName
                )
                if (detailRes.isSuccess) {
                    item.signDetail = detailRes.getOrNull()
                }
            }

            Result.success(results)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchActiveSignsForCourses error", e)
            Result.failure(e)
        }
    }

    suspend fun fetchSignDetail(
        courseId: String,
        classId: String,
        activeId: Long,
        studentId: String? = null,
        needConfigProbe: Boolean = true,
        initialOtherId: Int = -1,
        initialHintName: String = ""
    ): Result<ChaoXingSignDetail> = withContext(Dispatchers.IO) {
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

            if (needConfigProbe) {
                try {
                    val infoUrl = "https://mobilelearn.chaoxing.com/v2/apis/active/getPPTActiveInfo?activeId=$activeId"
                    val infoReq = Request.Builder()
                        .url(infoUrl)
                        .forAccount(account)
                        .header("Cookie", cookies)
                        .header("User-Agent", MOBILE_UA)
                        .get()
                        .build()
                    val infoResp = okHttpClient.newCall(infoReq).execute()
                    val infoBody = infoResp.body?.string().orEmpty()
                    if (infoResp.isSuccessful && infoBody.isNotBlank()) {
                        rawJson = infoBody
                        val json = JSONObject(infoBody)
                        val data = json.optJSONObject("data")
                        if (data != null) {
                            signCode = data.optString("signcode", data.optString("signCode", "")).trim().takeIf { it.isNotBlank() }
                            val parsedOtherId = data.optInt("otherId", -1).let {
                                if (it == -1) data.optString("otherId").toIntOrNull() ?: -1 else it
                            }
                            if (parsedOtherId > 0 || (parsedOtherId == 0 && otherId < 0)) {
                                otherId = parsedOtherId
                            }
                            ifPhoto = data.optInt("ifPhoto", 0) == 1 ||
                                    data.optString("ifPhoto") == "1" ||
                                    data.optBoolean("ifPhoto", false)
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

            var isAlreadySigned = false
            var isLate = false
            var isLeave = false
            var isExpired = false
            var statusText: String? = null
            var preHtml = ""
            var cleanBody = ""
            var finalUrl = ""

            try {
                val preUrl = "${ChaoXingClient.PRE_SIGN_URL}?courseId=$courseId&classId=$classId&activePrimaryId=$activeId&uid=$uid&general=1&sys=1&ls=1&appType=15&ut=s"
                val preReq = Request.Builder()
                    .url(preUrl)
                    .forAccount(account)
                    .header("Cookie", cookies)
                    .header("User-Agent", MOBILE_UA)
                    .get()
                    .build()
                val preResp = okHttpClient.newCall(preReq).execute()
                finalUrl = preResp.request.url.toString()
                preHtml = preResp.body?.string().orEmpty()

                val bodyText = if (preHtml.contains("<body")) preHtml.substringAfter("<body").substringBefore("</body>") else preHtml
                cleanBody = Jsoup.parse(bodyText).text()

                if (otherId <= 0) {
                    val htmlOtherId = Regex("""<input[^>]*id=["']otherId["'][^>]*value=["'](\d+)["']|<input[^>]*value=["'](\d+)["'][^>]*id=["']otherId["']""")
                        .find(preHtml)?.let { m -> (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull() }
                    if (htmlOtherId != null && htmlOtherId > 0) {
                        otherId = htmlOtherId
                    }
                }

                val isAbsent = cleanBody.contains("缺勤") ||
                        cleanBody.contains("未签到") ||
                        cleanBody.contains("已过教师设置的截止时间") ||
                        cleanBody.contains("下次早点哦") ||
                        cleanBody.contains("签到已结束") ||
                        cleanBody.contains("活动已结束") ||
                        preHtml.contains("zsign_embarrassed")

                if (cleanBody.contains("迟到")) isLate = true
                if (cleanBody.contains("请假") || cleanBody.contains("早退")) isLeave = true

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

            val isPhotoByConfig = ifPhoto || ChaoXingClient.hasHiddenPhotoFlag(preHtml)
            val typeDisplayName = ChaoXingClient.resolveSignTypeDisplayName(
                otherId = otherId,
                initialHintName = initialHintName,
                ifPhoto = isPhotoByConfig,
                preHtml = preHtml,
                signCode = signCode,
                cleanBody = cleanBody,
                finalUrl = finalUrl,
                locationText = locationText,
                lat = lat
            )

            val detail = ChaoXingSignDetail(
                activeId = activeId,
                otherId = if (otherId >= 0) otherId else 0,
                signTypeDisplayName = typeDisplayName,
                signCode = signCode,
                ifPhoto = isPhotoByConfig,
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

            Log.i(TAG, "fetchSignDetail activeId=$activeId -> otherId=$otherId, ifPhoto=$isPhotoByConfig, type=$typeDisplayName, signCode=$signCode, isAlreadySigned=$isAlreadySigned")
            Result.success(detail)
        } catch (e: Exception) {
            Log.e(TAG, "fetchSignDetail unexpected error", e)
            Result.failure(e)
        }
    }

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
            currentCoroutineContext().ensureActive()
            if (studentId == null && account != AuthPreferences(context).getStudentId()) {
                throw CancellationException("Account changed before attendance write")
            }
            val store = RollcallSubmissionStore(context, "chaoxing")
            if (!store.claim(account, activeId, allowRejectedRetry)) {
                val state = store.state(account, activeId)
                if (state == null) return@withContext Result.failure(Exception("无法持久化签到保护记录，本次未发送"))
                return@withContext Result.success(
                    ChaoXingSignResult(
                        state == "confirmed",
                        if (state == "confirmed") "本机已保存本活动的确认签到结果" else "本活动已有提交记录，请查看学习通官方状态；不会再次发送",
                        activeId,
                        signType
                    )
                )
            }

            try {
                val preOwner = ChaoXingClient.CookieOwner(account)
                val preUrl = "${ChaoXingClient.PRE_SIGN_URL}?courseId=$courseId&classId=$classId&activePrimaryId=$activeId&uid=$uid&general=1&sys=1&ls=1&appType=15&ut=s"
                val preReq = Request.Builder()
                    .url(preUrl)
                    .tag(ChaoXingClient.CookieOwner::class.java, preOwner)
                    .tag(SingleNetworkAttempt::class.java, SingleNetworkAttempt())
                    .header("Cookie", cookies)
                    .header("Cache-Control", "no-store")
                    .header("User-Agent", MOBILE_UA)
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
                currentCoroutineContext().ensureActive()
                if ((studentId == null && account != AuthPreferences(context).getStudentId()) ||
                    prefs.getCookies(account) != submissionCookies || prefs.getUid(account) != uid) {
                    return@withContext Result.success(ChaoXingSignResult(false, "账号或会话已更新，本次已停止，请查看官方签到状态", activeId, signType))
                }

                val submitBuilder = StringBuilder("${ChaoXingClient.SUBMIT_SIGN_URL}?activeId=$activeId&appType=15&ifTiJiao=1&fid=0")

                val cleanEnc = enc?.trim().takeIf { !it.isNullOrBlank() }
                val cleanSignCode = signCode?.trim().takeIf { !it.isNullOrBlank() }
                if (cleanEnc != null && cleanSignCode != null && cleanEnc != cleanSignCode) {
                    val encodedEnc = URLEncoder.encode(cleanEnc, "UTF-8")
                    val encodedCode = URLEncoder.encode(cleanSignCode, "UTF-8")
                    submitBuilder.append("&enc=").append(encodedEnc)
                    submitBuilder.append("&c=").append(encodedCode)
                    submitBuilder.append("&signCode=").append(encodedCode)
                } else {
                    val effectiveCode = cleanSignCode ?: cleanEnc
                    if (!effectiveCode.isNullOrBlank()) {
                        val encodedCode = URLEncoder.encode(effectiveCode, "UTF-8")
                        submitBuilder.append("&signCode=").append(encodedCode)
                        submitBuilder.append("&enc=").append(encodedCode)
                    }
                }

                if (!objectId.isNullOrBlank()) {
                    submitBuilder.append("&objectId=").append(URLEncoder.encode(objectId.trim(), "UTF-8"))
                    submitBuilder.append("&ifPhoto=1")
                }

                if (latitude != null && longitude != null) {
                    submitBuilder.append("&latitude=").append(latitude)
                        .append("&longitude=").append(longitude)
                        .append("&address=").append(URLEncoder.encode(address ?: "上海理工大学", "UTF-8"))
                }

                val submitReq = Request.Builder()
                    .url(submitBuilder.toString())
                    .forAccount(account)
                    .tag(SingleNetworkAttempt::class.java, SingleNetworkAttempt())
                    .header("Cookie", submissionCookies)
                    .header("Cache-Control", "no-store")
                    .header("User-Agent", MOBILE_UA)
                    .get()
                    .build()

                val outcome = submissionClient.newCall(submitReq).awaitBuffered().response(submitReq).use {
                    parseChaoXingSubmission(it.code, it.body?.string().orEmpty(), activeId, signType)
                }
                store.record(account, activeId, outcome.state)
                if (studentId == null && account != AuthPreferences(context).getStudentId()) {
                    throw CancellationException("Account changed while submitting attendance")
                }
                Result.success(outcome.result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "submitSign failed", e)
                Result.success(ChaoXingSignResult(false, "签到结果尚未确认，请查看学习通官方记录；不会再次发送", activeId, signType))
            }
        }
    }

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
                .header("User-Agent", MOBILE_UA)
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

    suspend fun fetchCourseSignHistory(
        courseId: String,
        classId: String,
        studentId: String? = null
    ): Result<List<UnifiedStudentRollcall>> = withContext(Dispatchers.IO) {
        val account = studentId ?: AuthPreferences(context).getStudentId()
        val cookies = prefs.getCookies(account) ?: return@withContext Result.failure(Exception("Cookie 为空"))

        try {
            ChaoXingRequestGuard.checkCircuitOrThrow(context)
            val url = "${ChaoXingClient.ACTIVE_LIST_URL}?courseId=$courseId&classId=$classId&fid=0&showNotStartedActive=0&_=${System.currentTimeMillis()}"
            val req = Request.Builder()
                .url(url)
                .forAccount(account)
                .header("Cookie", cookies)
                .header("User-Agent", MOBILE_UA)
                .get()
                .build()

            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful || body.isBlank()) {
                return@withContext Result.failure(Exception("拉取超星活动列表失败 (${resp.code})"))
            }

            val parsed = gson.fromJson(body, ChaoXingActiveListResponse::class.java)
            val allSignActivities = parsed.data?.activeList
                ?.filter { it.isSignActivity() }
                ?.sortedByDescending { if (it.startTime > 0L) it.startTime else it.createtime }
                ?.take(MAX_SIGN_HISTORY_DETAIL_PROBES)
                ?: emptyList()

            if (allSignActivities.isEmpty()) {
                return@withContext Result.success(emptyList())
            }

            val rollcallList = mutableListOf<UnifiedStudentRollcall>()
            for (item in allSignActivities) {
                currentCoroutineContext().ensureActive()
                val detail = if (!ChaoXingRequestGuard.isCircuitOpen(context)) {
                    val hintName = "${item.nameOne.orEmpty()} ${item.nameTwo.orEmpty()}".trim()
                    fetchSignDetail(
                        courseId = courseId,
                        classId = classId,
                        activeId = item.id,
                        studentId = account,
                        needConfigProbe = item.isActive(),
                        initialOtherId = item.otherId,
                        initialHintName = hintName
                    ).getOrNull()
                } else {
                    null
                }
                rollcallList.add(UnifiedStudentRollcall.fromChaoXing(item, detail))
            }

            val sorted = rollcallList.sortedByDescending { it.timeMillis }
            Result.success(sorted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchCourseSignHistory error for course $courseId", e)
            Result.failure(e)
        }
    }
}
