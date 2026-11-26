package cn.edu.usst.jwgl.util

enum class RollcallPopupRoute {
    FOREGROUND_ACTIVITY,
    BACKGROUND_OVERLAY,
    NOTIFICATION_ONLY
}

/**
 * Pure routing policy deciding how a rollcall alert should be presented without
 * blindly calling background startActivity or silencing fallback notifications.
 */
object RollcallPopupRouter {

    fun decide(
        popupEnabled: Boolean,
        hasResumedActivity: Boolean,
        isScreenInteractive: Boolean,
        isKeyguardLocked: Boolean,
        canDrawOverlays: Boolean
    ): RollcallPopupRoute {
        if (!popupEnabled) return RollcallPopupRoute.NOTIFICATION_ONLY
        if (!isScreenInteractive || isKeyguardLocked) return RollcallPopupRoute.NOTIFICATION_ONLY
        if (hasResumedActivity) return RollcallPopupRoute.FOREGROUND_ACTIVITY
        if (canDrawOverlays) return RollcallPopupRoute.BACKGROUND_OVERLAY
        return RollcallPopupRoute.NOTIFICATION_ONLY
    }
}
