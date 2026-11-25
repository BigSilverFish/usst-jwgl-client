package cn.edu.usst.jwgl.data.network

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveUsstTokenIsolationTest {

    @After
    fun tearDown() {
        LiveUsstClient.setCachedTokenForTest(null, null, 0L)
    }

    @Test
    fun isCachedTokenValidFor_rejectsTokenWhenAccountChanges() {
        val future = 2_000_000_000_000L
        LiveUsstClient.setCachedTokenForTest("jwt-account-a", "22010101", future)

        assertTrue(LiveUsstClient.isCachedTokenValidFor("22010101", nowMillis = 1_900_000_000_000L))
        assertFalse(LiveUsstClient.isCachedTokenValidFor("22010202", nowMillis = 1_900_000_000_000L))
        assertFalse(LiveUsstClient.isCachedTokenValidFor("22010101", nowMillis = future))
    }

    @Test
    fun isCachedTokenValidFor_rejectsLegacyUnboundTokenWhenStudentIdKnown() {
        val future = 2_000_000_000_000L
        LiveUsstClient.setCachedTokenForTest("jwt-legacy", null, future)

        assertFalse(LiveUsstClient.isCachedTokenValidFor("22010202", nowMillis = 1_900_000_000_000L))
    }

    @Test
    fun clearSession_invalidatesCachedToken() {
        val future = 2_000_000_000_000L
        LiveUsstClient.setCachedTokenForTest("jwt-account-a", "22010101", future)
        LiveUsstClient.clearSession(null)

        assertFalse(LiveUsstClient.isCachedTokenValidFor("22010101", nowMillis = 1_900_000_000_000L))
        assertFalse(LiveUsstClient.isCachedTokenValidFor("", nowMillis = 1_900_000_000_000L))
    }
}
