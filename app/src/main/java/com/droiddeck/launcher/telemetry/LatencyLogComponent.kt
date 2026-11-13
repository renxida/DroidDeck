package com.droiddeck.launcher.telemetry

import android.os.Process
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.session.GamePacing
import com.droiddeck.launcher.session.SessionEvents
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Writes latency.csv ([LatencyCsvWriter]) from the compositor's input probes and records an
 * input.latency event ([LatencySummary]) each minute that had input. Polls twice a second on a
 * background-priority thread.
 */
class LatencyLogComponent(private val csv: File) : SessionPart() {
    @Volatile private var running = false
    private var stopped = false
    private var thread: Thread? = null

    @Synchronized override fun start() {
        if (stopped || running) return
        running = true
        InputLatency.sink = { kind, eventNs -> WaylandCompositor.nativeInputMark(kind, eventNs) }
        thread = Thread({ run() }, "latency-log").apply { isDaemon = true; start() }
    }

    @Synchronized override fun stop() {
        stopped = true
        running = false
        InputLatency.sink = null
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        val t0Ns = System.nanoTime()
        val rows = LongArray(BATCH * LatencyCsvWriter.Row.FIELDS)
        var summary = LatencySummary()
        var summaryFrom = t0Ns
        var timingNoted = false
        var droppedSeen = 0
        try {
            BufferedWriter(FileWriter(csv, false), 16 * 1024).use { w ->
                val out = LatencyCsvWriter(w, t0Ns, MAX_BYTES)
                out.header(System.currentTimeMillis())
                fun report(dropped: Int) {
                    if (!summary.empty) runCatching { SessionEvents.record("input.latency", summary.fields(dropped)) }
                    summary = LatencySummary()
                }
                var stopping = false
                while (!stopping) {
                    stopping = !running
                    val cap = GamePacing.cap
                    var n: Int
                    do {
                        n = runCatching { WaylandCompositor.nativeLatencyDrain(rows) }.getOrDefault(0)
                        if (n > 0 && !timingNoted) {
                            timingNoted = true
                            val on = runCatching { WaylandCompositor.nativeDisplayTiming() }.getOrDefault(false)
                            out.note(System.nanoTime(), "display_timing=${if (on) "on" else "off (rows end at the present)"}")
                        }
                        for (i in 0 until n) {
                            val row = LatencyCsvWriter.Row.read(rows, i)
                            out.add(row, cap)
                            summary.add(row, cap)
                        }
                    } while (n == BATCH)
                    out.flush()
                    val now = System.nanoTime()
                    if (now - summaryFrom >= SUMMARY_NS || stopping) {
                        val dropped = runCatching { WaylandCompositor.nativeLatencyDropped() }.getOrDefault(droppedSeen)
                        report(dropped - droppedSeen)
                        droppedSeen = dropped
                        summaryFrom = now
                    }
                    if (!stopping) try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "latency log stopped: $e")
        }
    }

    companion object {
        private const val TAG = "LatencyLog"
        const val FILE = "latency.csv"
        private const val PERIOD_MS = 500L
        private const val SUMMARY_NS = 60_000_000_000L
        private const val BATCH = 64
        private const val MAX_BYTES = 4L shl 20
    }
}
