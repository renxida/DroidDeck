package com.droiddeck.launcher.input

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns one finger on a swipe pad into d-pad presses. A flick of at least [threshold] px within [FLICK_MS] presses the
 * d-pad once in its main direction, the moment it crosses the threshold, and the flick starts again from there, so the
 * next flick fires without a lift. Holding on after a flick repeats the press after [FIRST_REPEAT_MS], then every
 * [REPEAT_MS], until the finger lifts or flicks again. A long flick fires once: the same direction fires again only after
 * the finger has rested (moved less than [rest] px in [REST_MS]).
 *
 * The game polls the pad once a frame, so each press is held [PRESS_MS] and a direction is let go for at least [GAP_MS]
 * before it is pressed again. Times are the caller's clock in milliseconds; [advance] runs the presses due by then and
 * must be called again by [deadline].
 */
class SwipePadDetector(private val threshold: Float, private val rest: Float, private val press: (InputControl, Boolean) -> Unit) {
    private val xs = FloatArray(CAPACITY)
    private val ys = FloatArray(CAPACITY)
    private val ts = LongArray(CAPACITY)
    private var first = 0
    private var count = 0
    private var tracking = false
    private var fired: InputControl? = null
    private var rested = true
    private var held: InputControl? = null
    private var releaseAt = NONE
    private var released: InputControl? = null
    private var releasedAt = 0L
    private var pending: InputControl? = null
    private var pendingAt = NONE
    private var repeating: InputControl? = null
    private var repeatAt = NONE

    /** When [advance] next has work, or [Long.MAX_VALUE] for never. */
    val deadline: Long get() = minOf(releaseAt, pendingAt, repeatAt)

    fun down(x: Float, y: Float, time: Long) {
        advance(time)
        repeating = null; repeatAt = NONE
        tracking = true
        fired = null
        rested = true
        anchor(x, y, time)
    }

    fun move(x: Float, y: Float, time: Long) {
        if (!tracking) return
        advance(time)
        if (time - ts[index(count - 1)] >= REST_MS) rested = true
        add(x, y, time)
        while (count > 1 && ts[first] < time - FLICK_MS) { first = (first + 1) % CAPACITY; count-- }
        var far = -1f
        var dx = 0f
        var dy = 0f
        var still: Float? = null
        for (i in count - 2 downTo 0) {
            val at = index(i)
            val d = hypot(x - xs[at], y - ys[at])
            if (d > far) { far = d; dx = x - xs[at]; dy = y - ys[at] }
            if (still == null && ts[at] <= time - REST_MS) still = d
        }
        if (still != null && still < rest) rested = true
        if (far < threshold) return
        val direction = if (abs(dx) >= abs(dy)) { if (dx > 0) InputControl.RIGHT else InputControl.LEFT }
            else if (dy > 0) InputControl.DOWN else InputControl.UP
        anchor(x, y, time)
        if (direction == fired && !rested) return
        fired = direction
        rested = false
        repeating = direction
        repeatAt = time + FIRST_REPEAT_MS
        pressNow(direction, time)
    }

    /** The finger lifted: repeats stop, and a press under way is still held for its full time. */
    fun up(time: Long) {
        advance(time)
        tracking = false
        repeating = null; repeatAt = NONE
    }

    /** The touch was taken away: everything stops and a held direction is let go now. */
    fun cancel(time: Long) {
        tracking = false
        repeating = null; repeatAt = NONE
        pending = null; pendingAt = NONE
        held?.let { release(it, time) }
    }

    fun advance(now: Long) {
        while (true) {
            val at = deadline
            if (at > now) return
            when (at) {
                releaseAt -> release(held!!, at)
                pendingAt -> pending!!.let { pending = null; pendingAt = NONE; pressNow(it, at) }
                else -> { repeatAt = at + REPEAT_MS; pressNow(repeating!!, at) }
            }
        }
    }

    private fun pressNow(direction: InputControl, time: Long) {
        held?.let { release(it, time) }
        val ready = if (released == direction) releasedAt + GAP_MS else time
        if (ready > time) { pending = direction; pendingAt = ready; return }
        pending = null; pendingAt = NONE
        held = direction
        releaseAt = time + PRESS_MS
        press(direction, true)
    }

    private fun release(direction: InputControl, time: Long) {
        held = null
        releaseAt = NONE
        released = direction
        releasedAt = time
        press(direction, false)
    }

    private fun anchor(x: Float, y: Float, time: Long) {
        first = 0; count = 0
        add(x, y, time)
    }

    private fun add(x: Float, y: Float, time: Long) {
        if (count == CAPACITY) { first = (first + 1) % CAPACITY; count-- }
        val at = index(count)
        xs[at] = x; ys[at] = y; ts[at] = time
        count++
    }

    private fun index(i: Int) = (first + i) % CAPACITY

    companion object {
        const val FLICK_MM = 4f
        const val REST_MM = .5f
        const val FLICK_MS = 200L
        const val FIRST_REPEAT_MS = 350L
        const val REPEAT_MS = 110L
        const val PRESS_MS = 50L
        const val GAP_MS = 50L
        const val REST_MS = 50L
        private const val CAPACITY = 64
        private const val NONE = Long.MAX_VALUE
    }
}
