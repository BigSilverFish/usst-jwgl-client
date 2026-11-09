package cn.edu.usst.jwgl.data.network

import okhttp3.RequestBody
import okio.BufferedSink

/** Also blocks HTTP follow-ups (for example 503 Retry-After: 0), beyond connection retries. */
internal class SingleAttemptRequestBody(private val body: RequestBody) : RequestBody() {
    override fun contentType() = body.contentType()
    override fun contentLength() = body.contentLength()
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
}
