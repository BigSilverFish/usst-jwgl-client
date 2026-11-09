package cn.edu.usst.jwgl.data.network

import org.junit.Assert.*
import org.junit.Test

class SessionValidationWindowTest {
    @Test fun successfulProbeIsBoundToAccountAndCookie() {
        val window = SessionValidationWindow(now = { 10L })
        assertFalse(window.contains("a", "one"))
        window.record("a", "one")
        assertTrue(window.contains("a", "one"))
        assertFalse(window.contains("b", "one"))
        assertFalse(window.contains("a", "two"))
    }

    @Test fun expiredOrClockResetProbeMustBeRepeated() {
        var time = 100L
        val window = SessionValidationWindow(now = { time }, lifetimeMs = 50)
        window.record("a", "one")
        time = 149L
        assertTrue(window.contains("a", "one"))
        time = 150L
        assertFalse(window.contains("a", "one"))
        time = 99L
        assertFalse(window.contains("a", "one"))
    }

    @Test fun clearingSessionRemovesVerification() {
        val window = SessionValidationWindow(now = { 10L })
        window.record("a", "one")
        window.record("a", null)
        assertFalse(window.contains("a", "one"))
    }
}
