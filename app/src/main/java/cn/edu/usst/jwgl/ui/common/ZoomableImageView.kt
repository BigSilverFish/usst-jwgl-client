package cn.edu.usst.jwgl.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * 通用多点触控与平移缩放视图（Zero-dependency Pinch-to-zoom ImageView）
 * 专为图片全屏预览与原生 PDF 页面阅读设计，在放大时自动请求父容器（如 RecyclerView）禁止拦截触控事件。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val currentMatrix = Matrix()
    private var scaleFactor = 1.0f
    private val minScale = 1.0f
    private val maxScale = 5.0f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    var onSingleTapListener: (() -> Unit)? = null
    var onLongPressListener: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scale = detector.scaleFactor
            val newScale = scaleFactor * scale
            if (newScale in minScale..maxScale) {
                scaleFactor = newScale
                currentMatrix.postScale(scale, scale, detector.focusX, detector.focusY)
                checkMatrixBounds()
                imageMatrix = currentMatrix
            }
            if (scaleFactor > 1.05f) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            return true
        }

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val targetScale = if (scaleFactor > 1.2f) 1.0f else 2.5f
            val factor = targetScale / scaleFactor
            scaleFactor = targetScale
            if (targetScale == 1.0f) {
                resetMatrix()
                parent?.requestDisallowInterceptTouchEvent(false)
            } else {
                currentMatrix.postScale(factor, factor, e.x, e.y)
                checkMatrixBounds()
                imageMatrix = currentMatrix
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTapListener?.invoke()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPressListener?.invoke()
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        post { resetMatrix() }
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        post { resetMatrix() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetMatrix()
    }

    fun resetMatrix() {
        val d = drawable ?: return
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (dw <= 0f || dh <= 0f || vw <= 0f || vh <= 0f) return

        val scale = minOf(vw / dw, vh / dh)
        val dx = (vw - dw * scale) / 2f
        val dy = (vh - dh * scale) / 2f

        currentMatrix.reset()
        currentMatrix.postScale(scale, scale)
        currentMatrix.postTranslate(dx, dy)
        scaleFactor = 1.0f
        imageMatrix = currentMatrix
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun checkMatrixBounds() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        currentMatrix.mapRect(rect)

        var deltaX = 0f
        var deltaY = 0f
        val vw = width.toFloat()
        val vh = height.toFloat()

        if (rect.width() >= vw) {
            if (rect.left > 0f) deltaX = -rect.left
            if (rect.right < vw) deltaX = vw - rect.right
        } else {
            deltaX = (vw - rect.width()) / 2f - rect.left
        }

        if (rect.height() >= vh) {
            if (rect.top > 0f) deltaY = -rect.top
            if (rect.bottom < vh) deltaY = vh - rect.bottom
        } else {
            deltaY = (vh - rect.height()) / 2f - rect.top
        }

        currentMatrix.postTranslate(deltaX, deltaY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = true
                if (scaleFactor > 1.05f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleDetector.isInProgress && scaleFactor > 1.05f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    currentMatrix.postTranslate(dx, dy)
                    checkMatrixBounds()
                    imageMatrix = currentMatrix
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                if (scaleFactor <= 1.05f) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        return true
    }
}
