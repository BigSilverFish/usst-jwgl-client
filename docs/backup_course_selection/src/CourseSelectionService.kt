package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.util.Log
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.model.SelectedCourseItem
import cn.edu.usst.jwgl.data.model.SelectionCategoryTab
import cn.edu.usst.jwgl.data.model.SelectionContext
import cn.edu.usst.jwgl.data.model.SelectionCourse
import cn.edu.usst.jwgl.data.model.SelectionOperationResult
import cn.edu.usst.jwgl.data.model.TeachingClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

object CourseSelectionService {
    private const val TAG = "CourseSelectionService"

    // 全局教学班缓存 (courseUniqueId -> List<TeachingClass>)
    private val classCache = ConcurrentHashMap<String, List<TeachingClass>>()

    fun getCachedClasses(courseUniqueId: String): List<TeachingClass>? = classCache[courseUniqueId]

    fun cacheClasses(courseUniqueId: String, classes: List<TeachingClass>) {
        classCache[courseUniqueId] = classes
    }

    fun clearClassesCache() {
        classCache.clear()
    }

    fun removeCachedClasses(courseUniqueId: String) {
        classCache.remove(courseUniqueId)
    }
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val GNMKDM = "N253512"

    private const val INDEX_URL = "https://jwgl.usst.edu.cn/jwglxt/xsxk/zzxkyzb_cxZzxkYzbIndex.html?gnmkdm=$GNMKDM&layout=default"
    private const val DISPLAY_URL = "https://jwgl.usst.edu.cn/jwglxt/xsxk/zzxkyzb_cxZzxkYzbDisplay.html?gnmkdm=$GNMKDM"
    private const val LIST_URL = "https://jwgl.usst.edu.cn/jwglxt/xsxk/zzxkyzb_cxZzxkYzbPartDisplay.html?gnmkdm=$GNMKDM"
    private const val JXB_URL = "https://jwgl.usst.edu.cn/jwglxt/xsxk/zzxkyzbjk_cxJxbWithKchZzxkYzb.html?gnmkdm=$GNMKDM"
    private const val SELECT_URL = "https://jwgl.usst.edu.cn/jwglxt/xsxk/zzxkyzbjk_xkBcZyZzxkYzb.html?gnmkdm=$GNMKDM"
    private const val DROP_URL = "https://jwgl.usst.edu.cn/jwglxt/xsxk/zzxkyzb_tuikBcZzxkYzb.html?gnmkdm=$GNMKDM"
    private const val CHOOSED_URL = "https://jwgl.usst.edu.cn/jwglxt/xsxk/zzxkyzb_cxZzxkYzbChoosedDisplay.html?gnmkdm=$GNMKDM"

    // 精确正方教务字段白名单（避免多余隐藏字段触发防火墙安全拦截）
    private val COMMON_KEYS = "rwlx xklc xkly bklx_id sfkkjyxdxnxq kzkcgs xqh_id jg_id njdm_id zyh_id zyfx_id bh_id xbm xslbdm mzm xz ccdm xsbj gnjkxdnj sfkknj sfkkzy kzybkxy sfznkx zdkxms sfkxq sfkcfx kkbk kkbkdj bklbkcj xkxnm xkxqm kklxdm bbhzxjxb zxgbxkkg rlkz".split(" ")
    private val LIST_KEYS = COMMON_KEYS + "njdm_id_1 zyh_id_1 njdm_id_xs zyh_id_xs bjgkczxbbjwcx bhbcyxkjxb sfkgbcx sfrxtgkcxd tykczgxdcs xkzgbj jxbzb zh kspage jspage".split(" ")
    private val SECTION_KEYS = COMMON_KEYS + "kch_id bhbcyxkjxb jxbzcxskg xkxskcgskg cxbj fxbj cxcykclxxskg rlzlkz txbsfrl cdrlkz".split(" ")
    private val SELECTION_KEYS = "jxb_ids kch_id kcmc rwlx rlkz cdrlkz rlzlkz sxbj xxkbj qz cxbj njdm_id zyh_id kklxdm xklc xkxnm xkxqm jcxx_id xkly sfkxq".split(" ")
    private val SELECTED_KEYS = "xkxnm xkxqm xqh_id jg_id njdm_id zyh_id zyfx_id bh_id xz ccdm xkly".split(" ")

    /**
     * 自动检查并重试登录
     */
    private suspend fun ensureValidSession(context: Context?): Boolean {
        if (context == null) return false
        val auth = AuthPreferences(context)
        val id = auth.getStudentId()
        val pwd = auth.getPassword()
        if (id.isNotEmpty() && pwd.isNotEmpty()) {
            Log.d(TAG, "Attempting silent re-login for course selection...")
            val loginRes = JwglClient.login(id, pwd)
            return loginRes.isSuccess
        }
        return false
    }

    /**
     * 加载选课入口，解析可用分类标签与控制参数
     */
    suspend fun fetchSelectionContext(context: Context? = null): Result<SelectionContext> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(INDEX_URL)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://jwgl.usst.edu.cn/jwglxt/xtgl/index_initMenu.html")
                .get()
                .build()

            var resp = JwglClient.client.newCall(req).execute()
            var html = resp.body?.string() ?: ""

            // 检查是否会话失效重定向到登录页
            if (resp.request.url.toString().contains("authserver") || html.contains("userNamePasswordLogin") || html.contains("登录")) {
                if (ensureValidSession(context)) {
                    resp = JwglClient.client.newCall(req).execute()
                    html = resp.body?.string() ?: ""
                } else {
                    return@withContext Result.failure(IOException("登录已失效，请重新登录教务系统"))
                }
            }

            val doc = Jsoup.parse(html)
            val indexParams = mutableMapOf<String, String>()
            doc.select("input[type=hidden]").forEach { elem ->
                val name = elem.attr("name").ifEmpty { elem.id() }
                if (name.isNotEmpty()) {
                    indexParams[name] = elem.`val`()
                }
            }

            Log.d(TAG, "fetchSelectionContext indexParams: $indexParams")
            val choosedMatches = Regex("""[a-zA-Z0-9_/]+[cC]hoosed[a-zA-Z0-9_/.]*""").findAll(html).map { it.value }.toList()
            Log.d(TAG, "Choosed regex matches in INDEX_URL: $choosedMatches")
            doc.select("a, button, li").forEach { elem ->
                if (elem.text().contains("已选") || elem.attr("onclick").contains("Choosed") || elem.attr("onclick").contains("choosed")) {
                    Log.d(TAG, "Found element related to chosen: tag=${elem.tagName()}, text=${elem.text()}, onclick=${elem.attr("onclick")}, href=${elem.attr("href")}")
                }
            }

            val xkxnm = indexParams["xkxnm"] ?: "2026"
            val xkxqm = indexParams["xkxqm"] ?: "3"

            // 解析 queryCourse 标签
            val callsRegex = Regex("""queryCourse\(this,\s*'([^']+)',\s*'([^']+)',\s*'([^']+)',\s*'([^']+)',\s*'([^']+)'\)""")
            val categories = mutableListOf<SelectionCategoryTab>()

            // 查找标签名
            val tabMap = mutableMapOf<String, String>()
            doc.select("a[onclick*='queryCourse']").forEach { a ->
                val oc = a.attr("onclick")
                val m = callsRegex.find(oc)
                if (m != null) {
                    val code = m.groupValues[1]
                    val title = a.text().trim()
                    if (title.isNotEmpty()) {
                        tabMap[code] = title
                    }
                }
            }

            callsRegex.findAll(html).forEach { match ->
                val kklxdm = match.groupValues[1]
                val xkkzId = match.groupValues[2]
                val njdmId = match.groupValues[3]
                val zyhId = match.groupValues[4]
                val xkkzXh = match.groupValues[5]

                val name = tabMap[kklxdm] ?: when (kklxdm) {
                    "01" -> "学科或专业课程"
                    "10" -> "通识课程"
                    "06" -> "主修课程"
                    else -> "选课分类 $kklxdm"
                }

                if (categories.none { it.id == kklxdm }) {
                    categories.add(
                        SelectionCategoryTab(
                            id = kklxdm,
                            name = name,
                            xkkzId = xkkzId,
                            xkkzXh = xkkzXh,
                            grade = njdmId,
                            major = zyhId
                        )
                    )
                }
            }

            if (categories.isEmpty() && indexParams.containsKey("firstKklxdm")) {
                val firstId = indexParams["firstKklxdm"].orEmpty()
                val firstName = indexParams["firstKklxmc"].orEmpty().ifEmpty { "主选课程" }
                val firstXkkzId = indexParams["firstXkkzId"].orEmpty()
                val firstXkkzXh = indexParams["firstXkkzXh"].orEmpty()
                val firstNj = indexParams["firstNjdmId"].orEmpty().ifEmpty { indexParams["njdm_id"].orEmpty() }
                val firstZy = indexParams["firstZyhId"].orEmpty().ifEmpty { indexParams["zyh_id"].orEmpty() }

                categories.add(
                    SelectionCategoryTab(
                        id = firstId,
                        name = firstName,
                        xkkzId = firstXkkzId,
                        xkkzXh = firstXkkzXh,
                        grade = firstNj,
                        major = firstZy
                    )
                )
            }

            // 解析选课轮次与起止时间
            var roundName = indexParams["xklcmc"].orEmpty()
            if (roundName.isEmpty()) {
                val lc = indexParams["xklc"].orEmpty()
                if (lc.isNotEmpty()) {
                    roundName = if (lc.contains("轮")) lc else "第${lc}轮选课"
                }
            }

            var timeRange = indexParams["qzsj"].orEmpty()
            if (timeRange.isEmpty() && indexParams.containsKey("kssj") && indexParams.containsKey("jssj")) {
                timeRange = "${indexParams["kssj"]} ~ ${indexParams["jssj"]}"
            }

            // 从页面文本或 alert/notice 提示栏抓取轮次和起止时间
            val noticeText = doc.select(".alert, .notice-con, .panel-body, .panel-heading, #msg, .col-md-12, .col-sm-12").text()
            Log.d(TAG, "fetchSelectionContext noticeText: $noticeText")
            if (roundName.isEmpty()) {
                val roundMatch = Regex("""(第[0-9一二三四1-4]轮[^\s，,、；;（(]*)""").find(noticeText)
                if (roundMatch != null) {
                    roundName = roundMatch.groupValues[1]
                }
            }
            if (timeRange.isEmpty()) {
                val timeMatch = Regex("""(\d{4}[-/]\d{1,2}[-/]\d{1,2}(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?\s*(?:至|~|-)\s*\d{4}[-/]\d{1,2}[-/]\d{1,2}(?:\s+\d{1,2}:\d{2}(?::\d{2})?)?)""").find(noticeText)
                if (timeMatch != null) {
                    timeRange = timeMatch.groupValues[1]
                }
            }

            Log.d(TAG, "Parsed selection context: xkxnm=$xkxnm, xkxqm=$xkxqm, round=$roundName, timeRange=$timeRange, categories=${categories.size}")
            Result.success(SelectionContext(xkxnm, xkxqm, roundName, timeRange, indexParams, categories))
        } catch (e: Exception) {
            Log.e(TAG, "fetchSelectionContext failed", e)
            Result.failure(e)
        }
    }

    /**
     * 加载并缓存分类对应的 Display 隐藏字段
     */
    suspend fun loadCategoryDisplay(
        tab: SelectionCategoryTab,
        selectionContext: SelectionContext,
        context: Context? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (tab.displayParams.isNotEmpty()) {
            return@withContext Result.success(Unit)
        }
        try {
            val formBuilder = FormBody.Builder()
                .add("xkkz_id", tab.xkkzId)
                .add("xszxzt", selectionContext.indexParams["xszxzt"] ?: "1")
                .add("kspage", "0")
                .add("jspage", "0")
                .add("kklxdm", tab.id)
                .add("njdm_id", tab.grade)
                .add("zyh_id", tab.major)

            val req = Request.Builder()
                .url(DISPLAY_URL)
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", INDEX_URL)
                .post(formBuilder.build())
                .build()

            val resp = JwglClient.client.newCall(req).execute()
            val html = resp.body?.string() ?: ""

            val doc = Jsoup.parse(html)
            doc.select("input[type=hidden]").forEach { elem ->
                val name = elem.attr("name").ifEmpty { elem.id() }
                if (name.isNotEmpty()) {
                    tab.displayParams[name] = elem.`val`()
                }
            }
            Log.d(TAG, "Loaded display params for tab ${tab.id}: ${tab.displayParams.size} fields: ${tab.displayParams}")

            // Log all select dropdowns in DISPLAY_URL
            doc.select("select").forEach { sel ->
                val selName = sel.attr("name").ifEmpty { sel.id() }
                val options = sel.select("option").map { "${it.`val`()}:${it.text()}" }
                Log.d(TAG, "DISPLAY_URL select [$selName]: $options")
            }
            // Save html to /sdcard/display_tab_${tab.id}.html for analysis
            try {
                java.io.File("/sdcard/display_tab_${tab.id}.html").writeText(html)
            } catch (e: Exception) {
                Log.w(TAG, "Could not save display html", e)
            }

            if (selectionContext.roundName.isEmpty()) {
                val lc = tab.displayParams["xklcmc"]?.ifEmpty { null }
                    ?: tab.displayParams["xklc"]?.let { if (it.contains("轮")) it else "第${it}轮选课" }
                if (!lc.isNullOrEmpty()) {
                    selectionContext.roundName = lc
                }
            }
            val kssj = tab.displayParams["xkkssj"] ?: tab.displayParams["kssj"] ?: ""
            val jssj = tab.displayParams["xkjssj"] ?: tab.displayParams["jssj"] ?: ""
            if (kssj.isNotEmpty() && jssj.isNotEmpty()) {
                val start = if (kssj.length >= 16) kssj.substring(0, 16) else kssj
                val end = if (jssj.length >= 16) jssj.substring(0, 16) else jssj
                selectionContext.timeRange = "$start - $end"
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "loadCategoryDisplay failed for tab ${tab.id}", e)
            Result.failure(e)
        }
    }

    /**
     * 检索课程列表（支持关键词搜索、分页）
     */
    suspend fun queryCourses(
        tab: SelectionCategoryTab,
        selectionContext: SelectionContext,
        keyword: String = "",
        page: Int = 1,
        pageSize: Int = 15,
        context: Context? = null
    ): Result<List<SelectionCourse>> = withContext(Dispatchers.IO) {
        try {
            loadCategoryDisplay(tab, selectionContext, context)

            val source = mutableMapOf<String, String>()
            source.putAll(selectionContext.indexParams)
            source.putAll(tab.displayParams)
            source["kklxdm"] = tab.id
            source["xkkz_id"] = tab.xkkzId
            source["xkkz_xh"] = tab.xkkzXh
            source["njdm_id"] = tab.grade
            source["zyh_id"] = tab.major
            if (source.containsKey("jg_id_1")) {
                source["jg_id"] = source["jg_id_1"].orEmpty()
            }

            val kspage = ((page - 1) * pageSize + 1).toString()
            val jspage = (page * pageSize).toString()
            source["kspage"] = kspage
            source["jspage"] = jspage
            source["xkkz_id"] = tab.xkkzId
            source["xkkz_xh"] = tab.xkkzXh
            if (keyword.isNotBlank()) {
                source["filter_list[0]"] = keyword.trim()
            }

            val formBuilder = FormBody.Builder()
            val keysToSubmit = linkedSetOf<String>()
            keysToSubmit.addAll(LIST_KEYS)
            keysToSubmit.add("xkkz_id")
            keysToSubmit.add("xkkz_xh")
            if (keyword.isNotBlank()) {
                keysToSubmit.add("filter_list[0]")
            }

            keysToSubmit.forEach { key ->
                val v = source[key]
                if (v != null) {
                    formBuilder.add(key, v)
                }
            }

            val form = formBuilder.build()
            Log.d(TAG, "queryCourses URL: $LIST_URL, params count: ${form.size}")
            for (idx in 0 until form.size) {
                Log.d(TAG, "  param[${form.name(idx)}] = ${form.value(idx)}")
            }

            val req = Request.Builder()
                .url(LIST_URL)
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", INDEX_URL)
                .post(form)
                .build()

            val resp = JwglClient.client.newCall(req).execute()
            val jsonStr = resp.body?.string() ?: ""
            Log.d(TAG, "queryCourses response code: ${resp.code}, len: ${jsonStr.length}, snippet: ${jsonStr.take(300)}")

            if (!jsonStr.trimStart().startsWith("{")) {
                Log.e(TAG, "Non-JSON response from PartDisplay: $jsonStr")
                return@withContext Result.failure(IOException("教务系统返回了非JSON响应，可能需要重新刷新入口"))
            }

            val jsonObj = JSONObject(jsonStr)
            val tmpList = jsonObj.optJSONArray("tmpList") ?: JSONArray()
            val result = mutableListOf<SelectionCourse>()

            for (i in 0 until tmpList.length()) {
                val item = tmpList.optJSONObject(i) ?: continue
                val courseId = item.optString("kch", "")
                if (i < 2 || courseId == "14003440" || courseId == "12102994") {
                    Log.d(TAG, "Course item JSON ($courseId): $item")
                }
                val courseUniqueId = item.optString("kch_id", "")
                val courseName = item.optString("kcmc", "")
                val credit = item.optDouble("xf", 0.0)
                val hours = item.optString("rwzxs", "")
                val categoryCode = item.optString("kklxdm", tab.id)
                val rawType = item.optString("kkgsmc")
                    .ifEmpty { item.optString("kcxzmc") }
                    .ifEmpty { item.optString("kcxz") }
                    .ifEmpty { item.optString("kklxmc") }
                    .ifEmpty { item.optString("kclbmc") }
                    .ifEmpty { item.optString("kcgs") }
                    .ifEmpty { item.optString("kkgxmc") }

                val isGeneralTab = tab.id == "10" || tab.name.contains("通识")
                val isMajorTab = tab.id == "01" || tab.name.contains("学科") || tab.name.contains("专业")

                val courseTypeName = when {
                    rawType.isNotEmpty() && rawType != "通识课程" && !rawType.contains("选课") -> rawType
                    isMajorTab -> {
                        // 在学科基础专业课程中，xxkbj == "0" 为学科基础课程（必修），xxkbj == "1" 为专业课程（选修）
                        if (item.optString("xxkbj") == "0") "学科基础课程" else "专业课程"
                    }
                    isGeneralTab -> {
                        val cachedAff = CourseCatalogService.getAffiliation(courseId, courseName)
                        if (cachedAff.isNotEmpty()) cachedAff
                        else {
                            val h = CourseCatalogService.heuristicAffiliation(courseId, courseName)
                            if (h.isNotEmpty()) h else "通识课程"
                        }
                    }
                    else -> "专业课程"
                }
                val selectedCount = item.optInt("yxzrs", 0)
                val isRetake = item.optString("cxbj", "0") == "1"
                val collegeName = item.optString("kkxymc")
                    .ifEmpty { item.optString("jgmc") }
                    .ifEmpty { item.optString("kkbm") }
                    .ifEmpty { item.optString("kkbmmc") }
                    .ifEmpty { item.optString("xy") }

                result.add(
                    SelectionCourse(
                        courseId = courseId,
                        courseUniqueId = courseUniqueId,
                        courseName = courseName,
                        credit = credit,
                        hours = hours,
                        categoryCode = categoryCode,
                        courseTypeName = courseTypeName,
                        selectedCount = selectedCount,
                        isRetake = isRetake,
                        collegeName = collegeName
                    )
                )
            }

            val distinctResult = result.distinctBy { it.courseId.ifEmpty { it.courseUniqueId } }.toMutableList()

            // 如果是通识课程，自动向教务系统课程库查询权威的 kcgsmc（课程归属分类），如“创新思维与创业实践”、“全球视野与文明对话”等
            val isGeneralTab = tab.id == "10" || tab.name.contains("通识")
            if (isGeneralTab && context != null) {
                val coursesToFetch = distinctResult.filter { !CourseCatalogService.hasAuthoritativeAffiliation(it.courseId) }
                if (coursesToFetch.isNotEmpty()) {
                    coroutineScope {
                        coursesToFetch.chunked(8).forEach { batch ->
                            batch.map { c ->
                                async {
                                    try {
                                        val catRes = CourseCatalogService.queryCourses(context, courseCode = c.courseId)
                                        val match = catRes.getOrNull()?.firstOrNull { it.courseCode == c.courseId }
                                        if (match != null && match.courseAffiliation.isNotEmpty()) {
                                            CourseCatalogService.cacheAffiliation(c.courseId, match.courseAffiliation, c.courseName, context)
                                        }
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Failed to resolve affiliation for ${c.courseId}", e)
                                    }
                                }
                            }.awaitAll()
                        }
                    }
                    // 用最新获取的权威课程归属更新列表项
                    for (idx in distinctResult.indices) {
                        val c = distinctResult[idx]
                        val aff = CourseCatalogService.getAffiliation(c.courseId, c.courseName)
                        if (aff.isNotEmpty() && c.courseTypeName != aff) {
                            distinctResult[idx] = c.copy(courseTypeName = aff)
                        }
                    }
                }
            }

            Log.d(TAG, "Queried courses for tab ${tab.id} (kw='$keyword', p=$page): ${distinctResult.size} distinct courses (from ${result.size} raw)")
            Result.success(distinctResult)
        } catch (e: Exception) {
            Log.e(TAG, "queryCourses failed", e)
            Result.failure(e)
        }
    }

    /**
     * 查询指定课程的教学班列表及名额余量
     */
    suspend fun queryTeachingClasses(
        tab: SelectionCategoryTab,
        selectionContext: SelectionContext,
        course: SelectionCourse,
        context: Context? = null,
        forceRefresh: Boolean = false
    ): Result<List<TeachingClass>> = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            val cached = classCache[course.courseUniqueId]
            if (cached != null) {
                return@withContext Result.success(cached)
            }
        }
        try {
            loadCategoryDisplay(tab, selectionContext, context)

            val source = mutableMapOf<String, String>()
            source.putAll(selectionContext.indexParams)
            source.putAll(tab.displayParams)
            source["kklxdm"] = tab.id
            source["xkkz_id"] = tab.xkkzId
            source["xkkz_xh"] = tab.xkkzXh
            source["njdm_id"] = tab.grade
            source["zyh_id"] = tab.major
            if (source.containsKey("jg_id_1")) {
                source["jg_id"] = source["jg_id_1"].orEmpty()
            }

            val formBuilder = FormBody.Builder()
            SECTION_KEYS.forEach { key ->
                val v = source[key]
                if (!v.isNullOrEmpty()) {
                    formBuilder.add(key, v)
                }
            }
            formBuilder.add("kch_id", course.courseUniqueId)
            formBuilder.add("xkkz_id", tab.xkkzId)
            formBuilder.add("xkkz_xh", tab.xkkzXh)

            val req = Request.Builder()
                .url(JXB_URL)
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", INDEX_URL)
                .post(formBuilder.build())
                .build()

            val resp = JwglClient.client.newCall(req).execute()
            val respText = resp.body?.string() ?: ""

            val list = mutableListOf<TeachingClass>()
            if (respText.trimStart().startsWith("[")) {
                val jsonArr = JSONArray(respText)
                for (i in 0 until jsonArr.length()) {
                    val item = jsonArr.optJSONObject(i) ?: continue
                    list.add(parseTeachingClass(item, course))
                }
            } else if (respText.trimStart().startsWith("{")) {
                val jsonObj = JSONObject(respText)
                val jsonArr = jsonObj.optJSONArray("tmpList")
                    ?: jsonObj.optJSONArray("data")
                    ?: jsonObj.optJSONArray("jxbList")
                    ?: JSONArray()
                for (i in 0 until jsonArr.length()) {
                    val item = jsonArr.optJSONObject(i) ?: continue
                    list.add(parseTeachingClass(item, course))
                }
            }

            Log.d(TAG, "Queried ${list.size} classes for course ${course.courseId} (${course.courseName})")
            classCache[course.courseUniqueId] = list
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "queryTeachingClasses failed", e)
            Result.failure(e)
        }
    }

    /**
     * 批量并发预拉取教学班信息（受限并发度 5）
     */
    suspend fun prefetchTeachingClasses(
        tab: SelectionCategoryTab,
        selectionContext: SelectionContext,
        courses: List<SelectionCourse>,
        context: Context? = null,
        onCourseFinished: ((SelectionCourse) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val uncached = courses.filter { !classCache.containsKey(it.courseUniqueId) }
        if (uncached.isEmpty()) return@withContext
        val sem = Semaphore(5)
        coroutineScope {
            uncached.map { course ->
                async {
                    sem.withPermit {
                        try {
                            queryTeachingClasses(tab, selectionContext, course, context)
                        } catch (e: Exception) {
                            Log.w(TAG, "Prefetch classes error for ${course.courseId}", e)
                        }
                        onCourseFinished?.invoke(course)
                    }
                }
            }.awaitAll()
        }
    }

    private fun parseTeachingClass(item: JSONObject, course: SelectionCourse): TeachingClass {
        Log.d(TAG, "TeachingClass raw JSON: $item")
        val jxbId = item.optString("jxb_id", "")
        val doJxbId = item.optString("do_jxb_id", jxbId)
        val className = item.optString("jxbmc", "")
            .ifEmpty { item.optString("bjmc", "") }
            .ifEmpty { item.optString("bj", "") }
            .ifEmpty { item.optString("fzmc", "") }
        val jxbbh = item.optString("jxbbh", "").ifEmpty { item.optString("bh", "") }
        val teacherRaw = item.optString("jsxx", item.optString("jsxm", ""))
        val scheduleTime = item.optString("sksj", "")
        val location = item.optString("jxdd", "")
        val campus = item.optString("xqumc", "")
        val capacity = item.optInt("jxbrl", 0)
        val selectedCount = item.optInt("yxzrs", 0)
        val remaining = (capacity - selectedCount).coerceAtLeast(0)
        val credit = item.optDouble("xf", course.credit)
        val college = item.optString("kkxymc", "")
        val mode = item.optString("jxms", "")

        val czOper = item.optString("czOper", "")
        val isAlreadyChosen = czOper.contains("退选") || czOper.contains("tuik") ||
                item.optString("xkbj") == "1" || item.optString("sfyx") == "1" ||
                item.optString("isSlct") == "1"

        return TeachingClass(
            jxbId = jxbId,
            doJxbId = doJxbId,
            className = className,
            jxbbh = jxbbh,
            teacherInfo = teacherRaw,
            scheduleTime = scheduleTime,
            location = location,
            campus = campus,
            capacity = capacity,
            selectedCount = selectedCount,
            remainingCapacity = remaining,
            credit = credit,
            collegeName = college,
            teachingMode = mode,
            isChosenByStudent = isAlreadyChosen
        )
    }

    /**
     * 提交选课
     */
    suspend fun selectCourse(
        tab: SelectionCategoryTab,
        selectionContext: SelectionContext,
        course: SelectionCourse,
        section: TeachingClass,
        context: Context? = null
    ): Result<SelectionOperationResult> = withContext(Dispatchers.IO) {
        try {
            loadCategoryDisplay(tab, selectionContext, context)

            val source = mutableMapOf<String, String>()
            source.putAll(selectionContext.indexParams)
            source.putAll(tab.displayParams)

            val formBuilder = FormBody.Builder()
            SELECTION_KEYS.forEach { key ->
                val v = source[key]
                if (!v.isNullOrEmpty()) {
                    formBuilder.add(key, v)
                }
            }

            formBuilder.add("jxb_ids", section.doJxbId)
            formBuilder.add("kch_id", course.courseUniqueId)
            formBuilder.add("kcmc", course.courseName)
            formBuilder.add("kklxdm", tab.id)
            formBuilder.add("qz", "0")
            formBuilder.add("xkkz_id", tab.xkkzId)
            formBuilder.add("xkkz_xh", tab.xkkzXh)

            val req = Request.Builder()
                .url(SELECT_URL)
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", INDEX_URL)
                .post(formBuilder.build())
                .build()

            val resp = JwglClient.client.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            Log.d(TAG, "Select course response: $body")

            if (body.trimStart().startsWith("{")) {
                val json = JSONObject(body)
                val flag = json.optString("flag", "")
                val msg = json.optString("msg", json.optString("message", ""))
                if (flag == "1") {
                    Result.success(SelectionOperationResult(true, "选课成功！", flag))
                } else {
                    val displayMsg = if (msg.isNotEmpty()) msg else if (flag == "-1") "名额已满或选课冲突" else "选课失败 (错误代码: $flag)"
                    Result.success(SelectionOperationResult(false, displayMsg, flag))
                }
            } else if (body.trim() == "1" || body.contains("\"flag\":\"1\"")) {
                Result.success(SelectionOperationResult(true, "选课成功！", "1"))
            } else {
                val doc = Jsoup.parse(body)
                val alert = doc.select(".alert, #msg, p").text().trim()
                Result.success(SelectionOperationResult(false, alert.ifEmpty { "选课异常，未获得成功确认" }))
            }
        } catch (e: Exception) {
            Log.e(TAG, "selectCourse failed", e)
            Result.failure(e)
        }
    }

    /**
     * 查询已选课程清单
     */
    /**
     * 查询已选课程清单
     */
    suspend fun querySelectedCourses(
        selectionContext: SelectionContext? = null,
        tab: SelectionCategoryTab? = null,
        context: Context? = null
    ): Result<List<SelectedCourseItem>> = withContext(Dispatchers.IO) {
        try {
            val xkxnm = selectionContext?.xkxnm ?: "2026"
            val xkxqm = selectionContext?.xkxqm ?: "3"

            val source = mutableMapOf<String, String>()
            if (selectionContext != null) {
                source.putAll(selectionContext.indexParams)
                selectionContext.categories.forEach { source.putAll(it.displayParams) }
            }
            tab?.let { source.putAll(it.displayParams) }

            val activeTab = tab ?: selectionContext?.categories?.firstOrNull()
            if (activeTab != null && selectionContext != null && activeTab.displayParams.isEmpty()) {
                loadCategoryDisplay(activeTab, selectionContext, context)
                source.putAll(activeTab.displayParams)
            }

            val formBuilder = FormBody.Builder()
            val sentKeys = mutableSetOf<String>()
            fun addParam(k: String, v: String) {
                if (v.isNotEmpty() && sentKeys.add(k)) {
                    formBuilder.add(k, v)
                }
            }

            addParam("xkxnm", xkxnm)
            addParam("xkxqm", xkxqm)

            SELECTED_KEYS.forEach { k ->
                val v = source[k]
                if (!v.isNullOrEmpty()) {
                    addParam(k, v)
                }
            }
            val jgId1 = source["jg_id_1"]
            if (!jgId1.isNullOrEmpty() && !sentKeys.contains("jg_id")) {
                addParam("jg_id", jgId1)
            }

            val form = formBuilder.build()
            Log.d(TAG, "querySelectedCourses URL: $CHOOSED_URL, params count: ${form.size}")
            for (idx in 0 until form.size) {
                Log.d(TAG, "  choosed param[${form.name(idx)}] = ${form.value(idx)}")
            }

            val req = Request.Builder()
                .url(CHOOSED_URL)
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", INDEX_URL)
                .post(form)
                .build()

            var resp = JwglClient.client.newCall(req).execute()
            var respText = resp.body?.string() ?: ""
            Log.d(TAG, "Selected courses resp code: ${resp.code}, text: $respText")

            if (respText.trim() == "[]" || respText.trim().isEmpty()) {
                // Minimal params fallback (as in ZfAcademicAdapterCourseTest)
                val minForm = FormBody.Builder()
                    .add("xkxnm", xkxnm)
                    .add("xkxqm", xkxqm)
                    .build()
                val minReq = Request.Builder()
                    .url(CHOOSED_URL)
                    .header("User-Agent", USER_AGENT)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("Referer", INDEX_URL)
                    .post(minForm)
                    .build()
                val minResp = JwglClient.client.newCall(minReq).execute()
                val minText = minResp.body?.string() ?: ""
                Log.d(TAG, "Selected courses minimal fallback code: ${minResp.code}, text: $minText")
                if (minText.trim() != "[]" && minText.trim().isNotEmpty()) {
                    respText = minText
                }
            }

            val list = mutableListOf<SelectedCourseItem>()
            val jsonArr = if (respText.trimStart().startsWith("[")) {
                JSONArray(respText)
            } else if (respText.trimStart().startsWith("{")) {
                val obj = JSONObject(respText)
                obj.optJSONArray("tmpList")
                    ?: obj.optJSONArray("courses")
                    ?: obj.optJSONArray("items")
                    ?: obj.optJSONArray("data")
                    ?: obj.optJSONArray("rows")
                    ?: JSONArray()
            } else {
                JSONArray()
            }

            for (i in 0 until jsonArr.length()) {
                val it = jsonArr.optJSONObject(i) ?: continue
                val courseId = it.optString("kch", "")
                val courseUniqueId = it.optString("kch_id", courseId)
                val courseName = it.optString("kcmc", "")
                val jxbId = it.optString("jxb_id", "")
                val doJxbId = it.optString("do_jxb_id", jxbId)
                val className = it.optString("jxbmc", "")
                val teacher = it.optString("jsxm", it.optString("jsxx", it.optString("skls", "")))
                val time = it.optString("sksj", "")
                val loc = it.optString("jxdd", "")
                val credit = it.optDouble("xf", 0.0)

                list.add(
                    SelectedCourseItem(
                        courseId = courseId,
                        courseUniqueId = courseUniqueId,
                        courseName = courseName,
                        jxbId = jxbId,
                        doJxbId = doJxbId,
                        className = className,
                        teacherInfo = teacher,
                        scheduleTime = time,
                        location = loc,
                        credit = credit,
                        xkxnm = xkxnm,
                        xkxqm = xkxqm
                    )
                )
            }

            Log.d(TAG, "Queried ${list.size} selected courses")
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "querySelectedCourses failed", e)
            Result.failure(e)
        }
    }

    /**
     * 提交退选
     */
    suspend fun dropCourse(
        item: SelectedCourseItem,
        context: Context? = null
    ): Result<SelectionOperationResult> = withContext(Dispatchers.IO) {
        try {
            val formBuilder = FormBody.Builder()
                .add("kch_id", item.courseUniqueId)
                .add("jxb_ids", item.doJxbId)
                .add("xkxnm", item.xkxnm)
                .add("xkxqm", item.xkxqm)
                .add("txbsfrl", "0")

            val req = Request.Builder()
                .url(DROP_URL)
                .header("User-Agent", USER_AGENT)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Referer", INDEX_URL)
                .post(formBuilder.build())
                .build()

            val resp = JwglClient.client.newCall(req).execute()
            val body = resp.body?.string() ?: ""

            Log.d(TAG, "Drop course response: $body")

            if (body.trim() == "1" || body.contains("\"flag\":\"1\"") || body.trim() == "\"1\"") {
                Result.success(SelectionOperationResult(true, "退选成功", "1"))
            } else if (body.trimStart().startsWith("{")) {
                val json = JSONObject(body)
                val flag = json.optString("flag", "")
                val msg = json.optString("msg", json.optString("message", ""))
                if (flag == "1") {
                    Result.success(SelectionOperationResult(true, "退选成功", flag))
                } else {
                    Result.success(SelectionOperationResult(false, msg.ifEmpty { "退选失败 (代码: $flag)" }, flag))
                }
            } else {
                val doc = Jsoup.parse(body)
                val alert = doc.select(".alert, #msg, p").text().trim()
                Result.success(SelectionOperationResult(false, alert.ifEmpty { "退选未成功，教务系统返回: $body" }))
            }
        } catch (e: Exception) {
            Log.e(TAG, "dropCourse failed", e)
            Result.failure(e)
        }
    }
}
