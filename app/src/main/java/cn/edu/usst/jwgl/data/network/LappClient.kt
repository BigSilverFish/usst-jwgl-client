package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.util.Log
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.model.LappCourseItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

data class LappTerm(
    val schoolYear: String,
    val term: String,
    val beginDate: String,
    val endDate: String,
    val isCurrent: Boolean
) {
    val displayTitle: String
        get() = "$schoolYear 学年第 ${if (term == "1") "一" else "二"} 学期${if (isCurrent) " (当前)" else ""}"
}

data class FreeClassroomInfo(
    val name: String,
    val building: String,
    val isFree: Boolean,
    val occupiedCourse: String = "",
    val occupiedTeacher: String = ""
)

object LappClient {
    private const val TAG = "LappClient"
    private const val BASE_URL = "https://lapp.usst.edu.cn"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val cookieStore = CopyOnWriteArrayList<Cookie>()

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                cookieStore.removeAll { it.name == c.name && (it.domain == c.domain || c.domain.endsWith("usst.edu.cn")) }
                cookieStore.add(c)
                Log.d(TAG, "Saved Lapp cookie: ${c.name}=${c.value} domain=${c.domain}")
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val valid = mutableListOf<Cookie>()
            for (c in cookieStore) {
                if (c.expiresAt < now) {
                    cookieStore.remove(c)
                } else if (c.matches(url) || url.host.endsWith("usst.edu.cn")) {
                    valid.add(c)
                }
            }
            return valid
        }
    }

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * 预置全校主要教学楼已知教室清单 (由全校排课大数据库聚类生成)
     */
    val ALL_KNOWN_BUILDINGS = listOf(
        "全部教学楼",
        "第一教学楼",
        "第三教学楼",
        "第四教学楼",
        "卓越楼",
        "国合楼",
        "综合楼"
    )

    private val KNOWN_CLASSROOMS_BY_BUILDING: Map<String, List<String>> = mapOf(
        "第一教学楼" to listOf(
            "一教100", "一教101", "一教105", "一教109", "一教110", "一教113", "一教116", "一教117", "一教118", "一教119",
            "一教120", "一教121", "一教123", "一教124", "一教126", "一教128", "一教129", "一教130", "一教131", "一教133",
            "一教134", "一教137", "一教138", "一教140", "一教144", "一教146", "一教201", "一教205", "一教209", "一教210",
            "一教213", "一教214", "一教215", "一教216", "一教217", "一教218", "一教219", "一教220", "一教223", "一教225",
            "一教226", "一教229", "一教230", "一教232", "一教234", "一教235", "一教237", "一教238", "一教240", "一教242",
            "一教244", "一教301", "一教305", "一教309", "一教310", "一教313", "一教314", "一教315", "一教317", "一教318",
            "一教319", "一教320", "一教323", "一教325", "一教326", "一教329", "一教330", "一教332", "一教334", "一教335",
            "一教337", "一教338", "一教340", "一教344", "一教401", "一教405", "一教410", "一教413", "一教417", "一教420"
        ),
        "第三教学楼" to listOf(
            "三教101", "三教102", "三教103", "三教105", "三教106", "三教110", "三教113", "三教117", "三教201", "三教202",
            "三教203", "三教205", "三教206", "三教210", "三教213", "三教217", "三教301", "三教302", "三教303", "三教305",
            "三教306", "三教310", "三教313", "三教317", "三教401", "三教402", "三教403", "三教405", "三教406", "三教410",
            "三教413", "三教417", "三教501", "三教502", "三教503", "三教505", "三教506", "三教510", "三教513", "三教601",
            "三教602", "三教605"
        ),
        "第四教学楼" to listOf(
            "四教101", "四教102", "四教103", "四教104", "四教107", "四教115", "四教203", "四教205", "四教207", "四教301",
            "四教303", "四教307"
        ),
        "卓越楼" to listOf(
            "卓越楼118", "卓越楼122", "卓越楼123", "卓越楼127", "卓越楼201", "卓越楼202", "卓越楼203", "卓越楼204", "卓越楼205",
            "卓越楼301", "卓越楼302", "卓越楼303", "卓越楼304", "卓越楼305", "卓越楼401", "卓越楼402", "卓越楼403", "卓越楼404",
            "卓越楼501", "卓越楼502"
        ),
        "国合楼" to listOf(
            "国合楼101", "国合楼102", "国合楼103", "国合楼104", "国合楼105", "国合楼106", "国合楼201", "国合楼202", "国合楼203",
            "国合楼204", "国合楼205", "国合楼301", "国合楼302", "国合楼303", "国合楼304", "国合楼305", "国合楼306", "国合楼401",
            "国合楼402", "国合楼403", "国合楼404", "国合楼405", "国合楼406", "国合楼501", "国合楼502", "国合楼503", "国合楼504"
        ),
        "综合楼" to listOf(
            "综合楼A区101", "综合楼A区102", "综合楼A区201", "综合楼A区202", "综合楼B区101", "综合楼B区104", "综合楼B区105",
            "综合楼B区108", "综合楼B区201", "综合楼B区204", "综合楼B区205", "综合楼B区208", "综合楼B区301", "综合楼B区304",
            "综合楼B区305", "综合楼C区101", "综合楼C区102", "综合楼C区201", "综合楼C区202", "综合楼D区101", "综合楼D区106",
            "综合楼D区201", "综合楼D区206"
        )
    )

    /**
     * 同步 JwglClient 中的已登录 SSO 凭证
     */
    fun syncCookiesFromJwgl(jwglCookies: List<Cookie>) {
        for (c in jwglCookies) {
            cookieStore.removeAll { it.name == c.name && it.domain == c.domain }
            cookieStore.add(c)
        }
    }

    /**
     * 确保具备 lapp.usst.edu.cn 的有效登录会话
     */
    suspend fun ensureSession(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. 优先尝试把 JwglClient 的 cookie 导入过来
            syncCookiesFromJwgl(JwglClient.getCookies())

            // 2. 访问首页以激活 lapp.usst.edu.cn 的 JSESSIONID
            val indexReq = Request.Builder()
                .url("$BASE_URL/_web/_customizes/usst/schoolTimetable/mobile/permit/pc/pub/index.html")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val indexResp = client.newCall(indexReq).execute()
            val finalUrl = indexResp.request.url.toString()
            val html = indexResp.body?.string() ?: ""

            // 如果已经停留在 index.html 且有 JSESSIONID，验证快速接口
            if (finalUrl.contains("schoolTimetable") && hasLappSession()) {
                return@withContext true
            }

            // 3. 如果跳转到 CAS 登录页，则执行 CAS 表单免密自动登录
            if (finalUrl.contains("authserver/login")) {
                val authPrefs = AuthPreferences(context)
                val studentId = authPrefs.getStudentId()
                val password = authPrefs.getPassword()
                if (studentId.isEmpty() || password.isEmpty()) {
                    Log.w(TAG, "No stored credentials for CAS login")
                    return@withContext false
                }

                val doc = Jsoup.parse(html)
                val lt = doc.select("input[name=lt]").`val`()
                val execution = doc.select("input[name=execution]").`val`()
                val eventId = doc.select("input[name=_eventId]").`val`().ifEmpty { "submit" }

                if (lt.isEmpty() || execution.isEmpty()) {
                    Log.e(TAG, "Failed to parse CAS tokens from lapp redirect")
                    return@withContext false
                }

                val formBody = FormBody.Builder()
                    .add("username", studentId.trim())
                    .add("password", password.trim())
                    .add("lt", lt)
                    .add("dllt", "userNamePasswordLogin")
                    .add("execution", execution)
                    .add("_eventId", eventId)
                    .add("rmShown", "1")
                    .build()

                val casReq = Request.Builder()
                    .url(finalUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", finalUrl)
                    .post(formBody)
                    .build()

                val casResp = client.newCall(casReq).execute()
                val postFinalUrl = casResp.request.url.toString()
                casResp.body?.string() // consume

                Log.d(TAG, "CAS login completed, landed on: $postFinalUrl")
                return@withContext hasLappSession() || postFinalUrl.contains("lapp.usst.edu.cn")
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "ensureSession error", e)
            false
        }
    }

    private fun hasLappSession(): Boolean {
        return cookieStore.any { it.name.equals("JSESSIONID", ignoreCase = true) && (it.domain.contains("lapp") || it.domain.endsWith("usst.edu.cn")) }
    }

    /**
     * 查询全校课表开课信息
     */
    suspend fun queryClassSchedule(
        context: Context,
        xn: String = "",
        xq: String = "",
        week: Int = -1,
        xy: String = "",
        zj: Int = -1,
        jc: String = "-1",
        kcmc: String = "",
        kcdm: String = "",
        lsmc: String = "",
        page: Int = 0,
        rows: Int = 50
    ): Result<Pair<Int, List<LappCourseItem>>> = withContext(Dispatchers.IO) {
        try {
            ensureSession(context)

            var urlBuilder = StringBuilder("$BASE_URL/_web/_lightapp/schedule/mobile/school/queryClassSchedule.rst?")
            if (xn.isNotEmpty()) urlBuilder.append("xn=").append(URLEncoder.encode(xn, "UTF-8")).append("&")
            if (xq.isNotEmpty()) urlBuilder.append("xq=").append(URLEncoder.encode(xq, "UTF-8")).append("&")
            urlBuilder.append("week=").append(week).append("&")
            if (xy.isNotEmpty()) urlBuilder.append("xy=").append(URLEncoder.encode(xy, "UTF-8")).append("&")
            urlBuilder.append("zj=").append(zj).append("&")
            urlBuilder.append("jc=").append(URLEncoder.encode(jc, "UTF-8")).append("&")
            if (kcmc.isNotEmpty()) urlBuilder.append("kcmc=").append(URLEncoder.encode(kcmc, "UTF-8")).append("&")
            if (kcdm.isNotEmpty()) urlBuilder.append("kcdm=").append(URLEncoder.encode(kcdm, "UTF-8")).append("&")
            if (lsmc.isNotEmpty()) urlBuilder.append("lsmc=").append(URLEncoder.encode(lsmc, "UTF-8")).append("&")
            urlBuilder.append("page=").append(page).append("&")
            urlBuilder.append("rows=").append(rows)

            val requestUrl = urlBuilder.toString()
            Log.i(TAG, "queryClassSchedule URL: $requestUrl")

            val req = Request.Builder()
                .url(requestUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "$BASE_URL/_web/_customizes/usst/schoolTimetable/mobile/permit/pc/pub/index.html")
                .get()
                .build()

            var resp = client.newCall(req).execute()
            var body = resp.body?.string() ?: ""

            // 如果会话失效，重试一次
            if (body.contains("用户未登录")) {
                Log.w(TAG, "Lapp session expired, re-authenticating...")
                ensureSession(context)
                resp = client.newCall(req).execute()
                body = resp.body?.string() ?: ""
            }

            Log.i(TAG, "queryClassSchedule resp snippet: ${body.take(200)}")

            val json = JSONObject(body)
            if (json.optInt("resultCode", -1) != 0) {
                val err = json.optString("errorMsg", "查询失败")
                return@withContext Result.failure(Exception(err))
            }

            val resultObj = json.optJSONObject("result") ?: JSONObject()
            val total = resultObj.optInt("total", 0)
            val dataArr = resultObj.optJSONArray("data") ?: org.json.JSONArray()
            Log.i(TAG, "queryClassSchedule total: $total, dataArr length: ${dataArr.length()}")

            val list = mutableListOf<LappCourseItem>()
            for (i in 0 until dataArr.length()) {
                val item = dataArr.getJSONObject(i)
                list.add(
                    LappCourseItem(
                        id = item.optString("id"),
                        bjdm = item.optString("bjdm"),
                        bjmc = item.optString("bjmc"),
                        kcdm = item.optString("kcdm"),
                        kcmc = item.optString("kcmc"),
                        kclx = item.optString("kclx"),
                        xf = item.optString("xf"),
                        xs = item.optString("xs"),
                        xy = item.optString("xy"),
                        lsmc = item.optString("lsmc"),
                        lsgh = item.optString("lsgh"),
                        lszc = item.optString("lszc"),
                        zj = item.optInt("zj", 1),
                        jc = item.optInt("jc", 1),
                        jccd = item.optInt("jccd", 2),
                        classTimeBegin = item.optString("classTimeBegin"),
                        classTimeEnd = item.optString("classTimeEnd"),
                        jsdd = item.optString("jsdd"),
                        jsbh = item.optString("jsbh"),
                        qsz = item.optInt("qsz", 1),
                        jsz = item.optInt("jsz", 18),
                        zc = item.optString("zc"),
                        xn = item.optString("xn"),
                        xq = item.optString("xq")
                    )
                )
            }

            Result.success(Pair(total, list))
        } catch (e: Exception) {
            Log.e(TAG, "queryClassSchedule error", e)
            Result.failure(e)
        }
    }

    /**
     * 查询学年学期列表
     */
    suspend fun inquiryTerms(context: Context): Result<List<LappTerm>> = withContext(Dispatchers.IO) {
        try {
            ensureSession(context)
            val url = "$BASE_URL/_web/_lightapp/schedule/mobile/termAndWeek/inquiryTerms.rst?pageIndex=0&rows=100"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            Log.i(TAG, "inquiryTerms raw: ${body.take(300)}")
            val json = JSONObject(body)
            val dataArr = json.optJSONObject("result")?.optJSONArray("data") ?: org.json.JSONArray()

            val list = mutableListOf<LappTerm>()
            for (i in 0 until dataArr.length()) {
                val item = dataArr.getJSONObject(i)
                val term = LappTerm(
                    schoolYear = item.optString("schoolYear"),
                    term = item.optString("term"),
                    beginDate = item.optString("beginDate"),
                    endDate = item.optString("endDate"),
                    isCurrent = item.optString("sfdqxq") == "1"
                )
                list.add(term)
                Log.i(TAG, "LappTerm: year=${term.schoolYear}, term=${term.term}, current=${term.isCurrent}")
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "inquiryTerms error", e)
            Result.failure(e)
        }
    }

    /**
     * 查询开课学院列表
     */
    suspend fun queryColleges(context: Context): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            ensureSession(context)
            val url = "$BASE_URL/_web/_lightapp/schedule/mobile/school/queryClassXy.rst"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            val json = JSONObject(body)
            val dataArr = json.optJSONObject("result")?.optJSONArray("data") ?: org.json.JSONArray()

            val list = mutableListOf<String>()
            for (i in 0 until dataArr.length()) {
                val xy = dataArr.getJSONObject(i).optString("xy").trim()
                if (xy.isNotEmpty() && !list.contains(xy)) {
                    list.add(xy)
                }
            }
            Result.success(list)
        } catch (e: Exception) {
            Log.e(TAG, "queryColleges error", e)
            Result.failure(e)
        }
    }

    /**
     * 空教室计算：通过指定周次、星期、节次，获取被占用的教室，对比教学楼已知教室得出空闲教室
     */
    suspend fun queryFreeClassrooms(
        context: Context,
        xn: String,
        xq: String,
        week: Int,
        zj: Int,
        jc: String,
        building: String
    ): Result<List<FreeClassroomInfo>> = withContext(Dispatchers.IO) {
        try {
            // 拉取指定时段的全部排课（每页 500 条即可覆盖单时段的所有排课）
            val schedResult = queryClassSchedule(
                context = context,
                xn = xn,
                xq = xq,
                week = week,
                zj = zj,
                jc = jc,
                rows = 500
            )

            val occupiedMap = mutableMapOf<String, LappCourseItem>()
            if (schedResult.isSuccess) {
                val (_, courses) = schedResult.getOrNull() ?: Pair(0, emptyList())
                for (c in courses) {
                    if (c.jsdd.isNotEmpty()) {
                        occupiedMap[c.jsdd] = c
                    }
                }
            }

            // 过滤教学楼
            val targetBuildings = if (building == "全部教学楼" || building.isEmpty()) {
                KNOWN_CLASSROOMS_BY_BUILDING.keys
            } else {
                KNOWN_CLASSROOMS_BY_BUILDING.keys.filter { it.contains(building) || building.contains(it) }
            }

            val resultList = mutableListOf<FreeClassroomInfo>()
            for (bName in targetBuildings) {
                val roomList = KNOWN_CLASSROOMS_BY_BUILDING[bName] ?: continue
                for (room in roomList) {
                    val occupied = occupiedMap[room]
                    if (occupied != null) {
                        resultList.add(
                            FreeClassroomInfo(
                                name = room,
                                building = bName,
                                isFree = false,
                                occupiedCourse = occupied.kcmc,
                                occupiedTeacher = occupied.lsmc
                            )
                        )
                    } else {
                        resultList.add(
                            FreeClassroomInfo(
                                name = room,
                                building = bName,
                                isFree = true
                            )
                        )
                    }
                }
            }

            // 排序：空闲教室排在前面
            resultList.sortByDescending { it.isFree }
            Result.success(resultList)
        } catch (e: Exception) {
            Log.e(TAG, "queryFreeClassrooms error", e)
            Result.failure(e)
        }
    }
}
