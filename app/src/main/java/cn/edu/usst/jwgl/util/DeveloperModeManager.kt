package cn.edu.usst.jwgl.util

import android.content.Context
import cn.edu.usst.jwgl.devaddon.DevAddonManager

/**
 * 开发者模式统一管理器
 *
 * 状态由同签名独立扩展包（bsf.usst.jwgl.devpack）及设置开关决定：
 * 安装兼容扩展包后自动激活；未安装或停用时所有扩展能力完全隐藏与禁用。
 */
object DeveloperModeManager {

    fun isDeveloperMode(context: Context): Boolean = DevAddonManager.isAddonActive(context)

    fun addListener(listener: (Boolean) -> Unit) = DevAddonManager.addListener(listener)

    fun removeListener(listener: (Boolean) -> Unit) = DevAddonManager.removeListener(listener)
}
