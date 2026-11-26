package cn.edu.usst.jwgl.data.network

import android.content.Context
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 畅课（TronClass）HTTP 传输、会话认证头注入与并发 Session 刷新执行器
 */
internal class TronClassSessionExecutor(
    val context: Context,
    val prefs: TronClassPreferences,
    val gson: Gson = Gson()
) {
    var sessionRefresher: (suspend (String) -> Result<Boolean>)? = null

    val authHttpClient: OkHttpClient = TronClassTransport.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val orig = chain.request()
            val reqBuilder = orig.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 16; TronClass/2.16.1)")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
            chain.proceed(reqBuilder.build())
        }
        .build()

    val okHttpClient: OkHttpClient = TronClassTransport.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            val orig = chain.request()
            val reqBuilder = orig.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 16; TronClass/2.16.1)")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept-Language", "zh-CN,zh;q=0.9")

            val sessionId = prefs.getSessionId()
            val host = orig.url.host.lowercase()
            val isHttps = orig.isHttps
            val isTrustedHost = host == "1906.usst.edu.cn" ||
                                host.endsWith(".usst.edu.cn") ||
                                host.endsWith(".tronclass.com.cn") ||
                                host == "tronclass.com.cn"

            if (isHttps && isTrustedHost && !sessionId.isNullOrBlank() && !orig.headers.names().contains("x-session-id")) {
                reqBuilder.header("x-session-id", sessionId)
            }

            chain.proceed(reqBuilder.build())
        }
        .build()

    val submissionClient: OkHttpClient = okHttpClient.newBuilder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun accountKey(): String = AuthPreferences.currentStudentId(context)

    suspend fun executeRequest(request: Request, expectedAccount: String? = null): Response {
        val isRead = request.method == "GET"
        if (!isRead) {
            val path = request.url.encodedPath
            val attendanceWrite = path.startsWith("/api/rollcall/") &&
                (path.endsWith("/answer") || path.contains("/answer_"))
            val transport = if (attendanceWrite) submissionClient else okHttpClient
            return transport.newCall(request).awaitBuffered().response(request)
        }
        val account = expectedAccount ?: accountKey()
        if (account != accountKey()) throw CancellationException("Account changed before loading")
        val originalSession = prefs.getSessionId(account)
        val canRefresh = request.url.host == "1906.usst.edu.cn" &&
            request.url.encodedPath.startsWith("/api/") && request.header("x-session-id") == null
        fun authenticatedRequest(): Request = request.newBuilder().apply {
            if (canRefresh) header("x-session-id", prefs.getSessionId(account).orEmpty())
        }.build()
        val response = readWithSessionRefresh(
            read = { TronClassTransport.read(okHttpClient, authenticatedRequest(), account) },
            refresh = { canRefresh && refreshSessionOnce(account, originalSession).getOrDefault(false) }
        )
        currentCoroutineContext().ensureActive()
        if (account != accountKey()) {
            response.close()
            throw CancellationException("Account changed while loading")
        }
        return response
    }

    suspend fun refreshSessionOnce(account: String, previousSession: String?): Result<Boolean> =
        TronClassTransport.sessionRefreshes.await("$account\n$previousSession") {
            val current = prefs.getSessionId(account)
            if (!current.isNullOrBlank() && current != previousSession) Result.success(true)
            else sessionRefresher?.invoke(account) ?: Result.success(false)
        }
}
