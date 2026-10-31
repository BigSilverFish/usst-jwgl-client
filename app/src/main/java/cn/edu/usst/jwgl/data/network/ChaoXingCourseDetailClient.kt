package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.util.Log
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

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
        private val staticGson = Gson()

        /**
         * 从 cards 页面 HTML 解析 mArg 中的 attachments 列表
         */
        fun parseAttachmentsFromHtml(html: String): List<ChaoXingCourseAttachment> {
            val marg = extractMarg(html) ?: return emptyList()
            val raw = marg["attachments"] as? List<*> ?: return emptyList()
            val out = mutableListOf<ChaoXingCourseAttachment>()

            for (item in raw) {
                if (item !is Map<*, *>) continue
                val property = item["property"] as? Map<*, *>
                val fields = property ?: item

                val objectId = (fields["objectid"] ?: item["objectId"] ?: "").toString().trim()
                val name = (fields["name"] ?: fields["title"] ?: "").toString().trim()
                if (objectId.isEmpty() || name.isEmpty()) continue

                val size = when (val s = fields["size"]) {
                    is Number -> s.toLong()
                    is String -> s.toLongOrNull() ?: 0L
                    else -> 0L
                }
                val isVideo = item["type"]?.toString() == "video" ||
                        name.endsWith(".mp4", ignoreCase = true) ||
                        name.endsWith(".flv", ignoreCase = true) ||
                        name.endsWith(".wmv", ignoreCase = true) ||
                        name.endsWith(".avi", ignoreCase = true) ||
                        name.endsWith(".rmvb", ignoreCase = true) ||
                        name.endsWith(".mpg", ignoreCase = true) ||
                        name.endsWith(".mov", ignoreCase = true)

                out.add(
                    ChaoXingCourseAttachment(
                        objectId = objectId,
                        name = name,
                        size = size,
                        isVideo = isVideo
                    )
                )
            }
            return out
        }

        private fun extractMarg(html: String): Map<String, Any?>? {
            val pattern = Regex("""mArg\s*=\s*\{""")
            for (match in pattern.findAll(html)) {
                val start = html.indexOf('{', match.range.first)
                if (start < 0) continue
                val end = matchingBrace(html, start)
                if (end < 0) continue
                try {
                    val jsonStr = html.substring(start, end + 1)
                    val type = object : TypeToken<Map<String, Any?>>() {}.type
                    val decoded = staticGson.fromJson<Map<String, Any?>>(jsonStr, type)
                    if (decoded != null && decoded.containsKey("attachments")) {
                        return decoded
                    }
                } catch (_: Exception) {}
            }
            return null
        }

        private fun matchingBrace(text: String, openIndex: Int): Int {
            var depth = 0
            var inString = false
            var escaped = false
            for (i in openIndex until text.length) {
                val ch = text[i]
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '"') {
                        inString = false
                    }
                    continue
                }
                if (ch == '"') {
                    inString = true
                } else if (ch == '{') {
                    depth++
                } else if (ch == '}') {
                    depth--
                    if (depth == 0) return i
                }
            }
            return -1
        }

        fun parseExamsFromHtml(html: String): List<ChaoXingExam> {
            val out = mutableListOf<ChaoXingExam>()
            val blocks = Regex("""<li>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
            for (block in blocks) {
                val body = block.groupValues[1]
                if (!body.contains("overHidden2")) continue

                val rawTitle = Regex("""<p class="overHidden2[^"]*"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
                    .find(body)?.groupValues?.get(1) ?: ""
                val title = stripHtml(rawTitle)
                if (title.isBlank()) continue

                val rawStatus = Regex("""<p class="status[^"]*"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
                    .find(body)?.groupValues?.get(1) ?: ""
                val status = stripHtml(rawStatus)

                val remaining = Regex("""剩余[^<]*""").find(body)?.value?.trim() ?: ""
                val scoreBlock = Regex("""class="numspan"[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
                    .find(body)?.groupValues?.get(1) ?: ""
                val score = Regex("""\d+(?:\.\d+)?""").find(scoreBlock)?.value ?: ""

                val id = Regex("""viewExamAnswer\('(\d+)'""").find(body)?.groupValues?.get(1)
                    ?: Regex("""goTest\('[^']*',\s*(\d+)""").find(body)?.groupValues?.get(1)
                    ?: ""

                val rawAnalysis = Regex("""href="(https://stat2-ans[^"]+)"""").find(body)?.groupValues?.get(1) ?: ""
                val analysisUrl = rawAnalysis.replace("&amp;", "&")

                out.add(
                    ChaoXingExam(
                        id = id,
                        title = title,
                        status = status,
                        remaining = remaining,
                        score = score,
                        analysisUrl = analysisUrl
                    )
                )
            }
            return out
        }

        fun parseWorkListHtml(html: String, course: ChaoXingCourse): MutableList<ChaoXingWork> {
            val out = mutableListOf<ChaoXingWork>()
            if (html.isBlank()) return out

            val tagRe = Regex("""<li[^>]*goTask\(this\)[^>]*>""")
            val matches = tagRe.findAll(html).toList()

            for (m in matches) {
                val tag = m.value
                val dataUrl = extractAttr(tag, "data") ?: continue
                if (dataUrl.isBlank()) continue

                val workId = Regex("""workId=(\d+)""").find(dataUrl)?.groupValues?.get(1) ?: continue
                val label = extractAttr(tag, "aria-label") ?: ""

                var title = ""
                var status = ""
                if (label.contains(";")) {
                    val parts = label.split(";")
                    title = parts.first().trim()
                    status = parts.drop(1).joinToString(";").trim()
                } else {
                    title = label.trim()
                }

                val bodyStart = m.range.last + 1
                val bodyEnd = html.indexOf("</li>", bodyStart)
                val body = if (bodyEnd > bodyStart) html.substring(bodyStart, bodyEnd) else ""

                if (title.isBlank()) {
                    val rawTitle = Regex("""class="overHidden2[^"]*"[^>]*>([^<]+)<""").find(body)?.groupValues?.get(1) ?: "作业"
                    title = rawTitle.trim()
                }
                if (status.isBlank()) {
                    val rawStatus = Regex("""class="status[^"]*"[^>]*>([^<]+)<""").find(body)?.groupValues?.get(1) ?: ""
                    status = rawStatus.trim()
                }

                val timeRaw = Regex("""class="time[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
                    .find(body)?.groupValues?.get(1) ?: ""
                val timeText = stripHtml(timeRaw)

                out.add(
                    ChaoXingWork(
                        workId = workId,
                        title = title,
                        courseName = course.courseName,
                        statusText = status,
                        deadline = timeText,
                        expired = body.contains("icon-zy-g"),
                        taskUrl = dataUrl.replace("&amp;", "&")
                    )
                )
            }
            return out
        }

        private fun isGraded(status: String): Boolean =
            status.contains("已完成") || status.contains("已批阅")

        private fun extractAttr(tag: String, name: String): String? {
            val match = Regex("""$name="([^"]*)"""").find(tag)
            return match?.groupValues?.get(1)?.replace("&amp;", "&")
        }

        private fun stripHtml(html: String): String {
            return html
                .replace(Regex("""<[^>]+>"""), "")
                .replace("&nbsp;", " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }
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

            val exams = parseExamsFromHtml(html)
            Log.i(TAG, "Successfully fetched ${exams.size} exams for course ${course.courseName}")
            Result.success(exams)
        } catch (e: Exception) {
            Log.e(TAG, "fetchExams error", e)
            Result.failure(e)
        }
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

        try {
            // Step 1: 访问 stucoursemiddle 获取 workEnc
            val middleUrl = "https://mooc1-1.chaoxing.com/mooc-ans/visit/stucoursemiddle?courseid=${course.courseId}&clazzid=${course.classId}&vc=1&cpi=$cpi&ismooc2=1&v=2"
            val reqMiddle = Request.Builder()
                .url(middleUrl)
                .header("Cookie", cookies)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            val respMiddle = okHttpClient.newCall(reqMiddle).execute()
            val middleHtml = respMiddle.body?.string() ?: ""

            val workEnc = Regex("""name="workEnc"\s+value="([^"]+)"""").find(middleHtml)?.groupValues?.get(1)
                ?: Regex("""id="workEnc"\s+value="([^"]+)"""").find(middleHtml)?.groupValues?.get(1)
                ?: Regex("""'workEnc'\s*,\s*'([^']+)'""").find(middleHtml)?.groupValues?.get(1)
                ?: ""

            if (workEnc.isBlank()) {
                Log.w(TAG, "workEnc not found for course ${course.courseName}, skipping work list")
                return@withContext Result.success(emptyList())
            }

            // Step 2: 携带 workEnc 请求 work/list
            val listUrl = "https://mooc1.chaoxing.com/mooc2/work/list?courseId=${course.courseId}&classId=${course.classId}&enc=$workEnc&cpi=$cpi&ut=s"
            val reqList = Request.Builder()
                .url(listUrl)
                .header("Cookie", cookies)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            val respList = okHttpClient.newCall(reqList).execute()
            val listHtml = respList.body?.string() ?: ""

            val works = parseWorkListHtml(listHtml, course)

            // Step 3: 若需要补全已批阅/已完成作业的分数，并发补充
            if (withScores && works.isNotEmpty()) {
                val targets = works.mapIndexedNotNull { index, work ->
                    if (isGraded(work.statusText)) Pair(index, work) else null
                }
                if (targets.isNotEmpty()) {
                    coroutineScope {
                        val scoreTasks = targets.map { pair ->
                            async {
                                val score = fetchWorkScore(pair.second, studentId).getOrDefault("")
                                Pair(pair.first, score)
                            }
                        }
                        val results = scoreTasks.awaitAll()
                        for ((index, score) in results) {
                            if (score.isNotBlank()) {
                                works[index] = works[index].withScore(score)
                            }
                        }
                    }
                }
            }

            Log.i(TAG, "Successfully fetched ${works.size} homeworks for course ${course.courseName}")
            Result.success(works)
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
}
