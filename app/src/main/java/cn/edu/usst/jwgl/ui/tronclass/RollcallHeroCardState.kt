package cn.edu.usst.jwgl.ui.tronclass

import cn.edu.usst.jwgl.data.model.UnifiedActiveRollcall

internal enum class RollcallHeroCardState {
    NO_ROLLCALL,      // Red (default)
    ACTIVE_UNSIGNED,  // Blue (ongoing rollcall, current user has NOT signed in)
    ACTIVE_SIGNED     // Green (ongoing rollcall, current user HAS signed in)
}

internal fun isAttendedRollcallStatus(rawStatus: String?): Boolean {
    val normalized = rawStatus?.lowercase()?.trim() ?: return false
    return normalized in setOf(
        "on_call",
        "on_call_fine",
        "on_call_arrive_late",
        "on_call_leave_early",
        "on_call_arrive_late_leave_early",
        "present",
        "attended",
        "late",
        "leave_early"
    )
}

internal fun resolveHeroCardState(
    active: UnifiedActiveRollcall?,
    currentStudentId: String,
    localConfirmedState: String?,
    inMemorySignedKeys: Set<String>,
    latestStatsMyStatus: String? = null
): RollcallHeroCardState {
    if (active == null) return RollcallHeroCardState.NO_ROLLCALL
    if (!latestStatsMyStatus.isNullOrBlank() && !isAttendedRollcallStatus(latestStatsMyStatus)) {
        return RollcallHeroCardState.ACTIVE_UNSIGNED
    }
    val normalizedId = currentStudentId.trim()
    val inMemoryHit = normalizedId.isNotBlank() && "${normalizedId}|${active.effectiveId}" in inMemorySignedKeys
    val signed = localConfirmedState == "confirmed" ||
        inMemoryHit ||
        isAttendedRollcallStatus(active.rawTronClassRollcall?.studentStatus) ||
        active.chaoXingSignDetail?.isAlreadySigned == true ||
        isAttendedRollcallStatus(latestStatsMyStatus)
    return if (signed) RollcallHeroCardState.ACTIVE_SIGNED else RollcallHeroCardState.ACTIVE_UNSIGNED
}
