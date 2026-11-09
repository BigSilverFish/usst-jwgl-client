package cn.edu.usst.jwgl.data.network

/** A probe is reusable only for the same account and unchanged session. */
internal class SessionValidationWindow(
    private val now: () -> Long,
    private val lifetimeMs: Long = 15 * 60_000L
) {
    private data class Verified(val token: String, val at: Long)
    private val sessions = mutableMapOf<String, Verified>()

    @Synchronized fun contains(account: String, token: String?): Boolean {
        val verified = sessions[account] ?: return false
        val age = now() - verified.at
        return !token.isNullOrBlank() && token == verified.token && age in 0 until lifetimeMs
    }

    @Synchronized fun record(account: String, token: String?) {
        if (token.isNullOrBlank()) sessions.remove(account)
        else sessions[account] = Verified(token, now())
    }
}
