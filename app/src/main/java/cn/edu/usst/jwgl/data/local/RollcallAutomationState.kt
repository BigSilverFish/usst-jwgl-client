package cn.edu.usst.jwgl.data.local

import android.content.Context

/** Notification delivery and an attempted write are separate from confirmed attendance. */
class RollcallAutomationState(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("rollcall_automation_state", Context.MODE_PRIVATE)

    fun claim(account: String, rollcallId: Long, action: String): Boolean = synchronized(lock) {
        if (account.isBlank() || rollcallId <= 0L) return false
        val key = "$account|$rollcallId|$action"
        if (prefs.contains(key)) return false
        val now = System.currentTimeMillis()
        val editor = prefs.edit()
        // Old, finished classroom events need no permanent notification history.
        prefs.all.filterValues { it is Long && now - it > 30L * 24 * 60 * 60 * 1000 }
            .keys.forEach { editor.remove(it) }
        editor.putLong(key, now).commit()
    }

    companion object { private val lock = Any() }
}
