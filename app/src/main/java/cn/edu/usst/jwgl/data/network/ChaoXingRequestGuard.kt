package cn.edu.usst.jwgl.data.network

import android.content.Context
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.util.ChaoXingRiskControlException
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * 超星学习通统一请求节流、指纹管理、响应缓存与风控熔断守卫。
 *
 * 目的：
 * 1. 控制对 *.chaoxing.com 的请求节奏，避免并发风暴触发风控；
 * 2. 统一客户端 User-Agent 策略，且不再覆盖接口显式指定的移动端 UA；
 * 3. 在 60 秒窗口内复用全局 stu-work HTML，消除各门课程重复请求；
 * 4. 识别 403/429 或限制访问/频繁操作响应，立即持久化熔断冷却，防止封禁续期。
 */
object ChaoXingRequestGuard {

    const val DEFAULT_MOBILE_WEB_UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    const val MOBILE_APP_UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 com.chaoxing.mobile/ChaoXingStudy_3_6.5.1_android_phone"

    const val DEFAULT_MIN_INTERVAL_MS = 450L
    const val MAX_PACING_JITTER_MS = 150L
    const val STU_WORK_CACHE_TTL_MS = 60_000L
    const val HOME_SYNC_MIN_INTERVAL_MS = 10L * 60 * 1000L
    const val BACKGROUND_SYNC_MIN_INTERVAL_MS = 30L * 60 * 1000L
    const val HARD_BAN_COOLDOWN_MS = 6L * 60 * 60 * 1000L
    const val RATE_LIMIT_COOLDOWN_MS = 30L * 60 * 1000L

    private const val MAX_SNIPPET_BYTES = 8192L

    private val HARD_BAN_KEYWORDS = listOf(
        "限制访问",
        "账号存在异常",
        "存在异常访问",
        "检测到异常",
        "账号已被限制",
        "禁止访问"
    )

    private val SOFT_RATE_LIMIT_KEYWORDS = listOf(
        "访问频繁",
        "操作过于频繁",
        "请求过于频繁",
        "操作太频繁",
        "antispider",
        "滑块验证",
        "安全验证"
    )

    data class RiskSignal(
        val reason: String,
        val cooldownMs: Long
    )

    private data class CachedHtmlEntry(
        val html: String,
        val cachedAtMs: Long
    )

    private val pacingLock = Any()
    @Volatile
    private var lastRequestAtMs: Long = 0L

    private val cooldownLock = Any()
    @Volatile
    private var inMemoryCooldownUntilMs: Long = 0L
    @Volatile
    private var inMemoryCooldownReason: String = ""

    private val stuWorkHtmlCache = ConcurrentHashMap<String, CachedHtmlEntry>()
    private val lastHomeSyncByAccount = ConcurrentHashMap<String, Long>()
    private val lastBackgroundSyncByAccount = ConcurrentHashMap<String, Long>()

    fun isChaoXingHost(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT)
        return h == "chaoxing.com" || h.endsWith(".chaoxing.com")
    }

    /**
     * 仅在请求未显式指定 User-Agent / Accept-Language 时补充默认移动浏览器头，
     * 避免覆盖 mobilelearn 接口所需的客户端指纹。
     */
    fun applyDefaultHeaders(request: Request): Request {
        val hasUa = request.header("User-Agent") != null
        val hasLang = request.header("Accept-Language") != null
        if (hasUa && hasLang) return request
        return request.newBuilder().apply {
            if (!hasUa) header("User-Agent", DEFAULT_MOBILE_WEB_UA)
            if (!hasLang) header("Accept-Language", "zh-CN,zh;q=0.9")
        }.build()
    }

    fun awaitPacingSlot(
        minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
        jitterMs: Long = MAX_PACING_JITTER_MS,
        nowMs: () -> Long = { System.currentTimeMillis() },
        sleepFn: (Long) -> Unit = { Thread.sleep(it) }
    ) {
        if (minIntervalMs <= 0L) return
        val extraJitter = if (jitterMs > 0L) ThreadLocalRandom.current().nextLong(0L, jitterMs + 1L) else 0L
        val requiredInterval = minIntervalMs + extraJitter
        synchronized(pacingLock) {
            val now = nowMs()
            val waitMs = lastRequestAtMs + requiredInterval - now
            if (waitMs > 0L) {
                try {
                    sleepFn(waitMs)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            lastRequestAtMs = nowMs()
        }
    }

    internal fun resetPacingForTest() {
        synchronized(pacingLock) {
            lastRequestAtMs = 0L
        }
    }

    fun detectRiskSignal(statusCode: Int, url: String, bodySnippet: String): RiskSignal? {
        val lowerBody = bodySnippet.lowercase(Locale.ROOT)
        val lowerUrl = url.lowercase(Locale.ROOT)

        for (kw in HARD_BAN_KEYWORDS) {
            if (lowerBody.contains(kw.lowercase(Locale.ROOT))) {
                return RiskSignal(
                    reason = "超星返回「$kw」，已自动熔断保护账号",
                    cooldownMs = HARD_BAN_COOLDOWN_MS
                )
            }
        }

        val isLoginEndpoint = lowerUrl.contains("/login") || lowerUrl.contains("/fanyalogin")
        for (kw in SOFT_RATE_LIMIT_KEYWORDS) {
            if (isLoginEndpoint && (kw == "滑块验证" || kw == "安全验证")) {
                continue
            }
            if (lowerBody.contains(kw.lowercase(Locale.ROOT))) {
                return RiskSignal(
                    reason = "超星返回「$kw」，已暂停同步冷却",
                    cooldownMs = RATE_LIMIT_COOLDOWN_MS
                )
            }
        }

        if (statusCode in setOf(403, 412, 418, 429)) {
            return RiskSignal(
                reason = "超星接口返回风控拦截状态 ($statusCode)",
                cooldownMs = RATE_LIMIT_COOLDOWN_MS
            )
        }

        return null
    }

    fun inspectResponseForRisk(resp: Response): RiskSignal? {
        val url = resp.request.url.toString()
        val host = resp.request.url.host
        if (!isChaoXingHost(host)) return null

        val contentType = resp.body?.contentType()
        val isTextPayload = resp.code >= 400 || (contentType != null && (
            contentType.type.equals("text", ignoreCase = true) ||
                contentType.subtype.contains("json", ignoreCase = true) ||
                contentType.subtype.contains("html", ignoreCase = true) ||
                contentType.subtype.contains("xml", ignoreCase = true)
            ))

        val snippet = if (isTextPayload) {
            try {
                resp.peekBody(MAX_SNIPPET_BYTES).string()
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }

        return detectRiskSignal(resp.code, url, snippet)
    }

    fun isCircuitOpen(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val (until, _) = readCooldownState(context)
        return until > nowMs
    }

    fun checkCircuitOrThrow(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val (until, reason) = readCooldownState(context)
        if (until > nowMs) {
            throw ChaoXingRiskControlException(until, buildCooldownMessage(until, reason))
        }
    }

    fun tripCircuit(
        context: Context,
        signal: RiskSignal,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        val newUntil = nowMs + signal.cooldownMs
        synchronized(cooldownLock) {
            val prefs = ChaoXingPreferences(context.applicationContext)
            val persistedUntil = prefs.getRiskCooldownUntil()
            val existingUntil = maxOf(inMemoryCooldownUntilMs, persistedUntil)
            val effectiveUntil = maxOf(existingUntil, newUntil)
            val effectiveReason = if (newUntil >= existingUntil || inMemoryCooldownReason.isBlank()) {
                signal.reason
            } else {
                inMemoryCooldownReason.ifBlank { prefs.getRiskCooldownReason() }
            }
            inMemoryCooldownUntilMs = effectiveUntil
            inMemoryCooldownReason = effectiveReason
            prefs.saveRiskCooldown(effectiveUntil, effectiveReason)
            return effectiveUntil
        }
    }

    fun clearCooldown(context: Context) {
        synchronized(cooldownLock) {
            inMemoryCooldownUntilMs = 0L
            inMemoryCooldownReason = ""
            try {
                ChaoXingPreferences(context.applicationContext).clearRiskCooldown()
            } catch (_: Exception) {
            }
        }
    }

    private fun readCooldownState(context: Context): Pair<Long, String> {
        synchronized(cooldownLock) {
            val prefs = try {
                ChaoXingPreferences(context.applicationContext)
            } catch (_: Exception) {
                return inMemoryCooldownUntilMs to inMemoryCooldownReason
            }
            val persistedUntil = prefs.getRiskCooldownUntil()
            if (persistedUntil > inMemoryCooldownUntilMs) {
                inMemoryCooldownUntilMs = persistedUntil
                inMemoryCooldownReason = prefs.getRiskCooldownReason()
            }
            return inMemoryCooldownUntilMs to inMemoryCooldownReason
        }
    }

    fun buildCooldownMessage(untilMillis: Long, reason: String): String {
        val timeText = try {
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(untilMillis))
        } catch (_: Exception) {
            "稍后"
        }
        val prefix = reason.ifBlank { "超星学习通触发访问保护" }
        return "$prefix，已暂停学习通请求至 $timeText"
    }

    fun getCachedStuWorkHtml(
        account: String,
        ttlMs: Long = STU_WORK_CACHE_TTL_MS,
        nowMs: Long = System.currentTimeMillis()
    ): String? {
        if (account.isBlank()) return null
        val entry = stuWorkHtmlCache[account] ?: return null
        return if (nowMs - entry.cachedAtMs in 0 until ttlMs) {
            entry.html
        } else {
            stuWorkHtmlCache.remove(account, entry)
            null
        }
    }

    fun putCachedStuWorkHtml(
        account: String,
        html: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (account.isBlank() || html.isBlank()) return
        stuWorkHtmlCache[account] = CachedHtmlEntry(html, nowMs)
    }

    fun clearStuWorkCache(account: String? = null) {
        if (account.isNullOrBlank()) {
            stuWorkHtmlCache.clear()
        } else {
            stuWorkHtmlCache.remove(account)
        }
    }

    fun isHomeSyncFresh(account: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (account.isBlank()) return false
        val last = lastHomeSyncByAccount[account] ?: return false
        return nowMs - last in 0 until HOME_SYNC_MIN_INTERVAL_MS
    }

    fun markHomeSyncNow(account: String, nowMs: Long = System.currentTimeMillis()) {
        if (account.isBlank()) return
        lastHomeSyncByAccount[account] = nowMs
    }

    fun isBackgroundSyncFresh(account: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (account.isBlank()) return false
        val last = lastBackgroundSyncByAccount[account] ?: return false
        return nowMs - last in 0 until BACKGROUND_SYNC_MIN_INTERVAL_MS
    }

    fun markBackgroundSyncNow(account: String, nowMs: Long = System.currentTimeMillis()) {
        if (account.isBlank()) return
        lastBackgroundSyncByAccount[account] = nowMs
    }
}
