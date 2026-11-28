package com.droiddeck.launcher.telemetry

import java.io.Writer

/**
 * latency.csv: one line per touch or pad button the app handed to the guest. t_ms is the Android event
 * time on CLOCK_MONOTONIC relative to [t0Ns]; every other time is microseconds after that event, empty
 * when it never came:
 *  - inject_us: touch delivered to gamescope by the compositor, pad state published to the guest's ring;
 *  - commit_us: the first new game frame the compositor received after the inject. A lower bound on the
 *    game's reaction: the frame may have been started before the input arrived;
 *  - present_us: the composited frame carrying that game frame handed to the screen;
 *  - display_us: when that frame reached the panel (VK_GOOGLE_display_timing), empty without it.
 * present_id, swap_pending (presents not yet reported shown, -1 unknown), refresh_us and cap (the game
 * frame cap in force, 0 none) describe the queue the frame went through.
 * Rows come in the order inputs finish, not strictly by t_ms. Past [maxBytes] it writes one note and stops.
 */
internal class LatencyCsvWriter(private val out: Writer, private val t0Ns: Long, private val maxBytes: Long) {
    private val line = StringBuilder(96)
    var bytes = 0L
        private set
    var rows = 0L
        private set
    var capped = false
        private set

    fun header(t0WallMs: Long) {
        write("# latency t0_wall_ms=$t0WallMs t0_mono_ns=$t0Ns\n")
        write("$COLUMNS\n")
    }

    fun note(nowNs: Long, text: String) {
        write("# t_ms=${(nowNs - t0Ns) / 1_000_000} $text\n")
    }

    fun add(row: Row, cap: Int) {
        if (capped) return
        line.setLength(0)
        line.append((row.eventNs - t0Ns) / 1_000_000).append(',').append(InputLatency.KINDS.getOrElse(row.kind) { "unknown" })
        for (at in longArrayOf(row.injectNs, row.commitNs, row.presentNs, row.displayNs)) {
            line.append(',')
            if (at > 0) line.append((at - row.eventNs) / 1000)
        }
        line.append(',').append(row.presentId).append(',').append(row.swapPending)
            .append(',').append(row.refreshNs / 1000).append(',').append(cap).append('\n')
        if (write(line)) rows++
    }

    fun flush() = out.flush()

    private fun write(text: CharSequence): Boolean {
        if (capped) return false
        if (bytes + text.length > maxBytes - CAP_NOTE_RESERVE) {
            capped = true
            val note = "# capped at $maxBytes bytes after $rows inputs\n"
            out.write(note)
            bytes += note.length
            return false
        }
        out.append(text)
        bytes += text.length
        return true
    }

    /** One probe from latency_probe.h: CLOCK_MONOTONIC ns, 0 for a stage that never came. */
    data class Row(val kind: Int, val eventNs: Long, val injectNs: Long, val commitNs: Long, val presentNs: Long,
                   val displayNs: Long, val presentId: Long, val swapPending: Int, val refreshNs: Long) {
        val touch get() = kind == InputLatency.TOUCH_DOWN || kind == InputLatency.TOUCH_UP

        companion object {
            const val FIELDS = 9

            fun read(rows: LongArray, index: Int): Row {
                val o = index * FIELDS
                return Row(rows[o].toInt(), rows[o + 1], rows[o + 2], rows[o + 3], rows[o + 4], rows[o + 5], rows[o + 6],
                    rows[o + 7].toInt(), rows[o + 8])
            }
        }
    }

    companion object {
        const val COLUMNS = "t_ms,kind,inject_us,commit_us,present_us,display_us,present_id,swap_pending,refresh_us,cap"
        private const val CAP_NOTE_RESERVE = 96L
    }
}

/** The per-minute input.latency event: medians and 90th percentiles in ms from the Android event. */
internal class LatencySummary {
    private val touchPresent = ArrayList<Long>()
    private val touchDisplay = ArrayList<Long>()
    private val touchCommit = ArrayList<Long>()
    private val touchInject = ArrayList<Long>()
    private val padPresent = ArrayList<Long>()
    private var inputs = 0
    private var unanswered = 0
    private var swapPendingMax = -1
    private var refreshNs = 0L
    private val caps = sortedSetOf<Int>()

    val empty get() = inputs == 0

    fun add(row: LatencyCsvWriter.Row, cap: Int) {
        inputs++
        caps += cap
        if (row.refreshNs > 0) refreshNs = row.refreshNs
        if (row.swapPending > swapPendingMax) swapPendingMax = row.swapPending
        if (row.presentNs <= 0) unanswered++
        fun since(at: Long) = at - row.eventNs
        if (row.touch) {
            if (row.injectNs > 0) touchInject += since(row.injectNs)
            if (row.commitNs > 0) touchCommit += since(row.commitNs)
            if (row.presentNs > 0) touchPresent += since(row.presentNs)
            if (row.displayNs > 0) touchDisplay += since(row.displayNs)
        } else if (row.presentNs > 0) {
            padPresent += since(row.presentNs)
        }
    }

    fun fields(dropped: Int): Map<String, Any> {
        val out = linkedMapOf<String, Any>("inputs" to inputs, "unanswered" to unanswered)
        fun stat(name: String, values: List<Long>) {
            if (values.isEmpty()) return
            out["${name}_n"] = values.size
            out["${name}_p50_ms"] = ms(percentile(values, 50))
            out["${name}_p90_ms"] = ms(percentile(values, 90))
        }
        stat("touch_present", touchPresent)
        stat("touch_display", touchDisplay)
        stat("touch_commit", touchCommit)
        stat("touch_inject", touchInject)
        stat("pad_present", padPresent)
        if (swapPendingMax >= 0) out["swap_pending_max"] = swapPendingMax
        if (refreshNs > 0) out["refresh_hz"] = Math.round(1e9 / refreshNs).toInt()
        out["cap"] = caps.joinToString("/")
        if (dropped > 0) out["dropped"] = dropped
        return out
    }

    companion object {
        /** Nearest-rank percentile. */
        fun percentile(values: List<Long>, p: Int): Long {
            val sorted = values.sorted()
            val rank = Math.ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }

        private fun ms(ns: Long): Double = Math.round(ns / 100_000.0) / 10.0
    }
}
