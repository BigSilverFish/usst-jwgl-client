package cn.edu.usst.jwgl.data.network

import org.junit.Assert.*
import org.junit.Test

class RollcallWriteResultTest {
    @Test fun httpSuccessWithoutAttendanceStatusIsUnknown() {
        for (body in listOf("{}", "", "<html>login</html>", """{"success":true}""", """{"status_name":"已签到"}""")) {
            val parsed = parseRollcallWriteResult(200, body)
            assertFalse(parsed.result.success)
            assertEquals("unknown", parsed.state)
        }
    }

    @Test fun explicitAttendanceStateConfirmsAndExplicitFailureWins() {
        assertTrue(parseRollcallWriteResult(200, """{"status":"on_call","status_name":"已到"}""").result.success)
        assertFalse(parseRollcallWriteResult(200, """{"status":"on_call","success":false}""").result.success)
        assertEquals("rejected", parseRollcallWriteResult(200, """{"status":"absent"}""").state)
        assertEquals("rejected", parseRollcallWriteResult(403, """{"status":"on_call"}""").state)
        assertEquals("rejected", parseRollcallWriteResult(200, """{"status":"on_call","errorCode":"invalid"}""").state)
    }

    @Test fun ambiguousServerAndProxyFailuresDoNotAllowAnotherWrite() {
        for (code in listOf(302, 408, 500, 502, 503)) {
            assertEquals("unknown", parseRollcallWriteResult(code, "{}").state)
        }
        val outOfScope = parseRollcallWriteResult(400, """{"errorCode":"ROLLCALL_RADAR_OUT_OF_SCOPE","distance":125.3}""")
        assertEquals("rejected", outOfScope.state)
        assertEquals(125.3, outOfScope.result.distance!!, 0.001)
        assertTrue(outOfScope.result.message.contains("125 米"))
        assertTrue(parseRollcallWriteResult(200, """{"status":"on_call_leave_early"}""").result.success)
    }

    @Test fun compactIdsUseBase36AndOrdinaryIdsUseDecimal() {
        assertEquals(36L, decodeTronClassQrId("10", compact = true))
        assertEquals(10L, decodeTronClassQrId("10", compact = false))
        assertEquals(36L, decodeTronClassQrId("\u001010", compact = true))
        assertEquals(71L, decodeTronClassQrId("1z", compact = true))
        assertNull(decodeTronClassQrId("1z", compact = false))
        assertNull(decodeTronClassQrId("-1", compact = true))
        assertNull(decodeTronClassQrId("zzzzzzzzzzzzzzzz", compact = true))
    }
}
