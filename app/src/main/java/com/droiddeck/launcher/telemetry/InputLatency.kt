package com.droiddeck.launcher.telemetry

/**
 * Marks each touch and pad button the moment the app hands it to the guest, with the time Android
 * stamped the event (CLOCK_MONOTONIC ns). [LatencyLogComponent] points [sink] at the compositor, which
 * follows the mark to the next game frame and the screen; with no sink, as in JVM tests, marks are dropped.
 * The kinds match latency_probe.h.
 */
object InputLatency {
    const val TOUCH_DOWN = 0
    const val TOUCH_UP = 1
    const val PAD_DOWN = 2
    const val PAD_UP = 3
    val KINDS = listOf("touch_down", "touch_up", "pad_down", "pad_up")

    @Volatile var sink: ((kind: Int, eventNs: Long) -> Unit)? = null

    fun mark(kind: Int, eventNs: Long) {
        val to = sink ?: return
        try { to(kind, eventNs) } catch (e: Throwable) { sink = null }
    }
}
