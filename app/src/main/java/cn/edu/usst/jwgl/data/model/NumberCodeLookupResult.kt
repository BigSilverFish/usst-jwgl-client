package cn.edu.usst.jwgl.data.model

sealed class NumberCodeLookupResult {
    data class Found(val code: String, val rollcallId: Long, val courseId: Long) : NumberCodeLookupResult()
    data class Unavailable(val reason: NumberCodeLookupFailure, val message: String) : NumberCodeLookupResult()
}

enum class NumberCodeLookupFailure {
    NoActiveRollcall, CodeNotReturned, ContextMissing, Forbidden, SessionExpired,
    NetworkError, SchemaMismatch, Conflict
}
