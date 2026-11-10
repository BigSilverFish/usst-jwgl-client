package cn.edu.usst.jwgl.ui.tronclass

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView

/** Keep recycling inside the scrolling home header instead of measuring every row. */
class ViewportRecyclerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : RecyclerView(context, attrs, defStyleAttr) {
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        var ancestor = parent
        while (ancestor != null && ancestor !is NestedScrollView) ancestor = ancestor.parent
        val viewport = (ancestor as? View)?.height?.takeIf { it > 0 }
            ?: (ancestor as? View)?.measuredHeight?.takeIf { it > 0 }
            ?: rootView.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        val bounded = if (View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.UNSPECIFIED)
            View.MeasureSpec.makeMeasureSpec(viewport.coerceAtLeast(1), View.MeasureSpec.AT_MOST)
        else heightSpec
        super.onMeasure(widthSpec, bounded)
    }
}
