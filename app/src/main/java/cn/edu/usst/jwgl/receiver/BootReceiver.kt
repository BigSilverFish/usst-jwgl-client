package cn.edu.usst.jwgl.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.edu.usst.jwgl.data.local.DataCacheManager
import cn.edu.usst.jwgl.util.CourseReminderManager
import cn.edu.usst.jwgl.worker.RollcallMonitorScheduler
import cn.edu.usst.jwgl.worker.TronClassDdlScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // 1. Reschedule rolling 14-day course reminders
                    CourseReminderManager.scheduleUpcomingReminders(context)

                    // 2. Reschedule cached exam reminders (fixes missing exam reminders on device reboot)
                    if (CourseReminderManager.isExamReminderEnabled(context)) {
                        val exams = DataCacheManager(context).getAllCachedExams()
                        CourseReminderManager.scheduleExamReminders(context, exams)
                    }

                    // 3. Update home screen timetable widgets
                    cn.edu.usst.jwgl.widget.TodayScheduleWidgetProvider.updateAllWidgets(context)

                    // 4. Reschedule TronClass DDL reminders
                    TronClassDdlScheduler.scheduleFromPreferences(context)

                    // 5. Reschedule TronClass classroom rollcall monitoring
                    RollcallMonitorScheduler.scheduleFromPreferences(context)
                } catch (_: Exception) {
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
