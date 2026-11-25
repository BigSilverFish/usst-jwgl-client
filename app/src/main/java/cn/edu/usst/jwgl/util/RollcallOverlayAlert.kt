package cn.edu.usst.jwgl.util

import android.app.Activity
import android.app.ActivityOptions
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import cn.edu.usst.jwgl.R
import cn.edu.usst.jwgl.ui.MainActivity
import cn.edu.usst.jwgl.ui.tronclass.RollcallAlertActivity
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * Native WindowManager overlay manager for background rollcall alerts when overlay permission
 * is granted and no foreground activity is resumed.
 */
object RollcallOverlayAlert {
    private const val TAG = "RollcallOverlayAlert"
    private const val TIMEOUT_MS = 60_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val requestSequence = AtomicLong()

    @Volatile
    private var application: Application? = null

    @Volatile
    private var resumedActivity: WeakReference<Activity>? = null

    // Confined to the main thread.
    private var activeView: View? = null
    private var activeWindowManager: WindowManager? = null
    private var activeRollcallId: Long? = null
    private var timeoutRunnable: Runnable? = null

    fun initialize(app: Application) {
        if (application == null) {
            synchronized(this) {
                if (application == null) {
                    application = app
                    app.registerActivityLifecycleCallbacks(activityCallbacks)
                }
            }
        }
    }

    fun hasResumedActivity(): Boolean =
        resumedActivity?.get()?.let { !it.isFinishing && !it.isDestroyed } == true

    fun show(
        context: Context,
        rollcallId: Long,
        courseName: String?,
        typeName: String,
        actionIntent: Intent,
        shouldShow: () -> Boolean = { true },
        vibrate: Boolean = true,
        onShown: () -> Unit = {},
        onUnavailable: () -> Unit = {}
    ) {
        val request = requestSequence.incrementAndGet()
        onMain {
            if (requestSequence.get() != request || !shouldShow()) {
                return@onMain
            }
            if (activeRollcallId == rollcallId && activeView?.parent != null) {
                safeInvoke(onShown)
                return@onMain
            }
            dismissWindow()
            try {
                val app = (context.applicationContext as? Application) ?: application
                if (app == null || !NotificationDiagnosticHelper.isOverlayPermissionGranted(app)) {
                    safeInvoke(onUnavailable)
                    return@onMain
                }
                initialize(app)

                val themedContext = ContextThemeWrapper(app, R.style.Theme_UsstJwgl)
                val content = LayoutInflater.from(themedContext)
                    .inflate(R.layout.window_rollcall_overlay, null)
                content.findViewById<TextView>(R.id.tvOverlayCourseName).text =
                    courseName?.takeIf { it.isNotBlank() } ?: "当前课堂"
                content.findViewById<TextView>(R.id.tvOverlayType).text =
                    typeName.ifBlank { "课堂签到" }
                content.findViewById<View>(R.id.btnOverlayClose).setOnClickListener {
                    dismiss()
                }
                content.findViewById<View>(R.id.btnOverlayAction).setOnClickListener {
                    launchFromOverlay(app, rollcallId, actionIntent)
                    dismiss()
                }

                val windowManager = app.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    ?: throw IllegalStateException("WindowManager unavailable")
                val metrics = themedContext.resources.displayMetrics
                val width = minOf(
                    (360 * metrics.density).roundToInt(),
                    metrics.widthPixels - (32 * metrics.density).roundToInt()
                ).coerceAtLeast(1)

                @Suppress("DEPRECATION")
                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    width,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    dimAmount = 0.35f
                    gravity = Gravity.CENTER
                }

                windowManager.addView(content, params)
                check(content.parent != null) { "Overlay view did not attach" }

                activeView = content
                activeWindowManager = windowManager
                activeRollcallId = rollcallId

                if (vibrate) {
                    vibrateBriefly(app)
                }
                timeoutRunnable = Runnable {
                    if (activeView === content) {
                        dismissWindow()
                    }
                }.also { mainHandler.postDelayed(it, TIMEOUT_MS) }

                safeInvoke(onShown)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to show rollcall overlay", e)
                dismissWindow()
                safeInvoke(onUnavailable)
            }
        }
    }

    fun dismiss() {
        val request = requestSequence.incrementAndGet()
        onMain {
            if (requestSequence.get() == request) {
                dismissWindow()
            }
        }
    }

    fun dismissIfMatching(rollcallId: Long) {
        onMain {
            if (activeRollcallId == rollcallId) {
                dismissWindow()
            }
        }
    }

    private fun dismissWindow() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
        val view = activeView
        val wm = activeWindowManager
        activeView = null
        activeWindowManager = null
        activeRollcallId = null
        if (view != null && wm != null) {
            runCatching {
                wm.removeViewImmediate(view)
            }.onFailure {
                runCatching { wm.removeView(view) }
            }
        }
    }

    private fun launchFromOverlay(app: Application, rollcallId: Long, actionIntent: Intent) {
        val intentToLaunch = Intent(actionIntent).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val reqCode = ((rollcallId % 100000L).toInt()).coerceAtLeast(1) + 90000
        val pendingLaunched = runCatching {
            val pi = PendingIntent.getActivity(
                app,
                reqCode,
                intentToLaunch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                    .toBundle()
                pi.send(app, 0, null, null, null, null, options)
            } else {
                pi.send()
            }
            true
        }.onFailure {
            Log.w(TAG, "PendingIntent launch from overlay failed: ${it.message}")
        }.getOrDefault(false)

        if (pendingLaunched) return

        val directLaunched = runCatching {
            app.startActivity(intentToLaunch)
            true
        }.getOrDefault(false)
        if (directLaunched) return

        val mainLaunched = runCatching {
            app.startActivity(Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_TAB, 8)
            })
            true
        }.getOrDefault(false)
        if (!mainLaunched) {
            Toast.makeText(
                app,
                "请在顶部通知栏点击签到，或在系统诊断中开启「后台弹出界面」权限",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun vibrateBriefly(context: Context) {
        runCatching {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            val pattern = longArrayOf(0, 250, 100, 250)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    private fun safeInvoke(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Callback failed", e)
        }
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumedActivity = WeakReference(activity)
            if (activity is RollcallAlertActivity) {
                dismissWindow()
            }
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumedActivity?.get() === activity) {
                resumedActivity = null
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (resumedActivity?.get() === activity) {
                resumedActivity = null
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
