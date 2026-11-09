package cn.edu.usst.jwgl.data.network

import cn.edu.usst.jwgl.data.model.NumberCodeLookupFailure
import cn.edu.usst.jwgl.data.model.NumberCodeLookupResult
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Only the target-scoped detail object and its named rollcall object are accepted. */
internal object RollcallNumberCodeLookup {
    fun unavailable(reason: NumberCodeLookupFailure, message: String) =
        NumberCodeLookupResult.Unavailable(reason, message)

    fun parse(body: String, target: TronClassRollcall): NumberCodeLookupResult {
        if (target.getEffectiveId() <= 0L || target.courseId <= 0L || target.getNormalizedType() != "number") {
            return unavailable(NumberCodeLookupFailure.ContextMissing, "缺少明确的数字签到活动和课程")
        }
        if (target.isExpired) return unavailable(NumberCodeLookupFailure.NoActiveRollcall, "所选签到活动已结束")
        val root = try { JsonParser.parseString(body) } catch (_: Exception) { null }
        if (root == null || !root.isJsonObject) return unavailable(NumberCodeLookupFailure.SchemaMismatch, "签到详情格式无法识别")
        val obj = root.asJsonObject
        val records = mutableListOf(obj)
        if (obj.has("rollcall") && !obj.get("rollcall").isJsonNull) {
            if (!obj.get("rollcall").isJsonObject) return unavailable(NumberCodeLookupFailure.SchemaMismatch, "签到详情格式无法识别")
            records.add(obj.getAsJsonObject("rollcall"))
        }
        val codes = mutableSetOf<String>()
        for (record in records) {
            val expired = record.get("is_expired")
            if (expired != null && !expired.isJsonNull) {
                if (!expired.isJsonPrimitive || !expired.asJsonPrimitive.isBoolean) {
                    return unavailable(NumberCodeLookupFailure.SchemaMismatch, "签到有效状态格式无法识别")
                }
                if (expired.asBoolean) return unavailable(NumberCodeLookupFailure.NoActiveRollcall, "所选签到活动已结束")
            }
            for (key in listOf("rollcall_id", "rollcallId")) {
                if (record.has(key) && longField(record, key) != target.getEffectiveId()) {
                    return unavailable(NumberCodeLookupFailure.Conflict, "详情活动与所选活动不一致")
                }
            }
            // A detail-level id denotes the rollcall; student row ids are never traversed.
            if (record.has("id") && longField(record, "id") != target.getEffectiveId()) {
                return unavailable(NumberCodeLookupFailure.Conflict, "详情活动与所选活动不一致")
            }
            for (key in listOf("course_id", "courseId")) {
                if (record.has(key) && longField(record, key) != target.courseId) {
                    return unavailable(NumberCodeLookupFailure.Conflict, "详情课程与所选课程不一致")
                }
            }
            val code = record.get("number_code") ?: continue
            if (code.isJsonNull) continue
            // A short numeric value cannot recover leading zeros; only verbatim four-digit values are usable.
            if (!code.isJsonPrimitive || !(code.asJsonPrimitive.isString || code.asJsonPrimitive.isNumber) ||
                !code.asString.matches(Regex("[0-9]{4}"))) {
                return unavailable(NumberCodeLookupFailure.SchemaMismatch, "服务器返回的数字码格式无法确认")
            }
            codes.add(code.asString)
        }
        return when (codes.size) {
            0 -> unavailable(NumberCodeLookupFailure.CodeNotReturned, "服务器未返回本活动数字码，请使用课堂提供的口令")
            1 -> NumberCodeLookupResult.Found(codes.single(), target.getEffectiveId(), target.courseId)
            else -> unavailable(NumberCodeLookupFailure.Conflict, "详情中存在不一致的数字码，已停止取码")
        }
    }

    private fun longField(obj: JsonObject, key: String): Long? = try {
        obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull()
    } catch (_: Exception) { null }
}
