package cn.edu.usst.jwgl.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.receiver.RollcallAlarmReceiver
import cn.edu.usst.jwgl.service.RollcallMonitorService
import cn.edu.usst.jwgl.util.RollcallScheduleHelper

object RollcallMonitorScheduler {

    private const val TAG = "RollcallMonitorScheduler"
    private const val REQUEST_CODE_ALARM = 88002

    fun onPreferenceChanged(context: Context) {
        val prefs = TronClassPreferences.getInstance(context)
        if (prefs.isRollcallMonitorEnabled()) {
            checkAndSchedule(context)
        } else {
            cancel(context)
        }
    }

    fun scheduleFromPreferences(context: Context) {
        val prefs = TronClassPreferences.getInstance(context)
        if (prefs.isRollcallMonitorEnabled()) {
            checkAndSchedule(context)
        } else {
            cancel(context)
        }
    }

    fun checkAndSchedule(context: Context) {
        val prefs = TronClassPreferences.getInstance(context)
        if (!prefs.isRollcallMonitorEnabled() || !prefs.isLoggedIn()) {
            cancel(context)
            return
        }

        val currentSession = RollcallScheduleHelper.getCurrentSession(context)
        if (currentSession != null) {
            // 当前处于上课时段（或课前10分钟内），立即启动前台服务！
            Log.i(TAG, "Currently in class session (${currentSession.courseName}), starting RollcallMonitorService.")
            try {
                val serviceIntent = Intent(context, RollcallMonitorService::class.java).apply {
                    action = RollcallMonitorService.ACTION_START
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to startForegroundService: ${e.message}", e)
            }
        } else {
            // 当前不在上课时段，计算下一次上课时间并设定系统闹钟唤醒
            scheduleNext(context)
        }
    }

    /**
     * 调度下一次上课唤醒闹钟
     */
    fun scheduleNext(context: Context) {
        val prefs = TronClassPreferences.getInstance(context)
        if (!prefs.isRollcallMonitorEnabled() || !prefs.isLoggedIn()) {
            cancel(context)
            return
        }

        val now = System.currentTimeMillis()
        val nextSession = RollcallScheduleHelper.getNextUpcomingSessionToday(context, now)
            ?: RollcallScheduleHelper.findNextSessionAcrossDays(context)

        if (nextSession == null) {
            Log.i(TAG, "No upcoming classes found across rolling days.")
            return
        }

        val triggerAtMillis = nextSession.bufferStartTimeMillis
        if (triggerAtMillis <= now) {
            // 已经到达或超过了开始时间，直接启动
            checkAndSchedule(context)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, RollcallAlarmReceiver::class.java).apply {
            action = RollcallAlarmReceiver.ACTION_ROLLCALL_ALARM
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_ALARM,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
            Log.i(TAG, "Scheduled next rollcall alarm for ${nextSession.courseName} at $triggerAtMillis")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule exact alarm: ${e.message}", e)
        }
    }

    fun cancel(context: Context) {
        try {
            // 停止后台服务
            val stopIntent = Intent(context, RollcallMonitorService::class.java).apply {
                action = RollcallMonitorService.ACTION_STOP
            }
            context.stopService(stopIntent)
        } catch (_: Exception) {}

        try {
            // 取消已注册的闹钟
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, RollcallAlarmReceiver::class.java).apply {
                action = RollcallAlarmReceiver.ACTION_ROLLCALL_ALARM
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_ALARM,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            Log.i(TAG, "Cancelled rollcall monitor alarms.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel alarm: ${e.message}", e)
        }
    }
}
