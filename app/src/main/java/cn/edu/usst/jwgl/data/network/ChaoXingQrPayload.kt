package cn.edu.usst.jwgl.data.network

import java.net.URI
import java.net.URLDecoder

internal data class ParsedChaoXingQr(
    val activeId: Long,
    val enc: String,
    val code: String? = null
)

internal object ChaoXingQrPayload {
    fun isChaoXingQrUrl(raw: String): Boolean {
        val uri = try {
            URI(raw.trim())
        } catch (_: Exception) {
            return false
        }
        val host = uri.host.orEmpty().lowercase()
        return uri.scheme?.lowercase() in setOf("https", "http") &&
            (host == "chaoxing.com" || host.endsWith(".chaoxing.com"))
    }

    fun parse(raw: String, expectedActivity: Long? = null): Result<ParsedChaoXingQr> = runCatching {
        if (expectedActivity != null) {
            require(expectedActivity > 0) { "签到活动无效，请重新选择活动" }
        }
        val uri = URI(raw.trim())
        val host = uri.host.orEmpty().lowercase()
        require(uri.scheme?.lowercase() in setOf("https", "http") &&
            (host == "chaoxing.com" || host.endsWith(".chaoxing.com"))) { "请扫描学习通课堂签到二维码" }
        val params = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.map { entry ->
            val parts = entry.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }.groupBy({ it.first }, { it.second })

        val activityIds = listOf("activePrimaryId", "activeId", "id")
            .flatMap { params[it].orEmpty() }
            .mapNotNull { it.toLongOrNull() }
        val rawActivityStrings = listOf("activePrimaryId", "activeId", "id")
            .flatMap { params[it].orEmpty() }

        require(rawActivityStrings.isNotEmpty() && activityIds.size == rawActivityStrings.size) {
            "二维码缺少有效的活动标识，请重新扫码"
        }
        val distinctIds = activityIds.distinct()
        require(distinctIds.size == 1 && distinctIds.single() > 0L) {
            "二维码活动标识冲突或无效，请重新扫码"
        }
        val activeId = distinctIds.single()
        if (expectedActivity != null) {
            require(activeId == expectedActivity) {
                "二维码属于其他活动或缺少活动标识，请重新扫码"
            }
        }

        val encValues = params["enc"].orEmpty().distinct()
        require(encValues.size == 1 && encValues.single().isNotBlank()) {
            "二维码没有有效动态凭证，请刷新后重扫"
        }

        val codeValues = (params["c"].orEmpty() + params["code"].orEmpty())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val dynamicCode = if (codeValues.size == 1) codeValues.single() else null

        ParsedChaoXingQr(
            activeId = activeId,
            enc = encValues.single(),
            code = dynamicCode
        )
    }

    fun readEnc(raw: String, expectedActivity: Long): Result<String> {
        return parse(raw, expectedActivity).map { it.enc }
    }
}
