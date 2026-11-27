package cn.edu.usst.jwgl.service

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import cn.edu.usst.jwgl.data.local.ChaoXingPreferences
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.ChaoXingActiveItem
import cn.edu.usst.jwgl.data.model.ChaoXingCourse
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.data.network.ChaoXingClient
import cn.edu.usst.jwgl.data.network.ChaoXingRequestGuard
import cn.edu.usst.jwgl.data.network.RollcallPollingSource
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.devaddon.DevAddonManager
import cn.edu.usst.jwgl.devapi.AutoSignCallbacks
import cn.edu.usst.jwgl.devapi.AutoSignRequest
import cn.edu.usst.jwgl.devapi.HostRollcallInfo
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

        val autoSignExt = DevAddonManager.getEntry(this).autoSignExtension

        for (rollcall in actionable) {
            val rollcallId = rollcall.getEffectiveId()
            val normType = rollcall.getNormalizedType()
            val cName = rollcall.courseName ?: session.courseName

            val isAutoSignEnabled = prefs.isRollcallAutoSignEnabled() && autoSignExt.isAutoSignAvailable()

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
                val hostRollcall = HostRollcallInfo(
                    id = rollcallId,
                    courseId = rollcall.courseId,
                    courseName = rollcall.courseName,
                    normalizedType = normType,
                    typeDisplayName = rollcall.getTypeDisplayName(),
                    classroom = rollcall.classroom,
                    latitude = rollcall.latitude,
                    longitude = rollcall.longitude,
                    scope = rollcall.scope,
                    numberCode = rollcall.numberCode,
                    isExpired = rollcall.isExpired
                )
                val request = AutoSignRequest(
                    rollcall = hostRollcall,
                    sessionCourseName = session.courseName,
                    sessionRoom = session.room,
                    attempt = attempt,
                    maxAttempts = MAX_AUTO_SIGN_ATTEMPTS
                )
                val callbacks = object : AutoSignCallbacks {
                    override fun shouldShowPopup(): Boolean = shouldShowPopup()
                    override fun onForegroundStatus(statusText: String) {
                        updateForegroundNotification(session, statusText)
                    }
                }
                serviceScope.launch {
                    try {
                        val outcome = autoSignExt.executeAutoSign(request, callbacks)
                        if (!outcome.handledByAutoSign) {
                            prefs.markRollcallHandled(rollcallId)
                            RollcallNotificationHelper.notifyGenericRollcall(
                                context = this@RollcallMonitorService,
                                rollcallId = rollcallId,
                                courseName = cName,
                                typeDesc = rollcall.getTypeDisplayName(),
                                shouldShowPopup = shouldShowPopup
                            )
                        } else if (outcome.isTerminal || attempt + 1 >= MAX_AUTO_SIGN_ATTEMPTS) {
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
