package cn.edu.usst.jwgl.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import cn.edu.usst.jwgl.receiver.NotificationDismissReceiver

object NotificationUiHelper {

    /**
     * 将矢量图标 VectorDrawable 转换为带圆形浅底的高清 Bitmap，专门用作通知的大图标 LargeIcon
     * 显著增加系统通知在通知栏中的视觉面积与辨识度
     */
    fun getBitmapFromVector(
        context: Context,
        @DrawableRes drawableId: Int,
        @ColorInt tintColor: Int,
        sizeDp: Int = 64
    ): Bitmap? {
        return try {
            val density = context.resources.displayMetrics.density
            val sizePx = (sizeDp * density).toInt().coerceAtLeast(48)
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // 1. 绘制圆形浅色底板（提高在各种通知栏深/浅主题下的对比度）
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tintColor
                alpha = 38 // 约 15% 透明度底色
            }
            val radius = sizePx / 2f
            canvas.drawCircle(radius, radius, radius, bgPaint)

            // 2. 居中绘制有强调色着色的矢量图标
            val drawable: Drawable? = ContextCompat.getDrawable(context, drawableId)?.mutate()
            if (drawable != null) {
                drawable.colorFilter = PorterDuffColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
                val iconPadding = (sizePx * 0.22f).toInt()
                drawable.setBounds(
                    iconPadding,
                    iconPadding,
                    sizePx - iconPadding,
                    sizePx - iconPadding
                )
                drawable.draw(canvas)
            }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 熄屏状态下唤醒屏幕数秒，避免手机静音或放在课桌上被漏看
     */
    fun wakeUpScreen(context: Context, durationMillis: Long = 5000L) {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!powerManager.isInteractive) {
                @Suppress("DEPRECATION")
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "usst_jwgl:NotificationWakeUp"
                )
                wakeLock.acquire(durationMillis)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * 创建“我知道了”取消该条通知的 PendingIntent
     */
    fun createDismissPendingIntent(context: Context, notificationId: Int): PendingIntent {
        val intent = Intent(context, NotificationDismissReceiver::class.java).apply {
            action = NotificationDismissReceiver.ACTION_DISMISS
            putExtra(NotificationDismissReceiver.EXTRA_NOTIFICATION_ID, notificationId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return PendingIntent.getBroadcast(context, notificationId, intent, flags)
    }

    /**
     * 一键跳转至系统通知设置（方便学生开启“横幅悬浮窗通知”与“锁屏显示”）
     */
    fun openNotificationSettings(context: Context, channelId: String? = null) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!channelId.isNullOrEmpty()) {
                    val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return
                }
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } else {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        } catch (_: Exception) {
        }
    }
}
