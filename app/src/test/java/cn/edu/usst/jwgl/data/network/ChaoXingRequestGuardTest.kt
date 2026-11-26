package cn.edu.usst.jwgl.data.network

import cn.edu.usst.jwgl.ui.tronclass.DualPlatformRiskGate
import cn.edu.usst.jwgl.util.ChaoXingRiskControlException
import cn.edu.usst.jwgl.util.getFriendlyErrorMessage
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChaoXingRequestGuardTest {

    @Before
    fun setUp() {
        ChaoXingRequestGuard.resetPacingForTest()
        ChaoXingRequestGuard.clearStuWorkCache()
    }

    @Test
    fun detectRiskSignal_recognizesHardRestrictionAndRateLimit() {
        val hardBan = ChaoXingRequestGuard.detectRiskSignal(
            statusCode = 200,
            url = "https://mooc1-api.chaoxing.com/work/stu-work",
            bodySnippet = "<html><body>您的账号已被限制访问一周，请稍后再试</body></html>"
        )
        assertNotNull(hardBan)
        assertEquals(ChaoXingRequestGuard.HARD_BAN_COOLDOWN_MS, hardBan!!.cooldownMs)

        val frequent = ChaoXingRequestGuard.detectRiskSignal(
            statusCode = 200,
            url = "https://mobilelearn.chaoxing.com/v2/apis/active/student/activelist",
            bodySnippet = """{"result":0,"msg":"访问频繁，请稍后再试"}"""
        )
        assertNotNull(frequent)
        assertEquals(ChaoXingRequestGuard.RATE_LIMIT_COOLDOWN_MS, frequent!!.cooldownMs)

        val http403 = ChaoXingRequestGuard.detectRiskSignal(
            statusCode = 403,
            url = "https://mooc1.chaoxing.com/mooc2/work/list",
            bodySnippet = "<html>Forbidden</html>"
        )
        assertNotNull(http403)
        assertEquals(ChaoXingRequestGuard.RATE_LIMIT_COOLDOWN_MS, http403!!.cooldownMs)
    }

    @Test
    fun detectRiskSignal_doesNotFalsePositiveOnNormalOrLoginHtml() {
        val normalWorkHtml = ChaoXingRequestGuard.detectRiskSignal(
            statusCode = 200,
            url = "https://mooc1-api.chaoxing.com/work/stu-work",
            bodySnippet = "<ul class='stu-work-list'><li>Web应用开发实验</li></ul>"
        )
        assertNull(normalWorkHtml)

        val loginCaptchaHtml = ChaoXingRequestGuard.detectRiskSignal(
            statusCode = 200,
            url = "https://passport2.chaoxing.com/login?fid=207",
            bodySnippet = "<div class='tip'>请完成滑块验证或安全验证</div>"
        )
        assertNull(loginCaptchaHtml)
    }

    @Test
    fun awaitPacingSlot_enforcesMinimumSpacing() {
        var fakeNow = 1_000L
        val sleeps = mutableListOf<Long>()

        ChaoXingRequestGuard.awaitPacingSlot(
            minIntervalMs = 450L,
            jitterMs = 0L,
            nowMs = { fakeNow },
            sleepFn = { ms ->
                sleeps.add(ms)
                fakeNow += ms
            }
        )
        assertTrue(sleeps.isEmpty())

        fakeNow += 100L
        ChaoXingRequestGuard.awaitPacingSlot(
            minIntervalMs = 450L,
            jitterMs = 0L,
            nowMs = { fakeNow },
            sleepFn = { ms ->
                sleeps.add(ms)
                fakeNow += ms
            }
        )
        assertEquals(listOf(350L), sleeps)
    }

    @Test
    fun stuWorkCache_respectsTtl() {
        ChaoXingRequestGuard.putCachedStuWorkHtml("2335010123", "<html>work</html>", nowMs = 10_000L)

        assertEquals(
            "<html>work</html>",
            ChaoXingRequestGuard.getCachedStuWorkHtml("2335010123", ttlMs = 60_000L, nowMs = 50_000L)
        )
        assertNull(
            ChaoXingRequestGuard.getCachedStuWorkHtml("2335010123", ttlMs = 60_000L, nowMs = 70_000L)
        )
    }

    @Test
    fun applyDefaultHeaders_preservesExplicitMobileUa() {
        val withExplicitUa = Request.Builder()
            .url("https://mobilelearn.chaoxing.com/v2/apis/active/student/activelist")
            .header("User-Agent", ChaoXingRequestGuard.MOBILE_APP_UA)
            .get()
            .build()
        val preserved = ChaoXingRequestGuard.applyDefaultHeaders(withExplicitUa)
        assertEquals(ChaoXingRequestGuard.MOBILE_APP_UA, preserved.header("User-Agent"))
        assertEquals("zh-CN,zh;q=0.9", preserved.header("Accept-Language"))

        val withoutUa = Request.Builder()
            .url("https://mooc1-api.chaoxing.com/work/stu-work")
            .get()
            .build()
        val defaulted = ChaoXingRequestGuard.applyDefaultHeaders(withoutUa)
        assertEquals(ChaoXingRequestGuard.DEFAULT_MOBILE_WEB_UA, defaulted.header("User-Agent"))
    }

    @Test
    fun dualPlatformRiskGate_requiresExactAckText() {
        assertTrue(DualPlatformRiskGate.isAckTextValid("我已知晓"))
        assertTrue(DualPlatformRiskGate.isAckTextValid("  我已知晓  "))
        assertFalse(DualPlatformRiskGate.isAckTextValid(""))
        assertFalse(DualPlatformRiskGate.isAckTextValid(null))
        assertFalse(DualPlatformRiskGate.isAckTextValid("已知晓"))
        assertFalse(DualPlatformRiskGate.isAckTextValid("我已知晓风险"))
    }

    @Test
    fun riskControlException_returnsFriendlyMessage() {
        val ex = ChaoXingRiskControlException(
            cooldownUntilMillis = 123456L,
            message = "超星返回「限制访问」，已暂停学习通请求至 11-25 21:45"
        )
        assertEquals("超星返回「限制访问」，已暂停学习通请求至 11-25 21:45", ex.getFriendlyErrorMessage())
    }
}
