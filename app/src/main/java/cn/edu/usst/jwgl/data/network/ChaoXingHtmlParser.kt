package cn.edu.usst.jwgl.data.network

import android.util.Log
import cn.edu.usst.jwgl.data.model.ChaoXingCourse
import cn.edu.usst.jwgl.data.model.ChaoXingCourseAttachment
import cn.edu.usst.jwgl.data.model.ChaoXingExam
import cn.edu.usst.jwgl.data.model.ChaoXingWork
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale

/**
 * 超星学习通 HTML 解析与文本清洗纯函数集合
 */
object ChaoXingHtmlParser {
    private const val TAG = "ChaoXingHtmlParser"
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

            val rawAttachmentType = item["type"]?.toString()?.trim().orEmpty()
            val rawModule = fields["module"]?.toString()?.trim().orEmpty()
            val rawPropType = (fields["type"] ?: fields["ext"] ?: fields["suffix"] ?: "").toString().trim()

            val fileExt = normalizeExtensionCandidate(rawPropType).ifBlank {
                extractExtensionFromName(name)
            }

            val isVideo = rawAttachmentType.equals("video", ignoreCase = true) ||
                rawModule.equals("insertvideo", ignoreCase = true) ||
                fileExt in setOf("mp4", "flv", "wmv", "avi", "rmvb", "mpg", "mov", "mkv", "m4v", "webm")

            out.add(
                ChaoXingCourseAttachment(
                    objectId = objectId,
                    name = name,
                    size = size,
                    isVideo = isVideo,
                    fileExt = fileExt,
                    attachmentType = rawAttachmentType,
                    module = rawModule
                )
            )
        }
        return out
    }

    private fun normalizeExtensionCandidate(raw: String): String {
        val trimmed = raw.trim().lowercase(Locale.ROOT)
        if (trimmed.isBlank()) return ""
        val withoutDot = trimmed.trimStart('.')
        if (withoutDot.length !in 1..6 || !withoutDot.all { it.isLetterOrDigit() }) {
            return ""
        }
        if (!trimmed.startsWith(".") && withoutDot in setOf("doc", "book", "video", "audio", "image", "other", "file")) {
            // Note: ".doc" is accepted because it starts with '.'; bare "video"/"audio"/"image"/"book"/"other"/"file" are type labels, not extensions
            return if (withoutDot == "doc") "doc" else ""
        }
        return withoutDot
    }

    private fun extractExtensionFromName(name: String): String {
        val clean = name.substringBefore('?').substringBefore('#').trim()
        if (!clean.contains('.')) return ""
        val ext = clean.substringAfterLast('.', "").trim().lowercase(Locale.ROOT)
        return if (ext.length in 1..6 && ext.all { it.isLetterOrDigit() }) ext else ""
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

    fun normalizeChaoXingImageUrl(src: String): String {
        val s = src.trim()
        return when {
            s.startsWith("//") -> "https:$s"
            s.startsWith("http://") -> "https://" + s.substring(7)
            s.startsWith("https://") -> s
            s.startsWith("/ananas/") || s.startsWith("/star3/") -> "https://p.ananas.chaoxing.com$s"
            s.startsWith("/") -> "https://mooc1.chaoxing.com$s"
            else -> s
        }
    }

    fun parseExamsFromHtml(html: String): List<ChaoXingExam> {
        val out = mutableListOf<ChaoXingExam>()
        val blocks = Regex("""<li>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
        for (block in blocks) {
            val body = block.groupValues[1]
            if (!body.contains("overHidden2") && !body.contains("status")) continue

            val rawTitle = Regex("""<p class="overHidden2[^"]*"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1) ?: ""
            val title = stripHtml(rawTitle).ifBlank {
                stripHtml(Regex("""<h[1-6][^>]*>(.*?)</h[1-6]>""").find(body)?.groupValues?.get(1).orEmpty())
            }
            if (title.isBlank()) continue

            val rawStatus = Regex("""<p class="status[^"]*"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1) ?: ""
            val status = stripHtml(rawStatus)

            // 提取起止时间 / 剩余时间 / 截止时间
            var remaining = Regex("""(?:起止时间|有效时间|测验时间|截止时间|时间)[:：\s]*([^<]+)""").find(body)?.groupValues?.get(1)?.trim().orEmpty()
            if (remaining.isBlank()) {
                remaining = Regex("""\d{4}[-/.]\d{1,2}[-/.]\d{1,2}[^<]*""").find(body)?.value?.trim().orEmpty()
            }
            if (remaining.isBlank()) {
                remaining = Regex("""剩余[^<]*""").find(body)?.value?.trim().orEmpty()
            }
            if (remaining.isBlank()) {
                val timeDoc = Jsoup.parseBodyFragment(body)
                remaining = timeDoc.select(".time, .c_999, .date, .time_box, .end-time, .status-time").text().trim()
            }

            val scoreBlock = Regex("""class="numspan"[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1) ?: ""
            var score = Regex("""\d+(?:\.\d+)?""").find(scoreBlock)?.value ?: ""
            if (score.isBlank()) {
                score = Regex("""class="score"[^>]*>(\d+(?:\.\d+)?)""").find(body)?.groupValues?.get(1)
                    ?: Regex("""(?:得分|成绩)[:：\s]*(\d+(?:\.\d+)?)""").find(body)?.groupValues?.get(1)
                    ?: ""
            }

            if (score.isBlank()) {
                if (body.contains("未公布") || body.contains("不公布") || body.contains("成绩未公布") || status.contains("未公布")) {
                    score = "教师未公布得分"
                } else if (body.contains("待批") || body.contains("待评阅") || status.contains("待批") || status.contains("待评阅")) {
                    score = "待批改"
                }
            }

            // 提取 examAnswerId (答卷作答 ID) 与 examId (测验发布 ID)
            var examId = ""
            var examAnswerId = ""

            val viewAnswerMatch = Regex("""viewExam(?:Answer)?\s*\(\s*['"]?(\d+)['"]?(?:\s*,\s*['"]?(\d+)['"]?)?""").find(body)
            if (viewAnswerMatch != null) {
                val p1 = viewAnswerMatch.groupValues[1]
                val p2 = viewAnswerMatch.groupValues.getOrNull(2).orEmpty()
                if (p2.isNotBlank()) {
                    examId = p1
                    examAnswerId = p2
                } else {
                    examId = p1
                    examAnswerId = p1
                }
            }

            if (examId.isBlank()) {
                examId = Regex("""goTest\('[^']*',\s*['"]?(\d+)['"]?""").find(body)?.groupValues?.get(1).orEmpty()
            }
            if (examId.isBlank()) {
                examId = Regex("""(?:examId|paperId|testId)[=:]\s*['"]?(\d+)['"]?""").find(body)?.groupValues?.get(1).orEmpty()
            }
            if (examAnswerId.isBlank()) {
                examAnswerId = Regex("""(?:examAnswerId|answerId)[=:]\s*['"]?(\d+)['"]?""").find(body)?.groupValues?.get(1).orEmpty()
            }
            if (examId.isBlank()) {
                examId = Regex("""\bid=(\d+)""").find(body)?.groupValues?.get(1).orEmpty()
            }
            if (examId.isBlank() && examAnswerId.isNotBlank()) {
                examId = examAnswerId
            }

            val rawAnalysis = Regex("""href="(https://stat2-ans[^"]+)"""").find(body)?.groupValues?.get(1) ?: ""
            val analysisUrl = rawAnalysis.replace("&amp;", "&")

            var taskUrl = Regex("""(?:href=['"]|data=['"]|window\.open\(['"]|location\.href\s*=\s*['"])(/[^'"]*(?:exam|test|preview|paper|look)[^'"]*)""").find(body)?.groupValues?.get(1)?.replace("&amp;", "&").orEmpty()
            if (taskUrl.isBlank() && examAnswerId.isNotBlank() && examId.isNotBlank()) {
                taskUrl = "/exam-ans/exam/test/look?examId=$examId&examAnswerId=$examAnswerId"
            }

            out.add(
                ChaoXingExam(
                    id = examId,
                    title = title,
                    status = status,
                    remaining = remaining,
                    score = score,
                    analysisUrl = analysisUrl,
                    taskUrl = taskUrl,
                    examAnswerId = examAnswerId
                )
            )
        }
        return out
    }

    fun parseWorkListHtml(html: String, course: ChaoXingCourse): MutableList<ChaoXingWork> {
        val out = mutableListOf<ChaoXingWork>()
        if (html.isBlank()) return out

        try {
            val doc = Jsoup.parse(html)
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

                var timeRaw = extractDeadlineFromText(li.text(), li.html(), li.attr("aria-label"))
                if (timeRaw.isBlank()) {
                    val elemTime = li.select(".time, .end-time, .status-time, div.time, span.time, p.time, .date").text().trim()
                    timeRaw = cleanDeadline(elemTime)
                }
                val isExpired = li.select(".icon-zy-g").isNotEmpty() ||
                        li.html().contains("icon-zy-g") ||
                        li.html().contains("task-work-gray") ||
                        status.contains("已截止") ||
                        status.contains("已过期") ||
                        status.contains("已逾期") ||
                        status.contains("已结束")

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
            var timeText = cleanDeadline(stripHtml(timeRaw))
            if (timeText.isBlank()) {
                timeText = extractDeadlineFromText(body, body, label)
            }

            out.add(
                ChaoXingWork(
                    workId = workId,
                    title = title,
                    courseName = course.courseName,
                    statusText = status,
                    deadline = timeText,
                    expired = body.contains("icon-zy-g") || body.contains("task-work-gray") ||
                            status.contains("已截止") || status.contains("已过期") || status.contains("已逾期"),
                    taskUrl = dataUrl.replace("&amp;", "&")
                )
            )
        }
        return out
    }

    fun extractWorkEnc(html: String): String {
        if (html.isBlank()) return ""
        Regex("""id=["']workEnc["'][^>]*value=["']([^"']+)["']""").find(html)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it.trim() }
        Regex("""value=["']([^"']+)["'][^>]*id=["']workEnc["']""").find(html)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it.trim() }
        Regex("""name=["']workEnc["'][^>]*value=["']([^"']+)["']""").find(html)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it.trim() }
        Regex("""value=["']([^"']+)["'][^>]*name=["']workEnc["']""").find(html)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it.trim() }
        Regex("""['"]?workEnc['"]?\s*[:=,]\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it.trim() }
        Regex("""/mooc2/work/list\?[^"']*enc=([a-zA-Z0-9_-]+)""").find(html)?.groupValues?.get(1)?.let { if (it.isNotBlank()) return it.trim() }
        return ""
    }

    fun parseStuWorkHtmlForCourse(html: String, course: ChaoXingCourse): List<ChaoXingWork> {
        val out = mutableListOf<ChaoXingWork>()
        if (html.isBlank()) return out
        try {
            val doc = Jsoup.parse(html)
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

                var title = el.select("div[role='option'] > p, p").firstOrNull()?.text()?.trim().orEmpty()
                if (title.isBlank()) {
                    title = el.select(".title, .item-title, p.title, h3").text().trim()
                }
                if (title.isBlank()) {
                    val aria = el.select("[aria-label]").attr("aria-label").ifBlank { el.attr("aria-label") }
                    title = Regex("""作业名称(.*?)作业状态""").find(aria)?.groupValues?.get(1)?.trim().orEmpty()
                }

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

                val cleanItemCourse = courseName
                    .replace(Regex("""\(?[0-9]{4}-[0-9]{4}-[0-9]+\)?"""), "")
                    .replace(Regex("""\[.*?\]|【.*?】|\(.*?\)|（.*?）"""), "")
                    .replace(Regex("""\s+"""), "")
                    .lowercase()
                    .trim()

                val isTargetLab = course.courseName.contains("实验") || cleanTargetCourse.contains("实验")
                val isItemLab = courseName.contains("实验") || title.contains("实验") || cleanItemCourse.contains("实验")

                val idMatch = (cId.isNotBlank() && cId == course.courseId) ||
                        (clazzId.isNotBlank() && clazzId == course.classId)
                val nameMatch = if (isTargetLab != isItemLab) false else {
                    (cleanItemCourse.isNotBlank() && cleanTargetCourse.isNotBlank() &&
                            (cleanItemCourse == cleanTargetCourse || cleanItemCourse.contains(cleanTargetCourse) || cleanTargetCourse.contains(cleanItemCourse))) ||
                    (courseName.isNotBlank() && (courseName == course.courseName || courseName.contains(course.courseName) || course.courseName.contains(courseName)))
                }
                if (!idMatch && !nameMatch) continue

                var timeStr = extractDeadlineFromText(el.text(), el.html(), el.select("[aria-label]").attr("aria-label").ifBlank { el.attr("aria-label") })
                if (timeStr.isBlank()) {
                    val elemTime = el.select(".time, .end-time, .status-time, span.time, div.time, p.time, .date").text().trim()
                    timeStr = cleanDeadline(elemTime)
                }
                val isExpired = el.select(".icon-zy-g").isNotEmpty() ||
                        el.html().contains("icon-zy-g") ||
                        el.html().contains("task-work-gray") ||
                        statusText.contains("已截止") ||
                        statusText.contains("已过期") ||
                        statusText.contains("已逾期") ||
                        statusText.contains("已结束")

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
     * 清理并规范化截止时间格式，去除前缀标签，标准化日期连接符
     */
    fun cleanDeadline(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.trim()
        val withoutBrackets = s.replace(Regex("""\([^)]*\)|（[^）]*）"""), "").trim()
        s = if (withoutBrackets.any { it.isDigit() }) {
            withoutBrackets
        } else {
            s.replace(Regex("""^[（(]|[)）]$"""), "").trim()
        }
        val prefixes = listOf(
            "截止时间：", "截止时间:", "截止时间",
            "截止：", "截止:", "截止",
            "提交截止：", "提交截止:", "提交截止",
            "结束时间：", "结束时间:", "结束时间",
            "作答时间：", "作答时间:", "作答时间",
            "起止时间：", "起止时间:", "起止时间",
            "有效时间：", "有效时间:", "有效时间",
            "考试时间：", "考试时间:", "考试时间",
            "开放时间：", "开放时间:", "开放时间",
            "时间范围：", "时间范围:", "时间范围"
        )
        for (p in prefixes) {
            if (s.startsWith(p)) {
                s = s.substring(p.length).trim()
            }
        }
        s = s.replace(Regex("""^[^0-9]{0,10}[:：]\s*"""), "").trim()

        val rangeDelimiters = listOf("至", "到", "～", "~", "—", " - ")
        for (delim in rangeDelimiters) {
            if (s.contains(delim)) {
                val endPart = s.substringAfterLast(delim).trim()
                if (endPart.any { it.isDigit() }) {
                    s = endPart
                    break
                }
            }
        }
        s = s.replace(Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日?"""), "$1-$2-$3")
        s = s.replace(Regex("""(\d{1,2})月(\d{1,2})日?"""), "$1-$2")
        s = s.replace('/', '-').replace('.', '-')
        s = s.replace(Regex("""(?:截止|结束|前完成)$"""), "").trim()
        return s.replace("\u00A0", " ").trim()
    }

    /**
     * 从文本、HTML 或 aria-label 中智能提取作业截止时间
     */
    fun extractDeadlineFromText(text: String, html: String = "", aria: String = ""): String {
        val datePat = """(?:(?:20\d{2}[-/.年])?\d{1,2}[-/.月]\d{1,2}日?(?:\s+\d{1,2}:\d{1,2}(?::\d{1,2})?)?)"""
        val rangeRegex = Regex(
            """(?:作答时间|起止时间|有效时间|考试时间|开放时间|时间范围)[:：]?\s*[\s\S]*?(?:至|到|~|～|—|\s+-\s+)\s*($datePat)"""
        )
        val ddlRegex = Regex(
            """(?:截止时间|提交截止|结束时间|截止)[:：]?\s*($datePat)"""
        )
        val dualDateRegex = Regex(
            """$datePat\s*(?:至|到|~|～|—|\s+-\s+)\s*($datePat)"""
        )
        val reverseDdlRegex = Regex(
            """($datePat)\s*(?:前完成|截止|结束)"""
        )

        fun matchInText(input: String): String? {
            rangeRegex.find(input)?.let { m ->
                val ddl = cleanDeadline(m.groupValues[1])
                if (ddl.isNotBlank()) return ddl
            }
            ddlRegex.find(input)?.let { m ->
                val ddl = cleanDeadline(m.groupValues[1])
                if (ddl.isNotBlank()) return ddl
            }
            reverseDdlRegex.find(input)?.let { m ->
                val ddl = cleanDeadline(m.groupValues[1])
                if (ddl.isNotBlank()) return ddl
            }
            dualDateRegex.find(input)?.let { m ->
                val ddl = cleanDeadline(m.groupValues[1])
                if (ddl.isNotBlank()) return ddl
            }
            return null
        }

        if (html.isNotBlank() && html.contains("<")) {
            try {
                val doc = Jsoup.parse(html)
                val targetedEls = doc.select(".rightBord, .detailsHead, .infoHead, .time, .end-time, .status-time, .work-time, .date")
                for (el in targetedEls) {
                    matchInText(el.text().trim())?.let { return it }
                }
                matchInText(doc.text().trim())?.let { return it }
            } catch (_: Exception) {}
        }

        for (src in listOf(aria, text)) {
            if (src.isBlank()) continue
            val cleanSrc = if (src.contains("<")) src.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ") else src
            matchInText(cleanSrc)?.let { return it }
        }

        val mStandalone = Regex("""(20\d{2}[-/.年]\d{1,2}[-/.月]\d{1,2}日?\s+\d{1,2}:\d{1,2})""").find(text)
        if (mStandalone != null) {
            return cleanDeadline(mStandalone.groupValues[1])
        }

        return ""
    }

    /**
     * 清理教师评语，剔除网页模板占位词
     */
    fun cleanTeacherComment(commentEl: org.jsoup.nodes.Element?): String {
        if (commentEl == null) return ""
        val pText = commentEl.select("p, .text, .content").text().trim()
        val rawText = if (pText.isNotBlank()) pText else commentEl.text().trim()
        return cleanTeacherCommentText(rawText)
    }

    fun cleanTeacherCommentText(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val validLines = mutableListOf<String>()
        val prefixes = listOf(
            "教师批阅：", "教师批阅:", "教师批阅",
            "教师批语：", "教师批语:", "教师批语",
            "教师评语：", "教师评语:", "教师评语",
            "批阅人：", "批阅人:", "批阅人",
            "批改人：", "批改人:", "批改人",
            "批阅：", "批阅:", "批阅",
            "评语：", "评语:", "评语"
        )
        val placeholders = setOf(
            "", "暂无", "暂无评语", "暂无批语", "未批阅", "未批改", "待批阅", "待批改",
            "教师批阅", "教师批语", "教师评语", "批阅", "等待批阅", "等待教师批阅"
        )

        for (line in lines) {
            var s = line
            s = s.replace(Regex("""^第\d+题(?:批语|评语|批阅)[:：]?\s*"""), "").trim()
            for (p in prefixes) {
                if (s.startsWith(p)) {
                    s = s.substring(p.length).trim()
                }
            }
            if (s.isNotBlank() && s !in placeholders) {
                validLines.add(line)
            }
        }
        return validLines.joinToString("\n").trim()
    }

    /**
     * 格式化超星附件大小
     */
    fun formatSizeForChaoxing(bytes: Long): String {
        if (bytes <= 0L) return "0B"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> String.format(Locale.US, "%.2fM", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.2fK", kb)
            else -> "${bytes}B"
        }
    }

    /**
     * 兼容 Base64 编码 (兼容 Android 运行期与 JVM 单元测试)
     */
    fun encodeAttachmentName(jsonStr: String): String {
        val urlEncoded = URLEncoder.encode(jsonStr, "UTF-8")
        val bytes = urlEncoded.toByteArray(Charsets.UTF_8)
        val androidB64 = try {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
        if (!androidB64.isNullOrBlank()) {
            return androidB64
        }
        return try {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * 构造超星 insertCloud 附件 iframe 嵌入标签
     */
    fun buildAttachmentIframeHtml(att: ChaoXingCourseAttachment): String {
        val ext = att.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val sizeStr = formatSizeForChaoxing(att.size)
        val jsonPayload = """{"objectid":"${att.objectId}","name":"${att.name}","type":"$ext","size":"$sizeStr"}"""
        val encodedName = encodeAttachmentName(jsonPayload)
        return """<div class="editor-iframe" contenteditable="false"><iframe class="attach-iframe" filename="${att.name}" filesize="$sizeStr" filetype="$ext" frameborder="0" module="insertAttach" name="$encodedName" objectid="${att.objectId}" scrolling="no" src="/ananas/common-modules/attachment/insertCloud.html" style="display: block; max-width: 620px; width: 100%; height: 74px;"></iframe></div>"""
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
