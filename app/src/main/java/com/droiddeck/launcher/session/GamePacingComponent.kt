package com.droiddeck.launcher.session

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.telemetry.GameExitWatch
import java.io.File
import java.util.Locale

/**
 * Reads the game-env's pacing lines from [logs] and runs [GamePacing.Governor] on Android's thermal
 * headroom forecast while a capped game with the governor on is running.
 */
class GamePacingComponent(private val logs: List<File>) : SessionPart() {
    @Volatile private var running = false
    private var stopped = false
    private var thread: Thread? = null

    @Synchronized override fun start() {
        if (stopped || running) return
        running = true
        thread = Thread({ run() }, "game-pacing").apply { isDaemon = true; start() }
    }

    @Synchronized override fun stop() {
        stopped = true
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        val power = app()?.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val tail = GameExitWatch.Tail(logs)
        val tracker = GamePacing.Tracker()
        var governor: GamePacing.Governor? = null
        var headroom = Float.NaN
        var headroomMs = 0L
        var nextSample = 0L
        try {
            while (running) {
                var changed = false
                tail.read { if (tracker.line(it)) changed = true }
                val plan = tracker.plan
                if (changed) {
                    governor = plan?.takeIf { it.governor }?.let { GamePacing.Governor(it.cap) }
                    if (plan != null && governor != null) {
                        Log.i(TAG, "thermal governor: ladder ${governor.ladder.joinToString(" → ")} fps, down at headroom " +
                            "${GamePacing.DOWN_AT} for ${GamePacing.DOWN_MS / 1000} s (next step ${GamePacing.SETTLE_MS / 1000} s after the last), up at ${GamePacing.UP_AT} for ${GamePacing.UP_MS / 1000} s")
                    }
                    GamePacing.publish(plan, plan?.cap ?: 0, if (plan == null) "game ended" else "game started")
                }
                val now = System.currentTimeMillis()
                val gov = governor
                if (plan != null && gov != null && power != null && now >= nextSample && Build.VERSION.SDK_INT >= 29) {
                    nextSample = now + SAMPLE_MS
                    val read = if (Build.VERSION.SDK_INT >= 30)
                        com.droiddeck.launcher.telemetry.HeadroomCache.shared.get { power.getThermalHeadroom(FORECAST_S) } ?: Float.NaN
                    else Float.NaN
                    if (!read.isNaN()) { headroom = read; headroomMs = now }
                    val recent = if (now - headroomMs <= STALE_MS) headroom else Float.NaN
                    val status = power.currentThermalStatus
                    gov.sample(recent, status, now)?.let { cap ->
                        val why = String.format(Locale.US, "thermal governor, headroom %.2f, status %d", recent, status)
                        Log.i(TAG, "thermal governor: cap $cap fps ($why)")
                        GamePacing.publish(plan, cap, why)
                    }
                }
                try { Thread.sleep(PERIOD_MS) } catch (_: InterruptedException) { break }
            }
        } catch (e: Exception) {
            Log.w(TAG, "game pacing stopped: $e")
        } finally {
            GamePacing.publish(null, 0, "session ended")
        }
    }

    private companion object {
        const val TAG = "GamePacing"
        const val PERIOD_MS = 1_000L
        const val SAMPLE_MS = 5_000L
        const val STALE_MS = 15_000L
        const val FORECAST_S = 10
    }
}
