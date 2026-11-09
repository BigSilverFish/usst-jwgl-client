package cn.edu.usst.jwgl.data.network

import cn.edu.usst.jwgl.data.model.ChaoXingSignResult

internal data class ChaoXingSubmissionResult(val result: ChaoXingSignResult, val state: String)

internal fun parseChaoXingSubmission(httpCode: Int, body: String, activeId: Long, signType: String): ChaoXingSubmissionResult {
    val text = body.trim()
    val successfulTexts = setOf("success", "签到成功", "您已签到过了", "您已完成过该签到")
    val confirmed = httpCode in 200..299 && text in successfulTexts
    val state = when {
        confirmed -> "confirmed"
        httpCode in 400..499 && httpCode != 408 -> "rejected"
        else -> "unknown"
    }
    val message = when {
        confirmed && text == "success" -> "超星签到成功"
        confirmed -> text
        text.isNotBlank() && text.length <= 200 && text !in successfulTexts &&
            !text.contains('<') && !text.contains('>') && !text.startsWith("{") && !text.startsWith("[") ->
            "$text；请查看学习通官方记录，不会再次发送"
        state == "rejected" -> "签到未成功 ($httpCode)，请查看学习通官方记录；不会再次发送"
        else -> "签到结果尚未确认，请查看学习通官方记录；不会再次发送"
    }
    return ChaoXingSubmissionResult(ChaoXingSignResult(confirmed, message, activeId, signType), state)
}
