package cn.edu.usst.jwgl.data.local

import android.content.Context

/** A durable claim precedes the write. Unknown results must never enable a second write. */
class RollcallSubmissionStore(context: Context, private val platform: String = "tronclass") {
    init { require(platform in setOf("tronclass", "chaoxing")) }
    private val prefs = context.applicationContext.getSharedPreferences("rollcall_submissions", Context.MODE_PRIVATE)

    fun claim(account: String, rollcallId: Long, allowRejectedRetry: Boolean = false): Boolean = synchronized(lock) {
        if (account.isBlank() || rollcallId <= 0) return false
        val key = key(account, rollcallId)
        if (prefs.contains(key) && !(allowRejectedRetry && prefs.getString(key, null) == "rejected")) return false
        prefs.edit().putString(key, "unknown").commit()
    }

    fun record(account: String, rollcallId: Long, state: String) = synchronized(lock) {
        require(state in setOf("confirmed", "rejected", "unknown"))
        if (prefs.getString(key(account, rollcallId), null) == "confirmed" && state != "confirmed") return@synchronized true
        prefs.edit().putString(key(account, rollcallId), state).commit()
    }

    fun state(account: String, rollcallId: Long): String? = synchronized(lock) {
        prefs.getString(key(account, rollcallId), null)
    }

    private fun key(account: String, rollcallId: Long) = "$platform|${account.length}:$account|$rollcallId"
    companion object { private val lock = Any() }
}
