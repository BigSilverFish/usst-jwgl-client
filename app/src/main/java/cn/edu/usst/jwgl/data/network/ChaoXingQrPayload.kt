package cn.edu.usst.jwgl.data.network

import java.net.URI
import java.net.URLDecoder

internal object ChaoXingQrPayload {
    fun readEnc(raw: String, expectedActivity: Long): Result<String> = runCatching {
        require(expectedActivity > 0) { "签到活动无效，请重新选择活动" }
        val uri = URI(raw.trim())
        val host = uri.host.orEmpty().lowercase()
        require(uri.scheme in setOf("https", "http") &&
            (host == "chaoxing.com" || host.endsWith(".chaoxing.com"))) { "请扫描学习通课堂签到二维码" }
        val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.map { entry ->
            val parts = entry.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }.groupBy({ it.first }, { it.second })
        val activityValues = listOf("activePrimaryId", "activeId", "id").flatMap { params[it].orEmpty() }
        require(activityValues.isNotEmpty() && activityValues.all { it.toLongOrNull() == expectedActivity }) {
            "二维码属于其他活动或缺少活动标识，请重新扫码"
        }
        val codes = params["enc"].orEmpty().distinct()
        require(codes.size == 1 && codes.single().isNotBlank()) { "二维码没有有效动态凭证，请刷新后重扫" }
        codes.single()
    }
}
