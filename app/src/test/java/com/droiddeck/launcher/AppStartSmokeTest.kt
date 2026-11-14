package com.droiddeck.launcher

import android.content.Intent
import android.os.Looper
import androidx.lifecycle.Lifecycle
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionState
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    }

    @Test fun launcherEntryStartsResumesAndPausesWithoutCrashing() {
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClass(RuntimeEnvironment.getApplication(), FrontEndActivity::class.java)
        val controller = Robolectric.buildActivity(FrontEndActivity::class.java, launch)
        val activity = controller.get()

        controller.create().start().postCreate(null).resume().visible()
        activity.onWindowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)
        assertFalse(activity.isFinishing)

        awaitStartupWork()
        shadowOf(Looper.getMainLooper()).idle()
        assertNoUncaughtExceptions()
        assertEquals(Lifecycle.State.RESUMED, activity.lifecycle.currentState)

        controller.pause()
        assertEquals(Lifecycle.State.STARTED, activity.lifecycle.currentState)
        controller.stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertNoUncaughtExceptions()
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
