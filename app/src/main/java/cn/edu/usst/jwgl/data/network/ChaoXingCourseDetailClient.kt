package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.util.Log
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.model.*
import cn.edu.usst.jwgl.util.ChaoXingRiskControlException
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 超星学习通原生课程详情接口客户端
 * 负责解析章节课件、章节附件直链、作业列表与得分、在线考试与答卷分析
 */
class ChaoXingCourseDetailClient(private val context: Context) {

    private val chaoXingClient = ChaoXingClient(context)
    private val prefs = ChaoXingPreferences(context)
    private val okHttpClient: OkHttpClient = chaoXingClient.getOkHttpClient()
    private val gson = Gson()

    companion object {
        private const val TAG = "ChaoXingCourseDetail"
        private const val MAX_HOMEWORK_DETAIL_PROBES = 5

        fun parseAttachmentsFromHtml(html: String): List<ChaoXingCourseAttachment> =
            ChaoXingHtmlParser.parseAttachmentsFromHtml(html)

        fun normalizeChaoXingImageUrl(src: String): String =
            ChaoXingHtmlParser.normalizeChaoXingImageUrl(src)

        fun parseExamsFromHtml(html: String): List<ChaoXingExam> =
            ChaoXingHtmlParser.parseExamsFromHtml(html)

        fun parseWorkListHtml(html: String, course: ChaoXingCourse): MutableList<ChaoXingWork> =
            ChaoXingHtmlParser.parseWorkListHtml(html, course)

        fun extractWorkEnc(html: String): String =
            ChaoXingHtmlParser.extractWorkEnc(html)

        fun parseStuWorkHtmlForCourse(html: String, course: ChaoXingCourse): List<ChaoXingWork> =
            ChaoXingHtmlParser.parseStuWorkHtmlForCourse(html, course)

        private fun isGraded(status: String): Boolean =
            status.contains("已完成") || status.contains("已批阅")

        fun cleanDeadline(raw: String?): String =
            ChaoXingHtmlParser.cleanDeadline(raw)

        fun extractDeadlineFromText(text: String, html: String = "", aria: String = ""): String =
            ChaoXingHtmlParser.extractDeadlineFromText(text, html, aria)

        fun cleanTeacherComment(commentEl: org.jsoup.nodes.Element?): String =
            ChaoXingHtmlParser.cleanTeacherComment(commentEl)

        fun cleanTeacherCommentText(raw: String?): String =
            ChaoXingHtmlParser.cleanTeacherCommentText(raw)

        fun parseQuestionsFromHtml(html: String): List<ChaoXingQuestionItem> =
            ChaoXingQuestionParser.parseQuestionsFromHtml(html)

        fun parseOptionsFromQuestionElement(qEl: org.jsoup.nodes.Element): List<ChaoXingQuestionOption> =
            ChaoXingQuestionParser.parseOptionsFromQuestionElement(qEl)

        fun resolveQuestionType(
            qEl: org.jsoup.nodes.Element,
            typeScoreText: String,
            options: List<ChaoXingQuestionOption>
        ): Pair<String, String> =
            ChaoXingQuestionParser.resolveQuestionType(qEl, typeScoreText, options)

        fun formatSizeForChaoxing(bytes: Long): String =
            ChaoXingHtmlParser.formatSizeForChaoxing(bytes)

        fun encodeAttachmentName(jsonStr: String): String =
            ChaoXingHtmlParser.encodeAttachmentName(jsonStr)

        fun buildAttachmentIframeHtml(att: ChaoXingCourseAttachment): String =
            ChaoXingHtmlParser.buildAttachmentIframeHtml(att)

        fun parseSizeStrToBytes(sizeStr: String): Long =
            ChaoXingHtmlParser.parseSizeStrToBytes(sizeStr)
    }

    private fun getEffectiveCpi(course: ChaoXingCourse, studentId: String?): String {
        return course.cpi.ifBlank { prefs.getUid(studentId).orEmpty() }
    }

    /**
     * 1. 获取课程章节单元及小节列表
     */
    suspend fun fetchChapters(
        course: ChaoXingCourse,
        studentId: String? = null
    ): Result<List<ChaoXingChapterUnit>> = withContext(Dispatchers.IO) {
        val ensureRes = chaoXingClient.ensureSession(studentId)
        if (ensureRes.isFailure) {
            return@withContext Result.failure(ensureRes.exceptionOrNull() ?: Exception("超星登录失效"))
        }

        val cookies = prefs.getCookies(studentId) ?: return@withContext Result.failure(Exception("Cookie 为空"))
        val cpi = getEffectiveCpi(course, studentId)

        val referer = "https://mooc1.chaoxing.com/visit/interaction?courseid=${course.courseId}&clazzid=${course.classId}&personid=$cpi"
        val url = "https://mooc2-ans.chaoxing.com/mycourse/studentcourse?courseid=${course.courseId}&clazzid=${course.classId}&cpi=$cpi&ut=s"

        try {
            val req = Request.Builder()
                .url(url)
                .header("Cookie", cookies)
                .header("Referer", referer)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            val resp = okHttpClient.newCall(req).execute()
            val html = resp.body?.string() ?: ""

            if (!resp.isSuccessful || html.isBlank()) {
                return@withContext Result.failure(Exception("获取章节列表失败 (${resp.code})"))
            }

            val units = mutableListOf<ChaoXingChapterUnit>()
            val parts = html.split("class=\"chapter_unit\"")
            for (part in parts.drop(1)) {
                val unitTitle = Regex("""class="catalog_name[^"]*"[^>]*>.*?<span title="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                    .find(part)?.groupValues?.get(1)?.trim()
                    ?: Jsoup.parse(part).select(".catalog_name").text().trim().ifBlank { "课程章节" }

                val chapters = mutableListOf<ChaoXingChapter>()
                val matches = Regex("""class="chapter_item"[^>]*?id="cur(\d+)"[^>]*?title="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(part).toList()

                for (i in matches.indices) {
                    val m = matches[i]
                    val startAt = m.range.first
                    val endAt = if (i + 1 < matches.size) matches[i + 1].range.first else part.length
                    val block = part.substring(startAt, endAt)
                    val number = Regex("""class="catalog_sbar"[^>]*>([^<]+)<""")
                        .find(block)?.groupValues?.get(1)?.trim() ?: ""
                    val completed = block.contains("icon_yiwanc") || block.contains("已完成")

                    chapters.add(
                        ChaoXingChapter(
                            id = m.groupValues[1],
                            title = m.groupValues[2].trim(),
                            number = number,
                            completed = completed
                        )
                    )
                }

                if (chapters.isNotEmpty()) {
                    units.add(ChaoXingChapterUnit(title = unitTitle, chapters = chapters))
                }
            }

            if (units.isEmpty()) {
                val chapters = mutableListOf<ChaoXingChapter>()
                val matches = Regex("""class="chapter_item"[^>]*?id="cur(\d+)"[^>]*?title="([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(html)
                for (m in matches) {
                    chapters.add(
                        ChaoXingChapter(
                            id = m.groupValues[1],
                            title = m.groupValues[2].trim(),
                            number = "",
                            completed = false
                        )
                    )
                }
                if (chapters.isNotEmpty()) {
                    units.add(ChaoXingChapterUnit(title = "课程章节", chapters = chapters))
                }
            }

            Log.i(TAG, "Successfully fetched ${units.size} chapter units (${units.sumOf { it.chapters.size }} chapters) for course ${course.courseName}")
            Result.success(units)
        } catch (e: Exception) {
            Log.e(TAG, "fetchChapters error", e)
            Result.failure(e)
        }
    }

    /**
     * 2. 获取指定章节的附件列表 (PPT / Word / PDF / 视频)
     */
    suspend fun fetchAttachments(
        course: ChaoXingCourse,
        chapterId: String,
        studentId: String? = null
    ): Result<List<ChaoXingCourseAttachment>> = withContext(Dispatchers.IO) {
        val ensureRes = chaoXingClient.ensureSession(studentId)
        if (ensureRes.isFailure) {
            return@withContext Result.failure(ensureRes.exceptionOrNull() ?: Exception("超星登录失效"))
        }

        val cookies = prefs.getCookies(studentId) ?: return@withContext Result.failure(Exception("Cookie 为空"))
        val cpi = getEffectiveCpi(course, studentId)

        val referer = "https://mooc1.chaoxing.com/mycourse/studentstudy?chapterId=$chapterId&courseId=${course.courseId}&clazzid=${course.classId}&cpi=$cpi&mooc2=1"
        val url = "https://mooc1.chaoxing.com/mooc-ans/knowledge/cards?clazzid=${course.classId}&courseid=${course.courseId}&knowledgeid=$chapterId&num=0&ut=s&cpi=$cpi&mooc2=1&isMicroCourse=false&editorPreview=0&crossId=0"

        try {
            val req = Request.Builder()
                .url(url)
                .header("Cookie", cookies)
                .header("Referer", referer)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            val resp = okHttpClient.newCall(req).execute()
            val html = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("拉取章节课件卡片失败 (${resp.code})"))
            }

            val list = parseAttachmentsFromHtml(html)
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "fetchAttachments error", e)
            Result.failure(e)
        }
    }

    /**
     * 从 cards 页面 HTML 解析 mArg 中的 attachments 列表
     */
    fun parseAttachmentsFromHtml(html: String): List<ChaoXingCourseAttachment> =
        Companion.parseAttachmentsFromHtml(html)

    /**
     * 3. 请求超星云盘转换结果直链 (PPT/Word 返回 PDF 预览直链，视频返回直链播放流)
     */
    suspend fun fetchYunFile(objectId: String): Result<ChaoXingYunFile> = withContext(Dispatchers.IO) {
        val url = "https://contestyd.chaoxing.com/app/files/$objectId/getYunFiles?key=allData"
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build()

            val resp = okHttpClient.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            if (!resp.isSuccessful || body.isBlank()) {
                return@withContext Result.failure(Exception("获取云盘转换文件失败 (${resp.code})"))
            }

            val type = object : TypeToken<Map<String, Any?>>() {}.type
            val map = gson.fromJson<Map<String, Any?>>(body, type)
            val data = map["data"] as? Map<*, *>

            val pdf = (data?.get("pdf") ?: "").toString()
            val download = (data?.get("download") ?: "").toString()
            val http = (data?.get("http") ?: "").toString()

            Result.success(
                ChaoXingYunFile(
                    pdfUrl = pdf,
                    downloadUrl = download,
                    streamUrl = http
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "fetchYunFile error", e)
            Result.failure(e)
        }
    }

    /**
     * 4. 获取在线考试/测验列表
     */
    suspend fun fetchExams(
        course: ChaoXingCourse,
        studentId: String? = null
    ): Result<List<ChaoXingExam>> = withContext(Dispatchers.IO) {
        val ensureRes = chaoXingClient.ensureSession(studentId)
        if (ensureRes.isFailure) {
            return@withContext Result.failure(ensureRes.exceptionOrNull() ?: Exception("超星登录失效"))
        }

        val cookies = prefs.getCookies(studentId) ?: return@withContext Result.failure(Exception("Cookie 为空"))
        val cpi = getEffectiveCpi(course, studentId)

        val referer = "https://mooc1.chaoxing.com/visit/interaction?courseid=${course.courseId}&clazzid=${course.classId}&personid=$cpi"
        val url = "https://mooc1.chaoxing.com/mooc-ans/mooc2/exam/exam-list?courseid=${course.courseId}&clazzid=${course.classId}&personid=$cpi"

        try {
            val req = Request.Builder()
                .url(url)
                .header("Cookie", cookies)
                .header("Referer", referer)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            val resp = okHttpClient.newCall(req).execute()
            val html = resp.body?.string() ?: ""

            if (!resp.isSuccessful) {
                return@withContext Result.failure(Exception("获取测验考试列表失败 (${resp.code})"))
            }

            val parsedExams = parseExamsFromHtml(html)
            val exams = parsedExams.map {
                it.copy(
                    courseId = course.courseId,
                    classId = course.classId,
                    courseName = course.courseName
                )
            }
            Log.i(TAG, "Successfully fetched ${exams.size} exams for course ${course.courseName}")
            Result.success(exams)
        } catch (e: Exception) {
            Log.e(TAG, "fetchExams error", e)
            Result.failure(e)
        }
    }

    /**
     * 4b. 获取超星在线测验/考试详细试卷与客观题原生作答结构
     */
    suspend fun fetchExamDetail(
        exam: ChaoXingExam,
        course: ChaoXingCourse? = null,
        studentId: String? = null
    ): Result<ChaoXingExamDetail> = withContext(Dispatchers.IO) {
        val ensureRes = chaoXingClient.ensureSession(studentId)
        if (ensureRes.isFailure) {
            return@withContext Result.failure(ensureRes.exceptionOrNull() ?: Exception("超星登录失效"))
        }

        val cookies = prefs.getCookies(studentId) ?: return@withContext Result.failure(Exception("Cookie 为空"))
        val cpi = course?.let { getEffectiveCpi(it, studentId) } ?: prefs.getUid(studentId).orEmpty()

        val targetExamId = exam.id.orEmpty().trim()
        val targetAnswerId = exam.examAnswerId.orEmpty().trim()
        val examStatus = exam.status.orEmpty()
        val examScore = exam.score.orEmpty()
        val examTitle = exam.title.orEmpty()
        val examRemaining = exam.remaining.orEmpty()
        val examTaskUrl = exam.taskUrl.orEmpty()
        val examAnalysisUrl = exam.analysisUrl.orEmpty()
        val examCourseName = exam.courseName.orEmpty()
        val examCourseId = exam.courseId.orEmpty()
        val examClassId = exam.classId.orEmpty()

        val cacheMgr = TronClassDataCacheManager.getInstance(context)
        val cached = if (targetExamId.isNotBlank()) cacheMgr.getCachedChaoXingExamDetail(targetExamId) else null

        val targetCourseId = (course?.courseId?.ifBlank { null } ?: examCourseId).trim()
        val targetClassId = (course?.classId?.ifBlank { null } ?: examClassId).trim()
        val isDone = examStatus.contains("已完成") || examStatus.contains("已批") || examScore.isNotBlank() || targetAnswerId.isNotBlank()

        val candidateUrls = mutableListOf<String>()

        // 1. 超星查卷权威路径 /exam-ans/exam/test/look (首选标准 examId 与 examAnswerId)
        if (targetAnswerId.isNotBlank() && targetExamId.isNotBlank()) {
            candidateUrls.add("https://mooc1.chaoxing.com/exam-ans/exam/test/look?courseId=$targetCourseId&classId=$targetClassId&examId=$targetExamId&examAnswerId=$targetAnswerId")
            candidateUrls.add("https://mooc1.chaoxing.com/exam/test/look?courseId=$targetCourseId&classId=$targetClassId&examId=$targetExamId&examAnswerId=$targetAnswerId")
            candidateUrls.add("https://mooc1.chaoxing.com/exam-ans/exam/test/look?courseId=$targetCourseId&classId=$targetClassId&examId=$targetAnswerId&examAnswerId=$targetExamId")
            candidateUrls.add("https://mooc1.chaoxing.com/exam/test/look?courseId=$targetCourseId&classId=$targetClassId&examId=$targetAnswerId&examAnswerId=$targetExamId")
            candidateUrls.add("https://mooc1-api.chaoxing.com/exam-ans/exam/test/look?courseId=$targetCourseId&classId=$targetClassId&examId=$targetExamId&examAnswerId=$targetAnswerId")
        }

        // 2. 如果 taskUrl 不为空，补齐参数作为候选
        if (examTaskUrl.isNotBlank()) {
            val fullTask = if (examTaskUrl.startsWith("http")) examTaskUrl else "https://mooc1.chaoxing.com" + examTaskUrl
            var patchedUrl = fullTask
            if (!patchedUrl.contains("courseId=") && !patchedUrl.contains("courseid=") && targetCourseId.isNotBlank()) {
                patchedUrl += (if (patchedUrl.contains("?")) "&" else "?") + "courseId=$targetCourseId"
            }
            if (!patchedUrl.contains("classId=") && !patchedUrl.contains("clazzid=") && targetClassId.isNotBlank()) {
                patchedUrl += (if (patchedUrl.contains("?")) "&" else "?") + "classId=$targetClassId"
            }
            candidateUrls.add(patchedUrl)
        }

        // 若 targetAnswerId 为空但 targetExamId 存在，分别把 targetExamId 作为 examAnswerId 或 examId 探测
        if (targetAnswerId.isBlank() && targetExamId.isNotBlank()) {
            candidateUrls.add("https://mooc1.chaoxing.com/exam-ans/exam/test/look?courseId=$targetCourseId&classId=$targetClassId&examId=$targetExamId")
            candidateUrls.add("https://mooc1.chaoxing.com/exam-ans/exam/test/look?courseId=$targetCourseId&classId=$targetClassId&examAnswerId=$targetExamId")
            candidateUrls.add("https://mooc1.chaoxing.com/exam/test/look?courseId=$targetCourseId&classId=$targetClassId&examAnswerId=$targetExamId")
        }

        // 3. 兼容移动端预览与重考路径
        if (isDone) {
            candidateUrls.add("https://mooc1.chaoxing.com/mooc-ans/mooc2/exam/preview?courseid=$targetCourseId&clazzid=$targetClassId&examId=$targetExamId")
            candidateUrls.add("https://mooc1.chaoxing.com/exam/test/re-paper?courseId=$targetCourseId&classId=$targetClassId&id=$targetExamId")
            candidateUrls.add("https://mooc1-api.chaoxing.com/mooc-ans/mooc2/exam/preview?courseid=$targetCourseId&clazzid=$targetClassId&examId=$targetExamId")
        } else {
            candidateUrls.add("https://mooc1.chaoxing.com/mooc-ans/mooc2/exam/test?courseid=$targetCourseId&clazzid=$targetClassId&examId=$targetExamId")
            candidateUrls.add("https://mooc1.chaoxing.com/mooc-ans/mooc2/exam/preview?courseid=$targetCourseId&clazzid=$targetClassId&examId=$targetExamId")
            candidateUrls.add("https://mooc1.chaoxing.com/exam/test/re-paper?courseId=$targetCourseId&classId=$targetClassId&id=$targetExamId")
        }

        var lastException: Exception? = null
        for (rawUrl in candidateUrls) {
            if (ChaoXingRequestGuard.isCircuitOpen(context)) break
            try {
                val fullUrl = if (cpi.isNotBlank() && !rawUrl.contains("cpi=") && !rawUrl.contains("personid=")) {
                    rawUrl + (if (rawUrl.contains("?")) "&cpi=$cpi&personid=$cpi" else "?cpi=$cpi&personid=$cpi")
                } else rawUrl

                val req = Request.Builder()
                    .url(fullUrl)
                    .header("Cookie", cookies)
                    .header("Referer", "https://mooc1.chaoxing.com/visit/interaction?courseid=$targetCourseId&clazzid=$targetClassId&personid=$cpi")
                    .header("User-Agent", ChaoXingRequestGuard.DEFAULT_MOBILE_WEB_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .get()
                    .build()

                val resp = okHttpClient.newCall(req).execute()
                val html = resp.body?.string().orEmpty()
                val finalUrl = resp.request.url.toString()

                if (html.isNotBlank()) {
                    val doc = org.jsoup.Jsoup.parse(html)
                    val questions = parseQuestionsFromHtml(html)
                    val hasQuestions = questions.isNotEmpty() || html.contains("TiMu") || html.contains("singleQuesId") || html.contains("questionBlock")

                    // 1. 得分提取与未公布状态研判
                    var extractedScore: String? = null

                    // a. 从专用得分节点提取 (result_number b, font45, font15, mark_score, totalScore, score_num, score, numspan 等)
                    val scoreNodes = doc.select(".result_number b, .last_number b, .font45, .font15, .result_number, .last_number, .mark_score, .totalScore, .score_num, .score, .numspan, .p_score, .grade, .test_score, .colGreen, .colorDeep, .stuScore")
                    for (node in scoreNodes) {
                        val text = node.ownText().ifBlank { node.text() }.trim()
                        val m = Regex("""^(\d+(?:\.\d+)?)$""").find(text)
                            ?: Regex("""(?:本次成绩|最终成绩|考试成绩|成绩|得分|总分)[:：\s]*(\d+(?:\.\d+)?)""").find(text)
                        if (m != null) {
                            extractedScore = m.groupValues[1]
                            break
                        }
                    }

                    // b. 全文正则匹配
                    if (extractedScore.isNullOrBlank()) {
                        extractedScore = Regex("""(?:本次成绩|最终成绩|考试成绩|总得分|本次得分|测验得分|我的得分|最终成绩|试卷得分|得分|成绩)[:：\s]*(?:<[^>]+>)*\s*(\d+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
                            ?: Regex("""class="[^"]*(?:font45|font15|result_number)[^"]*"[^>]*>[\s\S]*?(\d+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
                            ?: Regex("""class="mark_score"[^>]*>[\s\S]*?(\d+(?:\.\d+)?)[\s\S]*?</div>""").find(html)?.groupValues?.get(1)
                            ?: Regex("""class="score_num"[^>]*>(\d+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
                            ?: Regex("""class="score"[^>]*>(\d+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
                            ?: Regex("""class="numspan"[^>]*>(\d+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
                    }

                    // c. 客观题累计得分兜底 (若页面未直接打印总分，但每题客观题作答及得分已经批出)
                    if (extractedScore.isNullOrBlank() && questions.isNotEmpty()) {
                        var hasScoredQuestion = false
                        var totalEarned = 0.0
                        for (q in questions) {
                            val p = Regex("""(\d+(?:\.\d+)?)""").find(q.scoreText)?.groupValues?.get(1)?.toDoubleOrNull()
                            if (p != null && q.cleanMyAnswer.isNotBlank() && q.correctAnswer.isNotBlank()) {
                                if (q.cleanMyAnswer.trim().equals(q.correctAnswer.trim(), ignoreCase = true)) {
                                    totalEarned += p
                                }
                                hasScoredQuestion = true
                            }
                        }
                        if (hasScoredQuestion) {
                            extractedScore = if (totalEarned % 1.0 == 0.0) totalEarned.toInt().toString() else String.format(java.util.Locale.CHINA, "%.1f", totalEarned)
                        }
                    }

                    var scoreStr = extractedScore?.ifBlank { null } ?: examScore

                    val isExplicitlyNotPub = html.contains("成绩未公布") || html.contains("教师未设置允许查看") || html.contains("未开启查看") || html.contains("成绩不公布") || html.contains("未公布成绩")
                    if (scoreStr.isBlank() || scoreStr == "--") {
                        scoreStr = when {
                            html.contains("待批") || html.contains("批阅中") || html.contains("待评阅") -> "待批改"
                            isExplicitlyNotPub -> "教师未公布得分"
                            isDone && questions.isEmpty() -> "教师未公布得分"
                            else -> ""
                        }
                    }

                    // 2. 作答时限与时长
                    val limitMatch = Regex("""(?:限时|考试时长|时长|用时|答题时间)[:：]?\s*(\d+)\s*分钟""").find(html)
                    val limitTime = limitMatch?.groupValues?.get(1)?.toIntOrNull()

                    // 3. 起止时间提取
                    var timeRange = extractDeadlineFromText(html, html)
                    if (timeRange.isBlank()) {
                        timeRange = examRemaining
                    }
                    if (timeRange.isBlank()) {
                        timeRange = if (isDone) "测验已截止" else "时间未公开"
                    }

                    val hasRealQuestions = questions.isNotEmpty()
                    val hasScore = scoreStr.isNotBlank() && scoreStr != "教师未公布得分" && scoreStr != "--"

                    // 只要成功解析出题目，或者提取到了真实测验得分，即视为成功命中真实试卷页
                    if (hasRealQuestions || hasScore) {
                        val examDetail = ChaoXingExamDetail(
                            examId = targetExamId,
                            title = examTitle,
                            courseName = course?.courseName ?: examCourseName,
                            statusText = examStatus.ifBlank { if (isDone) "已完成" else "待作答" },
                            score = scoreStr,
                            timeRange = timeRange,
                            limitTime = limitTime,
                            scoreRule = if (html.contains("最高分")) "highest" else "latest",
                            rawQuestions = questions,
                            analysisUrl = examAnalysisUrl,
                            taskUrl = finalUrl,
                            isSubmitted = isDone,
                            isGraded = scoreStr.isNotBlank() && scoreStr != "教师未公布得分"
                        )

                        cacheMgr.saveChaoXingExamDetail(targetExamId, examDetail)
                        Log.i(TAG, "Successfully fetched ChaoXing exam detail: $examTitle (${questions.size} questions, score=$scoreStr, time=$timeRange)")
                        return@withContext Result.success(examDetail)
                    }
                }
            } catch (e: ChaoXingRiskControlException) {
                lastException = e
                break
            } catch (e: Exception) {
                lastException = e
            }
        }

        if (cached != null) {
            Log.w(TAG, "Returning cached ChaoXingExamDetail for $targetExamId")
            return@withContext Result.success(cached)
        }

        // 兜底优雅降级：构造带有明确测验元数据的详情，杜绝出现界面生硬 "--" 与死锁的 "进行中"
        val fallbackScore = when {
            examScore.isNotBlank() -> examScore
            isDone -> "教师未公布得分"
            else -> ""
        }
        val fallbackTime = when {
            examRemaining.isNotBlank() -> examRemaining
            isDone -> "测验已截止"
            else -> "时间未公开"
        }
        val fallbackDetail = ChaoXingExamDetail(
            examId = targetExamId,
            title = examTitle,
            courseName = course?.courseName ?: examCourseName,
            statusText = examStatus.ifBlank { if (isDone) "已完成" else "待作答" },
            score = fallbackScore,
            timeRange = fallbackTime,
            limitTime = null,
            scoreRule = "highest",
            rawQuestions = emptyList(),
            analysisUrl = examAnalysisUrl,
            taskUrl = if (examTaskUrl.isNotBlank()) examTaskUrl else candidateUrls.firstOrNull().orEmpty(),
            isSubmitted = isDone,
            isGraded = fallbackScore.isNotBlank() && fallbackScore != "教师未公布得分"
        )
        Result.success(fallbackDetail)
    }

    fun parseExamsFromHtml(html: String): List<ChaoXingExam> =
        Companion.parseExamsFromHtml(html)

    /**
     * 5. 获取单门课程的全部作业列表 (可选择是否补全已批阅作业的分数)
     */
    suspend fun fetchHomeworks(
        course: ChaoXingCourse,
        withScores: Boolean = true,
        studentId: String? = null
    ): Result<List<ChaoXingWork>> = withContext(Dispatchers.IO) {
        val ensureRes = chaoXingClient.ensureSession(studentId)
        if (ensureRes.isFailure) {
            return@withContext Result.failure(ensureRes.exceptionOrNull() ?: Exception("超星登录失效"))
        }

        val cookies = prefs.getCookies(studentId) ?: return@withContext Result.failure(Exception("Cookie 为空"))
        val cpi = getEffectiveCpi(course, studentId)

        val allWorks = mutableListOf<ChaoXingWork>()

        try {
            // Channel 1: 尝试从 stucoursemiddle / studentcourse 提取 workEnc 请求 mooc2/work/list
            var workEnc = ""

            // 1a. 优先访问 mooc1-1 stucoursemiddle
            val middleUrl1 = "https://mooc1-1.chaoxing.com/mooc-ans/visit/stucoursemiddle?courseid=${course.courseId}&clazzid=${course.classId}&vc=1&cpi=$cpi&ismooc2=1&v=2"
            try {
                val req1 = Request.Builder()
                    .url(middleUrl1)
                    .header("Cookie", cookies)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .get()
                    .build()
                val resp1 = okHttpClient.newCall(req1).execute()
                val html1 = resp1.body?.string() ?: ""
                workEnc = extractWorkEnc(html1)
            } catch (e: Exception) {
                Log.w(TAG, "stucoursemiddle mooc1-1 failed: ${e.message}")
            }

            // 1b. 若未提取到，尝试 mooc1 stucoursemiddle
            if (workEnc.isBlank()) {
                val middleUrl2 = "https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=${course.courseId}&clazzid=${course.classId}&vc=1&cpi=$cpi"
                try {
                    val req2 = Request.Builder()
                        .url(middleUrl2)
                        .header("Cookie", cookies)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .get()
                        .build()
                    val resp2 = okHttpClient.newCall(req2).execute()
                    val html2 = resp2.body?.string() ?: ""
                    workEnc = extractWorkEnc(html2)
                } catch (e: Exception) {
                    Log.w(TAG, "stucoursemiddle mooc1 failed: ${e.message}")
                }
            }

            // 1c. 若还未提取到，尝试从已有的课程主页 studentcourse 提取
            if (workEnc.isBlank()) {
                val scUrl = "https://mooc2-ans.chaoxing.com/mycourse/studentcourse?courseid=${course.courseId}&clazzid=${course.classId}&cpi=$cpi&ut=s"
                try {
                    val reqSc = Request.Builder()
                        .url(scUrl)
                        .header("Cookie", cookies)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .get()
                        .build()
                    val respSc = okHttpClient.newCall(reqSc).execute()
                    val htmlSc = respSc.body?.string() ?: ""
                    workEnc = extractWorkEnc(htmlSc)
                } catch (e: Exception) {
                    Log.w(TAG, "studentcourse extract workEnc failed: ${e.message}")
                }
            }

            Log.w(TAG, "fetchHomeworks: course=${course.courseName}, courseId=${course.courseId}, classId=${course.classId}, cpi=$cpi, workEnc='$workEnc'")

            // 1d. 如果获取到 workEnc，请求 mooc2/work/list
            if (workEnc.isNotBlank()) {
                val listUrl = "https://mooc1.chaoxing.com/mooc2/work/list?courseId=${course.courseId}&classId=${course.classId}&enc=$workEnc&cpi=$cpi&ut=s"
                try {
                    val reqList = Request.Builder()
                        .url(listUrl)
                        .header("Cookie", cookies)
                        .header("Referer", "https://mooc2-ans.chaoxing.com/mycourse/studentcourse?courseid=${course.courseId}&clazzid=${course.classId}&cpi=$cpi&ut=s")
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .get()
                        .build()

                    val respList = okHttpClient.newCall(reqList).execute()
                    val listHtml = respList.body?.string() ?: ""
                    val works = parseWorkListHtml(listHtml, course)
                    Log.w(TAG, "fetchHomeworks mooc2/work/list parsed ${works.size} works: ${works.map { "[${it.title}] ddl='${it.deadline}' status='${it.statusText}'" }}")
                    allWorks.addAll(works)
                } catch (e: Exception) {
                    Log.w(TAG, "fetchHomeworks mooc2/work/list request failed", e)
                }
            }

            // Channel 2: 优先复用 60 秒内存缓存的全局 STU_WORK_URL HTML，避免多门课重复请求同一全局接口
            try {
                val effectiveAccount = studentId ?: cn.edu.usst.jwgl.data.local.AuthPreferences(context).getStudentId()
                val cachedStuWork = ChaoXingRequestGuard.getCachedStuWorkHtml(effectiveAccount)
                val stuWorkHtml = if (!cachedStuWork.isNullOrBlank()) {
                    cachedStuWork
                } else if (!ChaoXingRequestGuard.isCircuitOpen(context)) {
                    val stuWorkUrl = "https://mooc1-api.chaoxing.com/work/stu-work"
                    val reqStuWork = Request.Builder()
                        .url(stuWorkUrl)
                        .header("Cookie", cookies)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .get()
                        .build()

                    val respStuWork = okHttpClient.newCall(reqStuWork).execute()
                    val fetchedHtml = respStuWork.body?.string() ?: ""
                    if (respStuWork.isSuccessful && fetchedHtml.isNotBlank()) {
                        ChaoXingRequestGuard.putCachedStuWorkHtml(effectiveAccount, fetchedHtml)
                    }
                    fetchedHtml
                } else {
                    ""
                }
                val stuWorks = if (stuWorkHtml.isNotBlank()) parseStuWorkHtmlForCourse(stuWorkHtml, course) else emptyList()
                Log.w(TAG, "fetchHomeworks stu-work parsed ${stuWorks.size} works for course ${course.courseName}: ${stuWorks.map { "[${it.title}] ddl='${it.deadline}' status='${it.statusText}'" }}")

                // 合并去重 (根据 workId)
                val existingIds = allWorks.map { it.workId }.toSet()
                for (sw in stuWorks) {
                    if (!existingIds.contains(sw.workId)) {
                        allWorks.add(sw)
                    }
                }
                Log.w(TAG, "fetchHomeworks total combined works: ${allWorks.size}")
            } catch (e: Exception) {
                Log.w(TAG, "fetchHomeworks STU_WORK_URL fallback error", e)
            }

            // Step 3: 若需要补全已批阅/已完成作业的分数，顺序限流补充（单次最多探测 5 项）
            if (withScores && allWorks.isNotEmpty()) {
                val targets = allWorks.mapIndexedNotNull { index, work ->
                    if (isGraded(work.statusText) && work.score.isBlank()) Pair(index, work) else null
                }
                var scoreProbeCount = 0
                for ((index, work) in targets) {
                    if (scoreProbeCount >= MAX_HOMEWORK_DETAIL_PROBES || ChaoXingRequestGuard.isCircuitOpen(context)) break
                    scoreProbeCount++
                    val score = fetchWorkScore(work, studentId).getOrDefault("")
                    if (score.isNotBlank()) {
                        allWorks[index] = allWorks[index].withScore(score)
                    }
                }
            }

            // Step 3b: 针对未提交且缺失有效截止时间的作业，优先查缓存，未命中时顺序限量探测（单次最多 5 项）
            val unsubmittedWithoutDdl = allWorks.mapIndexedNotNull { index, work ->
                val ddlMs = ChaoXingClient.parseDdlMillis(work.deadline)
                val hasExactDate = work.deadline.isNotBlank() && (work.deadline.contains("-") || work.deadline.contains("/"))
                if (!work.isSubmitted() && (ddlMs == null || !hasExactDate)) Pair(index, work) else null
            }
            if (unsubmittedWithoutDdl.isNotEmpty()) {
                val cacheMgr = cn.edu.usst.jwgl.data.local.TronClassDataCacheManager.getInstance(context)
                var ddlProbeCount = 0
                for ((index, work) in unsubmittedWithoutDdl) {
                    // 优先查二级缓存（不计入网络探测上限）
                    val cached = cacheMgr.getCachedChaoXingHomeworkDetail(work.workId)
                    var ddl = if (cached != null && !cached.deadline.isNullOrBlank()) {
                        cleanDeadline(cached.deadline)
                    } else {
                        ""
                    }
                    // 其次查全局待办缓存（不计入网络探测上限）
                    if (ddl.isBlank()) {
                        val cachedTodo = prefs.getCachedTodos(studentId).find { it.originalTodoId == work.workId }
                        if (cachedTodo?.deadlineMillis != null) {
                            ddl = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(cachedTodo.deadlineMillis!!))
                        }
                    }
                    // 请求详情探测
                    if (ddl.isBlank()) {
                        if (ddlProbeCount >= MAX_HOMEWORK_DETAIL_PROBES || ChaoXingRequestGuard.isCircuitOpen(context)) continue
                        ddlProbeCount++
                        val detailRes = fetchHomeworkDetail(work, course, studentId)
                        ddl = cleanDeadline(detailRes.getOrNull()?.deadline.orEmpty())
                    }
                    if (ddl.isNotBlank()) {
                        val parsedMs = ChaoXingClient.parseDdlMillis(ddl)
                        val isPast = parsedMs != null && parsedMs < System.currentTimeMillis()
                        val isExp = allWorks[index].expired || isPast
                        allWorks[index] = allWorks[index].copy(deadline = ddl, expired = isExp)
                    }
                }
            }

            Log.i(TAG, "Successfully fetched ${allWorks.size} homeworks for course ${course.courseName}")
            Result.success(allWorks)
        } catch (e: Exception) {
            Log.e(TAG, "fetchHomeworks error", e)
            Result.failure(e)
        }
    }

    /**
     * 6. 请求单项已批阅作业的得分
     */
    suspend fun fetchWorkScore(
        work: ChaoXingWork,
        studentId: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val cookies = prefs.getCookies(studentId) ?: return@withContext Result.failure(Exception("Cookie 为空"))
        val url = if (work.taskUrl.isNotBlank()) {
            if (work.taskUrl.startsWith("http")) work.taskUrl else "https://mooc1.chaoxing.com" + work.taskUrl
        } else {
            "https://mooc1.chaoxing.com/mooc2/work/view?workId=${work.workId}"
        }

        try {
            val req = Request.Builder()
                .url(url)
                .header("Cookie", cookies)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            val resp = okHttpClient.newCall(req).execute()
            val html = resp.body?.string() ?: ""

            val score = Regex("""class="score"[^>]*>(\d+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
                ?: Regex("""class="numspan"[^>]*>(\d+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
                ?: Regex("""得分[:：]\s*(\d+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
                ?: ""

            Result.success(score)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun parseWorkListHtml(html: String, course: ChaoXingCourse): MutableList<ChaoXingWork> =
        Companion.parseWorkListHtml(html, course)

    /**
     * 7. 获取超星作业原生详细信息 (题干、各题说明、附件、学生作答、教师批阅与评语)
     */
    suspend fun fetchHomeworkDetail(
        work: ChaoXingWork,
        course: ChaoXingCourse? = null,
        studentId: String? = null
    ): Result<ChaoXingHomeworkDetail> = withContext(Dispatchers.IO) {
        val ensureRes = chaoXingClient.ensureSession(studentId)
        if (ensureRes.isFailure) {
            return@withContext Result.failure(ensureRes.exceptionOrNull() ?: Exception("超星登录失效"))
        }

        val cookies = prefs.getCookies(studentId) ?: return@withContext Result.failure(Exception("Cookie 为空"))
        val cpi = course?.let { getEffectiveCpi(it, studentId) } ?: prefs.getUid(studentId).orEmpty()

        val workIdSafe = work.workId.orEmpty()
        val workTitleSafe = work.title.orEmpty()
        val workTaskUrlSafe = work.taskUrl.orEmpty()
        val workCourseNameSafe = work.courseName.orEmpty()
        val workStatusTextSafe = work.statusText.orEmpty()
        val workDeadlineSafe = work.deadline.orEmpty()
        val workScoreSafe = work.score.orEmpty()

        // 优先检查本地离线缓存
        val cacheMgr = cn.edu.usst.jwgl.data.local.TronClassDataCacheManager.getInstance(context)
        val cached = if (workIdSafe.isNotBlank()) cacheMgr.getCachedChaoXingHomeworkDetail(workIdSafe) else null

        // 构造起始 URL
        var initialUrl = if (workTaskUrlSafe.isNotBlank()) {
            if (workTaskUrlSafe.startsWith("http")) workTaskUrlSafe else "https://mooc1.chaoxing.com" + workTaskUrlSafe
        } else {
            "https://mooc1.chaoxing.com/work/phone/work?courseId=${course?.courseId ?: ""}&classId=${course?.classId ?: ""}&workId=$workIdSafe"
        }
        initialUrl = initialUrl.replace("&amp;", "&")

        if (cpi.isNotBlank() && !initialUrl.contains("cpi=")) {
            initialUrl += if (initialUrl.contains("?")) "&cpi=$cpi" else "?cpi=$cpi"
        }

        try {
            val req1 = Request.Builder()
                .url(initialUrl)
                .header("Cookie", cookies)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            val resp1 = okHttpClient.newCall(req1).execute()
            val html1 = resp1.body?.string() ?: ""
            val finalUrl1 = resp1.request.url.toString()

            var questionsHtml = html1
            var qCountText = ""
            var statusTextFromPage = ""
            var deadlineFromPage = ""

            // 尝试从起始引导页/答题页提取截止时间
            val ddlFromHtml1 = extractDeadlineFromText(html1, html1)
            if (ddlFromHtml1.isNotBlank()) {
                deadlineFromPage = ddlFromHtml1
            }

            // 如果返回的是 task-work 引导页，解析状态、题目总数并提取进入答卷的详情链接
            if (html1.contains("selectWorkQuestion") || html1.contains("GrayWord") || html1.contains("padTop250")) {
                val statusMatch = Regex("""class="GrayWord[^"]*">([^<]+)<""").find(html1)
                if (statusMatch != null) {
                    statusTextFromPage = statusMatch.groupValues[1].trim()
                }

                val countMatch = Regex("""<p>(共\d+题[^<]*)</p>""").find(html1)
                if (countMatch != null) {
                    qCountText = countMatch.groupValues[1].trim()
                }

                // 提取 look / selectWorkQuestionYiPiYue / doHomeWork URL
                val lookMatch = Regex("""(?:location\.origin\s*\+\s*_CP_\s*\+\s*['"]|href=['"]|url\s*=\s*['"]|location\.href\s*=\s*['"]|window\.location(?:\.href)?\s*=\s*['"])(/work/phone/(?:selectWorkQuestion|doHomeWork)[^'"]+)""").find(html1)
                    ?: Regex("""['"](/work/phone/(?:selectWorkQuestion|doHomeWork)[^'"]+)""").find(html1)

                val subPath = lookMatch?.groupValues?.get(1)?.trim()?.replace("&amp;", "&").orEmpty()
                if (subPath.isNotBlank()) {
                    val detailSubUrlRaw = if (subPath.startsWith("http")) {
                        subPath
                    } else {
                        val hostBase = if (finalUrl1.contains("mooc1-api.chaoxing.com")) {
                            "https://mooc1-api.chaoxing.com/mooc-ans"
                        } else {
                            "https://mooc1.chaoxing.com/mooc-ans"
                        }
                        hostBase + (if (subPath.startsWith("/")) subPath else "/$subPath")
                    }
                    // 保留超星原始参数（若为查看/报告模式切勿强行覆盖为 isReport=false，否则会导致超星后端截断为单题滑动模式）
                    val detailSubUrl = if (detailSubUrlRaw.contains("isReport=false")) {
                        detailSubUrlRaw.replace("isReport=false", "isReport=true")
                    } else if (detailSubUrlRaw.contains("selectWorkQuestion") && !detailSubUrlRaw.contains("isReport=")) {
                        detailSubUrlRaw + (if (detailSubUrlRaw.contains("?")) "&isReport=true" else "?isReport=true")
                    } else {
                        detailSubUrlRaw
                    }

                    try {
                        val req2 = Request.Builder()
                            .url(detailSubUrl)
                            .header("Cookie", cookies)
                            .header("Referer", finalUrl1)
                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                            .get()
                            .build()
                        val resp2 = okHttpClient.newCall(req2).execute()
                        val html2 = resp2.body?.string() ?: ""
                        if (html2.isNotBlank()) {
                            questionsHtml = html2
                            val ddlFromHtml2 = extractDeadlineFromText(html2, html2)
                            if (ddlFromHtml2.isNotBlank()) {
                                deadlineFromPage = ddlFromHtml2
                            }
                            // 若返回的内容仍无题目容器，且含有下一层 selectWorkQuestion 链接，才尝试第三级
                            val hasQuestionsAlready = html2.contains("singleQuesId") || html2.contains("TiMu") || html2.contains("Zy_TItle") || html2.contains("questionBlock")
                            if (!hasQuestionsAlready && html2.contains("selectWorkQuestion")) {
                                val secondMatch = Regex("""(?:href=['"]|url\s*=\s*['"])(/work/phone/selectWorkQuestion[^'"]+)""").find(html2)
                                val secondSub = secondMatch?.groupValues?.get(1)?.trim().orEmpty()
                                if (secondSub.isNotBlank()) {
                                    val secondUrl = (if (secondSub.startsWith("http")) secondSub else {
                                        val host = if (finalUrl1.contains("mooc1-api.chaoxing.com")) "https://mooc1-api.chaoxing.com/mooc-ans" else "https://mooc1.chaoxing.com/mooc-ans"
                                        host + (if (secondSub.startsWith("/")) secondSub else "/$secondSub")
                                    }).let {
                                        if (it.contains("isReport=false")) it.replace("isReport=false", "isReport=true") else it
                                    }
                                    try {
                                        val req3 = Request.Builder()
                                            .url(secondUrl)
                                            .header("Cookie", cookies)
                                            .header("Referer", detailSubUrl)
                                            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                                            .get()
                                            .build()
                                        val resp3 = okHttpClient.newCall(req3).execute()
                                        val html3 = resp3.body?.string() ?: ""
                                        if (html3.contains("singleQuesId") || html3.contains("TiMu")) {
                                            questionsHtml = html3
                                        }
                                    } catch (e3: Exception) {
                                        Log.w(TAG, "Failed to fetch question detail level 3: ${e3.message}")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch question detail sub-url: ${e.message}")
                    }
                }
            }

            // 解析详细题目列表
            if (deadlineFromPage.isBlank()) {
                val ddlFromQuestions = extractDeadlineFromText(questionsHtml, questionsHtml)
                if (ddlFromQuestions.isNotBlank()) {
                    deadlineFromPage = ddlFromQuestions
                }
            }

            val parsedQuestions = parseQuestionsFromHtml(questionsHtml)

            // 提取批改得分
            var score = Regex("""class="score"[^>]*>(\d+(?:\.\d+)?)""").find(questionsHtml)?.groupValues?.get(1)
                ?: Regex("""class="numspan"[^>]*>(\d+(?:\.\d+)?)""").find(questionsHtml)?.groupValues?.get(1)
                ?: Regex("""得分[:：]\s*(\d+(?:\.\d+)?)""").find(questionsHtml)?.groupValues?.get(1)
                ?: workScoreSafe

            // 汇总题目说明 (HTML 及纯文本)
            val contentHtmlBuilder = StringBuilder()
            val cleanContentBuilder = StringBuilder()
            val teacherAttachments = mutableListOf<ChaoXingCourseAttachment>()
            val studentAttachments = mutableListOf<ChaoXingCourseAttachment>()
            val studentAnswerBuilder = StringBuilder()
            val teacherCommentBuilder = StringBuilder()

            for (q in parsedQuestions) {
                // 题目与要求
                if (contentHtmlBuilder.isNotEmpty()) {
                    contentHtmlBuilder.append("<br/><br/>")
                    cleanContentBuilder.append("\n\n")
                }
                val qHeader = "第${q.index}题 ${q.typeName} ${if (q.scoreText.isNotBlank()) "(${q.scoreText})" else ""}".trim()
                contentHtmlBuilder.append("<b>").append(qHeader).append("</b><br/>")
                val stemForHtml = if (q.stemHtml.isNotBlank()) q.stemHtml else q.cleanStem
                contentHtmlBuilder.append(stemForHtml)

                // 如果包含客观题选项，将其按行格式化追加
                val qOpts = q.options ?: emptyList()
                if (qOpts.isNotEmpty()) {
                    contentHtmlBuilder.append("<br/>")
                    for (opt in qOpts) {
                        contentHtmlBuilder.append("<br/>&nbsp;&nbsp;").append(opt.letter).append(". ").append(opt.content)
                    }
                }

                cleanContentBuilder.append("【").append(qHeader).append("】\n")
                cleanContentBuilder.append(q.cleanStem)
                if (qOpts.isNotEmpty()) {
                    for (opt in qOpts) {
                        cleanContentBuilder.append("\n  ").append(opt.letter).append(". ").append(opt.content)
                    }
                }

                if (q.cleanMyAnswer.isNotBlank()) {
                    contentHtmlBuilder.append("<br/><br/><b>我的作答:</b> ").append(q.cleanMyAnswer)
                    cleanContentBuilder.append("\n我的作答: ").append(q.cleanMyAnswer)
                }
                if (q.correctAnswer.isNotBlank()) {
                    contentHtmlBuilder.append("<br/><font color='#2E7D32'><b>标准答案:</b> ").append(q.correctAnswer).append("</font>")
                    cleanContentBuilder.append("\n标准答案: ").append(q.correctAnswer)
                }
                if (q.explanation.isNotBlank()) {
                    contentHtmlBuilder.append("<br/><font color='#667085'><b>解析:</b> ").append(q.explanation).append("</font>")
                    cleanContentBuilder.append("\n解析: ").append(q.explanation)
                }

                teacherAttachments.addAll(q.teacherAttachments)
                studentAttachments.addAll(q.studentAttachments)

                // 作答记录
                if (q.cleanMyAnswer.isNotBlank() || q.studentAttachments.isNotEmpty()) {
                    if (studentAnswerBuilder.isNotEmpty()) studentAnswerBuilder.append("\n\n")
                    studentAnswerBuilder.append("【第${q.index}题 作答】：\n")
                    if (q.cleanMyAnswer.isNotBlank()) {
                        studentAnswerBuilder.append(q.cleanMyAnswer)
                    }
                    if (q.studentAttachments.isNotEmpty()) {
                        studentAnswerBuilder.append(" (含附件: ")
                            .append(q.studentAttachments.joinToString(", ") { it.name })
                            .append(")")
                    }
                }

                // 教师评语
                if (q.teacherComment.isNotBlank()) {
                    if (teacherCommentBuilder.isNotEmpty()) teacherCommentBuilder.append("\n")
                    teacherCommentBuilder.append("第${q.index}题批语：").append(q.teacherComment)
                }
            }

            // 全局教师评语备选提取
            if (teacherCommentBuilder.isEmpty()) {
                val globalComment = Regex("""class="(?:pingyu|remark|comment|grayDiv)"[^>]*>([^<]+)<""").find(questionsHtml)?.groupValues?.get(1)?.trim()
                if (!globalComment.isNullOrBlank() && !globalComment.contains("function")) {
                    val cleanGlobal = cleanTeacherCommentText(globalComment)
                    if (cleanGlobal.isNotBlank()) {
                        teacherCommentBuilder.append(cleanGlobal)
                    }
                }
            }

            val finalStatus = when {
                statusTextFromPage.isNotBlank() -> statusTextFromPage
                workStatusTextSafe.isNotBlank() -> workStatusTextSafe
                studentAnswerBuilder.isNotEmpty() || studentAttachments.isNotEmpty() -> "已提交"
                else -> "未提交"
            }

            val isSub = finalStatus.contains("已交") || finalStatus.contains("已完成") || finalStatus.contains("已提交") ||
                    finalStatus.contains("待批") || finalStatus.contains("已批") ||
                    studentAnswerBuilder.isNotEmpty() || studentAttachments.isNotEmpty()
            val isGrd = (score.isNotBlank() || finalStatus.contains("已完成") || (finalStatus.contains("已批") && !finalStatus.contains("待批") && !finalStatus.contains("未批"))) &&
                    !finalStatus.contains("待批") && !finalStatus.contains("未批")

            val finalDeadline = deadlineFromPage.ifBlank { cleanDeadline(workDeadlineSafe) }

            val detail = ChaoXingHomeworkDetail(
                workId = workIdSafe,
                title = workTitleSafe,
                courseName = course?.courseName ?: workCourseNameSafe,
                statusText = finalStatus,
                deadline = finalDeadline,
                score = score,
                questionsCountText = qCountText,
                contentHtml = contentHtmlBuilder.toString(),
                cleanContent = cleanContentBuilder.toString(),
                teacherAttachments = teacherAttachments.distinctBy { it.objectId },
                myAnswerHtml = studentAnswerBuilder.toString(),
                cleanMyAnswer = studentAnswerBuilder.toString(),
                studentAttachments = studentAttachments.distinctBy { it.objectId },
                teacherComment = teacherCommentBuilder.toString(),
                isSubmitted = isSub,
                isGraded = isGrd,
                taskUrl = finalUrl1,
                rawQuestions = parsedQuestions
            )

            // 存入本地二级缓存
            cacheMgr.saveChaoXingHomeworkDetail(workIdSafe, detail)

            // 反向同步回填全局待办列表缓存中的截止时间
            if (finalDeadline.isNotBlank()) {
                try {
                    val account = if (!studentId.isNullOrBlank()) studentId else cn.edu.usst.jwgl.data.local.AuthPreferences.currentStudentId(context)
                    val cachedTodos = prefs.getCachedTodos(account).toMutableList()
                    val matchIdx = cachedTodos.indexOfFirst { it.originalTodoId == workIdSafe }
                    val ddlMs = ChaoXingClient.parseDdlMillis(finalDeadline)
                    if (matchIdx >= 0 && ddlMs != null) {
                        val oldTodo = cachedTodos[matchIdx]
                            val isItemLocked = ddlMs < System.currentTimeMillis() ||
                                    finalStatus.contains("已截止") ||
                                    finalStatus.contains("已过期")
                            if (oldTodo.deadlineMillis == null || oldTodo.deadlineMillis != ddlMs || oldTodo.isLocked != isItemLocked) {
                                cachedTodos[matchIdx] = oldTodo.copy(
                                    deadlineDate = Date(ddlMs),
                                    deadlineMillis = ddlMs,
                                    isLocked = isItemLocked
                                )
                            prefs.saveCachedTodos(cachedTodos, account)
                        }
                    }
                } catch (_: Exception) {}
            }

            Log.i(TAG, "Successfully fetched and cached ChaoXing homework detail: $workTitleSafe (${parsedQuestions.size} questions)")
            Result.success(detail)
        } catch (e: Exception) {
            Log.e(TAG, "fetchHomeworkDetail error", e)
            if (cached != null) {
                Log.w(TAG, "Returning cached ChaoXingHomeworkDetail on failure")
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }

    /**
     * 解析答卷或做题页面 HTML 中的全部题目结构
     */
    fun parseQuestionsFromHtml(html: String): List<ChaoXingQuestionItem> =
        Companion.parseQuestionsFromHtml(html)

    /**
     * 8. 上传文件到超星云盘，返回可在作业作答中引用的超星附件实体
     */
    suspend fun uploadAttachmentToPan(
        file: File,
        displayName: String,
        studentId: String? = null,
        onProgress: ((Int) -> Unit)? = null
    ): Result<ChaoXingCourseAttachment> = withContext(Dispatchers.IO) {
        try {
            val ensureRes = chaoXingClient.ensureSession(studentId)
            if (ensureRes.isFailure) {
                return@withContext Result.failure(ensureRes.exceptionOrNull() ?: Exception("超星登录失效"))
            }

            val cookies = prefs.getCookies(studentId) ?: return@withContext Result.failure(Exception("Cookie 为空"))
            val puid = prefs.getUid(studentId).orEmpty().ifBlank {
                Regex("""(?:^|;\s*)(?:UID|_uid)=(\d+)""").find(cookies)?.groupValues?.get(1).orEmpty()
            }

            // 1. 获取上传 Token
            val tokenReq = Request.Builder()
                .url("https://pan-yz.chaoxing.com/api/token/uservalid")
                .header("Cookie", cookies)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .get()
                .build()

            val tokenResp = okHttpClient.newCall(tokenReq).execute()
            val tokenBody = tokenResp.body?.string().orEmpty()
            val tokenJson = try {
                JSONObject(tokenBody)
            } catch (_: Exception) {
                return@withContext Result.failure(Exception("获取超星网盘 Token 失败: $tokenBody"))
            }

            val token = tokenJson.optString("_token")
            if (token.isBlank()) {
                return@withContext Result.failure(Exception("超星网盘上传 Token 为空"))
            }

            // 2. 构造 Multipart 上传请求体 (带进度监听)
            val mimeType = try {
                val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            } catch (_: Throwable) {
                "application/octet-stream"
            }

            val fileReqBody = object : RequestBody() {
                override fun contentType(): okhttp3.MediaType? = mimeType.toMediaTypeOrNull()
                override fun contentLength(): Long = file.length()
                override fun writeTo(sink: okio.BufferedSink) {
                    val total = contentLength()
                    var written = 0L
                    val buffer = ByteArray(8192)
                    file.inputStream().use { input ->
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            sink.write(buffer, 0, read)
                            written += read
                            if (total > 0 && onProgress != null) {
                                val pct = ((written * 100) / total).toInt()
                                onProgress(pct.coerceIn(0, 100))
                            }
                        }
                    }
                }
            }

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("puid", puid)
                .addFormDataPart("_token", token)
                .addFormDataPart("file", displayName, fileReqBody)
                .build()

            val uploadReq = Request.Builder()
                .url("https://pan-yz.chaoxing.com/upload")
                .header("Cookie", cookies)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                .post(multipartBody)
                .build()

            val uploadResp = okHttpClient.newCall(uploadReq).execute()
            val uploadBody = uploadResp.body?.string().orEmpty()
            val uploadJson = try {
                JSONObject(uploadBody)
            } catch (_: Exception) {
                return@withContext Result.failure(Exception("上传响应非 JSON: $uploadBody"))
            }

            if (uploadJson.optBoolean("result") || uploadJson.has("objectId")) {
                val objectId = uploadJson.optString("objectId").ifBlank {
                    uploadJson.optJSONObject("data")?.optString("objectId").orEmpty()
                }
                val size = uploadJson.optJSONObject("data")?.optLong("size", file.length()) ?: file.length()
                val name = uploadJson.optJSONObject("data")?.optString("name", displayName) ?: displayName

                if (objectId.isNotBlank()) {
                    val att = ChaoXingCourseAttachment(
                        objectId = objectId,
                        name = name,
                        size = size
                    )
                    Result.success(att)
                } else {
                    Result.failure(Exception("上传响应缺少 objectId: $uploadBody"))
                }
            } else {
                val msg = uploadJson.optString("msg", "上传失败")
                Result.failure(Exception("超星云盘上传失败: $msg"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadAttachmentToPan error", e)
            Result.failure(e)
        }
    }

    /**
     * 9. 提交或暂存超星作业与附件
     */
    suspend fun submitHomework(
        work: ChaoXingWork,
        course: ChaoXingCourse?,
        comment: String,
        attachments: List<ChaoXingCourseAttachment>,
        isSubmit: Boolean = true,
        studentId: String? = null
    ): Result<ChaoXingSubmitResult> = withContext(Dispatchers.IO) {
        try {
            val ensureRes = chaoXingClient.ensureSession(studentId)
            if (ensureRes.isFailure) {
                return@withContext Result.failure(ensureRes.exceptionOrNull() ?: Exception("超星登录失效"))
            }

            val cookies = prefs.getCookies(studentId) ?: return@withContext Result.failure(Exception("Cookie 为空"))
            val cpi = course?.let { getEffectiveCpi(it, studentId) } ?: prefs.getUid(studentId).orEmpty()

            val cacheMgr = cn.edu.usst.jwgl.data.local.TronClassDataCacheManager.getInstance(context)
            var detail = cacheMgr.getCachedChaoXingHomeworkDetail(work.workId)
            if (detail == null || detail.rawQuestions.isEmpty()) {
                val dRes = fetchHomeworkDetail(work, course, studentId)
                detail = dRes.getOrNull()
            }

            val questions = detail?.rawQuestions.orEmpty()
            val courseId = course?.courseId.orEmpty().ifBlank {
                Regex("""(?:courseId|courseid)=([0-9]+)""").find(work.taskUrl)?.groupValues?.get(1).orEmpty()
            }
            val classId = course?.classId.orEmpty().ifBlank {
                Regex("""(?:classId|clazzId|clazzid)=([0-9]+)""").find(work.taskUrl)?.groupValues?.get(1).orEmpty()
            }

            // 构造作答 HTML (富文本段落 + 超星 insertCloud 附件 iframe)
            val textHtml = if (comment.isNotBlank()) {
                comment.split("\r\n", "\n").joinToString("") { line ->
                    val escaped = line.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    "<p>$escaped</p>"
                }
            } else {
                "<p><br/></p>"
            }
            val iframesHtml = attachments.joinToString("") { buildAttachmentIframeHtml(it) }
            val finalAnswerHtml = textHtml + iframesHtml

            val formBuilder = FormBody.Builder()
            formBuilder.add("courseId", courseId)
            formBuilder.add("classId", classId)
            formBuilder.add("workId", work.workId)
            if (cpi.isNotBlank()) formBuilder.add("cpi", cpi)

            val pyFlag = if (isSubmit) "" else "1"
            formBuilder.add("pyFlag", pyFlag)

            if (questions.isNotEmpty()) {
                val qids = questions.map { it.id.ifBlank { it.index.toString() } }
                formBuilder.add("answerwqbid", qids.joinToString(",") + ",")

                for ((idx, q) in questions.withIndex()) {
                    val qid = q.id.ifBlank { q.index.toString() }
                    formBuilder.add("answertype$qid", q.typeCode.ifBlank { "4" })
                    if (idx == 0) {
                        formBuilder.add("answer$qid", finalAnswerHtml)
                    } else {
                        val prevAns = q.myAnswerHtml.ifBlank { "<p><br/></p>" }
                        formBuilder.add("answer$qid", prevAns)
                    }
                }
            } else {
                formBuilder.add("answerwqbid", "${work.workId},")
                formBuilder.add("answertype${work.workId}", "4")
                formBuilder.add("answer${work.workId}", finalAnswerHtml)
            }

            val request = Request.Builder()
                .url("https://mooc1.chaoxing.com/mooc-ans/work/addStudentWorkNew")
                .header("Host", "mooc1.chaoxing.com")
                .header("Origin", "https://mooc1.chaoxing.com")
                .header("Referer", detail?.taskUrl?.ifBlank { work.taskUrl } ?: work.taskUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "application/json, text/javascript, */*; q=0.01")
                .header("Cookie", cookies)
                .post(formBuilder.build())
                .build()

            val resp = okHttpClient.newCall(request).execute()
            val respBody = resp.body?.string().orEmpty()
            Log.i(TAG, "submitHomework response: code=${resp.code}, body=$respBody")

            val json = try {
                JSONObject(respBody)
            } catch (_: Exception) {
                JSONObject()
            }

            val isSuccess = json.optBoolean("status", false)
            val msg = json.optString("msg", respBody)

            if (isSuccess || respBody.contains("\"status\":true") || msg.contains("成功")) {
                val updatedDetail = detail?.copy(
                    isSubmitted = isSubmit,
                    statusText = if (isSubmit) "已提交 · 待批阅" else "草稿已暂存",
                    cleanMyAnswer = comment,
                    myAnswerHtml = finalAnswerHtml,
                    studentAttachments = attachments
                ) ?: ChaoXingHomeworkDetail(
                    workId = work.workId,
                    title = work.title,
                    courseName = course?.courseName ?: work.courseName,
                    statusText = if (isSubmit) "已提交 · 待批阅" else "草稿已暂存",
                    cleanMyAnswer = comment,
                    myAnswerHtml = finalAnswerHtml,
                    studentAttachments = attachments,
                    isSubmitted = isSubmit
                )
                cacheMgr.saveChaoXingHomeworkDetail(work.workId, updatedDetail)

                Result.success(
                    ChaoXingSubmitResult(
                        isSuccess = true,
                        message = if (isSubmit) "作业与附件已成功提交至超星学习通！" else "作业草稿暂存成功！",
                        isSubmitted = isSubmit
                    )
                )
            } else {
                val userMsg = when {
                    msg.contains("code-2") -> "作业已截止或教师未开放重复提交权限"
                    msg.contains("code-1") -> "作业参数校验未通过，请刷新后重试"
                    msg.isNotBlank() -> msg
                    else -> "提交失败 (${resp.code})"
                }
                Result.failure(Exception(userMsg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "submitHomework exception", e)
            Result.failure(e)
        }
    }
}
