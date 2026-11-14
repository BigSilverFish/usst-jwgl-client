package cn.edu.usst.jwgl.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat

/**
 * 接收通知操作栏中的“我知道了 / 忽略”点击事件，安全取消对应通知
 */
class NotificationDismissReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_DISMISS = "cn.edu.usst.jwgl.action.NOTIFICATION_DISMISS"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)
        if (notifId != -1) {
            try {
                NotificationManagerCompat.from(context).cancel(notifId)
            } catch (_: Exception) {
            }
        }
    }
}
