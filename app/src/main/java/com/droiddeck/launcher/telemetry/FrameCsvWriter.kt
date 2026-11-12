package com.droiddeck.launcher.telemetry

import java.io.Writer

/**
 * frames.csv: one line per game frame, `t_ms,frametime_us`, with t_ms on CLOCK_MONOTONIC relative to
 * [t0Ns]. Lines starting with `#` carry the header and focus changes (engine, pid, Steam focus).
 * Output is ASCII, so characters count bytes. Past [maxBytes] it writes one note and stops.
 */
internal class FrameCsvWriter(private val out: Writer, private val t0Ns: Long, private val maxBytes: Long) {
    private val line = StringBuilder(64)
    private var lastInfo: String? = null
    private var lastFrameNs = t0Ns

    var bytes = 0L
        private set
    var frames = 0L
        private set
    var capped = false
        private set

    fun header(t0WallMs: Long, source: String) {
        write("# frames t0_wall_ms=$t0WallMs t0_mono_ns=$t0Ns source=${clean(source)}\n")
        write("t_ms,frametime_us\n")
    }

    fun info(nowNs: Long, engine: String, pid: Int, steam: Boolean, width: Int, height: Int, refreshMilliHz: Int): Boolean {
        val desc = "engine=${clean(engine).ifEmpty { "none" }} pid=$pid steam=${if (steam) 1 else 0} " +
            "output=${width}x$height@${refreshMilliHz}mHz"
        if (desc == lastInfo) return false
        lastInfo = desc
        write("# t_ms=${ms(nowNs)} $desc\n")
        return true
    }

    /** Frames at or before the last one written (an earlier session's ring, or a reopened ring read again) are skipped. */
    fun add(frametimesNs: LongArray, timesNs: LongArray, n: Int) {
        for (i in 0 until n) {
            if (capped) return
            if (timesNs[i] <= lastFrameNs || frametimesNs[i] <= 0L) continue
            lastFrameNs = timesNs[i]
            line.setLength(0)
            line.append(ms(timesNs[i])).append(',').append(frametimesNs[i] / 1000).append('\n')
            if (write(line)) frames++
        }
    }

    fun flush() = out.flush()

    private fun ms(ns: Long): Long = (ns - t0Ns) / 1_000_000

    private fun write(text: CharSequence): Boolean {
        if (capped) return false
        if (bytes + text.length > maxBytes - CAP_NOTE_RESERVE) {
            capped = true
            val note = "# capped at $maxBytes bytes after $frames frames\n"
            out.write(note)
            bytes += note.length
            return false
        }
        out.append(text)
        bytes += text.length
        return true
    }

    companion object {
        private const val CAP_NOTE_RESERVE = 96L

        fun clean(value: String): String = value.map { if (it.isLetterOrDigit() && it.code < 128 || it in "._-") it else '_' }
            .joinToString("").take(40)
    }
}
