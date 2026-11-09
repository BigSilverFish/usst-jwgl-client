package cn.edu.usst.jwgl.data.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** GET cannot carry a one-shot body; the tag prevents HTTP follow-ups from writing again. */
internal class SingleNetworkAttempt {
    private val sent = AtomicBoolean()
    fun claim() = sent.compareAndSet(false, true)
}

internal object SingleNetworkAttemptInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val attempt = chain.request().tag(SingleNetworkAttempt::class.java)
        if (attempt == null || !attempt.claim()) throw IOException("签到请求已发出，禁止自动重放")
        return chain.proceed(chain.request())
    }
}
