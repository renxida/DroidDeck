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

    /** How long a hidden session keeps a running game going before it freezes, unless the session pref says otherwise. */
    const val FREEZE_GAME_AFTER_MS = 10_000L

    // A game stopped this way did not always run again after the resume (Nov 13: Slay the Spire 2,
    // then under Proton, stayed at 0% CPU behind Steam's spinner while it loaded), so a running game
    // keeps the session awake. With [freezeAfterMs] at 0 or more, that lasts only until the session
    // has been hidden that long: the game then freezes with the rest of the session, pages out to
    // zRAM and resumes where it was when the app shows again, instead of running in the background
    // or starting over. A negative [freezeAfterMs] keeps the game running for as long as it is hidden.
    fun gameKeepsAwake(
        hidden: Boolean,
        manualHold: Boolean,
        suspended: Boolean,
        steamMode: Boolean,
        hiddenForMs: Long = 0L,
        freezeAfterMs: Long = -1L,
        gameRunning: () -> Boolean,
    ): Boolean = hidden && !manualHold && !suspended && steamMode &&
        (freezeAfterMs < 0 || hiddenForMs < freezeAfterMs) && gameRunning()

    /** When the game-keeps-awake window above ends, in ms from now, or null when nothing is due. */
    fun freezeGameDueInMs(hidden: Boolean, hiddenForMs: Long, freezeAfterMs: Long): Long? =
        if (!hidden || freezeAfterMs < 0 || hiddenForMs >= freezeAfterMs) null else freezeAfterMs - hiddenForMs

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
