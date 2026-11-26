package cn.edu.usst.jwgl.service

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.Manifest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.ChaoXingActiveItem
import cn.edu.usst.jwgl.data.model.ChaoXingCourse
import cn.edu.usst.jwgl.data.model.NumberCodeLookupResult
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.data.network.ChaoXingClient
import cn.edu.usst.jwgl.data.network.ChaoXingRequestGuard
import cn.edu.usst.jwgl.data.network.RollcallPollingSource
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.util.CoordinateUtils
import cn.edu.usst.jwgl.util.DeveloperModeManager
import cn.edu.usst.jwgl.util.RollcallAlertStage
import cn.edu.usst.jwgl.util.RollcallNotificationHelper
import cn.edu.usst.jwgl.util.RollcallOverlayAlert
import cn.edu.usst.jwgl.util.RollcallScheduleHelper
import cn.edu.usst.jwgl.util.ScheduledClassSession
import cn.edu.usst.jwgl.worker.RollcallMonitorScheduler
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

class RollcallMonitorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null
    @Volatile
    private var activeSession: ScheduledClassSession? = null
    private val inFlightRollcalls = ConcurrentHashMap.newKeySet<Long>()
    private val retryAttempts = ConcurrentHashMap<Long, Int>()
    private val tronClassClient by lazy { TronClassClient(this) }
    private val chaoXingClient by lazy { ChaoXingClient(this) }
    private val prefs by lazy { TronClassPreferences.getInstance(this) }
    private val chaoXingPrefs by lazy { ChaoXingPreferences(this) }
    private val smartLiveQrManager by lazy { cn.edu.usst.jwgl.util.SmartLiveQrRollcallManager(this) }
    @Volatile
    private var cachedChaoXingCourses: List<ChaoXingCourse> = emptyList()
    @Volatile
    private var lastChaoXingCourseFetchMs: Long = 0L
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var screenOnReceiver: BroadcastReceiver? = null

    companion object {
        private const val TAG = "RollcallMonitorService"
        const val ACTION_START = "cn.edu.usst.jwgl.action.START_MONITOR"
        const val ACTION_STOP = "cn.edu.usst.jwgl.action.STOP_MONITOR"
        const val ACTION_CHECK_NOW = "cn.edu.usst.jwgl.action.CHECK_NOW"

        private const val POLL_INTERVAL_MS = 20_000L // 20 秒轮询一次
        private const val CHAOXING_POLL_BASE_INTERVAL_MS = 50_000L // 学习通低频探测基准 50 秒
        private const val CHAOXING_POLL_JITTER_MS = 10_000L // 随机抖动 0~10 秒
        private const val CHAOXING_COURSE_CACHE_TTL_MS = 30 * 60 * 1000L
        private const val CHAOXING_MAX_MATCHED_COURSES = 2
        private const val WAKE_LOCK_RENEW_TIMEOUT_MS = 3 * 60 * 1000L // 3 分钟滚动租约
        private const val MAX_AUTO_SIGN_ATTEMPTS = 3

        internal fun selectActionableRollcalls(
            list: List<TronClassRollcall>,
            isHandled: (Long) -> Boolean
        ): List<TronClassRollcall> = list.filter { rollcall ->
            val rollcallId = rollcall.getEffectiveId()
            rollcallId > 0L && !rollcall.isExpired && !isHandled(rollcallId)
        }

        private fun normalizeCourseName(raw: String): String =
            raw.replace(Regex("""[（(].*?[）)]"""), "")
                .replace(Regex("""\s+"""), "")
                .lowercase()

        internal fun selectChaoXingCoursesForSession(
            courses: List<ChaoXingCourse>,
            sessionCourseName: String,
            maxMatches: Int = CHAOXING_MAX_MATCHED_COURSES
        ): List<ChaoXingCourse> {
            val targetNorm = normalizeCourseName(sessionCourseName)
            if (targetNorm.isBlank() || maxMatches <= 0) return emptyList()
            val distinct = courses.distinctBy { "${it.courseId}_${it.classId}" }
            val exact = mutableListOf<ChaoXingCourse>()
            val partial = mutableListOf<ChaoXingCourse>()
            for (cx in distinct) {
                val cxNorm = normalizeCourseName(cx.courseName)
                if (cxNorm.isBlank()) continue
                when {
                    cxNorm == targetNorm -> exact.add(cx)
                    cxNorm.contains(targetNorm) || targetNorm.contains(cxNorm) -> partial.add(cx)
                }
            }
            return (exact + partial).take(maxMatches)
        }

        internal fun selectActionableChaoXingSigns(
            signs: List<Pair<ChaoXingCourse, ChaoXingActiveItem>>,
            isHandled: (Long) -> Boolean
        ): List<Pair<ChaoXingCourse, ChaoXingActiveItem>> = signs.filter { (_, item) ->
            item.id > 0L &&
                item.isSignActivity() &&
                item.isActive() &&
                item.signDetail?.isAlreadySigned != true &&
                item.signDetail?.isExpired != true &&
                !isHandled(item.id)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoringAndSelf()
            return START_NOT_STICKY
        }

        // 校验开关与登录状态（畅课已登录，或开启学习通同步监听且学习通已登录）
        val canMonitorTronClass = prefs.isLoggedIn()
        val canMonitorChaoXing = prefs.isRollcallChaoXingMonitorEnabled() && chaoXingClient.isLoggedIn()
        if (!prefs.isRollcallMonitorEnabled() || (!canMonitorTronClass && !canMonitorChaoXing)) {
            Log.i(TAG, "Monitor disabled or not logged in, stopping service.")
            stopMonitoringAndSelf()
            return START_NOT_STICKY
        }

        val currentSession = RollcallScheduleHelper.getCurrentSession(this)
        if (currentSession == null) {
            Log.i(TAG, "Currently not in class. Scheduling next alarm and stopping service.")
            RollcallMonitorScheduler.scheduleNext(this)
            stopMonitoringAndSelf()
            return START_NOT_STICKY
        }

        // 处于上课时段，启动/保持前台服务常驻
        promoteToForeground(currentSession)
        acquireOrRenewCpuWakeLock()
        registerScreenOnReceiver()

        // 启动共享轮询源与课程边界监听
        startPolling(currentSession)
        if (intent?.action == ACTION_CHECK_NOW) {
            RollcallPollingSource.getInstance(this).refresh()
        }

        return START_STICKY
    }

    private fun promoteToForeground(session: ScheduledClassSession, statusHint: String? = null) {
        val notif = RollcallNotificationHelper.buildForegroundNotification(this, session, statusHint)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    RollcallNotificationHelper.NOTIFICATION_ID_FOREGROUND,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(RollcallNotificationHelper.NOTIFICATION_ID_FOREGROUND, notif)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground: ${e.message}", e)
        }
    }

    private fun updateForegroundNotification(session: ScheduledClassSession?, statusHint: String?) {
        val notif = RollcallNotificationHelper.buildForegroundNotification(this, session, statusHint)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(RollcallNotificationHelper.NOTIFICATION_ID_FOREGROUND, notif)
    }

    private fun startPolling(initialSession: ScheduledClassSession) {
        pollJob?.cancel()
        activeSession = initialSession
        val pollingSource = RollcallPollingSource.getInstance(this)

        pollJob = serviceScope.launch {
            // 1. 课表边界与课间 15 分钟状态守护（独立于共享网络退避与订阅激活状态）
            launch {
                while (isActive) {
                    try {
                        acquireOrRenewCpuWakeLock()
                        val now = System.currentTimeMillis()
                        val previousSession = activeSession
                        val session = RollcallScheduleHelper.getCurrentSession(this@RollcallMonitorService, now)

                        if (session != null) {
                            val sessionChanged = previousSession == null ||
                                previousSession.courseName != session.courseName ||
                                previousSession.startTimeMillis != session.startTimeMillis
                            activeSession = session
                            if (sessionChanged) {
                                updateForegroundNotification(session, null)
                                pollingSource.refresh()
                            }
                        } else {
                            activeSession = null
                            val nextSession = RollcallScheduleHelper.getNextUpcomingSessionToday(this@RollcallMonitorService, now)
                            val gapMinutes = if (nextSession != null) {
                                (nextSession.bufferStartTimeMillis - now) / (60 * 1000L)
                            } else 999L

                            if (nextSession != null && gapMinutes in 0..15) {
                                updateForegroundNotification(nextSession, "课间休息中 · 即将进入【${nextSession.courseName}】")
                            } else {
                                Log.i(TAG, "Class session finished. Scheduling next alarm.")
                                RollcallMonitorScheduler.scheduleNext(this@RollcallMonitorService)
                                stopMonitoringAndSelf()
                                break
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error in rollcall schedule watcher: ${e.message}", e)
                    }
                    delay(POLL_INTERVAL_MS)
                }
            }

            // 2. 订阅共享轮询流，消除与直播页面的双倍轮询
            launch {
                pollingSource.observe(
                    intervalMillis = POLL_INTERVAL_MS,
                    enabled = { activeSession != null && prefs.isRollcallMonitorEnabled() && prefs.isLoggedIn() }
                ).collect { res ->
                    val session = activeSession ?: return@collect
                    if (!res.isSuccess) {
                        Log.d(TAG, "fetchOngoingRollcalls returned failure: ${res.exceptionOrNull()?.message}")
                        return@collect
                    }
                    handleTronClassRollcalls(session, res.getOrDefault(emptyList()))
                }
            }

            // 3. 超星学习通低频防风控探测（单课锁定 + 50~60s 抖动 + 熔断保护）
            launch {
                while (isActive) {
                    val session = activeSession
                    if (session != null &&
                        prefs.isRollcallMonitorEnabled() &&
                        prefs.isRollcallChaoXingMonitorEnabled() &&
                        chaoXingClient.isLoggedIn()
                    ) {
                        try {
                            checkChaoXingSignsForSession(session)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "Error in ChaoXing low-freq poll: ${e.message}", e)
                        }
                    }
                    val jitter = ThreadLocalRandom.current().nextLong(0L, CHAOXING_POLL_JITTER_MS + 1L)
                    delay(CHAOXING_POLL_BASE_INTERVAL_MS + jitter)
                }
            }
        }
    }

    private suspend fun checkChaoXingSignsForSession(session: ScheduledClassSession) {
        if (ChaoXingRequestGuard.isCircuitOpen(this)) {
            Log.d(TAG, "ChaoXing circuit open, skipping probe.")
            return
        }

        val now = System.currentTimeMillis()
        var courses = cachedChaoXingCourses.ifEmpty {
            chaoXingPrefs.getCachedCourses().also {
                if (it.isNotEmpty()) cachedChaoXingCourses = it
            }
        }
        if (courses.isEmpty() && (now - lastChaoXingCourseFetchMs) >= CHAOXING_COURSE_CACHE_TTL_MS) {
            lastChaoXingCourseFetchMs = now
            val fetchRes = chaoXingClient.fetchCourses()
            if (fetchRes.isSuccess) {
                courses = fetchRes.getOrDefault(emptyList())
                cachedChaoXingCourses = courses
            }
        }

        val matched = selectChaoXingCoursesForSession(courses, session.courseName)
        if (matched.isEmpty() || ChaoXingRequestGuard.isCircuitOpen(this)) return

        val signsRes = chaoXingClient.fetchActiveSignsForCourses(
            courses = matched,
            maxCourses = CHAOXING_MAX_MATCHED_COURSES
        )
        if (!signsRes.isSuccess) return

        val actionable = selectActionableChaoXingSigns(signsRes.getOrDefault(emptyList())) { activeId ->
            prefs.isChaoXingSignHandled(activeId)
        }
        if (actionable.isEmpty()) return

        val activeJob = pollJob
        val shouldShowPopup = { activeJob?.isActive == true }
        for ((course, item) in actionable) {
            val activeId = item.id
            prefs.markChaoXingSignHandled(activeId)
            val cName = course.courseName.ifBlank { session.courseName }
            val typeName = item.resolvedSignTypeName
            Log.i(TAG, "Detected ChaoXing active sign: activeId=$activeId, course=$cName, type=$typeName")
            RollcallNotificationHelper.notifyChaoXingActiveSign(
                context = this,
                courseId = course.courseId,
                classId = course.classId,
                activeId = activeId,
                courseName = cName,
                signTypeName = typeName,
                otherId = item.otherId,
                shouldShowPopup = shouldShowPopup
            )
        }
    }

    private fun handleTronClassRollcalls(session: ScheduledClassSession, list: List<TronClassRollcall>) {
        val actionable = selectActionableRollcalls(list) { prefs.isRollcallHandled(it) }
        if (actionable.isEmpty()) return

        val activeJob = pollJob
        val shouldShowPopup = { activeJob?.isActive == true }

        for (rollcall in actionable) {
            val rollcallId = rollcall.getEffectiveId()
            val normType = rollcall.getNormalizedType()
            val cName = rollcall.courseName ?: session.courseName

            val isAutoSignEnabled = prefs.isRollcallAutoSignEnabled() && DeveloperModeManager.isDeveloperMode(this)
            val delaySeconds = prefs.getRollcallAutoSignDelaySeconds()

            if (!isAutoSignEnabled) {
                // 仅监听模式：立即标记已处理并通知用户手动签到
                prefs.markRollcallHandled(rollcallId)
                Log.i(TAG, "Auto-sign disabled, notifying user for manual rollcall: id=$rollcallId, type=$normType")
                when (normType) {
                    "number" -> RollcallNotificationHelper.notifyNumberRollcall(
                        this,
                        rollcallId,
                        cName,
                        shouldShowPopup = shouldShowPopup
                    )
                    "radar" -> {
                        val room = rollcall.classroom ?: session.room
                        RollcallNotificationHelper.notifyRadarResult(
                            context = this,
                            rollcallId = rollcallId,
                            courseName = cName,
                            building = null,
                            success = false,
                            message = "已检测到课堂雷达签到，点击手动选择点位签到",
                            classroom = room,
                            latitude = rollcall.latitude,
                            longitude = rollcall.longitude,
                            scope = rollcall.scope,
                            shouldShowPopup = shouldShowPopup
                        )
                    }
                    "qr_code" -> RollcallNotificationHelper.notifyQrRollcall(
                        this,
                        rollcallId,
                        cName,
                        shouldShowPopup = shouldShowPopup
                    )
                    "self_registration" -> RollcallNotificationHelper.notifyGenericRollcall(
                        this,
                        rollcallId,
                        cName,
                        "自主签到",
                        shouldShowPopup = shouldShowPopup
                    )
                    else -> RollcallNotificationHelper.notifyGenericRollcall(
                        this,
                        rollcallId,
                        cName,
                        rollcall.getTypeDisplayName(),
                        shouldShowPopup = shouldShowPopup
                    )
                }
            } else {
                // 不支持自动提交的类型：直接回退手动提醒（不做无意义的探测等待）
                val supportsAutoSign = normType in setOf("radar", "number", "qr_code", "self_registration")
                if (!supportsAutoSign) {
                    prefs.markRollcallHandled(rollcallId)
                    RollcallNotificationHelper.notifyGenericRollcall(
                        context = this,
                        rollcallId = rollcallId,
                        courseName = cName,
                        typeDesc = rollcall.getTypeDisplayName(),
                        shouldShowPopup = shouldShowPopup
                    )
                    continue
                }

                // 自动签到模式：避免并发重复派发；仅在成功或终态/达到最大重试次数后才永久标记已处理
                if (!inFlightRollcalls.add(rollcallId)) {
                    continue
                }
                val attempt = retryAttempts[rollcallId] ?: 0
                val strategy = prefs.getRollcallAutoSignStrategy()
                val isHeadcount = TronClassPreferences.isHeadcountStrategy(strategy)
                val waitSec = if (attempt == 0 && !isHeadcount) delaySeconds else 0
                val strategyText = prefs.getRollcallAutoSignStrategyDisplayName()

                val waitHint = when {
                    waitSec > 0 -> "${waitSec}秒后"
                    isHeadcount -> "满足条件 ($strategyText) 后"
                    else -> "立即"
                }

                Log.i(TAG, "Detected ongoing rollcall: id=$rollcallId, type=$normType, course=$cName, attempt=${attempt + 1}/$MAX_AUTO_SIGN_ATTEMPTS, waitSec=$waitSec, strategy=$strategyText")
                if (attempt == 0) {
                    updateForegroundNotification(
                        session,
                        "检测到【$cName】签到，将在 $waitHint 尝试自动提交"
                    )
                    RollcallNotificationHelper.notifyAutoSignDetectedBanner(
                        context = this,
                        rollcallId = rollcallId,
                        courseName = cName,
                        rollcallType = normType,
                        typeDisplayName = rollcall.getTypeDisplayName(),
                        waitHint = waitHint,
                        classroom = rollcall.classroom ?: session.room,
                        latitude = rollcall.latitude,
                        longitude = rollcall.longitude,
                        scope = rollcall.scope,
                        shouldShowPopup = shouldShowPopup
                    )
                }
                serviceScope.launch {
                    try {
                        if (waitSec > 0) {
                            delay(waitSec * 1000L)
                        }
                        if (!isActive) return@launch

                        // 若开启人数/比例等待策略：轮询等待班级出勤达到条件（最多轮询 90 秒，避免签到已关闭）
                        if (isHeadcount && attempt == 0) {
                            val waitStart = System.currentTimeMillis()
                            var conditionMet = false
                            val targetCount = when (strategy) {
                                TronClassPreferences.STRATEGY_COUNT_1 -> 1
                                TronClassPreferences.STRATEGY_COUNT_10 -> 10
                                else -> 0
                            }
                            val targetRatio = when (strategy) {
                                TronClassPreferences.STRATEGY_HALF_CLASS -> 50
                                TronClassPreferences.STRATEGY_CUSTOM_RATIO -> prefs.getRollcallAutoSignCustomRatio()
                                else -> 0
                            }

                            while (isActive && (System.currentTimeMillis() - waitStart) < 90_000L) {
                                val currentSid = cn.edu.usst.jwgl.data.local.AuthPreferences(this@RollcallMonitorService).getStudentId()
                                val manuallyConfirmed = prefs.isRollcallHandled(rollcallId) || (
                                    currentSid.isNotBlank() &&
                                        cn.edu.usst.jwgl.data.local.RollcallSubmissionStore(this@RollcallMonitorService)
                                            .state(currentSid, rollcallId) == "confirmed"
                                    )
                                if (manuallyConfirmed) {
                                    break
                                }
                                val stats = tronClassClient.fetchRollcallAttendanceStats(rollcallId).getOrNull()
                                if (stats != null) {
                                    val present = stats.presentCount
                                    val total = stats.totalCount
                                    val met = when {
                                        targetCount > 0 -> {
                                            if (total in 1 until targetCount) {
                                                present >= total || present >= (total + 1) / 2
                                            } else {
                                                present >= targetCount
                                            }
                                        }
                                        targetRatio > 0 -> {
                                            if (total > 0) {
                                                (present * 100 / total) >= targetRatio
                                            } else {
                                                present >= 1
                                            }
                                        }
                                        else -> present >= 1
                                    }

                                    if (met) {
                                        conditionMet = true
                                        Log.i(TAG, "Headcount strategy condition met for rollcall $rollcallId: $present/$total signed (strategy=$strategyText)")
                                        updateForegroundNotification(
                                            session,
                                            "【$cName】已有 $present 人签到，正在跟进自动提交..."
                                        )
                                        break
                                    }
                                    updateForegroundNotification(
                                        session,
                                        "【$cName】等待满足签到条件 ($strategyText，当前已签 $present 人)..."
                                    )
                                }
                                delay(3000L)
                            }
                            if (!isActive) return@launch
                            if (!conditionMet) {
                                Log.i(TAG, "Headcount strategy wait timeout for rollcall $rollcallId, proceeding with submission anyway")
                            }
                        }

                        // 若用户在等待期间通过“手动签到”提前完成，则不再重复自动提交
                        val studentIdBeforeSubmit = cn.edu.usst.jwgl.data.local.AuthPreferences(this@RollcallMonitorService).getStudentId()
                        val alreadyDoneManually = prefs.isRollcallHandled(rollcallId) || (
                            studentIdBeforeSubmit.isNotBlank() &&
                                cn.edu.usst.jwgl.data.local.RollcallSubmissionStore(this@RollcallMonitorService)
                                    .state(studentIdBeforeSubmit, rollcallId) == "confirmed"
                            )
                        if (alreadyDoneManually) {
                            Log.i(TAG, "Rollcall $rollcallId already completed manually during wait window, skipping auto-submit")
                            RollcallNotificationHelper.cancelAutoSignDetectedBanner(this@RollcallMonitorService, rollcallId)
                            prefs.markRollcallHandled(rollcallId)
                            retryAttempts.remove(rollcallId)
                            return@launch
                        }

                        val isFinalAttempt = attempt + 1 >= MAX_AUTO_SIGN_ATTEMPTS
                        val isTerminal = when (normType) {
                            "radar" -> handleRadarAutoSign(rollcall, session, isFinalAttempt, shouldShowPopup)
                            "number" -> handleNumberAutoSign(rollcall, session, isFinalAttempt, shouldShowPopup)
                            "qr_code" -> handleQrCodeAutoSign(rollcall, session, isFinalAttempt, shouldShowPopup)
                            "self_registration" -> handleSelfRegistrationAutoSign(rollcall, session, isFinalAttempt, shouldShowPopup)
                            else -> true
                        }

                        if (isTerminal || isFinalAttempt) {
                            prefs.markRollcallHandled(rollcallId)
                            retryAttempts.remove(rollcallId)
                        } else {
                            retryAttempts[rollcallId] = attempt + 1
                            Log.i(TAG, "Auto-sign attempt ${attempt + 1} for rollcall $rollcallId not terminal, will retry on next poll")
                        }
                    } finally {
                        inFlightRollcalls.remove(rollcallId)
                    }
                }
            }
        }
    }

    private fun isPrimaryAccountSuccess(
        results: List<cn.edu.usst.jwgl.data.model.MultiAccountRollcallResult>,
        currentStudentId: String
    ): Boolean = cn.edu.usst.jwgl.data.model.MultiAccountRollcallResult.isPrimaryAccountSuccess(results, currentStudentId)

    private suspend fun handleQrCodeAutoSign(
        rollcall: TronClassRollcall,
        currentSession: ScheduledClassSession,
        isFinalAttempt: Boolean,
        shouldShowPopup: () -> Boolean
    ): Boolean {
        val rollcallId = rollcall.getEffectiveId()
        val matchedSession = RollcallScheduleHelper.findMatchingClassSession(this, rollcall.courseName) ?: currentSession
        val cName = rollcall.courseName ?: matchedSession.courseName
        val room = rollcall.classroom ?: matchedSession.room

        Log.i(TAG, "Triggering Smart Live QR auto sign for $cName in $room")

        val success = smartLiveQrManager.tryAutoSignFromLive(
            rollcall = rollcall,
            courseName = cName,
            classroom = room,
            notifyOnFailure = false
        )

        if (success) {
            RollcallNotificationHelper.cancelAutoSignDetectedBanner(this, rollcallId)
            updateForegroundNotification(
                matchedSession,
                "已自动大屏扫码签到【$cName】"
            )
            return true
        }

        if (isFinalAttempt) {
            // 降级回退：大屏未捕获到或无直播，消隐探测横幅后按当前提醒模式回退手动扫码
            Log.w(TAG, "Smart Live QR failed or unavailable on final attempt, fallback to manual QR alert")
            RollcallNotificationHelper.cancelAutoSignDetectedBanner(this, rollcallId)
            RollcallNotificationHelper.notifyQrRollcall(
                context = this,
                rollcallId = rollcallId,
                courseName = cName,
                stage = RollcallAlertStage.AUTO_SIGN_FAILED_FALLBACK,
                shouldShowPopup = shouldShowPopup
            )
        } else {
            Log.i(TAG, "Smart Live QR not captured yet for rollcall $rollcallId, will retry on next poll")
        }
        return false
    }

    private suspend fun handleRadarAutoSign(
        rollcall: TronClassRollcall,
        currentSession: ScheduledClassSession,
        isFinalAttempt: Boolean,
        shouldShowPopup: () -> Boolean
    ): Boolean {
        val rollcallId = rollcall.getEffectiveId()
        val matchedSession = RollcallScheduleHelper.findMatchingClassSession(this, rollcall.courseName) ?: currentSession
        val room = rollcall.classroom ?: matchedSession.room
        val courseName = rollcall.courseName ?: matchedSession.courseName

        val matchedBuilding = CoordinateUtils.findMatchingBuilding(room)
            ?: CoordinateUtils.findMatchingBuilding(courseName)

        // 1. 按优先级构造常规候选点位（教师下发参考点 -> 本机新鲜高精度定位 -> 精准匹配教学楼）
        val primaryCandidates = mutableListOf<TronClassClient.RadarCandidatePoint>()
        val teacherLat = rollcall.latitude
        val teacherLng = rollcall.longitude
        if (teacherLat != null && teacherLng != null && !(teacherLat == 0.0 && teacherLng == 0.0)) {
            val jitterLat = (Math.random() - 0.5) * 0.00003
            val jitterLng = (Math.random() - 0.5) * 0.00003
            primaryCandidates.add(
                TronClassClient.RadarCandidatePoint(
                    latitude = teacherLat + jitterLat,
                    longitude = teacherLng + jitterLng,
                    accuracy = 5.0,
                    label = "教师基准点"
                )
            )
        }

        val deviceLoc = getFreshDeviceLocation()
        if (deviceLoc != null) {
            val (gcjLat, gcjLng) = CoordinateUtils.wgs84ToGcj02(deviceLoc.latitude, deviceLoc.longitude)
            primaryCandidates.add(
                TronClassClient.RadarCandidatePoint(
                    latitude = gcjLat,
                    longitude = gcjLng,
                    accuracy = deviceLoc.accuracy.toDouble(),
                    label = "本机定位"
                )
            )
        }

        if (matchedBuilding != null) {
            primaryCandidates.add(
                TronClassClient.RadarCandidatePoint(
                    latitude = matchedBuilding.latitude,
                    longitude = matchedBuilding.longitude,
                    accuracy = 15.0,
                    label = "教学楼(${matchedBuilding.name})"
                )
            )
        }

        Log.i(
            TAG,
            "Auto signing radar rollcall $rollcallId: candidates=${primaryCandidates.joinToString(" -> ") { it.label }.ifBlank { "none" }} (trilateration fallback enabled)"
        )

        // 2. 常规点位优先，仅在明确超距并带回距离时复用已有观测点进入三点定位回退
        val results = tronClassClient.batchAnswerRadarWithTrilaterationFallback(
            rollcallId = rollcallId,
            primaryCandidates = primaryCandidates,
            classroom = room,
            courseName = courseName,
            allowTrilaterationFallback = true
        )

        val currentStudentId = cn.edu.usst.jwgl.data.local.AuthPreferences(this).getStudentId()
        var primarySuccess = isPrimaryAccountSuccess(results, currentStudentId)

        val successCount = results.count { it.success }
        val totalCount = results.size
        var verificationRejected = false

        // 权威查册复核闭环
        if (primarySuccess && currentStudentId.isNotBlank()) {
            val verification = tronClassClient.verifyRollcallAttendance(
                rollcallId = rollcallId,
                studentId = currentStudentId,
                expectedAccount = currentStudentId,
                courseIdHint = rollcall.courseId
            )
            Log.i(TAG, "Radar sign verifyRollcallAttendance result for $currentStudentId: $verification")
            if (verification == cn.edu.usst.jwgl.data.network.RollcallVerificationState.NOT_CONFIRMED) {
                primarySuccess = false
                verificationRejected = true
                cn.edu.usst.jwgl.data.local.RollcallSubmissionStore(this).invalidateConfirmed(currentStudentId, rollcallId)
            }
        }

        val details = if (verificationRejected) {
            "服务端花名册未检测到本人签到记录，请重新签到"
        } else if (results.size > 1) {
            "$successCount/$totalCount 个账号签到成功"
        } else {
            results.firstOrNull()?.message ?: if (primarySuccess) "签到成功" else "签到未成功"
        }

        val storeState = if (currentStudentId.isNotBlank()) {
            cn.edu.usst.jwgl.data.local.RollcallSubmissionStore(this).state(currentStudentId, rollcallId)
        } else null
        val isTerminal = primarySuccess || storeState == "rejected" || isFinalAttempt

        // 3. 仅在成功、终态拒绝或最终尝试时消隐探测横幅并发送对应结果通知
        if (isTerminal) {
            RollcallNotificationHelper.cancelAutoSignDetectedBanner(this, rollcallId)
            RollcallNotificationHelper.notifyRadarResult(
                context = this,
                rollcallId = rollcallId,
                courseName = courseName,
                building = matchedBuilding,
                success = primarySuccess,
                message = details,
                classroom = room,
                latitude = rollcall.latitude,
                longitude = rollcall.longitude,
                scope = rollcall.scope,
                stage = if (primarySuccess) RollcallAlertStage.AUTO_SIGN_SUCCEEDED else RollcallAlertStage.AUTO_SIGN_FAILED_FALLBACK,
                shouldShowPopup = shouldShowPopup
            )
        }

        // 4. 更新前台服务常驻通知状态
        if (primarySuccess) {
            updateForegroundNotification(
                matchedSession,
                "已自动签到【$courseName】(${matchedBuilding?.name ?: "智能定位"})"
            )
        }
        return isTerminal
    }

    /**
     * 数字口令签到自动化处理：
     * 尝试从服务端 student_rollcalls 接口读取 number_code（学校自部署实例可能未修复泄露），
     * 如果成功取到 4 位码则自动提交（含多账号批量），否则退回通知让用户手动输入。
     */
    private suspend fun handleNumberAutoSign(
        rollcall: TronClassRollcall,
        currentSession: ScheduledClassSession,
        isFinalAttempt: Boolean,
        shouldShowPopup: () -> Boolean
    ): Boolean {
        val rollcallId = rollcall.getEffectiveId()
        val courseName = rollcall.courseName ?: currentSession.courseName

        // 1. 尝试从服务端读取数字签到码
        Log.i(TAG, "Attempting to lookup number code for rollcall $rollcallId ($courseName)")
        val lookupResult = tronClassClient.lookupNumberRollcallCode(rollcall)

        if (lookupResult !is NumberCodeLookupResult.Found) {
            val unavailable = lookupResult as? NumberCodeLookupResult.Unavailable
            val reason = unavailable?.message ?: "未知原因"
            val isTerminalLookup = unavailable?.reason in setOf(
                cn.edu.usst.jwgl.data.model.NumberCodeLookupFailure.NoActiveRollcall,
                cn.edu.usst.jwgl.data.model.NumberCodeLookupFailure.Forbidden,
                cn.edu.usst.jwgl.data.model.NumberCodeLookupFailure.Conflict
            )
            Log.i(TAG, "Number code not available for rollcall $rollcallId: $reason (terminal=$isTerminalLookup, isFinalAttempt=$isFinalAttempt)")
            if (isTerminalLookup || isFinalAttempt) {
                RollcallNotificationHelper.cancelAutoSignDetectedBanner(this, rollcallId)
                RollcallNotificationHelper.notifyNumberRollcall(
                    context = this,
                    rollcallId = rollcallId,
                    courseName = courseName,
                    stage = RollcallAlertStage.AUTO_SIGN_FAILED_FALLBACK,
                    shouldShowPopup = shouldShowPopup
                )
                return true
            }
            return false
        }

        // 2. 成功获取数字码，自动提交
        val numberCode = lookupResult.code
        Log.i(TAG, "Got number code [$numberCode] for rollcall $rollcallId, auto-submitting")

        val results = tronClassClient.batchAnswerNumberRollcall(rollcallId, numberCode)

        val currentStudentId = cn.edu.usst.jwgl.data.local.AuthPreferences(this).getStudentId()
        var primarySuccess = isPrimaryAccountSuccess(results, currentStudentId)
        val successCount = results.count { it.success }
        val totalCount = results.size
        var verificationRejected = false

        // 权威查册复核闭环
        if (primarySuccess && currentStudentId.isNotBlank()) {
            val verification = tronClassClient.verifyRollcallAttendance(
                rollcallId = rollcallId,
                studentId = currentStudentId,
                expectedAccount = currentStudentId,
                courseIdHint = rollcall.courseId
            )
            Log.i(TAG, "Number sign verifyRollcallAttendance result for $currentStudentId: $verification")
            if (verification == cn.edu.usst.jwgl.data.network.RollcallVerificationState.NOT_CONFIRMED) {
                primarySuccess = false
                verificationRejected = true
                cn.edu.usst.jwgl.data.local.RollcallSubmissionStore(this).invalidateConfirmed(currentStudentId, rollcallId)
            }
        }

        val details = if (verificationRejected) {
            "服务端花名册未检测到本人签到记录，请重新签到"
        } else if (results.size > 1) {
            "$successCount/$totalCount 个账号签到成功"
        } else {
            results.firstOrNull()?.message ?: if (primarySuccess) "签到成功" else "签到未成功"
        }

        val storeState = if (currentStudentId.isNotBlank()) {
            cn.edu.usst.jwgl.data.local.RollcallSubmissionStore(this).state(currentStudentId, rollcallId)
        } else null
        val isTerminal = primarySuccess || storeState == "rejected" || isFinalAttempt

        // 3. 仅在成功、终态拒绝或最终尝试时消隐探测横幅并发送结果通知
        if (isTerminal) {
            RollcallNotificationHelper.cancelAutoSignDetectedBanner(this, rollcallId)
            RollcallNotificationHelper.notifyNumberAutoResult(
                context = this,
                rollcallId = rollcallId,
                courseName = courseName,
                success = primarySuccess,
                message = details,
                shouldShowPopup = shouldShowPopup
            )
        }

        // 4. 更新前台服务常驻通知
        if (primarySuccess) {
            updateForegroundNotification(
                currentSession,
                "已自动签到【$courseName】(数字口令 $numberCode)"
            )
        }
        return isTerminal
    }

    /**
     * 自主签到自动化处理：
     * 无需坐标、无需口令、无需扫码，直接向畅课服务器提交自主点名请求，
     * 并在完成后调用 recheckOnCallFine 进行权威复核闭环。
     */
    private suspend fun handleSelfRegistrationAutoSign(
        rollcall: TronClassRollcall,
        currentSession: ScheduledClassSession,
        isFinalAttempt: Boolean,
        shouldShowPopup: () -> Boolean
    ): Boolean {
        val rollcallId = rollcall.getEffectiveId()
        val courseName = rollcall.courseName ?: currentSession.courseName
        Log.i(TAG, "Triggering Self-Registration auto sign for $courseName (rollcallId=$rollcallId)")

        // 1. 批量提交自主签到
        val results = tronClassClient.batchAnswerSelfRegistrationRollcall(rollcallId)
        val currentStudentId = cn.edu.usst.jwgl.data.local.AuthPreferences(this).getStudentId()
        val primaryResult = results.find { it.studentId == currentStudentId } ?: results.firstOrNull()
        var primarySuccess = isPrimaryAccountSuccess(results, currentStudentId)
        var verificationRejected = false

        // 权威查册复核闭环
        if (primarySuccess && currentStudentId.isNotBlank()) {
            val verification = tronClassClient.verifyRollcallAttendance(
                rollcallId = rollcallId,
                studentId = currentStudentId,
                expectedAccount = currentStudentId,
                courseIdHint = rollcall.courseId
            )
            Log.i(TAG, "Self-Registration sign verifyRollcallAttendance result for $currentStudentId: $verification")
            if (verification == cn.edu.usst.jwgl.data.network.RollcallVerificationState.NOT_CONFIRMED) {
                primarySuccess = false
                verificationRejected = true
                cn.edu.usst.jwgl.data.local.RollcallSubmissionStore(this).invalidateConfirmed(currentStudentId, rollcallId)
            }
        }

        val successCount = results.count { it.success }
        val totalCount = results.size
        val details = if (verificationRejected) {
            "服务端花名册未检测到本人签到记录，请重新签到"
        } else if (results.size > 1) {
            "$successCount/$totalCount 个账号签到成功"
        } else {
            primaryResult?.message ?: if (primarySuccess) "签到成功" else "签到未成功"
        }

        val storeState = if (currentStudentId.isNotBlank()) {
            cn.edu.usst.jwgl.data.local.RollcallSubmissionStore(this).state(currentStudentId, rollcallId)
        } else null
        val isTerminal = primarySuccess || storeState == "rejected" || isFinalAttempt

        // 3. 仅在成功、终态拒绝或最终尝试时消隐探测横幅并发送对应结果通知
        if (isTerminal) {
            RollcallNotificationHelper.cancelAutoSignDetectedBanner(this, rollcallId)
            RollcallNotificationHelper.notifySelfRegistrationResult(
                context = this,
                rollcallId = rollcallId,
                courseName = courseName,
                success = primarySuccess,
                message = details,
                shouldShowPopup = shouldShowPopup
            )
        }

        // 4. 更新前台服务常驻通知
        if (primarySuccess) {
            updateForegroundNotification(
                currentSession,
                "已自动自主签到【$courseName】"
            )
        }
        return isTerminal
    }

    private fun getFreshDeviceLocation(): Location? {
        val finePerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarsePerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (finePerm != PackageManager.PERMISSION_GRANTED && coarsePerm != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
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
            } catch (_: Exception) {}
        }
        return bestLoc
    }

    private fun acquireOrRenewCpuWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = cpuWakeLock ?: pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "usst_jwgl:RollcallMonitorCpuLock"
            ).apply {
                setReferenceCounted(false)
                cpuWakeLock = this
            }
            lock.acquire(WAKE_LOCK_RENEW_TIMEOUT_MS)
        }.onFailure {
            Log.w(TAG, "Failed to acquire CPU wake lock: ${it.message}")
        }
    }

    private fun releaseCpuWakeLock() {
        runCatching {
            val lock = cpuWakeLock
            cpuWakeLock = null
            if (lock?.isHeld == true) {
                lock.release()
            }
        }
    }

    private fun registerScreenOnReceiver() {
        if (screenOnReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON && activeSession != null) {
                    RollcallPollingSource.getInstance(this@RollcallMonitorService).refresh()
                }
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                receiver,
                IntentFilter(Intent.ACTION_SCREEN_ON),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenOnReceiver = receiver
        }.onFailure {
            Log.w(TAG, "Failed to register screen-on receiver: ${it.message}")
        }
    }

    private fun unregisterScreenOnReceiver() {
        val receiver = screenOnReceiver ?: return
        screenOnReceiver = null
        runCatching {
            unregisterReceiver(receiver)
        }
    }

    private fun stopMonitoringAndSelf() {
        RollcallOverlayAlert.dismiss()
        activeSession = null
        pollJob?.cancel()
        pollJob = null
        unregisterScreenOnReceiver()
        releaseCpuWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        RollcallOverlayAlert.dismiss()
        pollJob?.cancel()
        unregisterScreenOnReceiver()
        releaseCpuWakeLock()
        serviceScope.cancel()
        Log.i(TAG, "RollcallMonitorService destroyed.")
    }
}
