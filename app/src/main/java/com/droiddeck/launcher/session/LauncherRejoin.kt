package com.droiddeck.launcher.session

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.lang.ref.WeakReference

/**
 * Where the launcher icon lands while a session is starting or running.
 *
 * Two ways the icon used to show the library instead of the session:
 * - Android brings Lanner's task forward untouched only when the icon's MAIN/LAUNCHER intent matches the intent that
 *   started the task. A task begun any other way (a game shortcut or link, `am start -n`, an update or agent prompt)
 *   has a different root intent, so the icon stacks a fresh FrontEndActivity on top of the live SessionActivity. That
 *   instance asks [shouldRejoin].
 * - Leaving a running game with Home sends SessionActivity into picture-in-picture, which moves it out of Lanner's
 *   task; the icon then brings back that task with the front screen on top. Its onResume asks [shouldExpandPip].
 *
 * Either way the session comes back (out of picture-in-picture, too) when it was the last screen the player saw. A
 * player who went to the library on purpose was last on the front screen, and one who pressed the session's own
 * picture-in-picture button chose to float it over the library, so the icon leaves both where they are.
 */
internal object LauncherRejoin {
    enum class Screen { NONE, FRONT, SESSION }

    /** Which of Lanner's two main screens was last in the foreground; kept by [Tracker]. */
    @Volatile
    var lastShown = Screen.NONE

    /** The player pressed the session's picture-in-picture button, rather than leaving the app; kept by SessionPipController. */
    @Volatile
    var pipChosen = false

    private const val ACTION_MAIN = "android.intent.action.MAIN"
    private val LAUNCHER_CATEGORIES = setOf("android.intent.category.LAUNCHER", "android.intent.category.CAR_LAUNCHER")
    private val NOT_STARTED = setOf(SessionPhase.IDLE, SessionPhase.FAILED)

    /** The home screen's icon (or a car launcher's), as opposed to a game link, shortcut or the app's own prompts. */
    fun launcherEntry(action: String?, categories: Set<String>?): Boolean =
        action == ACTION_MAIN && categories.orEmpty().any { it in LAUNCHER_CATEGORIES }

    /** A session the player can go back to: starting (any phase before READY), running or suspended, and not stopping. */
    fun live(running: Boolean, phase: SessionPhase, stopRequested: Boolean): Boolean =
        !stopRequested && phase != SessionPhase.STOPPING && (running || phase !in NOT_STARTED)

    /** A new front screen opened by the launcher icon. */
    fun shouldRejoin(
        action: String?,
        categories: Set<String>?,
        running: Boolean,
        phase: SessionPhase,
        stopRequested: Boolean,
        inPip: Boolean,
        pipChosen: Boolean,
        lastShown: Screen,
    ): Boolean = launcherEntry(action, categories) && live(running, phase, stopRequested) &&
        !(inPip && pipChosen) && lastShown == Screen.SESSION

    /**
     * The front screen came forward (launcher icon or recents) while the session floats in picture-in-picture after
     * the player left the app. Asked before the front screen's own resume is recorded as [lastShown].
     */
    fun shouldExpandPip(
        running: Boolean,
        phase: SessionPhase,
        stopRequested: Boolean,
        inPip: Boolean,
        pipChosen: Boolean,
        lastShown: Screen,
    ): Boolean = inPip && !pipChosen && live(running, phase, stopRequested) && lastShown == Screen.SESSION

    /** [shouldRejoin] for the current session. */
    fun shouldRejoin(action: String?, categories: Set<String>?): Boolean = shouldRejoin(
        action, categories, SessionState.running, SessionState.phase, SessionState.stopRequested,
        sessionInPip(), pipChosen, lastShown,
    )

    /** [shouldExpandPip] for the current session. */
    fun shouldExpandPip(): Boolean = shouldExpandPip(
        SessionState.running, SessionState.phase, SessionState.stopRequested, sessionInPip(), pipChosen, lastShown,
    )

    private fun sessionInPip(): Boolean = SessionState.pipActive ||
        Tracker.session?.get()?.let { !it.isFinishing && !it.isDestroyed && it.isInPictureInPictureMode } == true

    /** Registered by the application; records the front screen or the session each time one of them resumes. */
    object Tracker : Application.ActivityLifecycleCallbacks {
        @Volatile
        var session: WeakReference<Activity>? = null
            private set

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is com.droiddeck.launcher.SessionActivity) session = WeakReference(activity)
        }

        override fun onActivityResumed(activity: Activity) {
            when (activity) {
                is com.droiddeck.launcher.FrontEndActivity -> lastShown = Screen.FRONT
                is com.droiddeck.launcher.SessionActivity -> {
                    lastShown = Screen.SESSION
                    session = WeakReference(activity)
                }
            }
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (session?.get() === activity) session = null
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
