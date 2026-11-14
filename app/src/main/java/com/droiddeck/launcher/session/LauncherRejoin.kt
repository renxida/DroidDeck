package com.droiddeck.launcher.session

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Where the launcher icon lands while a session is starting or running.
 *
 * Android brings Lanner's task forward untouched only when the icon's MAIN/LAUNCHER intent matches the intent that
 * started the task. A task begun any other way (a game shortcut or link, `am start -n`, an update or agent prompt) has
 * a different root intent, so the icon stacks a fresh FrontEndActivity on top of the live SessionActivity and the
 * player lands on the library instead of their session. That fresh instance asks [shouldRejoin] and, when the session
 * was the last screen the player saw, hands straight back to it. A player who went to the library on purpose was last
 * on the front screen, so the icon leaves them there.
 */
internal object LauncherRejoin {
    enum class Screen { NONE, FRONT, SESSION }

    /** Which of Lanner's two main screens was last in the foreground; kept by [Tracker]. */
    @Volatile
    var lastShown = Screen.NONE

    private const val ACTION_MAIN = "android.intent.action.MAIN"
    private val LAUNCHER_CATEGORIES = setOf("android.intent.category.LAUNCHER", "android.intent.category.CAR_LAUNCHER")
    private val NOT_STARTED = setOf(SessionPhase.IDLE, SessionPhase.FAILED)

    /** The home screen's icon (or a car launcher's), as opposed to a game link, shortcut or the app's own prompts. */
    fun launcherEntry(action: String?, categories: Set<String>?): Boolean =
        action == ACTION_MAIN && categories.orEmpty().any { it in LAUNCHER_CATEGORIES }

    /** A session the player can go back to: starting (any phase before READY), running or suspended, and not stopping. */
    fun live(running: Boolean, phase: SessionPhase, stopRequested: Boolean): Boolean =
        !stopRequested && phase != SessionPhase.STOPPING && (running || phase !in NOT_STARTED)

    fun shouldRejoin(
        action: String?,
        categories: Set<String>?,
        running: Boolean,
        phase: SessionPhase,
        stopRequested: Boolean,
        pipActive: Boolean,
        lastShown: Screen,
    ): Boolean = launcherEntry(action, categories) && live(running, phase, stopRequested) &&
        !pipActive && lastShown == Screen.SESSION

    /** [shouldRejoin] for the current session. */
    fun shouldRejoin(action: String?, categories: Set<String>?): Boolean = shouldRejoin(
        action, categories, SessionState.running, SessionState.phase, SessionState.stopRequested,
        SessionState.pipActive, lastShown,
    )

    /** Registered by the application; records the front screen or the session each time one of them resumes. */
    object Tracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            when (activity) {
                is com.droiddeck.launcher.FrontEndActivity -> lastShown = Screen.FRONT
                is com.droiddeck.launcher.SessionActivity -> lastShown = Screen.SESSION
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
