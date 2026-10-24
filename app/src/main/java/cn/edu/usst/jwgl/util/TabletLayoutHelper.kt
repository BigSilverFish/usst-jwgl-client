package cn.edu.usst.jwgl.util

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

object TabletLayoutHelper {

    /**
     * 判断当前是否处于大屏（Pad/平板）横屏模式
     */
    fun isTabletLandscape(context: Context, config: Configuration? = null): Boolean {
        val cfg = config ?: context.resources.configuration
        return cfg.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                cfg.screenWidthDp >= 600
    }

    /**
     * 为大屏/横屏设备优化 BottomSheetDialog：
     * 1. 限制最大宽度为 560dp，避免在超宽屏幕上横向过度拉伸；
     * 2. 默认直接完全展开 (STATE_EXPANDED) 并跳过半折叠态，避免在横屏较矮高度下内容被截断。
     */
    fun setupBottomSheetForLargeScreen(dialog: Dialog?, context: Context, limitHeightRatio: Double = 0.0) {
        val bottomSheetDialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = BottomSheetBehavior.from(bottomSheet)
        val isLargeLand = isTabletLandscape(context) || context.resources.configuration.screenWidthDp >= 600

        if (isLargeLand) {
            val maxWidthPx = dpToPx(context, 560f)
            behavior.maxWidth = maxWidthPx
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        } else {
            behavior.skipCollapsed = true
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }

        if (limitHeightRatio > 0.0) {
            val ratio = if (isLargeLand) maxOf(limitHeightRatio, 0.86) else limitHeightRatio
            bottomSheet.layoutParams.height = (context.resources.displayMetrics.heightPixels * ratio).toInt()
            bottomSheet.requestLayout()
        }
    }

    fun dpToPx(context: Context, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }
}
