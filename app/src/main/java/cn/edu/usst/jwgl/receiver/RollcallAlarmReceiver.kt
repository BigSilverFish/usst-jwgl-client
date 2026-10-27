package cn.edu.usst.jwgl.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cn.edu.usst.jwgl.worker.RollcallMonitorScheduler

class RollcallAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ROLLCALL_ALARM = "cn.edu.usst.jwgl.action.ROLLCALL_ALARM"
        private const val TAG = "RollcallAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "RollcallAlarmReceiver triggered: action=${intent.action}")
        RollcallMonitorScheduler.checkAndSchedule(context)
    }
}
