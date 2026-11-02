package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.util.Log
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.local.TronClassDataCacheManager
import cn.edu.usst.jwgl.data.model.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
                    ?: Regex("""(?:examId|paperId|id)=(\d+)""").find(body)?.groupValues?.get(1)
                    ?: ""

                val rawAnalysis = Regex("""href="(https://stat2-ans[^"]+)"""").find(body)?.groupValues?.get(1) ?: ""
                val analysisUrl = rawAnalysis.replace("&amp;", "&")

                val taskUrl = Regex("""(?:href=['"]|data=['"])(/[^'"]*(?:exam|test|preview|paper)[^'"]*)""").find(body)?.groupValues?.get(1)?.replace("&amp;", "&").orEmpty()

                out.add(
                    ChaoXingExam(
                        id = id,
                        title = title,
                        status = status,
                        remaining = remaining,
                        score = score,
                        analysisUrl = analysisUrl,
                        taskUrl = taskUrl
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

                    var timeRaw = li.select(".time, .end-time, .status-time, div.time, span.time, p.time, .date").text().trim()
                    if (timeRaw.isBlank()) {
                        timeRaw = extractDeadlineFromText(li.text(), li.html(), li.attr("aria-label"))
                    } else {
                        timeRaw = cleanDeadline(timeRaw)
                    }
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

                    // 3. 课程归属判定 (多重判定：courseId 匹配、classId 匹配、课程名双向包含匹配，且严格保持实验课与非实验课互斥)
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
                    val matchCourse = idMatch || nameMatch

                    if (!matchCourse) continue

                    var timeStr = el.select(".time, .end-time, .status-time, span.time, div.time, p.time, .date").text().trim()
                    if (timeStr.isBlank()) {
                        val aria = el.select("[aria-label]").attr("aria-label").ifBlank { el.attr("aria-label") }
                        timeStr = extractDeadlineFromText(el.text(), el.html(), aria)
                    } else {
                        timeStr = cleanDeadline(timeStr)
                    }
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
         * 清理并规范化截止时间格式，去除前缀标签，标准化日期连接符
         */
        fun cleanDeadline(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            var s = raw.trim()
            val prefixes = listOf(
                "截止时间：", "截止时间:", "截止时间",
                "截止：", "截止:", "截止",
                "提交截止：", "提交截止:", "提交截止",
                "结束时间：", "结束时间:", "结束时间",
                "作答时间：", "作答时间:", "作答时间",
                "起止时间：", "起止时间:", "起止时间",
                "有效时间：", "有效时间:", "有效时间"
            )
            for (p in prefixes) {
                if (s.startsWith(p)) {
                    s = s.substring(p.length).trim()
                }
            }
            // 若传入时间区间如 "10-21 17:58 至 10-27 17:58"，提取区间终点作为截止时间
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
            return s.replace("\u00A0", " ").trim()
        }

        /**
         * 从文本、HTML 或 aria-label 中智能提取作业截止时间
         * 涵盖“作答时间/起止时间: xx 至 xx”、“截止时间: xx”等超星全形态格式
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

            // 0. 若存在 HTML，优先通过 Jsoup 精准分析常用时间容器（如 rightBord、time、end-time 等）
            if (html.isNotBlank() && html.contains("<")) {
                try {
                    val doc = org.jsoup.Jsoup.parse(html)
                    val targetedEls = doc.select(".rightBord, .detailsHead, .infoHead, .time, .end-time, .status-time, .work-time, .date")
                    for (el in targetedEls) {
                        val t = el.text().trim()
                        val mRange = rangeRegex.find(t)
                        if (mRange != null) {
                            val ddl = cleanDeadline(mRange.groupValues[1])
                            if (ddl.isNotBlank()) return ddl
                        }
                        val mDdl = ddlRegex.find(t)
                        if (mDdl != null) {
                            val ddl = cleanDeadline(mDdl.groupValues[1])
                            if (ddl.isNotBlank()) return ddl
                        }
                        val mDual = dualDateRegex.find(t)
                        if (mDual != null) {
                            val ddl = cleanDeadline(mDual.groupValues[1])
                            if (ddl.isNotBlank()) return ddl
                        }
                    }
                    val wholeDocText = doc.text().trim()
                    val mRangeWhole = rangeRegex.find(wholeDocText)
                    if (mRangeWhole != null) {
                        val ddl = cleanDeadline(mRangeWhole.groupValues[1])
                        if (ddl.isNotBlank()) return ddl
                    }
                    val mDdlWhole = ddlRegex.find(wholeDocText)
                    if (mDdlWhole != null) {
                        val ddl = cleanDeadline(mDdlWhole.groupValues[1])
                        if (ddl.isNotBlank()) return ddl
                    }
                    val mDualWhole = dualDateRegex.find(wholeDocText)
                    if (mDualWhole != null) {
                        val ddl = cleanDeadline(mDualWhole.groupValues[1])
                        if (ddl.isNotBlank()) return ddl
                    }
                } catch (_: Exception) {}
            }

            // 1. 遍历 candidates (aria, text)
            val candidates = listOf(aria, text)
            for (src in candidates) {
                if (src.isBlank()) continue
                val cleanSrc = if (src.contains("<")) src.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ") else src
                val mRange = rangeRegex.find(cleanSrc)
                if (mRange != null) {
                    val ddl = cleanDeadline(mRange.groupValues[1])
                    if (ddl.isNotBlank()) return ddl
                }
                val mDdl = ddlRegex.find(cleanSrc)
                if (mDdl != null) {
                    val ddl = cleanDeadline(mDdl.groupValues[1])
                    if (ddl.isNotBlank()) return ddl
                }
                val mDual = dualDateRegex.find(cleanSrc)
                if (mDual != null) {
                    val ddl = cleanDeadline(mDual.groupValues[1])
                    if (ddl.isNotBlank()) return ddl
                }
            }

            // 2. 兜底直接匹配独立日期
            val mStandalone = Regex("""(20\d{2}[-/.年]\d{1,2}[-/.月]\d{1,2}日?\s+\d{1,2}:\d{1,2})""").find(text)
            if (mStandalone != null) {
                return cleanDeadline(mStandalone.groupValues[1])
            }

            return ""
        }

        /**
         * 清理教师评语，剔除网页模板占位词（如“教师批阅”、“教师批语”等表头文字），避免未批阅时误显
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
         * 解析答卷或做题页面 HTML 中的全部题目结构
         */
        /**
         * 解析答卷或做题页面 HTML 中的全部题目结构与选项
         */
        fun parseQuestionsFromHtml(html: String): List<ChaoXingQuestionItem> {
            val list = mutableListOf<ChaoXingQuestionItem>()
            if (html.isBlank()) return list
            try {
                val doc = Jsoup.parse(html)
                // 1. 抓取所有可能代表题目的候选容器 (涵盖 PC 端 TiMu / Zy_TItle 以及移动端 singleQuesId / questionBlock 等)
                val rawCandidates = doc.select(".TiMu, .singleQuesId, div[class*='singleQues'], .mark_item, .questionBlock, .questionLi, .padlr30")
                // 关键防嵌套过滤：如果某个候选元素的祖先中已有其他候选元素，则该元素为嵌套的内部容器，应予过滤，只保留最外层完整题目容器
                val rawSet = rawCandidates.toSet()
                val topElements = rawCandidates.filter { el ->
                    el.parents().none { it in rawSet }
                }

                val seenSignatures = mutableSetOf<String>()
                var questionIndex = 1

                for (qEl in topElements) {
                    // 题型与分值提取 (支持 PC 端 newZy_TItle、Zy_TItle、colorDeep，以及移动端 stemGray 等)
                    val typeScoreEl = qEl.select(".newZy_TItle, .stemGray, .question-type, .type, .colorDeep, .type_tit, .color888, .font14").firstOrNull()
                    val typeScoreText = typeScoreEl?.text()?.trim().orEmpty()
                    val scoreMatch = Regex("""(\d+(?:\.\d+)?)\s*分""").find(typeScoreText)
                        ?: Regex("""[(（\[【]\s*(\d+(?:\.\d+)?)\s*分\s*[)）\]】]""").find(qEl.text())
                        ?: Regex("""(?:分值[:：\s]*|共\s*)(\d+(?:\.\d+)?)\s*分""").find(qEl.text())
                    val scoreText = scoreMatch?.groupValues?.get(1)?.let { "$it 分" } ?: ""

                    // 1. 题干提取 (支持 PC 端 Zy_TItle 与移动端 stemDiv / Picdiv)
                    val stemEl = qEl.select(".Zy_TItle, .stemDiv .Picdiv, .Picdiv, .stemDiv, .qtContent, .workTextWrap, .font14, .stem_title, .title, .sub-title").firstOrNull()
                    var stemHtml = stemEl?.html()?.trim().orEmpty()
                    var cleanStem = stemEl?.text()?.trim().orEmpty()
                    if (cleanStem.isBlank()) {
                        cleanStem = qEl.select(".Zy_TItle, .stemDiv, .title, .sub-title").text().trim()
                    }
                    // 清理题干开头的题号 ("1.", "1、", "1 ")
                    cleanStem = cleanStem.replace(Regex("""^\d+[.\u3001\s]+"""), "")
                    // 清理题干开头的题型与分值标注 ("(单选题, 2分)", "【单选题】", "[单选题]")
                    cleanStem = cleanStem.replace(Regex("""^[(（\[【][^()（）\[\]【】]*题[^()（）\[\]【】]*[)）\]】]\s*"""), "")
                    // 清理题干末尾的分值标注 ("（2.0分）", "(2分)")
                    cleanStem = cleanStem.replace(Regex("""[(（\[【]?\d+(?:\.\d+)?\s*分[)）\]】]?\s*$"""), "")
                    cleanStem = cleanStem.trim()
                    if (stemHtml.isBlank()) stemHtml = cleanStem

                    // 8. 题目 ID 提取
                    var qId = qEl.attr("data").trim()
                    if (qId.isBlank()) {
                        qId = Regex("""\d+""").find(qEl.attr("id"))?.value.orEmpty()
                    }
                    if (qId.isBlank()) {
                        qId = qEl.select("[id^='qb']").firstOrNull()?.let { Regex("""\d+""").find(it.attr("id"))?.value }.orEmpty()
                    }
                    if (qId.isBlank()) {
                        qId = qEl.attr("questionid").trim()
                    }
                    if (qId.isBlank()) {
                        qId = qEl.select("input[name*='id'], input[id*='id'], [data-singlequesid]").firstOrNull()?.let {
                            it.attr("data-singlequesid").ifBlank { it.attr("value") }
                        }.orEmpty()
                    }

                    // 避免重复题目条目入库 (根据 ID 或 Stem 签名去重)
                    val signature = if (qId.isNotBlank()) "id_$qId" else "stem_${cleanStem.take(60)}"
                    if (cleanStem.isNotBlank() && !seenSignatures.add(signature)) {
                        continue
                    }

                    // 2. 提取题干中的教师附件或图片
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

                    // 3. 提取客观题选项列表 (支持 Zy_ulTop, Cy_ulTop, Zy_ulTk, ulOption, stem_answer 等)
                    val options = parseOptionsFromQuestionElement(qEl)

                    // 4. 学生作答提取 (支持 Py_answer, stuAnswerContent, stuAnswer 等)
                    var cleanAns = ""
                    var ansHtml = ""
                    val pyAns = qEl.select(".Py_answer, .Py_tk").firstOrNull()
                    if (pyAns != null) {
                        val m = Regex("""(?:我的答案|学生答案)[:：\s]*([A-Za-z0-9,、\s\u4e00-\u9fa5✓✕对错正确错误]+?)(?=(?:正确答案|标准答案|参考答案|答案解析|解析|教师批阅|教师评语|$))""").find(pyAns.text())
                        if (m != null) {
                            cleanAns = m.groupValues[1].trim()
                        }
                        ansHtml = pyAns.html().trim()
                    }
                    if (cleanAns.isBlank()) {
                        val ansEl = qEl.select(".stuAnswerContent, .optionDiv, .myAnswer, .my-answer, .stuAnswer").firstOrNull()
                        ansHtml = ansEl?.html()?.trim().orEmpty()
                        cleanAns = if (ansEl != null) {
                            ansEl.text().replace(Regex("""^(?:我的答案|我的作答|学生答案)[:：\s]*"""), "").trim()
                        } else {
                            val m = Regex("""(?:我的答案|我的作答|学生答案)[:：]\s*([A-Za-z0-9,、\s\u4e00-\u9fa5✓✕对错正确错误]+?)(?=(?:正确答案|标准答案|参考答案|答案解析|解析|教师批阅|教师评语|$))""").find(qEl.text())
                            m?.groupValues?.get(1)?.trim().orEmpty()
                        }
                    }
                    if (cleanAns.isBlank() && options.isNotEmpty()) {
                        val chosenLetters = options.filter { it.isSelected }.map { it.letter }
                        if (chosenLetters.isNotEmpty()) {
                            cleanAns = chosenLetters.joinToString(", ")
                        }
                    }

                    // 5. 标准答案提取 (支持 Py_answer, true_answer, rightAnswer 等)
                    var correctAns = ""
                    if (pyAns != null) {
                        val m = Regex("""(?:正确答案|标准答案|参考答案)[:：\s]*([A-Za-z0-9,、\s\u4e00-\u9fa5✓✕对错正确错误]+?)(?=(?:我的答案|学生答案|答案解析|解析|教师批阅|教师评语|$))""").find(pyAns.text())
                        if (m != null) {
                            correctAns = m.groupValues[1].trim()
                        }
                    }
                    if (correctAns.isBlank()) {
                        val rightEl = qEl.select(".true_answer, .rightAnswer, .standardAnswer, .correctAnswer, .trueAnswer").firstOrNull()
                        correctAns = if (rightEl != null) {
                            rightEl.text().replace(Regex("""^(?:正确答案|标准答案|参考答案)[:：\s]*"""), "").trim()
                        } else {
                            val m = Regex("""(?:正确答案|标准答案|参考答案)[:：]\s*([A-Za-z0-9,、\s\u4e00-\u9fa5✓✕对错正确错误]+?)(?=(?:答案解析|解析|学生答案|我的答案|教师批阅|教师评语|$))""").find(qEl.text())
                            m?.groupValues?.get(1)?.trim().orEmpty()
                        }
                    }
                    if (correctAns.isBlank() && options.isNotEmpty()) {
                        val rightLetters = options.filter { it.isCorrect }.map { it.letter }
                        if (rightLetters.isNotEmpty()) {
                            correctAns = rightLetters.joinToString(", ")
                        }
                    }

                    // 6. 题目解析提取
                    val expEl = qEl.select(".analysis, .myAnalysis, .analysisDiv, .explain, .fenxi, .Py_tk").firstOrNull()
                    var explanation = if (expEl != null) {
                        expEl.text().replace(Regex("""^(?:答案解析|解析|题目分析)[:：\s]*"""), "").trim()
                    } else {
                        val m = Regex("""(?:答案解析|解析|题目分析)[:：]\s*([^\r\n]+)""").find(qEl.text())
                        m?.groupValues?.get(1)?.trim().orEmpty()
                    }

                    // 提取学生作答附件
                    val studentAtts = mutableListOf<ChaoXingCourseAttachment>()
                    val searchAnsEl = qEl.select(".stuAnswerContent, .optionDiv, .myAnswer, .my-answer, .stuAnswer, .Py_answer").firstOrNull()
                    if (searchAnsEl != null) {
                        for (iframe in searchAnsEl.select("iframe.attach-iframe, iframe[objectid]")) {
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

                    // 7. 教师评语提取
                    val commentEl = qEl.select(".grayDiv, .pingyu, .remark, .comment, .teach-comment").firstOrNull()
                    val commentText = cleanTeacherComment(commentEl)

                    // 9. 精准解析题型 (结合属性、文本与选项结构智能判别，坚决杜绝把选择题误判为主观题)
                    val (typeCode, typeName) = resolveQuestionType(qEl, typeScoreText, options)

                    list.add(
                        ChaoXingQuestionItem(
                            index = questionIndex++,
                            id = qId,
                            typeCode = typeCode,
                            typeName = typeName,
                            scoreText = scoreText,
                            stemHtml = stemHtml,
                            cleanStem = cleanStem,
                            options = options,
                            correctAnswer = correctAns,
                            explanation = explanation,
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

        /**
         * 解析超星单道题目的全部选项卡片数据
         */
        fun parseOptionsFromQuestionElement(qEl: org.jsoup.nodes.Element): List<ChaoXingQuestionOption> {
            val options = mutableListOf<ChaoXingQuestionOption>()

            val pyAnsText = qEl.select(".Py_answer, .Py_tk").text().trim()
            val myAnsText = qEl.select(".stuAnswer, .myAnswer, .my-answer, .stuAnswerContent").text().trim()
            val rightAnsText = qEl.select(".true_answer, .rightAnswer, .standardAnswer, .correctAnswer, .trueAnswer").text().trim()
            val myFallback = Regex("""(?:我的答案|我的作答|学生答案)[:：]\s*([A-Za-z0-9,、\s\u4e00-\u9fa5✓✕对错正确错误]+?)(?=(?:正确答案|标准答案|参考答案|答案解析|解析|教师批阅|教师评语|$))""").find(qEl.text())?.groupValues?.get(1).orEmpty()
            val rightFallback = Regex("""(?:正确答案|标准答案|参考答案)[:：]\s*([A-Za-z0-9,、\s\u4e00-\u9fa5✓✕对错正确错误]+?)(?=(?:答案解析|解析|学生答案|我的答案|教师批阅|教师评语|$))""").find(qEl.text())?.groupValues?.get(1).orEmpty()

            val myFullStr = "$pyAnsText $myAnsText $myFallback"
            val rightFullStr = "$pyAnsText $rightAnsText $rightFallback"

            val myLetters = extractChoiceLetters(myFullStr)
            val rightLetters = extractChoiceLetters(rightFullStr)

            // 1. 常见超星选项列表选择器 (涵盖 PC 端 Zy_ulTop, Cy_ulTop, Zy_ulTk, ulOption 及移动端 stem_answer, answerList 等)
            val optEls = qEl.select("""
                ul.Zy_ulTop > li,
                ul.Zy_ulTop li,
                ul.Cy_ulTop > li,
                ul.Cy_ulTop li,
                ul.Zy_ulTk > li,
                ul.Zy_ulTk li,
                ul.ulOption > li,
                ul.ulOption li,
                .stem_answer li,
                .stem_answer .clearfix,
                .answerList .clearfix,
                .answerList .answerList_item,
                .answerList > div,
                .answerList li,
                .options li,
                .optionList li,
                .option_list li,
                ul.tiMu_ul li,
                .choiceList .choice,
                .choiceList li,
                div[class*='answerBg'],
                ul[class*='answer'] li,
                ul[class*='option'] li,
                ul[class*='ulTop'] li,
                ul[class*='ulTk'] li
            """.trimIndent())

            val letterList = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")

            if (optEls.isNotEmpty()) {
                for ((idx, optEl) in optEls.withIndex()) {
                    val badgeEl = optEl.select(".num_option, .num, i.fl, em.fl, .option_num, span.fl, .letter, i, em").firstOrNull()
                    var letter = badgeEl?.attr("data")?.trim().orEmpty()
                    if (letter.isBlank()) {
                        letter = optEl.attr("data").trim()
                    }
                    if (letter.isBlank()) {
                        letter = badgeEl?.text()?.replace(Regex("""[^A-Za-z✓✕对错正确错误]"""), "")?.trim()?.uppercase().orEmpty()
                    }
                    val rawText = optEl.text().trim()

                    if (letter.isBlank()) {
                        val match = Regex("""^([A-Z])[.\u3001\s]""").find(rawText)
                        if (match != null) {
                            letter = match.groupValues[1]
                        }
                    }
                    if (letter.isBlank()) {
                        letter = if (idx < letterList.size) letterList[idx] else "${idx + 1}"
                    }

                    // 选项文本提取 (支持 a.after, .answer_p, .option_content, .text 等)
                    val contentEl = optEl.select("a.after, .answer_p, .option_content, .text, .fl:not(i):not(em):not(.num_option), p, a, div, span:not(.num_option)").firstOrNull()
                    var content = contentEl?.text()?.trim() ?: rawText
                    content = content.replace(Regex("""^[A-Za-z][.\u3001\s]*"""), "").trim()
                    if (content.isBlank() && rawText.isNotBlank()) {
                        content = rawText.replace(Regex("""^[A-Za-z][.\u3001\s]*"""), "").trim()
                    }

                    val isSelected = myLetters.contains(letter) ||
                            optEl.hasClass("check_answer") || optEl.hasClass("check") ||
                            optEl.hasClass("cur") || optEl.hasClass("active") ||
                            optEl.hasClass("selected") || optEl.select("input[checked]").isNotEmpty()

                    val isCorrect = rightLetters.contains(letter) ||
                            optEl.hasClass("rightAnswer") || optEl.hasClass("correct") ||
                            optEl.select(".rightAnswer, .colorGreen").isNotEmpty()

                    if (content.isNotBlank() || letter.isNotBlank()) {
                        options.add(
                            ChaoXingQuestionOption(
                                letter = letter,
                                content = content,
                                isSelected = isSelected,
                                isCorrect = isCorrect
                            )
                        )
                    }
                }
            } else {
                // 2. 备选方案：检查题干或题干下内嵌段落中的 A. B. C. D. 选项行
                val pElements = qEl.select(".Zy_TItle p, .stemDiv p, .Picdiv p, p")
                val pOptions = mutableListOf<Pair<String, String>>()
                for (p in pElements) {
                    val text = p.text().trim()
                    val m = Regex("""^([A-J])[.\u3001\s]+(.*)$""").find(text)
                    if (m != null) {
                        pOptions.add(Pair(m.groupValues[1].uppercase(), m.groupValues[2].trim()))
                    }
                }
                if (pOptions.size >= 2) {
                    for ((l, c) in pOptions) {
                        options.add(
                            ChaoXingQuestionOption(
                                letter = l,
                                content = c,
                                isSelected = myLetters.contains(l),
                                isCorrect = rightLetters.contains(l)
                            )
                        )
                    }
                }
            }

            return options
        }

        private fun extractChoiceLetters(text: String): Set<String> {
            if (text.isBlank()) return emptySet()
            val clean = Regex("""(?:我的答案|正确答案|标准答案|参考答案|学生答案)[:：\s]*""").replace(text, "").trim()
            val set = mutableSetOf<String>()
            Regex("""[A-Za-z]""").findAll(clean).forEach {
                set.add(it.value.uppercase())
            }
            if (set.isEmpty()) {
                if (clean.contains("对") || clean.contains("正确") || clean.equals("true", ignoreCase = true)) set.add("✓")
                if (clean.contains("错") || clean.contains("错误") || clean.equals("false", ignoreCase = true)) set.add("✕")
            }
            return set
        }

        /**
         * 智能判定题目类型编码与题型名称
         */
        fun resolveQuestionType(
            qEl: org.jsoup.nodes.Element,
            typeScoreText: String,
            options: List<ChaoXingQuestionOption>
        ): Pair<String, String> {
            val allText = qEl.text()
            val typeTextCandidates = qEl.select(".newZy_TItle, .Zy_TItle, .stemGray, .question-type, .type, .colorDeep, .color888, .type_tit, .stem_type, .font14, .stemDiv, .title").text()
            val fullScan = "$typeScoreText $typeTextCandidates $allText"

            val attrTypeId = qEl.attr("questiontypeid").ifBlank { qEl.attr("type") }.ifBlank {
                qEl.select("input[name*='type'], input[id*='type'], [questiontypeid], [data-type]").firstOrNull()?.let {
                    it.attr("questiontypeid").ifBlank { it.attr("data-type") }.ifBlank { it.attr("value") }
                }.orEmpty()
            }

            val typeName = when {
                fullScan.contains("多选题") || fullScan.contains("多选") || allText.contains("【多选题】") || allText.contains("(多选题") || allText.contains("（多选题") -> "多选题"
                fullScan.contains("单选题") || fullScan.contains("单选") || allText.contains("【单选题】") || allText.contains("(单选题") || allText.contains("（单选题") -> "单选题"
                fullScan.contains("判断题") || fullScan.contains("判断") || allText.contains("【判断题】") || allText.contains("(判断题") || allText.contains("（判断题") -> "判断题"
                fullScan.contains("填空题") || fullScan.contains("填空") || allText.contains("【填空题】") || allText.contains("(填空题") || allText.contains("（填空题") -> "填空题"
                fullScan.contains("简答题") || fullScan.contains("简答") || allText.contains("【简答题】") || allText.contains("(简答题") || allText.contains("（简答题") -> "简答题"
                fullScan.contains("名词解释") -> "名词解释"
                fullScan.contains("论述题") -> "论述题"
                fullScan.contains("计算题") -> "计算题"
                attrTypeId == "0" -> "单选题"
                attrTypeId == "1" -> "多选题"
                attrTypeId == "2" -> "填空题"
                attrTypeId == "3" -> "判断题"
                attrTypeId == "4" -> "简答题"
                options.size == 2 && (allText.contains("正确") || allText.contains("错误") || allText.contains("对") || allText.contains("错") || options.any { it.letter in listOf("✓", "✕", "对", "错") }) -> "判断题"
                options.isNotEmpty() && (qEl.select("input[type='checkbox']").isNotEmpty() || fullScan.contains("多选")) -> "多选题"
                options.isNotEmpty() -> "单选题"
                typeScoreText.isNotBlank() -> typeScoreText.replace(Regex("""[(（\[【\d.\s分)）\]】]"""), "").trim().ifBlank { "主观题" }
                else -> "主观题"
            }

            val typeCode = when (typeName) {
                "单选题" -> "0"
                "多选题" -> "1"
                "填空题" -> "2"
                "判断题" -> "3"
                "简答题" -> "4"
                "名词解释" -> "5"
                "论述题" -> "6"
                "计算题" -> "7"
                else -> "4"
            }

            return Pair(typeCode, typeName)
        }

        /**
         * 格式化超星附件大小 (如 "8.49K", "131.88K", "2.50M", "1024B")
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

        val cacheMgr = TronClassDataCacheManager.getInstance(context)
        val cached = cacheMgr.getCachedChaoXingExamDetail(exam.id)

        val targetCourseId = (course?.courseId ?: exam.courseId).trim()
        val targetClassId = (course?.classId ?: exam.classId).trim()
        val isDone = exam.status.contains("已完成") || exam.status.contains("已批") || exam.score.isNotBlank()

        val candidateUrls = mutableListOf<String>()
        if (exam.taskUrl.isNotBlank()) {
            val fullTask = if (exam.taskUrl.startsWith("http")) exam.taskUrl else "https://mooc1.chaoxing.com" + exam.taskUrl
            candidateUrls.add(fullTask)
        }
        if (isDone) {
            candidateUrls.add("https://mooc1.chaoxing.com/mooc-ans/mooc2/exam/preview?courseid=$targetCourseId&clazzid=$targetClassId&examId=${exam.id}")
            candidateUrls.add("https://mooc1.chaoxing.com/exam/test/re-paper?courseId=$targetCourseId&classId=$targetClassId&id=${exam.id}")
            candidateUrls.add("https://mooc1-api.chaoxing.com/mooc-ans/mooc2/exam/preview?courseid=$targetCourseId&clazzid=$targetClassId&examId=${exam.id}")
        } else {
            candidateUrls.add("https://mooc1.chaoxing.com/mooc-ans/mooc2/exam/test?courseid=$targetCourseId&clazzid=$targetClassId&examId=${exam.id}")
            candidateUrls.add("https://mooc1.chaoxing.com/mooc-ans/mooc2/exam/preview?courseid=$targetCourseId&clazzid=$targetClassId&examId=${exam.id}")
            candidateUrls.add("https://mooc1.chaoxing.com/exam/test/re-paper?courseId=$targetCourseId&classId=$targetClassId&id=${exam.id}")
        }

        var lastException: Exception? = null
        for (rawUrl in candidateUrls) {
            try {
                val fullUrl = if (cpi.isNotBlank() && !rawUrl.contains("cpi=") && !rawUrl.contains("personid=")) {
                    rawUrl + (if (rawUrl.contains("?")) "&cpi=$cpi&personid=$cpi" else "?cpi=$cpi&personid=$cpi")
                } else rawUrl

                val req = Request.Builder()
                    .url(fullUrl)
                    .header("Cookie", cookies)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .get()
                    .build()

                val resp = okHttpClient.newCall(req).execute()
                val html = resp.body?.string().orEmpty()
                val finalUrl = resp.request.url.toString()

                if (html.isNotBlank()) {
                    val questions = parseQuestionsFromHtml(html)
                    if (questions.isNotEmpty() || html.contains("TiMu") || html.contains("singleQuesId") || html.contains("questionBlock")) {
                        val scoreStr = Regex("""class="score"[^>]*>(\d+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
                            ?: Regex("""class="numspan"[^>]*>(\d+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
                            ?: Regex("""得分[:：]\s*(\d+(?:\.\d+)?)""").find(html)?.groupValues?.get(1)
                            ?: exam.score

                        val limitMatch = Regex("""(?:限时|考试时长|时长)[:：]?\s*(\d+)\s*分钟""").find(html)
                        val limitTime = limitMatch?.groupValues?.get(1)?.toIntOrNull()

                        val timeRange = extractDeadlineFromText(html, html)

                        val examDetail = ChaoXingExamDetail(
                            examId = exam.id,
                            title = exam.title,
                            courseName = course?.courseName ?: exam.courseName,
                            statusText = exam.status.ifBlank { if (isDone) "已完成" else "待作答" },
                            score = scoreStr,
                            timeRange = timeRange.ifBlank { exam.remaining },
                            limitTime = limitTime,
                            scoreRule = if (html.contains("最高分")) "highest" else "latest",
                            rawQuestions = questions,
                            analysisUrl = exam.analysisUrl,
                            taskUrl = finalUrl,
                            isSubmitted = isDone,
                            isGraded = scoreStr.isNotBlank() || isDone
                        )

                        cacheMgr.saveChaoXingExamDetail(exam.id, examDetail)
                        Log.i(TAG, "Successfully fetched ChaoXing exam detail: ${exam.title} (${questions.size} questions)")
                        return@withContext Result.success(examDetail)
                    }
                }
            } catch (e: Exception) {
                lastException = e
            }
        }

        if (cached != null) {
            Log.w(TAG, "Returning cached ChaoXingExamDetail for ${exam.id}")
            return@withContext Result.success(cached)
        }

        Result.failure<ChaoXingExamDetail>(lastException ?: Exception("未拉取到超星测验试卷题目"))
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
                contentHtmlBuilder.append(q.cleanStem.ifBlank { q.stemHtml })

                // 如果包含客观题选项，将其按行格式化追加
                if (q.options.isNotEmpty()) {
                    contentHtmlBuilder.append("<br/>")
                    for (opt in q.options) {
                        contentHtmlBuilder.append("<br/>&nbsp;&nbsp;").append(opt.letter).append(". ").append(opt.content)
                    }
                }

                cleanContentBuilder.append("【").append(qHeader).append("】\n")
                cleanContentBuilder.append(q.cleanStem)
                if (q.options.isNotEmpty()) {
                    for (opt in q.options) {
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
                work.statusText.isNotBlank() -> work.statusText
                studentAnswerBuilder.isNotEmpty() || studentAttachments.isNotEmpty() -> "已提交"
                else -> "未提交"
            }

            val isSub = finalStatus.contains("已交") || finalStatus.contains("已完成") || finalStatus.contains("已提交") ||
                    finalStatus.contains("待批") || finalStatus.contains("已批") ||
                    studentAnswerBuilder.isNotEmpty() || studentAttachments.isNotEmpty()
            val isGrd = (score.isNotBlank() || finalStatus.contains("已完成") || (finalStatus.contains("已批") && !finalStatus.contains("待批") && !finalStatus.contains("未批"))) &&
                    !finalStatus.contains("待批") && !finalStatus.contains("未批")

            val finalDeadline = deadlineFromPage.ifBlank { cleanDeadline(work.deadline) }

            val detail = ChaoXingHomeworkDetail(
                workId = work.workId,
                title = work.title,
                courseName = course?.courseName ?: work.courseName,
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
