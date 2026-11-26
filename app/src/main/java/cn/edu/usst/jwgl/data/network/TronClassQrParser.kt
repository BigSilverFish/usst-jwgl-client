package cn.edu.usst.jwgl.data.network

import android.util.Log
import org.json.JSONObject

/**
 * 畅课（TronClass）点名二维码与 Base36 协议解析器
 */
internal object TronClassQrParser {
    private const val TAG = "TronClassQrParser"

    fun parseRollcallQr(rawQr: String): TronClassClient.ParsedQrRollcall? {
        val trimmed = rawQr.trim()
        if (trimmed.isEmpty()) return null

        try {
            fun decodeBase36Number(raw: String, compact: Boolean = false): Long? =
                decodeTronClassQrId(raw, compact)

            var targetPayload = trimmed
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.contains("/j?p=") ||
                trimmed.contains("/scanner-jumper?")
            ) {
                try {
                    val uri = android.net.Uri.parse(trimmed)
                    val pUnderscore = uri.getQueryParameter("_p")
                    if (!pUnderscore.isNullOrBlank()) {
                        targetPayload = pUnderscore
                    } else {
                        val pParam = uri.getQueryParameter("p")
                        if (!pParam.isNullOrBlank()) {
                            targetPayload = pParam
                        } else {
                            fun values(vararg keys: String) = keys.flatMap { uri.getQueryParameters(it) }.distinct()
                            val ids = values("rollcall_id", "rollcallId")
                            val tokens = values("data", "qr_token")
                            val courses = values("course_id", "courseId")
                            if (ids.size != 1 || tokens.size != 1 || courses.size > 1) return null
                            val rId = decodeBase36Number(ids.single()) ?: return null
                            val courseId = courses.singleOrNull()?.let { decodeBase36Number(it) ?: return null }
                            val token = tokens.single().takeIf { it.isNotBlank() } ?: return null
                            return TronClassClient.ParsedQrRollcall(rId, token, courseId)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing URI: $trimmed", e)
                }
            }
            targetPayload = targetPayload.trim().trimStart('\u0010', '\u001a')

            if (targetPayload.startsWith("{") && targetPayload.endsWith("}")) {
                val obj = JSONObject(targetPayload)
                val ids = listOf("rollcallId", "rollcall_id").filter { obj.has(it) }
                    .map { decodeBase36Number(obj.optString(it)) ?: return null }.distinct()
                if (ids.size != 1) return null
                val rId = ids.single()
                val data = obj.optString("data", obj.optString("qr_token", ""))
                if (obj.has("data") && obj.has("qr_token") && obj.optString("data") != obj.optString("qr_token")) return null
                val courses = listOf("courseId", "course_id").filter { obj.has(it) }
                    .map { decodeBase36Number(obj.optString(it)) ?: return null }.distinct()
                if (courses.size > 1) return null

                if (rId > 0 && data.isNotBlank()) {
                    return TronClassClient.ParsedQrRollcall(rId, data, courses.singleOrNull())
                }
            }

            if (targetPayload.contains("!") || targetPayload.contains("~")) {
                val keyMap = mapOf(
                    "0" to "courseId",
                    "1" to "activityId",
                    "2" to "activityType",
                    "3" to "data",
                    "4" to "rollcallId"
                )
                var rollcallId: Long? = null
                var qrData: String? = null
                var courseId: Long? = null
                val seenFields = mutableMapOf<String, String>()

                val parts = targetPayload.split("!")
                for (part in parts) {
                    if (part.contains("~")) {
                        val kv = part.split("~", limit = 2)
                        val k = keyMap[kv[0]] ?: kv[0]
                        val v = kv[1]
                        if (k in setOf("rollcallId", "data", "courseId") && seenFields.put(k, v)?.let { it != v } == true) return null
                        when (k) {
                            "rollcallId" -> rollcallId = decodeBase36Number(v, compact = true) ?: return null
                            "data" -> qrData = v.replace(String(charArrayOf(30.toChar())), "~").replace(String(charArrayOf(31.toChar())), "!")
                            "courseId" -> courseId = decodeBase36Number(v, compact = true) ?: return null
                        }
                    }
                }

                if (rollcallId != null && !qrData.isNullOrBlank()) {
                    Log.i(TAG, "Successfully parsed TronClass QR: rollcallId=$rollcallId, courseId=$courseId, dataLen=${qrData.length}")
                    return TronClassClient.ParsedQrRollcall(rollcallId, qrData, courseId)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse rollcall QR string: $trimmed", e)
        }
        return null
    }
}
