package com.droiddeck.launcher.session

/** The suspend decisions SessionService.updateSuspendPolicy makes, in the order it makes them. */
internal object SessionSuspendPolicy {
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
    ): Boolean = steamMode && (manualHold || !downloadsBlockingSuspend && !gameKeepsAwake && when (policy) {
        SessionPrefs.SUSPEND_AUTO, SessionPrefs.SUSPEND_NATIVE -> hidden
        SessionPrefs.SUSPEND_MANUAL -> manualPauseRequested
        else -> false
    })

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
    ): Boolean = steamSleepRequested || (manualHold && prepared) || when (policy) {
        SessionPrefs.SUSPEND_AUTO -> hidden && prepared && !downloadsBlockingSuspend && !gameKeepsAwake
        SessionPrefs.SUSPEND_NATIVE -> hidden && nativeSleepReady && !downloadsBlockingSuspend && !gameKeepsAwake
        SessionPrefs.SUSPEND_MANUAL -> manualPauseRequested && prepared && !downloadsBlockingSuspend
        else -> false
    }
}
