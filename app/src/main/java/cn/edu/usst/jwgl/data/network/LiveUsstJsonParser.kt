package cn.edu.usst.jwgl.data.network

import cn.edu.usst.jwgl.data.model.LiveCourseRecord
import cn.edu.usst.jwgl.data.model.LiveDeviceViewDto
import cn.edu.usst.jwgl.data.model.LiveUnionSearchData
import cn.edu.usst.jwgl.data.model.LiveUnionSearchResponse
import cn.edu.usst.jwgl.data.model.LiveVideoInfoData
import cn.edu.usst.jwgl.data.model.LiveVideoInfoResponse
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * 智慧录播系统 (live.usst.edu.cn) 容错 JSON 解析器
 * 兼容服务端返回的格式化时间字符串（如 "2026-11-25 16:15:00"）、时间戳数字/字符串及多种字段形态
 */
object LiveUsstJsonParser {

    private val ASIA_SHANGHAI: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private val DATE_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm:ss.SSS",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSS",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd"
    )

    fun parseFlexibleTimestampMillis(element: JsonElement?): Long? {
        if (element == null || element.isJsonNull || !element.isJsonPrimitive) {
            return null
        }
        val primitive = element.asJsonPrimitive
        if (primitive.isNumber) {
            val value = runCatching { primitive.asLong }.getOrNull() ?: return null
            return normalizeEpochMillis(value)
        }
        if (primitive.isString) {
            return parseFlexibleTimestampMillis(primitive.asString)
        }
        return null
    }

    fun parseFlexibleTimestampMillis(raw: String?): Long? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) {
            return null
        }
        if (trimmed.all { it.isDigit() }) {
            val numeric = trimmed.toLongOrNull() ?: return null
            return normalizeEpochMillis(numeric)
        }
        for (pattern in DATE_PATTERNS) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = ASIA_SHANGHAI
                    isLenient = false
                }.parse(trimmed)?.time
            }.getOrNull()
            if (parsed != null) {
                return parsed
            }
        }
        return null
    }

    fun parseServerDateTimeMillis(body: String): Long? {
        if (body.isBlank()) return null
        val root = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return null
        if (root.isJsonPrimitive) {
            return parseFlexibleTimestampMillis(root)
        }
        if (!root.isJsonObject) {
            return null
        }
        val rootObj = root.asJsonObject
        val dataElem = rootObj.get("data")?.takeIf { !it.isJsonNull }
        val dataObj = dataElem?.takeIf { it.isJsonObject }?.asJsonObject
        val candidates = listOf(
            rootObj.get("timestamp"),
            rootObj.get("serverTime"),
            dataObj?.get("todayBeginTime"),
            dataObj?.get("serverTime"),
            dataObj?.get("timestamp"),
            dataElem?.takeIf { it.isJsonPrimitive }
        )
        for (candidate in candidates) {
            val millis = parseFlexibleTimestampMillis(candidate)
            if (millis != null) {
                return millis
            }
        }
        return null
    }

    fun parseUnionSearchResponse(body: String): LiveUnionSearchResponse {
        val root = runCatching { JsonParser.parseString(body) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: throw IOException("直播列表响应格式异常")

        val status = optFlexibleInt(root, "status", "code") ?: 0
        val dataElem = root.get("data")?.takeIf { !it.isJsonNull }
            ?: root.get("result")?.takeIf { !it.isJsonNull }

        val data = when {
            dataElem == null -> null
            dataElem.isJsonArray -> {
                val records = parseCourseRecords(dataElem.asJsonArray)
                LiveUnionSearchData(
                    records = records,
                    rowCount = records.size,
                    pageCount = 1,
                    todayBeginTime = null
                )
            }
            dataElem.isJsonObject -> {
                val dataObj = dataElem.asJsonObject
                val recordsArr = optJsonArray(dataObj, "records", "list", "rows")
                val records = recordsArr?.let(::parseCourseRecords) ?: emptyList()
                LiveUnionSearchData(
                    records = records,
                    rowCount = optFlexibleInt(dataObj, "rowCount", "total"),
                    pageCount = optFlexibleInt(dataObj, "pageCount", "pages"),
                    todayBeginTime = parseFlexibleTimestampMillis(dataObj.get("todayBeginTime"))
                )
            }
            else -> null
        }

        return LiveUnionSearchResponse(status = status, data = data)
    }

    fun parseVideoInfoResponse(body: String): LiveVideoInfoResponse {
        val root = runCatching { JsonParser.parseString(body) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: throw IOException("直播机位响应格式异常")

        val status = optFlexibleInt(root, "status") ?: 0
        val code = optFlexibleInt(root, "code")
        val message = optFlexibleString(root, "message", "msg")
        val errorMsg = optFlexibleString(root, "errorMsg")
        val dataObj = optJsonObject(root, "data") ?: optJsonObject(root, "result")

        val data = dataObj?.let { obj ->
            val viewsArr = optJsonArray(obj, "courseDeviceViewDtoList")
            val views = viewsArr?.mapNotNull { elem ->
                elem.takeIf { it.isJsonObject }?.asJsonObject?.let { vObj ->
                    LiveDeviceViewDto(
                        deviViewNum = optFlexibleInt(vObj, "deviViewNum") ?: 0,
                        chanNameMainPlayUrl = optFlexibleString(vObj, "chanNameMainPlayUrl"),
                        mainTokenStr = optFlexibleString(vObj, "mainTokenStr"),
                        chanGbidMain = optFlexibleString(vObj, "chanGbidMain"),
                        deviceStatus = optFlexibleInt(vObj, "deviceStatus")
                    )
                }
            } ?: emptyList()

            LiveVideoInfoData(
                teclId = optFlexibleString(obj, "teclId"),
                lvcrLiveStatus = optFlexibleInt(obj, "lvcrLiveStatus"),
                courseDeviceViewDtoList = views,
                userIp = optFlexibleString(obj, "userIp"),
                errorMsg = optFlexibleString(obj, "errorMsg")
            )
        }

        return LiveVideoInfoResponse(
            status = status,
            code = code,
            data = data,
            message = message,
            errorMsg = errorMsg
        )
    }

    private fun parseCourseRecords(array: JsonArray): List<LiveCourseRecord> {
        return array.mapNotNull { elem ->
            elem.takeIf { it.isJsonObject }?.asJsonObject?.let { recObj ->
                LiveCourseRecord(
                    id = optFlexibleLong(recObj, "id", "courseId", "courId", "liveId") ?: 0L,
                    teclId = optFlexibleString(recObj, "teclId"),
                    subjName = optFlexibleString(recObj, "subjName", "courseName", "unionName"),
                    clroName = optFlexibleString(recObj, "clroName", "classroomName"),
                    teclTeacNames = optFlexibleStringList(
                        recObj,
                        "teclTeacNames",
                        "teacNames",
                        "teacherNames"
                    ),
                    courBeginTime = parseFlexibleTimestampMillis(recObj.get("courBeginTime")),
                    courEndTime = parseFlexibleTimestampMillis(recObj.get("courEndTime")),
                    liveStartTime = parseFlexibleTimestampMillis(recObj.get("liveStartTime")),
                    liveEndTime = parseFlexibleTimestampMillis(recObj.get("liveEndTime")),
                    courStatus = optFlexibleInt(recObj, "courStatus")
                )
            }
        }
    }

    private fun normalizeEpochMillis(value: Long): Long? {
        if (value <= 0L) return null
        return if (value in 1_000_000_000L..9_999_999_999L) {
            value * 1000L
        } else {
            value
        }
    }

    private fun optJsonObject(obj: JsonObject, vararg keys: String): JsonObject? {
        for (key in keys) {
            val elem = obj.get(key)
            if (elem != null && elem.isJsonObject) {
                return elem.asJsonObject
            }
        }
        return null
    }

    private fun optJsonArray(obj: JsonObject, vararg keys: String): JsonArray? {
        for (key in keys) {
            val elem = obj.get(key)
            if (elem != null && elem.isJsonArray) {
                return elem.asJsonArray
            }
        }
        return null
    }

    private fun optFlexibleString(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val elem = obj.get(key) ?: continue
            if (elem.isJsonNull || !elem.isJsonPrimitive) continue
            val value = elem.asString.trim()
            if (value.isNotEmpty() && !value.equals("null", ignoreCase = true)) {
                return value
            }
        }
        return null
    }

    private fun optFlexibleInt(obj: JsonObject, vararg keys: String): Int? {
        for (key in keys) {
            val elem = obj.get(key) ?: continue
            if (elem.isJsonNull || !elem.isJsonPrimitive) continue
            val prim = elem.asJsonPrimitive
            when {
                prim.isBoolean -> return if (prim.asBoolean) 1 else 0
                prim.isNumber -> {
                    val v = runCatching { prim.asInt }.getOrNull()
                    if (v != null) return v
                }
                prim.isString -> {
                    val s = prim.asString.trim()
                    if (s.equals("true", ignoreCase = true)) return 1
                    if (s.equals("false", ignoreCase = true)) return 0
                    s.toIntOrNull()?.let { return it }
                }
            }
        }
        return null
    }

    private fun optFlexibleLong(obj: JsonObject, vararg keys: String): Long? {
        for (key in keys) {
            val elem = obj.get(key) ?: continue
            if (elem.isJsonNull || !elem.isJsonPrimitive) continue
            val prim = elem.asJsonPrimitive
            when {
                prim.isNumber -> {
                    val v = runCatching { prim.asLong }.getOrNull()
                    if (v != null) return v
                }
                prim.isString -> {
                    prim.asString.trim().toLongOrNull()?.let { return it }
                }
            }
        }
        return null
    }

    private fun optFlexibleStringList(obj: JsonObject, vararg keys: String): List<String>? {
        for (key in keys) {
            val elem = obj.get(key) ?: continue
            if (elem.isJsonNull) continue
            if (elem.isJsonArray) {
                return elem.asJsonArray.mapNotNull { item ->
                    item.takeIf { it.isJsonPrimitive }
                        ?.asString
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                }
            }
            if (elem.isJsonPrimitive) {
                val raw = elem.asString.trim()
                if (raw.isNotEmpty() && !raw.equals("null", ignoreCase = true)) {
                    return raw.split(Regex("[,，、;；]"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                }
            }
        }
        return null
    }
}
