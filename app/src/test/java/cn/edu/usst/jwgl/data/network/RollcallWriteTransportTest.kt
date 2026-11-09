package cn.edu.usst.jwgl.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class RollcallWriteTransportTest {
    @Test fun serviceUnavailableWithImmediateRetryHeaderCannotReplayAnAttendanceWrite() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"status":"on_call"}"""))
            server.start()
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false)
                .followRedirects(false).followSslRedirects(false).build()
            val request = Request.Builder().url(server.url("/api/rollcall/1/answer_number_rollcall"))
                .put(SingleAttemptRequestBody("""{"numberCode":"0042"}""".toRequestBody())).build()
            client.newCall(request).execute().use { assertEquals(503, it.code) }
            assertEquals(1, server.requestCount)
        }
    }
}
