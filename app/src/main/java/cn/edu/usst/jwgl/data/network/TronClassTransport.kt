package cn.edu.usst.jwgl.data.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object TronClassTransport {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reads = SharedRequest<BufferedResponse>(scope)
    val sessionRefreshes = SharedRequest<Result<Boolean>>(scope)

    suspend fun read(client: OkHttpClient, request: Request, account: String): Response {
        require(request.method == "GET")
        val key = "$account\n${request.url}\n${request.headers}"
        return reads.await(key) { client.newCall(request).awaitBuffered() }.response(request)
    }
}

internal data class BufferedResponse(
    val code: Int, val message: String, val protocol: Protocol,
    val headers: Headers, val bytes: ByteArray
) {
    fun response(request: Request): Response = Response.Builder()
        .request(request).code(code).message(message).protocol(protocol).headers(headers)
        .body(bytes.toResponseBody(headers["Content-Type"]?.toMediaTypeOrNull())).build()
}

internal suspend fun readWithSessionRefresh(read: suspend () -> Response, refresh: suspend () -> Boolean): Response {
    val response = read()
    if (response.code != 401) return response
    val refreshed = try { refresh() } catch (e: Throwable) {
        response.close()
        throw e
    }
    if (!refreshed) return response
    response.close()
    return read()
}

/** Cancellation covers receiving the body too, not just waiting for response headers. */
internal suspend fun Call.awaitBuffered(): BufferedResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            try {
                val value = response.use {
                    BufferedResponse(it.code, it.message, it.protocol, it.headers, it.body?.bytes() ?: byteArrayOf())
                }
                if (continuation.isActive) continuation.resume(value)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}
