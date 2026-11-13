package com.droiddeck.launcher.telemetry

import android.os.Process
import android.util.Log
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.hud.GameFrameFeed
import com.droiddeck.launcher.hud.HudFrames
import com.droiddeck.launcher.session.SessionEvents
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile

/**
 * Writes every frame gamescope publishes in the HUD ring to frames.csv for the whole session, shown
 * HUD or not, and marks the timeline's first Steam frame, first game frame, Steam sign-in, each
 * game's exit ([GameExitWatch]) and each launch droiddeck-game-env ended and started again because
 * Wine's desktop never came up.
 * Its own ring handle keeps its own read position, so the HUD's reader is untouched. Polls twice a
 * second on a background-priority thread; the ring holds 1024 frames, over 8 s at 120 fps.
 */
class FrameLogComponent(
    private val csv: File,
    private val ring: File,
    private val steamLogs: List<File> = emptyList(),
    private val stderrLogs: List<File> = emptyList(),
    private val onGamePlayed: () -> Unit = {},
    private val onGameExit: (GameExitWatch.Exit) -> Unit = {},
) : SessionPart() {
    @Volatile private var running = false
    private var stopped = false
    private var thread: Thread? = null

    @Synchronized override fun start() {
        if (stopped || running) return
        running = true
        thread = Thread({ run() }, "frame-log").apply { isDaemon = true; start() }
    }

    @Synchronized override fun stop() {
        stopped = true
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun run() {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
        val t0Ns = System.nanoTime()
        val t0Wall = System.currentTimeMillis()
        fun wall(ns: Long) = t0Wall + (ns - t0Ns) / 1_000_000
        val frametimes = LongArray(HudFrames.CAPACITY)
        val times = LongArray(HudFrames.CAPACITY)
        val info = IntArray(5)
        val signIn = SignInWatch(steamLogs)
        var handle = 0L
        var nextOpenNs = 0L
        var lastFrameNs = 0L
        var engine = ""
        var steam = false
        var steamSeen = false
        var gameSeen = false
        var launchedAt = 0L
        var playedSent = false
        var focusEvents = 0
        val exits = GameExitWatch(GameExitWatch::procAlive)
        val stderr = GameExitWatch.Tail(stderrLogs)
        var nextExitCheck = 0L
        try {
            BufferedWriter(FileWriter(csv, false), 64 * 1024).use { w ->
                val out = FrameCsvWriter(w, t0Ns, MAX_BYTES)
                out.header(t0Wall, "gamescope")
                while (running) {
                    val now = System.nanoTime()
                    if (handle == 0L && now >= nextOpenNs) {
                        nextOpenNs = now + REOPEN_NS
                        handle = runCatching { HudFrames.nativeOpen(ring.path) }.getOrDefault(0L)
                        if (handle != 0L) lastFrameNs = now
                    }
                    if (handle != 0L) {
                        val name = HudFrames.nativeInfo(handle, info)
                        if (name != null) {
                            engine = GameFrameFeed.engineLabel(name)
                            steam = info[4] != 0
                            exits.focus(info[0], engine, steam, System.currentTimeMillis())
                            if (out.info(now, engine, info[0], steam, info[1], info[2], info[3]) && focusEvents < MAX_FOCUS_EVENTS) {
                                focusEvents++
                                SessionEvents.record("frames.focus", mapOf("engine" to engine, "pid" to info[0], "steam" to steam))
                            }
                        }
                        val n = HudFrames.nativePoll(handle, frametimes, times)
                        if (n > 0) {
                            out.add(frametimes, times, n)
                            lastFrameNs = now
                            val first = wall(times[0])
                            if (steam && !steamSeen) {
                                steamSeen = true
                                SessionEvents.record("steam.ui_first_frame", at = first)
                            }
                            if (!steam && !gameSeen && name != null && engine != "GAMESCOPE") {
                                gameSeen = true
                                SessionEvents.record("game.first_frame", mapOf("engine" to engine, "pid" to info[0]), at = first)
                            }
                        } else if (now - lastFrameNs > STALE_NS) {
                            HudFrames.nativeClose(handle)
                            handle = 0L
                        }
                    }
                    out.flush()
                    signIn.poll()?.let { SessionEvents.record("steam.signed_in", at = it) }
                    val wallNow = System.currentTimeMillis()
                    if (wallNow >= nextExitCheck) {
                        nextExitCheck = wallNow + EXIT_PERIOD_MS
                        stderr.read {
                            exits.line(it, wallNow)
                            GameExitWatch.relaunchEvent(it)?.let { (event, fields) ->
                                SessionEvents.record(event, fields, at = fields["wall_ms"]?.toLongOrNull())
                            }
                            if (it.startsWith(EXPERIMENTS_LINE)) {
                                SessionEvents.record("game.experiments", experimentsFields(it))
                                launchedAt = wallNow
                                playedSent = false
                            }
                        }
                        for (exit in exits.poll(wallNow)) {
                            launchedAt = 0L
                            SessionEvents.record("game.exited", exit.fields().mapValues { (_, v) -> if (v is List<*>) org.json.JSONArray(v) else v })
                            runCatching { onGameExit(exit) }
                        }
                        // A session stopped while the game runs never sees its exit, so a long run counts on its own.
                        if (launchedAt > 0 && !playedSent && wallNow - launchedAt >= PLAYED_MS) {
                            playedSent = true
                            runCatching { onGamePlayed() }
                        }
                    }
                    try { Thread.sleep(PERIOD_MS) } catch (e: InterruptedException) { break }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame log stopped: $e")
        } finally {
            if (handle != 0L) HudFrames.nativeClose(handle)
        }
    }

    /** Steam writes this line to logs/connection_log.txt once a sign-in completes. */
    private class SignInWatch(private val logs: List<File>) {
        private val offsets = LongArray(logs.size) { logs[it].length() }
        private var done = logs.isEmpty()
        private var nextCheck = 0L

        fun poll(): Long? {
            val now = System.currentTimeMillis()
            if (done || now < nextCheck) return null
            nextCheck = now + SIGN_IN_PERIOD_MS
            logs.forEachIndexed { i, file ->
                val length = file.length()
                if (length < offsets[i]) offsets[i] = 0
                if (length == offsets[i]) return@forEachIndexed
                val from = maxOf(offsets[i], length - SIGN_IN_READ_MAX)
                val text = runCatching {
                    RandomAccessFile(file, "r").use { f ->
                        f.seek(from)
                        ByteArray((length - from).toInt()).also { f.readFully(it) }.toString(Charsets.ISO_8859_1)
                    }
                }.getOrNull() ?: return@forEachIndexed
                offsets[i] = length
                if (text.contains(SIGN_IN_LINE)) {
                    done = true
                    return now
                }
            }
            return null
        }
    }

    companion object {
        private const val TAG = "FrameLog"
        const val FILE = "frames.csv"
        private const val PERIOD_MS = 500L
        private const val PLAYED_MS = 120_000L
        private const val REOPEN_NS = 2_000_000_000L
        private const val STALE_NS = 5_000_000_000L
        private const val MAX_BYTES = 32L shl 20
        private const val MAX_FOCUS_EVENTS = 200
        private const val SIGN_IN_PERIOD_MS = 2_000L
        private const val EXIT_PERIOD_MS = 1_000L
        private const val EXPERIMENTS_LINE = "droiddeck-game-env: experiments: "
        private const val PENDING = "; saved but needs a new session: "

        /** droiddeck-game-env's per-launch line: the switches in effect, and any saved ones waiting for a new session. */
        internal fun experimentsFields(line: String): Map<String, String> {
            val text = line.removePrefix(EXPERIMENTS_LINE)
            val on = text.substringBefore(PENDING)
            return if (on == text) mapOf("on" to on) else mapOf("on" to on, "pending" to text.substringAfter(PENDING))
        }
        private const val SIGN_IN_READ_MAX = 1L shl 20
        private const val SIGN_IN_LINE = "RecvMsgClientLogOnResponse() : processing complete"
    }
}
