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

            try {
                val doc = org.jsoup.Jsoup.parse(html)
                val lis = doc.select("li[onclick*='goTask'], li[data*='workId'], ul.work-list > li, .work-list > li")
                for (li in lis) {
                    val dataUrl = li.attr("data").ifBlank { li.select("a").attr("href") }
                    val workId = Regex("""(?:workId|workid|taskrefId)=([0-9]+)""").find(dataUrl)?.groupValues?.get(1)
                        ?: Regex("""[0-9]+""").find(li.attr("id"))?.value
                        ?: ""
                    if (workId.isBlank() && dataUrl.isBlank()) continue

                    val label = li.attr("aria-label")
                    var title = ""
                    var status = ""
                    if (label.contains(";")) {
                        val parts = label.split(";")
                        title = parts.first().trim()
                        status = parts.drop(1).joinToString(";").trim()
                    }

                    if (title.isBlank()) {
                        title = li.select(".overHidden2, p.title, .title, .item-title, h3, a, p").text().trim()
                    }
                    if (status.isBlank()) {
                        status = li.select(".status, .state, span.status, p.status, span").text().trim()
                    }

                    val timeRaw = li.select(".time, .end-time, .status-time, div.time").text().trim()
                    val isExpired = li.select(".icon-zy-g").isNotEmpty() ||
                            li.html().contains("icon-zy-g") ||
                            status.contains("已截止")

                    out.add(
                        ChaoXingWork(
                            workId = workId.ifBlank { System.currentTimeMillis().toString() },
                            title = title.ifBlank { "课程作业" },
                            courseName = course.courseName,
                            statusText = status.ifBlank { "未交" },
                            deadline = timeRaw,
                            expired = isExpired,
                            taskUrl = dataUrl.replace("&amp;", "&")
                        )
                    )
                }
                if (out.isNotEmpty()) return out
            } catch (_: Exception) {}

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

        fun extractWorkEnc(html: String): String {
            if (html.isBlank()) return ""
            // 1. id="workEnc" ... value="..."
            Regex("""id=["']workEnc["'][^>]*value=["']([^"']+)["']""").find(html)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it.trim() }
            // 2. value="..." ... id="workEnc"
            Regex("""value=["']([^"']+)["'][^>]*id=["']workEnc["']""").find(html)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it.trim() }
            // 3. name="workEnc" ... value="..."
            Regex("""name=["']workEnc["'][^>]*value=["']([^"']+)["']""").find(html)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it.trim() }
            // 4. value="..." ... name="workEnc"
            Regex("""value=["']([^"']+)["'][^>]*name=["']workEnc["']""").find(html)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it.trim() }
            // 5. JavaScript / JSON: workEnc = "...", 'workEnc': "...", etc.
            Regex("""['"]?workEnc['"]?\s*[:=,]\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it.trim() }
            // 6. URL query parameter: /mooc2/work/list?...enc=...
            Regex("""/mooc2/work/list\?[^"']*enc=([a-zA-Z0-9_-]+)""").find(html)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it.trim() }
            return ""
        }

        fun parseStuWorkHtmlForCourse(html: String, course: ChaoXingCourse): List<ChaoXingWork> {
            val out = mutableListOf<ChaoXingWork>()
            if (html.isBlank()) return out
            try {
                val doc = org.jsoup.Jsoup.parse(html)
                val items = doc.select("ul.stu-work-list > li, .work-list > li, ul > li[data], li[data], li[onclick*='goTask']")

                val cleanTargetCourse = course.courseName
                    .replace(Regex("""\(?[0-9]{4}-[0-9]{4}-[0-9]+\)?"""), "")
                    .replace(Regex("""\[.*?\]|【.*?】|\(.*?\)|（.*?）"""), "")
                    .replace(Regex("""\s+"""), "")
                    .lowercase()
                    .trim()

                for (el in items) {
                    val dataUrl = el.attr("data").ifBlank { el.select("a").attr("href") }
                    if (dataUrl.isBlank()) continue

                    val workId = Regex("""(?:workId|workid|taskrefId)=([0-9]+)""").find(dataUrl)?.groupValues?.get(1)
                        ?: Regex("""[0-9]+""").find(el.attr("id"))?.value
                        ?: ""
                    val cId = Regex("""(?:courseId|courseid)=([0-9]+)""").find(dataUrl)?.groupValues?.get(1) ?: ""
                    val clazzId = Regex("""(?:classId|clazzId|clazzid)=([0-9]+)""").find(dataUrl)?.groupValues?.get(1) ?: ""

                    // 1. 标题提取 (优先读取 div[role=option] > p，其次读取 .title, 兜底读取 aria-label)
                    var title = el.select("div[role='option'] > p, p").firstOrNull()?.text()?.trim().orEmpty()
                    if (title.isBlank()) {
                        title = el.select(".title, .item-title, p.title, h3").text().trim()
                    }
                    if (title.isBlank()) {
                        val aria = el.select("[aria-label]").attr("aria-label").ifBlank { el.attr("aria-label") }
                        title = Regex("""作业名称(.*?)作业状态""").find(aria)?.groupValues?.get(1)?.trim().orEmpty()
                    }

                    // 2. 状态与课程名称 (超星 stu-work 中通常由 div[role=option] 下的两个 span 分别承载状态与课程名称)
                    val optionSpans = el.select("div[role='option'] > span")
                    var statusText = ""
                    var courseName = ""
                    if (optionSpans.isNotEmpty()) {
                        statusText = optionSpans[0].text().trim()
                        if (optionSpans.size >= 2) {
                            courseName = optionSpans[1].text().trim().replace("《", "").replace("》", "").replace("...", "")
                        }
                    }

                    if (statusText.isBlank()) {
                        statusText = el.select(".status, .state, span.status").text().trim()
                    }
                    if (statusText.isBlank()) {
                        val aria = el.select("[aria-label]").attr("aria-label").ifBlank { el.attr("aria-label") }
                        statusText = Regex("""作业状态(.*?)课程""").find(aria)?.groupValues?.get(1)?.trim().orEmpty()
                    }
                    if (statusText.isBlank()) {
                        statusText = "已交"
                    }

                    if (courseName.isBlank()) {
                        courseName = el.select(".course-name, .course, span.course-name").text().trim()
                    }
                    if (courseName.isBlank()) {
                        val aria = el.select("[aria-label]").attr("aria-label").ifBlank { el.attr("aria-label") }
                        courseName = Regex("""课程(.*)""").find(aria)?.groupValues?.get(1)?.trim().orEmpty().replace("...", "")
                    }

                    // 3. 课程归属判定 (多重判定：courseId 匹配、classId 匹配、课程名双向包含匹配)
                    val cleanItemCourse = courseName
                        .replace(Regex("""\(?[0-9]{4}-[0-9]{4}-[0-9]+\)?"""), "")
                        .replace(Regex("""\[.*?\]|【.*?】|\(.*?\)|（.*?）"""), "")
                        .replace(Regex("""\s+"""), "")
                        .lowercase()
                        .trim()

                    val matchCourse = (cId.isNotBlank() && cId == course.courseId) ||
                            (clazzId.isNotBlank() && clazzId == course.classId) ||
                            (cleanItemCourse.isNotBlank() && cleanTargetCourse.isNotBlank() &&
                                    (cleanItemCourse.contains(cleanTargetCourse) || cleanTargetCourse.contains(cleanItemCourse))) ||
                            (courseName.isNotBlank() && (courseName.contains(course.courseName) || course.courseName.contains(courseName)))

                    if (!matchCourse) continue

                    val timeStr = el.select(".time, .end-time, .status-time, span.time, div.time").text().trim()
                    val isExpired = el.select(".icon-zy-g").isNotEmpty() || statusText.contains("已截止")

                    out.add(
                        ChaoXingWork(
                            workId = workId.ifBlank { System.currentTimeMillis().toString() },
                            title = title.ifBlank { "课程作业" },
                            courseName = course.courseName,
                            statusText = statusText,
                            deadline = timeStr,
                            expired = isExpired,
                            taskUrl = dataUrl.replace("&amp;", "&")
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "parseStuWorkHtmlForCourse error: ${e.message}")
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

        /**
         * 解析答卷或做题页面 HTML 中的全部题目结构
         */
        fun parseQuestionsFromHtml(html: String): List<ChaoXingQuestionItem> {
            val list = mutableListOf<ChaoXingQuestionItem>()
            if (html.isBlank()) return list
            try {
                val doc = Jsoup.parse(html)
                val qElements = doc.select(".singleQuesId, div[class*='singleQues'], .TiMu, .mark_item, .questionBlock")

                for ((idx, qEl) in qElements.withIndex()) {
                    val index = idx + 1
                    val typeScoreEl = qEl.select(".stemGray, .question-type, .type").firstOrNull()
                    val typeScoreText = typeScoreEl?.text()?.trim().orEmpty()
                    val scoreMatch = Regex("""(\d+(?:\.\d+)?)\s*分""").find(typeScoreText)
                    val scoreText = scoreMatch?.groupValues?.get(1)?.let { "$it 分" } ?: ""

                    // 题干提取
                    val stemEl = qEl.select(".stemDiv .Picdiv, .Picdiv, .stemDiv, .qtContent, .workTextWrap, .font14").firstOrNull()
                    val stemHtml = stemEl?.html()?.trim().orEmpty()
                    val cleanStem = stemEl?.text()?.trim().orEmpty()

                    // 提取题干中的教师附件或图片
                    val teacherAtts = mutableListOf<ChaoXingCourseAttachment>()
                    if (stemEl != null) {
                        for (iframe in stemEl.select("iframe.attach-iframe, iframe[objectid]")) {
                            val fn = iframe.attr("filename").ifBlank { iframe.attr("name") }
                            val oid = iframe.attr("objectid")
                            val fsStr = iframe.attr("filesize")
                            if (oid.isNotBlank() && fn.isNotBlank()) {
                                teacherAtts.add(
                                    ChaoXingCourseAttachment(
                                        objectId = oid,
                                        name = fn,
                                        size = parseSizeStrToBytes(fsStr)
                                    )
                                )
                            }
                        }
                    }

                    // 学生作答提取
                    val ansEl = qEl.select(".stuAnswerContent, .optionDiv, .myAnswer, .my-answer").firstOrNull()
                    val ansHtml = ansEl?.html()?.trim().orEmpty()
                    val cleanAns = ansEl?.text()?.trim().orEmpty()

                    val studentAtts = mutableListOf<ChaoXingCourseAttachment>()
                    if (ansEl != null) {
                        for (iframe in ansEl.select("iframe.attach-iframe, iframe[objectid]")) {
                            val fn = iframe.attr("filename").ifBlank { iframe.attr("name") }
                            val oid = iframe.attr("objectid")
                            val fsStr = iframe.attr("filesize")
                            if (oid.isNotBlank() && fn.isNotBlank()) {
                                studentAtts.add(
                                    ChaoXingCourseAttachment(
                                        objectId = oid,
                                        name = fn,
                                        size = parseSizeStrToBytes(fsStr)
                                    )
                                )
                            }
                        }
                    }

                    // 教师评语提取
                    val commentEl = qEl.select(".grayDiv, .pingyu, .remark, .comment, .teach-comment").firstOrNull()
                    val commentText = commentEl?.text()?.replace("教师批语", "")?.replace("教师评语", "")?.replace("：", "")?.trim().orEmpty()

                    list.add(
                        ChaoXingQuestionItem(
                            index = index,
                            typeName = typeScoreText.ifBlank { "主观题" },
                            scoreText = scoreText,
                            stemHtml = stemHtml,
                            cleanStem = cleanStem,
                            myAnswerHtml = ansHtml,
                            cleanMyAnswer = cleanAns,
                            teacherComment = commentText,
                            studentAttachments = studentAtts,
                            teacherAttachments = teacherAtts
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "parseQuestionsFromHtml error: ${e.message}")
            }
            return list
        }

        fun parseSizeStrToBytes(sizeStr: String): Long {
            if (sizeStr.isBlank()) return 0L
            val trimmed = sizeStr.trim().uppercase()
            return try {
                when {
                    trimmed.endsWith("KB") || trimmed.endsWith("K") -> {
                        val num = trimmed.replace("KB", "").replace("K", "").trim().toDouble()
                        (num * 1024).toLong()
                    }
                    trimmed.endsWith("MB") || trimmed.endsWith("M") -> {
                        val num = trimmed.replace("MB", "").replace("M", "").trim().toDouble()
                        (num * 1024 * 1024).toLong()
                    }
                    trimmed.endsWith("GB") || trimmed.endsWith("G") -> {
                        val num = trimmed.replace("GB", "").replace("G", "").trim().toDouble()
                        (num * 1024 * 1024 * 1024).toLong()
                    }
                    trimmed.endsWith("B") -> {
                        trimmed.replace("B", "").trim().toLong()
                    }
                    else -> trimmed.toLongOrNull() ?: 0L
                }
            } catch (_: Exception) {
                0L
            }
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
                    Log.w(TAG, "fetchHomeworks mooc2/work/list parsed ${works.size} works")
                    allWorks.addAll(works)
                } catch (e: Exception) {
                    Log.w(TAG, "fetchHomeworks mooc2/work/list request failed", e)
                }
            }

            // Channel 2: 无论 Channel 1 是否获取到，都拉取 STU_WORK_URL (全局作业列表通道) 进行智能补充或兜底
            // 因为超星部分课程作业直接挂在全局作业平台中，且 STU_WORK_URL 包含已批阅与未交状态
            try {
                val stuWorkUrl = "https://mooc1-api.chaoxing.com/work/stu-work"
                val reqStuWork = Request.Builder()
                    .url(stuWorkUrl)
                    .header("Cookie", cookies)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .get()
                    .build()

                val respStuWork = okHttpClient.newCall(reqStuWork).execute()
                val stuWorkHtml = respStuWork.body?.string() ?: ""
                val stuWorks = parseStuWorkHtmlForCourse(stuWorkHtml, course)
                Log.w(TAG, "fetchHomeworks stu-work parsed ${stuWorks.size} works for course ${course.courseName}")

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

            // Step 3: 若需要补全已批阅/已完成作业的分数，并发补充
            if (withScores && allWorks.isNotEmpty()) {
                val targets = allWorks.mapIndexedNotNull { index, work ->
                    if (isGraded(work.statusText) && work.score.isBlank()) Pair(index, work) else null
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
                                allWorks[index] = allWorks[index].withScore(score)
                            }
                        }
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

        // 优先检查本地离线缓存
        val cacheMgr = cn.edu.usst.jwgl.data.local.TronClassDataCacheManager.getInstance(context)
        val cached = cacheMgr.getCachedChaoXingHomeworkDetail(work.workId)

        // 构造起始 URL
        var initialUrl = if (work.taskUrl.isNotBlank()) {
            if (work.taskUrl.startsWith("http")) work.taskUrl else "https://mooc1.chaoxing.com" + work.taskUrl
        } else {
            "https://mooc1.chaoxing.com/work/phone/work?courseId=${course?.courseId ?: ""}&classId=${course?.classId ?: ""}&workId=${work.workId}"
        }

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
                val lookMatch = Regex("""(?:location\.origin\s*\+\s*_CP_\s*\+\s*['"]|href=['"]|url\s*=\s*['"])(/work/phone/selectWorkQuestion[^'"]+)""").find(html1)
                    ?: Regex("""(?:location\.origin\s*\+\s*_CP_\s*\+\s*['"]|href=['"]|url\s*=\s*['"])(/work/phone/doHomeWork[^'"]+)""").find(html1)

                val subPath = lookMatch?.groupValues?.get(1)?.trim().orEmpty()
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
                    val detailSubUrl = if (detailSubUrlRaw.contains("selectWorkQuestion") && !detailSubUrlRaw.contains("isReport=false")) {
                        detailSubUrlRaw + (if (detailSubUrlRaw.contains("?")) "&isReport=false" else "?isReport=false")
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
                            // 若返回的内容仍是答题卡概览，检查是否有带 isReport=false 的终态题目链接
                            if (!html2.contains("singleQuesId") && html2.contains("selectWorkQuestion")) {
                                val secondMatch = Regex("""(?:href=['"]|url\s*=\s*['"])(/work/phone/selectWorkQuestion[^'"]+)""").find(html2)
                                val secondSub = secondMatch?.groupValues?.get(1)?.trim().orEmpty()
                                if (secondSub.isNotBlank()) {
                                    val secondUrl = (if (secondSub.startsWith("http")) secondSub else {
                                        val host = if (finalUrl1.contains("mooc1-api.chaoxing.com")) "https://mooc1-api.chaoxing.com/mooc-ans" else "https://mooc1.chaoxing.com/mooc-ans"
                                        host + (if (secondSub.startsWith("/")) secondSub else "/$secondSub")
                                    }).let {
                                        if (it.contains("selectWorkQuestion") && !it.contains("isReport=false")) {
                                            it + (if (it.contains("?")) "&isReport=false" else "?isReport=false")
                                        } else it
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
            val parsedQuestions = parseQuestionsFromHtml(questionsHtml)

            // 提取批改得分
            var score = Regex("""class="score"[^>]*>(\d+(?:\.\d+)?)""").find(questionsHtml)?.groupValues?.get(1)
                ?: Regex("""class="numspan"[^>]*>(\d+(?:\.\d+)?)""").find(questionsHtml)?.groupValues?.get(1)
                ?: Regex("""得分[:：]\s*(\d+(?:\.\d+)?)""").find(questionsHtml)?.groupValues?.get(1)
                ?: work.score

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
                contentHtmlBuilder.append(q.stemHtml)

                cleanContentBuilder.append("【").append(qHeader).append("】\n")
                cleanContentBuilder.append(q.cleanStem)

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
                    teacherCommentBuilder.append(globalComment)
                }
            }

            val finalStatus = when {
                statusTextFromPage.isNotBlank() -> statusTextFromPage
                work.statusText.isNotBlank() -> work.statusText
                studentAnswerBuilder.isNotEmpty() || studentAttachments.isNotEmpty() -> "已提交"
                else -> "未提交"
            }

            val isSub = finalStatus.contains("已交") || finalStatus.contains("已完成") || finalStatus.contains("批") ||
                    studentAnswerBuilder.isNotEmpty() || studentAttachments.isNotEmpty()
            val isGrd = score.isNotBlank() || finalStatus.contains("已批") || finalStatus.contains("已完成")

            val detail = ChaoXingHomeworkDetail(
                workId = work.workId,
                title = work.title,
                courseName = course?.courseName ?: work.courseName,
                statusText = finalStatus,
                deadline = work.deadline,
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
            cacheMgr.saveChaoXingHomeworkDetail(work.workId, detail)
            Log.i(TAG, "Successfully fetched and cached ChaoXing homework detail: ${work.title} (${parsedQuestions.size} questions)")
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
}
