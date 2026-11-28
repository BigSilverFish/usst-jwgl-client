package cn.edu.usst.jwgl.devaddon

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationManager
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.data.local.AccountVault
import cn.edu.usst.jwgl.data.local.AuthPreferences
import cn.edu.usst.jwgl.data.local.RollcallSubmissionStore
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.MultiAccountRollcallResult
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.data.network.ChaoXingClient
import cn.edu.usst.jwgl.data.network.ChaoXingQrPayload
import cn.edu.usst.jwgl.data.network.LiveUsstClient
import cn.edu.usst.jwgl.data.network.RollcallPollingSource
import cn.edu.usst.jwgl.data.network.RollcallVerificationState
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.devapi.DevHostBridge
import cn.edu.usst.jwgl.devapi.HostAccountInfo
import cn.edu.usst.jwgl.devapi.HostAccountSession
import cn.edu.usst.jwgl.devapi.HostAccountSignResult
import cn.edu.usst.jwgl.devapi.HostAttendanceStats
import cn.edu.usst.jwgl.devapi.HostBuilding
import cn.edu.usst.jwgl.devapi.HostChaoXingQrParsed
import cn.edu.usst.jwgl.devapi.HostColor
import cn.edu.usst.jwgl.devapi.HostHttpResponse
import cn.edu.usst.jwgl.devapi.HostLiveCourse
import cn.edu.usst.jwgl.devapi.HostLiveDeviceView
import cn.edu.usst.jwgl.devapi.HostLocation
import cn.edu.usst.jwgl.devapi.HostQrRollcallParsed
import cn.edu.usst.jwgl.devapi.HostRollcallInfo
import cn.edu.usst.jwgl.devapi.HostRollcallSubmitResult
import cn.edu.usst.jwgl.devapi.HostVerificationState
import cn.edu.usst.jwgl.ui.tronclass.TronClassFragment
import cn.edu.usst.jwgl.util.CoordinateUtils
import cn.edu.usst.jwgl.util.RollcallAlertStage
import cn.edu.usst.jwgl.util.RollcallNotificationHelper
import cn.edu.usst.jwgl.util.RollcallScheduleHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal class DevHostBridgeImpl(context: Context) : DevHostBridge {

    override val appContext: Context = context.applicationContext

    private val tronClassClient by lazy { TronClassClient(appContext) }
    private val chaoXingClient by lazy { ChaoXingClient(appContext) }
    private val liveClient by lazy { LiveUsstClient(appContext) }
    private val tcPrefs by lazy { TronClassPreferences.getInstance(appContext) }

    private val barcodeScanner: BarcodeScanner? by lazy {
        runCatching {
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX)
                .build()
            BarcodeScanning.getClient(options)
        }.getOrNull()
    }

    override fun isAddonActive(): Boolean = DevAddonManager.isAddonActive(appContext)

    override fun getCurrentStudentId(): String = AuthPreferences.currentStudentId(appContext)

    override fun getSavedAccountsOrdered(): List<HostAccountInfo> {
        val vault = AccountVault.getInstance(appContext)
        val currId = getCurrentStudentId()
        val ordered = MultiAccountRollcallResult.orderAccountsPrimaryFirst(vault.getAccounts(), currId)
            .map { HostAccountInfo(studentId = it.studentId, studentName = it.studentName) }
        if (!tcPrefs.isRollcallMultiAccountSignEnabled()) {
            return ordered.filter { it.studentId == currId }.ifEmpty { ordered.take(1) }
        }
        return ordered
    }

    override suspend fun ensureTronClassSession(
        studentId: String,
        fastPathIfVerified: Boolean
    ): Result<HostAccountSession> {
        return tronClassClient.ensureSessionForAccountWithDevice(studentId, fastPathIfVerified)
            .map { (sid, devId) -> HostAccountSession(studentId, sid, devId) }
    }

    override fun getCachedDeviceId(studentId: String): String = tcPrefs.getDeviceId(studentId)

    override suspend fun executeTronClassGet(
        url: String,
        expectedAccount: String?,
        session: HostAccountSession?
    ): Result<HostHttpResponse> {
        return tronClassClient.executeRawAuthenticatedRequest(
            method = "GET",
            url = url,
            jsonBody = null,
            customSessionId = session?.sessionId,
            expectedAccount = expectedAccount ?: session?.studentId
        ).map { (code, body) -> HostHttpResponse(code, body) }
    }

    override suspend fun executeTronClassPut(
        url: String,
        jsonBody: String,
        expectedAccount: String?,
        session: HostAccountSession?
    ): Result<HostHttpResponse> {
        return tronClassClient.executeRawAuthenticatedRequest(
            method = "PUT",
            url = url,
            jsonBody = jsonBody,
            customSessionId = session?.sessionId,
            expectedAccount = expectedAccount ?: session?.studentId
        ).map { (code, body) -> HostHttpResponse(code, body) }
    }

    override suspend fun fetchOngoingRollcalls(expectedAccount: String?): Result<List<HostRollcallInfo>> {
        return tronClassClient.fetchOngoingRollcalls(expectedAccount).map { list ->
            list.map { it.toHostInfo() }
        }
    }

    override suspend fun fetchRollcallAttendanceStats(
        rollcallId: Long,
        expectedAccount: String?
    ): Result<HostAttendanceStats> {
        return tronClassClient.fetchRollcallAttendanceStats(rollcallId, expectedAccount).map { stats ->
            HostAttendanceStats(
                presentCount = stats.presentCount,
                totalCount = stats.totalCount,
                badgeText = stats.formatBadgeText()
            )
        }
    }

    override suspend fun answerNumberRollcall(
        rollcallId: Long,
        code: String,
        session: HostAccountSession?
    ): Result<HostRollcallSubmitResult> {
        return tronClassClient.answerNumberRollcall(
            rollcallId = rollcallId,
            numberCode = code,
            customSessionId = session?.sessionId,
            customDeviceId = session?.deviceId,
            customAccountKey = session?.studentId
        ).map { HostRollcallSubmitResult(it.success, it.message, it.statusName.orEmpty(), it.distance) }
    }

    override suspend fun answerQrRollcall(
        rollcallId: Long,
        qrTokenData: String,
        session: HostAccountSession?
    ): Result<HostRollcallSubmitResult> {
        return tronClassClient.answerQRRollcall(
            rollcallId = rollcallId,
            qrTokenData = qrTokenData,
            customSessionId = session?.sessionId,
            customDeviceId = session?.deviceId,
            customAccountKey = session?.studentId
        ).map { HostRollcallSubmitResult(it.success, it.message, it.statusName.orEmpty(), it.distance) }
    }

    override suspend fun answerRadarRollcall(
        rollcallId: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Double?,
        session: HostAccountSession?
    ): Result<HostRollcallSubmitResult> {
        return tronClassClient.answerRadarRollcall(
            rollcallId = rollcallId,
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            customSessionId = session?.sessionId,
            customDeviceId = session?.deviceId,
            customAccountKey = session?.studentId
        ).map { HostRollcallSubmitResult(it.success, it.message, it.statusName.orEmpty(), it.distance) }
    }

    override suspend fun answerSelfRegistrationRollcall(
        rollcallId: Long,
        session: HostAccountSession?
    ): Result<HostRollcallSubmitResult> {
        return tronClassClient.answerSelfRegistrationRollcall(
            rollcallId = rollcallId,
            customSessionId = session?.sessionId,
            customDeviceId = session?.deviceId,
            customAccountKey = session?.studentId
        ).map { HostRollcallSubmitResult(it.success, it.message, it.statusName.orEmpty(), it.distance) }
    }

    override suspend fun verifyRollcallAttendance(
        rollcallId: Long,
        studentId: String,
        sessionId: String?,
        courseIdHint: Long
    ): HostVerificationState {
        return when (
            tronClassClient.verifyRollcallAttendance(
                rollcallId = rollcallId,
                studentId = studentId,
                customSessionId = sessionId,
                expectedAccount = studentId,
                courseIdHint = courseIdHint
            )
        ) {
            RollcallVerificationState.CONFIRMED -> HostVerificationState.CONFIRMED
            RollcallVerificationState.NOT_CONFIRMED -> HostVerificationState.NOT_CONFIRMED
            RollcallVerificationState.UNAVAILABLE -> HostVerificationState.UNAVAILABLE
        }
    }

    override fun parseRollcallQr(raw: String): HostQrRollcallParsed? {
        val parsed = tronClassClient.parseRollcallQr(raw) ?: return null
        return HostQrRollcallParsed(rollcallId = parsed.rollcallId, qrData = parsed.qrData)
    }

    override fun parseChaoXingQr(raw: String): HostChaoXingQrParsed? {
        if (!ChaoXingQrPayload.isChaoXingQrUrl(raw)) return null
        val parsed = ChaoXingQrPayload.parse(raw).getOrNull() ?: return null
        return HostChaoXingQrParsed(aid = parsed.activeId, enc = parsed.enc, code = parsed.code)
    }

    override suspend fun submitChaoXingQrSign(
        courseId: String,
        classId: String,
        activeId: Long,
        enc: String,
        signCode: String?
    ): Result<HostRollcallSubmitResult> {
        return chaoXingClient.submitSign(
            courseId = courseId,
            classId = classId,
            activeId = activeId,
            signType = "二维码签到",
            enc = enc,
            signCode = signCode,
            allowRejectedRetry = true
        ).map { res ->
            HostRollcallSubmitResult(success = res.isSuccess, message = res.message)
        }
    }

    override suspend fun reportVideoProgress(
        activityId: Long,
        durationSeconds: Double,
        uploadId: Long
    ): Result<Boolean> = tronClassClient.reportVideoProgress(activityId, durationSeconds, uploadId)

    override suspend fun logActivityRead(
        activityId: Long,
        uploadId: Long
    ): Result<Boolean> = tronClassClient.logActivityRead(activityId, uploadId)

    override fun markTronClassRollcallCompletedUi(rollcallId: Long) {
        TronClassFragment.onRollcallCompleted(rollcallId)
    }

    override suspend fun fetchOngoingLiveCourses(keyword: String): Result<List<HostLiveCourse>> {
        val now = System.currentTimeMillis()
        return liveClient.fetchOngoingLiveCourses(keyword).map { list ->
            list.map {
                HostLiveCourse(
                    id = it.id,
                    subjName = it.subjName,
                    clroName = it.clroName,
                    isCurrentlyLive = it.isCurrentlyLive(now)
                )
            }
        }
    }

    override suspend fun searchLiveCourse(courseName: String, classroom: String?): Result<HostLiveCourse?> {
        val now = System.currentTimeMillis()
        return liveClient.searchLiveCourse(courseName, classroom).map { record ->
            record?.let {
                HostLiveCourse(
                    id = it.id,
                    subjName = it.subjName,
                    clroName = it.clroName,
                    isCurrentlyLive = it.isCurrentlyLive(now)
                )
            }
        }
    }

    override suspend fun fetchLiveVideoInfos(liveCourseId: Long): Result<List<HostLiveDeviceView>> {
        return liveClient.fetchLiveVideoInfos(liveCourseId).map { list ->
            list.map {
                HostLiveDeviceView(
                    deviViewNum = it.deviViewNum,
                    viewName = it.viewName,
                    fullPlayUrl = it.getFullPlayUrl()
                )
            }
        }
    }

    override fun selectOptimalCandidateViews(views: List<HostLiveDeviceView>): List<HostLiveDeviceView> {
        if (views.isEmpty()) return emptyList()
        val ppt = views.firstOrNull { it.deviViewNum == 5 && !it.fullPlayUrl.isNullOrBlank() }
        if (ppt != null) return listOf(ppt)
        val candidates = mutableListOf<HostLiveDeviceView>()
        views.firstOrNull { (it.deviViewNum == 7 || it.deviViewNum == 6) && !it.fullPlayUrl.isNullOrBlank() }
            ?.let { candidates.add(it) }
        views.firstOrNull { (it.deviViewNum == 2 || it.deviViewNum == 1) && !it.fullPlayUrl.isNullOrBlank() }
            ?.let { candidates.add(it) }
        return candidates
    }

    override suspend fun scanQrFromBitmap(bitmap: Bitmap): Result<String?> =
        suspendCancellableCoroutine { continuation ->
            val scanner = barcodeScanner
            if (scanner == null) {
                if (continuation.isActive) {
                    continuation.resume(Result.failure(IllegalStateException("ML Kit 识码器初始化失败")))
                }
                return@suspendCancellableCoroutine
            }
            try {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                scanner.process(inputImage)
                    .addOnSuccessListener { barcodes ->
                        val raw = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                        if (continuation.isActive) {
                            continuation.resume(Result.success(raw))
                        }
                    }
                    .addOnFailureListener { e ->
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(e))
                        }
                    }
            } catch (t: Throwable) {
                if (continuation.isActive) {
                    continuation.resume(Result.failure(t))
                }
            }
        }

    override fun observeOngoingRollcalls(
        intervalMillis: Long,
        enabled: () -> Boolean,
        onResult: (Result<List<HostRollcallInfo>>) -> Unit
    ): AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch {
            RollcallPollingSource.getInstance(appContext)
                .observe(intervalMillis = intervalMillis, enabled = enabled)
                .collect { res ->
                    onResult(res.map { list -> list.map { it.toHostInfo() } })
                }
        }
        return AutoCloseable {
            job.cancel()
            scope.cancel()
        }
    }

    override fun getCampusBuildings(): List<HostBuilding> =
        CoordinateUtils.USST_BUILDINGS.map { it.toHostBuilding() }

    override fun findMatchingBuilding(query: String?): HostBuilding? =
        CoordinateUtils.findMatchingBuilding(query)?.toHostBuilding()

    override fun findNearestBuildingFromWgs84(lat: Double, lng: Double): Pair<HostBuilding, Double>? {
        val (building, dist) = CoordinateUtils.findNearestBuildingFromWgs84(lat, lng)
        return building.toHostBuilding() to dist
    }

    override fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> =
        CoordinateUtils.wgs84ToGcj02(lat, lng)

    override fun getFreshDeviceLocation(): HostLocation? {
        val finePerm = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarsePerm = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (finePerm != PackageManager.PERMISSION_GRANTED && coarsePerm != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val now = System.currentTimeMillis()
        var bestLoc: Location? = null
        for (p in providers) {
            try {
                if (lm.isProviderEnabled(p)) {
                    val loc = lm.getLastKnownLocation(p)
                    if (loc != null &&
                        (now - loc.time) in 0L..(5 * 60 * 1000L) &&
                        loc.hasAccuracy() &&
                        loc.accuracy > 0f &&
                        loc.accuracy <= 100f
                    ) {
                        if (bestLoc == null || loc.accuracy < bestLoc.accuracy) {
                            bestLoc = loc
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        return bestLoc?.let {
            HostLocation(
                latitude = it.latitude,
                longitude = it.longitude,
                accuracyMeters = it.accuracy.toDouble(),
                source = it.provider ?: "device"
            )
        }
    }

    override fun findScheduledRoomForCourse(courseName: String?): String? =
        RollcallScheduleHelper.findMatchingClassSession(appContext, courseName)?.room

    override fun isRollcallAutoSignEnabled(): Boolean = tcPrefs.isRollcallAutoSignEnabled()
    override fun setRollcallAutoSignEnabled(enabled: Boolean) = tcPrefs.setRollcallAutoSignEnabled(enabled)
    override fun getAutoSignStrategy(): String = tcPrefs.getRollcallAutoSignStrategy()
    override fun setAutoSignStrategy(strategy: String) = tcPrefs.setRollcallAutoSignStrategy(strategy)
    override fun getAutoSignStrategyIndex(): Int = tcPrefs.getRollcallAutoSignStrategyIndex()
    override fun getAutoSignStrategyDisplayName(): String = tcPrefs.getRollcallAutoSignStrategyDisplayName()
    override fun isHeadcountStrategy(strategy: String): Boolean = TronClassPreferences.isHeadcountStrategy(strategy)
    override fun getAutoSignDelaySeconds(): Int = tcPrefs.getRollcallAutoSignDelaySeconds()
    override fun getAutoSignCustomSeconds(): Int = tcPrefs.getRollcallAutoSignCustomSeconds()
    override fun setAutoSignCustomSeconds(seconds: Int) = tcPrefs.setRollcallAutoSignCustomSeconds(seconds)
    override fun getAutoSignCustomRatio(): Int = tcPrefs.getRollcallAutoSignCustomRatio()
    override fun setAutoSignCustomRatio(ratio: Int) = tcPrefs.setRollcallAutoSignCustomRatio(ratio)

    override fun isRollcallHandled(rollcallId: Long): Boolean = tcPrefs.isRollcallHandled(rollcallId)
    override fun markRollcallHandled(rollcallId: Long) = tcPrefs.markRollcallHandled(rollcallId)

    override fun getSubmissionState(studentId: String, rollcallId: Long): String? =
        RollcallSubmissionStore(appContext).state(studentId, rollcallId)

    override fun recordSubmissionConfirmed(studentId: String, rollcallId: Long) {
        RollcallSubmissionStore(appContext).record(studentId, rollcallId, "confirmed")
    }

    override fun invalidateSubmissionConfirmed(studentId: String, rollcallId: Long) {
        RollcallSubmissionStore(appContext).invalidateConfirmed(studentId, rollcallId)
    }

    override fun getLastCrashLog(): String? {
        return runCatching {
            appContext.getSharedPreferences("usst_crash_logs", Context.MODE_PRIVATE)
                .getString("last_crash", null)
        }.getOrNull()
    }

    override fun clearLastCrashLog() {
        runCatching {
            appContext.getSharedPreferences("usst_crash_logs", Context.MODE_PRIVATE)
                .edit()
                .remove("last_crash")
                .apply()
        }
    }

    override fun notifyAutoSignDetectedBanner(
        rollcall: HostRollcallInfo,
        waitHint: String,
        classroom: String?,
        shouldShowPopup: () -> Boolean
    ) {
        RollcallNotificationHelper.notifyAutoSignDetectedBanner(
            context = appContext,
            rollcallId = rollcall.id,
            courseName = rollcall.courseName,
            rollcallType = rollcall.normalizedType,
            typeDisplayName = rollcall.typeDisplayName,
            waitHint = waitHint,
            classroom = classroom,
            latitude = rollcall.latitude,
            longitude = rollcall.longitude,
            scope = rollcall.scope,
            shouldShowPopup = shouldShowPopup
        )
    }

    override fun cancelAutoSignDetectedBanner(rollcallId: Long) {
        RollcallNotificationHelper.cancelAutoSignDetectedBanner(appContext, rollcallId)
    }

    override fun notifyQrFallback(rollcallId: Long, courseName: String, shouldShowPopup: () -> Boolean) {
        RollcallNotificationHelper.notifyQrRollcall(
            context = appContext,
            rollcallId = rollcallId,
            courseName = courseName,
            stage = RollcallAlertStage.AUTO_SIGN_FAILED_FALLBACK,
            shouldShowPopup = shouldShowPopup
        )
    }

    override fun notifyRadarAutoResult(
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
    ) {
        val building = buildingId?.let { id -> CoordinateUtils.USST_BUILDINGS.find { it.id == id } }
        RollcallNotificationHelper.notifyRadarResult(
            context = appContext,
            rollcallId = rollcallId,
            courseName = courseName,
            building = building,
            success = success,
            message = message,
            classroom = classroom,
            latitude = lat,
            longitude = lng,
            scope = scope,
            stage = if (success) RollcallAlertStage.AUTO_SIGN_SUCCEEDED else RollcallAlertStage.AUTO_SIGN_FAILED_FALLBACK,
            shouldShowPopup = shouldShowPopup
        )
    }

    override fun notifyNumberFallback(rollcallId: Long, courseName: String, shouldShowPopup: () -> Boolean) {
        RollcallNotificationHelper.notifyNumberRollcall(
            context = appContext,
            rollcallId = rollcallId,
            courseName = courseName,
            stage = RollcallAlertStage.AUTO_SIGN_FAILED_FALLBACK,
            shouldShowPopup = shouldShowPopup
        )
    }

    override fun notifyNumberAutoResult(
        rollcallId: Long,
        courseName: String,
        success: Boolean,
        message: String,
        shouldShowPopup: () -> Boolean
    ) {
        RollcallNotificationHelper.notifyNumberAutoResult(
            context = appContext,
            rollcallId = rollcallId,
            courseName = courseName,
            success = success,
            message = message,
            shouldShowPopup = shouldShowPopup
        )
    }

    override fun notifySelfRegAutoResult(
        rollcallId: Long,
        courseName: String,
        success: Boolean,
        message: String,
        shouldShowPopup: () -> Boolean
    ) {
        RollcallNotificationHelper.notifySelfRegistrationResult(
            context = appContext,
            rollcallId = rollcallId,
            courseName = courseName,
            success = success,
            message = message,
            shouldShowPopup = shouldShowPopup
        )
    }

    override fun notifyLiveQrResult(
        rollcallId: Long,
        courseName: String,
        success: Boolean,
        viewName: String,
        message: String,
        shouldShowPopup: () -> Boolean
    ) {
        RollcallNotificationHelper.notifyLiveQrResult(
            context = appContext,
            rollcallId = rollcallId,
            courseName = courseName,
            success = success,
            viewName = viewName,
            message = message,
            shouldShowPopup = shouldShowPopup
        )
    }

    override fun showToast(message: String, longDuration: Boolean) {
        Toast.makeText(appContext, message, if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    override fun showSingleChoiceDialog(
        activity: Activity,
        title: String,
        items: Array<String>,
        checkedItem: Int,
        onSelected: (Int) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setSingleChoiceItems(items, checkedItem) { dialog, which ->
                dialog.dismiss()
                onSelected(which)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun showListDialog(
        activity: Activity,
        title: String,
        items: Array<String>,
        neutralText: String?,
        onNeutral: (() -> Unit)?,
        positiveText: String?,
        onPositive: (() -> Unit)?,
        onItemClick: (Int) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setItems(items) { _, which -> onItemClick(which) }
            .setNegativeButton("取消", null)
        if (!neutralText.isNullOrBlank()) {
            builder.setNeutralButton(neutralText) { _, _ -> onNeutral?.invoke() }
        }
        if (!positiveText.isNullOrBlank()) {
            builder.setPositiveButton(positiveText) { _, _ -> onPositive?.invoke() }
        }
        builder.show()
    }

    override fun showConfirmDialog(
        activity: Activity,
        title: String,
        message: String,
        positiveText: String,
        negativeText: String,
        neutralText: String?,
        onNeutral: (() -> Unit)?,
        onConfirm: () -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positiveText) { _, _ -> onConfirm() }
            .setNegativeButton(negativeText, null)
        if (!neutralText.isNullOrBlank()) {
            builder.setNeutralButton(neutralText) { _, _ -> onNeutral?.invoke() }
        }
        builder.show()
    }

    override fun showInputDialog(
        activity: Activity,
        title: String,
        message: String,
        hint: String,
        initialValue: String,
        numericOnly: Boolean,
        onConfirm: (String) -> Unit
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val input = EditText(activity).apply {
            this.hint = hint
            if (numericOnly) {
                inputType = InputType.TYPE_CLASS_NUMBER
            }
            setText(initialValue)
            setSelection(text.length)
        }
        val container = FrameLayout(activity).apply {
            val pad = (24 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, (8 * activity.resources.displayMetrics.density).toInt(), pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .apply { if (message.isNotBlank()) setMessage(message) }
            .setView(container)
            .setPositiveButton("确定") { _, _ -> onConfirm(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun showMultiAccountSummaryDialog(
        activity: Activity,
        title: String,
        results: List<HostAccountSignResult>
    ) {
        if (activity.isFinishing || activity.isDestroyed) return
        val sb = StringBuilder()
        for (r in results) {
            val icon = if (r.success) "🟢" else "🔴"
            val status = if (r.statusName.isNotBlank()) " [${r.statusName}]" else ""
            sb.append("$icon ${r.studentName} (${r.studentId})$status\n")
            sb.append("   ${r.message.trim()}\n\n")
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(sb.toString().trimEnd())
            .setPositiveButton("完成", null)
            .show()
    }

    override fun resolveThemeColor(context: Context, semantic: HostColor): Int {
        val resId = when (semantic) {
            HostColor.PRIMARY -> R.color.primary
            HostColor.SURFACE -> R.color.surface
            HostColor.SURFACE_VARIANT -> R.color.surface_variant
            HostColor.TEXT_PRIMARY -> R.color.text_primary
            HostColor.TEXT_SECONDARY -> R.color.text_secondary
            HostColor.ACCENT_BLUE -> R.color.grade_blue_text
            HostColor.DANGER -> R.color.grade_red_text
        }
        return ContextCompat.getColor(context, resId)
    }

    private fun TronClassRollcall.toHostInfo(): HostRollcallInfo = HostRollcallInfo(
        id = getEffectiveId(),
        courseId = courseId,
        courseName = courseName,
        normalizedType = getNormalizedType(),
        typeDisplayName = getTypeDisplayName(),
        classroom = classroom,
        latitude = latitude,
        longitude = longitude,
        scope = scope,
        numberCode = numberCode,
        isExpired = isExpired
    )

    private fun CoordinateUtils.CampusBuilding.toHostBuilding(): HostBuilding = HostBuilding(
        id = id,
        name = name,
        campus = campus,
        latitude = latitude,
        longitude = longitude,
        aliases = aliases
    )
}
