package cn.edu.usst.jwgl.ui.tronclass

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import cn.edu.usst.jwgl.data.model.LearningPlatform

/**
 * 学习平台轻量微徽标统一渲染器 (自适应浅色与深色模式)
 */
object LearningPlatformBadgeHelper {

    /**
     * 绑定平台微徽标
     * @param badgeView 目标微徽标 TextView
     * @param platform 平台来源枚举 (TRONCLASS / CHAOXING)
     * @param isMultiPlatformMode 是否处于双轨聚合模式。
     *        若为 false (单平台流)，badgeView 自动 View.GONE，布局完全回退为原畅课纯净态；
     *        若为 true (双轨融合流)，badgeView 显现，呈现对应平台色彩。
     */
    fun applyBadge(
        badgeView: TextView?,
        platform: LearningPlatform?,
        isMultiPlatformMode: Boolean = true
    ) {
        if (badgeView == null) return
        if (!isMultiPlatformMode || platform == null) {
            badgeView.visibility = View.GONE
            return
        }

        badgeView.visibility = View.VISIBLE
        badgeView.text = platform.shortName // "畅课" 或 "学习通"

        val context = badgeView.context
        val isDarkMode = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        // 浅色 / 深色主题精调微徽标配色
        val (bgColor, textColor) = if (isDarkMode) {
            when (platform) {
                LearningPlatform.TRONCLASS -> Color.parseColor("#134E4A") to Color.parseColor("#5EEAD4") // 深色 Teal 900 / Teal 300
                LearningPlatform.CHAOXING -> Color.parseColor("#7C2D12") to Color.parseColor("#FDBA74")  // 深色 Orange 900 / Orange 300
                LearningPlatform.DUAL -> Color.parseColor("#4C1D95") to Color.parseColor("#DDD6FE")      // 深色 Purple 900 / Purple 200
            }
        } else {
            platform.getBadgeBgColor() to platform.getBadgeTextColor() // 浅色 Teal 50/700, Orange 100/700
        }

        val density = context.resources.displayMetrics.density
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 4f * density // 4dp 微圆角
            setColor(bgColor)
        }

        badgeView.background = bgDrawable
        badgeView.setTextColor(textColor)
    }
}
