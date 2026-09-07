package cn.edu.usst.jwgl.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.edu.usst.jwgl.util.CourseReminderManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
        ) {
            CourseReminderManager.scheduleUpcomingReminders(context)
        }
    }
}
