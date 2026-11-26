package cn.edu.usst.jwgl.util

enum class RollcallAlertMode {
    POPUP,
    BANNER_ONLY
}

enum class RollcallAlertStage {
    MANUAL_DETECTED,
    AUTO_SIGN_DETECTED,
    AUTO_SIGN_SUCCEEDED,
    AUTO_SIGN_FAILED_FALLBACK
}

enum class RollcallPopupRoute {
    FOREGROUND_ACTIVITY,
    BACKGROUND_OVERLAY,
    TOP_BANNER_OVERLAY,
    NOTIFICATION_ONLY
}

/**
 * Pure routing policy deciding how a rollcall alert should be presented without
 * blindly calling background startActivity or silencing fallback notifications.
 */
object RollcallPopupRouter {

    fun resolveEffectiveMode(
        userMode: RollcallAlertMode,
        stage: RollcallAlertStage
    ): RollcallAlertMode = when (stage) {
        RollcallAlertStage.AUTO_SIGN_DETECTED,
        RollcallAlertStage.AUTO_SIGN_SUCCEEDED -> RollcallAlertMode.BANNER_ONLY
        RollcallAlertStage.MANUAL_DETECTED,
        RollcallAlertStage.AUTO_SIGN_FAILED_FALLBACK -> userMode
    }

    fun decide(
        effectiveMode: RollcallAlertMode,
        hasResumedActivity: Boolean,
        isScreenInteractive: Boolean,
        isKeyguardLocked: Boolean,
        canDrawOverlays: Boolean
    ): RollcallPopupRoute {
        if (!isScreenInteractive || isKeyguardLocked) return RollcallPopupRoute.NOTIFICATION_ONLY
        if (effectiveMode == RollcallAlertMode.BANNER_ONLY) {
            return if (canDrawOverlays) RollcallPopupRoute.TOP_BANNER_OVERLAY else RollcallPopupRoute.NOTIFICATION_ONLY
        }
        if (hasResumedActivity) return RollcallPopupRoute.FOREGROUND_ACTIVITY
        if (canDrawOverlays) return RollcallPopupRoute.BACKGROUND_OVERLAY
        return RollcallPopupRoute.NOTIFICATION_ONLY
    }

    fun decide(
        popupEnabled: Boolean,
        hasResumedActivity: Boolean,
        isScreenInteractive: Boolean,
        isKeyguardLocked: Boolean,
        canDrawOverlays: Boolean
    ): RollcallPopupRoute = decide(
        effectiveMode = if (popupEnabled) RollcallAlertMode.POPUP else RollcallAlertMode.BANNER_ONLY,
        hasResumedActivity = hasResumedActivity,
        isScreenInteractive = isScreenInteractive,
        isKeyguardLocked = isKeyguardLocked,
        canDrawOverlays = canDrawOverlays
    )
}
