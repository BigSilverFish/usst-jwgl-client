package cn.edu.usst.jwgl.data.network

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class TronClassTransportTest {
    @Test fun cancellationAlsoCancelsAResponseWhoseBodyHasNotArrived() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("delayed").setBodyDelay(2, TimeUnit.SECONDS))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/slow-body")).build())
            val reader = launch(Dispatchers.IO) { call.awaitBuffered() }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(1_000) { reader.cancelAndJoin() }
            assertTrue(call.isCanceled())
        }
    }

    @Test fun sharedReadsHaveIndependentBodiesAndUseOneHttpRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"courses\":[]}").setBodyDelay(200, TimeUnit.MILLISECONDS))
            val request = Request.Builder().url(server.url("/courses")).build()
            val first = async { TronClassTransport.read(OkHttpClient(), request, "a") }
            val second = async { TronClassTransport.read(OkHttpClient(), request, "a") }
            withTimeout(5_000) {
                assertEquals("{\"courses\":[]}", first.await().use { it.body!!.string() })
                assertEquals("{\"courses\":[]}", second.await().use { it.body!!.string() })
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun repeatedUnauthorizedResponseRefreshesOnlyOnce() = runBlocking {
        var reads = 0
        var refreshes = 0
        val request = Request.Builder().url("https://example.test/api").build()
        val response = readWithSessionRefresh(read = {
            reads++
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                .body("".toResponseBody()).build()
        }, refresh = { refreshes++; true })
        response.use { assertEquals(401, it.code) }
        assertEquals(2, reads); assertEquals(1, refreshes)
    }

    @Test fun forbiddenDoesNotRefreshOrReplay() = runBlocking {
        var refreshes = 0
        val request = Request.Builder().url("https://example.test/api").build()
        val response = readWithSessionRefresh(read = {
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                .body("".toResponseBody()).build()
        }, refresh = { refreshes++; true })
        response.close()
        assertEquals(0, refreshes)
    }
}
