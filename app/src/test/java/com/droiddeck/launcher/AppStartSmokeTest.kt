package com.droiddeck.launcher

import android.content.Intent
import android.os.Looper
import androidx.lifecycle.Lifecycle
import com.droiddeck.launcher.session.LauncherRejoin
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 34])
class AppStartSmokeTest {
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var threadsBefore: Set<Thread> = emptySet()

    @Before fun setUp() {
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
        LauncherRejoin.lastShown = LauncherRejoin.Screen.NONE
        // App.onCreate's CrashHandler ends in Runtime.halt when nothing is chained behind it, which would kill the test JVM.
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            uncaught.add(AssertionError("uncaught exception on thread \"${thread.name}\"", error))
        }
        threadsBefore = Thread.getAllStackTraces().keys.toSet()
    }

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        SessionState.running = false
        SessionState.phase = SessionPhase.IDLE
        LauncherRejoin.lastShown = LauncherRejoin.Screen.NONE
    }

    @Test fun launcherEntryStartsResumesAndPausesWithoutCrashing() {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(RuntimeEnvironment.getApplication(), FrontEndActivity::class.java)
        val controller = Robolectric.buildActivity(FrontEndActivity::class.java, launch)
        val activity = controller.get()
        try {
            startResumePause(controller, activity)
        } finally {
            // A failure part way must not leave the launcher's handlers posting on the shared main looper, or every
            // later Compose test in this JVM times out waiting for idle.
            runCatching { if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.pause() }
            runCatching { if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.stop() }
            runCatching { if (activity.lifecycle.currentState != Lifecycle.State.DESTROYED) controller.destroy() }
            runCatching { shadowOf(Looper.getMainLooper()).idle() }
        }
        assertNoUncaughtExceptions()
    }

    // The icon's intent differs from the one that began the task (here a game link, on the rig `am start -n`), so
    // Android stacks a new front screen over the session that was still starting; it must hand straight back.
    @Test fun launcherEntryWhileTheSessionStartsGoesBackToIt() {
        SessionState.phase = SessionPhase.STARTING_STEAM
        LauncherRejoin.lastShown = LauncherRejoin.Screen.SESSION
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(RuntimeEnvironment.getApplication(), FrontEndActivity::class.java)
        val controller = Robolectric.buildActivity(FrontEndActivity::class.java, launch)
        val activity = controller.get()
        shadowOf(activity).setIsTaskRoot(false)
        try {
            controller.create()
            assertTrue(activity.isFinishing)
            assertEquals(SessionActivity::class.java.name, shadowOf(activity).nextStartedActivity?.component?.className)
        } finally {
            runCatching { if (activity.lifecycle.currentState != Lifecycle.State.DESTROYED) controller.destroy() }
            runCatching { shadowOf(Looper.getMainLooper()).idle() }
        }
        assertNoUncaughtExceptions()
    }

    private fun startResumePause(controller: org.robolectric.android.controller.ActivityController<FrontEndActivity>, activity: FrontEndActivity) {
        controller.create().start().postCreate(null).resume().visible()
        activity.onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
        assertFalse(activity.isFinishing)
        assertEquals(LauncherRejoin.Screen.FRONT, LauncherRejoin.lastShown)

        awaitStartupWork()
        shadowOf(Looper.getMainLooper()).idle()
        assertNoUncaughtExceptions()
        assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)

        controller.pause()
        assertEquals(Lifecycle.State.STARTED, activity.lifecycle.currentState)
    }

    private fun assertNoUncaughtExceptions() {
        uncaught.firstOrNull()?.let { throw it }
    }

    // The launcher starts its catalog, component and log housekeeping on plain threads; network ones may still be
    // waiting on a slow host at the deadline, which is not a startup crash.
    private fun awaitStartupWork() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STARTUP_WORK_SECONDS)
        while (System.nanoTime() < deadline) {
            val busy = Thread.getAllStackTraces().keys.any {
                it !in threadsBefore && it !== Thread.currentThread() && it.isAlive &&
                    (it.state == Thread.State.RUNNABLE || it.state == Thread.State.BLOCKED)
            }
            if (!busy) return
            Thread.sleep(50)
        }
    }

    private companion object {
        const val STARTUP_WORK_SECONDS = 20L
    }
}
