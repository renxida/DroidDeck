package com.droiddeck.launcher.session

/**
 * Warns before lmkd is likely to kill the app, and with it Steam and the game. lmkd (system/memory/lmkd,
 * mp_event_psi) only kills a foreground or perceptible app once swap is low (free swap under
 * ro.lmk.swap_free_low_percentage, 10 % by default) together with min-watermark memory or heavy
 * thrashing, or on a complete PSI stall. The app polls far slower than lmkd, so it cannot act in time
 * to stop the game instead; it can only tell the player to save. See docs/research/memory-footprint.md.
 */
object LmkRisk {
    const val SWAP_LOW_PERCENT = 10
    const val PSI_SOME_MIN = 10.0
    const val COOLDOWN_MS = 5 * 60_000L
    const val CLEAR_MS = 60_000L

    data class Reading(
        val totalKb: Long,
        val availableKb: Long,
        val swapTotalKb: Long,
        val swapFreeKb: Long,
        val psiSome: Double?,
        val psiFull: Double?,
    )

    /** lmkd's get_free_swap: free swap, but no more than memory that is easy to reclaim. */
    fun freeSwapKb(r: Reading): Long = minOf(r.swapFreeKb, r.availableKb)

    fun swapLow(r: Reading): Boolean = r.swapTotalKb > 0 && freeSwapKb(r) * 100 < r.swapTotalKb * SWAP_LOW_PERCENT

    /** Low swap plus memory stall; without readable PSI, low swap plus under 5 % of RAM available. */
    fun atRisk(r: Reading): Boolean = swapLow(r) &&
        (r.psiSome?.let { it >= PSI_SOME_MIN } ?: (r.availableKb * 20 < r.totalKb))

    /** One warning per risk episode: an episode ends after [CLEAR_MS] without risk, and warnings are [COOLDOWN_MS] apart. */
    class Gate {
        private var lastWarnMs = Long.MIN_VALUE
        private var lastRiskMs = Long.MIN_VALUE
        private var armed = true

        fun warn(risk: Boolean, nowMs: Long): Boolean {
            if (!risk) {
                if (!armed && lastRiskMs != Long.MIN_VALUE && nowMs - lastRiskMs >= CLEAR_MS) armed = true
                return false
            }
            lastRiskMs = nowMs
            if (!armed || (lastWarnMs != Long.MIN_VALUE && nowMs - lastWarnMs < COOLDOWN_MS)) return false
            armed = false
            lastWarnMs = nowMs
            return true
        }
    }

    fun line(r: Reading): String =
        "DROIDDECK_LMK_RISK action=warn swapFreeMb=${freeSwapKb(r) / 1024} swapTotalMb=${r.swapTotalKb / 1024} " +
            "availableMb=${r.availableKb / 1024} totalMb=${r.totalKb / 1024} " +
            "psiSomeAvg10=${r.psiSome ?: "-"} psiFullAvg10=${r.psiFull ?: "-"}"

    fun fields(r: Reading): Map<String, Any?> = linkedMapOf(
        "swapFreeMb" to freeSwapKb(r) / 1024,
        "swapTotalMb" to r.swapTotalKb / 1024,
        "availableMb" to r.availableKb / 1024,
        "totalMb" to r.totalKb / 1024,
        "psiSomeAvg10" to r.psiSome,
        "psiFullAvg10" to r.psiFull,
    )
}
