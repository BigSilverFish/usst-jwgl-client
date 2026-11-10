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

class RollcallMonitorService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollJob: Job? = null

    private val tronClassClient by lazy { TronClassClient(this) }
    private val prefs by lazy { TronClassPreferences.getInstance(this) }
    private val smartLiveQrManager by lazy { cn.edu.usst.jwgl.util.SmartLiveQrRollcallManager(this) }

    companion object {
        private const val TAG = "RollcallMonitorService"
        const val ACTION_START = "cn.edu.usst.jwgl.action.START_MONITOR"
        const val ACTION_STOP = "cn.edu.usst.jwgl.action.STOP_MONITOR"
        const val ACTION_CHECK_NOW = "cn.edu.usst.jwgl.action.CHECK_NOW"

        private const val POLL_INTERVAL_MS = 20_000L // 20 秒轮询一次
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoringAndSelf()
            return START_NOT_STICKY
        }

        // 校验开关与登录状态
        if (!prefs.isRollcallMonitorEnabled() || !prefs.isLoggedIn()) {
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

            // 立即标记为已处理，防止重复通知或重复提交
            prefs.markRollcallHandled(rollcallId)

            val normType = rollcall.getNormalizedType()
            val cName = rollcall.courseName ?: session.courseName

            Log.i(TAG, "Detected ongoing rollcall: id=$rollcallId, type=$normType, course=$cName")

            val isAutoSignEnabled = prefs.isRollcallAutoSignEnabled()
            val delaySeconds = prefs.getRollcallAutoSignDelaySeconds().coerceIn(5, 30)

            if (!isAutoSignEnabled) {
                // 仅监听模式：立即通知用户手动签到
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
                // 自动签到模式：自首次发现签到的 5-30 秒才开始尝试自动签到
                Log.i(TAG, "Auto-sign enabled. Waiting $delaySeconds seconds before submitting rollcall $rollcallId ($normType)")
                updateForegroundNotification(
                    session,
                    "检测到【$cName】签到，将在 ${delaySeconds} 秒后尝试自动提交"
                )
                serviceScope.launch {
                    delay(delaySeconds * 1000L)
                    if (!isActive) return@launch

                    when (normType) {
                        "radar" -> handleRadarAutoSign(rollcall, session)
                        "number" -> handleNumberAutoSign(rollcall, session)
                        "qr_code" -> handleQrCodeAutoSign(rollcall, session)
                        "self_registration" -> handleSelfRegistrationAutoSign(rollcall, session)
                        else -> RollcallNotificationHelper.notifyGenericRollcall(
                            this@RollcallMonitorService,
                            rollcallId,
                            cName,
                            rollcall.getTypeDisplayName()
                        )
                    }
                }
            }
        }
    }

    private suspend fun handleQrCodeAutoSign(
        rollcall: TronClassRollcall,
        currentSession: ScheduledClassSession
    ) {
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
        } else {
            // 降级回退：大屏未捕获到或无直播，弹出普通扫码通知引导手动扫码
            Log.w(TAG, "Smart Live QR failed or unavailable, fallback to manual QR notification")
            RollcallNotificationHelper.notifyQrRollcall(this, rollcallId, cName)
        }
    }

    private suspend fun handleRadarAutoSign(
        rollcall: TronClassRollcall,
        currentSession: ScheduledClassSession
    ) {
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

        val primarySuccess = results.firstOrNull()?.success == true
        val successCount = results.count { it.success }
        val totalCount = results.size

        // 权威查册复核闭环（recheck_on_call_fine）
        val currentStudentId = cn.edu.usst.jwgl.data.local.AuthPreferences(this).getStudentId()
        if (primarySuccess && currentStudentId.isNotBlank()) {
            val confirmed = tronClassClient.recheckOnCallFine(rollcallId, currentStudentId)
            Log.i(TAG, "Radar sign recheckOnCallFine result for $currentStudentId: $confirmed")
        }

        val details = if (results.size > 1) {
            "$successCount/$totalCount 个账号签到成功"
        } else {
            results.firstOrNull()?.message ?: if (primarySuccess) "签到成功" else "签到未成功"
        }

        // 3. 发送对应结果通知
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

        // 4. 更新前台服务常驻通知状态
        if (primarySuccess) {
            updateForegroundNotification(
                matchedSession,
                "已自动签到【$courseName】(${building.name})"
            )
        }
    }

    /**
     * 数字口令签到自动化处理：
     * 尝试从服务端 student_rollcalls 接口读取 number_code（学校自部署实例可能未修复泄露），
     * 如果成功取到 4 位码则自动提交（含多账号批量），否则退回通知让用户手动输入。
     */
    private suspend fun handleNumberAutoSign(
        rollcall: TronClassRollcall,
        currentSession: ScheduledClassSession
    ) {
        val rollcallId = rollcall.getEffectiveId()
        val courseName = rollcall.courseName ?: currentSession.courseName

        // 1. 尝试从服务端读取数字签到码
        Log.i(TAG, "Attempting to lookup number code for rollcall $rollcallId ($courseName)")
        val lookupResult = tronClassClient.lookupNumberRollcallCode(rollcall)

        if (lookupResult !is NumberCodeLookupResult.Found) {
            // 服务端未返回数字码 → 退回通知让用户手动输入
            val reason = (lookupResult as? NumberCodeLookupResult.Unavailable)?.message ?: "未知原因"
            Log.i(TAG, "Number code not available for rollcall $rollcallId: $reason")
            RollcallNotificationHelper.notifyNumberRollcall(this, rollcallId, courseName)
            return
        }

        // 2. 成功获取数字码，自动提交
        val numberCode = lookupResult.code
        Log.i(TAG, "Got number code [$numberCode] for rollcall $rollcallId, auto-submitting")

        val results = tronClassClient.batchAnswerNumberRollcall(rollcallId, numberCode)

        val primarySuccess = results.firstOrNull()?.success == true
        val successCount = results.count { it.success }
        val totalCount = results.size

        // 权威查册复核闭环（recheck_on_call_fine）
        val currentStudentId = cn.edu.usst.jwgl.data.local.AuthPreferences(this).getStudentId()
        if (primarySuccess && currentStudentId.isNotBlank()) {
            val confirmed = tronClassClient.recheckOnCallFine(rollcallId, currentStudentId)
            Log.i(TAG, "Radar sign recheckOnCallFine result for $currentStudentId: $confirmed")
        }

        val details = if (results.size > 1) {
            "$successCount/$totalCount 个账号签到成功"
        } else {
            results.firstOrNull()?.message ?: if (primarySuccess) "签到成功" else "签到未成功"
        }

        // 3. 发送结果通知
        RollcallNotificationHelper.notifyNumberAutoResult(
            context = this,
            rollcallId = rollcallId,
            courseName = courseName,
            success = primarySuccess,
            message = details
        )

        // 4. 更新前台服务常驻通知
        if (primarySuccess) {
            updateForegroundNotification(
                currentSession,
                "已自动签到【$courseName】(数字口令 $numberCode)"
            )
        }
    }

    /**
     * 自主签到自动化处理：
     * 无需坐标、无需口令、无需扫码，直接向畅课服务器提交自主点名请求，
     * 并在完成后调用 recheckOnCallFine 进行权威复核闭环。
     */
    private suspend fun handleSelfRegistrationAutoSign(
        rollcall: TronClassRollcall,
        currentSession: ScheduledClassSession
    ) {
        val rollcallId = rollcall.getEffectiveId()
        val courseName = rollcall.courseName ?: currentSession.courseName
        Log.i(TAG, "Triggering Self-Registration auto sign for $courseName (rollcallId=$rollcallId)")

        // 1. 批量提交自主签到
        val results = tronClassClient.batchAnswerSelfRegistrationRollcall(rollcallId)
        val primaryResult = results.firstOrNull()
        val primarySuccess = primaryResult?.success == true

        // 2. 权威查册复核闭环：确认学号是否已成功标记为 on_call_fine
        val currentStudentId = cn.edu.usst.jwgl.data.local.AuthPreferences(this).getStudentId()
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

        // 3. 发送对应结果通知
        RollcallNotificationHelper.notifySelfRegistrationResult(
            context = this,
            rollcallId = rollcallId,
            courseName = courseName,
            success = primarySuccess,
            message = details
        )

        // 4. 更新前台服务常驻通知
        if (primarySuccess) {
            updateForegroundNotification(
                currentSession,
                "已自动自主签到【$courseName】"
            )
        }
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
