package cn.edu.usst.jwgl.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SingleNetworkAttemptTest {
    @Test fun aStateChangingGetCannotBeSentAgainAfter503RetryAfterZero() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("unknown"))
            server.enqueue(MockResponse().setBody("success"))
            server.start()
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
                .followSslRedirects(false).addNetworkInterceptor(SingleNetworkAttemptInterceptor).build()
            val request = Request.Builder().url(server.url("/pptSign/stuSignajax?activeId=1"))
                .tag(SingleNetworkAttempt::class.java, SingleNetworkAttempt()).get().build()
            try {
                client.newCall(request).execute().close()
                fail("The automatic HTTP follow-up must be stopped")
            } catch (_: IOException) {
                assertEquals(1, server.requestCount)
                assertEquals("GET", server.takeRequest().method)
            }
        }
    }

    @Test fun onlyExactSuccessfulResponsesConfirmAttendance() {
        assertTrue(parseChaoXingSubmission(200, "success", 1, "二维码签到").result.isSuccess)
        assertTrue(parseChaoXingSubmission(200, "您已签到过了", 1, "二维码签到").result.isSuccess)
        for (body in listOf("unsuccessful", "签到不成功", "<html>success</html>", "", "{}")) {
            val parsed = parseChaoXingSubmission(200, body, 1, "二维码签到")
            assertFalse(parsed.result.isSuccess)
            assertEquals("unknown", parsed.state)
        }
        assertFalse(parseChaoXingSubmission(500, "success", 1, "二维码签到").result.isSuccess)
        assertEquals("unknown", parseChaoXingSubmission(408, "success", 1, "二维码签到").state)
    }
}
