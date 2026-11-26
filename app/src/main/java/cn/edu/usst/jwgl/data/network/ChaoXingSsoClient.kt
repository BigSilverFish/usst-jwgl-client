package cn.edu.usst.jwgl.data.network

import android.content.Context
import android.util.Log
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.model.ChaoXingLoginResponse
import cn.edu.usst.jwgl.util.ChaoXingRiskControlException
import cn.edu.usst.jwgl.util.CookieSyncHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 超星学习通 (ChaoXing) 基于上海理工大学统一身份认证 (CAS SSO) 的单点登录客户端
 *
 * 原理参考 extrisha/litang:
 * 1. 优先使用教务系统的 CASTGC 会话或保存的学校统一身份认证账号密码，通过 ids6 CAS 服务静默换取超星会话；
 * 2. 入口端点:
 *    - 快速路径: https://ids6.usst.edu.cn/authserver/login?service=https%3A%2F%2Ftyrzfw.chaoxing.com%2Fauth3%2Fusst%2Fcas%2Findex
 *    - 标准入口: https://tyrzfw.chaoxing.com/auth3/usst/cas/init?isLogout=0&refer=https%3A%2F%2Fi.chaoxing.com&casinfo=&casenc=&casfrom=login
 * 3. 自动跟踪 HTTP 30x 重定向与超星 JS window.location 跳转（还原 \u0026 与 \/ 转义）；
 * 4. 捕获并存储 .chaoxing.com 的 _uid, _d, vc3, fid 等全部凭据，并在后台完成探活与课程同步，无需用户为超星输入账号密码。
 */
object ChaoXingSsoClient {
    private const val TAG = "ChaoXingSsoClient"
    private const val USER_AGENT = ChaoXingRequestGuard.DEFAULT_MOBILE_WEB_UA

    const val DIRECT_CAS_URL = "https://ids6.usst.edu.cn/authserver/login?service=https%3A%2F%2Ftyrzfw.chaoxing.com%2Fauth3%2Fusst%2Fcas%2Findex"
    const val SSO_ENTRY_URL = "https://tyrzfw.chaoxing.com/auth3/usst/cas/init?isLogout=0&refer=https%3A%2F%2Fi.chaoxing.com&casinfo=&casenc=&casfrom=login"

    // OkHttpClient 手动接管重定向以支持超星 JS 字符串跳转与域隔离 Cookie
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 执行 CAS SSO 流程换取超星 Cookie
     *
     * @param context Android 上下文
     * @param forceEntry 是否强制从 tyrzfw 入口发起（默认先试 direct ids6）
     */
    suspend fun loginWithCas(
        context: Context,
        forceEntry: Boolean = false,
        targetStudentId: String? = null
    ): Result<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            ChaoXingRequestGuard.checkCircuitOrThrow(context)
        } catch (e: ChaoXingRiskControlException) {
            return@withContext Result.failure(e)
        }
        val authPrefs = AuthPreferences(context)
        val studentId = targetStudentId?.trim()?.takeIf { it.isNotEmpty() } ?: authPrefs.getStudentId().trim()
        if (studentId.isBlank()) {
            return@withContext Result.failure(IOException("无法确认目标学生学号"))
        }
        val vaultPwd = try {
            cn.edu.usst.jwgl.data.local.AccountVault.getInstance(context).getAccountPassword(studentId)
        } catch (_: Exception) {
            ""
        }
        val password = vaultPwd.ifBlank {
            if (studentId == authPrefs.getStudentId().trim()) authPrefs.getPassword() else ""
        }

        // 1. 仅获取明确归属当前目标学号的 CAS Cookies，严禁跨账号复用 CASTGC
        val jwglCookies = JwglClient.getCookiesForStudent(studentId)
        val casCookieMap = LinkedHashMap<String, String>()
        for (c in jwglCookies) {
            if (c.domain.contains("usst.edu.cn") || c.name.equals("CASTGC", ignoreCase = true)) {
                casCookieMap[c.name] = c.value
            }
        }

        // 2. 准备超星 Cookie 存储
        val chaoxingCookieMap = LinkedHashMap<String, String>()

        // 3. 确定起始 URL
        val starts = mutableListOf<String>()
        if (!forceEntry && casCookieMap.containsKey("CASTGC")) {
            starts.add(DIRECT_CAS_URL)
        }
        starts.add(SSO_ENTRY_URL)
        if (!starts.contains(DIRECT_CAS_URL)) {
            starts.add(DIRECT_CAS_URL)
        }

        var lastException: Exception? = null

        for (startUrl in starts) {
            try {
                Log.d(TAG, "Starting ChaoXing SSO follow chain from: $startUrl (has CASTGC: ${casCookieMap.containsKey("CASTGC")})")
                val success = followChain(
                    context = context,
                    startUrl = startUrl,
                    studentId = studentId,
                    password = password,
                    casCookies = casCookieMap,
                    chaoxingCookies = chaoxingCookieMap
                )

                if (success && chaoxingCookieMap.isNotEmpty()) {
                    val cookieStr = chaoxingCookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    var uid = chaoxingCookieMap["_uid"] ?: chaoxingCookieMap["UID"] ?: ""
                    val prefs = ChaoXingPreferences(context)
                    val conflictOwner = prefs.findConflictingStudentForUid(studentId, uid)
                    if (conflictOwner != null) {
                        Log.w(TAG, "ChaoXing UID $uid already belongs to $conflictOwner, rejecting save for $studentId")
                        return@withContext Result.failure(IOException("超星返回会话归属异常（UID 与账号 $conflictOwner 冲突），已拦截保存"))
                    }

                    // 实例化超星客户端并执行有效性校验
                    val cxClient = ChaoXingClient(context)
                    cxClient.injectCookies(cookieStr, studentId)

                    // 校验会话
                    val isValid = cxClient.checkSessionValid(studentId)
                    if (isValid) {
                        if (uid.isEmpty()) {
                            uid = prefs.getUid(studentId) ?: ""
                        }
                        val postValidConflict = prefs.findConflictingStudentForUid(studentId, uid)
                        if (postValidConflict != null) {
                            prefs.clearSession(studentId)
                            return@withContext Result.failure(IOException("超星会话归属冲突 ($postValidConflict)，已清理"))
                        }
                        prefs.saveLoginSession(
                            cookies = cookieStr,
                            uid = uid,
                            studentNo = studentId,
                            userName = prefs.getUserName(studentId).ifEmpty { studentId },
                            schoolId = "207",
                            schoolName = "上海理工大学"
                        )
                        // 同步到 WebView CookieManager
                        CookieSyncHelper.syncChaoXingCookiesToWebView(context, studentId)
                        Log.d(TAG, "ChaoXing SSO successful! UID=$uid, cookies count=${chaoxingCookieMap.size}")
                        return@withContext Result.success(chaoxingCookieMap)
                    } else {
                        prefs.clearSession(studentId)
                        Log.w(TAG, "ChaoXing SSO chain completed but checkSessionValid returned false on $startUrl")
                    }
                }
            } catch (e: ChaoXingRiskControlException) {
                Log.w(TAG, "ChaoXing SSO halted by risk control: ${e.message}")
                return@withContext Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "ChaoXing SSO attempt error on $startUrl", e)
                lastException = e
            }
        }

        Result.failure(lastException ?: IOException("超星 CAS 单点登录未获取到有效凭据"))
    }

    private fun followChain(
        context: Context,
        startUrl: String,
        studentId: String,
        password: String,
        casCookies: MutableMap<String, String>,
        chaoxingCookies: MutableMap<String, String>
    ): Boolean {
        var currentUrl = startUrl
        var method = "GET"
        var formBody: FormBody? = null
        var referer: String? = null

        for (hop in 0 until 15) {
            val httpUrl = currentUrl.toHttpUrlOrNull() ?: break
            val host = httpUrl.host.lowercase()

            // 构造针对当前域名的 Cookie 报头
            val reqCookieBuilder = StringBuilder()
            if (host.contains("usst.edu.cn")) {
                for ((k, v) in casCookies) {
                    if (reqCookieBuilder.isNotEmpty()) reqCookieBuilder.append("; ")
                    reqCookieBuilder.append("$k=$v")
                }
            }
            if (host.contains("chaoxing.com")) {
                for ((k, v) in chaoxingCookies) {
                    if (reqCookieBuilder.isNotEmpty()) reqCookieBuilder.append("; ")
                    reqCookieBuilder.append("$k=$v")
                }
            }

            val reqBuilder = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")

            if (reqCookieBuilder.isNotEmpty()) {
                reqBuilder.header("Cookie", reqCookieBuilder.toString())
            }
            referer?.let { reqBuilder.header("Referer", it) }

            if (method == "POST" && formBody != null) {
                reqBuilder.post(formBody)
            } else {
                reqBuilder.get()
            }

            val isCxHost = ChaoXingRequestGuard.isChaoXingHost(host)
            if (isCxHost) {
                ChaoXingRequestGuard.checkCircuitOrThrow(context)
                ChaoXingRequestGuard.awaitPacingSlot()
            }

            val resp = client.newCall(reqBuilder.build()).execute()
            val code = resp.code
            val setCookies = resp.headers("Set-Cookie")
            val bodyStr = resp.body?.string() ?: ""

            if (isCxHost) {
                val riskSignal = ChaoXingRequestGuard.detectRiskSignal(code, currentUrl, bodyStr.take(8192))
                if (riskSignal != null) {
                    val until = ChaoXingRequestGuard.tripCircuit(context, riskSignal)
                    throw ChaoXingRiskControlException(
                        until,
                        ChaoXingRequestGuard.buildCooldownMessage(until, riskSignal.reason)
                    )
                }
            }

            // 吸收当前跳的 Set-Cookie
            for (header in setCookies) {
                absorbCookie(host, header, casCookies, chaoxingCookies)
            }

            Log.d(TAG, "Hop $hop: [$code] $method $currentUrl -> Cookies: cas=${casCookies.size}, cx=${chaoxingCookies.size}")

            // 1) HTTP 30x 重定向
            if (code in 300..399) {
                val location = resp.header("Location")
                if (!location.isNullOrEmpty()) {
                    referer = currentUrl
                    currentUrl = resolveUrl(currentUrl, location)
                    if (code == 303 || (code == 302 && method == "POST")) {
                        method = "GET"
                        formBody = null
                    }
                    continue
                }
            }

            // 2) 超星特色的 JS 跳转 (<script>window.location = "https:\/\/...";</script>)
            val jsTarget = parseJsRedirect(bodyStr)
            if (!jsTarget.isNullOrEmpty()) {
                Log.d(TAG, "Hop $hop: Found JS redirect target: $jsTarget")
                referer = currentUrl
                currentUrl = resolveUrl(currentUrl, jsTarget)
                method = "GET"
                formBody = null
                continue
            }

            // 3) 若落在了 CAS 统一身份认证登录页，且已提供账号密码，自动填充表单进行认证
            if (host.contains("ids6") || bodyStr.contains("authserver/login")) {
                if (studentId.isNotBlank() && password.isNotBlank() && method == "GET") {
                    Log.d(TAG, "Hop $hop: Landed on CAS authserver form, auto-submitting credentials...")
                    val doc = Jsoup.parse(bodyStr)
                    val form = doc.select("form").first()
                    val formAction = form?.attr("action") ?: ""
                    val lt = doc.select("input[name=lt]").`val`()
                    val execution = doc.select("input[name=execution]").`val`()
                    val eventId = doc.select("input[name=_eventId]").`val`().ifEmpty { "submit" }

                    if (lt.isNotEmpty() && execution.isNotEmpty()) {
                        val postUrl = resolveUrl(currentUrl, formAction.ifEmpty { currentUrl })
                        referer = currentUrl
                        currentUrl = postUrl
                        method = "POST"
                        formBody = FormBody.Builder()
                            .add("username", studentId.trim())
                            .add("password", password.trim())
                            .add("lt", lt)
                            .add("dllt", "userNamePasswordLogin")
                            .add("execution", execution)
                            .add("_eventId", eventId)
                            .add("rmShown", "1")
                            .build()
                        continue
                    } else {
                        Log.w(TAG, "CAS form tokens not found (lt or execution empty)")
                    }
                } else {
                    Log.w(TAG, "Landed on CAS authserver but no credentials available")
                    return false
                }
            }

            // 4) 若到达了超星业务页面（chaoxing.com，非 cas 或 login 路径），代表认证流程已成功结束
            if (host.endsWith("chaoxing.com") && !httpUrl.encodedPath.contains("/cas/") && !httpUrl.encodedPath.contains("/login")) {
                Log.d(TAG, "Hop $hop: Landed on authenticated ChaoXing page: $currentUrl")
                return true
            }

            // 5) 若已捕获关键登录凭据 (_uid / UID / vc3)，亦可视为换票成功
            if (chaoxingCookies.containsKey("_uid") || chaoxingCookies.containsKey("UID") || chaoxingCookies.containsKey("vc3")) {
                Log.d(TAG, "Hop $hop: Captured ChaoXing auth cookies: ${chaoxingCookies.keys}")
                return true
            }

            break
        }

        return chaoxingCookies.containsKey("_uid") || chaoxingCookies.containsKey("UID") || chaoxingCookies.containsKey("vc3")
    }

    private fun absorbCookie(
        host: String,
        cookieHeader: String,
        casCookies: MutableMap<String, String>,
        chaoxingCookies: MutableMap<String, String>
    ) {
        val part = cookieHeader.split(";")[0].trim()
        val eqIdx = part.indexOf('=')
        if (eqIdx <= 0) return
        val name = part.substring(0, eqIdx).trim()
        val value = part.substring(eqIdx + 1).trim()

        val domainMatch = Regex("""(?i)Domain=([^;]+)""").find(cookieHeader)
        val domain = domainMatch?.groupValues?.getOrNull(1)?.trim()?.lowercase() ?: host.lowercase()

        if (domain.contains("usst.edu.cn")) {
            casCookies[name] = value
        }
        if (domain.contains("chaoxing.com")) {
            chaoxingCookies[name] = value
        }
    }

    private fun resolveUrl(base: String, relative: String): String {
        val baseHttp = base.toHttpUrlOrNull() ?: return relative
        return baseHttp.resolve(relative)?.toString() ?: relative
    }

    /**
     * 解析超星 HTML 中的 window.location 跳转并还原字符转义
     */
    fun parseJsRedirect(html: String): String? {
        if (html.isEmpty()) return null
        val regex = Regex("""window\.location(?:\.href)?\s*=\s*["']([^"']+)["']""")
        val match = regex.find(html) ?: return null
        val raw = match.groupValues.getOrNull(1) ?: return null
        return unescapeJs(raw)
    }

    /**
     * 还原 JS 字符串中的转义（如 \/ 还原为 /，\u0026 还原为 &）
     */
    fun unescapeJs(raw: String): String {
        val slashes = raw.replace("""\/""", "/")
        val unicodeRegex = Regex("""\\u([0-9a-fA-F]{4})""")
        return unicodeRegex.replace(slashes) { matchResult ->
            val hex = matchResult.groupValues[1]
            try {
                hex.toInt(16).toChar().toString()
            } catch (_: Exception) {
                matchResult.value
            }
        }
    }
}
