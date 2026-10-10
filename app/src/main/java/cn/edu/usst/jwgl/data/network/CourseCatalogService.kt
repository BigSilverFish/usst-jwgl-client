package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.util.Log
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.model.CourseBasicInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * 上海理工大学正方教务系统课程库查询与课程大纲服务
 */
object CourseCatalogService {
    private const val TAG = "CourseCatalogService"
    private const val BASE_URL = "https://jwgl.usst.edu.cn/jwglxt"
    private const val GNMKDM = "N151005"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 课程类别全局缓存 (以课程代码或课程名称为 key，存储 "理论" / "实践" / "待定") */
    private val categoryCache = ConcurrentHashMap<String, String>()

    /** 通识课程归属全局缓存 (以课程代码或课程名称为 key，存储如 "创新思维与创业实践") */
    private val affiliationCache = ConcurrentHashMap<String, String>()

    val KNOWN_GENERAL_MODULES = listOf(
        "劳动教育实践",
        "创新思维与创业实践",
        "人文经典与文化传承",
        "艺术修养与审美体验",
        "全球视野与文明对话",
        "科学探索与持续发展",
        "工程创新及实践",
        "“四史”教育专题课程",
        "创新实践导论",
        "科学与工程伦理",
        "科研项目课程",
        "通识-数智基础类",
        "任选类",
        "通识-思政类",
        "通识-军体类",
        "通识-英语类",
        "通识-计算机类",
        "创新创业大作业",
        "劳动教育"
    )

    fun hasAuthoritativeAffiliation(courseCode: String): Boolean {
        return affiliationCache.containsKey(courseCode) && affiliationCache[courseCode]!!.isNotEmpty()
    }

    fun getAffiliation(courseCode: String, courseName: String = ""): String {
        if (courseCode.isNotEmpty() && affiliationCache.containsKey(courseCode)) {
            return affiliationCache[courseCode]!!
        }
        if (courseName.isNotEmpty() && affiliationCache.containsKey(courseName)) {
            return affiliationCache[courseName]!!
        }
        return ""
    }

    fun cacheAffiliation(courseCode: String, affiliation: String, courseName: String = "", context: Context? = null) {
        val aff = affiliation.trim()
        if (aff.isEmpty()) return
        if (courseCode.isNotEmpty()) {
            affiliationCache[courseCode] = aff
        }
        if (courseName.isNotEmpty()) {
            affiliationCache[courseName] = aff
        }
        if (context != null) {
            try {
                val sp = context.getSharedPreferences("course_affiliations", Context.MODE_PRIVATE)
                val editor = sp.edit()
                if (courseCode.isNotEmpty()) editor.putString("code_$courseCode", aff)
                if (courseName.isNotEmpty()) editor.putString("name_$courseName", aff)
                editor.apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist affiliation", e)
            }
        }
    }

    fun loadAffiliationsFromPrefs(context: Context) {
        try {
            val sp = context.getSharedPreferences("course_affiliations", Context.MODE_PRIVATE)
            sp.all.forEach { (k, v) ->
                if (v is String && v.isNotEmpty()) {
                    if (k.startsWith("code_")) {
                        affiliationCache[k.removePrefix("code_")] = v
                    } else if (k.startsWith("name_")) {
                        affiliationCache[k.removePrefix("name_")] = v
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load affiliations from prefs", e)
        }
    }

    fun heuristicAffiliation(courseCode: String, courseName: String): String {
        return when {
            courseName.contains("劳动") -> "劳动教育实践"
            courseName.contains("创业") || courseName.contains("创新思维") -> "创新思维与创业实践"
            courseName.contains("创新实践导论") -> "创新实践导论"
            courseName.contains("伦理") -> "科学与工程伦理"
            courseName.contains("四史") || courseName.contains("党史") || courseName.contains("新中国史") -> "“四史”教育专题课程"
            courseName.contains("科研") -> "科研项目课程"
            courseName.contains("数智") || courseName.contains("大数据") || courseName.contains("人工智能") -> "通识-数智基础类"
            courseName.contains("思政") || courseName.contains("马克思") || courseName.contains("毛泽东") || courseName.contains("近代史") || courseName.contains("思想道德") -> "通识-思政类"
            courseName.contains("体育") || courseName.contains("击剑") || courseName.contains("篮球") || courseName.contains("羽毛球") || courseName.contains("足球") || courseName.contains("游泳") || courseName.contains("太极") || courseName.contains("乒乓") || courseCode.startsWith("3110") -> "通识-军体类"
            courseName.contains("大学英语") || courseName.contains("英语口语") || courseName.contains("视听说") || courseName.contains("雅思") || courseName.contains("托福") -> "通识-英语类"
            courseName.contains("计算机") || courseName.contains("信息系统") || courseName.contains("程序设计") || courseName.contains("Python") || courseName.contains("Java") || courseName.contains("C语言") -> "通识-计算机类"
            courseName.contains("艺术") || courseName.contains("音乐") || courseName.contains("美术") || courseName.contains("舞蹈") || courseName.contains("审美") || courseName.contains("鉴赏") || courseName.contains("影视") -> "艺术修养与审美体验"
            courseName.contains("哲学") || courseName.contains("文学") || courseName.contains("历史") || courseName.contains("文化") || courseName.contains("国学") || courseName.contains("诗经") -> "人文经典与文化传承"
            courseName.contains("全球") || courseName.contains("国际") || courseName.contains("文明") || courseName.contains("跨文化") || courseName.contains("应急物流") -> "全球视野与文明对话"
            courseName.contains("探索") || courseName.contains("物理") || courseName.contains("化学") || courseName.contains("生命") || courseName.contains("宇宙") || courseName.contains("生态") -> "科学探索与持续发展"
            courseName.contains("工程") || courseName.contains("实践") || courseName.contains("机器人") || courseName.contains("3D打印") || courseName.contains("智能制造") -> "工程创新及实践"
            else -> ""
        }
    }

    /**
     * 将教务系统原始课程类别 (kclbmc) 规范化为 "理论"、"实践" 或 "待定"
     */
    fun normalizeCategory(
        rawCategory: String?,
        courseName: String = "",
        lectureHours: Int = 0,
        labHours: Int = 0
    ): String {
        val raw = rawCategory?.trim().orEmpty()
        return when {
            raw.contains("理论") -> "理论"
            raw.contains("实践") || raw.contains("实验") || raw.contains("实训") ||
            raw.contains("实习") || raw.contains("设计") || raw.contains("环节") -> "实践"
            // 基于课程名称的推断回退
            courseName.contains("实习") || courseName.contains("实训") || courseName.contains("设计") ||
            courseName.contains("实验") || courseName.contains("上机") || courseName.contains("金工") ||
            courseName.contains("电工") -> "实践"
            // 基于学时的推断回退
            lectureHours > 0 && labHours == 0 -> "理论"
            lectureHours == 0 && labHours > 0 -> "实践"
            else -> "待定"
        }
    }

    /**
     * 将解析出的课程信息存入类别缓存
     */
    fun cacheCourseInfo(info: CourseBasicInfo) {
        val cat = normalizeCategory(info.courseCategory, info.courseName, info.lectureHours, info.labHours)
        if (cat != "待定") {
            if (info.courseCode.isNotEmpty()) {
                categoryCache[info.courseCode] = cat
            }
            if (info.courseName.isNotEmpty()) {
                categoryCache[info.courseName] = cat
            }
        }
    }

    /**
     * 快速从缓存或课程名称规则获取课程类别（非阻塞，极速返回）
     */
    fun getCategoryFromCacheOrHeuristic(courseCode: String, courseName: String): String {
        if (courseCode.isNotEmpty() && categoryCache.containsKey(courseCode)) {
            return categoryCache[courseCode]!!
        }
        if (courseName.isNotEmpty() && categoryCache.containsKey(courseName)) {
            return categoryCache[courseName]!!
        }
        return normalizeCategory(null, courseName)
    }

    /**
     * 从班级名称、选课标识或纯数字输入中提取 8 位课程代码
     * 例如：
     * "(2026-2027-1)-22000210-10" -> "22000210"
     * "22000210" -> "22000210"
     */
    fun extractCourseCode(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // 优先匹配标准班级号中的 8 位课程代码：如 (2026-2027-1)-22000210-10 或 22000210-10
        val classRegex = Regex("""(?:\([^\)]+\)-)?(\d{8})(?:-\d+)?""")
        val match = classRegex.find(trimmed)
        if (match != null) {
            val code = match.groupValues[1]
            if (code.length == 8) return code
        }

        // 匹配任意独立的 8 位数字
        val digitsRegex = Regex("""\b(\d{8})\b""")
        val digitsMatch = digitsRegex.find(trimmed)
        if (digitsMatch != null) {
            return digitsMatch.groupValues[1]
        }

        // 如果用户直接输入纯 8 位数字
        if (trimmed.length == 8 && trimmed.all { it.isDigit() }) {
            return trimmed
        }

        return null
    }

    /**
     * 查询教务系统课程信息（按课程代码精确查询或按课程名称模糊查询）
     * 自动处理会话过期重登
     */
    suspend fun queryCourses(
        context: Context,
        courseCode: String = "",
        courseName: String = "",
        page: Int = 1
    ): Result<List<CourseBasicInfo>> = withContext(Dispatchers.IO) {
        try {
            ensureJwglSession(context)

            val url = "$BASE_URL/kckgl/kcjbxxwh_cxKcjbxxIndex.html?doType=query&gnmkdm=$GNMKDM"
            val formBody = FormBody.Builder()
                .add("kch", courseCode.trim())
                .add("kcmc", courseName.trim())
                .add("kkbm_id", "")
                .add("kcxzdm", "")
                .add("kclbdm", "")
                .add("_search", "false")
                .add("nd", System.currentTimeMillis().toString())
                .add("queryModel.showCount", "50")
                .add("queryModel.currentPage", page.toString())
                .add("queryModel.sortName", "kch")
                .add("queryModel.sortOrder", "asc")
                .add("time", "0")
                .build()

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$BASE_URL/kckgl/kcjbxxwh_cxKcjbxxIndex.html?gnmkdm=$GNMKDM")
                .header("X-Requested-With", "XMLHttpRequest")
                .post(formBody)
                .build()

            var resp = JwglClient.client.newCall(req).execute()
            var body = resp.body?.string() ?: ""

            // 如果会话过期，重新认证后重试一次
            if (!body.trimStart().startsWith("{")) {
                Log.w(TAG, "JWGL session expired while querying courses, re-logging in...")
                if (relogin(context)) {
                    resp = JwglClient.client.newCall(req).execute()
                    body = resp.body?.string() ?: ""
                }
            }

            if (!body.trimStart().startsWith("{")) {
                Log.e(TAG, "Non-JSON response from kcjbxxwh: ${body.take(200)}")
                return@withContext Result.failure(IOException("教务系统课程库查询失败，请检查登录会话"))
            }

            val json = JSONObject(body)
            val items = json.optJSONArray("items") ?: return@withContext Result.success(emptyList())

            val list = mutableListOf<CourseBasicInfo>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                Log.d(TAG, "kcjbxx item JSON: $item")
                val kch = item.optString("kch", item.optString("kch_id", "")).trim()
                val kcmc = item.optString("kcmc", "").trim()
                if (kch.isEmpty() && kcmc.isEmpty()) continue

                val kcgsmc = item.optString("kcgsmc", "").trim()
                if (kcgsmc.isNotEmpty()) {
                    cacheAffiliation(kch, kcgsmc, kcmc, context)
                }

                val courseInfo = CourseBasicInfo(
                    courseCode = kch,
                    courseName = kcmc,
                    englishName = item.optString("kcywmc", "").trim(),
                    credit = item.optString("xf", "0.0").toFloatOrNull() ?: 0f,
                    totalHours = item.optString("zxs", "0").toIntOrNull() ?: 0,
                    lectureHours = item.optString("llxs", "0").toIntOrNull() ?: 0,
                    labHours = item.optString("syxs", "0").toIntOrNull() ?: 0,
                    department = item.optString("kkbm", item.optString("kkbmmc", "")).trim(),
                    courseNature = item.optString("kcxzmc", "").trim(),
                    courseCategory = item.optString("kclbmc", "").trim(),
                    courseCategoryCode = item.optString("kclbdm", "").trim(),
                    courseAffiliation = kcgsmc,
                    syllabusId = item.optString("kcdg_id", "").trim()
                )
                cacheCourseInfo(courseInfo)
                list.add(courseInfo)
            }

            Log.i(TAG, "queryCourses success: found ${list.size} courses for kch='$courseCode', kcmc='$courseName'")
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "queryCourses error", e)
            Result.failure(e)
        }
    }

    /**
     * 根据课程代码反查唯一的标准课程名称
     */
    suspend fun queryCourseByCode(
        context: Context,
        courseCode: String
    ): Result<CourseBasicInfo?> = withContext(Dispatchers.IO) {
        val res = queryCourses(context, courseCode = courseCode)
        if (res.isSuccess) {
            val list = res.getOrNull() ?: emptyList()
            val match = list.firstOrNull { it.courseCode == courseCode } ?: list.firstOrNull()
            Result.success(match)
        } else {
            Result.failure(res.exceptionOrNull() ?: Exception("查询失败"))
        }
    }

    /**
     * 异步解析课程类别（先查缓存，未命中则请求教务系统，最后回退启发式推断）
     */
    suspend fun resolveCategory(
        context: Context,
        courseCode: String,
        courseName: String
    ): String = withContext(Dispatchers.IO) {
        if (courseCode.isNotEmpty() && categoryCache.containsKey(courseCode)) {
            return@withContext categoryCache[courseCode]!!
        }
        if (courseName.isNotEmpty() && categoryCache.containsKey(courseName)) {
            return@withContext categoryCache[courseName]!!
        }

        try {
            val res = if (courseCode.isNotEmpty()) {
                queryCourseByCode(context, courseCode)
            } else if (courseName.isNotEmpty()) {
                val listRes = queryCourses(context, courseName = courseName)
                if (listRes.isSuccess) Result.success(listRes.getOrNull()?.firstOrNull())
                else Result.failure(listRes.exceptionOrNull() ?: Exception("未查询到课程"))
            } else {
                Result.failure(Exception("缺少课程代码和名称"))
            }

            val info = res.getOrNull()
            if (info != null) {
                cacheCourseInfo(info)
                val cat = normalizeCategory(info.courseCategory, info.courseName, info.lectureHours, info.labHours)
                return@withContext cat
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveCategory failed for code=$courseCode, name=$courseName", e)
        }

        val fallback = normalizeCategory(null, courseName)
        if (fallback != "待定" && courseCode.isNotEmpty()) {
            categoryCache[courseCode] = fallback
        }
        return@withContext fallback
    }

    /**
     * 获取课程大纲的真实下载信息（通过专有字段查询接口 kcjbxxwh_cxKczdxx.html）
     * 查询 zdm=ZWJXDG (中文教学大纲) 或 YWJXDG (英文教学大纲)
     * 返回真实的 PDF/DOCX 下载链接与原始文件名
     */
    suspend fun fetchSyllabusInfo(
        context: Context,
        courseCode: String
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            ensureJwglSession(context)

            // 先优先检索中文教学大纲附件 (ZWJXDG)，若无则检索英文教学大纲 (YWJXDG)
            val pair = fetchZdmAttachment(context, courseCode, "ZWJXDG")
                ?: fetchZdmAttachment(context, courseCode, "YWJXDG")

            if (pair != null) {
                Log.i(TAG, "Successfully extracted syllabus downloadUrl: ${pair.first}, fileName=${pair.second}")
                Result.success(pair)
            } else {
                Result.failure(IOException("该课程在教务系统中暂未上传大纲文档附件"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchSyllabusInfo error for $courseCode", e)
            Result.failure(e)
        }
    }

    private suspend fun fetchZdmAttachment(context: Context, courseCode: String, zdm: String): Pair<String, String>? {
        val url = "$BASE_URL/kckgl/kcjbxxwh_cxKczdxx.html"
        val formBody = FormBody.Builder()
            .add("kch_id", courseCode.trim())
            .add("zdm", zdm)
            .build()

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$BASE_URL/kckgl/kcjbxxwh_cxKcjbxxIndex.html?gnmkdm=$GNMKDM")
            .header("X-Requested-With", "XMLHttpRequest")
            .post(formBody)
            .build()

        var resp = JwglClient.client.newCall(req).execute()
        var body = resp.body?.string() ?: ""

        if (!body.trimStart().startsWith("{")) {
            if (relogin(context)) {
                resp = JwglClient.client.newCall(req).execute()
                body = resp.body?.string() ?: ""
            }
        }

        if (body.trimStart().startsWith("{")) {
            val json = JSONObject(body)
            val htmlSnippet = json.optString("dm", "")
            Log.i(TAG, "fetchZdmAttachment for $courseCode ($zdm) snippet: ${htmlSnippet.take(150)}")

            // 解析 href="../ftp/..." 或 href="ftp/..."
            val hrefRegex = Regex("""href=["'](?:\.\./)?ftp/([^"'?]+)(?:\?filename=([^"'>\s]+))?["']""")
            val match = hrefRegex.find(htmlSnippet)
            if (match != null) {
                val token = match.groupValues[1]
                val filenameParam = match.groupValues.getOrNull(2) ?: ""
                val decodedFileName = try {
                    java.net.URLDecoder.decode(filenameParam, "UTF-8")
                } catch (e: Exception) {
                    filenameParam
                }

                val downloadUrl = if (filenameParam.isNotEmpty()) {
                    "$BASE_URL/ftp/$token?filename=$filenameParam"
                } else {
                    "$BASE_URL/ftp/$token"
                }
                return Pair(downloadUrl, decodedFileName)
            }
        }
        return null
    }

    /**
     * 下载课程大纲文件到本地（优先下载教务系统真实 PDF/DOCX 原件）
     */
    suspend fun downloadSyllabusFile(
        context: Context,
        courseCode: String,
        courseName: String,
        targetDirOrFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            ensureJwglSession(context)

            val targetDir = if (targetDirOrFile.isDirectory || !targetDirOrFile.name.contains(".")) {
                targetDirOrFile
            } else {
                targetDirOrFile.parentFile ?: targetDirOrFile
            }
            if (!targetDir.exists()) targetDir.mkdirs()

            // 1. 优先获取教务系统真实 PDF/DOCX 大纲附件
            val infoRes = fetchSyllabusInfo(context, courseCode)
            if (infoRes.isSuccess) {
                val (downloadUrl, originalName) = infoRes.getOrNull()!!
                Log.i(TAG, "Downloading genuine syllabus from $downloadUrl")

                val req = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "$BASE_URL/kckgl/kcjbxxwh_cxKcjbxxIndex.html?gnmkdm=$GNMKDM")
                    .get()
                    .build()

                var resp = JwglClient.client.newCall(req).execute()
                if (resp.code == 302 || resp.code == 401) {
                    if (relogin(context)) {
                        resp = JwglClient.client.newCall(req).execute()
                    }
                }

                if (resp.isSuccessful) {
                    val cleanCourseName = courseName.replace(Regex("""[\\/:*?"<>|]"""), "_")
                    val ext = if (originalName.contains(".")) originalName.substringAfterLast(".") else "pdf"
                    val saveName = if (originalName.isNotBlank()) originalName else "${cleanCourseName}_${courseCode}_教学大纲.$ext"
                    val targetFile = File(targetDir, saveName)

                    resp.body?.byteStream()?.use { input ->
                        FileOutputStream(targetFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.i(TAG, "Genuine syllabus file saved: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                    return@withContext Result.success(targetFile)
                }
            }

            // 2. 回退方案：如果教务系统中未上传附件，生成标准课程大纲规范文档
            val basicInfoRes = queryCourseByCode(context, courseCode)
            val info = basicInfoRes.getOrNull()
            val cleanCourseName = courseName.replace(Regex("""[\\/:*?"<>|]"""), "_")
            val targetFile = File(targetDir, "${cleanCourseName}_${courseCode}_课程信息大纲.html")

            val fallbackHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>《${courseName}》课程大纲与基本信息</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; padding: 24px; color: #212121; line-height: 1.6; }
                        h2 { color: #1976D2; border-bottom: 2px solid #1976D2; padding-bottom: 8px; }
                        table { width: 100%; border-collapse: collapse; margin-top: 16px; }
                        th, td { border: 1px solid #ddd; padding: 10px 14px; font-size: 14px; text-align: left; }
                        th { background-color: #f5f8fa; color: #333; width: 140px; }
                        .tip { margin-top: 20px; font-size: 12px; color: #757575; }
                    </style>
                </head>
                <body>
                    <h2>《${courseName}》课程教学基本信息</h2>
                    <table>
                        <tr><th>课程代码</th><td>${info?.courseCode ?: courseCode}</td></tr>
                        <tr><th>课程名称</th><td>${info?.courseName ?: courseName}</td></tr>
                        <tr><th>英文名称</th><td>${info?.englishName?.ifEmpty { "暂无" } ?: "暂无"}</td></tr>
                        <tr><th>开课学院</th><td>${info?.department?.ifEmpty { "暂无" } ?: "暂无"}</td></tr>
                        <tr><th>学分</th><td>${info?.credit ?: 0f} 学分</td></tr>
                        <tr><th>总学时</th><td>${info?.totalHours ?: 0} 学时</td></tr>
                        <tr><th>理论学时</th><td>${info?.lectureHours ?: 0} 学时</td></tr>
                        <tr><th>实验学时</th><td>${info?.labHours ?: 0} 学时</td></tr>
                        <tr><th>课程性质</th><td>${info?.courseNature?.ifEmpty { "通识必修" } ?: "通识必修"}</td></tr>
                    </table>
                    <p class="tip">注：教务系统中开课单位暂未上传独立的 PDF 附件大纲文档，本文件由正方教务系统课程库自动生成导出。</p>
                </body>
                </html>
            """.trimIndent()

            targetFile.writeText(fallbackHtml, Charsets.UTF_8)
            Log.i(TAG, "Generated syllabus summary file: ${targetFile.absolutePath}")
            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "downloadSyllabusFile error", e)
            Result.failure(e)
        }
    }

    private suspend fun ensureJwglSession(context: Context) {
        if (!JwglClient.hasValidSession()) {
            relogin(context)
        }
    }

    private suspend fun relogin(context: Context): Boolean {
        val auth = AuthPreferences(context)
        val id = auth.getStudentId()
        val pwd = auth.getPassword()
        if (id.isNotEmpty() && pwd.isNotEmpty()) {
            val res = JwglClient.login(id, pwd)
            return res.isSuccess
        }
        return false
    }
}
