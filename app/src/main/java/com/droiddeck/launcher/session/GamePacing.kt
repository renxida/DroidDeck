package com.droiddeck.launcher.session

import android.util.Log

/**
 * The frame cap droiddeck-game-env gave the running game, read from its `DROIDDECK_PACING` line in
 * session.log, and the cap the thermal governor holds it to now. Three parts follow it, each switched
 * off per game by its variable: the ADPF target duration ([GameThreadHints.setCap]), the display
 * mode and layer frame-rate vote, and the governor, which steps the cap down a ladder (60, 40, 30)
 * while Android's thermal headroom forecast stays high and back up after it stays low for longer.
 */
object GamePacing {
    private const val TAG = "GamePacing"
    const val LINE = "DROIDDECK_PACING "
    private const val EXEC = "DROIDDECK_TIMELINE event=game_exec "
    private const val EXIT = "DROIDDECK_TIMELINE event=game_exit "

    data class Plan(val appId: String, val cap: Int, val adpf: Boolean, val display: Boolean, val governor: Boolean)

    @Volatile var plan: Plan? = null
        private set
    /** The cap in force: the game's own, or the governor's lower step. 0 with no game cap. */
    @Volatile var cap: Int = 0
        private set
    @Volatile var listener: (() -> Unit)? = null

    /** The cap for the display vote, or 0 when there is none or the game turned the vote off. */
    fun displayCap(): Int = plan?.takeIf { it.display }?.let { cap } ?: 0

    /** The cap the compositor holds the output to: only a governor step below the game's own cap. */
    fun holdCap(): Int = plan?.takeIf { it.governor && cap in 1 until it.cap }?.let { cap } ?: 0

    /** The layer and display-mode vote for a Steam session whose compositor limit is [limit]. */
    fun vote(limit: Int, display: Int = displayCap()): Int = when {
        display <= 0 -> limit
        limit > 0 -> minOf(limit, display)
        else -> display
    }

    @Synchronized
    fun publish(next: Plan?, nextCap: Int, why: String) {
        if (next == plan && nextCap == cap) return
        plan = next
        cap = if (next == null) 0 else nextCap
        val line = if (next == null) "pacing: no game frame cap ($why)" else
            "pacing: game ${next.appId} capped at $cap fps of ${next.cap} ($why); ADPF target " +
                (if (next.adpf) "${1000 / cap.coerceAtLeast(1)} ms" else "off") +
                ", display vote ${if (next.display) "$cap fps" else "off"}, thermal governor ${if (next.governor) "on" else "off"}"
        Log.i(TAG, line)
        runCatching { SessionEvents.record("pacing.cap", mapOf("app_id" to next?.appId, "cap" to cap, "game_cap" to next?.cap, "why" to why)) }
        GameThreadHints.setCap(if (next?.adpf == true) cap else 0)
        listener?.let { runCatching { it() } }
    }

    fun parse(text: String): Plan? {
        if (!text.startsWith(LINE)) return null
        val fields = text.substring(LINE.length).trim().split(' ').mapNotNull {
            val at = it.indexOf('=')
            if (at > 0) it.substring(0, at) to it.substring(at + 1) else null
        }.toMap()
        val appId = fields["app_id"]?.takeIf { it.isNotEmpty() } ?: return null
        val cap = fields["cap"]?.toIntOrNull()?.takeIf { it in 1..999 } ?: return null
        return Plan(appId, cap, fields["adpf"] != "0", fields["display"] != "0", fields["governor"] != "0")
    }

    private fun field(text: String, name: String): String? =
        text.split(' ').firstOrNull { it.startsWith("$name=") }?.substring(name.length + 1)

    /**
     * Follows session.log: a pacing line names the next launch's cap, which holds from its exec line
     * until that game's exit line or another game's exec.
     */
    class Tracker {
        var plan: Plan? = null
            private set
        private var pending: Plan? = null

        /** True when [plan] changed. */
        fun line(text: String): Boolean {
            parse(text)?.let { pending = it; return false }
            val before = plan
            if (text.startsWith(EXEC)) {
                val appId = field(text, "app_id")
                plan = pending?.takeIf { it.appId == appId }
                pending = null
            } else if (text.startsWith(EXIT) && field(text, "app_id") == plan?.appId) {
                plan = null
            }
            return plan != before
        }
    }

    /**
     * Steps the cap down while the headroom forecast stays at or above [downAt] (or the thermal status
     * is severe) for [downMs], and back up while it stays at or under [upAt] for [upMs]. A further step
     * down waits [settleMs] after the last one, because a lower cap takes tens of seconds to show in the
     * forecast: with a short dwell the governor read a 40 fps step as having failed and went straight on
     * to 30 (Nov 14, headroom 0.87, status light, while charging). Headroom 1.0 is Android's forecast of
     * severe throttling.
     */
    class Governor(
        top: Int,
        private val downAt: Float = DOWN_AT,
        private val upAt: Float = UP_AT,
        private val downMs: Long = DOWN_MS,
        private val upMs: Long = UP_MS,
        private val settleMs: Long = SETTLE_MS,
    ) {
        val ladder: List<Int> = (listOf(top) + STEPS.filter { it < top }).distinct()
        private var index = 0
        private var hotSince = -1L
        private var coolSince = -1L
        private var steppedAt = Long.MIN_VALUE / 2
        val cap: Int get() = ladder[index]

        /** The new cap when this sample moves it, else null. NaN headroom counts as unknown. */
        fun sample(headroom: Float, status: Int, nowMs: Long): Int? {
            val hot = (!headroom.isNaN() && headroom >= downAt) || status >= STATUS_SEVERE
            val cool = !hot && (headroom.isNaN() || headroom <= upAt) && status < STATUS_MODERATE
            if (!hot) hotSince = -1L else if (hotSince < 0) hotSince = nowMs
            if (!cool) coolSince = -1L else if (coolSince < 0) coolSince = nowMs
            if (hot && index < ladder.size - 1 && nowMs - hotSince >= downMs && (index == 0 || nowMs - steppedAt >= settleMs)) {
                index++
                hotSince = nowMs
                steppedAt = nowMs
                return cap
            }
            if (cool && index > 0 && nowMs - coolSince >= upMs) {
                index--
                coolSince = nowMs
                return cap
            }
            return null
        }
    }

    val STEPS = listOf(60, 40, 30)
    /** 0.85 stepped 60 to 40 at a light status that was holding, halving the frame rate for nothing. */
    const val DOWN_AT = 0.95f
    const val UP_AT = 0.80f
    const val DOWN_MS = 10_000L
    const val UP_MS = 30_000L
    const val SETTLE_MS = 60_000L
    private const val STATUS_MODERATE = 2
    private const val STATUS_SEVERE = 3
}
