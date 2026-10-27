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

            when (normType) {
                "radar" -> {
                    handleRadarAutoSign(rollcall, session)
                }
                "number" -> {
                    RollcallNotificationHelper.notifyNumberRollcall(this, rollcallId, cName)
                }
                "qr_code" -> {
                    RollcallNotificationHelper.notifyQrRollcall(this, rollcallId, cName)
                }
                else -> {
                    RollcallNotificationHelper.notifyGenericRollcall(
                        this,
                        rollcallId,
                        cName,
                        rollcall.getTypeDisplayName()
                    )
                }
            }
        }
    }

    private suspend fun handleRadarAutoSign(
        rollcall: TronClassRollcall,
        currentSession: ScheduledClassSession
    ) {
        val rollcallId = rollcall.getEffectiveId()
        val matchedSession = RollcallScheduleHelper.findMatchingClassSession(this, rollcall.courseName) ?: currentSession
        val room = matchedSession.room
        val courseName = rollcall.courseName ?: matchedSession.courseName

        // 1. 自动匹配教学楼经纬度基准
        val building = CoordinateUtils.findMatchingBuilding(room)
            ?: CoordinateUtils.findMatchingBuilding(courseName)
            ?: CoordinateUtils.USST_BUILDINGS.find { it.id == "b_516_1j_m" }
            ?: CoordinateUtils.USST_BUILDINGS.first()

        Log.i(TAG, "Auto signing radar rollcall $rollcallId with building: ${building.name} (${building.campus})")

        // 2. 批量签到（多账号自动分流并各自使用独立设备指纹）
        val results = tronClassClient.batchAnswerRadarRollcall(
            rollcallId = rollcallId,
            latitude = building.latitude,
            longitude = building.longitude,
            accuracy = 15.0
        )

        val primarySuccess = results.firstOrNull()?.success == true
        val successCount = results.count { it.success }
        val totalCount = results.size

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
            classroom = room
        )

        // 4. 更新前台服务常驻通知状态
        if (primarySuccess) {
            updateForegroundNotification(
                matchedSession,
                "已自动签到【$courseName】(${building.name})"
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
