package cn.edu.usst.jwgl.data.network

import cn.edu.usst.jwgl.data.model.RollcallSubmitResult
import com.google.gson.JsonParser

internal data class RollcallWriteResult(val result: RollcallSubmitResult, val state: String)

internal fun parseRollcallWriteResult(httpCode: Int, body: String): RollcallWriteResult {
    val obj = try { JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject } catch (_: Exception) { null }
    fun text(key: String): String? = try { obj?.get(key)?.takeIf { it.isJsonPrimitive }?.asString } catch (_: Exception) { null }
    val distance = text("distance")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    if (httpCode !in 200..299) {
        val errorCode = text("errorCode")
        val rawMessage = text("message")?.takeIf { it.isNotBlank() }
        val message = when {
            errorCode == "ROLLCALL_RADAR_OUT_OF_SCOPE" || rawMessage?.contains("scope", ignoreCase = true) == true ->
                "当前定位超出签到有效范围${distance?.let { " (相距约 ${it.toInt()} 米)" } ?: ""}，请靠近教室或切换为教学楼基准模式"
            errorCode == "RADAR_ROLLCALL_POSITION_INVALID" -> "经纬度坐标为空或无效，请重新定位"
            errorCode == "ROLLCALL_DEVICE_ALREADY_IN_USE" -> "该设备已为其他账号签过到，无法重复签到"
            errorCode == "RADAR_SIGNAL_INVALID_NO_SIGNAL" -> "本堂课开启了蓝牙信标验证，需要教室蓝牙信号支持"
            rawMessage != null -> rawMessage
            httpCode == 404 -> "点名已结束或已关闭"
            else -> "签到未成功 ($httpCode)，请查看官方记录"
        }
        // A server/proxy error can arrive after the attendance write was applied.
        val state = if (httpCode in 400..499 && httpCode != 408) "rejected" else "unknown"
        return RollcallWriteResult(RollcallSubmitResult(false, message, distance = distance), state)
    }
    val status = text("status")?.lowercase()?.trim()
    val explicitFailure = (try { obj?.get("success")?.asBoolean == false } catch (_: Exception) { false }) ||
        !text("errorCode").isNullOrBlank()
    val confirmed = !explicitFailure && status in setOf("on_call", "on_call_fine", "on_call_arrive_late", "on_call_leave_early", "on_call_arrive_late_leave_early", "present", "attended", "late", "leave_early")
    val name = text("status_name") ?: status
    if (confirmed) return RollcallWriteResult(RollcallSubmitResult(true, "已确认签到状态：$name", name, distance), "confirmed")
    val rejected = explicitFailure || status in setOf("absent", "failed", "error", "closed", "expired")
    return if (rejected) {
        RollcallWriteResult(RollcallSubmitResult(false, text("message") ?: "签到未成功，请查看官方记录", name, distance), "rejected")
    } else {
        RollcallWriteResult(RollcallSubmitResult(false, "请求结果尚未确认，请查询官方签到记录；不会再次发送", name, distance), "unknown")
    }
}

internal fun decodeTronClassQrId(raw: String, compact: Boolean): Long? {
    val clean = raw.trim().trimStart('\u0010', '\u001a')
    val radix = if (compact) 36 else 10
    return clean.toLongOrNull(radix)?.takeIf { it > 0L }
}
