package cn.edu.usst.jwgl.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import cn.edu.usst.jwgl.data.local.TronClassPreferences
import cn.edu.usst.jwgl.data.model.TronClassTodo
import cn.edu.usst.jwgl.receiver.TronClassDdlReceiver
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.util.CourseReminderManager
import java.util.concurrent.TimeUnit

object TronClassDdlScheduler {

    private const val TAG = "TronClassDdlScheduler"
    private const val WORK_NAME_PERIODIC = "tronclass_ddl_periodic"

    /**
     * 根据设置项初始化周期性后台检查和精准闹钟
     */
    fun scheduleFromPreferences(context: Context) {
        val prefs = TronClassPreferences(context)
        if (!prefs.isAnyDdlReminderEnabled()) {
            cancel(context)
            return
        }
        schedulePeriodicWork(context)
        scheduleExactAlarms(context, prefs.getCachedTodos())
    }

    /**
     * 注册周期性后台 DDL 预警守护（每 1 小时自动静默检查一次，有网络连接时触发）
     */
    fun schedulePeriodicWork(context: Context) {
        val prefs = TronClassPreferences(context)
        if (!prefs.isAnyDdlReminderEnabled()) {
            cancel(context)
            return
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicRequest = PeriodicWorkRequestBuilder<TronClassDdlWorker>(1, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodicRequest
        )
    }

    /**
     * 针对已缓存的待办列表设定精准系统闹钟（截止前24小时与提前1-3小时二级预警）
     */
    fun scheduleExactAlarms(context: Context, todos: List<TronClassTodo>) {
        val prefs = TronClassPreferences(context)
        val is24hEnabled = prefs.isDdlReminder24hEnabled()
        val isUrgentEnabled = prefs.isDdlReminderUrgentEnabled()

        if (!is24hEnabled && !isUrgentEnabled) {
            cancelAllExactAlarms(context, todos)
            return
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val now = System.currentTimeMillis()
        val urgentHours = prefs.getDdlReminderUrgentHours()
        val sp = context.getSharedPreferences(TronClassDdlReceiver.PREF_DDL_NOTIFIED, Context.MODE_PRIVATE)

        for (todo in todos) {
            val endMillis = todo.getDeadlineDate()?.time ?: continue
            if (endMillis <= now) continue

            val courseName = todo.getCleanCourseName()

            // 1. 截止前 24 小时预警闹钟
            if (is24hEnabled) {
                val trigger24h = endMillis - TimeUnit.HOURS.toMillis(24)
                val notified24h = sp.getBoolean("todo_${todo.id}_notified_24h", false)
                if (trigger24h > now && !notified24h) {
                    val requestCode = getRequestCode(todo.id, 1)
                    val intent = Intent(context, TronClassDdlReceiver::class.java).apply {
                        action = TronClassDdlReceiver.ACTION_TRONCLASS_DDL_ALARM
                        putExtra(TronClassDdlReceiver.EXTRA_TODO_ID, todo.id)
                        putExtra(TronClassDdlReceiver.EXTRA_TODO_TITLE, todo.title)
                        putExtra(TronClassDdlReceiver.EXTRA_COURSE_NAME, courseName)
                        putExtra(TronClassDdlReceiver.EXTRA_COURSE_ID, todo.courseId)
                        putExtra(TronClassDdlReceiver.EXTRA_IS_URGENT, false)
                        putExtra(TronClassDdlReceiver.EXTRA_DEADLINE_MILLIS, endMillis)
                    }
                    val pi = PendingIntent.getBroadcast(
                        context,
                        requestCode,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                    )
                    setSingleAlarm(context, alarmManager, trigger24h, pi)
                }
            } else {
                cancelPendingIntent(context, alarmManager, getRequestCode(todo.id, 1))
            }

            // 2. 截止前 1-3 小时临急闹钟
            if (isUrgentEnabled) {
                val triggerUrgent = endMillis - TimeUnit.HOURS.toMillis(urgentHours.toLong())
                val notifiedUrgent = sp.getBoolean("todo_${todo.id}_notified_urgent", false)
                if (triggerUrgent > now && !notifiedUrgent) {
                    val requestCode = getRequestCode(todo.id, 2)
                    val intent = Intent(context, TronClassDdlReceiver::class.java).apply {
                        action = TronClassDdlReceiver.ACTION_TRONCLASS_DDL_ALARM
                        putExtra(TronClassDdlReceiver.EXTRA_TODO_ID, todo.id)
                        putExtra(TronClassDdlReceiver.EXTRA_TODO_TITLE, todo.title)
                        putExtra(TronClassDdlReceiver.EXTRA_COURSE_NAME, courseName)
                        putExtra(TronClassDdlReceiver.EXTRA_COURSE_ID, todo.courseId)
                        putExtra(TronClassDdlReceiver.EXTRA_IS_URGENT, true)
                        putExtra(TronClassDdlReceiver.EXTRA_DEADLINE_MILLIS, endMillis)
                    }
                    val pi = PendingIntent.getBroadcast(
                        context,
                        requestCode,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                    )
                    setSingleAlarm(context, alarmManager, triggerUrgent, pi)
                }
            } else {
                cancelPendingIntent(context, alarmManager, getRequestCode(todo.id, 2))
            }
        }
    }

    private fun getRequestCode(todoId: Long, tier: Int): Int {
        return ((todoId * 10 + tier) % Int.MAX_VALUE).toInt()
    }

    private fun setSingleAlarm(
        context: Context,
        alarmManager: AlarmManager,
        triggerTimeMillis: Long,
        pendingIntent: PendingIntent
    ) {
        val powerful = CourseReminderManager.isPowerfulReminderEnabled(context)
        try {
            if (powerful) {
                val showIntent = Intent(context, MainActivity::class.java)
                val showPi = PendingIntent.getActivity(
                    context,
                    0,
                    showIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                )
                val info = AlarmManager.AlarmClockInfo(triggerTimeMillis, showPi)
                alarmManager.setAlarmClock(info, pendingIntent)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTimeMillis, pendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTimeMillis, pendingIntent)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to setExact DDL alarm at $triggerTimeMillis", e)
            try {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTimeMillis, pendingIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback alarm failed", e2)
            }
        }
    }

    private fun cancelPendingIntent(context: Context, alarmManager: AlarmManager, requestCode: Int) {
        val intent = Intent(context, TronClassDdlReceiver::class.java).apply {
            action = TronClassDdlReceiver.ACTION_TRONCLASS_DDL_ALARM
        }
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_NO_CREATE or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }

    fun cancelAllExactAlarms(context: Context, todos: List<TronClassTodo>? = null) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val list = todos ?: TronClassPreferences(context).getCachedTodos()
        for (todo in list) {
            cancelPendingIntent(context, alarmManager, getRequestCode(todo.id, 1))
            cancelPendingIntent(context, alarmManager, getRequestCode(todo.id, 2))
        }
    }

    /**
     * 立即触发一次一次性后台 DDL 检查（如登录完成或刷新时）
     */
    fun runOnce(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeRequest = OneTimeWorkRequestBuilder<TronClassDdlWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueue(oneTimeRequest)
    }

    /**
     * 取消后台守护与闹钟
     */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PERIODIC)
        cancelAllExactAlarms(context)
    }
}
