package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.droiddeck.launcher.telemetry.InputLatency
import kotlin.math.abs

class DirectTouchHold(
    private val slopPx: Float,
    private val listener: PointerGestures.Listener,
    private val touch: Touch,
) {
    interface Touch {
        fun down(pointerId: Int, x: Float, y: Float)
        fun move(pointerId: Int, x: Float, y: Float)
        fun up(pointerId: Int, x: Float, y: Float)
        fun cancel()
    }

    /**
     * Quick taps: the down reaches the game the moment the finger lands, instead of when it lifts or
     * moves, and the lift follows once the down has been in the game [QUICK_GAP_MS], instead of [LIFT_MS]
     * after the finger. A long press still clicks right: the touch the game already has is cancelled first.
     */
    var quickLift = false

    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var downId = 0
    private var downEventMs = 0L
    private var upEventMs = 0L
    private var forwardedAtMs = 0L
    private var delayMs = 0L
    private var holdBehavior = TouchGestureConfig.MOUSE_BEHAVIOR_CLICK
    private var forwarded = false
    private var held = false
    private var pressing = false
    private var upX = 0f
    private var upY = 0f
    private var liftId = 0
    private var liftPending = false
    private val lift = Runnable {
        liftPending = false
        if (forwarded) {
            touch.up(liftId, upX, upY)
            InputLatency.mark(InputLatency.TOUCH_UP, upEventMs * 1_000_000L)
            forwarded = false
        }
    }
    private val release = Runnable {
        if (pressing) listener.onButton(PointerGestures.BTN_RIGHT, false, downX, downY)
        pressing = false
    }
    private val hold = Runnable {
        if (forwarded) {
            touch.cancel()
            forwarded = false
        }
        held = true
        listener.onLongPress()
        listener.onButton(PointerGestures.BTN_RIGHT, true, downX, downY)
        pressing = true
        if (holdBehavior != TouchGestureConfig.MOUSE_BEHAVIOR_HOLD) handler.postDelayed(release, 50L)
    }

    fun configure(config: TouchGestureConfig) {
        cancel()
        delayMs = config.longPressDelay.toLong().coerceAtLeast(1L)
        holdBehavior = config.directHoldBehavior
    }

    fun cancel() {
        handler.removeCallbacks(hold)
        handler.removeCallbacks(release)
        handler.removeCallbacks(lift)
        liftPending = false
        release.run()
        if (forwarded) touch.cancel()
        forwarded = false
        held = false
    }

    fun cancelPendingHold() {
        handler.removeCallbacks(hold)
    }

    fun cancelTouch() {
        handler.removeCallbacks(hold)
        handler.removeCallbacks(lift)
        liftPending = false
        if (forwarded) touch.cancel()
        forwarded = false
    }

    fun onTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (liftPending) {
                handler.removeCallbacks(lift)
                lift.run()
            }
            cancel()
            downX = event.x
            downY = event.y
            downId = event.getPointerId(0)
            downEventMs = event.eventTime
            if (holdBehavior == TouchGestureConfig.MOUSE_BEHAVIOR_NONE) forwardDown()
            else {
                if (quickLift) forwardDown()
                handler.postDelayed(hold, delayMs)
            }
            return true
        }
        if (held) {
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> { release.run(); held = false }
                MotionEvent.ACTION_CANCEL -> cancel()
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(hold)
                if (!forwarded) forwardDown()
                val index = event.actionIndex
                touch.down(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || abs(event.x - downX) > slopPx || abs(event.y - downY) > slopPx) {
                    // A drag is never a long press, also when a quick tap's down is already in the game.
                    handler.removeCallbacks(hold)
                    if (!forwarded) forwardDown()
                }
                if (forwarded) for (index in 0 until event.pointerCount)
                    touch.move(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                touch.up(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(hold)
                if (!forwarded) forwardDown()
                upX = event.x
                upY = event.y
                liftId = event.getPointerId(event.actionIndex)
                upEventMs = event.eventTime
                liftPending = true
                val wait = if (quickLift) forwardedAtMs + QUICK_GAP_MS - SystemClock.uptimeMillis() else LIFT_MS
                if (wait > 0) handler.postDelayed(lift, wait) else lift.run()
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    private fun forwardDown() {
        touch.down(downId, downX, downY)
        forwarded = true
        forwardedAtMs = SystemClock.uptimeMillis()
        InputLatency.mark(InputLatency.TOUCH_DOWN, downEventMs * 1_000_000L)
    }

    private companion object {
        const val LIFT_MS = 50L
        const val QUICK_GAP_MS = 20L
    }
}
