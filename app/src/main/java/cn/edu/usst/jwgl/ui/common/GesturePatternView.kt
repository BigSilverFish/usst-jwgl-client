package cn.edu.usst.jwgl.ui.common

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import cn.edu.usst.jwgl.R
import kotlin.math.hypot
import kotlin.math.min

/**
 * 超星手势签到九宫格绘制控件 (GesturePatternView)
 * 映射标准 3x3 锁屏矩阵 (从左至右、从上至下编号 1 到 9)。
 * 支持手指滑动连线、自动补全穿透中间点、震动反馈与图案输出。
 */
class GesturePatternView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private data class Dot(
        val index: Int, // 1..9
        val row: Int,   // 0..2
        val col: Int,   // 0..2
        var x: Float = 0f,
        var y: Float = 0f
    )

    private val dots = ArrayList<Dot>(9)
    private val selectedDots = ArrayList<Dot>(9)

    private var currentTouchX: Float? = null
    private var currentTouchY: Float? = null

    // 颜色配置
    private var primaryColor: Int = Color.parseColor("#8C1D27")
    private var primaryLightColor: Int = Color.parseColor("#268C1D27")
    private var defaultDotColor: Int = Color.parseColor("#808080")
    private var defaultRingColor: Int = Color.parseColor("#20808080")

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val ringStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dpToPx(11f)
    }

    private var hitRadius = dpToPx(36f)
    private var dotRadius = dpToPx(6.5f)
    private var selectedDotRadius = dpToPx(8.5f)
    private var selectedRingRadius = dpToPx(24f)

    var onPatternCompletedListener: ((String) -> Unit)? = null
    var onPatternChangedListener: ((String) -> Unit)? = null

    init {
        resolveThemeColors()

        linePaint.color = primaryColor
        linePaint.strokeWidth = dpToPx(4.5f)

        ringStrokePaint.color = primaryColor
        ringStrokePaint.strokeWidth = dpToPx(2f)

        // 初始化 9 个点：1 到 9
        for (r in 0..2) {
            for (c in 0..2) {
                val idx = r * 3 + c + 1
                dots.add(Dot(index = idx, row = r, col = c))
            }
        }
    }

    private fun resolveThemeColors() {
        val tv = TypedValue()
        if (context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true)) {
            primaryColor = tv.data
            primaryLightColor = Color.argb(45, Color.red(primaryColor), Color.green(primaryColor), Color.blue(primaryColor))
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val availableW = w - paddingLeft - paddingRight
        val availableH = h - paddingTop - paddingBottom
        val size = min(availableW, availableH).toFloat()
        if (size <= 0f) return

        val spacing = size / 3.4f
        val startX = paddingLeft + (availableW - spacing * 2) / 2f
        val startY = paddingTop + (availableH - spacing * 2) / 2f

        hitRadius = spacing * 0.42f
        selectedRingRadius = spacing * 0.35f

        for (dot in dots) {
            dot.x = startX + dot.col * spacing
            dot.y = startY + dot.row * spacing
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. 绘制已连接的线条
        if (selectedDots.size > 1) {
            val path = Path()
            path.moveTo(selectedDots[0].x, selectedDots[0].y)
            for (i in 1 until selectedDots.size) {
                path.lineTo(selectedDots[i].x, selectedDots[i].y)
            }
            canvas.drawPath(path, linePaint)
        }

        // 2. 绘制从最后一个连接点到当前触摸点的延伸线
        val last = selectedDots.lastOrNull()
        val tx = currentTouchX
        val ty = currentTouchY
        if (last != null && tx != null && ty != null) {
            canvas.drawLine(last.x, last.y, tx, ty, linePaint)
        }

        // 3. 绘制 9 个圆点及光晕状态
        for (dot in dots) {
            val isSelected = selectedDots.contains(dot)
            if (isSelected) {
                // 选中态：外圈半透明光晕 + 外圈实线 + 中心实心圆
                ringPaint.color = primaryLightColor
                canvas.drawCircle(dot.x, dot.y, selectedRingRadius, ringPaint)

                ringStrokePaint.color = primaryColor
                canvas.drawCircle(dot.x, dot.y, selectedRingRadius, ringStrokePaint)

                dotPaint.color = primaryColor
                canvas.drawCircle(dot.x, dot.y, selectedDotRadius, dotPaint)
            } else {
                // 未选中态：浅色轮廓圈 + 中心微圆点
                ringPaint.color = defaultRingColor
                canvas.drawCircle(dot.x, dot.y, selectedRingRadius * 0.65f, ringPaint)

                dotPaint.color = defaultDotColor
                canvas.drawCircle(dot.x, dot.y, dotRadius, dotPaint)
            }

            // 绘制数字标识（1 到 9）在右下角或微偏置
            textPaint.color = if (isSelected) primaryColor else defaultDotColor
            textPaint.alpha = if (isSelected) 220 else 120
            val textY = dot.y + selectedRingRadius * 0.95f
            canvas.drawText(dot.index.toString(), dot.x, textY, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                clearPatternInternal(notify = false)
                val hit = findHitDot(event.x, event.y)
                if (hit != null) {
                    addDotToPath(hit)
                }
                currentTouchX = event.x
                currentTouchY = event.y
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                currentTouchX = event.x
                currentTouchY = event.y
                val hit = findHitDot(event.x, event.y)
                if (hit != null && !selectedDots.contains(hit)) {
                    val last = selectedDots.lastOrNull()
                    if (last != null) {
                        val intermediate = findIntermediateDot(last, hit)
                        if (intermediate != null && !selectedDots.contains(intermediate)) {
                            addDotToPath(intermediate)
                        }
                    }
                    addDotToPath(hit)
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                currentTouchX = null
                currentTouchY = null
                invalidate()
                if (selectedDots.isNotEmpty()) {
                    val pattern = getSelectedPattern()
                    onPatternCompletedListener?.invoke(pattern)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findHitDot(x: Float, y: Float): Dot? {
        for (dot in dots) {
            val d = hypot((x - dot.x).toDouble(), (y - dot.y).toDouble()).toFloat()
            if (d <= hitRadius) {
                return dot
            }
        }
        return null
    }

    /**
     * 判断两点之间是否存在跨越的中间点并返回（例如 1 与 3 之间有 2）
     */
    private fun findIntermediateDot(from: Dot, to: Dot): Dot? {
        val middleRow = (from.row + to.row)
        val middleCol = (from.col + to.col)
        // 只有两坐标和均为偶数时才存在正中心的中间点
        if (middleRow % 2 == 0 && middleCol % 2 == 0) {
            val r = middleRow / 2
            val c = middleCol / 2
            return dots.find { it.row == r && it.col == c }
        }
        return null
    }

    private fun addDotToPath(dot: Dot) {
        selectedDots.add(dot)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onPatternChangedListener?.invoke(getSelectedPattern())
    }

    fun clearPattern() {
        clearPatternInternal(notify = true)
        invalidate()
    }

    private fun clearPatternInternal(notify: Boolean) {
        selectedDots.clear()
        currentTouchX = null
        currentTouchY = null
        if (notify) {
            onPatternChangedListener?.invoke("")
        }
    }

    fun setPattern(patternCode: String) {
        clearPatternInternal(notify = false)
        for (ch in patternCode) {
            val idx = ch.digitToIntOrNull() ?: continue
            val dot = dots.find { it.index == idx } ?: continue
            if (!selectedDots.contains(dot)) {
                selectedDots.add(dot)
            }
        }
        invalidate()
        onPatternChangedListener?.invoke(getSelectedPattern())
    }

    fun getSelectedPattern(): String = selectedDots.map { it.index }.joinToString("")

    fun getPatternDisplay(): String = selectedDots.map { it.index }.joinToString(" ➔ ")

    private fun dpToPx(dp: Float): Float {
        return dp * resources.displayMetrics.density
    }
}
