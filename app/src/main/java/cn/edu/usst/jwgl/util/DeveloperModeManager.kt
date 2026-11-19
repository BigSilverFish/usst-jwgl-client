package cn.edu.usst.jwgl.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 开发者模式统一管理器
 *
 * 核心机制：
 * 1. 在底栏模式下从左到右依次点击五个图标（0:课表 -> 1:成绩 -> 2:畅课 -> 3:更多 -> 4:设置）
 * 2. 序列点完后进入 60 秒“就绪等待态”（带轻微震动反馈）
 * 3. 打开作者声明页，长按“我已了解”按钮，正式激活 / 关闭开发者模式！
 * 4. 控制【一机多签】、【自动签到】、【自动刷课】在未激活时的完全隐藏与禁用。
 */
object DeveloperModeManager {

    private const val PREF_NAME = "usst_developer_mode_pref"
    private const val KEY_DEV_MODE_ENABLED = "key_dev_mode_enabled"
    private const val STEP_TIMEOUT_MS = 4000L
    private const val ARMED_TIMEOUT_MS = 60_000L

    private var currentStep = 0
    private var lastClickTime = 0L

    private var isSequenceArmed = false
    private var armedTimestamp = 0L

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /**
     * 查询开发者模式是否已激活
     */
    fun isDeveloperMode(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return sp.getBoolean(KEY_DEV_MODE_ENABLED, false)
    }

    /**
     * 写入开发者模式状态
     */
    fun setDeveloperMode(context: Context, enabled: Boolean) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_DEV_MODE_ENABLED, enabled).apply()
        // 重置临时就绪状态
        resetSequence()
        // 通知所有观察者
        listeners.forEach { it.invoke(enabled) }
    }

    /**
     * 切换开发者模式状态
     */
    fun toggleDeveloperMode(context: Context): Boolean {
        val newState = !isDeveloperMode(context)
        setDeveloperMode(context, newState)
        return newState
    }

    /**
     * 监听底栏 5 个 Tab 的点击（0..4）
     * 依次为：0:课表, 1:成绩, 2:课程门户, 3:更多功能, 4:个人设置
     */
    fun onBottomTabClicked(context: Context, tabIndex: Int, onArmed: (() -> Unit)? = null) {
        val now = System.currentTimeMillis()
        if (now - lastClickTime > STEP_TIMEOUT_MS) {
            currentStep = 0
        }
        lastClickTime = now

        if (tabIndex == currentStep) {
            currentStep++
            if (currentStep == 5) {
                // 成功按序完成 0..4 点击！
                isSequenceArmed = true
                armedTimestamp = now
                currentStep = 0
                performShortVibration(context)
                onArmed?.invoke()
            }
        } else {
            // 顺序不匹配，如果用户点的是 0 则重新从第一步开始计算，否则清零
            currentStep = if (tabIndex == 0) 1 else 0
        }
    }

    /**
     * 检查当前是否满足长按激活的就绪条件（完成底栏从左到右点击且在 60 秒内）
     */
    fun isArmed(): Boolean {
        if (!isSequenceArmed) return false
        val now = System.currentTimeMillis()
        if (now - armedTimestamp > ARMED_TIMEOUT_MS) {
            resetSequence()
            return false
        }
        return true
    }

    fun resetSequence() {
        currentStep = 0
        isSequenceArmed = false
        armedTimestamp = 0L
    }

    fun addListener(listener: (Boolean) -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 触发轻微震动反馈
     */
    fun performShortVibration(context: Context, isStrong: Boolean = false) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val duration = if (isStrong) 150L else 40L
                    val amplitude = if (isStrong) VibrationEffect.DEFAULT_AMPLITUDE else 80
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(if (isStrong) 150L else 40L)
                }
            }
        } catch (_: Exception) {
            // 忽略无振动权限或设备异常
        }
    }
}
