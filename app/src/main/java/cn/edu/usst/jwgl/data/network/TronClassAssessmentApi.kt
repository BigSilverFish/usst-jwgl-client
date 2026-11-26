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

internal class TronClassAssessmentApi(
    private val executor: TronClassSessionExecutor,
    private val authApi: TronClassAuthApi,
    private val courseApi: TronClassCourseApi
) {
    companion object {
        private const val TAG = "TronClassAssessmentApi"
    }

    private val context get() = executor.context
    private val prefs get() = executor.prefs
    private val gson get() = executor.gson
    private val okHttpClient get() = executor.okHttpClient

    private fun accountKey() = executor.accountKey()
    private suspend fun executeRequest(request: Request, expectedAccount: String? = null) = executor.executeRequest(request, expectedAccount)
    private suspend fun refreshSessionOnce(account: String, previousSession: String?) = executor.refreshSessionOnce(account, previousSession)
    private suspend fun refreshSession(studentId: String? = null) = authApi.refreshSession(studentId)
    private suspend fun logActivityRead(activityId: Long, uploadId: Long = 0L) = courseApi.logActivityRead(activityId, uploadId)
    private fun getUserIdFromSession(): Long = prefs.getUserId().takeIf { it > 0L } ?: 0L

    suspend fun fetchActivityDetail(activityId: Long): Result<TronClassActivityItem> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/activities/$activityId"
            val req = Request.Builder().url(url).build()
            val resp = executeRequest(req)
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("获取活动详情失败: HTTP ${resp.code}"))
            }

            val item: TronClassActivityItem = gson.fromJson(body, TronClassActivityItem::class.java)
            Result.success(item)
        } catch (e: Exception) {
            Log.e(TAG, "fetchActivityDetail error", e)
            Result.failure(e)
        }
    }

    /**
     * 15. 下载课件/附件并提供实时进度回调
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
            val resp = executeRequest(req)

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("下载课件失败: HTTP ${resp.code}"))
            }

            val body = resp.body ?: return@withContext Result.failure(Exception("响应体为空"))
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
            val resp = executeRequest(req)
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("获取作业提交详情失败: HTTP ${resp.code}"))
            }

            val sub: TronClassSubmission = gson.fromJson(body, TronClassSubmission::class.java)
            Result.success(sub)
        } catch (e: Exception) {
            Log.e(TAG, "fetchSubmissionDetail error", e)
            Result.failure(e)
        }
    }

    /**
     * 17. 上传作业附件文件
     * 流程：
     * 1) POST /api/uploads 初始化上传，获取 upload_url å upload_id
     * 2) POST Multipart å° upload_url (å¸¦ x-session-id)
     */
    suspend fun uploadFile(
        file: File,
        fileName: String = file.name,
        parentType: String = "activity",
        allowRetry: Boolean = true,
        onProgress: ((Int) -> Unit)? = null
    ): Result<TronClassUpload> = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() == 0L) {
                return@withContext Result.failure(Exception("待上传文件不存在或内容为空"))
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

            val initResp = executeRequest(initReq)
            val initBody = initResp.body?.string() ?: ""

            if (!initResp.isSuccessful) {
                if (allowRetry && initResp.code == 401 && refreshSession().isSuccess) {
                    return@withContext uploadFile(file, fileName, parentType, allowRetry = false, onProgress = onProgress)
                }
                return@withContext Result.failure(Exception("创建文件上传记录失败: HTTP ${initResp.code}"))
            }

            val initJson = JSONObject(initBody)
            val uploadId = initJson.optLong("id")
            val uploadUrl = initJson.optString("upload_url", initJson.optString("url", ""))

            if (uploadId <= 0L || uploadUrl.isBlank()) {
                return@withContext Result.failure(Exception("服务器未下发有效上传地址"))
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

            val upResp = executeRequest(upReq)
            if (!upResp.isSuccessful) {
                return@withContext Result.failure(Exception("文件二进制传输失败: HTTP ${upResp.code}"))
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
     * 18. 提交作业 / 重新提交作业
     * POST /api/course/activities/{activityId}/submissions
     * 若重新提交：PUT /api/course/activities/{activityId}/submissions?retract=true
     */
    suspend fun submitHomework(
        activityId: Long,
        comment: String,
        uploads: List<TronClassUpload>,
        isResubmit: Boolean = false,
        submissionId: Long? = null,
        allowRetry: Boolean = true
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
                val retractResp = executeRequest(retractReq)
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

            val resp = executeRequest(req)
            val code = resp.code
            val body = resp.body?.string() ?: ""

            if (code == 200 || code == 201) {
                Log.d(TAG, "submitHomework success: HTTP $code")
                Result.success(true)
            } else {
                if (allowRetry && code == 401 && refreshSession().isSuccess) {
                    return@withContext submitHomework(activityId, comment, uploads, false, submissionId, allowRetry = false)
                }
                Result.failure(Exception("提交作业失败: HTTP $code $body"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "submitHomework error", e)
            Result.failure(e)
        }
    }


    suspend fun fetchCourseExams(courseId: Long): Result<List<TronClassExam>> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/api/courses/$courseId/exams"
            val req = Request.Builder().url(url).build()
            val resp = executeRequest(req)
            if (!resp.isSuccessful) {
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
                            val subResp = executeRequest(subReq)
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
            val examResp = executeRequest(examReq)
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
            val subResp = executeRequest(subReq)

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
                val detailResp = executeRequest(detailReq)
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
                val sumResp = executeRequest(sumReq)
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
     * 提交测试/随堂小测作答 (POST /api/exams/{examId}/submissions)
     */
    suspend fun submitExamAnswers(
        examId: Long,
        answers: Map<String, Any>,
        allowRetry: Boolean = true
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val submitUrl = "$BASE_URL/api/exams/$examId/submissions"
            val subjectsArray = org.json.JSONArray()
            for ((sid, ans) in answers) {
                val sObj = JSONObject()
                val sidLong = sid.toLongOrNull()
                if (sidLong != null) sObj.put("subject_id", sidLong) else sObj.put("subject_id", sid)

                when (ans) {
                    is List<*> -> {
                        val arr = org.json.JSONArray()
                        ans.forEach { item ->
                            val optId = item.toString().toLongOrNull()
                            if (optId != null) arr.put(optId)
                        }
                        sObj.put("answer_option_ids", arr)
                    }
                    is Set<*> -> {
                        val arr = org.json.JSONArray()
                        ans.forEach { item ->
                            val optId = item.toString().toLongOrNull()
                            if (optId != null) arr.put(optId)
                        }
                        sObj.put("answer_option_ids", arr)
                    }
                    is Number -> {
                        val arr = org.json.JSONArray().apply { put(ans.toLong()) }
                        sObj.put("answer_option_ids", arr)
                    }
                    is String -> {
                        val optId = ans.toLongOrNull()
                        if (optId != null) {
                            val arr = org.json.JSONArray().apply { put(optId) }
                            sObj.put("answer_option_ids", arr)
                        } else if (ans.contains(",")) {
                            val arr = org.json.JSONArray()
                            ans.split(",").forEach { part ->
                                part.trim().toLongOrNull()?.let { arr.put(it) }
                            }
                            sObj.put("answer_option_ids", arr)
                        } else {
                            val arr = org.json.JSONArray().apply {
                                val b = JSONObject().apply { put("content", ans) }
                                put(b)
                            }
                            sObj.put("answers", arr)
                        }
                    }
                }
                subjectsArray.put(sObj)
            }
            val payload = JSONObject().apply {
                put("submission_data", JSONObject().apply {
                    put("subjects", subjectsArray)
                })
            }
            val reqBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url(submitUrl).post(reqBody).build()
            val resp = executeRequest(req)
            val code = resp.code
            Log.d(TAG, "submitExamAnswers response code: $code")
            if (code == 200 || code == 201) {
                logActivityRead(examId)
                Result.success(true)
            } else {
                if (allowRetry && code == 401 && refreshSession().isSuccess) {
                    return@withContext submitExamAnswers(examId, answers, allowRetry = false)
                }
                logActivityRead(examId)
                Result.success(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "submitExamAnswers failed", e)
            logActivityRead(examId)
            Result.success(true)
        }
    }

    /**
     * 获取视频/课件的在线流媒体播放 URL
     */
}
