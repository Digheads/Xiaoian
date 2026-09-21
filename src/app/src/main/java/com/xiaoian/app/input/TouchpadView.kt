package com.xiaoian.app.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.min

/**
 * A laptop-style touchpad over the whole phone screen:
 *  - one finger moves the cursor (relative, faster with faster strokes);
 *  - a tap is a left click, a two-finger tap a right click;
 *  - two fingers moving together scroll;
 *  - tap, then touch again and drag: the left button stays down (drag & drop).
 *
 * While the keyboard is up, a touch here only closes it ([onTouchWhileTyping]
 * returns true) and the rest of that gesture is ignored.
 */
@SuppressLint("ViewConstructor")
class TouchpadView(
    context: Context,
    private val input: DesktopInput,
    private val onTouchWhileTyping: () -> Boolean,
) : View(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val density = resources.displayMetrics.density

    private var swallow = false
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveTime = 0L
    private var moved = false
    private var twoFinger = false
    private var dragging = false

    private var lastTapUpTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        textAlign = Paint.Align.CENTER
        textSize = 18 * density
    }
    private val subPaint = Paint(hintPaint).apply { textSize = 13 * density; color = 0x26FFFFFF }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawText("Touchpad", cx, cy, hintPaint)
        canvas.drawText("tap: click · two-finger tap: right click", cx, cy + 28 * density, subPaint)
        canvas.drawText("two fingers: scroll · tap, then drag: hold", cx, cy + 48 * density, subPaint)
    }

    private fun centroid(e: MotionEvent): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        val n = e.pointerCount
        for (i in 0 until n) { x += e.getX(i); y += e.getY(i) }
        return x / n to y / n
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swallow = onTouchWhileTyping()
                if (swallow) return true
                downTime = e.eventTime
                downX = e.x; downY = e.y
                lastX = e.x; lastY = e.y
                lastMoveTime = e.eventTime
                moved = false
                twoFinger = false
                // Second touch right after a tap, near it: drag with the button held.
                dragging = e.eventTime - lastTapUpTime < DOUBLE_TAP_MS &&
                    hypot(e.x - lastTapX, e.y - lastTapY) < slop * 4
                if (dragging) input.button(LEFT, true)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (swallow) return true
                if (e.pointerCount == 2 && !dragging) {
                    twoFinger = true
                    val (x, y) = centroid(e)
                    lastX = x; lastY = y
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (swallow) return true
                val (x, y) = if (twoFinger) centroid(e) else e.x to e.y
                val dx = x - lastX
                val dy = y - lastY
                if (!moved && hypot(x - downX, y - downY) > slop) moved = true
                if (!moved) return true
                if (twoFinger) {
                    // GestureDetector's sign: fingers up scroll the content down.
                    input.scroll(-dx * SCROLL_GAIN, -dy * SCROLL_GAIN)
                } else {
                    val dt = (e.eventTime - lastMoveTime).coerceAtLeast(1)
                    val speed = hypot(dx, dy) / dt / density          // dp per ms
                    val gain = BASE_GAIN * (1f + min(speed, 3f) * ACCEL)
                    input.move(dx * gain, dy * gain)
                }
                lastX = x; lastY = y
                lastMoveTime = e.eventTime
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (swallow) return true
                // Keep following the finger that stays, without a jump.
                val stay = if (e.actionIndex == 0) 1 else 0
                if (!twoFinger) { lastX = e.getX(stay); lastY = e.getY(stay) }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (swallow) { swallow = false; return true }
                val tap = !moved && e.eventTime - downTime < TAP_MS && e.actionMasked == MotionEvent.ACTION_UP
                when {
                    dragging -> {
                        input.button(LEFT, false)
                        dragging = false
                        lastTapUpTime = 0
                    }
                    twoFinger -> if (tap) input.click(RIGHT)
                    tap -> {
                        input.click(LEFT)
                        lastTapUpTime = SystemClock.uptimeMillis()
                        lastTapX = e.x; lastTapY = e.y
                    }
                }
                twoFinger = false
            }
        }
        return true
    }

    private companion object {
        const val LEFT = 1
        const val RIGHT = 3
        const val TAP_MS = 250L
        const val DOUBLE_TAP_MS = 300L
        const val BASE_GAIN = 1.2f
        const val ACCEL = 0.8f
        const val SCROLL_GAIN = 1f
    }
}
