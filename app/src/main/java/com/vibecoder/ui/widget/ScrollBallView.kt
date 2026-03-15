package com.vibecoder.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import kotlin.math.*

/**
 * 滑动球视图 - 支持垂直滑动和拖动
 * 向上滑动 = 滚轮向上，向下滑动 = 滚轮向下
 */
class ScrollBallView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 外观属性
    var ballAlpha: Float = 0.6f
        set(value) {
            field = value.coerceIn(0.1f, 1.0f)
            invalidate()
        }

    var ballColor: Int = Color.parseColor("#4CAF50")
        set(value) {
            field = value
            invalidate()
        }

    var ballRadius: Float = 40f
        set(value) {
            field = value
            invalidate()
        }

    // 滚动回调
    var onScrollUp: (() -> Unit)? = null
    var onScrollDown: (() -> Unit)? = null
    var onPositionChanged: ((x: Float, y: Float) -> Unit)? = null

    // 拖动状态
    private var isDragging = false
    private var isScrolling = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var lastY = 0f
    private var scrollAccumulator = 0f

    // 滚动阈值 - 滑动多少像素触发一次滚动
    private val SCROLL_THRESHOLD = 30f

    // 视觉反馈
    private var pulseAnimator: ValueAnimator? = null
    private var pulseScale = 1f
    private var scrollDirection = 0 // -1 上, 0 无, 1 下

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    init {
        setOnTouchListener { _, event -> handleTouch(event) }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                isScrolling = false
                dragOffsetX = event.rawX - x
                dragOffsetY = event.rawY - y
                lastY = event.rawY
                scrollAccumulator = 0f
                startPulse()
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val deltaX = event.rawX - dragOffsetX - x
                    val deltaY = event.rawY - dragOffsetY - y

                    // 判断是拖动还是滚动
                    if (!isScrolling && abs(deltaY) > 20) {
                        isScrolling = true
                    }

                    if (isScrolling) {
                        // 滚动模式：检测垂直滑动
                        val scrollDelta = event.rawY - lastY
                        scrollAccumulator += scrollDelta
                        lastY = event.rawY

                        // 累计超过阈值时触发滚动
                        while (scrollAccumulator >= SCROLL_THRESHOLD) {
                            scrollAccumulator -= SCROLL_THRESHOLD
                            scrollDirection = 1
                            onScrollDown?.invoke()
                            invalidate()
                        }
                        while (scrollAccumulator <= -SCROLL_THRESHOLD) {
                            scrollAccumulator += SCROLL_THRESHOLD
                            scrollDirection = -1
                            onScrollUp?.invoke()
                            invalidate()
                        }
                    } else {
                        // 拖动模式：更新位置
                        val newX = event.rawX - dragOffsetX
                        val newY = event.rawY - dragOffsetY
                        val parent = parent as? View
                        val maxX = (parent?.width?.toFloat() ?: Float.MAX_VALUE) - width
                        val maxY = (parent?.height?.toFloat() ?: Float.MAX_VALUE) - height

                        x = newX.coerceIn(0f, maxX)
                        y = newY.coerceIn(0f, maxY)
                        onPositionChanged?.invoke(x, y)
                    }
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                isScrolling = false
                scrollDirection = 0
                scrollAccumulator = 0f
                stopPulse()
                invalidate()
                true
            }
            else -> false
        }
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(1f, 1.15f, 1f).apply {
            duration = 600
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulseScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseScale = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val radius = ballRadius * pulseScale

        // 绘制外圈光晕
        paint.color = adjustAlpha(ballColor, ballAlpha * 0.3f)
        canvas.drawCircle(centerX, centerY, radius * 1.3f, paint)

        // 绘制主球体
        paint.color = adjustAlpha(ballColor, ballAlpha)
        canvas.drawCircle(centerX, centerY, radius, paint)

        // 绘制边框
        strokePaint.color = adjustAlpha(Color.WHITE, ballAlpha * 0.5f)
        canvas.drawCircle(centerX, centerY, radius, strokePaint)

        // 绘制滚动方向指示
        if (scrollDirection != 0 && isScrolling) {
            val indicatorColor = if (scrollDirection > 0) {
                Color.parseColor("#FF9800") // 橙色 - 向下
            } else {
                Color.parseColor("#2196F3") // 蓝色 - 向上
            }
            paint.color = adjustAlpha(indicatorColor, 0.8f)
            canvas.drawCircle(centerX, centerY, radius * 0.5f, paint)

            // 绘制箭头
            val arrow = if (scrollDirection > 0) "↓" else "↑"
            textPaint.color = Color.WHITE
            canvas.drawText(arrow, centerX, centerY + 8, textPaint)
        } else {
            // 绘制滚动提示（上下箭头）
            textPaint.color = adjustAlpha(Color.WHITE, ballAlpha * 0.7f)
            textPaint.textSize = 14f
            canvas.drawText("↕", centerX, centerY + 5, textPaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (ballRadius * 2.6f).toInt()
        setMeasuredDimension(size, size)
    }

    private fun adjustAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    // 保存和恢复位置
    fun savePosition(): Pair<Float, Float> = Pair(x, y)

    fun restorePosition(pos: Pair<Float, Float>) {
        x = pos.first
        y = pos.second
    }
}