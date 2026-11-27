package cn.edu.usst.jwgl.devapi

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap

interface DevHostBridge {
    val appContext: Context

    fun isAddonActive(): Boolean

    // Accounts & Sessions
    fun getCurrentStudentId(): String
    fun getSavedAccountsOrdered(): List<HostAccountInfo>
    suspend fun ensureTronClassSession(
        studentId: String,
        fastPathIfVerified: Boolean = false
    ): Result<HostAccountSession>
    fun getCachedDeviceId(studentId: String): String

    // TronClass Atomic APIs
    suspend fun executeTronClassGet(
        url: String,
        expectedAccount: String? = null,
        session: HostAccountSession? = null
    ): Result<HostHttpResponse>

    suspend fun executeTronClassPut(
        url: String,
        jsonBody: String,
        expectedAccount: String? = null,
        session: HostAccountSession? = null
    ): Result<HostHttpResponse>

    suspend fun fetchOngoingRollcalls(expectedAccount: String? = null): Result<List<HostRollcallInfo>>
    suspend fun fetchRollcallAttendanceStats(
        rollcallId: Long,
        expectedAccount: String? = null
    ): Result<HostAttendanceStats>

    suspend fun answerNumberRollcall(
        rollcallId: Long,
        code: String,
        session: HostAccountSession? = null
    ): Result<HostRollcallSubmitResult>

    suspend fun answerQrRollcall(
        rollcallId: Long,
        qrTokenData: String,
        session: HostAccountSession? = null
    ): Result<HostRollcallSubmitResult>

    suspend fun answerRadarRollcall(
        rollcallId: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Double?,
        session: HostAccountSession? = null
    ): Result<HostRollcallSubmitResult>

    suspend fun answerSelfRegistrationRollcall(
        rollcallId: Long,
        session: HostAccountSession? = null
    ): Result<HostRollcallSubmitResult>

    suspend fun verifyRollcallAttendance(
        rollcallId: Long,
        studentId: String,
        sessionId: String? = null,
        courseIdHint: Long = 0L
    ): HostVerificationState

    fun parseRollcallQr(raw: String): HostQrRollcallParsed?
    fun parseChaoXingQr(raw: String): HostChaoXingQrParsed?

    suspend fun submitChaoXingQrSign(
        courseId: String,
        classId: String,
        activeId: Long,
        enc: String,
        signCode: String? = null
    ): Result<HostRollcallSubmitResult>

    suspend fun reportVideoProgress(
        activityId: Long,
        durationSeconds: Double,
        uploadId: Long
    ): Result<Boolean>

    suspend fun logActivityRead(
        activityId: Long,
        uploadId: Long
    ): Result<Boolean>

    fun markTronClassRollcallCompletedUi(rollcallId: Long)

    // Live Course & QR Vision APIs
    suspend fun fetchOngoingLiveCourses(keyword: String): Result<List<HostLiveCourse>>
    suspend fun searchLiveCourse(courseName: String, classroom: String?): Result<HostLiveCourse?>
    suspend fun fetchLiveVideoInfos(liveCourseId: Long): Result<List<HostLiveDeviceView>>
    fun selectOptimalCandidateViews(views: List<HostLiveDeviceView>): List<HostLiveDeviceView>
    suspend fun scanQrFromBitmap(bitmap: Bitmap): Result<String?>
    fun observeOngoingRollcalls(
        intervalMillis: Long,
        enabled: () -> Boolean,
        onResult: (Result<List<HostRollcallInfo>>) -> Unit
    ): AutoCloseable

    // Location & Building Helpers
    fun getCampusBuildings(): List<HostBuilding>
    fun findMatchingBuilding(query: String?): HostBuilding?
    fun findNearestBuildingFromWgs84(lat: Double, lng: Double): Pair<HostBuilding, Double>?
    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double>
    fun getFreshDeviceLocation(): HostLocation?
    fun findScheduledRoomForCourse(courseName: String?): String?

    // Preferences & Submission State
    fun isRollcallAutoSignEnabled(): Boolean
    fun setRollcallAutoSignEnabled(enabled: Boolean)
    fun getAutoSignStrategy(): String
    fun setAutoSignStrategy(strategy: String)
    fun getAutoSignStrategyIndex(): Int
    fun getAutoSignStrategyDisplayName(): String
    fun isHeadcountStrategy(strategy: String): Boolean
    fun getAutoSignDelaySeconds(): Int
    fun getAutoSignCustomSeconds(): Int
    fun setAutoSignCustomSeconds(seconds: Int)
    fun getAutoSignCustomRatio(): Int
    fun setAutoSignCustomRatio(ratio: Int)

    fun isRollcallHandled(rollcallId: Long): Boolean
    fun markRollcallHandled(rollcallId: Long)
    fun getSubmissionState(studentId: String, rollcallId: Long): String?
    fun recordSubmissionConfirmed(studentId: String, rollcallId: Long)
    fun invalidateSubmissionConfirmed(studentId: String, rollcallId: Long)
    fun getLastCrashLog(): String?
    fun clearLastCrashLog()

    // Notifications & UI Dialog Helpers
    fun notifyAutoSignDetectedBanner(
        rollcall: HostRollcallInfo,
        waitHint: String,
        classroom: String?,
        shouldShowPopup: () -> Boolean
    )

    fun cancelAutoSignDetectedBanner(rollcallId: Long)

    fun notifyQrFallback(
        rollcallId: Long,
        courseName: String,
        shouldShowPopup: () -> Boolean
    )

    fun notifyRadarAutoResult(
        rollcallId: Long,
        courseName: String,
        buildingId: String?,
        success: Boolean,
        message: String,
        classroom: String?,
        lat: Double?,
        lng: Double?,
        scope: Double?,
        shouldShowPopup: () -> Boolean
    )

    fun notifyNumberFallback(
        rollcallId: Long,
        courseName: String,
        shouldShowPopup: () -> Boolean
    )

    fun notifyNumberAutoResult(
        rollcallId: Long,
        courseName: String,
        success: Boolean,
        message: String,
        shouldShowPopup: () -> Boolean
    )

    fun notifySelfRegAutoResult(
        rollcallId: Long,
        courseName: String,
        success: Boolean,
        message: String,
        shouldShowPopup: () -> Boolean
    )

    fun notifyLiveQrResult(
        rollcallId: Long,
        courseName: String,
        success: Boolean,
        viewName: String,
        message: String,
        shouldShowPopup: () -> Boolean
    )

    fun showToast(message: String, longDuration: Boolean = false)

    fun showSingleChoiceDialog(
        activity: Activity,
        title: String,
        items: Array<String>,
        checkedItem: Int,
        onSelected: (Int) -> Unit
    )

    fun showListDialog(
        activity: Activity,
        title: String,
        items: Array<String>,
        neutralText: String? = null,
        onNeutral: (() -> Unit)? = null,
        positiveText: String? = null,
        onPositive: (() -> Unit)? = null,
        onItemClick: (Int) -> Unit
    )

    fun showConfirmDialog(
        activity: Activity,
        title: String,
        message: String,
        positiveText: String,
        negativeText: String = "取消",
        neutralText: String? = null,
        onNeutral: (() -> Unit)? = null,
        onConfirm: () -> Unit
    )

    fun showInputDialog(
        activity: Activity,
        title: String,
        message: String,
        hint: String,
        initialValue: String,
        numericOnly: Boolean,
        onConfirm: (String) -> Unit
    )

    fun showMultiAccountSummaryDialog(
        activity: Activity,
        title: String,
        results: List<HostAccountSignResult>
    )

    fun resolveThemeColor(context: Context, semantic: HostColor): Int
}
