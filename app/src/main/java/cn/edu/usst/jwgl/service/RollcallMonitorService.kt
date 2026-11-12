package cn.edu.usst.jwgl.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.NumberCodeLookupResult
import cn.edu.usst.jwgl.data.model.TronClassRollcall
import cn.edu.usst.jwgl.data.network.TronClassClient
import cn.edu.usst.jwgl.util.CoordinateUtils
import cn.edu.usst.jwgl.util.RollcallNotificationHelper
import cn.edu.usst.jwgl.util.RollcallScheduleHelper
import cn.edu.usst.jwgl.util.ScheduledClassSession
import cn.edu.usst.jwgl.worker.RollcallMonitorScheduler
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

class RollcallMonitorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null
    private val inFlightRollcalls = ConcurrentHashMap.newKeySet<Long>()
    private val retryAttempts = ConcurrentHashMap<Long, Int>()
    private val inFlightChaoXingSigns = ConcurrentHashMap.newKeySet<Long>()
    private val chaoXingRetryAttempts = ConcurrentHashMap<Long, Int>()

    private val tronClassClient by lazy { TronClassClient(this) }
    private val chaoXingClient by lazy { cn.edu.usst.jwgl.data.network.ChaoXingClient(this) }
    private val prefs by lazy { TronClassPreferences.getInstance(this) }
    private val smartLiveQrManager by lazy { cn.edu.usst.jwgl.util.SmartLiveQrRollcallManager(this) }

    @Volatile
    private var cachedChaoXingCourses: List<cn.edu.usst.jwgl.data.model.ChaoXingCourse> = emptyList()
    @Volatile
    private var lastChaoXingCourseFetchMs: Long = 0L

    companion object {
        private const val TAG = "RollcallMonitorService"
        const val ACTION_START = "cn.edu.usst.jwgl.action.START_MONITOR"
        const val ACTION_STOP = "cn.edu.usst.jwgl.action.STOP_MONITOR"
        const val ACTION_CHECK_NOW = "cn.edu.usst.jwgl.action.CHECK_NOW"

        private const val POLL_INTERVAL_MS = 20_000L // 20 秒轮询一次
        private const val MAX_AUTO_SIGN_ATTEMPTS = 3
        private const val CHAOXING_COURSE_CACHE_TTL_MS = 30 * 60 * 1000L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoringAndSelf()
            return START_NOT_STICKY
        }

        // 校验开关与登录状态（畅课或超星任一已登录即可运行）
        if (!prefs.isRollcallMonitorEnabled() || (!prefs.isLoggedIn() && !chaoXingClient.isLoggedIn())) {
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

        // 启动轮询协程
        startPolling(currentSession)

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
        pollJob = serviceScope.launch {
            var activeSession: ScheduledClassSession? = initialSession

            while (isActive) {
                try {
                    val now = System.currentTimeMillis()
                    val session = RollcallScheduleHelper.getCurrentSession(this@RollcallMonitorService, now)
                    activeSession = session

                    if (session == null) {
                        // 当前课程及其缓冲区已结束
                        val nextSession = RollcallScheduleHelper.getNextUpcomingSessionToday(this@RollcallMonitorService, now)
                        val gapMinutes = if (nextSession != null) {
                            (nextSession.bufferStartTimeMillis - now) / (60 * 1000L)
                        } else 999L

                        if (nextSession != null && gapMinutes in 0..15) {
                            // 课间休息 <= 15 分钟，保持监听并更新前台常驻通知
                            updateForegroundNotification(nextSession, "课间休息中 · 即将进入【${nextSession.courseName}】")
                            delay(POLL_INTERVAL_MS)
                            continue
                        } else {
                            // 上课时段结束，安排下次闹钟并退出
                            Log.i(TAG, "Class session finished. Scheduling next alarm.")
                            RollcallMonitorScheduler.scheduleNext(this@RollcallMonitorService)
                            stopMonitoringAndSelf()
                            break
                        }
                    }

                    // 检查签到
                    checkRollcallsOnce(session)

                } catch (e: Exception) {
                    Log.w(TAG, "Error in rollcall monitor loop: ${e.message}", e)
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun checkRollcallsOnce(session: ScheduledClassSession) {
        if (prefs.isLoggedIn()) {
            checkTronClassRollcalls(session)
        }
        if (chaoXingClient.isLoggedIn()) {
            checkChaoXingLocationSigns(session)
        }
    }

    private suspend fun checkTronClassRollcalls(session: ScheduledClassSession) {
        val res = tronClassClient.fetchOngoingRollcalls()
        if (!res.isSuccess) {
            Log.d(TAG, "fetchOngoingRollcalls returned failure: ${res.exceptionOrNull()?.message}")
            return
        }

        val list = res.getOrDefault(emptyList())
        if (list.isEmpty()) return

        for (rollcall in list) {
            val rollcallId = rollcall.getEffectiveId()
            if (rollcallId <= 0L) continue

            // 判断是否已处理
            if (prefs.isRollcallHandled(rollcallId)) {
                continue
            }

            val normType = rollcall.getNormalizedType()
            val cName = rollcall.courseName ?: session.courseName

            val isAutoSignEnabled = prefs.isRollcallAutoSignEnabled()
            val delaySeconds = prefs.getRollcallAutoSignDelaySeconds().coerceIn(5, 30)

            if (!isAutoSignEnabled) {
                // 仅监听模式：立即标记已处理并通知用户手动签到
                prefs.markRollcallHandled(rollcallId)
                Log.i(TAG, "Auto-sign disabled, notifying user for manual rollcall: id=$rollcallId, type=$normType")
                when (normType) {
                    "number" -> RollcallNotificationHelper.notifyNumberRollcall(this, rollcallId, cName)
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
                            scope = rollcall.scope
                        )
                    }
                    "qr_code" -> RollcallNotificationHelper.notifyQrRollcall(this, rollcallId, cName)
                    "self_registration" -> RollcallNotificationHelper.notifyGenericRollcall(
                        this,
                        rollcallId,
                        cName,
                        "自主签到"
                    )
                    else -> RollcallNotificationHelper.notifyGenericRollcall(
                        this,
                        rollcallId,
                        cName,
                        rollcall.getTypeDisplayName()
                    )
                }
            } else {
                // 自动签到模式：避免并发重复派发；仅在成功或终态/达到最大重试次数后才永久标记已处理
                if (!inFlightRollcalls.add(rollcallId)) {
                    continue
                }
                val attempt = retryAttempts[rollcallId] ?: 0
                val waitSec = if (attempt == 0) delaySeconds else 0

                Log.i(TAG, "Detected ongoing rollcall: id=$rollcallId, type=$normType, course=$cName, attempt=${attempt + 1}/$MAX_AUTO_SIGN_ATTEMPTS, wait=${waitSec}s")
                if (attempt == 0) {
                    updateForegroundNotification(
                        session,
                        "检测到【$cName】签到，将在 ${delaySeconds} 秒后尝试自动提交"
                    )
                }
                serviceScope.launch {
                    try {
                        if (waitSec > 0) {
                            delay(waitSec * 1000L)
                        }
                        if (!isActive) return@launch

                        val isFinalAttempt = attempt + 1 >= MAX_AUTO_SIGN_ATTEMPTS
                        val isTerminal = when (normType) {
                            "radar" -> handleRadarAutoSign(rollcall, session, isFinalAttempt)
                            "number" -> handleNumberAutoSign(rollcall, session, isFinalAttempt)
                            "qr_code" -> handleQrCodeAutoSign(rollcall, session, isFinalAttempt)
                            "self_registration" -> handleSelfRegistrationAutoSign(rollcall, session, isFinalAttempt)
                            else -> {
                                RollcallNotificationHelper.notifyGenericRollcall(
                                    this@RollcallMonitorService,
                                    rollcallId,
                                    cName,
                                    rollcall.getTypeDisplayName()
                                )
                                true
                            }
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
    ): Boolean {
        if (results.isEmpty()) return false
        if (currentStudentId.isNotBlank()) {
            results.find { it.studentId == currentStudentId }?.let { return it.success }
        }
        return results.first().success
    }

    private suspend fun handleQrCodeAutoSign(
        rollcall: TronClassRollcall,
        currentSession: ScheduledClassSession,
        isFinalAttempt: Boolean
    ): Boolean {
        val rollcallId = rollcall.getEffectiveId()
        val matchedSession = RollcallScheduleHelper.findMatchingClassSession(this, rollcall.courseName) ?: currentSession
        val cName = rollcall.courseName ?: matchedSession.courseName
        val room = rollcall.classroom ?: matchedSession.room

        Log.i(TAG, "Triggering Smart Live QR auto sign for $cName in $room")

        val success = smartLiveQrManager.tryAutoSignFromLive(
            rollcall = rollcall,
            courseName = cName,
            classroom = room
        )

        if (success) {
            updateForegroundNotification(
                matchedSession,
                "已自动大屏扫码签到【$cName】"
            )
            return true
        }

        if (isFinalAttempt) {
            // 降级回退：大屏未捕获到或无直播，弹出普通扫码通知引导手动扫码
            Log.w(TAG, "Smart Live QR failed or unavailable on final attempt, fallback to manual QR notification")
            RollcallNotificationHelper.notifyQrRollcall(this, rollcallId, cName)
        } else {
            Log.i(TAG, "Smart Live QR not captured yet for rollcall $rollcallId, will retry on next poll")
        }
        return false
    }

    private suspend fun handleRadarAutoSign(
        rollcall: TronClassRollcall,
        currentSession: ScheduledClassSession,
        isFinalAttempt: Boolean
    ): Boolean {
        val rollcallId = rollcall.getEffectiveId()
        val matchedSession = RollcallScheduleHelper.findMatchingClassSession(this, rollcall.courseName) ?: currentSession
        val room = rollcall.classroom ?: matchedSession.room
        val courseName = rollcall.courseName ?: matchedSession.courseName

        // 1. 自动匹配教学楼经纬度基准
        val building = CoordinateUtils.findMatchingBuilding(room)
            ?: CoordinateUtils.findMatchingBuilding(courseName)
            ?: CoordinateUtils.USST_BUILDINGS.find { it.id == "b_516_1j_e" }
            ?: CoordinateUtils.USST_BUILDINGS.first()

        // 优先采纳教师端下发的实时高精基准坐标（0偏差必过通道），若未下发则采纳教学楼 GCJ-02 基准
        val targetLat = rollcall.latitude ?: building.latitude
        val targetLng = rollcall.longitude ?: building.longitude
        val accuracy = if (rollcall.latitude != null) 5.0 else 15.0

        Log.i(TAG, "Auto signing radar rollcall $rollcallId with lat=$targetLat, lng=$targetLng (isTeacherBeacon=${rollcall.latitude != null}, building=${building.name})")

        // 2. 批量签到（多账号自动分流并各自使用独立设备指纹）
        val results = tronClassClient.batchAnswerRadarRollcall(
            rollcallId = rollcallId,
            latitude = targetLat,
            longitude = targetLng,
            accuracy = accuracy
        )

        val currentStudentId = cn.edu.usst.jwgl.data.local.AuthPreferences(this).getStudentId()
        val primarySuccess = isPrimaryAccountSuccess(results, currentStudentId)
        val successCount = results.count { it.success }
        val totalCount = results.size

        // 权威查册复核闭环（recheck_on_call_fine）
        if (primarySuccess && currentStudentId.isNotBlank()) {
            val confirmed = tronClassClient.recheckOnCallFine(rollcallId, currentStudentId)
            Log.i(TAG, "Radar sign recheckOnCallFine result for $currentStudentId: $confirmed")
        }

        val details = if (results.size > 1) {
            "$successCount/$totalCount 个账号签到成功"
        } else {
            results.firstOrNull()?.message ?: if (primarySuccess) "签到成功" else "签到未成功"
        }

        val storeState = if (currentStudentId.isNotBlank()) {
            cn.edu.usst.jwgl.data.local.RollcallSubmissionStore(this).state(currentStudentId, rollcallId)
        } else null
        val isTerminal = primarySuccess || storeState == "rejected" || isFinalAttempt

        // 3. 仅在成功、终态拒绝或最终尝试时发送对应结果通知
        if (isTerminal) {
            RollcallNotificationHelper.notifyRadarResult(
                context = this,
                rollcallId = rollcallId,
                courseName = courseName,
                building = building,
                success = primarySuccess,
                message = details,
                classroom = room,
                latitude = rollcall.latitude,
                longitude = rollcall.longitude,
                scope = rollcall.scope
            )
        }

        // 4. 更新前台服务常驻通知状态
        if (primarySuccess) {
            updateForegroundNotification(
                matchedSession,
                "已自动签到【$courseName】(${building.name})"
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
        isFinalAttempt: Boolean
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
                cn.edu.usst.jwgl.data.model.NumberCodeLookupFailure.ContextMissing,
                cn.edu.usst.jwgl.data.model.NumberCodeLookupFailure.Forbidden,
                cn.edu.usst.jwgl.data.model.NumberCodeLookupFailure.CodeNotReturned,
                cn.edu.usst.jwgl.data.model.NumberCodeLookupFailure.Conflict
            )
            Log.i(TAG, "Number code not available for rollcall $rollcallId: $reason (terminal=$isTerminalLookup)")
            if (isTerminalLookup || isFinalAttempt) {
                RollcallNotificationHelper.notifyNumberRollcall(this, rollcallId, courseName)
                return true
            }
            return false
        }

        // 2. 成功获取数字码，自动提交
        val numberCode = lookupResult.code
        Log.i(TAG, "Got number code [$numberCode] for rollcall $rollcallId, auto-submitting")

        val results = tronClassClient.batchAnswerNumberRollcall(rollcallId, numberCode)

        val currentStudentId = cn.edu.usst.jwgl.data.local.AuthPreferences(this).getStudentId()
        val primarySuccess = isPrimaryAccountSuccess(results, currentStudentId)
        val successCount = results.count { it.success }
        val totalCount = results.size

        // 权威查册复核闭环（recheck_on_call_fine）
        if (primarySuccess && currentStudentId.isNotBlank()) {
            val confirmed = tronClassClient.recheckOnCallFine(rollcallId, currentStudentId)
            Log.i(TAG, "Number sign recheckOnCallFine result for $currentStudentId: $confirmed")
        }

        val details = if (results.size > 1) {
            "$successCount/$totalCount 个账号签到成功"
        } else {
            results.firstOrNull()?.message ?: if (primarySuccess) "签到成功" else "签到未成功"
        }

        val storeState = if (currentStudentId.isNotBlank()) {
            cn.edu.usst.jwgl.data.local.RollcallSubmissionStore(this).state(currentStudentId, rollcallId)
        } else null
        val isTerminal = primarySuccess || storeState == "rejected" || isFinalAttempt

        // 3. 仅在成功、终态拒绝或最终尝试时发送结果通知
        if (isTerminal) {
            RollcallNotificationHelper.notifyNumberAutoResult(
                context = this,
                rollcallId = rollcallId,
                courseName = courseName,
                success = primarySuccess,
                message = details
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
        isFinalAttempt: Boolean
    ): Boolean {
        val rollcallId = rollcall.getEffectiveId()
        val courseName = rollcall.courseName ?: currentSession.courseName
        Log.i(TAG, "Triggering Self-Registration auto sign for $courseName (rollcallId=$rollcallId)")

        // 1. 批量提交自主签到
        val results = tronClassClient.batchAnswerSelfRegistrationRollcall(rollcallId)
        val currentStudentId = cn.edu.usst.jwgl.data.local.AuthPreferences(this).getStudentId()
        val primaryResult = results.find { it.studentId == currentStudentId } ?: results.firstOrNull()
        val primarySuccess = isPrimaryAccountSuccess(results, currentStudentId)

        // 2. 权威查册复核闭环：确认学号是否已成功标记为 on_call_fine
        if (primarySuccess && currentStudentId.isNotBlank()) {
            val confirmed = tronClassClient.recheckOnCallFine(rollcallId, currentStudentId)
            Log.i(TAG, "Self-Registration sign recheckOnCallFine result for $currentStudentId: $confirmed")
        }

        val successCount = results.count { it.success }
        val totalCount = results.size
        val details = if (results.size > 1) {
            "$successCount/$totalCount 个账号签到成功"
        } else {
            primaryResult?.message ?: if (primarySuccess) "签到成功" else "签到未成功"
        }

        val storeState = if (currentStudentId.isNotBlank()) {
            cn.edu.usst.jwgl.data.local.RollcallSubmissionStore(this).state(currentStudentId, rollcallId)
        } else null
        val isTerminal = primarySuccess || storeState == "rejected" || isFinalAttempt

        // 3. 仅在成功、终态拒绝或最终尝试时发送对应结果通知
        if (isTerminal) {
            RollcallNotificationHelper.notifySelfRegistrationResult(
                context = this,
                rollcallId = rollcallId,
                courseName = courseName,
                success = primarySuccess,
                message = details
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

    /**
     * 轮询超星学习通进行中的“位置签到”，在后台自动按教师要求坐标或匹配教学楼坐标提交。
     */
    private suspend fun checkChaoXingLocationSigns(session: ScheduledClassSession) {
        val now = System.currentTimeMillis()
        if (cachedChaoXingCourses.isEmpty() || now - lastChaoXingCourseFetchMs > CHAOXING_COURSE_CACHE_TTL_MS) {
            val fetched = chaoXingClient.fetchCourses().getOrNull()
            if (!fetched.isNullOrEmpty()) {
                cachedChaoXingCourses = fetched
                lastChaoXingCourseFetchMs = now
            }
        }
        if (cachedChaoXingCourses.isEmpty()) return

        val activePairs = chaoXingClient.fetchActiveSignsForCourses(cachedChaoXingCourses).getOrNull() ?: return
        val account = cn.edu.usst.jwgl.data.local.AuthPreferences(this).getStudentId()
        val store = cn.edu.usst.jwgl.data.local.RollcallSubmissionStore(this, "chaoxing")

        for ((course, item) in activePairs) {
            if (item.resolvedSignTypeName != "位置签到") continue
            val activeId = item.id
            if (activeId <= 0L) continue
            if (prefs.isRollcallHandled(activeId)) continue
            if (account.isNotBlank() && store.state(account, activeId) != null) {
                prefs.markRollcallHandled(activeId)
                continue
            }

            val isAutoSignEnabled = prefs.isRollcallAutoSignEnabled()
            val delaySeconds = prefs.getRollcallAutoSignDelaySeconds().coerceIn(5, 30)

            if (!isAutoSignEnabled) {
                prefs.markRollcallHandled(activeId)
                RollcallNotificationHelper.notifyGenericRollcall(
                    this,
                    activeId,
                    course.courseName,
                    "超星位置签到"
                )
                continue
            }

            if (!inFlightChaoXingSigns.add(activeId)) continue
            val attempt = chaoXingRetryAttempts[activeId] ?: 0
            val waitSec = if (attempt == 0) delaySeconds else 0

            Log.i(TAG, "Detected ChaoXing location sign: activeId=$activeId, course=${course.courseName}, attempt=${attempt + 1}/$MAX_AUTO_SIGN_ATTEMPTS")
            if (attempt == 0) {
                updateForegroundNotification(
                    session,
                    "检测到超星【${course.courseName}】位置签到，将在 ${delaySeconds} 秒后尝试自动提交"
                )
            }

            serviceScope.launch {
                try {
                    if (waitSec > 0) {
                        delay(waitSec * 1000L)
                    }
                    if (!isActive) return@launch

                    val isFinalAttempt = attempt + 1 >= MAX_AUTO_SIGN_ATTEMPTS
                    val isTerminal = handleChaoXingLocationAutoSign(course, item, session, isFinalAttempt)
                    if (isTerminal || isFinalAttempt) {
                        prefs.markRollcallHandled(activeId)
                        chaoXingRetryAttempts.remove(activeId)
                    } else {
                        chaoXingRetryAttempts[activeId] = attempt + 1
                    }
                } finally {
                    inFlightChaoXingSigns.remove(activeId)
                }
            }
        }
    }

    private suspend fun handleChaoXingLocationAutoSign(
        course: cn.edu.usst.jwgl.data.model.ChaoXingCourse,
        item: cn.edu.usst.jwgl.data.model.ChaoXingActiveItem,
        currentSession: ScheduledClassSession,
        isFinalAttempt: Boolean
    ): Boolean {
        val activeId = item.id
        val hintName = "${item.nameOne.orEmpty()} ${item.nameTwo.orEmpty()}".trim()
        val detail = chaoXingClient.fetchSignDetail(
            activeId = activeId,
            courseId = course.courseId,
            classId = course.classId,
            initialOtherId = item.otherId,
            initialHintName = hintName
        ).getOrNull()

        if (detail?.isAlreadySigned == true || detail?.isExpired == true) {
            return true
        }

        val hasTeacherCoords = detail?.latitude != null && detail.longitude != null &&
            detail.latitude != 0.0 && detail.longitude != 0.0
        val matchedSession = RollcallScheduleHelper.findMatchingClassSession(this, course.courseName) ?: currentSession

        val targetLat: Double
        val targetLng: Double
        val targetAddress: String
        val locationLabel: String

        if (hasTeacherCoords) {
            val jitterLat = (Math.random() - 0.5) * 0.00003
            val jitterLng = (Math.random() - 0.5) * 0.00003
            targetLat = detail!!.latitude!! + jitterLat
            targetLng = detail.longitude!! + jitterLng
            targetAddress = detail.locationText?.takeIf { it.isNotBlank() } ?: "上海理工大学"
            locationLabel = "教师指定位置 ($targetAddress)"
        } else {
            val room = matchedSession.room
            val building = CoordinateUtils.findMatchingBuilding(room)
                ?: CoordinateUtils.findMatchingBuilding(course.courseName)
                ?: CoordinateUtils.USST_BUILDINGS.find { it.id == "b_516_1j_e" }
                ?: CoordinateUtils.USST_BUILDINGS.first()
            targetLat = building.latitude
            targetLng = building.longitude
            targetAddress = detail?.locationText?.takeIf { it.isNotBlank() } ?: "上海理工大学 ${building.name}"
            locationLabel = "${building.name} (${building.campus})"
        }

        val submitRes = chaoXingClient.submitSign(
            courseId = course.courseId,
            classId = course.classId,
            activeId = activeId,
            signType = "位置签到",
            latitude = targetLat,
            longitude = targetLng,
            address = targetAddress,
            allowRejectedRetry = false
        )

        val result = submitRes.getOrNull()
        val success = result?.isSuccess == true
        val message = result?.message ?: submitRes.exceptionOrNull()?.message ?: "提交失败"
        val account = cn.edu.usst.jwgl.data.local.AuthPreferences(this).getStudentId()
        val storeState = if (account.isNotBlank()) {
            cn.edu.usst.jwgl.data.local.RollcallSubmissionStore(this, "chaoxing").state(account, activeId)
        } else null
        val isTerminal = success || storeState == "rejected" || isFinalAttempt

        if (isTerminal) {
            RollcallNotificationHelper.notifyChaoXingLocationResult(
                context = this,
                activeId = activeId,
                courseName = course.courseName,
                locationLabel = locationLabel,
                success = success,
                message = message
            )
        }

        if (success) {
            updateForegroundNotification(
                matchedSession,
                "已自动超星位置签到【${course.courseName}】"
            )
        }
        return isTerminal
    }

    private fun stopMonitoringAndSelf() {
        pollJob?.cancel()
        pollJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        serviceScope.cancel()
        Log.i(TAG, "RollcallMonitorService destroyed.")
    }
}
