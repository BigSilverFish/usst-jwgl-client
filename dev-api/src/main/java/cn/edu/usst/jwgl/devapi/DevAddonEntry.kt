package cn.edu.usst.jwgl.devapi

import android.app.Activity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.RadioGroup

interface DevAddonEntry {
    val apiVersion: Int
    val addonVersionName: String

    fun initialize(bridge: DevHostBridge)

    val autoSignExtension: AutoSignExtension
    val numberRollcallExtension: NumberRollcallExtension
    val radarRollcallExtension: RadarRollcallExtension
    val autoBrushExtension: AutoBrushExtension
    val liveScreenExtension: LiveScreenExtension
}

interface AutoSignExtension {
    fun isAutoSignAvailable(): Boolean
    fun showStrategyConfigDialog(activity: Activity, onChanged: () -> Unit)
    fun showAutoSignSettingsDialog(activity: Activity, onChanged: () -> Unit)
    suspend fun executeAutoSign(request: AutoSignRequest, callbacks: AutoSignCallbacks): AutoSignOutcome
}

interface NumberDialogAddonHandle {
    fun setFetchEnabled(enabled: Boolean)
    fun isBatchChecked(): Boolean
}

interface NumberRollcallExtension {
    fun shouldPrefillChaoXingCode(): Boolean
    fun mountNumberDialogSlots(
        activity: Activity,
        actionSlot: ViewGroup,
        batchSlot: ViewGroup,
        onFetchCodeClicked: () -> Unit
    ): NumberDialogAddonHandle?

    suspend fun lookupNumberCode(rollcall: HostRollcallInfo): NumberCodeResult
    suspend fun batchAnswerNumber(rollcallId: Long, code: String): List<HostAccountSignResult>
    suspend fun batchAnswerQr(rollcallId: Long, qrTokenData: String): List<HostAccountSignResult>
}

interface RadarDialogHost {
    val rgLocationMode: RadioGroup
    val extraPanelSlot: ViewGroup
    val batchSlot: ViewGroup
    val gpsRadioButtonId: Int
    val rollcallIdProvider: () -> Long
    val courseName: String?
    val classroom: String?
    val isChaoXingMode: Boolean
    val teacherLat: Double?
    val teacherLng: Double?
    val teacherScope: Double?
    val locationAddress: String?

    fun onSelectVirtualLocation(location: HostLocation, displayTitle: String, accuracyTip: String)
    fun onSelectGpsMode()
    fun onSetSubmitEnabled(enabled: Boolean)
    fun onSignSucceeded()
    fun onDismiss()
    fun ensureRollcallId(onReady: (Long) -> Unit)
    fun getCurrentGpsLocation(): HostLocation?
}

interface RadarDialogAddonHandle {
    fun isVirtualModeSelected(): Boolean
    fun isBatchSignChecked(): Boolean
    fun onGpsLocationUpdated(wgsLat: Double, wgsLng: Double)
    fun onGpsPermissionDenied(): Boolean
    fun onGpsTimeoutHint(): String?
    fun handleCustomSubmit(
        rollcallId: Long,
        currentLat: Double,
        currentLng: Double,
        currentAcc: Double
    ): Boolean

    fun destroy()
}

interface RadarRollcallExtension {
    fun mountRadarDialogSlots(activity: Activity, host: RadarDialogHost): RadarDialogAddonHandle?
    suspend fun submitQuickBuildingRadar(
        rollcallId: Long,
        classroom: String?,
        targetLat: Double?,
        targetLng: Double?
    ): Result<HostRollcallSubmitResult>

    fun resolveDefaultBuildingLabel(buildingName: String?, classroom: String?): String?
}

interface AutoBrushExtension {
    fun isAutoBrushAvailable(): Boolean
    fun startAutoBrush(
        activity: Activity,
        courseId: Long,
        activities: List<HostActivityBrief>,
        onCompleted: () -> Unit
    )
}

interface LiveWebBannerHost {
    fun showBanner(title: String, scanning: Boolean, showDialpadIcon: Boolean)
    fun hideBanner()
    fun postDelayedHideBanner(delayMs: Long)
}

interface LiveWebSessionHandle {
    fun onMenuItemSelected(itemId: Int, item: MenuItem): Boolean
    fun onPageFinished(isLiveUrl: Boolean)
    fun onDestroy()
}

interface LiveScreenExtension {
    fun isLiveScreenAvailable(): Boolean
    fun showLiveFrameTestDialog(activity: Activity, keyword: String = "")
    fun showLiveQrRollcallDialog(
        activity: Activity,
        rollcallId: Long,
        courseName: String?,
        classroom: String?,
        chaoXingCourseId: String? = null,
        chaoXingClassId: String? = null,
        onSignSuccess: () -> Unit,
        onFallbackCamera: () -> Unit
    ): Boolean

    fun attachWebLiveSession(
        activity: Activity,
        webView: WebView,
        toolbarMenu: Menu,
        bannerHost: LiveWebBannerHost,
        isNumberFabMode: Boolean,
        onSetupNumberFab: (Long, String?) -> Unit
    ): LiveWebSessionHandle?
}

object NoOpDevAddonEntry : DevAddonEntry {
    override val apiVersion: Int = DevApiConstants.DEV_API_VERSION
    override val addonVersionName: String = ""

    override fun initialize(bridge: DevHostBridge) = Unit

    override val autoSignExtension: AutoSignExtension = object : AutoSignExtension {
        override fun isAutoSignAvailable(): Boolean = false
        override fun showStrategyConfigDialog(activity: Activity, onChanged: () -> Unit) = Unit
        override fun showAutoSignSettingsDialog(activity: Activity, onChanged: () -> Unit) = Unit
        override suspend fun executeAutoSign(
            request: AutoSignRequest,
            callbacks: AutoSignCallbacks
        ): AutoSignOutcome = AutoSignOutcome(handledByAutoSign = false, isTerminal = false)
    }

    override val numberRollcallExtension: NumberRollcallExtension = object : NumberRollcallExtension {
        override fun shouldPrefillChaoXingCode(): Boolean = false
        override fun mountNumberDialogSlots(
            activity: Activity,
            actionSlot: ViewGroup,
            batchSlot: ViewGroup,
            onFetchCodeClicked: () -> Unit
        ): NumberDialogAddonHandle? = null

        override suspend fun lookupNumberCode(rollcall: HostRollcallInfo): NumberCodeResult =
            NumberCodeResult.Unavailable(isTerminal = true, message = "未安装开发者扩展包")

        override suspend fun batchAnswerNumber(
            rollcallId: Long,
            code: String
        ): List<HostAccountSignResult> = emptyList()

        override suspend fun batchAnswerQr(
            rollcallId: Long,
            qrTokenData: String
        ): List<HostAccountSignResult> = emptyList()
    }

    override val radarRollcallExtension: RadarRollcallExtension = object : RadarRollcallExtension {
        override fun mountRadarDialogSlots(
            activity: Activity,
            host: RadarDialogHost
        ): RadarDialogAddonHandle? = null

        override suspend fun submitQuickBuildingRadar(
            rollcallId: Long,
            classroom: String?,
            targetLat: Double?,
            targetLng: Double?
        ): Result<HostRollcallSubmitResult> =
            Result.failure(IllegalStateException("未安装开发者扩展包"))

        override fun resolveDefaultBuildingLabel(buildingName: String?, classroom: String?): String? =
            null
    }

    override val autoBrushExtension: AutoBrushExtension = object : AutoBrushExtension {
        override fun isAutoBrushAvailable(): Boolean = false
        override fun startAutoBrush(
            activity: Activity,
            courseId: Long,
            activities: List<HostActivityBrief>,
            onCompleted: () -> Unit
        ) = Unit
    }

    override val liveScreenExtension: LiveScreenExtension = object : LiveScreenExtension {
        override fun isLiveScreenAvailable(): Boolean = false
        override fun showLiveFrameTestDialog(activity: Activity, keyword: String) = Unit
        override fun showLiveQrRollcallDialog(
            activity: Activity,
            rollcallId: Long,
            courseName: String?,
            classroom: String?,
            chaoXingCourseId: String?,
            chaoXingClassId: String?,
            onSignSuccess: () -> Unit,
            onFallbackCamera: () -> Unit
        ): Boolean = false

        override fun attachWebLiveSession(
            activity: Activity,
            webView: WebView,
            toolbarMenu: Menu,
            bannerHost: LiveWebBannerHost,
            isNumberFabMode: Boolean,
            onSetupNumberFab: (Long, String?) -> Unit
        ): LiveWebSessionHandle? = null
    }
}
