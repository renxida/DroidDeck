package com.droiddeck.launcher.session

/** The suspend decisions SessionService.updateSuspendPolicy makes, in the order it makes them. */
internal object SessionSuspendPolicy {
    /**
     * How long after game.exited the guest is left running. Freezing it while the game winds down (wineserver, Proton
     * and the launcher still exiting) left the guest stopped mid-exit: guest.exited never came and the hidden session
     * sat SUSPENDED until the rig's timeout. Once the window passes, an idle hidden session suspends as before.
     */
    const val GAME_EXIT_AWAKE_MS = 30_000L

    /** Whether a game exited recently enough ([lastGameExitAt] in elapsedRealtime, negative for never) to keep the guest awake. */
    fun gameExiting(now: Long, lastGameExitAt: Long, windowMs: Long = GAME_EXIT_AWAKE_MS): Boolean =
        lastGameExitAt >= 0 && now >= lastGameExitAt && now - lastGameExitAt < windowMs

    // A game stopped this way does not always run again after the resume (Nov 13: Slay the
    // Spire 2 stayed at 0% CPU behind Steam's spinner), so leaving the app keeps a game running.
    fun gameKeepsAwake(
        hidden: Boolean,
        manualHold: Boolean,
        suspended: Boolean,
        steamMode: Boolean,
        gameRunning: () -> Boolean,
    ): Boolean = hidden && !manualHold && !suspended && steamMode && gameRunning()

    fun manualPauseRequested(
        policy: String,
        hidden: Boolean,
        suspended: Boolean,
        downloadsBlockingSuspend: Boolean,
        gameKeepsAwake: Boolean,
        requested: Boolean,
    ): Boolean = when {
        policy == SessionPrefs.SUSPEND_MANUAL && hidden && !downloadsBlockingSuspend && !gameKeepsAwake -> true
        !hidden && !suspended -> false
        else -> requested
    }

    fun steamPause(
        policy: String,
        steamMode: Boolean,
        hidden: Boolean,
        manualHold: Boolean,
        downloadsBlockingSuspend: Boolean,
        gameKeepsAwake: Boolean,
        manualPauseRequested: Boolean,
        gameExiting: Boolean = false,
    ): Boolean = steamMode && !gameExiting && (manualHold || !downloadsBlockingSuspend && !gameKeepsAwake && when (policy) {
        SessionPrefs.SUSPEND_AUTO, SessionPrefs.SUSPEND_NATIVE -> hidden
        SessionPrefs.SUSPEND_MANUAL -> manualPauseRequested
        else -> false
    })

    /** A game that just exited vetoes every reason, Steam's own sleep and a manual hold included, until [GAME_EXIT_AWAKE_MS] passes. */
    fun shouldSuspend(
        policy: String,
        hidden: Boolean,
        manualHold: Boolean,
        downloadsBlockingSuspend: Boolean,
        gameKeepsAwake: Boolean,
        manualPauseRequested: Boolean,
        prepared: Boolean,
        nativeSleepReady: Boolean,
        steamSleepRequested: Boolean,
        gameExiting: Boolean = false,
    ): Boolean = !gameExiting && (steamSleepRequested || (manualHold && prepared) || when (policy) {
        SessionPrefs.SUSPEND_AUTO -> hidden && prepared && !downloadsBlockingSuspend && !gameKeepsAwake
        SessionPrefs.SUSPEND_NATIVE -> hidden && nativeSleepReady && !downloadsBlockingSuspend && !gameKeepsAwake
        SessionPrefs.SUSPEND_MANUAL -> manualPauseRequested && prepared && !downloadsBlockingSuspend
        else -> false
    })
}
